"""The owner's check of the apofis-media add-on against this profile's own API server listener.

Run once on the Hermes host, as the gateway's account, after install and restart:

    python3 -I <Location>/verify.py --port N [--host IP] [--home DIR]

Type the API server key at "API key:". The script creates two fixture files in <home>/cache/images,
sends eight requests to the listener and removes exactly those two files. It prints only the lines
V1 to V9 PASS or FAIL (or one FAIL line for a refused argument, key or setup) and exits 0 only when
all nine pass. Never imported by the plugin. Standard library only.
"""
import argparse
import getpass
import ipaddress
import json
import os
import re
import secrets
import signal
import socket
import sys
import time
import warnings

DEADLINE_S = 60  # one wall-clock deadline for the whole network phase, on the monotonic clock
TIMEOUT_S = 10  # the longest connect, and the longest wait without a received byte
SLICE_S = 0.25  # one receive wait; the flag and the deadline are looked at between slices
BODY_LIMIT = 64 * 1024  # ping, error and file bodies alike
HEAD_LIMIT = 8 * 1024  # status line and header lines of one answer
ANSWER_LIMIT = HEAD_LIMIT + 4 + BODY_LIMIT  # the transport reads at most this plus one byte
PING = "/apofis-media/v1/ping"
FILE = "/apofis-media/v1/file"
PNG = bytes.fromhex(  # a valid 1 by 1 grey PNG
    "89504e470d0a1a0a0000000d49484452000000010000000108000000003a7e9b55"
    "0000000a49444154789c636000000002000148afa4710000000049454e44ae426082")
TEXT = b"apofis-media verifier fixture: plain text, not a picture\n"
DIR_FLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC
CREATE_FLAGS = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC
STATUS_LINE = re.compile(rb"HTTP/1\.[01] ([0-9]{3}) [ -~]*")
HEADER_NAME = re.compile(rb"[A-Za-z0-9-]+")
HEADER_VALUE = re.compile(rb"[ -~\t]*")


def key_ok(key):
    """One line of printable ASCII: the only keys this verifier sends."""
    return isinstance(key, str) and bool(key) and key.isascii() and key.isprintable()


def _exchange(address, request, flag, end):
    """The raw answer, read until the peer closes; None once the flag is set, the deadline passes, ten seconds
    go by without a byte or the answer outgrows ANSWER_LIMIT. The socket never leaves this function."""
    if flag[0] or time.monotonic() >= end:
        return None
    sock = socket.create_connection(address, timeout=min(TIMEOUT_S, end - time.monotonic()))
    try:
        left = end - time.monotonic()
        if flag[0] or left <= 0:  # a connect that returned late is not permission to send
            return None
        sock.settimeout(min(TIMEOUT_S, left))
        sock.sendall(request)
        sock.settimeout(SLICE_S)
        data, last = b"", time.monotonic()
        while True:
            try:
                chunk = sock.recv(ANSWER_LIMIT + 1 - len(data))
            except TimeoutError:
                chunk = None
            now = time.monotonic()
            if flag[0] or now >= end or now - last >= TIMEOUT_S:  # idle: measured from the previous byte
                return None
            if chunk:
                data, last = data + chunk, now
            if len(data) > ANSWER_LIMIT:
                return None
            if chunk == b"":
                return data
    finally:
        sock.close()


def _request(method, path, key=None, body=None):
    lines = ["%s %s HTTP/1.1" % (method, path), "Host: localhost", "Connection: close"]
    if body is not None:
        lines += ["Content-Type: application/json", "Content-Length: %d" % len(body)]
    if key is not None:
        lines.append("Authorization: Bearer " + key)
    return ("\r\n".join(lines) + "\r\n\r\n").encode("ascii") + (body or b"")


def parse(raw):
    """(status, content type, body) of one strictly framed answer, else None: a status line with a status
    from 100 to 599, header lines, exactly one Content-Length of ASCII digits, no Transfer-Encoding or
    Content-Encoding, the body exactly that long."""
    head, sep, body = raw.partition(b"\r\n\r\n")
    if not sep or len(head) > HEAD_LIMIT:
        return None
    lines = head.split(b"\r\n")
    status = STATUS_LINE.fullmatch(lines[0])
    if not status or not 100 <= int(status.group(1)) <= 599:
        return None
    headers = {}
    for line in lines[1:]:
        name, colon, value = line.partition(b":")
        if not colon or not HEADER_NAME.fullmatch(name) or not HEADER_VALUE.fullmatch(value):
            return None
        headers.setdefault(name.lower(), []).append(value.strip(b" \t"))
    lengths = headers.get(b"content-length", [])
    if (b"transfer-encoding" in headers or b"content-encoding" in headers or len(lengths) != 1
            or not lengths[0].isdigit() or int(lengths[0]) > BODY_LIMIT or len(body) != int(lengths[0])):
        return None
    return int(status.group(1)), b", ".join(headers.get(b"content-type", [])).decode("ascii"), body


