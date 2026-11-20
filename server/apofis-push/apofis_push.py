#!/usr/bin/env python3
"""Apofis push hook for Hermes: a contentless UnifiedPush wake through ntfy. See README.md.

`hook` (run by Hermes from its hooks: block) reserves one of two slots, spawns one detached `send`
child and returns at once. The child reads the app's registration records from Hermes' state
database read-only, closes it, and POSTs {"session_id","kind"} to each validated endpoint.
`check` reports configuration and records without any network. Python 3.9+ standard library.
"""
from __future__ import annotations

import fcntl
import json
import os
import re
import signal
import socket
import sqlite3
import ssl
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request

UUID = r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
UUID_RE = re.compile(UUID)
RECORD_RE = re.compile(r"apofis-push-(%s)-([1-9][0-9]{0,5})" % UUID)
ROOT_RE = re.compile(r"apofis-(?:tmp-)?%s" % UUID)
RESERVED = ("apofis-push-", "apofis-probe-")
HTTPS_ORIGIN_RE = re.compile(r"https://[a-z0-9-]+(?:\.[a-z0-9-]+)*")
HTTP_ORIGIN_RE = re.compile(r"http://127\.0\.0\.1:([1-9][0-9]{0,4})")
PATH_RE = re.compile(r"/up[A-Za-z0-9_-]{12}")
URL_CHARS_RE = re.compile(r"[!#-\[\]-~]*")  # printable ASCII without space, quote or backslash
FORK_MARKERS = ("_branched_from", "_delegate_from", "_reset_from")

TTL = 90 * 86400
MAX_REGS = 32
MAX_URL = 512
MAX_PROMPT = 1024
STDIN_MAX = 1 << 20
CHILD_DEADLINE = 25
DB_BUDGET = 1.5
LOG_MAX = 256 * 1024
LOG_KEEP = 128 * 1024
LOG_LOCK_WAIT = 1.0
HOOKS = {"finished": "on_session_end", "needs_input": "on_human_input_request"}
CHILD_ENV = ("HERMES_HOME", "PATH", "SSL_CERT_FILE", "LANG")
VALUE_OPTS = {"--allow-origin", "--allow-http-origin", "--state-db", "--log", "--timeout", "--delay",
              "--only-install", "--cafile", "--session", "--kind", "--slot-fd"}
SEND_ONLY = {"--session", "--kind", "--slot-fd"}

# Narrow ranking over the primary-key range; canonical ids are checked before ranking so invalid rows
# never take a slot. LIMIT 33 (not 32) only so the log can say "regs=32+".
RANK_SQL = """
SELECT id, started_at FROM (
  SELECT id, started_at, ROW_NUMBER() OVER (PARTITION BY substr(id, 13, 36)
                                 ORDER BY CAST(substr(id, 50) AS INTEGER) DESC) AS rn
  FROM (SELECT id, started_at FROM sessions
        WHERE id >= 'apofis-push-' AND id < 'apofis-push.'
          AND source = 'api_server' AND hidden = 1 AND started_at >= :cutoff
          AND length(id) BETWEEN 50 AND 55 AND substr(id, 49, 1) = '-'
          AND substr(id, 13, 36) GLOB '[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]-[0-9a-f][0-9a-f][0-9a-f][0-9a-f]-[0-9a-f][0-9a-f][0-9a-f][0-9a-f]-[0-9a-f][0-9a-f][0-9a-f][0-9a-f]-[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]'
          AND substr(id, 50) GLOB '[1-9]*' AND substr(id, 50) NOT GLOB '*[^0-9]*'
          AND (:install IS NULL OR substr(id, 13, 36) = :install)
        ORDER BY started_at DESC LIMIT 4096))
WHERE rn = 1 ORDER BY started_at DESC LIMIT 33
"""
PROMPT_SQL = ("SELECT s.id, substr(COALESCE(sp.prompt, s.system_prompt), 1, 1025) FROM sessions s "
              "LEFT JOIN system_prompts sp ON sp.hash = s.system_prompt_hash WHERE s.id IN (%s)")
ROW_SQL = "SELECT id, source, hidden, parent_session_id, end_reason, model_config FROM sessions WHERE id = ?"


class Usage(Exception):
    pass


class NoDb(Exception):
    pass


