"""Pure logic of the apofis-media plugin: the bounded body parse, the fence, the bounded read and the
fixed error bodies. Standard library only, no web framework, no Hermes import. The core never logs and
never puts input or exception text into an answer.
"""
import json
import os
import stat
import string

PROTOCOL = 1
MAX_BYTES = 10485760
TYPES = ("png", "jpeg", "gif", "webp")

BODY_LIMIT = 8192
PATH_MIN, PATH_MAX = 2, 1024
SUFFIXES = (".png", ".jpg", ".jpeg", ".gif", ".webp")
ROOTS = (("cache", "generated", "images"), ("cache", "images"))
SIGNATURES = ((b"\x89PNG\r\n\x1a\n", "image/png"), (b"\xff\xd8\xff", "image/jpeg"),
              (b"GIF87a", "image/gif"), (b"GIF89a", "image/gif"))

_FOLD = str.maketrans(string.ascii_uppercase, string.ascii_lowercase)
_ROOT_FLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_CLOEXEC
_DIR_FLAGS = os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW | os.O_CLOEXEC
_LEAF_FLAGS = os.O_RDONLY | os.O_NOFOLLOW | os.O_NONBLOCK | os.O_CLOEXEC

_ERRORS = {
    "bad_request": (400, "The request is not valid."),
    "file_unavailable": (404, "The file is not available."),
    "too_large": (413, "The file is too large."),
    "not_a_picture": (415, "The file is not a supported picture."),
    "internal": (500, "The plugin hit an internal error."),
    "unavailable": (503, "The plugin is not available right now."),
    "busy": (503, "The plugin is busy. Try again soon."),
    "hermes_incompatible": (503, "This Hermes version does not fit the plugin."),
}


class BodyRefused(Exception):
    """A refused request body. Carries only a status and a code, never input."""

    def __init__(self, status, code):
        super().__init__(status, code)
        self.status, self.code = status, code


def _fold(s):
    """ASCII-only lower case: only A to Z fold."""
    return s.translate(_FOLD)


def _picture_name(name):
    return _fold(name).endswith(SUFFIXES)


def _json_content_type(value):
    """application/json, alone or with exactly one charset parameter whose value is utf-8."""
    if value is None:
        return False
    parts = value.split(";")
    if _fold(parts[0].strip(" \t")) != "application/json" or len(parts) > 2:
        return False
    if len(parts) == 1:
        return True
    name, eq, charset = parts[1].strip(" \t").partition("=")
    if len(charset) >= 2 and charset[0] == charset[-1] == '"':
        charset = charset[1:-1]
    return eq == "=" and _fold(name) == "charset" and _fold(charset) == "utf-8"


def _content_length(value):
    """The value of an all-ASCII-digit header, or None. int() alone would take '+5', ' 5' and '5_0'."""
    if not value or not value.isascii() or not value.isdigit():
        return None
    digits = value.lstrip("0")
    return int(digits or "0") if len(digits) <= len(str(BODY_LIMIT)) else None


def _unique(pairs):
    obj = {}
    for key, value in pairs:
        if key in obj:
            raise ValueError("duplicate key")
        obj[key] = value
    return obj


def _decode(raw):
    """The parsed JSON value, or None for anything that is not strict UTF-8 JSON without duplicate keys."""
    try:
        return json.loads(raw.decode("utf-8"), object_pairs_hook=_unique)
    except (ValueError, RecursionError):
        return None


def _valid_path(path):
    if not isinstance(path, str) or not PATH_MIN <= len(path) <= PATH_MAX or path[0] != "/":
        return False
    if any(ord(c) < 0x20 or 0x7F <= ord(c) <= 0x9F for c in path):
        return False
    try:
        path.encode("utf-8")
    except UnicodeEncodeError:
        return False
    return True