def _no_constant(name):
    raise ValueError("not JSON")


def _json(body):
    return json.loads(body.decode("utf-8"), parse_constant=_no_constant)  # strict UTF-8, no NaN


def _object(answer, status):
    """The JSON object of an answer with this status, else an empty dict."""
    obj = _json(answer[2]) if answer[0] == status else None
    return obj if isinstance(obj, dict) else {}


def _error_code(answer, status):
    err = _object(answer, status).get("error")
    return err.get("code") if isinstance(err, dict) else None


def _ping_ready(answer):
    obj = _object(answer, 200)
    types = obj.get("types")
    return (obj.get("name") == "apofis-media"
            and type(obj.get("protocol")) is int and obj["protocol"] == 1
            and obj.get("ready") is True
            and type(obj.get("max_bytes")) is int and obj["max_bytes"] > 0
            and type(types) is list and all(type(t) is str for t in types) and "png" in types)


def _checks(key, png, text, absent):
    """V1 to V8 as (request, judge) pairs; a judge returns True for PASS on a parsed answer."""
    wrong = secrets.token_hex(32)
    while wrong == key:
        wrong = secrets.token_hex(32)

    def file(k, path):
        return _request("POST", FILE, k, json.dumps({"path": path}).encode("ascii"))

    def refused(a):
        return _error_code(a, 401) == "gateway_auth_failed"

    return ((_request("GET", PING), refused), (_request("GET", PING, wrong), refused),
            (file(None, png), refused), (file(wrong, png), refused),
            (_request("GET", PING, key), _ping_ready),
            (file(key, png), lambda a: a == (200, "image/png", PNG)),
            (file(key, text), lambda a: _error_code(a, 415) == "not_a_picture"),
            (file(key, absent), lambda a: _error_code(a, 404) == "file_unavailable"))


def _write(out, text):
    """True when the writer confirmed the whole text: each call must return a count from 1 to what is
    left. Anything else (None, zero, too large, an exception) is a failed output."""
    while text:
        try:
            n = out.write(text)
        except Exception:
            return False
        if type(n) is not int or not 1 <= n <= len(text):
            return False
        text = text[n:]
    return True


def _setup(home, fds, owned):
    """Opens home/cache/images without following links and creates the two fixtures in it. Each
    descriptor goes to `fds` and each fixture to `owned` as soon as it exists. Returns three paths."""
    def opened(*args, **kwargs):
        fds.append(os.open(*args, **kwargs))
        return fds[-1]
    d = opened(home, DIR_FLAGS)
    d = opened("cache", DIR_FLAGS | os.O_NOFOLLOW, dir_fd=d)
    d = opened("images", DIR_FLAGS | os.O_NOFOLLOW, dir_fd=d)
    names = ("apofis-verify-%s.png" % secrets.token_hex(16),
             "apofis-verify-%s-text.png" % secrets.token_hex(16),
             "apofis-verify-%s.png" % secrets.token_hex(16))  # the third is never created
    for name, data in zip(names, (PNG, TEXT)):
        fd = opened(name, CREATE_FLAGS, 0o600, dir_fd=d)
        owned.append((d, name, fd))  # owned before the first byte is written
        while data:
            data = data[os.write(fd, data):]
    try:
        os.lstat(names[2], dir_fd=d)  # V8's name must be missing; lstat neither follows nor opens it
    except FileNotFoundError:
        return [os.path.join(home, "cache", "images", name) for name in names]
    raise FileExistsError()


