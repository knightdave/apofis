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


class Deadline(Exception):
    pass


def _alarm(signum, frame):
    raise Deadline()


class HttpTransport:
    """One connection per request: no proxy, no redirect, TIMEOUT_S per socket operation. Returns the
    http.client response; the caller reads its body through its own bound and closes it."""

    def __init__(self, host, port):
        self.host, self.port = host, port

    def __call__(self, method, path, headers, body):
        conn = http.client.HTTPConnection(self.host, self.port, timeout=TIMEOUT_S)
        try:
            conn.request(method, path, body=body, headers=dict(headers, Connection="close"))
            return conn.getresponse()  # the response keeps the socket until it is closed
        except BaseException:
            conn.close()
            raise


def _read(resp):
    """Asks the body for at most BODY_LIMIT + 1 bytes in total. None when there are more than BODY_LIMIT."""
    data = b""
    while len(data) <= BODY_LIMIT:
        chunk = resp.read(BODY_LIMIT + 1 - len(data))
        if not chunk:
            return data
        data += chunk
    return None


def _exchange(transport, method, path, headers, body):
    resp = transport(method, path, headers, body)
    try:
        return resp.status, resp.getheader("Content-Type"), _read(resp)
    finally:
        resp.close()


def _error_code(answer, status):
    got, _, data = answer
    if got != status or data is None:
        return None
    obj = json.loads(data)
    err = obj.get("error") if isinstance(obj, dict) else None
    return err.get("code") if isinstance(err, dict) else None


def _ping_ready(answer):
    got, _, data = answer
    if got != 200 or data is None:
        return False
    obj = json.loads(data)
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


def _network(checks, emit):
    """Runs V1 to V8 under the deadline and prints each. Returns True when all eight pass."""
    results = []

    def line(passed):
        results.append(passed)
        emit(passed)

    old = signal.signal(signal.SIGALRM, _alarm)
    try:
        signal.setitimer(signal.ITIMER_REAL, DEADLINE_S)
        expired = False
        for n, check in enumerate(checks, 1):
            passed = False
            # The real key goes out only after V1 to V4 passed; nothing goes out after the deadline.
            if not expired and (n <= 4 or all(results[:4])):
                try:
                    passed = check() is True
                except Deadline:
                    expired = True
                except Exception:
                    pass
            line(passed)
    except Deadline:  # fired between two lines: the lines not yet printed fail
        while len(results) < 8:
            line(False)
    finally:
        signal.setitimer(signal.ITIMER_REAL, 0)
        signal.signal(signal.SIGALRM, old)
    return all(results)


def _setup(home, fds, owned):
    """Opens home/cache/images without following links and creates the two fixtures in it. Every
    descriptor goes to `fds` and each fixture to `owned` as soon as it exists. Returns the three paths."""
    def opened(fd):
        fds.append(fd)
        return fd
    d = opened(os.open(home, DIR_FLAGS))
    d = opened(os.open("cache", DIR_FLAGS | os.O_NOFOLLOW, dir_fd=d))
    d = opened(os.open("images", DIR_FLAGS | os.O_NOFOLLOW, dir_fd=d))
    names = ("apofis-verify-%s.png" % secrets.token_hex(16),
             "apofis-verify-%s-text.png" % secrets.token_hex(16),
             "apofis-verify-%s.png" % secrets.token_hex(16))  # the third is never created
    for name, data in zip(names, (PNG, TEXT)):
        fd = opened(os.open(name, CREATE_FLAGS, 0o600, dir_fd=d))
        owned.append((d, name, fd))  # owned before the first byte is written
        while data:
            data = data[os.write(fd, data):]
    folder = os.path.join(home, "cache", "images")
    return [os.path.join(folder, name) for name in names]


def _cleanup(fds, owned):
    """Unlinks each owned fixture only while its name still points at the file this script created,
    then closes every descriptor once. Safe to call again. True when both fixtures were removed."""
    removed = 0
    while owned:
        d, name, fd = owned.pop(0)
        try:
            entry, mine = os.lstat(name, dir_fd=d), os.fstat(fd)
            if (entry.st_dev, entry.st_ino) == (mine.st_dev, mine.st_ino):
                os.unlink(name, dir_fd=d)
                removed += 1
        except Exception:
            pass
    while fds:
        try:
            os.close(fds.pop())
        except Exception:
            pass
    return removed == 2


def run(transport, home, key, out):
    """The checks of spec section 4 against `transport`. Returns the exit code."""
    fds, owned, printed = [], [], []

    def emit(passed):
        printed.append(passed)
        out.write("V%d %s\n" % (len(printed), "PASS" if passed else "FAIL"))

    try:
        png, text, absent = _setup(home, fds, owned)
    except BaseException:
        out.write("FAIL setup\n")
        _cleanup(fds, owned)
        return 1
    try:
        ok = _network(_checks(transport, key, png, text, absent), emit)
        removed = _cleanup(fds, owned)
        emit(removed)
        return 0 if ok and removed else 1
    except BaseException:  # an interruption: every line not yet printed fails, V9 included
        while len(printed) < 9:
            emit(False)
        return 1
    finally:
        _cleanup(fds, owned)  # nothing left to do unless an interruption skipped it


class _Parser(argparse.ArgumentParser):
    def error(self, message):  # argparse's own message echoes the value
        raise ValueError()


def _arguments(argv):
    parser = _Parser(prog="verify.py", description="Check the apofis-media add-on against this listener.")
    parser.add_argument("--port", type=int, required=True, help="this profile's API server port")
    parser.add_argument("--host", default="127.0.0.1", help="a loopback IP address (default 127.0.0.1)")
    parser.add_argument("--home", help="the Hermes home (default: two levels above this file's folder)")
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
    except ValueError:
        out.write("FAIL arguments\n")
        return 2
    try:
        with warnings.catch_warnings():
            warnings.simplefilter("error", getpass.GetPassWarning)  # never the echoed stdin fallback
            key = getpass.getpass("API key: ")
    except BaseException:  # no terminal, end of input or Ctrl-C
        out.write("FAIL key\n")
        return 2
    try:
        return run(HttpTransport(host, port), home, key, out)
    except BaseException:  # run has printed its lines; never print exception text
        return 1


if __name__ == "__main__":
    sys.exit(main())
