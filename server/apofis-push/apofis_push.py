#!/usr/bin/env python3
"""Apofis push hook for Hermes: a contentless UnifiedPush wake through ntfy. See README.md.

`hook` (run by Hermes from its hooks: block) reserves one of two slots, spawns one detached `send`
child and returns at once. The child forks a reader that drops every filesystem write right
(Landlock) before it opens Hermes' state database read-only; the reader returns the registrations
and exits (killed at a hard limit). The child then POSTs {"session_id","kind"} to each validated
endpoint. `check` reports configuration and records without any network. Python 3.9+ stdlib, Linux.
"""
from __future__ import annotations

import fcntl
import json
import os
import re
import resource
import select
import signal
import socket
import sqlite3
import ssl
import stat
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
MAX_MODEL_CONFIG = 65536
MAX_SESSION_ID = 256
STDIN_MAX = 1 << 20
CHILD_HARD = 25  # default-action SIGALRM: the kernel ends the child, no Python code has to run
SOFT_DEADLINE = 20  # the child stops waiting for requests and reports "deadline"
DB_BUDGET = 1.5  # SQLite progress budget inside the reader
DB_HARD = 3.0  # the reader is SIGKILLed after this, whatever a single operation does
READER_MEM = 256 << 20
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
        # Only bodies inside the contract: an anonymous wake exists only for needs_input.
        if o.get("--kind") not in HOOKS or "--session" not in o or (o["--kind"] == "finished" and not o["--session"]):
            raise Usage
        o["slot_fd"] = int(o["--slot-fd"]) if "--slot-fd" in o else None
    elif SEND_ONLY & set(o):
        raise Usage
    if o["--ids"] and (o["cmd"] != "check" or "--only-install" not in o):
        raise Usage
    return o


# --- Paths: nothing is ever written in the database's directory -----------------------------------

def db_path(o: dict, home: str) -> str:
    return os.path.abspath(o.get("--state-db") or os.path.join(home, "state.db"))


def state_dir(home: str) -> str:
    """Slots and log live in $HERMES_HOME/agent-hooks (created by the owner's install), never next to
    the database."""
    return os.path.join(home, "agent-hooks")


def log_path(o: dict, home: str) -> str:
    return o.get("--log") or os.path.join(state_dir(home), "apofis-push.log")


def forbidden(db: str) -> dict:
    """The database directory and the identities of the database and its sidecars (hard links too)."""
    ids = set()
    for p in (db, db + "-wal", db + "-shm", db + "-journal"):
        try:
            st = os.stat(p)
            ids.add((st.st_dev, st.st_ino))
        except OSError:
            pass
    return {"dir": os.path.realpath(os.path.dirname(db)), "ids": ids}


def usable_dir(path: str, bad: dict) -> bool:
    return os.path.isdir(path) and os.path.realpath(path) != bad["dir"]


def open_private(path: str, flags: int, bad: dict) -> int:
    """Open a private regular file without following symlinks or blocking on a FIFO; refuse anything in
    the database directory, any non-regular or hard-linked file and any database identity."""
    if not usable_dir(os.path.dirname(os.path.abspath(path)), bad):
        raise OSError("refused directory")
    fd = os.open(path, flags | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC, 0o600)
    st = os.fstat(fd)
    if not stat.S_ISREG(st.st_mode) or st.st_nlink != 1 or (st.st_dev, st.st_ino) in bad["ids"]:
        os.close(fd)
        raise OSError("refused file")
    return fd


def slot_paths(home: str) -> list[str]:
    return [os.path.join(state_dir(home), "apofis-push.slot%d.lock" % i) for i in (0, 1)]


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
    sid = event.get("session_id")
    if sid is None:
        sid = ""
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


def reserve_slot(home: str, lock, bad: dict) -> int | None:
    for path in slot_paths(home):
        fd = open_private(path, os.O_RDWR | os.O_CREAT, bad)
        try:
            lock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError:
            os.close(fd)
            continue
        os.ftruncate(fd, 0)  # no stale pid survives into the new child's slot
        return fd
    return None


def run_hook(o: dict, home: str, stdin, env, spawn, lock) -> int:
    try:
        data = stdin.read(STDIN_MAX + 1)
        if len(data) > STDIN_MAX:
            return 0
        target = hook_target(json.loads(data))
        if target is None:
            return 0
        fd = reserve_slot(home, lock, forbidden(db_path(o, home)))
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


# --- Database: a sandboxed, hard-limited reader ---------------------------------------------------