def _cleanup(fds, owned):
    """Unlinks each owned fixture only while its name still points at the file this script created, then
    closes every descriptor once. True when both fixtures were removed and every close succeeded."""
    removed, closed = 0, True
    for d, name, fd in owned:
        try:
            entry, mine = os.lstat(name, dir_fd=d), os.fstat(fd)
            if (entry.st_dev, entry.st_ino) == (mine.st_dev, mine.st_ino):
                os.unlink(name, dir_fd=d)
                removed += 1
        except Exception:
            pass
    for fd in reversed(fds):
        try:
            os.close(fd)
        except Exception:  # never retried: the number may already belong to someone else
            closed = False
    owned.clear()
    fds.clear()  # each fixture and descriptor is handled exactly once
    return removed == 2 and closed


def run(address, home, key, out, deadline=DEADLINE_S):
    """The checks of spec section 4 against the listener at `address`, a (host, port) pair; returns the
    exit code. Single-threaded: check, decide, print. While it owns anything, Ctrl-C only sets a flag."""
    if not key_ok(key):
        _write(out, "FAIL key\n")
        return 2
    previous, flag = signal.getsignal(signal.SIGINT), [False]

    def interrupt(signum, frame):  # one assignment: nothing else may run inside a signal handler here
        flag[0] = True

    try:
        if previous is None:  # a disposition Python could not put back
            raise ValueError()
        signal.signal(signal.SIGINT, interrupt)
    except ValueError:  # ... or not the main thread
        _write(out, "FAIL setup\n")
        return 1
    fds, owned, results = [], [], []
    try:
        try:
            checks = _checks(key, *_setup(home, fds, owned))  # preparation: the fixtures and every request
        except Exception:
            checks = None
        if checks is None or flag[0]:
            _cleanup(fds, owned)
            _write(out, "FAIL setup\n")
            return 1
        end, printed = time.monotonic() + deadline, True
        for n, (request, judge) in enumerate(checks, 1):
            passed = False
            if n <= 4 or results[:4] == [True] * 4:  # the real key only after four PASS lines written in full
                try:
                    raw = _exchange(address, request, flag, end)
                except Exception:
                    raw = None
                if raw is not None and not flag[0] and time.monotonic() < end:  # decided as it returned
                    try:
                        answer = parse(raw)
                        passed = answer is not None and judge(answer) is True
                    except Exception:
                        pass
            printed = printed and _write(out, "V%d %s\n" % (n, "PASS" if passed else "FAIL"))
            results.append(passed and printed)
        passed = _cleanup(fds, owned) and not flag[0]  # the flag is read once: a later Ctrl-C keeps this line
        printed = printed and _write(out, "V9 %s\n" % ("PASS" if passed else "FAIL"))
        results.append(passed and printed)
    finally:
        _cleanup(fds, owned)  # nothing left to do unless an error skipped the cleanup above
        signal.signal(signal.SIGINT, previous)
    return 0 if len(results) == 9 and all(results) and not flag[0] else 1  # decided after the restore


class _Parser(argparse.ArgumentParser):
    def error(self, message):  # argparse's own message echoes the value
        raise ValueError()


def _arguments(argv):
    parser = _Parser(prog="verify.py", add_help=False)  # no help path: only the fixed lines
    parser.add_argument("--port", type=int, required=True)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--home")
    args = parser.parse_args(argv)
    if not 1 <= args.port <= 65535 or not ipaddress.ip_address(args.host).is_loopback:
        raise ValueError()
    home = args.home
    if home is None:
        home = os.path.dirname(os.path.dirname(os.path.dirname(os.path.abspath(__file__))))
    return args.host, args.port, os.path.abspath(home)


class _Seen:
    """The output, remembering whether anything was written to it."""

    def __init__(self, out):
        self.out, self.seen = out, False

    def write(self, text):
        self.seen = True
        return self.out.write(text)


def main(argv=None, out=None, deadline=DEADLINE_S):
    out = _Seen(sys.stdout if out is None else out)
    try:
        try:
            host, port, home = _arguments(argv)
        except (ValueError, OSError):  # a refused value, or a path that cannot be resolved
            _write(out, "FAIL arguments\n")
            return 2
        try:
            with warnings.catch_warnings():
                warnings.simplefilter("error", getpass.GetPassWarning)  # never the echoed stdin fallback
                key = getpass.getpass("API key: ")
        except BaseException:  # no terminal, end of input or Ctrl-C
            key = ""
        if not key_ok(key):
            _write(out, "FAIL key\n")
            return 2
        return run((host, port), home, key, out, deadline)
    except BaseException:  # Ctrl-C outside run's handler window: nothing is owned there
        if not out.seen:
            _write(out, "FAIL setup\n")
        return 1


if __name__ == "__main__":
    sys.exit(main())
