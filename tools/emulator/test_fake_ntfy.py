"""Tests for fake_ntfy.py: python3 -I -m unittest discover -s tools/emulator -p 'test_*.py' -v

Every server here binds 127.0.0.1; nothing contacts another host. The trigger tests marked "real" spawn the real
hook script against the fake's own run-private synthetic database.
"""
from __future__ import annotations

import http.client
import json
import os
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import unittest

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, HERE)
import fake_ntfy  # noqa: E402

# Containment: whatever these tests (or a deliberately broken fake) write through HOME or HERMES_HOME lands in a
# temporary directory, never in the real account.
_CONTAINER = tempfile.TemporaryDirectory()
os.environ["HOME"] = os.path.join(_CONTAINER.name, "home")
os.environ["HERMES_HOME"] = os.path.join(_CONTAINER.name, "hermes-home")
os.makedirs(os.environ["HOME"])

SCRIPT = os.path.normpath(os.path.join(HERE, "..", "..", "server", "apofis-push", "apofis_push.py"))
TOPIC = "upAbC123xyz09q"
INSTALL = "0f8fad5b-d9cb-469f-a165-70867728950e"
WATCHDOG = 30.0


def request(port, method, path, body=None, headers=None):
    c = http.client.HTTPConnection("127.0.0.1", port, timeout=WATCHDOG)
    try:
        c.request(method, path, body=body, headers=headers or {})
        r = c.getresponse()
        return r.status, r.read()
    finally:
        c.close()


def wait_until(what, condition, timeout=WATCHDOG):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        if condition():
            return
        time.sleep(0.01)
    raise AssertionError("not seen within %ss: %s" % (timeout, what))


def alive(pid):
    try:
        with open("/proc/%d/stat" % pid) as f:
            return f.read().rsplit(")", 1)[1].split()[0] != "Z"
    except OSError:
        return False


def slot_pid(home):
    """The pid a hook child published in a slot file, or None."""
    for name in fake_ntfy.SLOTS:
        try:
            with open(os.path.join(home, "agent-hooks", name), "rb") as f:
                v = f.read(32)
        except OSError:
            continue
        if v.isdigit():
            return int(v)
    return None


def open_files(pid):
    """(path, opened for writing) of each descriptor of pid."""
    out = set()
    for fd in os.listdir("/proc/%d/fd" % pid):
        path = os.readlink("/proc/%d/fd/%s" % (pid, fd))
        with open("/proc/%d/fdinfo/%s" % (pid, fd)) as info:
            flags = int(next(l for l in info if l.startswith("flags:")).split()[1], 8)
        out.add((path, flags & 3 != 0))
    return out


def tree(root):
    """Every path under root with its type, size and mtime: what changed shows."""
    out = {}
    for d, dirs, files in os.walk(root):
        for n in dirs + files:
            p = os.path.join(d, n)
            st = os.lstat(p)
            out[os.path.relpath(p, root)] = (st.st_mode, st.st_size, st.st_mtime_ns)
    return out


class Base(unittest.TestCase):
    def fake(self, wake_install=INSTALL, runner=None):
        d = tempfile.TemporaryDirectory()
        self.addCleanup(d.cleanup)
        self.run_dir = d.name
        f = fake_ntfy.Fake(self.run_dir, 0, TOPIC, SCRIPT, wake_install, runner=runner)
        self.addCleanup(f.stop)
        f.start()
        self.f = f
        return f


