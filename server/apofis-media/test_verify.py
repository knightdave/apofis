"""Offline tests for verify.py: a scripted fake transport, a temporary home, no socket of any kind.

Run inside a network namespace on a host with a live listener; CI runs the same command plain:
  python3 -I -B -m unittest discover -s server/apofis-media -p 'test_*.py' -v
"""
import contextlib
import errno
import getpass
import http.client
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
import unittest
import warnings
from unittest import mock

import verify

LIMIT = 64 * 1024
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


# --- fakes ------------------------------------------------------------------------------------------

class Resp:
    """A scripted answer. Records every size asked of its body and how many bytes it handed out."""

    def __init__(self, status, body=b"", ctype="application/json", on_read=None, length=None, short=None,
                 on_close=None):
        self.status, self._body, self._ctype, self._on_read = status, body, ctype, on_read
        self._length, self._short, self._on_close = length, short, on_close
        self._pos, self.asked, self.given = 0, [], 0

    def getheader(self, name, default=None):
        if name.lower() == "content-length" and self._length is not None:
            return str(self._length)
        return self._ctype if name.lower() == "content-type" else default

    def read(self, n=None):
        self.asked.append(n)
        if self._on_read:
            self._on_read()
        end = len(self._body) if n is None or n < 0 else self._pos + n
        if self._short:  # a source that hands out fewer bytes than asked
            end = min(end, self._pos + self._short)
        chunk = self._body[self._pos:end]
        self._pos += len(chunk)
        self.given += len(chunk)
        return chunk

    def close(self):
        if self._on_close:
            self._on_close()


class MemSock:
    """Stands in for a socket: http.client reads the whole answer from memory, nothing is opened."""

    def __init__(self, raw):
        self.raw = raw

    def makefile(self, *a, **k):
        return io.BytesIO(self.raw)


def http_answer(status, body, length=None):
    """A real http.client.HTTPResponse parsed from memory; `length` overrides the announced length."""
    head = "HTTP/1.1 %d X\r\nContent-Type: application/json\r\nContent-Length: %d\r\n\r\n" % (
        status, len(body) if length is None else length)
    resp = http.client.HTTPResponse(MemSock(head.encode() + body))
    resp.begin()
    return resp


def raiser(exc):
    def go():
        raise exc
    return go


def jbody(obj):
    return json.dumps(obj).encode()


def err(status, code):
    return Resp(status, jbody({"error": {"code": code, "message": "fixed " + BODY_CANARY}}))


def auth_failed():
    return Resp(401, jbody({"error": {"message": "Invalid " + BODY_CANARY, "type": "gateway_auth_error",
                                      "code": "gateway_auth_failed"}}))


def path_of(req):
    return json.loads(req["body"])["path"]


def file_bytes(path):
    with open(path, "rb") as f:
        return f.read()


class Fake:
    """Answers each V line from a table. A line is recognised from the request itself; `answers` maps a
    line to a Resp factory taking the recorded request, or to an exception to raise."""

    def __init__(self, answers=None, log=None):
        self.answers, self.log, self.requests, self.responses = answers or {}, log, [], []

    @staticmethod
    def line_of(path, headers, body):
        auth = headers.get("Authorization")
        keyed = auth == "Bearer " + REAL_KEY
        if path == PING:
            return 5 if keyed else (1 if auth is None else 2)
        if not keyed:
            return 3 if auth is None else 4
        p = json.loads(body)["path"]
        if p.endswith("-text.png"):
            return 7
        return 6 if os.path.exists(p) else 8

    def __call__(self, method, path, headers, body):
        line = self.line_of(path, headers, body)
        req = {"line": line, "method": method, "path": path, "headers": dict(headers), "body": body}
        self.requests.append(req)
        if self.log is not None:
            self.log.append(("req", line))
        answer = self.answers.get(line)
        if isinstance(answer, BaseException):
            raise answer
        resp = answer(req) if answer else self.default(line, req)
        self.responses.append(resp)
        return resp

    @staticmethod
    def default(line, req):
        if line <= 4:
            return auth_failed()
        if line == 5:
            return Resp(200, jbody(PING_OK))
        if line == 6:
            return Resp(200, file_bytes(path_of(req)), "image/png")
        return err(415, "not_a_picture") if line == 7 else err(404, "file_unavailable")

    def lines(self):
        return [r["line"] for r in self.requests]