class Budget(Exception):
    pass


def parse_args(argv: list[str]) -> dict:
    if not argv or argv[0] not in ("hook", "send", "check"):
        raise Usage
    o: dict = {"cmd": argv[0], "origins": [], "http_origins": [], "forward": [], "--ids": False}
    it = iter(argv[1:])
    for a in it:
        if a == "--ids":
            o["--ids"] = True
            continue
        v = next(it, None)
        if a not in VALUE_OPTS or v is None:
            raise Usage
        if a == "--allow-origin":
            if not HTTPS_ORIGIN_RE.fullmatch(v):
                raise Usage
            o["origins"].append(v)
        elif a == "--allow-http-origin":
            m = HTTP_ORIGIN_RE.fullmatch(v)
            if not m or int(m.group(1)) > 65535:
                raise Usage
            o["http_origins"].append(v)
        elif a in o:
            raise Usage
        else:
            o[a] = v
        if a not in SEND_ONLY:
            o["forward"] += [a, v]
    o["timeout"] = float(o.get("--timeout", 5))
    o["delay"] = float(o.get("--delay", 2))
    if not (0 < o["timeout"] <= 10 and 0 <= o["delay"] <= 10):
        raise Usage
    if "--only-install" in o and not UUID_RE.fullmatch(o["--only-install"]):
        raise Usage
    if o["cmd"] == "send":
        if o.get("--kind") not in HOOKS or "--session" not in o:
            raise Usage
        o["slot_fd"] = int(o["--slot-fd"]) if "--slot-fd" in o else None
    elif SEND_ONLY & set(o):
        raise Usage
    if o["--ids"] and (o["cmd"] != "check" or "--only-install" not in o):
        raise Usage
    return o


# --- Parent: Hermes' hook -------------------------------------------------------------------------

def hook_target(event: dict) -> tuple[str, str] | None:
    """(session id or "" for an anonymous approval, kind) to push, or None."""
    if not isinstance(event, dict):
        return None
    extra = event.get("extra")
    extra = extra if isinstance(extra, dict) else {}
    platform = extra.get("platform")
    platform = platform if isinstance(platform, str) else ""
    if platform and platform != "api_server":
        return None
    sid = event.get("session_id")
    sid = sid if isinstance(sid, str) and len(sid) <= 256 else ""
    name = event.get("hook_event_name")
    if name == "on_session_end":
        if sid and (extra.get("completed") is True or extra.get("failed") is True):
            return sid, "finished"
    elif name == "on_human_input_request":
        kind = extra.get("kind")
        if kind == "approval" and (sid or platform == "api_server"):
            return sid, "needs_input"
        if kind == "clarify" and sid:
            return sid, "needs_input"
    return None


def reserve_slot(home: str, lock) -> int | None:
    for i in (0, 1):
        fd = os.open(os.path.join(home, "apofis-push.slot%d.lock" % i), os.O_RDWR | os.O_CREAT | os.O_CLOEXEC, 0o600)
        try:
            lock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return fd
        except OSError:
            os.close(fd)
    return None


def run_hook(o: dict, home: str, stdin, env, spawn, lock) -> int:
    try:
        data = stdin.read(STDIN_MAX + 1)
        if len(data) > STDIN_MAX:
            return 0
        target = hook_target(json.loads(data))
        if target is None:
            return 0
        fd = reserve_slot(home, lock)
        if fd is None:
            return 0
        try:
            child_env = {k: env[k] for k in CHILD_ENV if k in env}
            child_env["HERMES_HOME"] = home
            spawn([sys.executable, "-I", os.path.abspath(__file__), "send", "--session", target[0],
                   "--kind", target[1], "--slot-fd", str(fd), *o["forward"]],
                  stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                  start_new_session=True, close_fds=True, pass_fds=(fd,), env=child_env)
        finally:
            os.close(fd)
    except BaseException:  # never break or slow a Hermes run
        pass
    return 0


# --- Database (read-only snapshot) ----------------------------------------------------------------

