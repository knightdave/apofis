"""The owner's check of the apofis-media add-on against this profile's own API server listener.

Run once on the Hermes host, as the gateway's account, after install and restart:

    python3 -I <Location>/verify.py --port N [--host IP] [--home DIR]

Type the API server key at "API key:". The script creates two fixture files in <home>/cache/images,
sends eight requests to the listener and removes exactly those two files. It prints only the lines
V1 to V9 PASS or FAIL (or one FAIL line for a refused argument, key or setup) and exits 0 only when
all nine pass. Never imported by the plugin. Standard library only.
"""
import argparse
import contextlib
import getpass
import http.client
import ipaddress
import json
import os
import secrets
import signal
import sys
import warnings

DEADLINE_S = 60  # one wall-clock deadline for the whole network phase
TIMEOUT_S = 10  # per socket operation
BODY_LIMIT = 64 * 1024  # ping, error and file bodies alike
PING = "/apofis-media/v1/ping"
FILE = "/apofis-media/v1/file"
AUTH_FAILED = "gateway_auth_failed"
PNG = bytes.fromhex(  # a valid 1 by 1 grey PNG
    "89504e470d0a1a0a0000000d49484452000000010000000108000000003a7e9b55"
    "0000000a49444154789c636000000002000148afa4710000000049454e44ae426082")
TEXT = b"apofis-media verifier fixture: plain text, not a picture\n"
DIR_FLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC
CREATE_FLAGS = os.O_WRONLY | os.O_CREAT | os.O_EXCL | os.O_NOFOLLOW | os.O_CLOEXEC


class Deadline(BaseException):  # not an Exception: no library "except Exception" may swallow it
    pass


@contextlib.contextmanager
def _masked():
    """Holds SIGINT and SIGALRM for the block; a signal that arrives meanwhile is delivered when it ends."""
    old = signal.pthread_sigmask(signal.SIG_BLOCK, ())  # reads the mask, changes nothing
    try:
        signal.pthread_sigmask(signal.SIG_BLOCK, (signal.SIGINT, signal.SIGALRM))
        yield
    finally:
        signal.pthread_sigmask(signal.SIG_SETMASK, old)


class _Lines:
    """The V lines committed so far: a line counts only once it is written."""

    def __init__(self, out):
        self.out, self.passed = out, []

    def commit(self, passed):
        with _masked():
            self.out.write("V%d %s\n" % (len(self.passed) + 1, "PASS" if passed else "FAIL"))
            self.passed.append(passed)

    def fill(self, upto):
        while len(self.passed) < upto:
            self.commit(False)


class _Answer:
    """An http.client response that owns its connection until it is closed."""

    def __init__(self, conn, resp):
        self.conn, self.resp, self.status = conn, resp, resp.status

    def getheader(self, name):
        return self.resp.getheader(name)

    def read(self, n):
        return self.resp.read(n)

    def close(self):
        try:
            self.resp.close()
        finally:
            self.conn.close()


class HttpTransport:
    """One connection per request: no proxy, no redirect, TIMEOUT_S per socket operation. The answer's
    read(n) returns up to n bytes, fewer only at the end of the body; close() releases the connection."""

    def __init__(self, host, port):
        self.host, self.port = host, port

    def __call__(self, method, path, headers, body):
        conn = http.client.HTTPConnection(self.host, self.port, timeout=TIMEOUT_S)
        try:
            conn.request(method, path, body=body, headers=dict(headers, Connection="close"))
            return _Answer(conn, conn.getresponse())
        except BaseException:
            conn.close()
            raise


def _read(resp):
    """The whole body, or None when it is over BODY_LIMIT or shorter than its announced length. One read
    of at most BODY_LIMIT + 1 bytes; an announced length over the limit is refused before any read."""
    length = resp.getheader("Content-Length")
    if length is not None and int(length) > BODY_LIMIT:
        return None
    data = resp.read(BODY_LIMIT + 1)
    if len(data) > BODY_LIMIT or (length is not None and len(data) != int(length)):
        return None
    return data


def _exchange(transport, method, path, headers, body):
    resp = transport(method, path, headers, body)
    try:
        answer = resp.status, resp.getheader("Content-Type"), _read(resp)
    except BaseException:
        try:
            resp.close()
        except Exception:  # a close error never replaces a deadline or an interruption
            pass
        raise
    resp.close()
    return answer


def _no_constant(name):
    raise ValueError("not JSON")


def _json(data):
    return json.loads(data, parse_constant=_no_constant)  # NaN and the infinities are not JSON


def _error_code(answer, status):
    got, _, data = answer
    if got != status or data is None:
        return None
    obj = _json(data)
    err = obj.get("error") if isinstance(obj, dict) else None
    return err.get("code") if isinstance(err, dict) else None


def _ping_ready(answer):
    got, _, data = answer
    if got != 200 or data is None:
        return False
    obj = _json(data)
    if not isinstance(obj, dict):
        return False
    types = obj.get("types")
    return (obj.get("name") == "apofis-media"
            and type(obj.get("protocol")) is int and obj["protocol"] == 1
            and obj.get("ready") is True
            and type(obj.get("max_bytes")) is int and obj["max_bytes"] > 0
            and type(types) is list and all(type(t) is str for t in types) and "png" in types)


def _png_whole(answer):
    got, ctype, data = answer
    return got == 200 and ctype == "image/png" and data == PNG


