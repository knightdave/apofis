#!/usr/bin/env python3
"""Apofis push hook for Hermes: a contentless UnifiedPush wake through ntfy. See README.md.

`hook` (run by Hermes from its hooks: block) reserves one of two slots, spawns one detached `send`
child and returns at once; it does no work on the database. The child reads the app's registration
records with a read-only SQLite connection, closes it, and POSTs {"session_id","kind"} to each
validated endpoint; the kernel ends it at CHILD_HARD seconds whatever it is doing. `check` reports
configuration and records without any network. Python 3.9+ standard library, Linux.
"""
from __future__ import annotations

import signal
import sys

CHILD_HARD = 25  # seconds; the kernel ends a `send` child here (default SIGALRM action, no Python code)


def arm_hard_deadline() -> None:
    signal.signal(signal.SIGALRM, signal.SIG_DFL)
    signal.alarm(CHILD_HARD)
    signal.pthread_sigmask(signal.SIG_UNBLOCK, {signal.SIGALRM})  # an inherited blocked mask must not defeat it


if __name__ == "__main__" and sys.argv[1:2] == ["send"]:
    arm_hard_deadline()  # first, before any other import or work

import fcntl  # noqa: E402
import json  # noqa: E402
import os  # noqa: E402
import re  # noqa: E402
import socket  # noqa: E402
import sqlite3  # noqa: E402
import ssl  # noqa: E402
import stat  # noqa: E402
import subprocess  # noqa: E402
import threading  # noqa: E402
import time  # noqa: E402
import urllib.error  # noqa: E402
import urllib.parse  # noqa: E402
import urllib.request  # noqa: E402

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
MAX_MODEL_CONFIG = 65536
MAX_SESSION_ID = 256
STDIN_MAX = 1 << 20
SOFT_DEADLINE = 20  # after this the child starts no new POST and reports "deadline"
DB_BUDGET = 1.5  # SQLite progress-handler budget for the whole read
DB_BUSY = 0.5  # SQLite busy timeout
LOG_MAX = 256 * 1024
LOG_KEEP = 128 * 1024
LOG_LOCK_WAIT = 1.0
HOOKS = {"finished": "on_session_end", "needs_input": "on_human_input_request"}
CHILD_ENV = ("HERMES_HOME", "PATH", "SSL_CERT_FILE", "LANG")
VALUE_OPTS = {"--allow-origin", "--allow-http-origin", "--state-db", "--log", "--timeout", "--delay",
              "--only-install", "--cafile", "--session", "--kind", "--slot-fd"}
SEND_ONLY = {"--session", "--kind", "--slot-fd"}

# Narrow ranking over the primary-key range; canonical ids (byte length, so no embedded NUL) are
# checked before ranking so invalid rows never take a slot. LIMIT 33 only so the log can say "32+".
RANK_SQL = """
SELECT id, started_at FROM (
  SELECT id, started_at, ROW_NUMBER() OVER (PARTITION BY substr(id, 13, 36)
                                 ORDER BY CAST(substr(id, 50) AS INTEGER) DESC) AS rn
  FROM (SELECT id, started_at FROM sessions
        WHERE id >= 'apofis-push-' AND id < 'apofis-push.'
          AND source = 'api_server' AND hidden = 1 AND started_at >= :cutoff
          AND length(CAST(id AS BLOB)) BETWEEN 50 AND 55 AND length(id) = length(CAST(id AS BLOB))
          AND substr(id, 49, 1) = '-'
          AND substr(id, 13, 36) GLOB '[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]-[0-9a-f][0-9a-f][0-9a-f][0-9a-f]-[0-9a-f][0-9a-f][0-9a-f][0-9a-f]-[0-9a-f][0-9a-f][0-9a-f][0-9a-f]-[0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]'
          AND substr(id, 50) GLOB '[1-9]*' AND substr(id, 50) NOT GLOB '*[^0-9]*'
          AND (:install IS NULL OR substr(id, 13, 36) = :install)
        ORDER BY started_at DESC LIMIT 4096))
WHERE rn = 1 ORDER BY started_at DESC LIMIT 33
"""
PROMPT_SQL = ("SELECT s.id, substr(CAST(COALESCE(sp.prompt, s.system_prompt) AS BLOB), 1, 1025) FROM sessions s "
              "LEFT JOIN system_prompts sp ON sp.hash = s.system_prompt_hash WHERE s.id IN (%s)")