def open_ro(db: str, monotonic) -> sqlite3.Connection:
    """Read-only, query_only, with a monotonic progress budget. Refuses when the WAL sidecars are
    missing, so the open never creates files (Hermes holds them while it runs)."""
    if not (os.path.exists(db + "-wal") and os.path.exists(db + "-shm")):
        raise NoDb
    conn = sqlite3.connect("file:%s?mode=ro" % urllib.parse.quote(os.path.abspath(db)), uri=True, timeout=2,
                           isolation_level=None)
    try:
        end = monotonic() + DB_BUDGET
        conn.set_progress_handler(lambda: 1 if monotonic() > end else 0, 1000)
        conn.execute("PRAGMA query_only=1")
    except BaseException:
        conn.close()
        raise
    return conn


def _markers(model_config) -> tuple:
    try:
        cfg = json.loads(model_config) if isinstance(model_config, str) else None
    except ValueError:
        cfg = None  # Hermes treats unparsable model_config as no fork markers
    return tuple(cfg.get(k) for k in FORK_MARKERS) if isinstance(cfg, dict) else ()


def resolve_app_session(conn, session_id: str, cap: int = 1000) -> str | None:
    """Lineage root along true compression-continuation edges (H11), every row on the path a visible,
    non-reserved api_server row, the root an app id; None otherwise (cycle, cap, foreign row)."""
    seen: set = set()
    row = conn.execute(ROW_SQL, (session_id,)).fetchone()
    while row is not None:
        rid, source, hidden, parent_id = row[:4]
        if (not isinstance(rid, str) or source != "api_server" or hidden != 0 or rid.startswith(RESERVED)
                or rid in seen or len(seen) > cap):
            return None
        seen.add(rid)
        parent = conn.execute(ROW_SQL, (parent_id,)).fetchone() if parent_id else None
        if parent is None or parent[4] != "compression" or parent[0] in _markers(row[5]):
            return rid if ROOT_RE.fullmatch(rid) else None
        row = parent
    return None


def parse_prompt(text) -> str | None:
    if not isinstance(text, str) or len(text) > MAX_PROMPT:
        return None
    try:
        obj = json.loads(text)
    except ValueError:
        return None
    if (isinstance(obj, dict) and set(obj) == {"v", "endpoint"} and type(obj["v"]) is int and obj["v"] == 1
            and isinstance(obj["endpoint"], str)):
        return obj["endpoint"]
    return None


def registration_rows(conn, now: float, only_install: str | None) -> tuple[list, bool]:
    """([(record id, started_at, endpoint or None)] at most 32, more than 32 ranked)."""
    ranked = conn.execute(RANK_SQL, {"cutoff": now - TTL, "install": only_install}).fetchall()
    top = ranked[:MAX_REGS]
    prompts = dict(conn.execute(PROMPT_SQL % ",".join("?" * len(top)), [r[0] for r in top]).fetchall()) if top else {}
    return [(rid, started, parse_prompt(prompts.get(rid))) for rid, started in top], len(ranked) > MAX_REGS


def load_registrations(conn, now: float, only_install: str | None) -> list[tuple[str, str]]:
    return [(rid, ep) for rid, _s, ep in registration_rows(conn, now, only_install)[0] if ep is not None]


# --- Endpoint rule and sending --------------------------------------------------------------------

def validate_endpoint(url: str, origins: list[str], http_origins: list[str]) -> tuple[str, str] | str:
    """(origin label, rebuilt URL) or a reason code. One rule shared with the app (vectors.json)."""
    if not isinstance(url, str) or len(url) > MAX_URL:
        return "length"
    if not URL_CHARS_RE.fullmatch(url):
        return "parse"
    scheme, sep, rest = url.partition("://")
    if not sep or scheme not in ("https", "http") or (scheme == "http" and not http_origins):
        return "scheme"
    authority = re.match(r"[^/?#]*", rest).group(0)
    if "@" in authority:
        return "userinfo"
    origin = "%s://%s" % (scheme, authority)
    if origin not in (origins if scheme == "https" else http_origins):
        return "origin"
    tail = rest[len(authority):]
    if "#" in tail:
        return "fragment"
    path, q, query = tail.partition("?")
    if not PATH_RE.fullmatch(path):
        return "path"
    if q and query != "up=1":
        return "query"
    return origin, origin + path + ("?up=1" if q else "")