class Http(Base):
    def test_binds_loopback_only(self):
        f = self.fake()
        self.assertEqual(("127.0.0.1", f.port), f.server.server_address)
        # Another loopback address of this host: nothing listens there for the fake.
        with self.assertRaises(ConnectionRefusedError):
            socket.create_connection(("127.0.0.2", f.port), timeout=5).close()

    def test_only_its_topic_and_poll(self):
        f = self.fake()
        self.assertEqual(200, request(f.port, "POST", "/" + TOPIC, b"a")[0])
        self.assertEqual(200, request(f.port, "POST", "/%s?up=1" % TOPIC, b"b")[0])
        for method, path in (("POST", "/upOTHER0000000"), ("POST", "/%s?up=2" % TOPIC), ("POST", "/%s/x" % TOPIC),
                             ("GET", "/" + TOPIC), ("GET", "/upOTHER0000000/poll"), ("GET", "/%s/poll?x=1" % TOPIC),
                             ("POST", "/%s/poll" % TOPIC), ("GET", "/trigger")):
            self.assertEqual(404, request(f.port, method, path, b"x" if method == "POST" else None)[0], path)
        self.assertEqual((200, b"a"), request(f.port, "GET", "/%s/poll" % TOPIC))
        self.assertEqual((200, b"b"), request(f.port, "GET", "/%s/poll" % TOPIC))
        self.assertEqual(204, request(f.port, "GET", "/%s/poll" % TOPIC)[0])

    def test_body_at_most_4096_bytes(self):
        f = self.fake()
        self.assertEqual(200, request(f.port, "POST", "/" + TOPIC, b"\xff" * 4096)[0])
        self.assertEqual(413, request(f.port, "POST", "/" + TOPIC, b"\xff" * 4097)[0])
        self.assertEqual((200, b"\xff" * 4096), request(f.port, "GET", "/%s/poll" % TOPIC))
        self.assertEqual(204, request(f.port, "GET", "/%s/poll" % TOPIC)[0])

    def test_at_most_64_queued(self):
        f = self.fake()
        for i in range(64):
            self.assertEqual(200, request(f.port, "POST", "/" + TOPIC, b"%d" % i)[0])
        self.assertEqual(429, request(f.port, "POST", "/" + TOPIC, b"65th")[0])
        self.assertEqual((200, b"0"), request(f.port, "GET", "/%s/poll" % TOPIC))
        self.assertEqual(200, request(f.port, "POST", "/" + TOPIC, b"again")[0])

    def test_at_most_8_handlers(self):
        f = self.fake()
        held = []
        for _ in range(8):  # each sends its headers and only part of the promised body, so its handler waits
            s = socket.create_connection(("127.0.0.1", f.port), timeout=WATCHDOG)
            s.sendall(b"POST /%s HTTP/1.0\r\nContent-Length: 10\r\n\r\nab" % TOPIC.encode())
            held.append(s)
        try:
            wait_until("8 busy handlers", lambda: f.active == 8)
            # The 9th is answered at once, before its request is read (so read the answer, send nothing).
            with socket.create_connection(("127.0.0.1", f.port), timeout=WATCHDOG) as s:
                self.assertTrue(s.recv(64).startswith(b"HTTP/1.0 503 "))
            self.assertEqual(8, f.active)
        finally:
            for s in held:
                s.close()
        wait_until("handlers free again", lambda: f.active == 0)
        self.assertEqual(200, request(f.port, "POST", "/" + TOPIC, b"later")[0])
        self.assertEqual((200, b"later"), request(f.port, "GET", "/%s/poll" % TOPIC))