ROW_SQL = ("SELECT id, source, hidden, parent_session_id, end_reason, "
           "substr(CAST(model_config AS BLOB), 1, %d) FROM sessions WHERE id = ?" % (MAX_MODEL_CONFIG + 1))


class Usage(Exception):
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
        elif a in o or (a == "--log" and v != "-"):  # the log location is fixed; only stdout is selectable
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
        # Only bodies inside the contract: an anonymous wake exists only for needs_input.
        if o.get("--kind") not in HOOKS or "--session" not in o or (o["--kind"] == "finished" and not o["--session"]):
            raise Usage
        o["slot_fd"] = int(o["--slot-fd"]) if "--slot-fd" in o else None
    elif SEND_ONLY & set(o):
        raise Usage
    if o["--ids"] and (o["cmd"] != "check" or "--only-install" not in o):
        raise Usage
    return o


# --- Our own files: $HERMES_HOME/agent-hooks, opened relative to one directory descriptor ----------

SLOTS = ("apofis-push.slot0.lock", "apofis-push.slot1.lock")


def db_path(o: dict, home: str) -> str:
    return os.path.abspath(o.get("--state-db") or os.path.join(home, "state.db"))


def state_dir(home: str) -> str:
    return os.path.join(home, "agent-hooks")  # created by the owner's install


def log_path(o: dict, home: str) -> str:
    return o.get("--log") or os.path.join(state_dir(home), "apofis-push.log")


def db_ids(db: str) -> set:
    """(device, inode) of the database and its sidecars; only the child looks at the database."""
    ids = set()
    for p in (db, db + "-wal", db + "-shm", db + "-journal"):
        try:
            st = os.stat(p)
            ids.add((st.st_dev, st.st_ino))
        except OSError:
            pass
    return ids


def open_dir(path: str) -> int:
    return os.open(path, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC)


def private_ok(st, bad) -> bool:
    return (stat.S_ISREG(st.st_mode) and st.st_nlink == 1 and st.st_uid == os.geteuid()
            and (st.st_dev, st.st_ino) not in bad)


def open_private(dfd: int, name: str, flags: int, bad=frozenset()) -> int:
    """`name` in the directory `dfd`, checked before any write: a regular file we own with one link and
    no database identity; never follows a symlink, never truncates on open, never blocks on a FIFO."""
    fd = os.open(name, flags | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC, 0o600, dir_fd=dfd)
    if not private_ok(os.fstat(fd), bad):
        os.close(fd)
        raise OSError("refused file")
    return fd


# --- Parent: Hermes' hook -------------------------------------------------------------------------

def hook_target(event: dict) -> tuple[str, str] | None:
    """(session id or "" for an anonymous approval, kind) to push, or None. A present but invalid
    session id drops the event; only an absent or empty one can become an anonymous approval."""
    if not isinstance(event, dict):
        return None
    extra = event.get("extra")
    extra = extra if isinstance(extra, dict) else {}
    platform = extra.get("platform")
    platform = platform if isinstance(platform, str) else ""
    if platform and platform != "api_server":
        return None
    sid = event.get("session_id", "")  # missing is anonymous; a present null is invalid
    if not isinstance(sid, str) or len(sid) > MAX_SESSION_ID or "\x00" in sid:
        return None
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
    dfd = open_dir(state_dir(home))
    try:
        for name in SLOTS:
            fd = open_private(dfd, name, os.O_RDWR | os.O_CREAT)
            try:
                lock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except OSError:
                os.close(fd)
                continue
            return fd  # unchanged here: only the child, after its identity checks, writes to it
        return None
    finally:
        os.close(dfd)


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