def fire_deadline():
    signal.getsignal(signal.SIGALRM)(signal.SIGALRM, None)


def blocked_now():
    return {signal.SIGINT, signal.SIGALRM} & set(signal.pthread_sigmask(signal.SIG_BLOCK, []))


class SignalOut(io.StringIO):
    """An output that sends itself a real signal while one exact line is being written."""

    def __init__(self, sig, line):
        super().__init__()
        self.sig, self.line = sig, line

    def write(self, text):
        if text == self.line + "\n":
            signal.raise_signal(self.sig)
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
        timer = mock.patch.object(signal, "setitimer")  # never a real alarm
        self.setitimer = timer.start()
        self.addCleanup(timer.stop)

    def tmpdir(self):
        d = tempfile.mkdtemp(prefix="vt-")
        self.addCleanup(shutil.rmtree, d, True)
        return d

    def run_verify(self, fake, home=None, out=None):
        out = io.StringIO() if out is None else out
        try:
            code = verify.run(fake, home or self.home, REAL_KEY, out)
        except KeyboardInterrupt:  # a failure of this test, not of the whole process
            self.fail("an interruption escaped run")
        self.assertEqual(blocked_now(), set())  # no signal left blocked
        return code, out.getvalue().splitlines()

    def assert_no_keyed_request(self, fake):
        for r in fake.requests:
            self.assertNotIn(REAL_KEY, r["headers"].get("Authorization", ""))
            self.assertNotIn(REAL_KEY.encode(), r["body"] or b"")

    def assert_no_fixture(self, folder=None):
        self.assertEqual(os.listdir(folder or self.images), [])

    def assert_fixed_lines(self, lines):
        for line in lines:
            self.assertRegex(line, FIXED_LINE)