class Trigger(Base):
    def setUp(self):
        self.calls = []
        self.child = "posts"  # what the stand-in hook does: posts (log line and body), body, log, twice, other, nothing

    def runner(self, cmd, env, event):
        """A stand-in for the hook: records its call and does what self.child says a child would have done."""
        self.calls.append((cmd, env, event))
        port = self.f.port
        line = "2026-11-23T00:00:00Z on_human_input_request matched regs=1 http://127.0.0.1:%d=200\n" % port
        if self.child in ("posts", "log", "twice", "other"):
            with open(os.path.join(env["HERMES_HOME"], "agent-hooks", "apofis-push.log"), "a") as log:
                log.write(line)
        if self.child == "polled":  # two posts with a poll between: the queue ends as one right body, received is two
            with open(os.path.join(env["HERMES_HOME"], "agent-hooks", "apofis-push.log"), "a") as log:
                log.write(line)
            request(port, "POST", "/%s?up=1" % TOPIC, fake_ntfy.WAKE_BODY)
            request(port, "GET", "/%s/poll" % TOPIC)
        bodies = {"posts": [fake_ntfy.WAKE_BODY], "body": [fake_ntfy.WAKE_BODY], "twice": [fake_ntfy.WAKE_BODY] * 2,
                  "other": [b'{"kind":"finished"}'], "polled": [fake_ntfy.WAKE_BODY]}.get(self.child, [])
        for b in bodies:
            request(port, "POST", "/%s?up=1" % TOPIC, b)

    def test_runs_exactly_the_fixed_command_with_the_run_private_environment(self):
        f = self.fake(runner=self.runner)
        self.assertEqual(200, request(f.port, "POST", "/trigger")[0])
        cmd, env, event = self.calls[0]
        self.assertEqual([sys.executable, "-I", SCRIPT, "hook", "--allow-http-origin", "http://127.0.0.1:%d" % f.port,
                          "--state-db", os.path.join(self.run_dir, "fixture.db"), "--delay", "0"], cmd)
        self.assertEqual({"HERMES_HOME", "PATH"}, set(env))
        self.assertEqual(os.path.join(self.run_dir, "home"), env["HERMES_HOME"])
        self.assertEqual({"hook_event_name": "on_human_input_request", "session_id": "",
                          "extra": {"kind": "approval", "platform": "api_server"}}, json.loads(event))

    def test_at_most_3_triggers(self):
        f = self.fake(runner=self.runner)
        for _ in range(3):
            self.assertEqual(200, request(f.port, "POST", "/trigger")[0])
            self.assertEqual((200, fake_ntfy.WAKE_BODY), request(f.port, "GET", "/%s/poll" % TOPIC))
        self.assertEqual(429, request(f.port, "POST", "/trigger")[0])
        self.assertEqual(3, len(self.calls))

    def test_a_trigger_without_proof_of_one_post_is_not_accepted(self):
        for child in ("nothing", "body", "log", "twice", "other", "polled"):
            self.calls = []
            self.child = child
            f = self.fake(runner=self.runner)
            with open(f.log_path(), "w") as log:  # an earlier run's line: only lines of this trigger count
                log.write("2026-11-23T00:00:00Z on_human_input_request matched regs=1 http://127.0.0.1:%d=200\n" % f.port)
            self.assertEqual(502, request(f.port, "POST", "/trigger")[0], child)
            self.assertEqual(1, len(self.calls))

    def test_a_queued_message_refuses_the_trigger(self):
        f = self.fake(runner=self.runner)
        self.assertEqual(200, request(f.port, "POST", "/" + TOPIC, fake_ntfy.WAKE_BODY)[0])  # preloaded, even the right body
        self.assertEqual(409, request(f.port, "POST", "/trigger")[0])
        self.assertEqual([], self.calls)

    def test_one_trigger_at_a_time_and_none_after_stop_began(self):
        entered, release = threading.Event(), threading.Event()

        def slow(cmd, env, event):
            entered.set()
            release.wait(WATCHDOG)
            self.runner(cmd, env, event)
        f = self.fake(runner=slow)
        first = threading.Thread(target=request, args=(f.port, "POST", "/trigger"), daemon=True)
        first.start()
        self.assertTrue(entered.wait(WATCHDOG))
        self.assertEqual(409, request(f.port, "POST", "/trigger")[0])
        release.set()
        first.join(WATCHDOG)
        f.stop()
        self.assertEqual(503, f.trigger())
        self.assertEqual(1, len(self.calls))

    def test_takes_no_input(self):
        f = self.fake(runner=self.runner)
        self.assertEqual(400, request(f.port, "POST", "/trigger", b"{}")[0])
        self.assertEqual(404, request(f.port, "POST", "/trigger?x=1")[0])
        self.assertEqual([], self.calls)

    def test_without_a_wake_install_there_is_no_fixture_and_no_trigger(self):
        f = self.fake(wake_install=None, runner=self.runner)
        self.assertEqual(409, request(f.port, "POST", "/trigger")[0])
        self.assertFalse(os.path.exists(os.path.join(self.run_dir, "fixture.db")))
        self.assertEqual([], self.calls)

    def test_fixture_holds_one_hidden_record_naming_the_fake(self):
        f = self.fake(runner=self.runner)
        import sqlite3
        c = sqlite3.connect("file:%s?mode=ro" % os.path.join(self.run_dir, "fixture.db"), uri=True)
        try:
            rows = c.execute("SELECT id, source, hidden, system_prompt FROM sessions").fetchall()
        finally:
            c.close()
        self.assertEqual([("apofis-push-%s-1" % INSTALL, "api_server", 1,
                           '{"v":1,"endpoint":"http://127.0.0.1:%d/%s?up=1"}' % (f.port, TOPIC))], rows)