# --- Database (child only) ------------------------------------------------------------------------

def connect_ro(db: str) -> sqlite3.Connection:
    """The only way the script opens the database: read-only URI (never creates, never writable), and
    query_only on top."""
    conn = sqlite3.connect("file:%s?mode=ro" % urllib.parse.quote(db), uri=True, timeout=DB_BUSY,
                           isolation_level=None)
    conn.execute("PRAGMA query_only=1")
    return conn


def snapshot(db: str, work, monotonic, on_progress=None):
    """One read transaction with a plain read-only connection (`mode=ro`, query_only, short busy
    timeout) inside a monotonic budget, checked by SQLite's progress handler and before the result is
    accepted."""
    end = monotonic() + DB_BUDGET

    def progress():
        if on_progress:
            on_progress()
        return 1 if monotonic() > end else 0
    conn = connect_ro(db)
    try:
        conn.set_progress_handler(progress, 1000)
        conn.execute("BEGIN")
        result = work(conn)
        conn.execute("COMMIT")
    except sqlite3.OperationalError as e:
        if "interrupt" in str(e):
            raise Budget
        raise
    finally:
        conn.close()
    if monotonic() > end:
        raise Budget
    return result


def read(db: str, work, monotonic) -> tuple[str, object]:
    """("ok", result) or (reason, None): db-budget, db-busy (busy timeout) or db."""
    try:
        return "ok", snapshot(db, work, monotonic)
    except Budget:
        return "db-budget", None
    except sqlite3.OperationalError as e:
        return ("db-busy" if "locked" in str(e) else "db"), None
    except Exception:
        return "db", None


def _markers(model_config) -> tuple | None:
    """Fork markers, () for none, None when the value is too large to judge (fail closed)."""
    if model_config is None:
        return ()
    if len(model_config) > MAX_MODEL_CONFIG:
        return None
    try:
        cfg = json.loads(model_config.decode("utf-8"))
    except ValueError:
        return ()  # Hermes treats unparsable model_config as no fork markers
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
        if parent is None or parent[4] != "compression":
            return rid if ROOT_RE.fullmatch(rid) else None
        markers = _markers(row[5])
        if markers is None:
            return None
        if parent[0] in markers:
            return rid if ROOT_RE.fullmatch(rid) else None
        row = parent
    return None


def parse_prompt(raw) -> str | None:
    """Endpoint from the whole stored prompt (bytes, at most MAX_PROMPT, strict UTF-8, exact shape)."""
    if not isinstance(raw, bytes) or len(raw) > MAX_PROMPT:
        return None
    try:
        obj = json.loads(raw.decode("utf-8"))
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

def write_log(path: str, line: str, bad=frozenset(), lock=fcntl.flock, monotonic=time.monotonic,
              sleep=time.sleep) -> None:
    """Append one line under <log>.lock (non-blocking polls, 1 s cap: skipped on timeout); rotate over
    LOG_MAX keeping the last LOG_KEEP bytes. Private files only (open_private). Never raises."""
    if path == "-":
        sys.stdout.write(line + "\n")
        sys.stdout.flush()  # the CLI ends with os._exit, which does not flush
        return
    try:
        dfd = open_dir(os.path.dirname(path))
    except OSError:
        return
    lfd = fd = -1
    try:
        name = os.path.basename(path)
        lfd = open_private(dfd, name + ".lock", os.O_RDWR | os.O_CREAT, bad)
        end = monotonic() + LOG_LOCK_WAIT
        while True:
            try:
                lock(lfd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                break
            except OSError:
                if monotonic() >= end:
                    return
                sleep(0.01)
        fd = open_private(dfd, name, os.O_RDWR | os.O_CREAT | os.O_APPEND, bad)
        os.fchmod(fd, 0o600)
        os.write(fd, (line + "\n").encode())
        size = os.fstat(fd).st_size
        if size > LOG_MAX:
            os.lseek(fd, size - LOG_KEEP, os.SEEK_SET)
            tail = os.read(fd, LOG_KEEP)
            tail = tail[tail.find(b"\n") + 1:]
            os.ftruncate(fd, 0)
            os.write(fd, tail)
    except OSError:
        pass
    finally:
        for d in (fd, lfd, dfd):
            if d >= 0:
                os.close(d)


def utc(t: float) -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(t))


