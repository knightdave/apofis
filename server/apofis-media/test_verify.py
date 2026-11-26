"""Offline tests for verify.py: a scripted raw-bytes transport, a temporary home, no socket of any kind.

Run inside a network namespace on a host with a live listener; CI runs the same command plain:
  python3 -I -B -m unittest discover -s server/apofis-media -p 'test_*.py' -v
"""
import contextlib
import errno
import getpass
import io
import json
import logging
import os
import re
import secrets
import shutil
import signal
import socket
import stat
import struct
import tempfile
import threading
import types
import unittest
import warnings
from unittest import mock

import verify

BODY_LIMIT = 64 * 1024
PING = "/apofis-media/v1/ping"
FILE = "/apofis-media/v1/file"
REAL_KEY = "rk" * 20  # built at run time, never a key-like literal
BODY_CANARY = "bodycanary" * 2
EXC_CANARY = "exccanary" * 2
HOLD = 10  # every wait in this module is bounded by this many seconds
PNG_SIG = bytes.fromhex("89504e470d0a1a0a")
PNG_NAME = re.compile(r"^apofis-verify-[0-9a-f]{32}\.png$")
TEXT_NAME = re.compile(r"^apofis-verify-[0-9a-f]{32}-text\.png$")
FIXED_LINE = re.compile(r"^(V[1-9] (PASS|FAIL)|FAIL setup|FAIL arguments|FAIL key)$")
ALL_PASS = ["V%d PASS" % n for n in range(1, 10)]
PASS8 = ["V%d PASS" % n for n in range(1, 9)]
PING_OK = {"name": "apofis-media", "protocol": 1, "ready": True, "max_bytes": 10485760,
           "types": ["png", "jpeg", "gif", "webp"], "note": BODY_CANARY}


# --- egress guard -----------------------------------------------------------------------------------
# A copy of server/apofis-push/test_apofis_push.py:64-105 without its loopback exception and with bind
# added: this module opens no socket at all, so every connect, bind and name lookup is recorded and raises.

VIOLATIONS: list = []
_REAL = {}


def setUpModule():
    _REAL["connect"], _REAL["connect_ex"] = socket.socket.connect, socket.socket.connect_ex
    _REAL["bind"], _REAL["getaddrinfo"] = socket.socket.bind, socket.getaddrinfo

    def blocked(what):
        def deny(*a, **k):
            VIOLATIONS.append(what)
            raise PermissionError("egress blocked by test guard: %s" % what)
        return deny
    socket.socket.connect, socket.socket.connect_ex = blocked("connect"), blocked("connect_ex")
    socket.socket.bind, socket.getaddrinfo = blocked("bind"), blocked("getaddrinfo")


def tearDownModule():
    socket.socket.connect, socket.socket.connect_ex = _REAL["connect"], _REAL["connect_ex"]
    socket.socket.bind, socket.getaddrinfo = _REAL["bind"], _REAL["getaddrinfo"]
    if VIOLATIONS:
        raise AssertionError("egress attempted: %d" % len(VIOLATIONS))


# --- raw answers ------------------------------------------------------------------------------------

def jbody(obj):
    return json.dumps(obj).encode()


def http(status, body, ctype="application/json", headers=None, version="HTTP/1.1"):
    """A well-framed raw answer unless `headers` replaces the two default header lines."""
    if headers is None:
        headers = ["Content-Type: " + ctype, "Content-Length: %d" % len(body)]
    head = "%s %d X\r\n" % (version, status) + "".join(h + "\r\n" for h in headers)
    return head.encode() + b"\r\n" + body


def err_body(code):
    return jbody({"error": {"code": code, "message": "fixed " + BODY_CANARY}})


AUTH_BODY = jbody({"error": {"message": "Invalid " + BODY_CANARY, "type": "gateway_auth_error",
                             "code": "gateway_auth_failed"}})
AUTH = http(401, AUTH_BODY)


def parse_request(raw):
    head, _, body = raw.partition(b"\r\n\r\n")
    lines = head.decode("ascii").split("\r\n")
    method, path, version = lines[0].split(" ")
    headers = dict(line.split(": ", 1) for line in lines[1:])
    return {"method": method, "path": path, "version": version, "headers": headers, "body": body or None}


def path_of(req):
    return json.loads(req["body"])["path"]


def file_bytes(path):
    with open(path, "rb") as f:
        return f.read()


class Fake:
    """The transport seam: takes request bytes, answers raw bytes from a table. A line is recognised from
    the request itself; `answers` maps a line to raw bytes, to a function of the parsed request, or to an
    exception to raise. close() is the abort: it is recorded and releases a held answer."""

    def __init__(self, answers=None, log=None):
        self.answers, self.log, self.requests, self.aborts = answers or {}, log, [], 0
        self.release = threading.Event()

    @staticmethod
    def line_of(req):
        auth = req["headers"].get("Authorization")
        keyed = auth == "Bearer " + REAL_KEY
        if req["path"] == PING:
            return 5 if keyed else (1 if auth is None else 2)
        if not keyed:
            return 3 if auth is None else 4
        p = path_of(req)
        if p.endswith("-text.png"):
            return 7
        return 6 if os.path.exists(p) else 8

    def __call__(self, request):
        req = parse_request(request)
        req["line"] = line = self.line_of(req)
        self.requests.append(req)
        if self.log is not None:
            self.log.append(("req", line))
        answer = self.answers.get(line)
        if isinstance(answer, BaseException):
            raise answer
        if callable(answer):
            return answer(req)
        return self.default(line, req) if answer is None else answer

    def hold(self, out, clock, raw=b""):
        """An answer that waits until the lines before it are printed, moves the manual clock past any
        deadline, waits until the main thread aborts, then returns `raw`."""
        def held(req):
            out.wait_lines(req["line"] - 1)
            clock.now += 3600
            self.release.wait(HOLD)
            return raw
        return held

    def close(self):
        self.aborts += 1
        self.release.set()

    @staticmethod
    def default(line, req):
        if line <= 4:
            return AUTH
        if line == 5:
            return http(200, jbody(PING_OK))
        if line == 6:
            return http(200, file_bytes(path_of(req)), "image/png")
        return http(415, err_body("not_a_picture")) if line == 7 else http(404, err_body("file_unavailable"))

    def lines(self):
        return [r["line"] for r in self.requests]


