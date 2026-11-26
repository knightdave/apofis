"""Offline tests for verify.py: a fake socket module, a manual clock, a signal recorder, a temporary home.
No real socket, no thread (but one, joined, for the off-main-thread case), no sleep.

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
ADDRESS = ("127.0.0.1", 8642)
PING = "/apofis-media/v1/ping"
FILE = "/apofis-media/v1/file"
REAL_KEY = "rk" * 20  # built at run time, never a key-like literal
BODY_CANARY = "bodycanary" * 2
EXC_CANARY = "exccanary" * 2
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


def default_answer(line, req):
    if line <= 4:
        return AUTH
    if line == 5:
        return http(200, jbody(PING_OK))
    if line == 6:
        return http(200, file_bytes(path_of(req)), "image/png")
    return http(415, err_body("not_a_picture")) if line == 7 else http(404, err_body("file_unavailable"))


# --- fake socket module, clock and signal module ------------------------------------------------------

TIMEOUT = object()  # a recv slice that times out; it moves the clock by one slice


class OnClose:
    """A step that runs when the connection is closed, after the answer was read."""

    def __init__(self, fn):
        self.fn = fn


class Clock:
    """A manual monotonic clock: it moves only when a test moves it."""

    def __init__(self):
        self.now = 1000.0

    def monotonic(self):
        return self.now


class Sock:
    def __init__(self, net):
        self.net, self.steps, self.closing, self.timeouts, self.asked, self.closed = net, [], [], [], [], 0

    def sendall(self, data):
        req = parse_request(data)
        req["line"] = line = line_of(req)
        req["sock"] = self
        self.net.requests.append(req)
        answer = self.net.answers.get(line)
        if isinstance(answer, BaseException):
            raise answer
        if callable(answer):
            answer = answer(req)
        if answer is None:
            answer = default_answer(line, req)
        steps = [answer] if isinstance(answer, bytes) else list(answer)
        self.closing = [s.fn for s in steps if isinstance(s, OnClose)]
        self.steps = [s for s in steps if not isinstance(s, OnClose)]

    def settimeout(self, seconds):
        self.timeouts.append(seconds)

    def recv(self, n):
        self.asked.append(n)
        while self.steps:
            step = self.steps.pop(0)
            if step is TIMEOUT:
                self.net.clock.now += 0.25
                raise TimeoutError()
            if callable(step):  # something happens while recv waits: that slice then times out
                step()
                self.net.clock.now += 0.25
                raise TimeoutError()
            chunk, rest = step[:n], step[n:]
            if rest:
                self.steps.insert(0, rest)
            return chunk
        return b""

    def close(self):
        self.closed += 1
        for fn in self.closing:
            fn()


class Net:
    """Patched in as verify.socket. `answers` maps a V line to raw bytes, to a list of steps (bytes handed out
    in pieces no larger than recv asks, TIMEOUT, a callable run in place, OnClose), to a function of the
    parsed request returning either, or to an exception that sendall raises. A callable step ends its recv
    slice like TIMEOUT. After the steps the peer closes."""

    def __init__(self, clock, answers=None):
        self.clock, self.answers, self.connects, self.requests, self.socks = clock, answers or {}, [], [], []

    def create_connection(self, address, timeout=None):
        self.connects.append((address, timeout))
        sock = Sock(self)
        self.socks.append(sock)
        return sock

    def lines(self):
        return [r["line"] for r in self.requests]


def previous_handler(signum, frame):
    """Stands for whatever SIGINT disposition the caller had."""


class Sig:
    """Patched in as verify.signal: records every setter call; interrupt() is what a Ctrl-C does."""
    SIGINT = signal.SIGINT

    def __init__(self, current=previous_handler):
        self.current, self.calls = current, []

    def getsignal(self, signum):
        return self.current

    def signal(self, signum, handler):
        self.calls.append(handler)
        old, self.current = self.current, handler
        return old

    def interrupt(self, times=1):
        for _ in range(times):
            self.current(signal.SIGINT, None)


# --- outputs ------------------------------------------------------------------------------------------

class ShortOut(io.StringIO):
    """Accepts at most `step` characters per write and says so."""

    def __init__(self, step):
        super().__init__()
        self.step = step

    def write(self, text):
        return super().write(text[:self.step])


class BadOut(io.StringIO):
    """Writes a prefix, then returns a count the verifier must not accept (or raises)."""

    def __init__(self, reply):
        super().__init__()
        self.reply = reply

    def write(self, text):
        super().write(text[:2])
        if isinstance(self.reply, BaseException):
            raise self.reply
        return self.reply(text) if callable(self.reply) else self.reply


class HookOut(io.StringIO):
    """Runs `fn` while one exact line is being written; `fail` makes that write raise instead."""

    def __init__(self, line, fn=None, fail=False):
        super().__init__()
        self.line, self.fn, self.fail = line + "\n", fn, fail

    def write(self, text):
        if text == self.line:
            if self.fail:
                raise OSError(errno.EPIPE, EXC_CANARY)
            self.fn()
        return super().write(text)


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
        self.clock, self.sig = Clock(), Sig()
        self.net = Net(self.clock)
        for name, fake in (("socket", self.net), ("signal", self.sig),
                           ("time", types.SimpleNamespace(monotonic=self.clock.monotonic))):
            patcher = mock.patch.object(verify, name, fake)
            patcher.start()
            self.addCleanup(patcher.stop)

    def tmpdir(self):
        d = tempfile.mkdtemp(prefix="vt-")
        self.addCleanup(shutil.rmtree, d, True)
        return d

    def run_verify(self, answers=None, home=None, out=None, deadline=60, key=REAL_KEY):
        """run(); afterwards the previous SIGINT handler is back, set through the recorded setter."""
        self.net.answers.update(answers or {})
        out, start = io.StringIO() if out is None else out, len(self.sig.calls)
        try:
            code = verify.run(ADDRESS, home or self.home, key, out, deadline)
        except BaseException as e:  # a failure of this test, never an escape from the test process
            self.fail("run raised %s" % type(e).__name__)
        calls = self.sig.calls[start:]
        if calls:
            self.assertEqual(len(calls), 2)
            self.assertIs(calls[1], previous_handler)
        self.assertIs(self.sig.current, previous_handler)
        for sock in self.net.socks:
            self.assertEqual(sock.closed, 1)
        return code, out.getvalue().splitlines()

    def assert_no_fixture(self, folder=None):
        self.assertEqual(os.listdir(folder or self.images), [])

    def assert_no_keyed_request(self):
        for r in self.net.requests:
            self.assertNotIn(REAL_KEY, r["headers"].get("Authorization", ""))
            self.assertNotIn(REAL_KEY.encode(), r["body"] or b"")

    def assert_fixed_lines(self, lines):
        for line in lines:
            self.assertRegex(line, FIXED_LINE)


class RunTest(Base):
    def test_all_pass_exits_zero_and_leaves_no_fixture(self):
        code, lines = self.run_verify()
        self.assertEqual((code, lines), (0, ALL_PASS))
        self.assertEqual(self.net.connects, [(ADDRESS, 10)] * 8)
        for sock in self.net.socks:
            self.assertEqual(sock.timeouts, [0.25])
        self.assertIsInstance(self.sig.calls[0], types.FunctionType)
        self.assert_no_fixture()

    def test_request_shapes(self):
        self.run_verify()
        self.assertEqual(self.net.lines(), list(range(1, 9)))
        by = {r["line"]: r for r in self.net.requests}
        for r in by.values():
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
                self.setUp()
                code, lines = self.run_verify({n: answer})
                self.assertEqual(code, 1)
                self.assertEqual(len(lines), 9)
                self.assertEqual(lines[n - 1], "V%d FAIL" % n)
                if n >= 5:
                    for later in range(n + 1, 9):
                        self.assertIn(later, self.net.lines())
                        self.assertEqual(lines[later - 1], "V%d PASS" % later)
                    self.assertEqual(lines[8], "V9 PASS")
                self.assert_no_fixture()

    def test_raising_positive_line_lets_later_lines_run(self):
        code, lines = self.run_verify({6: RuntimeError("transport broke")})
        self.assertEqual(code, 1)
        self.assertEqual(self.net.lines(), list(range(1, 9)))
        self.assertEqual(lines, PASS8[:5] + ["V6 FAIL", "V7 PASS", "V8 PASS", "V9 PASS"])
        self.assert_no_fixture()

    def test_negative_fail_never_sends_real_key(self):
        for n in (1, 2, 3, 4):
            with self.subTest(line=n):
                self.setUp()
                code, lines = self.run_verify({n: http(200, jbody(PING_OK))})
                self.assertEqual(code, 1)
                self.assert_no_keyed_request()
                self.assertEqual(lines[4:8], ["V5 FAIL", "V6 FAIL", "V7 FAIL", "V8 FAIL"])
                self.assertEqual(self.net.lines(), [1, 2, 3, 4])
                self.assert_no_fixture()

    def test_negative_line_refuses_malformed_answers(self):
        body = AUTH_BODY
        n = len(body)
        cases = {
            "wrong version": http(401, body, version="HTTP/2"),
            "no reason": b"HTTP/1.1 401\r\nContent-Length: %d\r\n\r\n" % n + body,
            "four digit status": b"HTTP/1.1 4010 X\r\nContent-Length: %d\r\n\r\n" % n + body,
            "status 000": b"HTTP/1.1 000 X\r\nContent-Length: %d\r\n\r\n" % n + body,
            "status 600": b"HTTP/1.1 600 X\r\nContent-Length: %d\r\n\r\n" % n + body,
            "missing length": http(401, body, headers=["Content-Type: application/json"]),
            "signed length": http(401, body, headers=["Content-Length: +%d" % n]),
            "spaced digits": http(401, body, headers=["Content-Length: %d %d" % (n // 10, n % 10)]),
            "two lengths": http(401, body, headers=["Content-Length: %d" % n, "Content-Length: %d" % n]),
            "length over the limit": http(401, body + b" " * (BODY_LIMIT + 1 - n)),
            "transfer encoding": http(401, body, headers=["Content-Length: %d" % n, "Transfer-Encoding: chunked"]),
            "chunked": http(401, b"%x\r\n" % n + body + b"\r\n0\r\n\r\n", headers=["Transfer-Encoding: chunked"]),
            "content encoding": http(401, body, headers=["Content-Length: %d" % n, "Content-Encoding: gzip"]),
            "short body": http(401, body, headers=["Content-Length: %d" % (n + 40)]),
            "bytes after the body": http(401, body) + b"  \r\n",  # JSON alone would accept this tail
            "no end of headers": b"HTTP/1.1 401 X\r\nContent-Length: %d\r\n" % n,
            "bare line feeds": b"HTTP/1.1 401 X\nContent-Length: %d\n\n" % n + body,
            "bad header line": http(401, body, headers=["Content-Length: %d" % n, "no colon here"]),
            "invalid UTF-8": http(401, body[:-1] + b', "x": "\xff"}'),
            "NaN": http(401, body[:-1] + b', "x": NaN}'),
            "Infinity": http(401, body[:-1] + b', "x": Infinity}'),
            "-Infinity": http(401, body[:-1] + b', "x": -Infinity}'),
            "wrong code": http(401, err_body("gateway_auth_wrong")),
            "close delimited": b"HTTP/1.1 401 X\r\nContent-Type: application/json\r\n\r\n" + body,
            "long headers": http(401, body, headers=["Content-Length: %d" % n, "X-Pad: " + "a" * 8200]),
        }
        for line in (1, 3):
            for case, raw in cases.items():
                with self.subTest(line=line, case=case):
                    self.setUp()
                    code, lines = self.run_verify({line: raw})
                    self.assertEqual(lines[line - 1], "V%d FAIL" % line)
                    self.assertEqual(lines[4:8], ["V5 FAIL", "V6 FAIL", "V7 FAIL", "V8 FAIL"])
                    self.assert_no_keyed_request()
                    self.assertEqual(lines[8], "V9 PASS")
                    self.assertEqual(code, 1)
                    self.assert_no_fixture()

    def test_good_framing_variants_pass(self):
        variants = [http(401, AUTH_BODY, version="HTTP/1.0"),
                    http(401, AUTH_BODY, headers=["content-length:   %d  " % len(AUTH_BODY), "Server: x"]),
                    [AUTH[:10], TIMEOUT, AUTH[10:30], TIMEOUT, AUTH[30:]]]  # a trickle inside every limit
        for raw in variants:
            with self.subTest(raw=repr(raw)[:40]):
                self.setUp()
                code, lines = self.run_verify({n: raw for n in (1, 2, 3, 4)})
                self.assertEqual((code, lines), (0, ALL_PASS))

    def test_v5_exact_types(self):
        cases = [("protocol", True), ("protocol", "1"), ("protocol", 1.0), ("ready", "true"), ("ready", 1),
                 ("max_bytes", 0), ("max_bytes", -1), ("max_bytes", True), ("types", "notpng"),
                 ("types", ["jpeg"]), ("types", [1]), ("types", ["png", 1]), ("name", "apofis-other")]
        for field, value in cases:
            with self.subTest(field=field, value=value):
                self.setUp()
                code, lines = self.run_verify({5: http(200, jbody(dict(PING_OK, **{field: value})))})
                self.assertEqual(lines[4:], ["V5 FAIL", "V6 PASS", "V7 PASS", "V8 PASS", "V9 PASS"])
                self.assertEqual(code, 1)

    def test_proxy_error_page_fails(self):
        for status in (401, 502):
            with self.subTest(status=status):
                self.setUp()
                page = b"<html><body><h1>%d</h1>gateway_auth_failed</body></html>" % status
                code, lines = self.run_verify({1: http(status, page, "text/html")})
                self.assertEqual(lines[0], "V1 FAIL")
                self.assertEqual(code, 1)

    def test_oversized_answers_fail_and_clean_up(self):
        pad = "x" * BODY_LIMIT
        answers = {5: http(200, jbody(dict(PING_OK, pad=pad))),
                   6: http(200, verify.PNG + b"\0" * BODY_LIMIT, "image/png"),
                   7: http(415, jbody({"error": {"code": "not_a_picture", "message": pad}}))}
        for n, raw in answers.items():
            with self.subTest(line=n):
                self.setUp()
                code, lines = self.run_verify({n: raw})
                self.assertEqual(lines[n - 1], "V%d FAIL" % n)
                self.assertEqual(code, 1)
                self.assert_no_fixture()

    def test_byte_budget_ends_the_exchange(self):
        code, lines = self.run_verify({5: [b"x" * 1000] * 200})
        sock = self.net.requests[4]["sock"]
        self.assertEqual(lines[4:], ["V5 FAIL", "V6 PASS", "V7 PASS", "V8 PASS", "V9 PASS"])
        self.assertTrue(sock.steps)  # the peer still had more: the verifier stopped reading
        for asked in sock.asked:
            self.assertGreaterEqual(asked, 1)
        self.assertEqual(sum(min(a, 1000) for a in sock.asked), verify.ANSWER_LIMIT + 1)
        self.assertEqual(verify.ANSWER_LIMIT, 8 * 1024 + 4 + BODY_LIMIT)

    def test_idle_limit(self):
        for slices, want in ((39, "V5 PASS"), (40, "V5 FAIL")):  # 9.75 s and 10 s without a byte
            with self.subTest(slices=slices):
                self.setUp()
                ping = http(200, jbody(PING_OK))
                code, lines = self.run_verify({5: [TIMEOUT] * slices + [ping]})
                self.assertEqual(lines[4:], [want, "V6 PASS", "V7 PASS", "V8 PASS", "V9 PASS"])
                if want == "V5 FAIL":
                    self.assertEqual(self.net.requests[4]["sock"].steps, [ping])  # never read

    def test_deadline_during_a_trickled_body(self):
        raw = http(200, jbody(PING_OK))
        code, lines = self.run_verify({6: [raw[:20], TIMEOUT, TIMEOUT, raw[20:40], TIMEOUT, raw[40:]]}, deadline=0.5)
        self.assertEqual(self.net.connects[0], (ADDRESS, 0.5))  # connect never waits past the deadline
        self.assertEqual(lines, PASS8[:5] + ["V6 FAIL", "V7 FAIL", "V8 FAIL", "V9 PASS"])
        self.assertEqual(code, 1)
        self.assertEqual(self.net.lines(), [1, 2, 3, 4, 5, 6])  # no request after the deadline
        self.assertEqual(self.net.requests[5]["sock"].steps, [raw[20:40], TIMEOUT, raw[40:]])
        self.assert_no_fixture()

    def test_deadline_crossing_as_v8_returns(self):
        def cross():
            self.clock.now = 1000 + 30
        code, lines = self.run_verify({8: [http(404, err_body("file_unavailable")), OnClose(cross)]}, deadline=30)
        self.assertEqual(lines, PASS8[:7] + ["V8 FAIL", "V9 PASS"])
        self.assertEqual(code, 1)
        self.assert_no_fixture()

    def test_interrupt_during_an_exchange(self):
        for at, times in ((2, 1), (6, 1), (6, 2)):
            with self.subTest(at=at, times=times):
                self.setUp()
                raw = AUTH if at <= 4 else http(200, b"x" * 40, "image/png")
                code, lines = self.run_verify({at: [raw[:20], lambda: self.sig.interrupt(times), raw[20:]]})
                self.assertEqual(lines, PASS8[:at - 1] + ["V%d FAIL" % m for m in range(at, 10)])
                self.assertEqual(code, 1)
                self.assertEqual(self.net.lines(), list(range(1, at + 1)))  # no request after the interrupt
                self.assertEqual(self.net.requests[at - 1]["sock"].steps, [raw[20:]])  # stopped reading
                if at <= 4:
                    self.assert_no_keyed_request()
                self.assert_no_fixture()

    def test_interrupt_after_a_right_v4_answer(self):
        code, lines = self.run_verify({4: [AUTH, OnClose(self.sig.interrupt)]})
        self.assertEqual(lines, PASS8[:3] + ["V%d FAIL" % m for m in range(4, 10)])
        self.assertEqual(code, 1)
        self.assertEqual(self.net.lines(), [1, 2, 3, 4])
        self.assert_no_keyed_request()
        self.assert_no_fixture()

    def test_interrupt_during_setup(self):
        real_open = os.open

        def fake_open(path, flags, mode=0o777, *, dir_fd=None):
            fd = real_open(path, flags, mode, dir_fd=dir_fd)
            if flags & os.O_EXCL:
                self.sig.interrupt()
            return fd
        with mock.patch.object(os, "open", side_effect=fake_open):
            code, lines = self.run_verify()
        self.assertEqual((code, lines), (1, ["FAIL setup"]))
        self.assertEqual(self.net.connects, [])
        self.assert_no_fixture()

    def test_interrupt_during_cleanup_fails_v9(self):
        real_lstat = os.lstat

        def lstat(path, *a, dir_fd=None, **k):
            if dir_fd is not None and PNG_NAME.match(path) and os.path.exists(os.path.join(self.images, path)):
                self.sig.interrupt(2)
            return real_lstat(path, *a, dir_fd=dir_fd, **k)
        with mock.patch.object(os, "lstat", side_effect=lstat):
            code, lines = self.run_verify()
        self.assertEqual((code, lines), (1, PASS8 + ["V9 FAIL"]))
        self.assert_no_fixture()

    def test_interrupt_while_v9_is_written(self):
        out = HookOut("V9 PASS", self.sig.interrupt)
        code, lines = self.run_verify(out=out)
        self.assertEqual((code, lines), (1, ALL_PASS))  # the line stands; the exit code is 1
        self.assert_no_fixture()

    def test_writers(self):
        for step in (1, 3):
            with self.subTest(step=step):
                self.setUp()
                code, lines = self.run_verify(out=ShortOut(step))
                self.assertEqual((code, lines), (0, ALL_PASS))
        bad = {"None after a prefix": None, "zero": 0, "too large": lambda text: len(text) + 1,
               "a bool": True, "raises": OSError(errno.EPIPE, EXC_CANARY)}
        for name, reply in bad.items():
            with self.subTest(writer=name):
                self.setUp()
                out = BadOut(reply)
                code, lines = self.run_verify(out=out)
                self.assertEqual(code, 1)
                self.assertEqual(out.getvalue(), "V1")  # the first write was not confirmed: nothing more
                self.assert_no_keyed_request()  # the gate reads only lines written in full
                self.assert_no_fixture()

    def test_gate_reads_the_printed_lines(self):
        out = HookOut("V4 PASS", fail=True)
        code, lines = self.run_verify(out=out)
        self.assertEqual((code, lines), (1, PASS8[:3]))
        self.assertEqual(self.net.lines(), [1, 2, 3, 4])  # V4 was right, but it was not printed
        self.assert_no_keyed_request()
        self.assert_no_fixture()

    def test_key_predicate_in_run(self):
        for key in ("", "line\nbreak", "carriage\r", "nul\0", "tab\tkey", "klüssel", "del" + chr(127), None):
            with self.subTest(key=repr(key)):
                self.setUp()
                code, lines = self.run_verify(key=key)
                self.assertEqual((code, lines), (2, ["FAIL key"]))
                self.assertEqual((self.net.connects, self.sig.calls), ([], []))
                self.assert_no_fixture()

    def test_unrestorable_handler_refused(self):
        self.sig.current = None
        out = io.StringIO()
        code = verify.run(ADDRESS, self.home, REAL_KEY, out, 60)
        self.assertEqual((code, out.getvalue()), (1, "FAIL setup\n"))
        self.assertEqual(self.sig.calls, [])  # the setter was never called
        self.assertEqual(self.net.connects, [])
        self.assert_no_fixture()

    def test_off_the_main_thread_refused(self):
        got, out = {}, io.StringIO()
        with mock.patch.object(verify, "signal", signal):  # the real module: it refuses off the main thread
            before = signal.getsignal(signal.SIGINT)
            t = threading.Thread(target=lambda: got.update(code=verify.run(ADDRESS, self.home, REAL_KEY, out, 60)))
            t.start()
            t.join(10)
            self.assertIs(signal.getsignal(signal.SIGINT), before)
        self.assertEqual((got.get("code"), out.getvalue()), (1, "FAIL setup\n"))
        self.assertEqual(self.net.connects, [])
        self.assert_no_fixture()

    def test_handler_is_a_plain_flag_setter(self):
        self.run_verify()
        handler = self.sig.calls[0]
        self.assertIsInstance(handler, types.FunctionType)
        self.assertIsNone(handler(signal.SIGINT, None))
        self.assertIsNone(handler(signal.SIGINT, None))

    def test_canaries_never_printed(self):
        def garbage(req):
            return http(200, (BODY_CANARY + " " + path_of(req)).encode(), "text/plain")
        runs = [
            ({}, 0),
            ({2: RuntimeError(EXC_CANARY + " " + self.home)}, 1),
            ({5: RuntimeError(EXC_CANARY), 6: garbage, 7: garbage, 8: http(500, BODY_CANARY.encode())}, 1),
        ]
        for answers, want in runs:
            with self.subTest(want=want):
                self.setUp()
                code, lines = self.run_verify(answers)
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
        with self.wrap_open(fail_second):
            code, lines = self.run_verify()
        self.assertEqual(len(self.creates), 1)
        self.assertEqual((code, lines), (1, ["FAIL setup"]))
        self.assert_no_fixture()
        self.assertEqual(self.net.connects, [])

    def test_fixture_two_write_failure_removes_both(self):
        real_write = os.write

        def write(fd, data):
            if len(self.creates) == 2 and fd == self.creates[1]:
                raise OSError(errno.ENOSPC, "second write")
            return real_write(fd, data)
        with self.wrap_open(), mock.patch.object(os, "write", side_effect=write):
            code, lines = self.run_verify()
        self.assertEqual(len(self.creates), 2)
        self.assertEqual((code, lines), (1, ["FAIL setup"]))
        self.assert_no_fixture()
        self.assertEqual(self.net.connects, [])

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
                code, lines = self.run_verify(home=home)
                self.assertEqual((code, lines), (1, ["FAIL setup"]))
                self.assertEqual(self.net.connects, [])
                self.assert_no_fixture(os.path.join(other, "images"))
                self.assertEqual(os.listdir(other), ["images"])
                self.assertEqual(os.listdir(home), ["cache"])

    def test_existing_file_never_overwritten(self):
        hexes = iter(["a" * 32, "b" * 32, "c" * 32])
        real_hex = secrets.token_hex
        taken = "apofis-verify-%s.png" % ("a" * 32)
        with open(os.path.join(self.images, taken), "wb") as f:
            f.write(b"keep these bytes")
        with mock.patch.object(secrets, "token_hex", side_effect=lambda n=None: next(hexes) if n == 16 else real_hex(n)):
            code, lines = self.run_verify()
        self.assertEqual((code, lines), (1, ["FAIL setup"]))
        self.assertEqual(os.listdir(self.images), [taken])
        self.assertEqual(file_bytes(os.path.join(self.images, taken)), b"keep these bytes")
        self.assertEqual(self.net.connects, [])

    def test_v8_name_checked_absent(self):
        real_hex = secrets.token_hex
        for case in ("same as the PNG fixture", "an existing entry"):
            with self.subTest(case=case):
                self.setUp()
                hexes = iter(["a" * 32, "b" * 32, "a" * 32 if case == "same as the PNG fixture" else "c" * 32])
                taken = os.path.join(self.images, "apofis-verify-%s.png" % ("c" * 32))
                if case == "an existing entry":
                    os.symlink(os.path.join(self.home, "nowhere"), taken)  # dangling: only lstat sees it
                with mock.patch.object(secrets, "token_hex",
                                       side_effect=lambda n=None: next(hexes) if n == 16 else real_hex(n)):
                    code, lines = self.run_verify()
                self.assertEqual((code, lines), (1, ["FAIL setup"]))
                self.assertEqual(self.net.connects, [])
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
        code, lines = self.run_verify({6: self.swap})
        self.assertEqual((code, lines), (1, PASS8 + ["V9 FAIL"]))
        self.assertEqual(file_bytes(self.swapped), b"someone else's file")
        self.assertEqual(os.listdir(self.images), [os.path.basename(self.swapped)])
        os.unlink(self.swapped)

    def test_first_cleanup_failure_still_removes_second(self):
        real_unlink, real_close = os.unlink, os.close
        for case in ("swapped", "unlink raises"):
            with self.subTest(case=case):
                self.setUp()
                closed = []

                def close(fd):
                    closed.append(fd)
                    return real_close(fd)

                def unlink(path, *a, dir_fd=None):
                    if dir_fd is not None and not path.endswith("-text.png"):
                        raise OSError(errno.EIO, EXC_CANARY)
                    return real_unlink(path, *a, dir_fd=dir_fd)
                with contextlib.ExitStack() as stack:
                    stack.enter_context(self.wrap_open())
                    stack.enter_context(mock.patch.object(os, "close", side_effect=close))
                    if case == "unlink raises":
                        stack.enter_context(mock.patch.object(os, "unlink", side_effect=unlink))
                    code, lines = self.run_verify({6: self.swap} if case == "swapped" else None)
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
            code, lines = self.run_verify()
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
        code, lines = self.run_verify({6: inspect})
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
        absent = os.path.basename(path_of(self.net.requests[7]))
        self.assertEqual(self.net.requests[7]["line"], 8)
        self.assertRegex(absent, PNG_NAME)
        self.assertNotIn(absent, seen)
        self.assertFalse(os.path.exists(os.path.join(self.images, absent)))


class MainTest(Base):
    def call_main(self, argv, key=REAL_KEY, out=None):
        """main() with getpass replaced by a recorder; the network, clock and signals are the fakes."""
        self.prompts = []

        def prompt(text="", stream=None):
            self.prompts.append(text)
            if isinstance(key, BaseException):
                raise key
            return key(text) if callable(key) else key
        out, errout = io.StringIO() if out is None else out, io.StringIO()
        with mock.patch.object(getpass, "getpass", side_effect=prompt), contextlib.redirect_stderr(errout):
            try:
                code = verify.main(argv, out, 60)
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
                self.assertEqual((self.prompts, self.net.connects), ([], []))
                self.assert_no_fixture()

    def test_help_and_unresolvable_arguments_refused(self):
        for argv in (["-h"], ["--help"], ["--port", "8642", "-h", "--home", self.home]):
            with self.subTest(argv=argv):
                code, lines = self.call_main(argv)
                self.assertEqual((code, lines, self.stderr), (2, ["FAIL arguments"], ""))
                self.assertEqual(self.prompts, [])
        with mock.patch.object(os, "getcwd", side_effect=FileNotFoundError(errno.ENOENT, EXC_CANARY)):
            code, lines = self.call_main(["--port", "8642", "--home", "relative-home"])
        self.assertEqual((code, lines, self.stderr), (2, ["FAIL arguments"], ""))
        self.assertEqual(self.prompts, [])

    def test_loopback_hosts_accepted(self):
        for host in ("127.0.0.2", "::1"):
            with self.subTest(host=host):
                self.setUp()
                code, lines = self.call_main(["--port", "8642", "--host", host, "--home", self.home])
                self.assertEqual({a for a, t in self.net.connects}, {(host, 8642)})
                self.assertEqual((code, lines), (0, ALL_PASS))

    def test_default_home_two_levels_up(self):
        tree = self.tmpdir()
        os.makedirs(os.path.join(tree, "plugins", "apofis-media"))
        homes = []
        with mock.patch.object(verify, "__file__", os.path.join(tree, "plugins", "apofis-media", "verify.py")), \
                mock.patch.object(verify, "run", side_effect=lambda a, home, k, out, d: homes.append(home) or 0):
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
                self.assertEqual(self.net.connects, [])
                self.assert_no_fixture()

    def test_bad_keys_refused(self):
        for key in ("", "line\nbreak", "carriage\r", "nul\0", "tab\tkey", "klüssel", "del" + chr(127)):
            with self.subTest(key=repr(key)):
                with mock.patch.object(verify, "run", side_effect=AssertionError("run reached")):
                    code, lines = self.call_main(["--port", "8642", "--home", self.home], key=key)
                self.assertEqual((code, lines), (2, ["FAIL key"]))
                self.assertEqual(self.net.connects, [])
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

    def test_interrupt_outside_the_handler_window(self):
        def printed_then_interrupted(address, home, key, out, deadline):
            out.write("V1 PASS\n")
            raise KeyboardInterrupt()
        for run, want in ((KeyboardInterrupt(), ["FAIL setup"]), (printed_then_interrupted, ["V1 PASS"])):
            with self.subTest(printed=bool(want != ["FAIL setup"])):
                with mock.patch.object(verify, "run", side_effect=run):
                    code, lines = self.call_main(["--port", "8642", "--home", self.home])
                self.assertEqual((code, lines, self.stderr), (1, want, ""))

    def test_privacy_on_every_channel(self):
        def garbage(req):
            return http(200, (BODY_CANARY + " " + path_of(req)).encode(), "text/plain")
        runs = [
            ("passing", REAL_KEY, {}, 0),
            ("failing", REAL_KEY, {2: RuntimeError(EXC_CANARY + " " + self.home)}, 1),
            ("failing late", REAL_KEY, {5: RuntimeError(EXC_CANARY), 6: garbage, 7: garbage,
                                        8: http(500, BODY_CANARY.encode())}, 1),
            ("bad key", REAL_KEY + "\n" + EXC_CANARY, {}, 2),
        ]
        for case, key, answers, want in runs:
            with self.subTest(case=case):
                self.setUp()
                self.net.answers.update(answers)
                with channels() as got, mock.patch.object(getpass, "getpass", return_value=key):
                    code = verify.main(["--port", "8642", "--home", self.home], None, 60)
                self.assertEqual(code, want)
                lines = got["stdout"].getvalue().splitlines()
                self.assertTrue(lines)
                self.assert_fixed_lines(lines)
                everything = "\n".join([got["stdout"].getvalue(), got["stderr"].getvalue()] + got["log"])
                for canary in (REAL_KEY, self.home, BODY_CANARY, EXC_CANARY, "apofis-verify", "cache"):
                    self.assertNotIn(canary, everything)
                self.assert_no_fixture()


if __name__ == "__main__":
    unittest.main()