# --- Child: send ----------------------------------------------------------------------------------

def own_slot(fd: int, home: str, bad) -> bool:
    """The inherited descriptor is one of this home's slot files, private and not a database identity."""
    try:
        st = os.fstat(fd)
        if not private_ok(st, bad):
            return False
        dfd = open_dir(state_dir(home))
        try:
            for name in SLOTS:
                try:
                    lst = os.stat(name, dir_fd=dfd, follow_symlinks=False)
                except OSError:
                    continue
                if (lst.st_dev, lst.st_ino) == (st.st_dev, st.st_ino):
                    return True
        finally:
            os.close(dfd)
    except OSError:
        pass
    return False


def deliver(o: dict, home: str, now, monotonic, sleep, opener, soft_end: float) -> str:
    session = o["--session"] or None
    install = o.get("--only-install")
    now_s = now()

    def work(conn):
        app = resolve_app_session(conn, session) if session else None
        rows, more = registration_rows(conn, now_s, install) if app or not session else ([], False)
        return app, rows, more
    status, result = read(db_path(o, home), work, monotonic)
    if status != "ok":
        return "skipped " + status
    app, rows, more = result
    if session and not app:
        return "skipped session"
    if o["--kind"] == "finished" and o["delay"]:
        sleep(max(0.0, min(o["delay"], soft_end - monotonic())))
    try:
        opener = opener or build_opener(o.get("--cafile"))
    except Exception:
        return "skipped tls"
    body = push_body(app, o["--kind"])
    results: list = [None] * len(rows)
    threads = []
    late = False
    for i, (_rid, _started, endpoint) in enumerate(rows):
        v = validate_endpoint(endpoint, o["origins"], o["http_origins"]) if endpoint is not None else "prompt"
        if isinstance(v, str):
            results[i] = "refused:%s=skipped" % v
            continue
        if monotonic() >= soft_end:  # no new POST after the soft deadline
            late = True
            break

        def work_send(i=i, label=v[0], url=v[1]):
            results[i] = "%s=%s" % (label, post(url, body, o["timeout"], opener))
        t = threading.Thread(target=work_send, daemon=True)
        t.start()
        threads.append(t)
    for t in threads:
        t.join(max(0.0, soft_end - monotonic()))
    if late or any(t.is_alive() for t in threads):
        return "deadline regs=%d" % len(rows)
    return " ".join(["matched regs=%d%s" % (len(rows), "+" if more else "")] + [r or "error" for r in results])


def run_send(o: dict, home: str, now, monotonic, sleep, opener) -> int:
    soft_end = monotonic() + SOFT_DEADLINE
    hook = HOOKS[o["--kind"]]
    fd = o["slot_fd"]
    bad = db_ids(db_path(o, home))
    try:
        if fd is None:
            line = deliver(o, home, now, monotonic, sleep, opener, soft_end)
        elif not own_slot(fd, home, bad):
            line = "skipped slot"
        else:
            try:
                os.ftruncate(fd, 0)
                pid = str(os.getpid()).encode()
                published = os.pwrite(fd, pid, 0) == len(pid)
            except OSError:
                published = False
            # A child that cannot publish its identity cannot be accounted for: it sends nothing.
            line = deliver(o, home, now, monotonic, sleep, opener, soft_end) if published else "skipped slot"
    except Exception:
        line = "skipped error"
    try:
        write_log(log_path(o, home), "%s %s %s" % (utc(now()), hook, line), bad, monotonic=monotonic, sleep=sleep)
    finally:
        if fd is not None:
            try:
                os.close(fd)  # releases the slot
            except OSError:
                pass
    return 0