def push_body(session_id: str | None, kind: str) -> bytes:
    body = {"session_id": session_id, "kind": kind} if session_id else {"kind": kind}
    return json.dumps(body, separators=(",", ":")).encode()


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None  # urllib then raises HTTPError with the 3xx code: never followed


def build_opener(cafile: str | None):
    return urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect(),
                                       urllib.request.HTTPSHandler(context=ssl.create_default_context(cafile=cafile)))


def classify(exc: BaseException) -> str:
    if isinstance(exc, urllib.error.URLError) and isinstance(exc.reason, BaseException):
        exc = exc.reason
    if isinstance(exc, ssl.SSLError):
        return "tls"
    if isinstance(exc, (socket.timeout, TimeoutError)):
        return "timeout"
    if isinstance(exc, socket.gaierror):
        return "dns"
    if isinstance(exc, ConnectionError):
        return "connect"
    if isinstance(exc, OSError):
        return "net"
    return "error"


def post(url: str, body: bytes, timeout: float, opener) -> str:
    req = urllib.request.Request(url, data=body, method="POST",
                                 headers={"Content-Type": "application/json", "X-UnifiedPush": "1"})
    try:
        with opener.open(req, timeout=timeout) as resp:
            resp.read(1024)
            return str(resp.status)
    except urllib.error.HTTPError as e:
        try:
            e.close()
        except Exception:
            pass
        return str(e.code)
    except Exception as e:
        return classify(e)


# --- Log ------------------------------------------------------------------------------------------

def write_log(path: str, line: str, lock=fcntl.flock, monotonic=time.monotonic, sleep=time.sleep) -> None:
    """Append one line under <log>.lock (non-blocking polls, 1 s cap: skipped on timeout); rotate over
    LOG_MAX keeping the last LOG_KEEP bytes. Never raises."""
    if path == "-":
        sys.stdout.write(line + "\n")
        return
    try:
        lfd = os.open(path + ".lock", os.O_RDWR | os.O_CREAT | os.O_CLOEXEC, 0o600)
    except OSError:
        return
    try:
        end = monotonic() + LOG_LOCK_WAIT
        while True:
            try:
                lock(lfd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except OSError:
                if monotonic() >= end:
                    return
                sleep(0.01)
        fd = os.open(path, os.O_RDWR | os.O_CREAT | os.O_APPEND | os.O_CLOEXEC, 0o600)
        try:
            os.fchmod(fd, 0o600)
            os.write(fd, (line + "\n").encode())
            size = os.fstat(fd).st_size
            if size > LOG_MAX:
                os.lseek(fd, size - LOG_KEEP, os.SEEK_SET)
                tail = os.read(fd, LOG_KEEP)
                tail = tail[tail.find(b"\n") + 1:]
                os.ftruncate(fd, 0)
                os.write(fd, tail)
        finally:
            os.close(fd)
    except OSError:
        pass
    finally:
        os.close(lfd)


def deadline_handler(log: str, hook: str, exit=os._exit):
    def handler(signum, frame):
        write_log(log, "%s %s deadline" % (utc(time.time()), hook))
        exit(0)
    return handler


def utc(t: float) -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(t))


# --- Child: send ----------------------------------------------------------------------------------

def deliver(o: dict, home: str, now, monotonic, sleep, opener) -> str:
    db = o.get("--state-db") or os.path.join(home, "state.db")
    session = o["--session"] or None
    try:
        conn = open_ro(db, monotonic)
        try:
            conn.execute("BEGIN")
            app = resolve_app_session(conn, session) if session else None
            rows, more = registration_rows(conn, now(), o.get("--only-install")) if app or not session else ([], 0)
        finally:
            conn.close()  # before the delay and before any network
    except sqlite3.OperationalError as e:
        return "skipped db-budget" if "interrupt" in str(e) else "skipped db"
    except Exception:
        return "skipped db"
    if session and not app:
        return "skipped session"
    if o["--kind"] == "finished" and o["delay"]:
        sleep(o["delay"])
    try:
        opener = opener or build_opener(o.get("--cafile"))
    except Exception:
        return "skipped tls"
    body = push_body(app, o["--kind"])
    results: list = [None] * len(rows)
    threads = []
    for i, (_rid, _started, endpoint) in enumerate(rows):
        v = validate_endpoint(endpoint, o["origins"], o["http_origins"]) if endpoint is not None else "prompt"
        if isinstance(v, str):
            results[i] = "refused:%s=skipped" % v
            continue

        def work(i=i, label=v[0], url=v[1]):
            results[i] = "%s=%s" % (label, post(url, body, o["timeout"], opener))
        t = threading.Thread(target=work, daemon=True)
        t.start()
        threads.append(t)
    for t in threads:
        t.join()
    return " ".join(["matched regs=%d%s" % (len(rows), "+" if more else "")] + [r or "error" for r in results])