def interrupt_then(fake, out, times=1, raw=None):
    """An answer that waits until the lines before it are printed, sends SIGINT `times` times (only the
    verifier's flag handler sees it), waits for the abort the interruption causes, then answers."""
    def go(req):
        out.wait_lines(req["line"] - 1)
        for _ in range(times):
            signal.raise_signal(signal.SIGINT)
        fake.release.wait(HOLD)
        return Fake.default(req["line"], req) if raw is None else raw
    return go


class WatchedOut(io.StringIO):
    """An output a fake can wait on: wait_lines(k) returns once k whole lines are written."""

    def __init__(self):
        super().__init__()
        self.cond = threading.Condition()

    def write(self, text):
        with self.cond:
            n = super().write(text)
            self.cond.notify_all()
            return n

    def wait_lines(self, k):
        with self.cond:
            self.cond.wait_for(lambda: self.getvalue().count("\n") >= k, HOLD)


class Clock:
    """A manual monotonic clock: it moves only when a test moves it."""

    def __init__(self):
        self.now = 1000.0

    def monotonic(self):
        return self.now


class ShortOut(io.StringIO):
    """Accepts at most `step` characters per write and says so; step 0 never accepts anything."""

    def __init__(self, step):
        super().__init__()
        self.step = step

    def write(self, text):
        return super().write(text[:self.step]) if self.step else 0


class NoneOut(io.StringIO):
    def write(self, text):
        super().write(text)
        return None


class RaisingOut(io.StringIO):
    def write(self, text):
        raise OSError(errno.EPIPE, EXC_CANARY)


@contextlib.contextmanager
def channels():
    """Captures real stdout, stderr and every log record of any logger."""
    got = {"stdout": io.StringIO(), "stderr": io.StringIO(), "log": []}

    class Keep(logging.Handler):
        def emit(self, record):
            got["log"].append(self.format(record))
    root, handler = logging.getLogger(), Keep(logging.DEBUG)
    level = root.level
    root.addHandler(handler)
    root.setLevel(logging.DEBUG)
    try:
        with contextlib.redirect_stdout(got["stdout"]), contextlib.redirect_stderr(got["stderr"]):
            yield got
    finally:
        root.removeHandler(handler)
        root.setLevel(level)


class Base(unittest.TestCase):
    def setUp(self):
        self.home = self.tmpdir()
        self.images = os.path.join(self.home, "cache", "images")
        os.makedirs(self.images, mode=0o700)

    def tmpdir(self):
        d = tempfile.mkdtemp(prefix="vt-")
        self.addCleanup(shutil.rmtree, d, True)
        return d

    def run_verify(self, fake, home=None, out=None, deadline=HOLD, key=REAL_KEY):
        """run() on this thread; afterwards the worker has ended and the SIGINT handler is the old one."""
        out = io.StringIO() if out is None else out
        before, threads = signal.getsignal(signal.SIGINT), set(threading.enumerate())
        try:
            code = verify.run(fake, home or self.home, key, out, deadline)
        except BaseException as e:  # a failure of this test, never an escape from the test process
            self.fail("run raised %s" % type(e).__name__)
        for t in set(threading.enumerate()) - threads:
            t.join(HOLD)
            self.assertFalse(t.is_alive())
        self.assertIs(signal.getsignal(signal.SIGINT), before)
        return code, out.getvalue().splitlines()

    def assert_no_fixture(self, folder=None):
        self.assertEqual(os.listdir(folder or self.images), [])

    def assert_no_keyed_request(self, fake):
        for r in fake.requests:
            self.assertNotIn(REAL_KEY, r["headers"].get("Authorization", ""))
            self.assertNotIn(REAL_KEY.encode(), r["body"] or b"")

    def assert_fixed_lines(self, lines):
        for line in lines:
            self.assertRegex(line, FIXED_LINE)