# --- check ----------------------------------------------------------------------------------------

def run_check(o: dict, home: str, env, now, monotonic, out) -> int:
    """Reads everything, closes the database, and only then prints. Exit 0 complete, 4 incomplete
    (--ids capped or a field shortened), 3 failed or interrupted."""
    lines = ["python %s" % sys.version.split()[0],
             "SSL_CERT_FILE %s" % ("set" if env.get("SSL_CERT_FILE") else "unset"),
             "origins %s" % (" ".join(o["origins"] + o["http_origins"]) or "none")]
    now_s = now()
    if o["--ids"]:
        prefix = "apofis-push-%s-" % o["--only-install"]

        def work(conn):  # at most MAX_REGS + 1 rows, every field bounded in SQL, with shortening flags
            return conn.execute(
                "SELECT substr(id, 1, 64), substr(title, 1, 200), substr(source, 1, 32), "
                "CASE WHEN typeof(hidden) = 'integer' AND hidden IN (0, 1) THEN hidden END, "
                "length(id) > 64 OR length(title) > 200 OR length(source) > 32 "
                "FROM sessions WHERE id >= ? AND id < ? ORDER BY id LIMIT ?",
                (prefix, prefix[:-1] + ".", MAX_REGS + 1)).fetchall()
    else:
        def work(conn):
            return registration_rows(conn, now_s, o.get("--only-install"))
    status, result = read(db_path(o, home), work, monotonic)
    if status != "ok":
        out.write("\n".join(lines + ["skipped " + status]) + "\n")
        return 3
    lines.append("db opened read-only")
    code = 0
    if o["--ids"]:
        rows = result[:MAX_REGS]
        for row in rows:
            lines.append("\t".join(re.sub(r"[\x00-\x1f\x7f]", "?", "?" if c is None else str(c)) for c in row[:4]))
        if len(result) > MAX_REGS or any(r[3] is None or r[4] for r in rows):
            lines.append("incomplete: capped at %d rows or a field shortened" % MAX_REGS)
            code = 4
    else:
        rows, more = result
        for rid, started, endpoint in rows:
            m = RECORD_RE.fullmatch(rid)
            if not m:
                continue
            v = validate_endpoint(endpoint, o["origins"], o["http_origins"]) if endpoint is not None else "prompt"
            state = "refused %s" % v if isinstance(v, str) else "valid %s" % v[0]
            lines.append("apofis-push-%s...-%s %s age=%dd" % (m.group(1)[:8], m.group(2), state,
                                                              max(0, now_s - started) // 86400))
        lines.append("regs=%d%s" % (len(rows), "+" if more else ""))
    out.write("\n".join(lines) + "\n")
    out.flush()
    return code


def main(argv, stdin, env, now=time.time, spawn=subprocess.Popen, opener=None, lock=fcntl.flock,
         monotonic=time.monotonic, sleep=time.sleep, stdout=None) -> int:
    """Never touches the process alarm: the hard deadline belongs to the CLI process only."""
    try:
        o = parse_args(list(argv))
    except Exception:
        return 2
    home = env.get("HERMES_HOME") or os.path.join(env.get("HOME") or os.path.expanduser("~"), ".hermes")
    if o["cmd"] == "hook":
        return run_hook(o, home, stdin, env, spawn, lock)
    if o["cmd"] == "send":
        return run_send(o, home, now, monotonic, sleep, opener)
    try:
        return run_check(o, home, env, now, monotonic, stdout or sys.stdout)
    except BaseException:  # interruption or output failure: never a status that reads as complete
        return 3


if __name__ == "__main__":
    observer = len(sys.argv) > 1 and sys.argv[1] in ("hook", "send")
    try:
        code = main(sys.argv[1:], sys.stdin.buffer, os.environ)
    except BaseException:
        code = 0 if observer else 3
    if observer:
        os._exit(code)  # no interpreter teardown waiting on stuck daemon threads
    sys.exit(code)