def run_send(o: dict, home: str, now, monotonic, sleep, opener) -> int:
    log = o.get("--log") or os.path.join(home, "apofis-push.log")
    hook = HOOKS[o["--kind"]]
    old = signal.signal(signal.SIGALRM, deadline_handler(log, hook))
    signal.alarm(CHILD_DEADLINE)
    try:
        if o["slot_fd"] is not None:
            try:
                os.ftruncate(o["slot_fd"], 0)
                os.pwrite(o["slot_fd"], str(os.getpid()).encode(), 0)
            except OSError:
                pass
        try:
            line = deliver(o, home, now, monotonic, sleep, opener)
        except Exception:
            line = "skipped error"
        write_log(log, "%s %s %s" % (utc(now()), hook, line), monotonic=monotonic, sleep=sleep)
    finally:
        signal.alarm(0)
        signal.signal(signal.SIGALRM, old)
        if o["slot_fd"] is not None:
            try:
                os.close(o["slot_fd"])  # releases the slot
            except OSError:
                pass
    return 0


# --- check ----------------------------------------------------------------------------------------

def run_check(o: dict, home: str, env, now, monotonic, out) -> int:
    def p(s):
        out.write(s + "\n")
    p("python %s" % sys.version.split()[0])
    p("SSL_CERT_FILE %s" % ("set" if env.get("SSL_CERT_FILE") else "unset"))
    p("origins %s" % (" ".join(o["origins"] + o["http_origins"]) or "none"))
    db = o.get("--state-db") or os.path.join(home, "state.db")
    try:
        conn = open_ro(db, monotonic)
    except Exception:
        p("skipped db")
        return 3
    try:
        conn.execute("BEGIN")
        if o["--ids"]:
            prefix = "apofis-push-%s-" % o["--only-install"]
            rows = conn.execute("SELECT id, title, source, hidden FROM sessions WHERE id >= ? AND id < ? ORDER BY id",
                                (prefix, prefix[:-1] + ".")).fetchall()
        else:
            rows, more = registration_rows(conn, now(), o.get("--only-install"))
    except Exception:
        p("skipped db")
        return 3
    finally:
        conn.close()
    p("db opened read-only")
    if o["--ids"]:
        for row in rows:
            p("\t".join(re.sub(r"[\x00-\x1f\x7f]", "?", str(c)) for c in row))
        return 0
    for rid, started, endpoint in rows:
        m = RECORD_RE.fullmatch(rid)
        v = validate_endpoint(endpoint, o["origins"], o["http_origins"]) if endpoint is not None else "prompt"
        state = "refused %s" % v if isinstance(v, str) else "valid %s" % v[0]
        p("apofis-push-%s...-%s %s age=%dd" % (m.group(1)[:8], m.group(2), state, max(0, now() - started) // 86400))
    p("regs=%d%s" % (len(rows), "+" if more else ""))
    return 0


def main(argv, stdin, env, now=time.time, spawn=subprocess.Popen, opener=None, lock=fcntl.flock,
         monotonic=time.monotonic, sleep=time.sleep, stdout=None) -> int:
    try:
        o = parse_args(list(argv))
    except Exception:
        return 2
    home = env.get("HERMES_HOME") or os.path.join(env.get("HOME") or os.path.expanduser("~"), ".hermes")
    if o["cmd"] == "hook":
        return run_hook(o, home, stdin, env, spawn, lock)
    if o["cmd"] == "send":
        return run_send(o, home, now, monotonic, sleep, opener)
    return run_check(o, home, env, now, monotonic, stdout or sys.stdout)


if __name__ == "__main__":
    try:
        code = main(sys.argv[1:], sys.stdin.buffer, os.environ)
    except BaseException:
        code = 0
    sys.exit(code)