def _checks(transport, key, png, text, absent):
    """V1 to V8 as callables returning True for PASS."""
    wrong = secrets.token_hex(32)
    while wrong == key:
        wrong = secrets.token_hex(32)

    def ask(k, path=None):  # path None: the ping; else the file route for that path
        headers = {} if k is None else {"Authorization": "Bearer " + k}
        if path is None:
            return _exchange(transport, "GET", PING, headers, None)
        headers["Content-Type"] = "application/json"
        return _exchange(transport, "POST", FILE, headers, json.dumps({"path": path}).encode())

    return (
        lambda: _error_code(ask(None), 401) == AUTH_FAILED,
        lambda: _error_code(ask(wrong), 401) == AUTH_FAILED,
        lambda: _error_code(ask(None, png), 401) == AUTH_FAILED,
        lambda: _error_code(ask(wrong, png), 401) == AUTH_FAILED,
        lambda: _ping_ready(ask(key)),
        lambda: _png_whole(ask(key, png)),
        lambda: _error_code(ask(key, text), 415) == "not_a_picture",
        lambda: _error_code(ask(key, absent), 404) == "file_unavailable",
    )


def _network(checks, lines):
    """Runs V1 to V8 under the deadline and commits each line."""
    expired = []

    def alarm(signum, frame):
        expired.append(True)  # latched first: whatever swallows the raise, no later request goes out
        raise Deadline()

    old = signal.signal(signal.SIGALRM, alarm)
    try:
        signal.setitimer(signal.ITIMER_REAL, DEADLINE_S)
        for n, check in enumerate(checks, 1):
            passed = False
            # The real key goes out only after V1 to V4 passed; nothing goes out after the deadline.
            if not expired and (n <= 4 or lines.passed[:4] == [True] * 4):
                try:
                    passed = check() is True
                except (Deadline, Exception):
                    pass
            lines.commit(passed and not expired)
    except Deadline:  # fired outside a check: the lines not yet written fail
        lines.fill(8)
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, old)


def _setup(home, fds, owned):
    """Opens home/cache/images without following links and creates the two fixtures in it. Every
    descriptor is in `fds` and each fixture in `owned` from the moment it exists. Returns three paths."""
    def opened(*args, **kwargs):
        with _masked():
            fd = os.open(*args, **kwargs)
            fds.append(fd)
        return fd
    d = opened(home, DIR_FLAGS)
    d = opened("cache", DIR_FLAGS | os.O_NOFOLLOW, dir_fd=d)
    d = opened("images", DIR_FLAGS | os.O_NOFOLLOW, dir_fd=d)
    names = ("apofis-verify-%s.png" % secrets.token_hex(16),
             "apofis-verify-%s-text.png" % secrets.token_hex(16),
             "apofis-verify-%s.png" % secrets.token_hex(16))  # the third is never created
    for name, data in zip(names, (PNG, TEXT)):
        with _masked():  # created and owned in one step
            fd = opened(name, CREATE_FLAGS, 0o600, dir_fd=d)
            owned.append((d, name, fd))
        while data:
            data = data[os.write(fd, data):]
    try:
        os.lstat(names[2], dir_fd=d)  # V8's name must be missing; lstat neither follows nor opens it
    except FileNotFoundError:
        pass
    else:
        raise FileExistsError()
    folder = os.path.join(home, "cache", "images")
    return [os.path.join(folder, name) for name in names]


def _cleanup(fds, owned):
    """Unlinks each owned fixture only while its name still points at the file this script created, and
    drops the entry only after that; then closes every descriptor once. True when both fixtures were
    removed and every close succeeded."""
    removed, closed = 0, True
    while owned:
        d, name, fd = owned[0]
        try:
            entry, mine = os.lstat(name, dir_fd=d), os.fstat(fd)
            if (entry.st_dev, entry.st_ino) == (mine.st_dev, mine.st_ino):
                os.unlink(name, dir_fd=d)
                removed += 1
        except Exception:
            pass
        del owned[0]
    while fds:
        fd = fds.pop()  # dropped first: a number is never closed twice
        try:
            os.close(fd)
        except Exception:
            closed = False
    return removed == 2 and closed


def run(transport, home, key, out):
    """The checks of spec section 4 against `transport`. Returns the exit code."""
    fds, owned, lines = [], [], _Lines(out)
    try:
        try:
            paths = _setup(home, fds, owned)
        except BaseException:
            paths = None
        if paths is None:
            with _masked():
                out.write("FAIL setup\n")
            return 1
        try:
            _network(_checks(transport, key, *paths), lines)
            with _masked():  # V9 is the real result; an interruption meanwhile arrives after it is written
                lines.commit(_cleanup(fds, owned))
        except BaseException:  # an interruption: every line not yet written fails, V9 included
            lines.fill(9)
            return 1
        return 0 if lines.passed == [True] * 9 else 1
    finally:
        # Whatever an interruption left. Bounded: only an error raised directly in a step gets out of the mask.
        for _ in range(3):
            if not (owned or fds):
                break
            try:
                with _masked():
                    _cleanup(fds, owned)
            except BaseException:
                pass


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


def main(argv=None, out=None):
    out = sys.stdout if out is None else out
    try:
        host, port, home = _arguments(argv)
    except (ValueError, OSError):  # a refused value, or a path that cannot be resolved
        out.write("FAIL arguments\n")
        return 2
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", getpass.GetPassWarning)  # never the echoed stdin fallback
            key = getpass.getpass("API key: ")
    except BaseException:  # no terminal, end of input or Ctrl-C
        key = ""
    if not (key and key.isascii() and key.isprintable()):  # one line of printable ASCII, safe as a header
        out.write("FAIL key\n")
        return 2
    try:
        transport = HttpTransport(host, port)
    except Exception:
        out.write("FAIL setup\n")
        return 1
    try:
        return run(transport, home, key, out)
    except BaseException:  # a second interruption or a failing output: run wrote what it could
        return 1


if __name__ == "__main__":
    sys.exit(main())
