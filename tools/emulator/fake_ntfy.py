#!/usr/bin/env python3
"""A run-private stand-in for ntfy, for the emulator harness (tools/emulator/README.md).

    python3 -I fake_ntfy.py --run-dir D --port P --topic T --script apofis_push.py [--wake-install UUID]

Binds 127.0.0.1:P only. POST /T (or /T?up=1) queues a message (at most 4096 bytes, at most 64 queued); GET /T/poll
answers the oldest one and removes it (204 when none). At most 8 requests are handled at once.

POST /trigger (no input, at most 3 times, one at a time, only with an empty queue) runs the real hook script once
with a fixed anonymous approval event, against D/fixture.db: a synthetic WAL database built here at start with one
hidden record of the --wake-install, whose endpoint is this fake. The live state database is never opened. The script
runs in D/home with HERMES_HOME=D/home, so its slot locks and log stay in D, with only PATH besides. The trigger
answers once the detached child the script starts has released its slot (past CHILD_WAIT it kills the child's process
group), and answers 200 only with proof that this invocation's child posted exactly once to this fake: one new hook
log line "matched regs=1 <this origin>=200", exactly one message received meanwhile, and that message the fixed
anonymous body. With --owner-pid it starts only when that process is its parent. On SIGTERM, SIGINT, SIGHUP, or (with
--owner-pid) when its parent is no longer that owner, the fake admits
no further trigger, waits for a hook still starting, stops serving, kills any child still holding a slot, checks it
is gone and exits 0 (1 if one could not be accounted for); after an owner's death it also removes the files it
created when every child is accounted for. Python 3.9+ standard library.
"""
from __future__ import annotations

import argparse
import collections
import fcntl
import http.server
import json
import os
import re
import signal
import sqlite3
import subprocess
import sys
import threading
import time
import urllib.parse

MAX_BODY = 4096
MAX_QUEUE = 64
MAX_HANDLERS = 8
MAX_TRIGGERS = 3
HANDLER_TIMEOUT = 5  # seconds a connection has to send its request
CHILD_WAIT = 30.0  # the hook's child is ended by the kernel at 25 s
SLOTS = ("apofis-push.slot0.lock", "apofis-push.slot1.lock")  # the hook script's slot files (apofis_push.py)
WAKE_BODY = b'{"kind":"needs_input"}'  # what the hook sends for the event below (apofis_push.push_body)
EVENT = json.dumps({"hook_event_name": "on_human_input_request", "session_id": "",
                    "extra": {"kind": "approval", "platform": "api_server"}}).encode()