class RealTrigger(Base):
    """Gate N3, Task 6 part: the real hook spawn, no injected runner."""

    def home(self):
        return os.path.join(self.run_dir, "home")

    def blocked_fake(self, **kw):
        """A fake whose topic handler waits until released, so the hook child stays alive inside its POST."""
        release = threading.Event()
        entered = threading.Event()
        f = self.fake(**kw)

        def hold(_body):
            entered.set()
            release.wait(WATCHDOG)
        f.before_queue = hold
        self.addCleanup(release.set)
        return f, entered, release

    def test_stop_before_slot_acquisition_waits_for_the_hook_and_accounts_for_its_child(self):
        entered, release = threading.Event(), threading.Event()

        def late(cmd, env, event):  # the hook parent, held before it reserves a slot and spawns its child
            entered.set()
            release.wait(WATCHDOG)
            fake_ntfy.run_hook(cmd, env, event)
        f = self.fake(runner=late)
        threading.Thread(target=request, args=(f.port, "POST", "/trigger"), daemon=True).start()
        self.assertTrue(entered.wait(WATCHDOG))
        result = []
        stopper = threading.Thread(target=lambda: result.append(f.stop()), daemon=True)
        stopper.start()
        stopper.join(1)
        self.assertTrue(stopper.is_alive(), "stop waits for the hook still starting")
        release.set()
        stopper.join(WATCHDOG)
        self.assertIn(result[0], ("released", "killed"))
        fixture = os.path.join(self.run_dir, "fixture.db").encode()
        for pid in [int(d) for d in os.listdir("/proc") if d.isdigit()]:  # no child of this fixture survives the stop
            try:
                with open("/proc/%d/cmdline" % pid, "rb") as c:
                    self.assertFalse(alive(pid) and fixture in c.read().split(b"\0"), pid)
            except OSError:
                pass

    def test_a_slot_held_by_a_foreign_process_is_never_signalled(self):
        f = self.fake()
        # It even looks like the hook's send child, in its own session, but for another fixture.
        dummy = subprocess.Popen([sys.executable, "-I", "-c", "import time; time.sleep(60)", SCRIPT, "send", "--state-db",
                                  os.path.join(self.run_dir, "other", "fixture.db")], start_new_session=True)
        self.addCleanup(dummy.kill)
        path = os.path.join(self.home(), "agent-hooks", fake_ntfy.SLOTS[0])
        with open(path, "w") as slot:
            slot.write(str(dummy.pid))
        fd = os.open(path, os.O_RDONLY)
        import fcntl
        fcntl.flock(fd, fcntl.LOCK_EX)  # held, with a pid that is not this fake's hook child
        try:
            self.assertEqual("unaccounted", f.account(0))
            self.assertIsNone(dummy.poll())
        finally:
            os.close(fd)

    def test_a_child_that_released_its_slot_but_lives_is_not_released(self):
        f = self.fake()
        child = subprocess.Popen([sys.executable, "-I", "-c", "import time; time.sleep(60)", SCRIPT, "send", "--state-db",
                                  os.path.join(self.run_dir, "fixture.db")], start_new_session=True)
        self.addCleanup(child.kill)
        with open(os.path.join(self.home(), "agent-hooks", fake_ntfy.SLOTS[0]), "w") as slot:
            slot.write(str(child.pid))  # published, slot not locked: released, still running
        self.assertEqual("unaccounted", f.account(0))

    def test_trigger_child_accounted_on_success(self):
        f = self.fake()
        self.assertEqual(200, request(f.port, "POST", "/trigger")[0])
        # Answered only after the child released its slot: it is gone, and its wake arrived.
        self.assertEqual("released", f.account(0))
        pid = slot_pid(self.home())
        self.assertIsNotNone(pid)
        self.assertFalse(alive(pid))
        self.assertEqual((200, b'{"kind":"needs_input"}'), request(f.port, "GET", "/%s/poll" % TOPIC))
        with open(os.path.join(self.home(), "agent-hooks", "apofis-push.log")) as log:
            self.assertRegex(log.read(), r"on_human_input_request matched regs=1 http://127\.0\.0\.1:%d=200\n$" % f.port)

    def test_trigger_child_accounted_on_interruption(self):
        f, entered, _release = self.blocked_fake()
        t = threading.Thread(target=request, args=(f.port, "POST", "/trigger"), daemon=True)
        t.start()
        self.assertTrue(entered.wait(WATCHDOG))
        pid = slot_pid(self.home())
        self.assertTrue(pid and alive(pid))
        started = time.monotonic()
        self.assertEqual("killed", f.stop())  # the teardown kills the child's process group
        self.assertLess(time.monotonic() - started, 4)  # not its own 5 s request timeout
        self.assertFalse(alive(pid))
        with self.assertRaises(ProcessLookupError):
            os.killpg(pid, 0)

    def test_trigger_child_accounted_on_timeout(self):
        f, entered, _release = self.blocked_fake()
        f.child_wait = 1
        status = []
        t = threading.Thread(target=lambda: status.append(request(f.port, "POST", "/trigger")[0]), daemon=True)
        t.start()
        self.assertTrue(entered.wait(WATCHDOG))
        pid = slot_pid(self.home())
        t.join(WATCHDOG)
        self.assertEqual([502], status)
        self.assertFalse(alive(pid))

    def test_no_live_db_opened(self):
        f = self.fake()
        fixture = os.path.join(self.run_dir, "fixture.db")
        # An exclusive lock on the fixture holds the child's read in its 0.5 s busy wait, with the database open.
        import sqlite3
        lock = sqlite3.connect(fixture, isolation_level=None)
        self.addCleanup(lock.close)
        lock.execute("PRAGMA locking_mode=EXCLUSIVE")
        lock.execute("BEGIN EXCLUSIVE")
        seen, argv, environ = set(), [], {}
        done = threading.Event()

        def watch():  # samples the child from its start until the trigger has answered
            while not done.is_set():
                p = slot_pid(self.home())
                try:
                    if p and alive(p):
                        if not argv:
                            with open("/proc/%d/cmdline" % p, "rb") as c:
                                argv.extend(c.read().split(b"\0"))
                            with open("/proc/%d/environ" % p, "rb") as e:
                                environ.update(v.split(b"=", 1) for v in e.read().split(b"\0") if b"=" in v)
                        seen.update(open_files(p))
                        cwd.add(os.readlink("/proc/%d/cwd" % p))
                except OSError:
                    pass
        cwd = set()
        w = threading.Thread(target=watch, daemon=True)
        w.start()
        self.assertEqual(502, request(f.port, "POST", "/trigger")[0])  # a skipped hook posted nothing: no proof
        done.set()
        w.join(WATCHDOG)
        lock.execute("COMMIT")
        self.assertEqual(fixture.encode(), argv[argv.index(b"--state-db") + 1])
        self.assertEqual([fixture.encode()], [a for a in argv if b".db" in a])
        self.assertEqual(self.home().encode(), environ[b"HERMES_HOME"])
        self.assertEqual({self.home()}, cwd)  # its working directory is the private home, not the checkout
        listening = "socket:[%d]" % os.fstat(f.server.fileno()).st_ino
        paths = {path for path, _ in seen}
        self.assertNotIn(listening, paths)  # the fake's own socket was not inherited
        for path, writable in seen:  # writes only inside the run directory; reads elsewhere (stdlib, CA file)
            inside = path.startswith(self.run_dir + "/") or path == "/dev/null" or path.startswith(("socket:[", "pipe:[", "anon_inode:"))
            self.assertTrue(inside or not writable, path)
        self.assertIn(fixture, paths)  # the sampling saw the read, and only that database
        self.assertLessEqual({p for p in paths if ".db" in p}, {fixture + s for s in ("", "-wal", "-shm")})
        with open(os.path.join(self.home(), "agent-hooks", "apofis-push.log")) as log:
            self.assertRegex(log.read(), r"on_human_input_request skipped db-busy\n$")

    def test_nothing_outside_run_dir(self):
        with tempfile.TemporaryDirectory() as root:
            for d in ("run", "home", "tmp"):
                os.mkdir(os.path.join(root, d))
            before = tree(root)
            env = {"PATH": os.environ.get("PATH", "/usr/bin:/bin"), "HOME": os.path.join(root, "home"),
                   "TMPDIR": os.path.join(root, "tmp")}
            with socket.socket() as s:  # a free port, then the fake on it
                s.bind(("127.0.0.1", 0))
                port = s.getsockname()[1]
            r, w = os.pipe()  # a descriptor the fake inherits (as from the harness's shell) and must close
            p = subprocess.Popen([sys.executable, "-I", os.path.join(HERE, "fake_ntfy.py"), "--run-dir",
                                  os.path.join(root, "run"), "--port", str(port), "--topic", TOPIC, "--script", SCRIPT,
                                  "--wake-install", INSTALL], cwd=root, env=env, stdout=subprocess.PIPE,
                                 stderr=subprocess.DEVNULL, pass_fds=(w,))
            try:
                self.assertEqual(b"READY\n", p.stdout.readline())
                inherited = "pipe:[%d]" % os.fstat(w).st_ino
                self.assertNotIn(inherited, {path for path, _ in open_files(p.pid)})
                os.close(w)
                os.close(r)
                self.assertEqual(200, request(port, "POST", "/trigger")[0])
                self.assertEqual((200, b'{"kind":"needs_input"}'), request(port, "GET", "/%s/poll" % TOPIC))
            finally:
                p.send_signal(signal.SIGTERM)
                self.assertEqual(0, p.wait(WATCHDOG))
                p.stdout.close()
            after = tree(root)
            outside = {k: v for k, v in after.items() if k != "run" and not k.startswith("run/")}
            self.assertEqual({k: v for k, v in before.items() if k != "run"}, outside)
            self.assertIn("run/fixture.db", after)
            self.assertIn("run/home/agent-hooks/apofis-push.log", after)
            with socket.socket() as s:  # the port is closed after the teardown
                self.assertNotEqual(0, s.connect_ex(("127.0.0.1", port)))


    def test_owner_loss_stops_the_fake_and_removes_its_files(self):
        with tempfile.TemporaryDirectory() as root:
            run = os.path.join(root, "run")
            os.mkdir(run)
            with socket.socket() as s:
                s.bind(("127.0.0.1", 0))
                port = s.getsockname()[1]
            r, w = os.pipe()
            owner = subprocess.Popen(["sleep", "60"], pass_fds=(w,))  # a dummy owner holding the pipe's write end
            p = subprocess.Popen([sys.executable, "-I", os.path.join(HERE, "fake_ntfy.py"), "--run-dir", run, "--port",
                                  str(port), "--topic", TOPIC, "--script", SCRIPT, "--wake-install", INSTALL,
                                  "--owner-stdin"], stdin=r, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL)
            os.close(r)
            os.close(w)
            try:
                self.assertEqual(b"READY\n", p.stdout.readline())
                self.assertEqual(200, request(port, "POST", "/trigger")[0])  # a real child ran and left its files
                self.assertTrue(os.path.exists(os.path.join(run, "home", "agent-hooks", "apofis-push.log")))
                owner.kill()  # the owner dies abruptly; nothing signals the fake
                owner.wait(WATCHDOG)
                self.assertEqual(0, p.wait(WATCHDOG))
                self.assertEqual(b"stopped: child released, owner gone", p.stdout.read().splitlines()[-1])
            finally:
                if p.poll() is None:
                    p.kill()
                p.stdout.close()
            self.assertEqual([], os.listdir(run))
            with socket.socket() as s:
                self.assertNotEqual(0, s.connect_ex(("127.0.0.1", port)))

    def test_length_header_of_ascii_digits_only(self):
        f = self.fake()
        for value in ("\u00b2", "123456", "1e3", "-1"):
            s = socket.create_connection(("127.0.0.1", f.port), timeout=WATCHDOG)
            with s:
                s.sendall(("POST /%s HTTP/1.0\r\nContent-Length: %s\r\n\r\n" % (TOPIC, value)).encode("utf-8"))
                self.assertTrue(s.recv(64).startswith(b"HTTP/1.0 400 "), value)
        self.assertEqual(400, request(f.port, "POST", "/" + TOPIC, b"x", {"Transfer-Encoding": "chunked"})[0])
        self.assertEqual(204, request(f.port, "GET", "/%s/poll" % TOPIC)[0])


class Cli(unittest.TestCase):
    def test_bad_arguments_refused(self):
        with tempfile.TemporaryDirectory() as d:
            for bad in (["--topic", "up123"], ["--topic", "upAbC123xyz_-9"], ["--port", "80"],
                        ["--wake-install", "not-a-uuid"], ["--run-dir", os.path.join(d, "missing")]):
                args = {"--run-dir": d, "--port": "18999", "--topic": TOPIC, "--script": SCRIPT}
                args.update(dict(zip(bad[::2], bad[1::2])))
                p = subprocess.run([sys.executable, "-I", os.path.join(HERE, "fake_ntfy.py")]
                                   + [x for kv in args.items() for x in kv], capture_output=True, timeout=WATCHDOG)
                self.assertEqual(2, p.returncode, bad)
            self.assertEqual([], os.listdir(d))


if __name__ == "__main__":
    unittest.main()