class RunTest(Base):
    def test_all_pass_exits_zero_and_leaves_no_fixture(self):
        fake = Fake()
        code, lines = self.run_verify(fake)
        self.assertEqual(code, 0)
        self.assertEqual(lines, ALL_PASS)
        self.assert_no_fixture()

    def test_request_shapes(self):
        fake = Fake()
        self.run_verify(fake)
        self.assertEqual(fake.lines(), list(range(1, 9)))
        by = {r["line"]: r for r in fake.requests}
        for n in (1, 3):
            self.assertNotIn("Authorization", by[n]["headers"])
        for n in (2, 4):
            auth = by[n]["headers"]["Authorization"]
            self.assertTrue(auth.startswith("Bearer ") and len(auth) > len("Bearer "))
            self.assertNotIn(REAL_KEY, auth)
        for n in (5, 6, 7, 8):
            self.assertEqual(by[n]["headers"]["Authorization"], "Bearer " + REAL_KEY)
        for n in (1, 2, 5):
            self.assertEqual((by[n]["method"], by[n]["path"], by[n]["body"]), ("GET", PING, None))
        paths = {}
        for n in (3, 4, 6, 7, 8):
            r = by[n]
            self.assertEqual((r["method"], r["path"]), ("POST", FILE))
            self.assertEqual(r["headers"]["Content-Type"], "application/json")
            obj = json.loads(r["body"])
            self.assertEqual(list(obj), ["path"])
            paths[n] = obj["path"]
            self.assertTrue(os.path.isabs(paths[n]))
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
            (1, lambda req: Resp(200, jbody(PING_OK))),
            (2, lambda req: err(401, "other_code")),
            (3, lambda req: err(403, "gateway_auth_failed")),
            (4, lambda req: Resp(401, jbody({"error": "gateway_auth_failed"}))),
            (5, lambda req: err(503, "unavailable")),
            (6, lambda req: Resp(200, file_bytes(path_of(req)), "image/jpeg")),
            (6, lambda req: Resp(200, file_bytes(path_of(req))[:-1], "image/png")),
            (7, lambda req: err(404, "file_unavailable")),
            (8, lambda req: err(404, "not_a_picture")),
        ]
        for n, answer in wrong:
            with self.subTest(line=n):
                fake = Fake({n: answer})
                code, lines = self.run_verify(fake)
                self.assertNotEqual(code, 0)
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
        self.assertNotEqual(code, 0)
        self.assertEqual(lines[5], "V6 FAIL")
        self.assertEqual(fake.lines(), list(range(1, 9)))
        self.assertEqual(lines[6:], ["V7 PASS", "V8 PASS", "V9 PASS"])

    def test_negative_fail_never_sends_real_key(self):
        for n in (1, 2, 3, 4):
            with self.subTest(line=n):
                fake = Fake({n: lambda req: Resp(200, jbody(PING_OK))})
                code, lines = self.run_verify(fake)
                self.assertNotEqual(code, 0)
                for r in fake.requests:
                    self.assertNotIn(REAL_KEY, r["headers"].get("Authorization", ""))
                    self.assertNotIn(REAL_KEY.encode(), r["body"] or b"")
                self.assertEqual(lines[4:8], ["V5 FAIL", "V6 FAIL", "V7 FAIL", "V8 FAIL"])
                self.assertTrue(set(fake.lines()) <= {1, 2, 3, 4})
                self.assert_no_fixture()

    def test_v5_exact_types(self):
        cases = [("protocol", True), ("protocol", "1"), ("protocol", 1.0), ("ready", "true"), ("ready", 1),
                 ("max_bytes", 0), ("max_bytes", -1), ("max_bytes", True), ("types", "notpng"),
                 ("types", ["jpeg"]), ("types", [1]), ("types", ["png", 1]), ("name", "apofis-other")]
        for field, value in cases:
            with self.subTest(field=field, value=value):
                body = dict(PING_OK, **{field: value})
                code, lines = self.run_verify(Fake({5: lambda req: Resp(200, jbody(body))}))
                self.assertEqual(lines[4], "V5 FAIL")
                self.assertEqual(lines[5:], ["V6 PASS", "V7 PASS", "V8 PASS", "V9 PASS"])
                self.assertNotEqual(code, 0)

    def test_proxy_error_page_fails(self):
        for status in (401, 502):
            with self.subTest(status=status):
                page = b"<html><body><h1>%d</h1>gateway_auth_failed</body></html>" % status
                code, lines = self.run_verify(Fake({1: lambda req: Resp(status, page, "text/html")}))
                self.assertEqual(lines[0], "V1 FAIL")
                self.assertNotEqual(code, 0)

    def test_oversized_bodies_fail_and_clean_up(self):
        pad = "x" * (1 << 20)
        png = verify.PNG + b"\0" * (1 << 20)  # the fixture's own bytes, padded past the limit
        answers = {5: (200, jbody(dict(PING_OK, pad=pad)), "application/json"), 6: (200, png, "image/png"),
                   7: (415, jbody({"error": {"code": "not_a_picture", "message": pad}}), "application/json")}
        for n, (status, body, ctype) in answers.items():
            with self.subTest(line=n):
                resp = Resp(status, body, ctype)
                code, lines = self.run_verify(Fake({n: lambda req: resp}))
                self.assertEqual(lines[n - 1], "V%d FAIL" % n)
                self.assertNotEqual(code, 0)
                self.assert_no_fixture()
                self.assertTrue(resp.asked)
                for asked in resp.asked:
                    self.assertIsInstance(asked, int)
                    self.assertLessEqual(asked, LIMIT + 1)
                self.assertLessEqual(sum(resp.asked), LIMIT + 1)
                self.assertLessEqual(resp.given, LIMIT + 1)

    def test_deadline_fails_current_and_later_lines_and_cleans_up(self):
        def slow(req):
            return Resp(200, file_bytes(path_of(req)), "image/png", on_read=fire_deadline)
        fake = Fake({6: slow})
        code, lines = self.run_verify(fake)
        self.assertNotEqual(code, 0)
        self.assertEqual(lines[:8], ["V1 PASS", "V2 PASS", "V3 PASS", "V4 PASS", "V5 PASS",
                                     "V6 FAIL", "V7 FAIL", "V8 FAIL"])
        self.assertEqual(fake.lines(), [1, 2, 3, 4, 5, 6])
        self.assertEqual(len(lines), 9)
        self.assert_no_fixture()

    def test_timer_armed_then_cleared_before_cleanup(self):
        events = []
        self.setitimer.side_effect = lambda which, seconds, *a: events.append(("timer", which, seconds))
        before = signal.getsignal(signal.SIGALRM)
        real_unlink = os.unlink

        def unlink(*a, **k):
            events.append(("unlink",))
            return real_unlink(*a, **k)
        with mock.patch.object(os, "unlink", side_effect=unlink):
            code, lines = self.run_verify(Fake(log=events))
        self.assertEqual(code, 0)
        self.assertEqual(events[0], ("timer", signal.ITIMER_REAL, 60))
        self.assertEqual(events[1], ("req", 1))
        cleared = events.index(("timer", signal.ITIMER_REAL, 0))
        self.assertLess(cleared, events.index(("unlink",)))
        self.assertGreater(cleared, events.index(("req", 8)))
        self.assertEqual(signal.getsignal(signal.SIGALRM), before)

    def test_transport_exception_still_removes_both(self):
        code, lines = self.run_verify(Fake({6: OSError("connection reset")}))
        self.assertNotEqual(code, 0)
        self.assertEqual(lines[5], "V6 FAIL")
        self.assert_no_fixture()

    def test_interrupt_prints_fail_and_cleans_up(self):
        fake = Fake({6: KeyboardInterrupt()})
        code, lines = self.run_verify(fake)
        self.assertEqual(code, 1)
        self.assertEqual(lines, ["V1 PASS", "V2 PASS", "V3 PASS", "V4 PASS", "V5 PASS",
                                 "V6 FAIL", "V7 FAIL", "V8 FAIL", "V9 FAIL"])
        self.assertEqual(fake.lines(), [1, 2, 3, 4, 5, 6])
        self.assert_no_fixture()

    def test_canaries_never_printed(self):
        def garbage(req):
            return Resp(200, (BODY_CANARY + " " + path_of(req)).encode(), "text/plain")
        runs = [
            (Fake(), 0),
            (Fake({2: RuntimeError(EXC_CANARY + " " + self.home)}), 1),
            (Fake({5: RuntimeError(EXC_CANARY), 6: garbage, 7: garbage,
                   8: lambda req: Resp(500, BODY_CANARY.encode())}), 1),
        ]
        for fake, failing in runs:
            with self.subTest(failing=failing):
                code, lines = self.run_verify(fake)
                self.assertEqual(code != 0, bool(failing))
                text = "\n".join(lines)
                for canary in (REAL_KEY, self.home, BODY_CANARY, EXC_CANARY, "apofis-verify", "cache"):
                    self.assertNotIn(canary, text)
                self.assertEqual(len(lines), 9)
                self.assert_fixed_lines(lines)


    def test_read_budget_small_and_short_bodies(self):
        fake = Fake({5: lambda req: Resp(200, jbody(PING_OK), short=10)})
        code, lines = self.run_verify(fake)
        self.assertEqual(len(fake.responses), 8)
        for resp in fake.responses:  # small valid bodies and the short source alike
            self.assertTrue(resp.asked)
            self.assertLessEqual(sum(resp.asked), LIMIT + 1)
        self.assertEqual(lines[4], "V5 FAIL")
        self.assertEqual(lines[5:], ["V6 PASS", "V7 PASS", "V8 PASS", "V9 PASS"])
        self.assert_no_fixture()

    def test_negative_line_refuses_bad_framing_and_constants(self):
        good = {"error": {"code": "gateway_auth_failed", "message": "fixed", "type": "gateway_auth_error"}}
        body = jbody(good)
        cases = {
            "NaN": lambda req: Resp(401, body[:-1] + b', "x": NaN}'),
            "Infinity": lambda req: Resp(401, body[:-1] + b', "x": Infinity}'),
            "-Infinity": lambda req: Resp(401, body[:-1] + b', "x": -Infinity}'),
            "short framing": lambda req: http_answer(401, body, length=len(body) + 40),
            "announced over limit": lambda req: Resp(401, body, length=LIMIT + 1),
        }
        for n in (1, 3):
            for case, answer in cases.items():
                with self.subTest(line=n, case=case):
                    fake = Fake({n: answer})
                    code, lines = self.run_verify(fake)
                    self.assertEqual(lines[n - 1], "V%d FAIL" % n)
                    self.assertEqual(lines[4:8], ["V5 FAIL", "V6 FAIL", "V7 FAIL", "V8 FAIL"])
                    self.assert_no_keyed_request(fake)
                    self.assertTrue(set(fake.lines()) <= {1, 2, 3, 4})
                    self.assertEqual(code, 1)
                    self.assert_no_fixture()
                    if case == "announced over limit":
                        self.assertEqual(fake.responses[n - 1].asked, [])

    def test_memory_backed_answers_pass(self):
        body = jbody({"error": {"code": "gateway_auth_failed", "message": "fixed"}})
        fake = Fake({n: (lambda req: http_answer(401, body)) for n in (1, 2, 3, 4)})
        code, lines = self.run_verify(fake)
        self.assertEqual((code, lines), (0, ALL_PASS))

    def test_deadline_latched_when_swallowed(self):
        def swallow(req):
            try:
                fire_deadline()
            except BaseException:
                pass
            return Resp(200, file_bytes(path_of(req)), "image/png")
        before = signal.getsignal(signal.SIGALRM)
        fake = Fake({6: swallow})
        code, lines = self.run_verify(fake)
        self.assertEqual(fake.lines(), [1, 2, 3, 4, 5, 6])
        self.assertEqual(lines[5:], ["V6 FAIL", "V7 FAIL", "V8 FAIL", "V9 PASS"])
        self.assertEqual(code, 1)
        self.assertEqual(signal.getsignal(signal.SIGALRM), before)
        self.assert_no_fixture()

    def test_close_error_cannot_hide_deadline_or_interrupt(self):
        before = signal.getsignal(signal.SIGALRM)
        cases = {"deadline": (fire_deadline, ["V6 FAIL", "V7 FAIL", "V8 FAIL", "V9 PASS"]),
                 "interrupt": (raiser(KeyboardInterrupt()), ["V6 FAIL", "V7 FAIL", "V8 FAIL", "V9 FAIL"])}
        for case, (on_read, tail) in cases.items():
            with self.subTest(case=case):
                self.setitimer.reset_mock()

                def answer(req):
                    return Resp(200, file_bytes(path_of(req)), "image/png", on_read=on_read,
                                on_close=raiser(OSError(EXC_CANARY)))
                fake = Fake({6: answer})
                code, lines = self.run_verify(fake)
                self.assertEqual(fake.lines(), [1, 2, 3, 4, 5, 6])
                self.assertEqual(lines[5:], tail)
                self.assertEqual(code, 1)
                self.assertEqual(self.setitimer.call_args_list[-1], mock.call(signal.ITIMER_REAL, 0))
                self.assertEqual(signal.getsignal(signal.SIGALRM), before)
                self.assert_no_fixture()

    def test_signal_at_publication(self):
        passed = ["V%d PASS" % n for n in range(1, 9)]
        cases = [
            (signal.SIGALRM, "V5 PASS", passed[:5] + ["V6 FAIL", "V7 FAIL", "V8 FAIL", "V9 PASS"], [1, 2, 3, 4, 5]),
            (signal.SIGALRM, "V8 PASS", ALL_PASS, list(range(1, 9))),
            (signal.SIGINT, "V5 PASS", passed[:5] + ["V6 FAIL", "V7 FAIL", "V8 FAIL", "V9 FAIL"], [1, 2, 3, 4, 5]),
            (signal.SIGINT, "V8 PASS", passed + ["V9 FAIL"], list(range(1, 9))),
        ]
        for sig, at, want, requested in cases:
            with self.subTest(signal=sig.name, at=at):
                fake = Fake()
                code, lines = self.run_verify(fake, out=SignalOut(sig, at))
                self.assertEqual(lines, want)
                self.assertEqual(code, 0 if want == ALL_PASS else 1)
                self.assertEqual(fake.lines(), requested)
                self.assert_no_fixture()


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
        self.assertEqual(lines, ["FAIL setup"])
        self.assertEqual(code, 1)
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
        self.assertEqual(lines, ["FAIL setup"])
        self.assertEqual(code, 1)
        self.assert_no_fixture()
        self.assertEqual(fake.requests, [])

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
                self.assertEqual(lines, ["FAIL setup"])
                self.assertEqual(code, 1)
                self.assertEqual(fake.requests, [])
                self.assert_no_fixture(os.path.join(other, "images"))
                self.assertEqual(os.listdir(other), ["images"])
                self.assertEqual(os.listdir(home), ["cache"])
                if case != "linked cache":
                    self.assertEqual(os.listdir(os.path.join(home, "cache")),
                                     ["images"] if case == "linked images" else [])

    def test_existing_file_never_overwritten(self):
        hexes = iter(["a" * 32, "b" * 32, "c" * 32])
        real_hex = secrets.token_hex
        taken = "apofis-verify-%s.png" % ("a" * 32)
        with open(os.path.join(self.images, taken), "wb") as f:
            f.write(b"keep these bytes")
        fake = Fake()
        with mock.patch.object(secrets, "token_hex", side_effect=lambda n=None: next(hexes) if n == 16 else real_hex(n)):
            code, lines = self.run_verify(fake)
        self.assertEqual(lines, ["FAIL setup"])
        self.assertEqual(code, 1)
        self.assertEqual(os.listdir(self.images), [taken])
        self.assertEqual(file_bytes(os.path.join(self.images, taken)), b"keep these bytes")
        self.assertEqual(fake.requests, [])

    def swap(self, req):
        """Replace the PNG fixture with a new file of the same name, then answer V6 correctly."""
        p = path_of(req)
        data = file_bytes(p)
        os.unlink(p)
        with open(p, "wb") as f:
            f.write(b"someone else's file")
        self.swapped = p
        return Resp(200, data, "image/png")

    def test_swapped_fixture_not_unlinked(self):
        code, lines = self.run_verify(Fake({6: self.swap}))
        self.assertNotEqual(code, 0)
        self.assertEqual(lines[5], "V6 PASS")
        self.assertEqual(lines[8], "V9 FAIL")
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
                self.assertNotEqual(code, 0)
                self.assertEqual(lines[8], "V9 FAIL")
                left = os.listdir(self.images)
                self.assertEqual(len(left), 1)
                self.assertRegex(left[0], PNG_NAME)
                self.assertEqual(sorted(closed), sorted(self.opened))
                self.assertEqual(len(closed), len(set(closed)))
                self.assertEqual(len(self.opened), 5)
                self.assertNotIn(EXC_CANARY, "\n".join(lines))
                self.assert_fixed_lines(lines)
                real_unlink(os.path.join(self.images, left[0]))

    def test_fixture_modes_and_names(self):
        seen = {}

        def inspect(req):
            for name in os.listdir(self.images):
                st = os.lstat(os.path.join(self.images, name))
                seen[name] = (stat.S_IMODE(st.st_mode), stat.S_ISREG(st.st_mode),
                              file_bytes(os.path.join(self.images, name)))
            return Resp(200, file_bytes(path_of(req)), "image/png")
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


    def wrap_lstat(self, act):
        """Wrap os.lstat: after each lstat by descriptor of an existing PNG fixture, call act(count)."""
        real_lstat, count = os.lstat, []

        def lstat(path, *a, dir_fd=None, **k):
            st = real_lstat(path, *a, dir_fd=dir_fd, **k)
            if dir_fd is not None and PNG_NAME.match(path):
                count.append(path)
                act(len(count))
            return st
        return mock.patch.object(os, "lstat", side_effect=lstat)

    def test_interrupt_during_create_keeps_ownership(self):
        for which in (1, 2):
            with self.subTest(create=which):
                real_open = os.open
                made = []

                def fake_open(path, flags, mode=0o777, *, dir_fd=None):
                    fd = real_open(path, flags, mode, dir_fd=dir_fd)
                    if flags & os.O_EXCL:
                        made.append(fd)
                        if len(made) == which:
                            signal.raise_signal(signal.SIGINT)  # right after the create, before its return
                    return fd
                fake = Fake()
                with mock.patch.object(os, "open", side_effect=fake_open):
                    code, lines = self.run_verify(fake)
                self.assertEqual(lines, ["FAIL setup"])
                self.assertEqual(code, 1)
                self.assertEqual(fake.requests, [])
                self.assert_no_fixture()

    def test_interrupt_in_cleanup_step_is_deferred(self):
        def interrupt(count):
            if count == 1:
                signal.raise_signal(signal.SIGINT)
        with self.wrap_lstat(interrupt):
            code, lines = self.run_verify(Fake())
        self.assertEqual(lines, ALL_PASS)  # V9 is the real result; the interruption arrives after it
        self.assertEqual(code, 1)
        self.assert_no_fixture()

    def test_cleanup_keeps_entry_until_handled(self):
        def interrupt(count):
            if count <= 2:
                raise KeyboardInterrupt()  # raised directly: no mask can defer it
        for case in ("after the checks", "after a setup failure"):
            with self.subTest(case=case):
                real_write = os.write

                def write(fd, data):
                    if data == verify.TEXT:
                        raise OSError(errno.ENOSPC, "second write")
                    return real_write(fd, data)
                with contextlib.ExitStack() as stack:
                    stack.enter_context(self.wrap_lstat(interrupt))
                    if case == "after a setup failure":
                        stack.enter_context(mock.patch.object(os, "write", side_effect=write))
                    code, lines = self.run_verify(Fake())
                if case == "after the checks":
                    self.assertEqual(lines, ["V%d PASS" % n for n in range(1, 9)] + ["V9 FAIL"])
                else:
                    self.assertEqual(lines, ["FAIL setup"])
                self.assertEqual(code, 1)
                self.assert_no_fixture()

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
        self.assertEqual(lines, ["V%d PASS" % n for n in range(1, 9)] + ["V9 FAIL"])
        self.assertEqual(code, 1)
        self.assertEqual(sorted(closed), sorted(self.opened))  # every descriptor closed, none twice
        self.assertEqual(closed.count(self.creates[0]), 1)
        self.assert_no_fixture()

    def test_v8_name_checked_absent(self):
        real_hex = secrets.token_hex
        for case in ("same as the PNG fixture", "an existing entry"):
            with self.subTest(case=case):
                third = "a" * 32 if case == "same as the PNG fixture" else "c" * 32
                hexes = iter(["a" * 32, "b" * 32, third])
                taken = os.path.join(self.images, "apofis-verify-%s.png" % ("c" * 32))
                if case == "an existing entry":
                    os.symlink(os.path.join(self.home, "nowhere"), taken)  # dangling: only lstat sees it
                fake = Fake()
                with mock.patch.object(secrets, "token_hex",
                                       side_effect=lambda n=None: next(hexes) if n == 16 else real_hex(n)):
                    code, lines = self.run_verify(fake)
                self.assertEqual(lines, ["FAIL setup"])
                self.assertEqual(code, 1)
                self.assertEqual(fake.requests, [])
                if case == "an existing entry":
                    self.assertEqual(os.listdir(self.images), [os.path.basename(taken)])
                    self.assertTrue(os.path.islink(taken))
                    os.unlink(taken)
                self.assert_no_fixture()


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
                mock.patch.object(verify, "HttpTransport", side_effect=transport), \
                contextlib.redirect_stderr(errout):
            try:
                code = verify.main(argv, out)
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
                self.assertNotEqual(code, 0)
                self.assertEqual(lines, ["FAIL arguments"])
                self.assertEqual(self.stderr, "")
                self.assertEqual((self.prompts, self.transports), ([], []))
                self.assert_no_fixture()

    def test_loopback_hosts_accepted(self):
        for host in ("127.0.0.2", "::1"):
            with self.subTest(host=host):
                code, lines = self.call_main(["--port", "8642", "--host", host, "--home", self.home])
                self.assertEqual(self.transports, [(host, 8642)])
                self.assertEqual(lines, ALL_PASS)
                self.assertEqual(code, 0)

    def test_default_home_two_levels_up(self):
        tree = self.tmpdir()
        os.makedirs(os.path.join(tree, "plugins", "apofis-media"))
        homes = []
        with mock.patch.object(verify, "__file__", os.path.join(tree, "plugins", "apofis-media", "verify.py")), \
                mock.patch.object(verify, "run", side_effect=lambda t, home, k, out: homes.append(home) or 0):
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
                self.assertEqual(lines, ["FAIL key"])
                self.assertNotEqual(code, 0)
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

    def test_pre_network_failure_classified(self):
        def broken(host, port):
            raise RuntimeError(EXC_CANARY)
        out, errout = io.StringIO(), io.StringIO()
        with mock.patch.object(getpass, "getpass", return_value=REAL_KEY), \
                mock.patch.object(verify, "HttpTransport", side_effect=broken), contextlib.redirect_stderr(errout):
            code = verify.main(["--port", "8642", "--home", self.home], out)
        self.assertEqual((code, out.getvalue(), errout.getvalue()), (1, "FAIL setup\n", ""))
        self.assert_no_fixture()

    def test_bad_keys_refused(self):
        for key in ("", "line\nbreak", "carriage\r", "nul\0", "tab\tkey", "kl\u00fcssel", "del\x7f"):
            with self.subTest(key=repr(key)):
                code, lines = self.call_main(["--port", "8642", "--home", self.home], key=key)
                self.assertEqual((code, lines), (2, ["FAIL key"]))
                self.assertEqual(self.transports, [])
                self.assert_no_fixture()

    def test_privacy_on_every_channel(self):
        def garbage(req):
            return Resp(200, (BODY_CANARY + " " + path_of(req)).encode(), "text/plain")
        runs = [
            ("passing", REAL_KEY, None, 0),
            ("failing", REAL_KEY, {2: RuntimeError(EXC_CANARY + " " + self.home)}, 1),
            ("failing late", REAL_KEY, {5: RuntimeError(EXC_CANARY), 6: garbage, 7: garbage,
                                        8: lambda req: Resp(500, BODY_CANARY.encode())}, 1),
            ("bad key", REAL_KEY + "\n" + EXC_CANARY, None, 2),
        ]
        for case, key, answers, want in runs:
            with self.subTest(case=case):
                self.prompts, self.transports = [], []
                with channels() as got, mock.patch.object(getpass, "getpass", return_value=key), \
                        mock.patch.object(verify, "HttpTransport", side_effect=lambda h, p: Fake(answers)):
                    code = verify.main(["--port", "8642", "--home", self.home])
                self.assertEqual(code, want)
                lines = got["stdout"].getvalue().splitlines()
                self.assertTrue(lines)
                self.assert_fixed_lines(lines)
                everything = "\n".join([got["stdout"].getvalue(), got["stderr"].getvalue()] + got["log"])
                for canary in (REAL_KEY, self.home, BODY_CANARY, EXC_CANARY, "apofis-verify", "cache"):
                    self.assertNotIn(canary, everything)
                self.assert_no_fixture()

    def test_production_transport_lifecycle(self):
        made, events = [], []

        class Answer:  # a persistent HTTP/1.1 answer: the connection keeps its socket
            status, will_close = 200, False

            def __init__(self, fail_close=False):
                self.fail_close = fail_close

            def getheader(self, name, default=None):
                return "application/json" if name.lower() == "content-type" else default

            def read(self, n):
                events.append(("read", n))
                return b"{}"

            def close(self):
                events.append("response closed")
                if self.fail_close:
                    raise OSError(EXC_CANARY)

        class Conn:
            mode = "ok"

            def __init__(self, *a, **k):
                self.args, self.kwargs, self.calls = a, k, []
                made.append(self)

            def request(self, method, url, body=None, headers=None):
                self.calls.append((method, url, body, dict(headers or {})))

            def getresponse(self):
                if Conn.mode == "raise":
                    raise OSError(EXC_CANARY)
                return Answer(fail_close=Conn.mode == "fail close")

            def connect(self):
                raise AssertionError("no connection may be opened")

            def close(self):
                events.append("connection closed")

        with mock.patch.object(http.client, "HTTPConnection", Conn):
            transport = verify.HttpTransport("127.0.0.1", 8642)
            answer = transport("GET", PING, {"Authorization": "Bearer x"}, None)
            self.assertEqual((answer.status, answer.getheader("Content-Type")), (200, "application/json"))
            self.assertEqual(answer.read(LIMIT + 1), b"{}")
            self.assertNotIn("connection closed", events)  # kept until the answer is consumed
            answer.close()
            self.assertEqual(events[-2:], ["response closed", "connection closed"])

            events.clear()
            Conn.mode = "fail close"
            answer = transport("GET", PING, {}, None)
            with self.assertRaises(OSError):
                answer.close()
            self.assertEqual(events, ["response closed", "connection closed"])

            events.clear()
            Conn.mode = "raise"
            with self.assertRaises(OSError):
                transport("GET", PING, {}, None)
            self.assertEqual(events, ["connection closed"])
        self.assertEqual(len(made), 3)
        self.assertEqual((made[0].args, made[0].kwargs), (("127.0.0.1", 8642), {"timeout": 10}))
        method, url, body, headers = made[0].calls[0]
        self.assertEqual((method, url, body), ("GET", PING, None))
        self.assertEqual(headers["Authorization"], "Bearer x")
        self.assertEqual(headers.get("Connection"), "close")


if __name__ == "__main__":
    unittest.main()