async def parse_body(header, read):
    """The requested path from a file request, or BodyRefused. Reads at most BODY_LIMIT + 1 bytes."""
    encoding = header("Content-Encoding")
    length = _content_length(header("Content-Length"))
    if ((encoding is not None and _fold(encoding) != "identity")
            or not _json_content_type(header("Content-Type"))
            or length is None or length > BODY_LIMIT):
        raise BodyRefused(400, "bad_request")
    raw = bytearray()
    while len(raw) <= BODY_LIMIT:
        chunk = await read(BODY_LIMIT + 1 - len(raw))
        if not chunk:
            break
        raw += chunk
    if len(raw) != length:
        raise BodyRefused(400, "bad_request")
    # Refusals are raised outside every except block, so no exception context carries the input along.
    obj = _decode(bytes(raw))
    if not isinstance(obj, dict) or list(obj) != ["path"] or not _valid_path(obj["path"]):
        raise BodyRefused(400, "bad_request")
    path = obj["path"]
    if not _picture_name(path.rsplit("/", 1)[-1]):
        raise BodyRefused(415, "not_a_picture")
    return path


def _refusal(code):
    return (_ERRORS[code][0], code, None, None)


def _open(name, flags, dir_fd=None):
    try:
        return os.open(name, flags, dir_fd=dir_fd)
    except OSError:
        return None


def _sniff(data):
    for signature, content_type in SIGNATURES:
        if data.startswith(signature):
            return content_type
    if data[:4] == b"RIFF" and data[8:12] == b"WEBP":
        return "image/webp"
    return None


def _walk_and_read(real, rest, cap, own):
    """Open real, then each name of rest by directory descriptor, and read the leaf.

    own holds the descriptors this worker still owns: own[0] the current directory, then the leaf.
    A number leaves own before os.close is called on it, so it is never closed twice.
    """
    fd = _open(real, _ROOT_FLAGS)
    if fd is None:
        return _refusal("file_unavailable")
    own.append(fd)
    for name in rest[:-1]:
        fd = _open(name, _DIR_FLAGS, own[0])
        if fd is None:
            return _refusal("file_unavailable")
        old, own[0] = own[0], fd
        os.close(old)
    fd = _open(rest[-1], _LEAF_FLAGS, own[0])
    if fd is None:
        return _refusal("file_unavailable")
    own.append(fd)
    st = os.fstat(fd)
    if not stat.S_ISREG(st.st_mode):
        return _refusal("file_unavailable")
    if st.st_size > cap:
        return _refusal("too_large")
    data = bytearray()
    while len(data) <= cap:
        chunk = os.read(fd, cap + 1 - len(data))
        if not chunk:
            break
        data += chunk
    if len(data) > cap:
        return _refusal("too_large")
    content_type = _sniff(data)
    if content_type is None:
        return _refusal("not_a_picture")
    return (200, None, content_type, bytes(data))


def serve(path, home, validate, cap):
    """Serve the picture validate(path) names, if it lies beneath one of the two real picture folders of
    home. Never raises: (200, None, content_type, data) or (status, code, None, None)."""
    try:
        v = validate(path)
    except (AttributeError, TypeError):
        return _refusal("hermes_incompatible")
    except Exception:
        return _refusal("internal")
    if v is None:
        return _refusal("file_unavailable")
    if not isinstance(v, str):
        return _refusal("hermes_incompatible")
    # Raw components, before any normalization: pathlib would silently drop "." and repeated slashes.
    parts = v.split("/")
    names = parts[1:]
    if parts[0] != "" or not names or any(p in ("", ".", "..") for p in names) or not _picture_name(names[-1]):
        return _refusal("file_unavailable")
    own = []
    try:
        real = os.path.realpath(home)
        home_names = [p for p in real.split("/") if p]
        rest = names[len(home_names):]
        if names[:len(home_names)] != home_names or not any(
                tuple(rest[:len(root)]) == root and len(rest) > len(root) for root in ROOTS):
            return _refusal("file_unavailable")
        result = _walk_and_read(real, rest, cap, own)
    except Exception:
        result = _refusal("internal")
    while own:
        fd = own.pop()
        try:
            os.close(fd)
        except Exception:
            result = _refusal("internal")
    return result


def error_body(status, code):
    """The fixed error body for a code of the protocol. An unknown code or a wrong status raises."""
    if _ERRORS.get(code, (None,))[0] != status:
        raise ValueError("unknown error code")
    body = {"error": {"code": code, "message": _ERRORS[code][1]}}
    return json.dumps(body, separators=(",", ":")).encode("utf-8")
