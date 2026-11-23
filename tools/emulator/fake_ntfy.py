#!/usr/bin/env python3
"""A run-private stand-in for ntfy, for the emulator harness (tools/emulator/README.md).

    python3 -I fake_ntfy.py --run-dir D --port P --topic T --script apofis_push.py [--wake-install UUID]

Binds 127.0.0.1:P only. POST /T (or /T?up=1) queues a message (at most 4096 bytes, at most 64 queued); GET /T/poll
answers the oldest one and removes it (204 when none). At most 8 requests are handled at once.

POST /trigger (no input, at most 3 times) runs the real hook script once with a fixed anonymous approval event,
against D/fixture.db: a synthetic WAL database built here at start with one hidden record of the --wake-install,
whose endpoint is this fake. The live state database is never opened. The script runs with HERMES_HOME=D/home, so
its slot locks and log stay in D, with only PATH besides. The trigger answers once the detached child the script
starts has released its slot; past CHILD_WAIT it kills the child's process group. On SIGTERM, SIGINT or SIGHUP the
fake stops serving, kills any child still holding a slot, checks it is gone and exits 0 (1 if one could not be
accounted for). Python 3.9+ standard library.
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
    """The hook parent: the event on stdin, no output, no inherited descriptors; it returns at once."""
    subprocess.run(cmd, input=event, env=env, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, close_fds=True,
                   timeout=10, check=False)


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
        self.before_queue = lambda body: None  # test seam: runs in the handler before a message is queued
        self.queue: collections.deque = collections.deque()
        self.lock = threading.Lock()
        self.handlers = threading.BoundedSemaphore(MAX_HANDLERS)
        self.active = 0
        self.triggers = 0
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
            return "released"
        killed = False
        for pid in held:
            if pid is not None:
                try:
                    os.killpg(pid, signal.SIGKILL)  # the child runs in its own session: its group id is its pid
                    killed = True
                except OSError:
                    pass
        end = time.monotonic() + 5
        while self.held() and time.monotonic() < end:
            time.sleep(0.05)
        return "unaccounted" if self.held() or not killed else "killed"

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
            return os.getpgid(pid) == pid and self.script.encode() in argv and b"send" in argv
        except OSError:
            return False

    def trigger(self) -> int:
        with self.lock:
            if self.fixture is None:
                return 409
            if self.triggers >= MAX_TRIGGERS:
                return 429
            self.triggers += 1
            n = self.triggers
        try:
            self.runner(self.command(), self.env(), EVENT)
            rc = "ran"
        except Exception as e:  # a hook that did not return in time: its child is still accounted below
            rc = type(e).__name__
        child = self.account(self.child_wait)
        say("trigger %d: hook %s, child %s" % (n, rc, child))
        return 200 if rc == "ran" and child == "released" else 500

    def stop(self) -> str:
        """Stops serving, then accounts for every hook child at once (killing a holder). Safe to call twice."""
        if self.thread is not None:
            self.server.shutdown()
            self.thread = None
        self.server.server_close()
        return self.account(0)


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
        if not n.isdigit():
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
    try:
        o = p.parse_args(argv)
    except SystemExit:
        return 2
    if (not os.path.isdir(o.run_dir) or not o.port.isdigit() or not 1024 <= int(o.port) <= 65535
            or not TOPIC_RE.fullmatch(o.topic) or not os.path.isfile(o.script)
            or (o.wake_install is not None and not UUID_RE.fullmatch(o.wake_install))):
        return 2
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
    fake.start()
    say("READY")
    while not stop.wait(1):
        pass
    child = fake.stop()
    say("stopped: child %s" % child)
    return 1 if child == "unaccounted" else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv[1:]))
