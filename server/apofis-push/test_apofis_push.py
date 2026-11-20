"""Tests for apofis_push.py. Run: python3 -I -m unittest discover -s server/apofis-push -p 'test_*.py' -v

Deterministic: injected clocks, openers and spawns; real children only against loopback fakes and
private WAL fixtures. Real-time limits are generous watchdogs only.
"""
from __future__ import annotations

import contextlib
import fcntl
import hashlib
import http.server
import io
import json
import os
import re
import shutil
import signal
import socket
import sqlite3
import ssl
import subprocess
import sys
import tempfile
import threading
import time
import unittest
import urllib.error
import urllib.request
from unittest import mock

import apofis_push as ap

HERE = os.path.dirname(os.path.abspath(__file__))
SCRIPT = os.path.join(HERE, "apofis_push.py")
with open(os.path.join(HERE, "vectors.json"), encoding="utf-8") as _f:
    VEC = json.load(_f)
VALID = VEC["valid"][0]
INSTALL = "0f8fad5b-d9cb-469f-a165-70867728950e"
APP = "apofis-1b4e28ba-2fa1-11d2-883f-0016d3cca427"
NOW = 1_800_000_000.0
WATCHDOG = 30.0
ORIGIN = ["--allow-origin", "https://ntfy.sh"]

SCHEMA = """
CREATE TABLE system_prompts (hash TEXT PRIMARY KEY, prompt TEXT NOT NULL);
CREATE TABLE sessions (
    id TEXT PRIMARY KEY, source TEXT NOT NULL, model TEXT, model_config TEXT, system_prompt TEXT,
    system_prompt_hash TEXT, parent_session_id TEXT, started_at REAL NOT NULL, ended_at REAL,
    end_reason TEXT, message_count INTEGER DEFAULT 0, title TEXT,
    archived INTEGER NOT NULL DEFAULT 0, hidden INTEGER NOT NULL DEFAULT 0,
    FOREIGN KEY (parent_session_id) REFERENCES sessions(id),
    FOREIGN KEY (system_prompt_hash) REFERENCES system_prompts(hash));
CREATE TABLE messages (id INTEGER PRIMARY KEY AUTOINCREMENT, session_id TEXT NOT NULL, content TEXT);
"""


class DeadlineFired(BaseException):
    pass


def setUpModule():
    """In-process `send` runs arm the real 25 s alarm; its handler must never os._exit the test runner
    (that would end the run with status 0). Here it raises, so a hung test fails loudly instead."""
    real = ap.deadline_handler

    def safe(log, hook, exit=None):
        def fired(code):
            raise DeadlineFired("child deadline fired in-process")
        return real(log, hook, exit=exit or fired)
    ap.deadline_handler = safe


def uuid_n(n: int) -> str:
    return "%08x-abcd-4000-8000-%012x" % (n, n)


def prompt(endpoint: str) -> str:
    return json.dumps({"v": 1, "endpoint": endpoint})


class Fixture:
    """A private WAL database (writer kept open so -wal and -shm exist) and a private HERMES_HOME."""

    def __init__(self, tc: unittest.TestCase):
        self.dir = tempfile.mkdtemp(prefix="apofis-push-test-")
        tc.addCleanup(shutil.rmtree, self.dir, True)
        self.home = os.path.join(self.dir, "home")
        self.dbdir = os.path.join(self.dir, "db")
        os.mkdir(self.home)
        os.mkdir(self.dbdir)
        self.db = os.path.join(self.dbdir, "state.db")
        self.w = sqlite3.connect(self.db, isolation_level=None)
        tc.addCleanup(self.close)
        self.w.execute("PRAGMA journal_mode=WAL")
        self.w.executescript(SCHEMA)
        self.log = os.path.join(self.home, "apofis-push.log")

    def close(self):
        self.w.close()

    def session(self, sid, source="api_server", hidden=0, parent=None, end_reason=None, model_config=None,
                started=NOW, title=None, system_prompt=None, prompt_hash=None):
        self.w.execute("INSERT INTO sessions (id, source, hidden, parent_session_id, end_reason, model_config,"
                       " started_at, title, system_prompt, system_prompt_hash) VALUES (?,?,?,?,?,?,?,?,?,?)",
                       (sid, source, hidden, parent, end_reason,
                        json.dumps(model_config) if isinstance(model_config, dict) else model_config,
                        started, title, system_prompt, prompt_hash))

    def record(self, install=INSTALL, seq="1", endpoint=VALID, started=NOW, hidden=1, raw_prompt=None, **kw):
        rid = "apofis-push-%s-%s" % (install, seq)
        self.session(rid, hidden=hidden, started=started, title=rid + " op-0123456789abcdef",
                     system_prompt=prompt(endpoint) if raw_prompt is None else raw_prompt, **kw)
        return rid

    def app(self, sid=APP, **kw):
        self.session(sid, **kw)
        return sid

    def ro(self):
        conn = sqlite3.connect("file:%s?mode=ro" % self.db, uri=True)
        return conn

    def listing(self):
        return sorted(os.listdir(self.dbdir))

    def digest(self):
        h = hashlib.sha256()
        for name in ("state.db", "state.db-wal"):
            p = os.path.join(self.dbdir, name)
            if os.path.exists(p):
                with open(p, "rb") as f:
                    h.update(f.read())
        return h.hexdigest()

    def log_text(self):
        try:
            with open(self.log, encoding="utf-8") as f:
                return f.read()
        except FileNotFoundError:
            return ""

    def env(self, **extra):
        env = {"HERMES_HOME": self.home, "PATH": os.environ.get("PATH", "/usr/bin:/bin")}
        env.update(extra)
        return env

    def run(self, argv, stdin=b"", env=None, **kw):
        out = io.StringIO()
        kw.setdefault("sleep", lambda s: None)
        code = ap.main(argv, io.BytesIO(stdin), env or self.env(), stdout=out, **kw)
        return code, out.getvalue()

    def send(self, session=APP, kind="finished", extra=(), opener=None, **kw):
        opener = opener or FakeOpener()
        argv = ["send", "--session", session, "--kind", kind, "--state-db", self.db, "--delay", "0",
                *ORIGIN, *extra]
        code, out = self.run(argv, opener=opener, now=lambda: NOW, **kw)
        return code, opener

    def slot_path(self, i):
        return os.path.join(self.home, "apofis-push.slot%d.lock" % i)

    def hold(self, path):
        fd = os.open(path, os.O_RDWR | os.O_CREAT, 0o600)
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return fd

    def lockable(self, path):
        try:
            fd = os.open(path, os.O_RDWR)
        except FileNotFoundError:
            return True
        try:
            fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            return True
        except OSError:
            return False
        finally:
            os.close(fd)


class FakeResp:
    def __init__(self, status=200, body=b""):
        self.status = status
        self.body = body
        self.reads = []

    def read(self, n=-1):
        self.reads.append(n)
        return self.body[:n] if n >= 0 else self.body

    def __enter__(self):
        return self

    def __exit__(self, *a):
        return False