def sandbox() -> None:
    """Drop every filesystem write, create, remove, rename and truncate right of this process for good
    (Landlock); reads stay allowed. Raises when Landlock is unavailable."""
    import ctypes
    libc = ctypes.CDLL(None, use_errno=True)
    libc.syscall.restype = ctypes.c_long
    abi = libc.syscall(ctypes.c_long(444), None, ctypes.c_size_t(0), ctypes.c_uint32(1))
    if abi < 1:
        raise OSError("landlock unavailable")
    access = 0x1FF2  # WRITE_FILE, REMOVE_DIR, REMOVE_FILE, MAKE_CHAR..MAKE_SYM
    access |= (1 << 13 if abi >= 2 else 0) | (1 << 14 if abi >= 3 else 0)  # REFER, TRUNCATE
    attr = ctypes.c_uint64(access)
    fd = libc.syscall(ctypes.c_long(444), ctypes.byref(attr), ctypes.c_size_t(8), ctypes.c_uint32(0))
    if fd < 0:
        raise OSError(ctypes.get_errno(), "landlock ruleset")
    try:
        if libc.prctl(38, 1, 0, 0, 0) != 0:  # PR_SET_NO_NEW_PRIVS
            raise OSError(ctypes.get_errno(), "no_new_privs")
        if libc.syscall(ctypes.c_long(446), ctypes.c_int(fd), ctypes.c_uint32(0)) != 0:
            raise OSError(ctypes.get_errno(), "landlock restrict")
    finally:
        os.close(fd)


def snapshot(db: str, work, monotonic, on_progress=None):
    """One read transaction on a WAL database, read-only, query_only, in-memory temp store, inside a
    monotonic budget checked by SQLite's progress handler and again before the result is accepted."""
    with open(db, "rb") as f:
        header = f.read(100)
    if len(header) < 100 or header[18] != 2 or header[19] != 2:
        raise OSError("not a WAL database")
    end = monotonic() + DB_BUDGET

    def progress():
        if on_progress:
            on_progress()
        return 1 if monotonic() > end else 0
    conn = sqlite3.connect("file:%s?mode=ro" % urllib.parse.quote(db), uri=True, timeout=DB_BUDGET,
                           isolation_level=None)
    try:
        conn.set_progress_handler(progress, 1000)
        conn.execute("PRAGMA query_only=1")
        conn.execute("PRAGMA temp_store=MEMORY")
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


def read_db(db: str, work, monotonic) -> tuple[str, object]:
    """Run `work` in a forked reader under the sandbox. ("ok", result) or (reason, None) with reason
    "budget", "db" or "sandbox". The reader is SIGKILLed after DB_HARD seconds."""
    r, w = os.pipe()
    pid = os.fork()
    if pid == 0:  # reader
        out = {"err": "db"}
        try:
            os.close(r)
            signal.signal(signal.SIGALRM, signal.SIG_DFL)
            signal.alarm(int(DB_HARD) + 2)
            try:
                sandbox()
            except Exception:
                out = {"err": "sandbox"}
                raise
            with open("/proc/self/statm") as f:  # cap growth: current size plus READER_MEM
                cap = int(f.read().split()[0]) * resource.getpagesize() + READER_MEM
            resource.setrlimit(resource.RLIMIT_AS, (cap, cap))
            out = {"ok": snapshot(db, work, monotonic)}
        except Budget:
            out = {"err": "budget"}
        except BaseException:
            pass
        try:
            data = json.dumps(out).encode()
            while data:
                data = data[os.write(w, data):]
        finally:
            os._exit(0)
    os.close(w)
    chunks: list = []
    end = time.monotonic() + DB_HARD
    done = False
    try:
        while not done:
            left = end - time.monotonic()
            if left <= 0:
                break
            if select.select([r], [], [], left)[0]:
                b = os.read(r, 65536)
                chunks.append(b)
                done = not b
    finally:
        os.close(r)
        if not done:
            try:
                os.kill(pid, signal.SIGKILL)
            except OSError:
                pass
        os.waitpid(pid, 0)
    if not done:
        return "budget", None
    try:
        out = json.loads(b"".join(chunks))
    except ValueError:
        return "db", None
    return ("ok", out["ok"]) if "ok" in out else (out.get("err", "db"), None)


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

def write_log(path: str, line: str, bad: dict, lock=fcntl.flock, monotonic=time.monotonic, sleep=time.sleep) -> None:
    """Append one line under <log>.lock (non-blocking polls, 1 s cap: skipped on timeout); rotate over
    LOG_MAX keeping the last LOG_KEEP bytes. Private regular files only (see open_private). Never raises."""
    if path == "-":
        sys.stdout.write(line + "\n")
        return
    try:
        lfd = open_private(path + ".lock", os.O_RDWR | os.O_CREAT, bad)
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
        fd = open_private(path, os.O_RDWR | os.O_CREAT | os.O_APPEND, bad)
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