TOPIC_RE = re.compile(r"up[A-Za-z0-9]{12}")
UUID_RE = re.compile(r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
# The Hermes columns the hook's queries read (as in server/apofis-push/test_apofis_push.py).
SCHEMA = """
CREATE TABLE system_prompts (hash TEXT PRIMARY KEY, prompt TEXT NOT NULL);
CREATE TABLE sessions (
    id TEXT PRIMARY KEY, source TEXT NOT NULL, model_config TEXT, system_prompt TEXT, system_prompt_hash TEXT,
    parent_session_id TEXT, started_at REAL NOT NULL, end_reason TEXT, title TEXT,
    hidden INTEGER NOT NULL DEFAULT 0);
"""


def build_fixture(path: str, record_id: str, endpoint: str, now: float) -> None:
    """A new synthetic WAL database at path holding one hidden registration record."""
    if os.path.lexists(path):
        raise FileExistsError(path)
    c = sqlite3.connect(path, isolation_level=None)
    try:
        c.execute("PRAGMA journal_mode=WAL")
        c.executescript(SCHEMA)
        c.execute("INSERT INTO sessions (id, source, hidden, started_at, title, system_prompt) VALUES (?, 'api_server', 1, ?, ?, ?)",
                  (record_id, now, record_id + " op-0000000000000000",
                   json.dumps({"v": 1, "endpoint": endpoint}, separators=(",", ":"))))
    finally:
        c.close()


def run_hook(cmd: list, env: dict, event: bytes) -> None:
    """The hook parent, in the private home: the event on stdin, no output, no inherited descriptors; it returns at
    once (its detached child inherits that working directory)."""
    subprocess.run(cmd, input=event, env=env, cwd=env["HERMES_HOME"], stdout=subprocess.DEVNULL,
                   stderr=subprocess.DEVNULL, close_fds=True, timeout=10, check=False)


def say(line: str) -> None:
    sys.stdout.write(line + "\n")
    sys.stdout.flush()


class Fake:
    def __init__(self, run_dir: str, port: int, topic: str, script: str, wake_install: str | None, runner=None):
        self.run_dir = os.path.abspath(run_dir)
        self.topic = topic
        self.script = os.path.abspath(script)
        self.home = os.path.join(self.run_dir, "home")
        self.runner = runner or run_hook
        self.child_wait = CHILD_WAIT
        self.join_wait = 10.0  # how long stop waits for admitted triggers to answer
        self.kill = os.killpg  # test seam
        self.before_queue = lambda body: None  # test seam: runs in the handler before a message is queued
        self.queue: collections.deque = collections.deque()
        self.lock = threading.Lock()
        self.handlers = threading.BoundedSemaphore(MAX_HANDLERS)
        self.active = 0
        self.triggers = 0
        self.received = 0  # messages accepted for the topic
        self.closing = False  # set when stop begins: no trigger is admitted after it
        self.in_flight = 0  # admitted triggers not yet answered
        self.starting = 0  # hook parents still running (before or while they spawn their child)
        self.thread: threading.Thread | None = None
        os.makedirs(os.path.join(self.home, "agent-hooks"), mode=0o700, exist_ok=True)
        self.server = http.server.ThreadingHTTPServer(("127.0.0.1", port), Handler)
        self.server.daemon_threads = True
        self.server.fake = self  # type: ignore[attr-defined]
        self.port = self.server.server_address[1]
        self.fixture = None
        if wake_install:
            self.fixture = os.path.join(self.run_dir, "fixture.db")
            build_fixture(self.fixture, "apofis-push-%s-1" % wake_install,
                          "http://127.0.0.1:%d/%s?up=1" % (self.port, topic), time.time())

    def start(self) -> None:
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def command(self) -> list:
        return [sys.executable, "-I", self.script, "hook", "--allow-http-origin", "http://127.0.0.1:%d" % self.port,
                "--state-db", self.fixture, "--delay", "0"]

    def log_path(self) -> str:
        return os.path.join(self.home, "agent-hooks", "apofis-push.log")

    def log_size(self) -> int:
        try:
            return os.path.getsize(self.log_path())
        except OSError:
            return 0

    def env(self) -> dict:
        return {"HERMES_HOME": self.home, "PATH": os.environ.get("PATH", "/usr/bin:/bin")}

    def account(self, wait: float) -> str:
        """released: no hook child holds a slot (waited up to `wait`); killed: one did, its process group was killed
        and it is gone; unaccounted: one could not be killed or still holds its slot."""
        end = time.monotonic() + wait
        held = self.held()
        while held and time.monotonic() < end:
            time.sleep(0.05)
            held = self.held()
        if not held:
            return "released" if self.children_exited(5) else "unaccounted"
        signalled = []
        for pid in held:
            if pid is not None:
                try:
                    self.kill(pid, signal.SIGKILL)  # the child runs in its own session: its group id is its pid
                    signalled.append(pid)
                except OSError:
                    pass

        def remaining():  # a slot still held, or a signalled child not yet exited (a signal sent is not an exit)
            return self.held() or any(self.is_child(p) for p in signalled)
        end = time.monotonic() + 5
        while remaining() and time.monotonic() < end:
            time.sleep(0.05)
        gone = signalled and None not in held and not remaining() and self.children_exited(0)
        return "killed" if gone else "unaccounted"

    def children_exited(self, wait: float) -> bool:
        """Every child pid published in a slot file that is still this fixture's child has exited (a child releases
        its slot just before it exits)."""
        end = time.monotonic() + wait
        while True:
            pids = []
            for name in SLOTS:
                try:
                    with open(os.path.join(self.home, "agent-hooks", name), "rb") as f:
                        v = f.read(32)
                except OSError:
                    continue
                if v.isdigit() and self.is_child(int(v)):
                    pids.append(int(v))
            if not pids:
                return True
            if time.monotonic() >= end:
                return False
            time.sleep(0.02)

    def held(self) -> list:
        """The published pid (or None) of each slot a hook child of this home still holds."""
        out = []
        for name in SLOTS:
            try:
                fd = os.open(os.path.join(self.home, "agent-hooks", name), os.O_RDONLY | os.O_NOFOLLOW | os.O_CLOEXEC)
            except FileNotFoundError:
                continue
            try:
                try:
                    fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
                    fcntl.flock(fd, fcntl.LOCK_UN)
                    continue
                except OSError:
                    pass
                v = os.pread(fd, 32, 0)
                pid = int(v) if v.isdigit() else None
                out.append(pid if pid is not None and self.is_child(pid) else None)
            finally:
                os.close(fd)
        return out

    def is_child(self, pid: int) -> bool:
        """pid leads its own process group and runs this script's send: never signal anything else."""
        try:
            with open("/proc/%d/cmdline" % pid, "rb") as f:
                argv = f.read().split(b"\0")
            return (os.getpgid(pid) == pid and self.script.encode() in argv and b"send" in argv
                    and self.fixture is not None and self.fixture.encode() in argv)  # this fake's own fixture
        except OSError:
            return False

    def trigger(self) -> int:
        with self.lock:
            if self.closing:
                return 503
            if self.fixture is None or self.in_flight or self.queue:  # one at a time, from an empty topic only
                return 409
            if self.triggers >= MAX_TRIGGERS:
                return 429
            self.triggers += 1
            self.in_flight += 1
            self.starting += 1
            n, received, log_from = self.triggers, self.received, self.log_size()
        try:
            try:
                self.runner(self.command(), self.env(), EVENT)
                rc = "ran"
            except Exception as e:  # a hook that did not return in time: its child is still accounted below
                rc = type(e).__name__
            finally:
                with self.lock:
                    self.starting -= 1
            child = self.account(self.child_wait)
            proved = rc == "ran" and child == "released" and self.posted_once(received, log_from)
            say("trigger %d: hook %s, child %s, %s" % (n, rc, child, "posted once" if proved else "no proof of one post"))
            return 200 if proved else 502
        finally:
            with self.lock:
                self.in_flight -= 1

    def posted_once(self, received: int, log_from: int) -> bool:
        """Since the trigger began: one new hook log line saying it posted to this fake and got 200, exactly one message
        received, and the queue holding exactly that fixed body."""
        try:
            with open(self.log_path(), "rb") as f:
                f.seek(log_from)
                lines = f.read(4096).decode("ascii").splitlines()
        except (OSError, UnicodeDecodeError):
            return False
        line = re.compile(r"\S+ on_human_input_request matched regs=1 http://127\.0\.0\.1:%d=200" % self.port)
        with self.lock:
            return (len(lines) == 1 and bool(line.fullmatch(lines[0])) and self.received == received + 1
                    and list(self.queue) == [WAKE_BODY])

    def stop(self) -> str:
        """Admits no further trigger, waits (bounded) for a hook still starting its child, stops serving, then accounts
        for every hook child at once (killing a holder) and waits for the admitted triggers to answer. Safe to call
        twice."""
        with self.lock:
            self.closing = True
        end = time.monotonic() + 15  # the hook parent's own timeout is 10 s
        while self.starting and time.monotonic() < end:
            time.sleep(0.05)
        late = self.starting > 0
        if self.thread is not None:
            self.server.shutdown()
            self.thread = None
        self.server.server_close()
        child = self.account(0)
        end = time.monotonic() + self.join_wait
        while self.in_flight and time.monotonic() < end:
            time.sleep(0.05)
        return "unaccounted" if late or self.in_flight else child  # a join that timed out proves nothing

    def remove_private(self) -> None:
        """The files this fake and its hook children created in the run directory (named files, then empty dirs)."""
        names = [os.path.join(self.home, "agent-hooks", n) for n in SLOTS + ("apofis-push.log", "apofis-push.log.lock")]
        if self.fixture:
            names += [self.fixture + s for s in ("", "-wal", "-shm", "-journal")]
        for p in names:
            try:
                os.unlink(p)
            except FileNotFoundError:
                pass
        for d in (os.path.join(self.home, "agent-hooks"), self.home):
            try:
                os.rmdir(d)
            except OSError:
                pass


class Handler(http.server.BaseHTTPRequestHandler):
    timeout = HANDLER_TIMEOUT

    def handle(self) -> None:
        fake: Fake = self.server.fake  # type: ignore[attr-defined]
        if not fake.handlers.acquire(blocking=False):
            try:
                self.wfile.write(b"HTTP/1.0 503 Busy\r\nContent-Length: 0\r\n\r\n")
            except OSError:
                pass
            return
        with fake.lock:
            fake.active += 1
        try:
            super().handle()
        except OSError:
            pass
        finally:
            with fake.lock:
                fake.active -= 1
            fake.handlers.release()

    def log_message(self, format, *args) -> None:  # noqa: A002
        pass  # no request lines: the fake says only what it decided

    def reply(self, code: int, body: bytes = b"") -> None:
        self.send_response(code)
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def body(self) -> bytes | None:
        """The request body within MAX_BODY, or None after answering an error."""
        if self.headers.get("Transfer-Encoding"):
            self.reply(400)
            return None
        n = self.headers.get("Content-Length")
        if n is None:
            return b""
        if not re.fullmatch(r"[0-9]{1,5}", n):  # ASCII digits only, bounded
            self.reply(400)
            return None
        if int(n) > MAX_BODY:
            self.reply(413)
            return None
        data = self.rfile.read(int(n))
        if len(data) != int(n):
            self.reply(400)
            return None
        return data

    def do_POST(self) -> None:  # noqa: N802
        fake: Fake = self.server.fake  # type: ignore[attr-defined]
        u = urllib.parse.urlsplit(self.path)
        if u.path == "/trigger" and not u.query:
            data = self.body()
            if data is not None:
                self.reply(fake.trigger() if data == b"" else 400)
            return
        if u.path != "/" + fake.topic or u.query not in ("", "up=1"):
            return self.reply(404)
        data = self.body()
        if data is None:
            return
        fake.before_queue(data)
        with fake.lock:
            full = len(fake.queue) >= MAX_QUEUE
            if not full:
                fake.queue.append(data)
                fake.received += 1
        self.reply(429 if full else 200, b"" if full else b'{"id":"fake"}')

    def do_GET(self) -> None:  # noqa: N802
        fake: Fake = self.server.fake  # type: ignore[attr-defined]
        if self.path != "/%s/poll" % fake.topic:
            return self.reply(404)
        with fake.lock:
            data = fake.queue.popleft() if fake.queue else None
        self.reply(204) if data is None else self.reply(200, data)


def main(argv: list) -> int:
    p = argparse.ArgumentParser(add_help=False)
    for a in ("--run-dir", "--port", "--topic", "--script"):
        p.add_argument(a, required=True)
    p.add_argument("--wake-install")
    p.add_argument("--owner-pid")  # the harness's own pid: it must be our parent, and its going away stops us
    try:
        o = p.parse_args(argv)
    except SystemExit:
        return 2
    if (not os.path.isdir(o.run_dir) or not o.port.isdigit() or not 1024 <= int(o.port) <= 65535
            or not TOPIC_RE.fullmatch(o.topic) or not os.path.isfile(o.script)
            or (o.wake_install is not None and not UUID_RE.fullmatch(o.wake_install))
            or (o.owner_pid is not None and not o.owner_pid.isdigit())):
        return 2
    owner = int(o.owner_pid) if o.owner_pid is not None else None
    if owner is not None and os.getppid() != owner:  # not started by that owner (or it is already gone): nothing at all
        return 3
    for fd in [int(f) for f in os.listdir("/proc/self/fd")]:  # nothing inherited beyond stdio (a lock, a tunnel)
        if fd > 2:
            try:
                os.close(fd)
            except OSError:
                pass
    stop = threading.Event()
    for s in (signal.SIGTERM, signal.SIGINT, signal.SIGHUP):
        signal.signal(s, lambda *_: stop.set())
    fake = Fake(o.run_dir, int(o.port), o.topic, o.script, o.wake_install)
    orphaned = threading.Event()
    fake.start()
    say("READY")
    while not stop.wait(0.5):
        if owner is not None and os.getppid() != owner:  # reparented: the owner is gone, whoever holds its descriptors
            orphaned.set()
            break
    child = fake.stop()
    if orphaned.is_set() and child != "unaccounted":
        fake.remove_private()  # nobody is left to clean up after us; kept as evidence when a child is unaccounted
    say("stopped: child %s%s" % (child, ", owner gone" if orphaned.is_set() else ""))
    return 1 if child == "unaccounted" else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