class FakeOpener:
    def __init__(self, fn=None):
        self.calls = []
        self.fn = fn or (lambda req, timeout: FakeResp(200))
        self.lock = threading.Lock()

    def open(self, req, timeout=None):
        with self.lock:
            self.calls.append((req, timeout))
        return self.fn(req, timeout)


def http_error(code):
    def fn(req, timeout):
        raise urllib.error.HTTPError(req.full_url, code, "x", {}, io.BytesIO(b""))
    return fn


class Server:
    """Loopback fake ntfy. `hold` keeps each request on a latch until `release()`."""

    def __init__(self, tc, hold=False, status=200, location=None):
        outer = self
        self.requests = []
        self.entered = threading.Event()
        self.latch = threading.Event()
        self.hold = hold

        class H(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                outer.requests.append((self.path, {k.lower(): v for k, v in self.headers.items()}, body))
                outer.entered.set()
                if outer.hold:
                    outer.latch.wait(WATCHDOG)
                self.send_response(status)
                if location:
                    self.send_header("Location", location)
                self.send_header("Content-Length", "0")
                self.end_headers()

            def log_message(self, *a):
                pass

        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.httpd.daemon_threads = True
        self.port = self.httpd.server_address[1]
        self.origin = "http://127.0.0.1:%d" % self.port
        self.endpoint = self.origin + "/upWIRECHECK012?up=1"
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()
        tc.addCleanup(self.stop)

    def release(self):
        self.latch.set()

    def stop(self):
        self.latch.set()
        self.httpd.shutdown()
        self.httpd.server_close()


def payload(event, session_id="", **extra):
    return json.dumps({"hook_event_name": event, "tool_name": None, "tool_input": None, "session_id": session_id,
                       "cwd": "/", "profile": "default", "extra": extra}).encode()


FINISHED = dict(completed=True, failed=False, interrupted=False, platform="api_server")


class SpawnRecorder:
    def __init__(self, check=None):
        self.calls = []
        self.check = check

    def __call__(self, args, **kw):
        if self.check:
            self.check(args, kw)
        self.calls.append((args, kw))
        return mock.Mock()


# ---------------------------------------------------------------------------


class StartupTest(unittest.TestCase):
    def test_bad_origins_exit_2_without_side_effects(self):
        for opt in (["--allow-origin", "http://ntfy.sh"], ["--allow-http-origin", "http://localhost:1"],
                    ["--allow-http-origin", "http://127.0.0.1"], ["--allow-origin", "https://ntfy.sh/"],
                    ["--allow-origin", "https://ntfy.sh."], ["--allow-http-origin", "http://127.0.0.1:70000"],
                    ["--timeout", "11"], ["--delay", "nan"], ["--only-install", INSTALL.upper()], ["--bogus", "1"]):
            with self.subTest(opt=opt):
                fx = Fixture(self)
                spawn = SpawnRecorder()
                err = io.StringIO()
                with contextlib.redirect_stderr(err):
                    code, out = fx.run(["hook", *opt], stdin=payload("on_session_end", APP, **FINISHED), spawn=spawn)
                self.assertEqual(code, 2)
                self.assertEqual(spawn.calls, [])
                self.assertEqual((out, err.getvalue()), ("", ""))
                self.assertEqual(os.listdir(fx.home), [])

    def test_no_command_exits_2(self):
        fx = Fixture(self)
        self.assertEqual(fx.run([])[0], 2)
        self.assertEqual(fx.run(["frobnicate"])[0], 2)


class HookParentTest(unittest.TestCase):
    def hook(self, data, env=None, spawn=None):
        fx = Fixture(self)
        spawn = spawn or SpawnRecorder()
        out, err = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(out), contextlib.redirect_stderr(err):
            code, printed = fx.run(["hook", *ORIGIN], stdin=data, env=env or fx.env(), spawn=spawn)
        self.assertEqual(code, 0)
        self.assertEqual((printed, out.getvalue(), err.getvalue()), ("", "", ""))
        return fx, spawn

    def target(self, spawn):
        if not spawn.calls:
            return None
        args = spawn.calls[0][0]
        return args[args.index("--session") + 1], args[args.index("--kind") + 1]

    def test_target_table(self):
        cases = [
            (payload("on_session_end", APP, **FINISHED), (APP, "finished")),
            (payload("on_session_end", APP, completed=False, failed=True, platform=""), (APP, "finished")),
            (payload("on_session_end", "", **FINISHED), None),
            (payload("on_session_end", APP, completed=False, failed=False, interrupted=True,
                     platform="api_server"), None),
            (payload("on_session_end", APP, completed="yes", platform="api_server"), None),
            (payload("on_session_end", APP, completed=True, platform="telegram"), None),
            (payload("on_human_input_request", APP, kind="approval", platform="api_server"), (APP, "needs_input")),
            (payload("on_human_input_request", APP, kind="approval", platform=""), (APP, "needs_input")),
            (payload("on_human_input_request", "", kind="approval", platform="api_server"), ("", "needs_input")),
            (payload("on_human_input_request", "", kind="approval", platform=""), None),
            (payload("on_human_input_request", "", kind="approval", platform="cli"), None),
            (payload("on_human_input_request", APP, kind="clarify", platform="api_server"), (APP, "needs_input")),
            (payload("on_human_input_request", "", kind="clarify", platform="api_server"), None),
            (payload("on_human_input_request", APP, kind="approval", platform="discord"), None),
            (payload("on_human_input_request", APP, kind="sudo", platform="api_server"), None),
            (payload("on_human_input_request", APP, kind="future-kind", platform="api_server"), None),
            (payload("pre_tool_call", APP, platform="api_server"), None),
        ]
        for data, want in cases:
            with self.subTest(data=data):
                _fx, spawn = self.hook(data)
                self.assertEqual(self.target(spawn), want)

    def test_hook_target_function(self):
        self.assertEqual(ap.hook_target(json.loads(payload("on_session_end", APP, **FINISHED))), (APP, "finished"))
        self.assertIsNone(ap.hook_target([]))
        self.assertIsNone(ap.hook_target({"hook_event_name": "on_session_end", "session_id": 5,
                                          "extra": {"completed": True}}))
        self.assertIsNone(ap.hook_target({"hook_event_name": "on_session_end", "session_id": APP, "extra": ["completed"]}))

    def test_hostile_stdin(self):
        for data in (b"", b"null", b"[]", b"\xff\xfe\x00garbage", b"{" * 3, b" " * (2 << 20) + b"{}",
                     b"[" * 10000 + b"]" * 10000, payload("on_session_end", APP, **FINISHED) + b" " * (2 << 20)):
            with self.subTest(n=len(data)):
                _fx, spawn = self.hook(data)
                self.assertEqual(spawn.calls, [])

    def test_hostile_stdin_real_process(self):
        fx = Fixture(self)
        p = subprocess.run([sys.executable, "-I", SCRIPT, "hook", *ORIGIN], input=b"[" * (2 << 20),
                           capture_output=True, env=fx.env(), timeout=WATCHDOG)
        self.assertEqual((p.returncode, p.stdout, p.stderr), (0, b"", b""))
        self.assertEqual(os.listdir(fx.home), [])

    def test_child_arguments(self):
        seen = {}

        def check(args, kw):
            fd = kw["pass_fds"][0]
            seen["fd_target"] = os.readlink("/proc/self/fd/%d" % fd)
            seen["held"] = not fx_ref[0].lockable(fx_ref[0].slot_path(0))

        fx_ref = []
        fx = Fixture(self)
        fx_ref.append(fx)
        spawn = SpawnRecorder(check)
        env = fx.env(SSL_CERT_FILE="/etc/ssl/x.crt", LANG="C.UTF-8", API_SERVER_KEY="secret", HOME="/h",
                     https_proxy="http://127.0.0.1:9")
        code, _ = fx.run(["hook", *ORIGIN, "--timeout", "3"], stdin=payload("on_session_end", APP, **FINISHED),
                         env=env, spawn=spawn)
        self.assertEqual(code, 0)
        self.assertEqual(len(spawn.calls), 1)
        args, kw = spawn.calls[0]
        self.assertEqual(args[:4], [sys.executable, "-I", SCRIPT, "send"])
        self.assertTrue(os.path.isabs(args[2]))
        self.assertEqual(args[args.index("--kind") + 1], "finished")
        self.assertIn("--timeout", args)
        self.assertEqual(args[args.index("--slot-fd") + 1], str(kw["pass_fds"][0]))
        for k in ("stdin", "stdout", "stderr"):
            self.assertIs(kw[k], subprocess.DEVNULL)
        self.assertIs(kw["start_new_session"], True)
        self.assertIs(kw["close_fds"], True)
        self.assertEqual(len(kw["pass_fds"]), 1)
        self.assertEqual(seen["fd_target"], fx.slot_path(0))
        self.assertTrue(seen["held"])
        self.assertEqual(set(kw["env"]), {"HERMES_HOME", "PATH", "SSL_CERT_FILE", "LANG"})
        self.assertEqual(kw["env"]["HERMES_HOME"], fx.home)
        self.assertTrue(fx.lockable(fx.slot_path(0)), "parent must close its copy of the slot")

    def test_spawn_failure_is_quiet(self):
        def boom(args, **kw):
            raise OSError("no")
        _fx, _spawn = self.hook(payload("on_session_end", APP, **FINISHED), spawn=boom)

    def test_slot_reserved_before_spawn(self):
        fx = Fixture(self)
        a, b = fx.hold(fx.slot_path(0)), fx.hold(fx.slot_path(1))
        before = {n: os.stat(os.path.join(fx.home, n)) for n in os.listdir(fx.home)}
        spawn = SpawnRecorder()
        fx.run(["hook", *ORIGIN], stdin=payload("on_session_end", APP, **FINISHED), spawn=spawn)
        self.assertEqual(spawn.calls, [])
        after = {n: os.stat(os.path.join(fx.home, n)) for n in os.listdir(fx.home)}
        self.assertEqual(set(before), set(after))
        for n in before:
            self.assertEqual((before[n].st_size, before[n].st_mtime_ns), (after[n].st_size, after[n].st_mtime_ns))
        os.close(a)
        held = []
        spawn = SpawnRecorder(lambda args, kw: held.append(not fx.lockable(fx.slot_path(0))))
        fx.run(["hook", *ORIGIN], stdin=payload("on_session_end", APP, **FINISHED), spawn=spawn)
        self.assertEqual(len(spawn.calls), 1)
        self.assertEqual(held, [True])
        self.assertEqual(os.readlink("/proc/self/fd/%d" % b), fx.slot_path(1))
        os.close(b)


class RealChildMixin:
    def real_fixture(self, hold=True):
        fx = Fixture(self)
        server = Server(self, hold=hold)
        fx.app()
        fx.record(endpoint=server.endpoint)
        return fx, server

    def run_parent(self, fx, server, data=None, **kw):
        return subprocess.run(
            [sys.executable, "-I", SCRIPT, "hook", "--allow-http-origin", server.origin, "--delay", "0",
             "--state-db", fx.db],
            input=data or payload("on_session_end", APP, **FINISHED), capture_output=True, env=fx.env(),
            timeout=WATCHDOG, **kw)

    def slot_pid(self, fx):
        for i in (0, 1):
            try:
                with open(fx.slot_path(i)) as f:
                    text = f.read().strip()
            except FileNotFoundError:
                continue
            if text and not fx.lockable(fx.slot_path(i)):
                return int(text)
        return None

    def wait_pid(self, fx):
        end = time.monotonic() + WATCHDOG
        while time.monotonic() < end:
            pid = self.slot_pid(fx)
            if pid:
                return pid
            time.sleep(0.01)
        self.fail("child never recorded its pid")

    def await_child(self, fx, watchdog=WATCHDOG):
        """Wait until both slots are free (the child ended); on the watchdog kill its group and fail."""
        end = time.monotonic() + watchdog
        while time.monotonic() < end:
            if fx.lockable(fx.slot_path(0)) and fx.lockable(fx.slot_path(1)):
                return True
            time.sleep(0.01)
        self.kill_child(fx)
        self.fail("child did not end within the watchdog; killed")

    def kill_child(self, fx):
        pid = self.slot_pid(fx)
        if pid:
            with contextlib.suppress(ProcessLookupError):
                os.killpg(pid, signal.SIGKILL)
        return pid

    def assert_gone(self, pid):
        end = time.monotonic() + WATCHDOG
        while time.monotonic() < end:
            try:
                os.kill(pid, 0)
            except ProcessLookupError:
                return
            try:
                with open("/proc/%d/stat" % pid) as f:
                    if f.read().rsplit(") ", 1)[1].startswith("Z"):
                        return
            except FileNotFoundError:
                return
            time.sleep(0.01)
        self.fail("process %d still alive" % pid)


class ParentNeverWaitsTest(RealChildMixin, unittest.TestCase):
    def test_parent_never_waits_for_child(self):
        fx, server = self.real_fixture(hold=True)
        p = self.run_parent(fx, server)
        self.assertEqual((p.returncode, p.stdout, p.stderr), (0, b"", b""))
        self.assertTrue(server.entered.wait(WATCHDOG), "child never posted")
        self.assertFalse(server.latch.is_set())
        self.assertFalse(fx.lockable(fx.slot_path(0)), "child still holds its slot while the request is held")
        server.release()
        self.await_child(fx)
        self.assertEqual(len(server.requests), 1)
        path, headers, body = server.requests[0]
        self.assertEqual(path, "/upWIRECHECK012?up=1")
        self.assertEqual(body, b'{"session_id":"%s","kind":"finished"}' % APP.encode())
        self.assertEqual(headers.get("x-unifiedpush"), "1")
        self.assertRegex(fx.log_text(), r"on_session_end matched regs=1 http://127\.0\.0\.1:\d+=200\n$")


class ContainmentTest(RealChildMixin, unittest.TestCase):
    def test_real_child_accounted(self):
        fx, server = self.real_fixture(hold=False)
        self.assertEqual(self.run_parent(fx, server).returncode, 0)
        self.await_child(fx)
        with open(fx.slot_path(0)) as f:
            pid = int(f.read())
        self.assert_gone(pid)
        self.assertEqual(len(server.requests), 1)

    def test_real_child_killed_on_timeout(self):
        fx, server = self.real_fixture(hold=True)
        self.assertEqual(self.run_parent(fx, server).returncode, 0)
        self.assertTrue(server.entered.wait(WATCHDOG))
        pid = self.wait_pid(fx)
        self.assertEqual(os.getpgid(pid), pid, "child leads its own process group")
        self.assertEqual(self.kill_child(fx), pid)
        self.assert_gone(pid)
        self.await_child(fx, watchdog=WATCHDOG)

    def test_descriptors_isolated(self):
        fx, server = self.real_fixture(hold=True)
        r, w = os.pipe()
        self.addCleanup(os.close, r)
        self.addCleanup(os.close, w)
        self.assertEqual(self.run_parent(fx, server, pass_fds=(w,)).returncode, 0)
        self.assertTrue(server.entered.wait(WATCHDOG))
        pid = self.wait_pid(fx)
        try:
            fds = {int(n): os.readlink("/proc/%d/fd/%s" % (pid, n)) for n in os.listdir("/proc/%d/fd" % pid)}
        finally:
            server.release()
        self.await_child(fx)
        for n in (0, 1, 2):
            self.assertEqual(fds.pop(n), "/dev/null")
        slots = [n for n, t in fds.items() if t in (fx.slot_path(0), fx.slot_path(1))]
        self.assertEqual(len(slots), 1)
        fds.pop(slots[0])
        # Only the child's own in-flight request socket may remain; no pipe, file or database.
        for n, t in fds.items():
            self.assertTrue(t.startswith("socket:"), (n, t))

    def test_no_sidecar_created(self):
        fx = Fixture(self)
        fx.app()
        fx.record()
        before = fx.listing()
        self.assertIn("state.db-wal", before)
        code, out = fx.run(["check", *ORIGIN, "--state-db", fx.db], now=lambda: NOW)
        self.assertEqual(code, 0)
        code, opener = fx.send()
        self.assertEqual(len(opener.calls), 1)
        self.assertEqual(fx.listing(), before)
        fx.close()
        bare = fx.listing()
        self.assertEqual(bare, ["state.db"])
        code, out = fx.run(["check", *ORIGIN, "--state-db", fx.db], now=lambda: NOW)
        self.assertEqual(code, 3)
        self.assertIn("skipped db", out)
        code, opener = fx.send()
        self.assertEqual(opener.calls, [])
        self.assertIn("on_session_end skipped db\n", fx.log_text())
        self.assertEqual(fx.listing(), bare)


class LineageTest(unittest.TestCase):
    def resolve(self, fx, sid, **kw):
        conn = fx.ro()
        try:
            return ap.resolve_app_session(conn, sid, **kw)
        finally:
            conn.close()

    def chain(self, fx, hops, root=APP):
        fx.app(root, end_reason="compression")
        prev = root
        for i in range(hops):
            sid = "cont-%d" % i
            fx.session(sid, parent=prev, end_reason="compression" if i < hops - 1 else None)
            prev = sid
        return prev

    def test_own_id(self):
        fx = Fixture(self)
        fx.app()
        self.assertEqual(self.resolve(fx, APP), APP)
        fx.app("apofis-tmp-" + uuid_n(3))
        self.assertEqual(self.resolve(fx, "apofis-tmp-" + uuid_n(3)), "apofis-tmp-" + uuid_n(3))
        self.assertIsNone(self.resolve(fx, "missing"))

    def test_continuations(self):
        for hops in (3, 200, 1000):
            with self.subTest(hops=hops):
                fx = Fixture(self)
                self.assertEqual(self.resolve(fx, self.chain(fx, hops)), APP)

    def test_inherited_branch_marker_still_resolves(self):
        fx = Fixture(self)
        fx.app("apofis-" + uuid_n(1), end_reason="compression")
        fx.app(APP, parent="apofis-" + uuid_n(1), model_config={"_branched_from": "apofis-" + uuid_n(1)},
               end_reason="compression")
        fx.session("cont", parent=APP, model_config={"_branched_from": "apofis-" + uuid_n(1)})
        self.assertEqual(self.resolve(fx, "cont"), APP)

    def test_forks_stop(self):
        for marker in ("_branched_from", "_reset_from", "_delegate_from"):
            with self.subTest(marker=marker):
                fx = Fixture(self)
                fx.app("apofis-" + uuid_n(1), end_reason="compression")
                fx.app(APP, parent="apofis-" + uuid_n(1), model_config={marker: "apofis-" + uuid_n(1)})
                self.assertEqual(self.resolve(fx, APP), APP)

    def test_tool_child_stops(self):
        fx = Fixture(self)
        fx.app(end_reason="compression")
        fx.session("t", source="tool", parent=APP)
        self.assertIsNone(self.resolve(fx, "t"))

    def test_parent_not_compression_stops(self):
        fx = Fixture(self)
        fx.app("apofis-" + uuid_n(1), end_reason="session_reset")
        fx.app(APP, parent="apofis-" + uuid_n(1))
        self.assertEqual(self.resolve(fx, APP), APP)
        fx.session("cont", parent="apofis-" + uuid_n(1))
        self.assertIsNone(self.resolve(fx, "cont"), "a non-UUID row that is its own root never resolves")

    def test_path_rows_must_be_visible_api_rows(self):
        cases = {
            "hidden tip": lambda fx: (fx.app(end_reason="compression"), fx.session("tip", parent=APP, hidden=1)),
            "hidden intermediate": lambda fx: (fx.app(end_reason="compression"),
                                               fx.session("mid", parent=APP, hidden=1, end_reason="compression"),
                                               fx.session("tip", parent="mid")),
            "hidden root": lambda fx: (fx.app(end_reason="compression", hidden=1), fx.session("tip", parent=APP)),
            "reserved tip": lambda fx: (fx.app(end_reason="compression"),
                                        fx.session("apofis-push-x", parent=APP), fx.session("tip", parent="x")),
            "cli intermediate": lambda fx: (fx.app(end_reason="compression"),
                                            fx.session("mid", source="cli", parent=APP, end_reason="compression"),
                                            fx.session("tip", parent="mid")),
        }
        for name, build in cases.items():
            with self.subTest(name):
                fx = Fixture(self)
                build(fx)
                tip = "apofis-push-x" if name == "reserved tip" else "tip"
                self.assertIsNone(self.resolve(fx, tip))
        fx = Fixture(self)
        fx.app("apofis-probe-" + uuid_n(1))
        self.assertIsNone(self.resolve(fx, "apofis-probe-" + uuid_n(1)))

    def test_cycle_and_cap(self):
        fx = Fixture(self)
        fx.session("a", parent="b", end_reason="compression")
        fx.session("b", parent="a", end_reason="compression")
        self.assertIsNone(self.resolve(fx, "a"))
        fx = Fixture(self)
        self.assertIsNone(self.resolve(fx, self.chain(fx, 1001)))
        fx = Fixture(self)
        self.assertIsNone(self.resolve(fx, self.chain(fx, 5), cap=4))

    def test_non_uuid_root(self):
        for root in ("apofis-notauuid", "apofis-" + uuid_n(1).upper(), "other-" + uuid_n(1),
                     "apofis-" + uuid_n(1) + "x"):
            with self.subTest(root=root):
                fx = Fixture(self)
                self.assertIsNone(self.resolve(fx, self.chain(fx, 2, root=root)))


class RegistrationTest(unittest.TestCase):
    def load(self, fx, install=None, now=NOW):
        conn = fx.ro()
        try:
            return ap.load_registrations(conn, now, install)
        finally:
            conn.close()

    def test_seq_numeric_order(self):
        fx = Fixture(self)
        fx.record(seq="10", endpoint="https://ntfy.sh/up0000000000AA", started=NOW - 10)
        fx.record(seq="9", endpoint="https://ntfy.sh/up0000000000BB", started=NOW)
        self.assertEqual(self.load(fx), [("apofis-push-%s-10" % INSTALL, "https://ntfy.sh/up0000000000AA")])

    def test_non_canonical_seq_never_ranks(self):
        fx = Fixture(self)
        fx.record(seq="000002", endpoint="https://ntfy.sh/up0000000000AA")
        fx.record(seq="01", endpoint="https://ntfy.sh/up0000000000BB")
        fx.record(seq="1234567", endpoint="https://ntfy.sh/up0000000000DD")
        fx.record(seq="1", endpoint="https://ntfy.sh/up0000000000CC", started=NOW - 100)
        self.assertEqual(self.load(fx), [("apofis-push-%s-1" % INSTALL, "https://ntfy.sh/up0000000000CC")])

    def test_invalid_ids_and_states_never_rank(self):
        fx = Fixture(self)
        fx.record(install=INSTALL.upper())
        fx.record(install=uuid_n(2), hidden=0)
        fx.record(install=uuid_n(3), source="cli")
        fx.record(install=uuid_n(4), seq="1x")
        fx.session("apofis-push-%s-1-2" % uuid_n(5), hidden=1, system_prompt=prompt(VALID))
        self.assertEqual(self.load(fx), [])

    def test_prompt_via_system_prompts(self):
        fx = Fixture(self)
        fx.w.execute("INSERT INTO system_prompts VALUES ('h1', ?)", (prompt(VALID),))
        fx.record(raw_prompt="", prompt_hash="h1")
        fx.w.execute("UPDATE sessions SET system_prompt = NULL")
        self.assertEqual(self.load(fx), [("apofis-push-%s-1" % INSTALL, VALID)])

    def test_bad_prompts_skipped(self):
        for raw in ("", "x", '{"v":2,"endpoint":"%s"}' % VALID, '{"v":true,"endpoint":"%s"}' % VALID,
                    '{"v":1,"endpoint":7}', '{"v":1,"endpoint":"%s","x":1}' % VALID, "[1]",
                    json.dumps({"v": 1, "endpoint": VALID + "#" + "a" * 1100}),
                    (prompt(VALID) + " " * 1025)[:1025]):  # parses, but over 1024 characters
            with self.subTest(raw=raw[:40]):
                fx = Fixture(self)
                fx.record(raw_prompt=raw)
                self.assertEqual(self.load(fx), [])

    def test_ttl_boundary(self):
        fx = Fixture(self)
        fx.record(install=uuid_n(1), started=NOW - ap.TTL)
        fx.record(install=uuid_n(2), started=NOW - ap.TTL - 0.5)
        self.assertEqual([r[0] for r in self.load(fx)], ["apofis-push-%s-1" % uuid_n(1)])

    def test_32_valid_installs_despite_invalid_groups(self):
        fx = Fixture(self)
        for i in range(40):
            fx.record(install=uuid_n(i), started=NOW - 1000 - i)
        for i in range(60):
            fx.record(install=uuid_n(100 + i).upper(), started=NOW - i)
            fx.record(install=uuid_n(200 + i), seq="0%d" % (i + 1), started=NOW - i)
        regs = self.load(fx)
        self.assertEqual(len(regs), 32)
        self.assertEqual([r[0] for r in regs], ["apofis-push-%s-1" % uuid_n(i) for i in range(32)])
        conn = fx.ro()
        rows, more = ap.registration_rows(conn, NOW, None)
        conn.close()
        self.assertTrue(more)

    def test_one_install_does_not_crowd_others(self):
        fx = Fixture(self)
        for s in range(1, 501):
            fx.record(seq=str(s), started=NOW - 1 + s / 1000)
        for i in range(1, 6):
            fx.record(install=uuid_n(i), started=NOW - 100 - i)
        regs = self.load(fx)
        self.assertEqual(len(regs), 6)
        self.assertEqual(regs[0][0], "apofis-push-%s-500" % INSTALL)

    def test_only_install(self):
        fx = Fixture(self)
        fx.record()
        fx.record(install=uuid_n(1))
        self.assertEqual([r[0] for r in self.load(fx, install=uuid_n(1))], ["apofis-push-%s-1" % uuid_n(1)])

    def test_huge_prompt_fetched_truncated(self):
        fx = Fixture(self)
        rid = fx.record(raw_prompt="x" * (10 << 20))
        conn = fx.ro()
        try:
            sql = ap.PROMPT_SQL % "?"
            ((got_id, text),) = conn.execute(sql, (rid,)).fetchall()
            self.assertEqual(len(text), 1025)
            self.assertEqual(ap.load_registrations(conn, NOW, None), [])
        finally:
            conn.close()

    def test_ranking_reads_only_narrow_columns(self):
        fx = Fixture(self)
        fx.record()
        conn = fx.ro()
        try:
            root = conn.execute("SELECT rootpage FROM sqlite_master WHERE name = 'sessions'").fetchone()[0]
            cols = [r[1] for r in conn.execute("PRAGMA table_info(sessions)")]
            ops = conn.execute("EXPLAIN " + ap.RANK_SQL, {"cutoff": 0, "install": None}).fetchall()
            cursors = {r[2] for r in ops if r[1] == "OpenRead" and r[3] == root}
            self.assertTrue(cursors)
            used = {cols[r[3]] for r in ops if r[1] == "Column" and r[2] in cursors}
            self.assertLessEqual(used, {"id", "started_at", "source", "hidden"})
            self.assertNotIn("system_prompt", used)
            plan = " ".join(r[3] for r in conn.execute("EXPLAIN QUERY PLAN " + ap.RANK_SQL,
                                                       {"cutoff": 0, "install": None}))
            self.assertIn("sqlite_autoindex_sessions_1", plan, plan)
        finally:
            conn.close()


class SnapshotTest(unittest.TestCase):
    def tracked(self):
        """Patch the script's sqlite3.connect so each connection records whether it was closed.
        (In-process, SQLite may keep a closed connection's fd while the fixture writer holds locks on
        the same file, so fds are checked in the real child instead: ContainmentTest.)"""
        conns = []
        real = sqlite3.connect

        class Tracked(sqlite3.Connection):
            closed = False

            def close(self):
                self.closed = True
                super().close()

        def connect(*a, **k):
            conn = real(*a, factory=Tracked, **k)
            conns.append(conn)
            return conn
        patcher = mock.patch.object(ap.sqlite3, "connect", connect)
        patcher.start()
        self.addCleanup(patcher.stop)
        return conns

    def test_connection_closed_before_network(self):
        fx = Fixture(self)
        fx.app()
        fx.record()
        conns = self.tracked()
        seen = []

        def fn(req, timeout):
            seen.append([c.closed for c in conns])
            return FakeResp(200)
        code, opener = fx.send(opener=FakeOpener(fn))
        self.assertEqual(seen, [[True]])

    def test_delay_after_close_and_only_for_finished(self):
        fx = Fixture(self)
        fx.app()
        fx.record()
        conns = self.tracked()
        slept = []
        sleep = lambda s: slept.append((s, [c.closed for c in conns]))
        fx.run(["send", "--session", APP, "--kind", "finished", "--state-db", fx.db, *ORIGIN],
               opener=FakeOpener(), now=lambda: NOW, sleep=sleep)
        self.assertEqual(slept, [(2.0, [True])])
        slept.clear()
        fx.run(["send", "--session", APP, "--kind", "needs_input", "--state-db", fx.db, *ORIGIN],
               opener=FakeOpener(), now=lambda: NOW, sleep=sleep)
        self.assertEqual(slept, [])

    def test_concurrent_writer_not_blocked(self):
        fx = Fixture(self)
        fx.app()
        fx.record()
        writer = sqlite3.connect(fx.db, timeout=0.5, isolation_level=None)
        self.addCleanup(writer.close)
        done = []
        clock = [0.0]

        def monotonic():
            clock[0] += 0.001
            if not done:
                writer.execute("INSERT INTO messages (session_id, content) VALUES ('x', 'y')")
                done.append(True)
            return clock[0]
        # A long ranking scan so the progress handler fires inside the read transaction.
        for s in range(1, 3000):
            fx.record(install=uuid_n(s))
        code, opener = fx.send(monotonic=monotonic)
        self.assertEqual(done, [True])
        self.assertEqual(len(opener.calls), 32)

    def test_database_unchanged_after_404_and_410(self):
        for status in (404, 410):
            with self.subTest(status=status):
                fx = Fixture(self)
                fx.app()
                fx.record()
                fx.w.execute("PRAGMA wal_checkpoint(TRUNCATE)")
                before = fx.digest()
                code, opener = fx.send(opener=FakeOpener(http_error(status)))
                self.assertEqual(len(opener.calls), 1)
                self.assertEqual(fx.digest(), before)
                self.assertIn("https://ntfy.sh=%d" % status, fx.log_text())

    def test_unresolved_session_sends_nothing(self):
        fx = Fixture(self)
        fx.session("tip", source="cli")
        fx.record()
        code, opener = fx.send(session="tip")
        self.assertEqual(opener.calls, [])
        self.assertIn("on_session_end skipped session", fx.log_text())

    def test_corrupt_database_fails_open(self):
        fx = Fixture(self)
        fx.close()
        with open(fx.db, "wb") as f:
            f.write(b"not a database" * 100)
        for n in ("-wal", "-shm"):
            open(fx.db + n, "wb").close()
        code, opener = fx.send()
        self.assertEqual((code, opener.calls), (0, []))
        self.assertIn("skipped db", fx.log_text())


class EndpointTest(unittest.TestCase):
    O = [VEC["origin"]]

    def test_valid(self):
        for url in VEC["valid"]:
            self.assertEqual(ap.validate_endpoint(url, self.O, []), ("https://ntfy.sh", url))

    def test_negatives(self):
        for case in VEC["negatives"]:
            with self.subTest(url=case["url"]):
                self.assertEqual(ap.validate_endpoint(case["url"], self.O, []), case["reason"])

    def test_length(self):
        t = VEC["tooLong"]
        url = t["prefix"] + t["pad"] * (t["length"] - len(t["prefix"]))
        self.assertEqual(len(url), 513)
        self.assertEqual(ap.validate_endpoint(url, self.O, []), "length")
        ok = "https://ntfy.sh/upAbC123xyz_-9"
        self.assertEqual(ap.validate_endpoint(ok, self.O, []), ("https://ntfy.sh", ok))
        self.assertEqual(ap.validate_endpoint(None, self.O, []), "length")

    def test_loopback(self):
        lb = VEC["loopback"]
        port = 41234
        fill = lambda s: s.replace("{port1}", str(port + 1)).replace("{port}", str(port))
        origin = fill(lb["httpOrigin"])
        self.assertEqual(ap.validate_endpoint(fill(lb["valid"]), self.O, [origin]), (origin, fill(lb["valid"])))
        for case in lb["negatives"]:
            with self.subTest(url=case["url"]):
                http = [fill(case["httpOrigin"])] if case["httpOrigin"] else []
                self.assertEqual(ap.validate_endpoint(fill(case["url"]), self.O, http), case["reason"])

    def test_https_origin_list_refuses_loopback_record(self):
        self.assertEqual(ap.validate_endpoint("http://127.0.0.1:41234/upWIRECHECK012?up=1", self.O, []), "scheme")


class SendTest(unittest.TestCase):
    def test_exact_bodies_and_headers(self):
        self.assertEqual(ap.push_body(APP, "finished"), b'{"session_id":"%s","kind":"finished"}' % APP.encode())
        self.assertEqual(ap.push_body(None, "needs_input"), b'{"kind":"needs_input"}')
        fx = Fixture(self)
        fx.app()
        fx.record()
        code, opener = fx.send(kind="needs_input")
        self.assertEqual(code, 0)
        ((req, timeout),) = opener.calls
        self.assertEqual(timeout, 5.0)
        self.assertEqual(req.full_url, VALID)
        self.assertEqual(req.get_method(), "POST")
        self.assertEqual(req.data, b'{"session_id":"%s","kind":"needs_input"}' % APP.encode())
        names = {k.lower(): v for k, v in req.header_items()}
        self.assertEqual(names, {"content-type": "application/json", "x-unifiedpush": "1"})

    def test_anonymous_needs_input(self):
        fx = Fixture(self)
        fx.record()
        code, opener = fx.send(session="", kind="needs_input")
        ((req, _),) = opener.calls
        self.assertEqual(req.data, b'{"kind":"needs_input"}')
        self.assertRegex(fx.log_text(), r"on_human_input_request matched regs=1 https://ntfy\.sh=200\n$")

    def test_compressed_session_pushes_root(self):
        fx = Fixture(self)
        fx.app(end_reason="compression")
        fx.session("tip", parent=APP)
        fx.record()
        code, opener = fx.send(session="tip")
        self.assertEqual(opener.calls[0][0].data, b'{"session_id":"%s","kind":"finished"}' % APP.encode())

    def test_proxy_environment_ignored(self):
        server = Server(self)
        dead = socket.socket()
        dead.bind(("127.0.0.1", 0))
        proxy = "http://127.0.0.1:%d" % dead.getsockname()[1]
        dead.close()
        env = {"http_proxy": proxy, "HTTP_PROXY": proxy, "https_proxy": proxy, "all_proxy": proxy}
        with mock.patch.dict(os.environ, env):
            os.environ.pop("no_proxy", None)
            os.environ.pop("NO_PROXY", None)
            status = ap.post(server.endpoint, b"{}", 5, ap.build_opener(None))
        self.assertEqual(status, "200")
        self.assertEqual(len(server.requests), 1)

    def test_parallel_timeouts(self):
        fx = Fixture(self)
        fx.app()
        for i in range(32):
            fx.record(install=uuid_n(i))
        barrier = threading.Barrier(32, timeout=WATCHDOG)

        def fn(req, timeout):
            barrier.wait()  # all 32 in flight at once: the batch takes one timeout period
            raise urllib.error.URLError(socket.timeout("timed out"))
        code, opener = fx.send(opener=FakeOpener(fn))
        self.assertEqual(len(opener.calls), 32)
        self.assertEqual({t for _, t in opener.calls}, {5.0})
        self.assertIn("regs=32 " + " ".join(["https://ntfy.sh=timeout"] * 32), fx.log_text())

    def test_redirect_not_followed(self):
        server = Server(self, status=302, location="/upOTHER0000000?up=1")
        status = ap.post(server.endpoint, b"{}", 5, ap.build_opener(None))
        self.assertEqual(status, "302")
        self.assertEqual([r[0] for r in server.requests], ["/upWIRECHECK012?up=1"])

    def test_large_response_cut(self):
        resp = FakeResp(200, b"x" * (10 << 20))
        self.assertEqual(ap.post(VALID, b"{}", 5, FakeOpener(lambda r, t: resp)), "200")
        self.assertEqual(resp.reads, [1024])

    def test_errors_classified(self):
        cases = [
            (urllib.error.URLError(ssl.SSLCertVerificationError("bad cert")), "tls"),
            (ssl.SSLError("eof"), "tls"),
            (urllib.error.URLError(socket.gaierror(-2, "Name or service not known")), "dns"),
            (urllib.error.URLError(socket.timeout("t")), "timeout"),
            (socket.timeout("t"), "timeout"),
            (urllib.error.URLError(ConnectionRefusedError(111, "refused")), "connect"),
            (ValueError("x"), "error"),
        ]
        for exc, want in cases:
            with self.subTest(want=want):
                def fn(req, timeout, exc=exc):
                    raise exc
                self.assertEqual(ap.post(VALID, b"{}", 5, FakeOpener(fn)), want)

    def test_refused_records_not_sent(self):
        fx = Fixture(self)
        fx.app()
        fx.record(endpoint="https://evil.example/upAbC123xyz_-9")
        fx.record(install=uuid_n(1), raw_prompt="nope")
        code, opener = fx.send()
        self.assertEqual(opener.calls, [])
        self.assertIn("regs=2 ", fx.log_text())
        self.assertIn("refused:origin=skipped", fx.log_text())
        self.assertIn("refused:prompt=skipped", fx.log_text())

    def test_32_plus(self):
        fx = Fixture(self)
        fx.app()
        for i in range(33):
            fx.record(install=uuid_n(i))
        code, opener = fx.send()
        self.assertEqual(len(opener.calls), 32)
        self.assertIn("regs=32+ ", fx.log_text())

    def test_deadline_handler(self):
        fx = Fixture(self)
        exits = []
        handler = ap.deadline_handler(fx.log, "on_session_end", exit=exits.append)
        handler(signal.SIGALRM, None)
        self.assertEqual(exits, [0])
        self.assertRegex(fx.log_text(), r"^\S+ on_session_end deadline\n$")


class LogTest(unittest.TestCase):
    def test_hostile_values_never_logged(self):
        fx = Fixture(self)
        fx.app()
        hostile = "https://evil.example/upAbC123xyz_-9?up=1"
        fx.record(endpoint=hostile)
        fx.record(install=uuid_n(1), endpoint="https://ntfy.sh/upSECRETTOPIC1")
        code, opener = fx.send()
        text = fx.log_text()
        for bad in ("evil", "upAbC", "SECRETTOPIC", APP, INSTALL, fx.db, fx.home, "op-"):
            self.assertNotIn(bad, text)
        self.assertRegex(text, r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ on_session_end matched regs=2 "
                               r"(refused:origin=skipped|https://ntfy\.sh=200) "
                               r"(refused:origin=skipped|https://ntfy\.sh=200)\n$")

    def test_mode_0600(self):
        fx = Fixture(self)
        ap.write_log(fx.log, "line")
        self.assertEqual(os.stat(fx.log).st_mode & 0o777, 0o600)
        os.chmod(fx.log, 0o644)
        ap.write_log(fx.log, "line")
        self.assertEqual(os.stat(fx.log).st_mode & 0o777, 0o600)

    def test_rotation(self):
        fx = Fixture(self)
        with open(fx.log, "w") as f:
            f.write(("x" * 99 + "\n") * 2700)
        ap.write_log(fx.log, "last")
        size = os.path.getsize(fx.log)
        self.assertLessEqual(size, ap.LOG_KEEP)
        with open(fx.log) as f:
            text = f.read()
        self.assertTrue(text.endswith("\nlast\n"))
        self.assertTrue(text.startswith("x" * 99 + "\n"))

    def test_concurrent_writers_never_interleave(self):
        fx = Fixture(self)
        code = ("import sys; sys.path.insert(0, %r); import apofis_push as a; a.LOG_MAX = 20000; a.LOG_KEEP = 10000\n"
                "for i in range(1000): a.write_log(%r, 'w%%s-%%04d-' %% (sys.argv[1], i) + 'p' * 60)\n"
                % (HERE, fx.log))
        procs = [subprocess.Popen([sys.executable, "-I", "-c", code, n]) for n in ("a", "b")]
        for p in procs:
            self.assertEqual(p.wait(WATCHDOG), 0)
        with open(fx.log) as f:
            lines = f.read().split("\n")
        self.assertEqual(lines[-1], "")
        lines = lines[:-1]
        self.assertTrue(lines)
        for line in lines:
            self.assertRegex(line, r"^w[ab]-\d{4}-p{60}$")
        self.assertLessEqual(os.path.getsize(fx.log), 20000)
        last = {line[1] for line in lines[-50:]}
        self.assertTrue(any(line.startswith(("wa-0999", "wb-0999")) for line in lines))
        self.assertTrue(last)

    def test_log_lock_timeout_skips_line(self):
        fx = Fixture(self)
        held = fx.hold(fx.log + ".lock")
        self.addCleanup(os.close, held)
        clock = [0.0]
        sleeps = []

        def sleep(s):
            sleeps.append(s)
            clock[0] += s
        ap.write_log(fx.log, "x", monotonic=lambda: clock[0], sleep=sleep)
        self.assertEqual(fx.log_text(), "")
        self.assertGreaterEqual(clock[0], 1.0)
        self.assertLess(clock[0], 1.1)


class ResourceTest(unittest.TestCase):
    def big(self):
        fx = Fixture(self)
        fx.app()
        fx.w.execute("BEGIN")
        for s in range(1, 50001):
            fx.record(seq=str(s), started=NOW - s)
        fx.w.execute("COMMIT")
        return fx

    def reserve(self, fx):
        fd = os.open(fx.slot_path(0), os.O_RDWR | os.O_CREAT, 0o600)
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return fd

    def test_query_budget_fails_open_and_releases_slots(self):
        fx = self.big()
        fd = self.reserve(fx)
        clock = [0.0]
        calls = []

        def monotonic():
            calls.append(1)
            clock[0] += 0.1
            return clock[0]
        old = signal.getsignal(signal.SIGALRM)
        code, opener = fx.send(extra=["--slot-fd", str(fd)], monotonic=monotonic)
        self.assertEqual(code, 0)
        self.assertEqual(opener.calls, [])
        self.assertRegex(fx.log_text(), r"on_session_end skipped db-budget\n$")
        self.assertGreater(len(calls), 10)
        self.assertTrue(fx.lockable(fx.slot_path(0)) and fx.lockable(fx.slot_path(1)))
        self.assertEqual(signal.alarm(0), 0, "the 25 s deadline alarm is cleared")
        self.assertIs(signal.getsignal(signal.SIGALRM), old)
        with open(fx.slot_path(0)) as f:
            self.assertEqual(f.read(), str(os.getpid()))

    def test_real_clock_finishes_within_budget(self):
        fx = self.big()
        code, opener = fx.send()
        self.assertEqual(len(opener.calls), 1)

    def test_log_lock_timeout_does_not_shorten_deadline(self):
        fx = Fixture(self)
        fx.app()
        fx.record()
        held = fx.hold(fx.log + ".lock")
        self.addCleanup(os.close, held)
        clock = [0.0]
        alarms = []
        real_alarm = signal.alarm

        def sleep(s):
            clock[0] += s

        def alarm(n):
            alarms.append(n)
            return real_alarm(n)
        with mock.patch.object(ap.signal, "alarm", alarm):
            code, opener = fx.send(monotonic=lambda: clock[0], sleep=sleep)
        self.assertEqual(code, 0)
        self.assertEqual(len(opener.calls), 1)
        self.assertEqual(fx.log_text(), "")
        self.assertEqual(alarms, [25, 0], "only the outer deadline uses the alarm")


class CheckTest(unittest.TestCase):
    def test_report(self):
        fx = Fixture(self)
        fx.record(endpoint="https://ntfy.sh/upSECRETTOPIC1", started=NOW - 3 * 86400)
        fx.record(install=uuid_n(1), endpoint="https://evil.example/upSECRETTOPIC2")
        code, out = fx.run(["check", *ORIGIN, "--state-db", fx.db], env=fx.env(SSL_CERT_FILE="/x"),
                           now=lambda: NOW)
        self.assertEqual(code, 0)
        self.assertNotIn("SECRETTOPIC", out)
        self.assertNotIn("evil", out)
        self.assertIn("python %d.%d" % sys.version_info[:2], out)
        self.assertIn("db opened read-only", out)
        self.assertIn("SSL_CERT_FILE set", out)
        self.assertIn("origins https://ntfy.sh", out)
        self.assertIn("apofis-push-0f8fad5b...-1 valid https://ntfy.sh age=3d", out)
        self.assertIn("apofis-push-00000001...-1 refused origin age=0d", out)
        self.assertEqual(os.listdir(fx.home), [])

    def test_ids_listing(self):
        fx = Fixture(self)
        fx.record(seq="1", hidden=0, started=NOW - 400 * 86400)
        fx.record(seq="01")
        fx.record(install=uuid_n(1))
        fx.session("apofis-push-%s-2" % INSTALL, source="cli", title="t\tx\ny")
        code, out = fx.run(["check", "--only-install", INSTALL, "--ids", "--state-db", fx.db], now=lambda: NOW)
        self.assertEqual(code, 0)
        rows = [line.split("\t") for line in out.splitlines() if line.startswith("apofis-push-")]
        self.assertEqual(rows, [
            ["apofis-push-%s-01" % INSTALL, "apofis-push-%s-01 op-0123456789abcdef" % INSTALL, "api_server", "1"],
            ["apofis-push-%s-1" % INSTALL, "apofis-push-%s-1 op-0123456789abcdef" % INSTALL, "api_server", "0"],
            ["apofis-push-%s-2" % INSTALL, "t?x?y", "cli", "0"],
        ])

    def test_ids_requires_install(self):
        fx = Fixture(self)
        self.assertEqual(fx.run(["check", "--ids", "--state-db", fx.db])[0], 2)

    def test_failed_open_exits_3(self):
        fx = Fixture(self)
        code, out = fx.run(["check", "--only-install", INSTALL, "--ids", "--state-db",
                            os.path.join(fx.dir, "missing.db")])
        self.assertEqual(code, 3)
        self.assertNotIn("apofis-push-", out)
        fx.close()
        with open(fx.db, "wb") as f:
            f.write(b"garbage" * 1000)
        for n in ("-wal", "-shm"):
            open(fx.db + n, "wb").close()
        code, out = fx.run(["check", "--only-install", INSTALL, "--ids", "--state-db", fx.db])
        self.assertEqual(code, 3)


if __name__ == "__main__":
    unittest.main()
