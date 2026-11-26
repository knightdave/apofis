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
import queue
import re
import secrets
import signal
import socket
import sys
import threading
import time
import warnings

DEADLINE_S = 60  # one wall-clock deadline for the whole network phase, on the monotonic clock
TIMEOUT_S = 10  # per socket operation
BODY_LIMIT = 64 * 1024  # ping, error and file bodies alike
HEAD_LIMIT = 8 * 1024  # status line and header lines of one answer
ANSWER_LIMIT = HEAD_LIMIT + 4 + BODY_LIMIT  # the transport reads at most this plus one byte
PING = "/apofis-media/v1/ping"
FILE = "/apofis-media/v1/file"
AUTH_FAILED = "gateway_auth_failed"
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


class SocketTransport:
    """One TCP connection per request to a numeric loopback address: no proxy, no redirect, TIMEOUT_S per
    socket operation. Returns the raw answer, read until the peer closes or ANSWER_LIMIT + 1 bytes are in.
    close() aborts the request in flight."""

    def __init__(self, host, port):
        self.host, self.port, self.sock = host, port, None

    def __call__(self, request):
        sock = socket.create_connection((self.host, self.port), timeout=TIMEOUT_S)
        self.sock = sock
        try:
            sock.sendall(request)
            data = b""
            while len(data) <= ANSWER_LIMIT:
                chunk = sock.recv(ANSWER_LIMIT + 1 - len(data))
                if not chunk:
                    break
                data += chunk
            return data
        finally:
            self.sock = None
            sock.close()

    def close(self):
        try:
            self.sock.shutdown(socket.SHUT_RDWR)  # wakes the worker's recv; the worker closes the socket
        except (AttributeError, OSError):  # no request in flight, or it just ended
            pass


def _request(method, path, key=None, body=None):
    lines = ["%s %s HTTP/1.1" % (method, path), "Host: localhost", "Connection: close"]
    if body is not None:
        lines += ["Content-Type: application/json", "Content-Length: %d" % len(body)]
    if key is not None:
        lines.append("Authorization: Bearer " + key)
    return ("\r\n".join(lines) + "\r\n\r\n").encode("ascii") + (body or b"")


def parse(raw):
    """(status, content type, body) of one strictly framed answer, else None: a status line, header lines,
    exactly one Content-Length of ASCII digits, no Transfer-Encoding, the body exactly that long."""
    head, sep, body = raw.partition(b"\r\n\r\n")
    if not sep or len(head) > HEAD_LIMIT:
        return None
    lines = head.split(b"\r\n")
    status = STATUS_LINE.fullmatch(lines[0])
    if not status:
        return None
    headers = {}
    for line in lines[1:]:
        name, colon, value = line.partition(b":")
        if not colon or not HEADER_NAME.fullmatch(name) or not HEADER_VALUE.fullmatch(value):
            return None
        headers.setdefault(name.lower(), []).append(value.strip(b" \t"))
    lengths = headers.get(b"content-length", [])
    if b"transfer-encoding" in headers or len(lengths) != 1 or not lengths[0].isdigit():
        return None
    if int(lengths[0]) > BODY_LIMIT or len(body) != int(lengths[0]):
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
        return _error_code(a, 401) == AUTH_FAILED

    return ((_request("GET", PING), refused), (_request("GET", PING, wrong), refused),
            (file(None, png), refused), (file(wrong, png), refused),
            (_request("GET", PING, key), _ping_ready),
            (file(key, png), lambda a: a == (200, "image/png", PNG)),
            (file(key, text), lambda a: _error_code(a, 415) == "not_a_picture"),
            (file(key, absent), lambda a: _error_code(a, 404) == "file_unavailable"))


def _worker(transport, checks, verdicts, stop):
    """Runs the checks in order and queues each verdict. The real key goes out only after V1 to V4
    passed, and nothing goes out once `stop` is set."""
    results = []
    for n, (request, judge) in enumerate(checks, 1):
        passed = False
        if not stop.is_set() and (n <= 4 or results[:4] == [True] * 4):
            try:
                answer = parse(transport(request))
                passed = answer is not None and judge(answer) is True
            except Exception:
                pass
        results.append(passed)
        verdicts.put(passed)


def _next(verdicts, interrupted, end):
    """The next verdict, or None at the deadline or once interrupted."""
    while not interrupted.is_set() and time.monotonic() < end:
        try:
            return verdicts.get(timeout=max(0.0, min(end - time.monotonic(), 0.05)))
        except queue.Empty:
            pass
    return None


def _write(out, text):
    """True when the whole text was accepted. A writer may accept less; None means it took all."""
    while text:
        try:
            n = out.write(text)
        except Exception:
            return False
        if n is None:
            return True
        if n <= 0:
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
    return removed == 2 and closed


def run(transport, home, key, out, deadline=DEADLINE_S):
    """The checks of spec section 4 through `transport(request_bytes) -> answer_bytes`; returns the exit
    code. Nothing asynchronous can raise while it owns a file: Ctrl-C only sets a flag (the previous
    handler is restored at the end), the requests go out from one worker thread, and this thread prints,
    keeps the deadline and cleans up."""
    if not key_ok(key):
        _write(out, "FAIL key\n")
        return 2
    interrupted, stop, verdicts = threading.Event(), threading.Event(), queue.Queue()
    try:
        old = signal.signal(signal.SIGINT, lambda signum, frame: interrupted.set())
    except ValueError:  # not the main thread: go on without the handler
        old = None
    fds, owned, results, printed = [], [], [], True
    try:
        try:
            paths = _setup(home, fds, owned)
            threading.Thread(target=_worker, args=(transport, _checks(key, *paths), verdicts, stop),
                             daemon=True).start()
        except Exception:
            _cleanup(fds, owned)
            _write(out, "FAIL setup\n")
            return 1
        end = time.monotonic() + deadline
        for n in range(1, 10):
            if n < 9:
                passed = _next(verdicts, interrupted, end) if printed and not stop.is_set() else None
                if passed is None and not stop.is_set():  # the deadline, an interruption or a failed write
                    stop.set()
                    try:
                        getattr(transport, "close", lambda: None)()  # abort the request in flight
                    except Exception:
                        pass
            else:
                passed = _cleanup(fds, owned) and not interrupted.is_set()
            results.append(passed is True)
            printed = printed and _write(out, "V%d %s\n" % (n, "PASS" if passed is True else "FAIL"))
        return 0 if printed and results == [True] * 9 and not interrupted.is_set() else 1
    finally:
        if old is not None:
            signal.signal(signal.SIGINT, old)


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


def main(argv=None, out=None, deadline=DEADLINE_S):
    out = sys.stdout if out is None else out
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
    try:
        transport = SocketTransport(host, port)
    except BaseException:
        _write(out, "FAIL setup\n")
        return 1
    try:
        return run(transport, home, key, out, deadline)
    except BaseException:  # Ctrl-C just before run's handler is installed or after it is restored
        return 1


if __name__ == "__main__":
    sys.exit(main())