def utc(t: float) -> str:
    return time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime(t))


def arm_hard_deadline() -> None:
    signal.signal(signal.SIGALRM, signal.SIG_DFL)
    signal.alarm(CHILD_HARD)


# --- Child: send ----------------------------------------------------------------------------------

def own_slot(fd: int, home: str) -> bool:
    """The inherited descriptor is one of this home's slot files (regular, single link, no symlink)."""
    try:
        st = os.fstat(fd)
        if not stat.S_ISREG(st.st_mode) or st.st_nlink != 1:
            return False
        for path in slot_paths(home):
            try:
                lst = os.lstat(path)
            except OSError:
                continue
            if stat.S_ISREG(lst.st_mode) and (lst.st_dev, lst.st_ino) == (st.st_dev, st.st_ino):
                return True
    except OSError:
        pass
    return False


def deliver(o: dict, home: str, now, monotonic, sleep, opener, soft_end: float) -> str:
    db = db_path(o, home)
    session = o["--session"] or None
    install = o.get("--only-install")
    now_s = now()

    def work(conn):
        app = resolve_app_session(conn, session) if session else None
        rows, more = registration_rows(conn, now_s, install) if app or not session else ([], False)
        return [app, rows, more]
    status, result = read_db(db, work, monotonic)
    if status != "ok":
        return "skipped " + {"budget": "db-budget", "sandbox": "sandbox"}.get(status, "db")
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
    for i, (_rid, _started, endpoint) in enumerate(rows):
        v = validate_endpoint(endpoint, o["origins"], o["http_origins"]) if endpoint is not None else "prompt"
        if isinstance(v, str):
            results[i] = "refused:%s=skipped" % v
            continue

        def work_send(i=i, label=v[0], url=v[1]):
            results[i] = "%s=%s" % (label, post(url, body, o["timeout"], opener))
        t = threading.Thread(target=work_send, daemon=True)
        t.start()
        threads.append(t)
    for t in threads:
        t.join(max(0.0, soft_end - monotonic()))
    if any(t.is_alive() for t in threads):
        return "deadline regs=%d" % len(rows)
    return " ".join(["matched regs=%d%s" % (len(rows), "+" if more else "")] + [r or "error" for r in results])


def run_send(o: dict, home: str, now, monotonic, sleep, opener, arm) -> int:
    arm()  # hard stop first, so nothing below can outlive CHILD_HARD
    soft_end = monotonic() + SOFT_DEADLINE
    hook = HOOKS[o["--kind"]]
    fd = o["slot_fd"]
    bad = forbidden(db_path(o, home))
    try:
        if fd is None:
            line = deliver(o, home, now, monotonic, sleep, opener, soft_end)
        elif not own_slot(fd, home):
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
    """Prints nothing until the whole report is built; any failure or interruption exits 3."""
    lines = ["python %s" % sys.version.split()[0],
             "SSL_CERT_FILE %s" % ("set" if env.get("SSL_CERT_FILE") else "unset"),
             "origins %s" % (" ".join(o["origins"] + o["http_origins"]) or "none")]
    now_s = now()
    if o["--ids"]:
        prefix = "apofis-push-%s-" % o["--only-install"]

        def work(conn):
            return [[str(c) for c in r] for r in conn.execute(
                "SELECT id, title, source, hidden FROM sessions WHERE id >= ? AND id < ? ORDER BY id",
                (prefix, prefix[:-1] + ".")).fetchall()]
    else:
        def work(conn):
            return list(registration_rows(conn, now_s, o.get("--only-install")))
    status, result = read_db(db_path(o, home), work, monotonic)
    if status != "ok":
        out.write("\n".join(lines + ["skipped " + {"budget": "db-budget", "sandbox": "sandbox"}.get(status, "db")]) + "\n")
        return 3
    lines.append("db opened read-only")
    if o["--ids"]:
        lines += ["\t".join(re.sub(r"[\x00-\x1f\x7f]", "?", c) for c in row) for row in result]
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
    return 0


def main(argv, stdin, env, now=time.time, spawn=subprocess.Popen, opener=None, lock=fcntl.flock,
         monotonic=time.monotonic, sleep=time.sleep, stdout=None, arm=arm_hard_deadline) -> int:
    try:
        o = parse_args(list(argv))
    except Exception:
        return 2
    home = env.get("HERMES_HOME") or os.path.join(env.get("HOME") or os.path.expanduser("~"), ".hermes")
    if o["cmd"] == "hook":
        return run_hook(o, home, stdin, env, spawn, lock)
    if o["cmd"] == "send":
        return run_send(o, home, now, monotonic, sleep, opener, arm)
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