class RunTest(Base):
    def test_all_pass_exits_zero_and_leaves_no_fixture(self):
        code, lines = self.run_verify(Fake())
        self.assertEqual((code, lines), (0, ALL_PASS))
        self.assert_no_fixture()

    def test_request_shapes(self):
        fake = Fake()
        self.run_verify(fake)
        self.assertEqual(fake.lines(), list(range(1, 9)))
        by = {r["line"]: r for r in fake.requests}
        for n, r in by.items():
            self.assertEqual(r["version"], "HTTP/1.1")
            self.assertEqual((r["headers"]["Host"], r["headers"]["Connection"]), ("localhost", "close"))
        for n in (1, 2, 5):
            self.assertEqual((by[n]["method"], by[n]["path"], by[n]["body"]), ("GET", PING, None))
        self.assertEqual(set(by[1]["headers"]), {"Host", "Connection"})
        self.assertEqual(set(by[2]["headers"]), {"Host", "Connection", "Authorization"})
        self.assertEqual(set(by[3]["headers"]), {"Host", "Connection", "Content-Type", "Content-Length"})
        for n in (2, 4):
            auth = by[n]["headers"]["Authorization"]
            self.assertTrue(auth.startswith("Bearer ") and len(auth) > len("Bearer "))
            self.assertNotIn(REAL_KEY, auth)
        for n in (5, 6, 7, 8):
            self.assertEqual(by[n]["headers"]["Authorization"], "Bearer " + REAL_KEY)
        paths = {}
        for n in (3, 4, 6, 7, 8):
            r = by[n]
            self.assertEqual((r["method"], r["path"]), ("POST", FILE))
            self.assertEqual(r["headers"]["Content-Type"], "application/json")
            self.assertEqual(int(r["headers"]["Content-Length"]), len(r["body"]))
            obj = json.loads(r["body"])
            self.assertEqual(list(obj), ["path"])
            paths[n] = obj["path"]
            self.assertEqual(os.path.dirname(paths[n]), self.images)
        self.assertEqual(paths[3], paths[6])
        self.assertEqual(paths[4], paths[6])
        self.assertRegex(os.path.basename(paths[6]), PNG_NAME)
        self.assertRegex(os.path.basename(paths[7]), TEXT_NAME)
        self.assertRegex(os.path.basename(paths[8]), PNG_NAME)
        self.assertNotEqual(paths[8], paths[6])
        self.assertFalse(os.path.exists(paths[8]))

    def test_each_line_failure(self):
        wrong = [
            (1, http(200, jbody(PING_OK))),
            (2, http(401, err_body("other_code"))),
            (3, http(403, AUTH_BODY)),
            (4, http(401, jbody({"error": "gateway_auth_failed"}))),
            (5, http(503, err_body("unavailable"))),
            (6, lambda req: http(200, file_bytes(path_of(req)), "image/jpeg")),
            (6, lambda req: http(200, file_bytes(path_of(req))[:-1], "image/png")),
            (7, http(404, err_body("file_unavailable"))),
            (8, http(404, err_body("not_a_picture"))),
        ]
        for n, answer in wrong:
            with self.subTest(line=n):
                fake = Fake({n: answer})
                code, lines = self.run_verify(fake)
                self.assertEqual(code, 1)
                self.assertEqual(len(lines), 9)
                self.assertEqual(lines[n - 1], "V%d FAIL" % n)
                if n >= 5:
                    for later in range(n + 1, 9):
                        self.assertIn(later, fake.lines())
                        self.assertEqual(lines[later - 1], "V%d PASS" % later)
                    self.assertEqual(lines[8], "V9 PASS")
                self.assert_no_fixture()

    def test_raising_positive_line_lets_later_lines_run(self):
        fake = Fake({6: RuntimeError("transport broke")})
        code, lines = self.run_verify(fake)
        self.assertEqual(code, 1)
        self.assertEqual(fake.lines(), list(range(1, 9)))
        self.assertEqual(lines, PASS8[:5] + ["V6 FAIL", "V7 PASS", "V8 PASS", "V9 PASS"])
        self.assert_no_fixture()

    def test_negative_fail_never_sends_real_key(self):
        for n in (1, 2, 3, 4):
            with self.subTest(line=n):
                fake = Fake({n: http(200, jbody(PING_OK))})
                code, lines = self.run_verify(fake)
                self.assertEqual(code, 1)
                self.assert_no_keyed_request(fake)
                self.assertEqual(lines[4:8], ["V5 FAIL", "V6 FAIL", "V7 FAIL", "V8 FAIL"])
                self.assertEqual(fake.lines(), [1, 2, 3, 4])
                self.assert_no_fixture()

    def test_negative_line_refuses_malformed_answers(self):
        body = AUTH_BODY
        n = len(body)
        cases = {
            "wrong version": http(401, body, version="HTTP/2"),
            "no reason": b"HTTP/1.1 401\r\nContent-Length: %d\r\n\r\n" % n + body,
            "four digit status": b"HTTP/1.1 4010 X\r\nContent-Length: %d\r\n\r\n" % n + body,
            "missing length": http(401, body, headers=["Content-Type: application/json"]),
            "signed length": http(401, body, headers=["Content-Length: +%d" % n]),
            "spaced digits": http(401, body, headers=["Content-Length: %d %d" % (n // 10, n % 10)]),
            "two lengths": http(401, body, headers=["Content-Length: %d" % n, "Content-Length: %d" % n]),
            "length over the limit": http(401, body + b" " * (BODY_LIMIT + 1 - n)),
            "transfer encoding": http(401, body, headers=["Content-Length: %d" % n, "Transfer-Encoding: chunked"]),
            "chunked": http(401, b"%x\r\n" % n + body + b"\r\n0\r\n\r\n", headers=["Transfer-Encoding: chunked"]),
            "short body": http(401, body, headers=["Content-Length: %d" % (n + 40)]),
            "bytes after the body": http(401, body) + b"\r\nextra",
            "no end of headers": b"HTTP/1.1 401 X\r\nContent-Length: %d\r\n" % n,
            "bare line feeds": b"HTTP/1.1 401 X\nContent-Length: %d\n\n" % n + body,
            "bad header line": http(401, body, headers=["Content-Length: %d" % n, "no colon here"]),
            "invalid UTF-8": http(401, body[:-1] + b', "x": "\xff"}'),
            "NaN": http(401, body[:-1] + b', "x": NaN}'),
            "Infinity": http(401, body[:-1] + b', "x": Infinity}'),
            "-Infinity": http(401, body[:-1] + b', "x": -Infinity}'),
            "wrong code": http(401, err_body("gateway_auth_wrong")),
            "close delimited": b"HTTP/1.1 401 X\r\nContent-Type: application/json\r\n\r\n" + body,
            "too long": http(401, body) + b"x" * (verify.ANSWER_LIMIT + 1),
            "long headers": http(401, body, headers=["Content-Length: %d" % n, "X-Pad: " + "a" * 8200]),
        }
        for line in (1, 3):
            for case, raw in cases.items():
                with self.subTest(line=line, case=case):
                    fake = Fake({line: raw})
                    code, lines = self.run_verify(fake)
                    self.assertEqual(lines[line - 1], "V%d FAIL" % line)
                    self.assertEqual(lines[4:8], ["V5 FAIL", "V6 FAIL", "V7 FAIL", "V8 FAIL"])
                    self.assert_no_keyed_request(fake)
                    self.assertEqual(lines[8], "V9 PASS")
                    self.assertEqual(code, 1)
                    self.assert_no_fixture()

    def test_good_framing_variants_pass(self):
        variants = [http(401, AUTH_BODY, version="HTTP/1.0"),
                    http(401, AUTH_BODY, headers=["content-length:   %d  " % len(AUTH_BODY), "Server: x"])]
        for raw in variants:
            with self.subTest(raw=raw[:40]):
                code, lines = self.run_verify(Fake({n: raw for n in (1, 2, 3, 4)}))
                self.assertEqual((code, lines), (0, ALL_PASS))

    def test_v5_exact_types(self):
        cases = [("protocol", True), ("protocol", "1"), ("protocol", 1.0), ("ready", "true"), ("ready", 1),
                 ("max_bytes", 0), ("max_bytes", -1), ("max_bytes", True), ("types", "notpng"),
                 ("types", ["jpeg"]), ("types", [1]), ("types", ["png", 1]), ("name", "apofis-other")]
        for field, value in cases:
            with self.subTest(field=field, value=value):
                code, lines = self.run_verify(Fake({5: http(200, jbody(dict(PING_OK, **{field: value})))}))
                self.assertEqual(lines[4:], ["V5 FAIL", "V6 PASS", "V7 PASS", "V8 PASS", "V9 PASS"])
                self.assertEqual(code, 1)

    def test_proxy_error_page_fails(self):
        for status in (401, 502):
            with self.subTest(status=status):
                page = b"<html><body><h1>%d</h1>gateway_auth_failed</body></html>" % status
                code, lines = self.run_verify(Fake({1: http(status, page, "text/html")}))
                self.assertEqual(lines[0], "V1 FAIL")
                self.assertEqual(code, 1)

    def test_oversized_answers_fail_and_clean_up(self):
        pad = "x" * BODY_LIMIT
        answers = {5: http(200, jbody(dict(PING_OK, pad=pad))),
                   6: http(200, verify.PNG + b"\0" * BODY_LIMIT, "image/png"),
                   7: http(415, jbody({"error": {"code": "not_a_picture", "message": pad}}))}
        for n, raw in answers.items():
            with self.subTest(line=n):
                code, lines = self.run_verify(Fake({n: raw}))
                self.assertEqual(lines[n - 1], "V%d FAIL" % n)
                self.assertEqual(code, 1)
                self.assert_no_fixture()

    def test_deadline_aborts_and_fails_the_rest(self):
        for n in (3, 6):
            with self.subTest(line=n):
                fake, out, clock = Fake(), WatchedOut(), Clock()
                fake.answers[n] = fake.hold(out, clock, http(200, b"{}"))
                with mock.patch.object(verify, "time", types.SimpleNamespace(monotonic=clock.monotonic)):
                    code, lines = self.run_verify(fake, out=out, deadline=0.05)
                self.assertEqual(lines, PASS8[:n - 1] + ["V%d FAIL" % m for m in range(n, 9)] + ["V9 PASS"])
                self.assertEqual(code, 1)
                self.assertEqual(fake.aborts, 1)
                self.assertEqual(fake.lines(), list(range(1, n + 1)))  # nothing after the held request
                self.assert_no_fixture()

    def test_interrupt_only_sets_the_flag(self):
        for at, times in ((2, 1), (6, 1), (6, 2)):
            with self.subTest(at=at, times=times):
                fake, out = Fake(), WatchedOut()
                fake.answers[at] = interrupt_then(fake, out, times)
                code, lines = self.run_verify(fake, out=out)
                self.assertEqual(lines, PASS8[:at - 1] + ["V%d FAIL" % m for m in range(at, 10)])
                self.assertEqual(code, 1)
                self.assertEqual(fake.aborts, 1)
                self.assertEqual(fake.lines(), list(range(1, at + 1)))
                if at <= 4:
                    self.assert_no_keyed_request(fake)
                self.assert_no_fixture()

    def test_interrupt_during_cleanup_fails_v9(self):
        real_lstat = os.lstat

        def lstat(path, *a, dir_fd=None, **k):
            if dir_fd is not None and PNG_NAME.match(path) and os.path.exists(os.path.join(self.images, path)):
                signal.raise_signal(signal.SIGINT)
                signal.raise_signal(signal.SIGINT)
            return real_lstat(path, *a, dir_fd=dir_fd, **k)
        with mock.patch.object(os, "lstat", side_effect=lstat):
            code, lines = self.run_verify(Fake())
        self.assertEqual(lines, PASS8 + ["V9 FAIL"])
        self.assertEqual(code, 1)
        self.assert_no_fixture()

    def test_short_and_failing_writers(self):
        for step in (1, 3):
            with self.subTest(step=step):
                out = ShortOut(step)
                code, lines = self.run_verify(Fake(), out=out)
                self.assertEqual((code, lines), (0, ALL_PASS))
        with self.subTest(writer="returns None"):
            code, lines = self.run_verify(Fake(), out=NoneOut())
            self.assertEqual((code, lines), (0, ALL_PASS))
        for name, out in (("no progress", ShortOut(0)), ("raises", RaisingOut())):
            with self.subTest(writer=name):
                fake = Fake()
                code, lines = self.run_verify(fake, out=out)
                self.assertEqual((code, lines), (1, []))
                self.assertEqual(fake.aborts, 1)  # the worker is stopped at the first failed line
                self.assert_no_fixture()

    def test_key_predicate_in_run(self):
        for key in ("", "line\nbreak", "carriage\r", "nul\0", "tab\tkey", "klüssel", "del" + chr(127), None):
            with self.subTest(key=repr(key)):
                fake = Fake()
                code, lines = self.run_verify(fake, key=key)
                self.assertEqual((code, lines), (2, ["FAIL key"]))
                self.assertEqual(fake.requests, [])
                self.assert_no_fixture()

    def test_run_off_the_main_thread(self):
        got, out = {}, io.StringIO()
        before = signal.getsignal(signal.SIGINT)
        t = threading.Thread(target=lambda: got.update(code=verify.run(Fake(), self.home, REAL_KEY, out, HOLD)))
        t.start()
        t.join(HOLD)
        self.assertEqual((got.get("code"), out.getvalue().splitlines()), (0, ALL_PASS))
        self.assertIs(signal.getsignal(signal.SIGINT), before)
        self.assert_no_fixture()

    def test_canaries_never_printed(self):
        def garbage(req):
            return http(200, (BODY_CANARY + " " + path_of(req)).encode(), "text/plain")
        runs = [
            (Fake(), 0),
            (Fake({2: RuntimeError(EXC_CANARY + " " + self.home)}), 1),
            (Fake({5: RuntimeError(EXC_CANARY), 6: garbage, 7: garbage, 8: http(500, BODY_CANARY.encode())}), 1),
        ]
        for fake, want in runs:
            with self.subTest(want=want):
                code, lines = self.run_verify(fake)
                self.assertEqual(code, want)
                text = "\n".join(lines)
                for canary in (REAL_KEY, self.home, BODY_CANARY, EXC_CANARY, "apofis-verify", "cache"):
                    self.assertNotIn(canary, text)
                self.assertEqual(len(lines), 9)
                self.assert_fixed_lines(lines)


class FixtureTest(Base):
    def wrap_open(self, on_create=None):
        """Wrap os.open: record every descriptor, call on_create(count) before each exclusive create."""
        real_open, self.opened, self.creates = os.open, [], []

        def fake_open(path, flags, mode=0o777, *, dir_fd=None):
            if flags & os.O_EXCL and on_create:
                on_create(len(self.creates) + 1)
            fd = real_open(path, flags, mode, dir_fd=dir_fd)
            self.opened.append(fd)
            if flags & os.O_EXCL:
                self.creates.append(fd)
            return fd
        return mock.patch.object(os, "open", side_effect=fake_open)

    def test_fixture_two_create_failure_removes_one(self):
        def fail_second(count):
            if count == 2:
                raise OSError(errno.EIO, "second create")
        fake = Fake()
        with self.wrap_open(fail_second):
            code, lines = self.run_verify(fake)
        self.assertEqual(len(self.creates), 1)
        self.assertEqual((code, lines), (1, ["FAIL setup"]))
        self.assert_no_fixture()
        self.assertEqual(fake.requests, [])

    def test_fixture_two_write_failure_removes_both(self):
        real_write = os.write

        def write(fd, data):
            if len(self.creates) == 2 and fd == self.creates[1]:
                raise OSError(errno.ENOSPC, "second write")
            return real_write(fd, data)
        fake = Fake()
        with self.wrap_open(), mock.patch.object(os, "write", side_effect=write):
            code, lines = self.run_verify(fake)
        self.assertEqual(len(self.creates), 2)
        self.assertEqual((code, lines), (1, ["FAIL setup"]))
        self.assert_no_fixture()
        self.assertEqual(fake.requests, [])

    def test_worker_start_failure_cleans_up(self):
        fake = Fake()
        with mock.patch.object(threading.Thread, "start", side_effect=RuntimeError(EXC_CANARY)):
            code, lines = self.run_verify(fake)
        self.assertEqual((code, lines), (1, ["FAIL setup"]))
        self.assertEqual(fake.requests, [])
        self.assert_no_fixture()

    def test_symlinked_cache_fails_setup_and_creates_nothing(self):
        for case in ("linked cache", "linked images", "missing images"):
            with self.subTest(case=case):
                home, other = self.tmpdir(), self.tmpdir()
                os.makedirs(os.path.join(other, "images"), mode=0o700)
                if case == "linked cache":
                    os.symlink(other, os.path.join(home, "cache"))
                else:
                    os.makedirs(os.path.join(home, "cache"), mode=0o700)
                if case == "linked images":
                    os.symlink(os.path.join(other, "images"), os.path.join(home, "cache", "images"))
                fake = Fake()
                code, lines = self.run_verify(fake, home)
                self.assertEqual((code, lines), (1, ["FAIL setup"]))
                self.assertEqual(fake.requests, [])
                self.assert_no_fixture(os.path.join(other, "images"))
                self.assertEqual(os.listdir(other), ["images"])
                self.assertEqual(os.listdir(home), ["cache"])

    def test_existing_file_never_overwritten(self):
        hexes = iter(["a" * 32, "b" * 32, "c" * 32])
        real_hex = secrets.token_hex
        taken = "apofis-verify-%s.png" % ("a" * 32)
        with open(os.path.join(self.images, taken), "wb") as f:
            f.write(b"keep these bytes")
        fake = Fake()
        with mock.patch.object(secrets, "token_hex", side_effect=lambda n=None: next(hexes) if n == 16 else real_hex(n)):
            code, lines = self.run_verify(fake)
        self.assertEqual((code, lines), (1, ["FAIL setup"]))
        self.assertEqual(os.listdir(self.images), [taken])
        self.assertEqual(file_bytes(os.path.join(self.images, taken)), b"keep these bytes")
        self.assertEqual(fake.requests, [])

    def test_v8_name_checked_absent(self):
        real_hex = secrets.token_hex
        for case in ("same as the PNG fixture", "an existing entry"):
            with self.subTest(case=case):
                hexes = iter(["a" * 32, "b" * 32, "a" * 32 if case == "same as the PNG fixture" else "c" * 32])
                taken = os.path.join(self.images, "apofis-verify-%s.png" % ("c" * 32))
                if case == "an existing entry":
                    os.symlink(os.path.join(self.home, "nowhere"), taken)  # dangling: only lstat sees it
                fake = Fake()
                with mock.patch.object(secrets, "token_hex",
                                       side_effect=lambda n=None: next(hexes) if n == 16 else real_hex(n)):
                    code, lines = self.run_verify(fake)
                self.assertEqual((code, lines), (1, ["FAIL setup"]))
                self.assertEqual(fake.requests, [])
                if case == "an existing entry":
                    self.assertEqual(os.listdir(self.images), [os.path.basename(taken)])
                    self.assertTrue(os.path.islink(taken))
                    os.unlink(taken)
                self.assert_no_fixture()

    def swap(self, req):
        """Replace the PNG fixture with a new file of the same name, then answer V6 correctly."""
        p = path_of(req)
        data = file_bytes(p)
        os.unlink(p)
        with open(p, "wb") as f:
            f.write(b"someone else's file")
        self.swapped = p
        return http(200, data, "image/png")

    def test_swapped_fixture_not_unlinked(self):
        code, lines = self.run_verify(Fake({6: self.swap}))
        self.assertEqual(code, 1)
        self.assertEqual(lines, PASS8 + ["V9 FAIL"])
        self.assertEqual(file_bytes(self.swapped), b"someone else's file")
        self.assertEqual(os.listdir(self.images), [os.path.basename(self.swapped)])
        os.unlink(self.swapped)

    def test_first_cleanup_failure_still_removes_second(self):
        real_unlink, real_close = os.unlink, os.close
        for case in ("swapped", "unlink raises"):
            with self.subTest(case=case):
                closed = []

                def close(fd):
                    closed.append(fd)
                    return real_close(fd)

                def unlink(path, *a, dir_fd=None):
                    if dir_fd is not None and not path.endswith("-text.png"):
                        raise OSError(errno.EIO, EXC_CANARY)
                    return real_unlink(path, *a, dir_fd=dir_fd)
                fake = Fake({6: self.swap} if case == "swapped" else None)
                with contextlib.ExitStack() as stack:
                    stack.enter_context(self.wrap_open())
                    stack.enter_context(mock.patch.object(os, "close", side_effect=close))
                    if case == "unlink raises":
                        stack.enter_context(mock.patch.object(os, "unlink", side_effect=unlink))
                    code, lines = self.run_verify(fake)
                self.assertEqual(code, 1)
                self.assertEqual(lines[8], "V9 FAIL")
                left = os.listdir(self.images)
                self.assertEqual(len(left), 1)
                self.assertRegex(left[0], PNG_NAME)
                self.assertEqual(sorted(closed), sorted(self.opened))
                self.assertEqual(len(closed), len(set(closed)))
                self.assertEqual(len(self.opened), 5)
                self.assertNotIn(EXC_CANARY, "\n".join(lines))
                real_unlink(os.path.join(self.images, left[0]))

    def test_fixture_close_error_fails_v9(self):
        real_close = os.close
        closed = []

        def close(fd):
            closed.append(fd)
            real_close(fd)
            if fd == self.creates[0]:
                raise OSError(errno.EIO, EXC_CANARY)
        with self.wrap_open(), mock.patch.object(os, "close", side_effect=close):
            code, lines = self.run_verify(Fake())
        self.assertEqual((code, lines), (1, PASS8 + ["V9 FAIL"]))
        self.assertEqual(sorted(closed), sorted(self.opened))  # every descriptor closed, none twice
        self.assertEqual(closed.count(self.creates[0]), 1)
        self.assert_no_fixture()

    def test_fixture_modes_and_names(self):
        seen = {}

        def inspect(req):
            for name in os.listdir(self.images):
                st = os.lstat(os.path.join(self.images, name))
                seen[name] = (stat.S_IMODE(st.st_mode), stat.S_ISREG(st.st_mode),
                              file_bytes(os.path.join(self.images, name)))
            return http(200, file_bytes(path_of(req)), "image/png")
        fake = Fake({6: inspect})
        code, lines = self.run_verify(fake)
        self.assertEqual(code, 0)
        self.assertEqual(len(seen), 2)
        png = [n for n in seen if PNG_NAME.match(n)]
        text = [n for n in seen if TEXT_NAME.match(n)]
        self.assertEqual((len(png), len(text)), (1, 1))
        for name in seen:
            self.assertEqual(seen[name][:2], (0o600, True))
        data = seen[png[0]][2]
        self.assertEqual(data[:8], PNG_SIG)
        self.assertEqual(data[12:16], b"IHDR")
        self.assertEqual(struct.unpack(">II", data[16:24]), (1, 1))
        body = seen[text[0]][2]
        self.assertTrue(body and body.isascii())
        self.assertNotIn(PNG_SIG[1:4], body)
        absent = os.path.basename(path_of(fake.requests[7]))
        self.assertEqual(fake.requests[7]["line"], 8)
        self.assertRegex(absent, PNG_NAME)
        self.assertNotIn(absent, seen)
        self.assertFalse(os.path.exists(os.path.join(self.images, absent)))


class MainTest(Base):
    def call_main(self, argv, key=REAL_KEY, answers=None):
        """main() with getpass and the production transport replaced by recorders."""
        self.prompts, self.transports = [], []

        def prompt(text="", stream=None):
            self.prompts.append(text)
            if isinstance(key, BaseException):
                raise key
            return key(text) if callable(key) else key

        def transport(host, port):
            self.transports.append((host, port))
            return Fake(answers)
        out, errout = io.StringIO(), io.StringIO()
        with mock.patch.object(getpass, "getpass", side_effect=prompt), \
                mock.patch.object(verify, "SocketTransport", side_effect=transport), \
                contextlib.redirect_stderr(errout):
            try:
                code = verify.main(argv, out, HOLD)
            except BaseException as e:  # a test failure, never an escape from the test process
                self.fail("main raised %s" % type(e).__name__)
        self.stderr = errout.getvalue()
        return code, out.getvalue().splitlines()

    def test_host_and_port_refused(self):
        cases = [["--port", "8642", "--host", "192.0.2.1"], ["--port", "8642", "--host", "localhost"],
                 ["--port", "0"], ["--port", "65536"], ["--host", "127.0.0.1"], ["--port", "eighty"]]
        for argv in cases:
            with self.subTest(argv=argv):
                code, lines = self.call_main(argv + ["--home", self.home])
                self.assertEqual((code, lines, self.stderr), (2, ["FAIL arguments"], ""))
                self.assertEqual((self.prompts, self.transports), ([], []))
                self.assert_no_fixture()

    def test_help_and_unresolvable_arguments_refused(self):
        for argv in (["-h"], ["--help"], ["--port", "8642", "-h", "--home", self.home]):
            with self.subTest(argv=argv):
                code, lines = self.call_main(argv)
                self.assertEqual((code, lines, self.stderr), (2, ["FAIL arguments"], ""))
                self.assertEqual((self.prompts, self.transports), ([], []))
        with mock.patch.object(os, "getcwd", side_effect=FileNotFoundError(errno.ENOENT, EXC_CANARY)):
            code, lines = self.call_main(["--port", "8642", "--home", "relative-home"])
        self.assertEqual((code, lines, self.stderr), (2, ["FAIL arguments"], ""))
        self.assertEqual((self.prompts, self.transports), ([], []))

    def test_loopback_hosts_accepted(self):
        for host in ("127.0.0.2", "::1"):
            with self.subTest(host=host):
                code, lines = self.call_main(["--port", "8642", "--host", host, "--home", self.home])
                self.assertEqual(self.transports, [(host, 8642)])
                self.assertEqual((code, lines), (0, ALL_PASS))

    def test_default_home_two_levels_up(self):
        tree = self.tmpdir()
        os.makedirs(os.path.join(tree, "plugins", "apofis-media"))
        homes = []
        with mock.patch.object(verify, "__file__", os.path.join(tree, "plugins", "apofis-media", "verify.py")), \
                mock.patch.object(verify, "run", side_effect=lambda t, home, k, out, d: homes.append(home) or 0):
            code, lines = self.call_main(["--port", "8642"])
        self.assertEqual(code, 0)
        self.assertEqual(homes, [tree])

    def test_no_terminal_fixed_failure_no_fallback(self):
        mark = {}

        def no_tty(text):
            warnings.warn("Can not control echo on the terminal.", getpass.GetPassWarning)
            mark["read"] = True
            return REAL_KEY
        for key in (no_tty, KeyboardInterrupt(), EOFError()):
            with self.subTest(key=type(key).__name__):
                mark.clear()
                code, lines = self.call_main(["--port", "8642", "--home", self.home], key=key)
                self.assertNotIn("read", mark)
                self.assertEqual((code, lines), (2, ["FAIL key"]))
                self.assertEqual(self.transports, [])
                self.assert_no_fixture()

    def test_bad_keys_refused(self):
        for key in ("", "line\nbreak", "carriage\r", "nul\0", "tab\tkey", "klüssel", "del" + chr(127)):
            with self.subTest(key=repr(key)):
                code, lines = self.call_main(["--port", "8642", "--home", self.home], key=key)
                self.assertEqual((code, lines), (2, ["FAIL key"]))
                self.assertEqual(self.transports, [])
                self.assert_no_fixture()

    def test_key_read_before_setup(self):
        events = []
        real_open = os.open

        def fake_open(path, flags, mode=0o777, *, dir_fd=None):
            if flags & os.O_EXCL:
                events.append("create")
            return real_open(path, flags, mode, dir_fd=dir_fd)

        def key(text):
            events.append("prompt")
            return REAL_KEY
        with mock.patch.object(os, "open", side_effect=fake_open):
            code, lines = self.call_main(["--port", "8642", "--home", self.home], key=key)
        self.assertEqual(events, ["prompt", "create", "create"])
        self.assertEqual(self.prompts, ["API key: "])
        self.assertEqual(code, 0)

    def test_pre_network_failures_classified(self):
        for exc in (RuntimeError(EXC_CANARY), KeyboardInterrupt()):
            with self.subTest(exc=type(exc).__name__):
                out, errout = io.StringIO(), io.StringIO()
                with mock.patch.object(getpass, "getpass", return_value=REAL_KEY), \
                        mock.patch.object(verify, "SocketTransport", side_effect=exc), \
                        contextlib.redirect_stderr(errout):
                    try:
                        code = verify.main(["--port", "8642", "--home", self.home], out, HOLD)
                    except BaseException as e:
                        self.fail("main raised %s" % type(e).__name__)
                self.assertEqual((code, out.getvalue(), errout.getvalue()), (1, "FAIL setup\n", ""))
                self.assert_no_fixture()
        with mock.patch.object(getpass, "getpass", return_value=REAL_KEY), \
                mock.patch.object(verify, "SocketTransport", return_value=Fake()), \
                mock.patch.object(verify, "run", side_effect=KeyboardInterrupt()):
            code = verify.main(["--port", "8642", "--home", self.home], io.StringIO(), HOLD)
        self.assertEqual(code, 1)  # Ctrl-C just outside run's handler: nothing is owned there

    def test_privacy_on_every_channel(self):
        def garbage(req):
            return http(200, (BODY_CANARY + " " + path_of(req)).encode(), "text/plain")
        runs = [
            ("passing", REAL_KEY, None, 0),
            ("failing", REAL_KEY, {2: RuntimeError(EXC_CANARY + " " + self.home)}, 1),
            ("failing late", REAL_KEY, {5: RuntimeError(EXC_CANARY), 6: garbage, 7: garbage,
                                        8: http(500, BODY_CANARY.encode())}, 1),
            ("bad key", REAL_KEY + "\n" + EXC_CANARY, None, 2),
        ]
        for case, key, answers, want in runs:
            with self.subTest(case=case):
                with channels() as got, mock.patch.object(getpass, "getpass", return_value=key), \
                        mock.patch.object(verify, "SocketTransport", side_effect=lambda h, p: Fake(answers)):
                    code = verify.main(["--port", "8642", "--home", self.home], None, HOLD)
                self.assertEqual(code, want)
                lines = got["stdout"].getvalue().splitlines()
                self.assertTrue(lines)
                self.assert_fixed_lines(lines)
                everything = "\n".join([got["stdout"].getvalue(), got["stderr"].getvalue()] + got["log"])
                for canary in (REAL_KEY, self.home, BODY_CANARY, EXC_CANARY, "apofis-verify", "cache"):
                    self.assertNotIn(canary, everything)
                self.assert_no_fixture()


class TransportTest(unittest.TestCase):
    """SocketTransport against a fake socket module object: no real socket is ever made."""

    def fake_socket(self, recv, send_error=None):
        made = []

        class Sock:
            def __init__(self):
                self.events = []

            def sendall(self, data):
                self.events.append(("sendall", data))
                if send_error:
                    raise send_error

            def recv(self, n):
                self.events.append(("recv", n))
                return recv(self, n)

            def shutdown(self, how):
                self.events.append(("shutdown", how))

            def close(self):
                self.events.append("close")

        def create_connection(address, timeout=None):
            made.append((address, timeout))
            self.sock = Sock()
            return self.sock
        module = types.SimpleNamespace(create_connection=create_connection, SHUT_RDWR=socket.SHUT_RDWR)
        return module, made

    def test_request_answer_and_close(self):
        chunks = [b"HTTP/1.1 200 X\r\n", b"Content-Length: 2\r\n\r\n{}"]
        module, made = self.fake_socket(lambda sock, n: chunks.pop(0) if chunks else b"")
        with mock.patch.object(verify, "socket", module):
            got = verify.SocketTransport("127.0.0.1", 8642)(b"GET / HTTP/1.1\r\n\r\n")
        self.assertEqual(got, b"HTTP/1.1 200 X\r\nContent-Length: 2\r\n\r\n{}")
        self.assertEqual(made, [(("127.0.0.1", 8642), 10)])
        self.assertEqual(self.sock.events[0], ("sendall", b"GET / HTTP/1.1\r\n\r\n"))
        self.assertEqual(self.sock.events[-1], "close")
        self.assertEqual(self.sock.events.count("close"), 1)

    def test_endless_peer_is_cut_at_the_budget(self):
        module, _ = self.fake_socket(lambda sock, n: b"x" * min(n, 1000))
        with mock.patch.object(verify, "socket", module):
            got = verify.SocketTransport("127.0.0.1", 8642)(b"x")
        self.assertEqual(verify.ANSWER_LIMIT, 8 * 1024 + 4 + BODY_LIMIT)
        self.assertEqual(len(got), verify.ANSWER_LIMIT + 1)
        self.assertEqual(self.sock.events[-1], "close")

    def test_send_error_still_closes(self):
        module, _ = self.fake_socket(lambda sock, n: b"", send_error=OSError(EXC_CANARY))
        with mock.patch.object(verify, "socket", module):
            with self.assertRaises(OSError):
                verify.SocketTransport("127.0.0.1", 8642)(b"x")
        self.assertEqual(self.sock.events[-1], "close")

    def test_close_aborts_the_request_in_flight(self):
        transport = verify.SocketTransport("127.0.0.1", 8642)

        def recv(sock, n):
            transport.close()  # what the main thread does at the deadline
            return b""
        module, _ = self.fake_socket(recv)
        with mock.patch.object(verify, "socket", module):
            self.assertEqual(transport(b"x"), b"")
            transport.close()  # no request in flight: nothing to do
        self.assertEqual(self.sock.events.count(("shutdown", socket.SHUT_RDWR)), 1)
        self.assertEqual(self.sock.events[-1], "close")


if __name__ == "__main__":
    unittest.main()
