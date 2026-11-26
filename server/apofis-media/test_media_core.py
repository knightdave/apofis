"""Offline tests for media_core: real temporary folders, fake validators, no network of any kind."""
import asyncio
import json
import os
import pathlib
import re
import shutil
import socket
import tempfile
import threading
import types
import unittest
from unittest import mock

import media_core
from media_core import BodyRefused

# --- egress guard -----------------------------------------------------------------------------------
# Shape of server/apofis-push/test_apofis_push.py, without its loopback exception and with bind added:
# these tests open no socket at all, so every call is a violation.

VIOLATIONS: list = []
_REAL = {}
_GUARDED = ("connect", "connect_ex", "bind")


def setUpModule():
    """Deny every connect, bind and name lookup in this process, loopback included; a violation is
    recorded and fails the suite in tearDownModule."""
    for name in _GUARDED:
        _REAL[name] = getattr(socket.socket, name)
    _REAL["getaddrinfo"] = socket.getaddrinfo

    def deny(kind):
        def blocked(*a, **k):
            VIOLATIONS.append(kind)
            raise PermissionError("network blocked by test guard: %s" % kind)
        return blocked
    for name in _GUARDED:
        setattr(socket.socket, name, deny(name))
    socket.getaddrinfo = deny("getaddrinfo")


def tearDownModule():
    for name in _GUARDED:
        setattr(socket.socket, name, _REAL[name])
    socket.getaddrinfo = _REAL["getaddrinfo"]
    if VIOLATIONS:
        raise AssertionError("network use attempted: %d" % len(VIOLATIONS))


# --- fixtures ---------------------------------------------------------------------------------------

CAP = media_core.MAX_BYTES
PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 8
JPEG = b"\xff\xd8\xff\xe0" + b"\x00" * 8
GIF7 = b"GIF87a" + b"\x00" * 8
GIF9 = b"GIF89a" + b"\x00" * 8
WEBP = b"RIFF" + b"\x10\x00\x00\x00" + b"WEBP" + b"\x00" * 8
NOT_FOUND = (404, "file_unavailable", None, None)
INTERNAL = (500, "internal", None, None)
INCOMPATIBLE = (503, "hermes_incompatible", None, None)


def check_shape(result):
    """Every serve answer: a 4-tuple, code and data never both set, a refusal has no content type."""
    assert isinstance(result, tuple) and len(result) == 4, result[:2]
    status, code, ctype, data = result
    assert not (code is not None and data is not None), status
    if status == 200:
        assert code is None and isinstance(data, bytes) and ctype in (
            "image/png", "image/jpeg", "image/gif", "image/webp"), status
    else:
        assert isinstance(code, str) and ctype is None and data is None, status
    return result


def serve(path, home, validate, cap=CAP):
    return check_shape(media_core.serve(path, home, validate, cap))


def resolving(p):
    """What Hermes's validator returns for a plain host path: the resolved name of a regular file."""
    r = os.path.realpath(p)
    return r if os.path.isfile(r) else None


def returns(value):
    return lambda p: value


def write(path, data):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "wb") as f:
        f.write(data)
    return path


class TempCase(unittest.TestCase):
    def tmpdir(self):
        d = os.path.realpath(tempfile.mkdtemp(prefix="apofis-media-test-"))
        self.addCleanup(shutil.rmtree, d)
        return d

    def make_home(self, generated=True):
        home = self.tmpdir()
        os.makedirs(os.path.join(home, "cache", "images"))
        if generated:
            os.makedirs(os.path.join(home, "cache", "generated", "images"))
        return home


class FdTracker:
    """Wraps os.open and os.close for one serve call. fail_at: index of the open call that raises.

    A number may be handed out again after its close, so ownership is tracked per open: live holds the
    numbers opened and not yet closed; a close of a number that is not live is a stray close."""

    def __init__(self, fail_at=None, on_close=None):
        self.opened, self.closed, self.fail_at, self.on_close = [], [], fail_at, on_close
        self.live, self.stray = set(), []
        self._open, self._close = os.open, os.close

    def open(self, *a, **k):
        if self.fail_at is not None and len(self.opened) == self.fail_at:
            self.fail_at = None
            raise OSError("injected")
        fd = self._open(*a, **k)
        assert fd not in self.live, fd
        self.opened.append(fd)
        self.live.add(fd)
        return fd

    def close(self, fd):
        self.closed.append(fd)
        if fd in self.live:
            self.live.remove(fd)
        else:
            self.stray.append(fd)
        if self.on_close is not None:
            hook, self.on_close = self.on_close, None
            return hook(fd)
        return self._close(fd)

    def __enter__(self):
        self._patches = [mock.patch.object(os, "open", self.open), mock.patch.object(os, "close", self.close)]
        for p in self._patches:
            p.start()
        return self

    def __exit__(self, *exc):
        for p in self._patches:
            p.stop()

    def assert_each_closed_once(self, test):
        test.assertEqual(self.stray, [], "closed a number the worker did not own")
        test.assertEqual(self.live, set(), "left a descriptor open")
        test.assertEqual(len(self.closed), len(self.opened))


# --- fence ------------------------------------------------------------------------------------------

class FenceTest(TempCase):
    def test_png_in_generated_served(self):
        home = self.make_home()
        p = write(os.path.join(home, "cache", "generated", "images", "a.png"), PNG)
        self.assertEqual(serve(p, home, resolving), (200, None, "image/png", PNG))

    def test_png_in_images_served(self):
        home = self.make_home()
        p = write(os.path.join(home, "cache", "images", "a.png"), PNG)
        self.assertEqual(serve(p, home, resolving), (200, None, "image/png", PNG))

    def test_nested_subfolder_served(self):
        home = self.make_home()
        p = write(os.path.join(home, "cache", "images", "sub", "deeper", "a.png"), PNG)
        self.assertEqual(serve(p, home, resolving), (200, None, "image/png", PNG))

    def test_beside_folders_refused(self):
        home = self.make_home()
        p = write(os.path.join(home, "a.png"), PNG)
        self.assertEqual(serve(p, home, resolving), NOT_FOUND)

    def test_in_cache_refused(self):
        home = self.make_home()
        p = write(os.path.join(home, "cache", "a.png"), PNG)
        self.assertEqual(serve(p, home, resolving), NOT_FOUND)

    def test_in_cache_generated_refused(self):
        home = self.make_home()
        p = write(os.path.join(home, "cache", "generated", "a.png"), PNG)
        self.assertEqual(serve(p, home, resolving), NOT_FOUND)

    def test_other_home_refused(self):
        home, other = self.make_home(), self.make_home()
        p = write(os.path.join(other, "cache", "images", "a.png"), PNG)
        self.assertEqual(serve(p, home, resolving), NOT_FOUND)

    def test_prefix_sibling_refused(self):
        home = self.make_home()
        p = write(os.path.join(home, "cache", "images2", "a.png"), PNG)
        self.assertEqual(serve(p, home, resolving), NOT_FOUND)
        g = write(os.path.join(home, "cache", "generated", "images2", "a.png"), PNG)
        self.assertEqual(serve(g, home, resolving), NOT_FOUND)
        sibling = home + "x"
        self.addCleanup(shutil.rmtree, sibling, True)
        s = write(os.path.join(sibling, "cache", "images", "a.png"), PNG)
        self.assertEqual(serve(s, home, resolving), NOT_FOUND)

    def test_link_out_of_folders_refused(self):
        home, outside = self.make_home(), self.tmpdir()
        target = write(os.path.join(outside, "a.png"), PNG)
        link = os.path.join(home, "cache", "images", "l.png")
        os.symlink(target, link)
        self.assertEqual(resolving(link), target)
        self.assertEqual(serve(link, home, resolving), NOT_FOUND)

    def test_symlinked_file_refused(self):
        home = self.make_home()
        write(os.path.join(home, "cache", "images", "a.png"), PNG)
        link = os.path.join(home, "cache", "images", "l.png")
        os.symlink("a.png", link)
        self.assertEqual(serve(link, home, returns(link)), NOT_FOUND)

    def test_symlink_named_png_to_text_refused(self):
        home = self.make_home()
        notes = write(os.path.join(home, "cache", "images", "notes.txt"), b"plain notes, no signature\n")
        link = os.path.join(home, "cache", "images", "chart.png")
        os.symlink("notes.txt", link)
        self.assertEqual(resolving(link), notes)
        self.assertEqual(serve(link, home, resolving), (404, "file_unavailable", None, None))

    def test_alias_into_real_folder_served(self):
        home, outside = self.make_home(), self.tmpdir()
        write(os.path.join(home, "cache", "images", "a.png"), PNG)
        alias = os.path.join(outside, "alias")
        os.symlink(os.path.join(home, "cache", "images"), alias)
        self.assertEqual(serve(os.path.join(alias, "a.png"), home, resolving), (200, None, "image/png", PNG))

    def test_linked_images_folder_elsewhere_refused(self):
        home, third = self.make_home(generated=False), self.tmpdir()
        os.rmdir(os.path.join(home, "cache", "images"))
        write(os.path.join(third, "a.png"), PNG)
        os.symlink(third, os.path.join(home, "cache", "images"))
        spelled = os.path.join(home, "cache", "images", "a.png")
        with self.subTest("resolving validator"):
            self.assertEqual(serve(spelled, home, resolving), NOT_FOUND)
        with self.subTest("validator returns the spelled name"):
            self.assertEqual(serve(spelled, home, returns(spelled)), NOT_FOUND)

    def test_linked_generated_images_to_real_images_served(self):
        home = self.make_home(generated=False)
        os.makedirs(os.path.join(home, "cache", "generated"))
        write(os.path.join(home, "cache", "images", "a.png"), PNG)
        os.symlink(os.path.join(home, "cache", "images"), os.path.join(home, "cache", "generated", "images"))
        p = os.path.join(home, "cache", "generated", "images", "a.png")
        self.assertEqual(resolving(p), os.path.join(home, "cache", "images", "a.png"))
        self.assertEqual(serve(p, home, resolving), (200, None, "image/png", PNG))

    def test_home_given_through_link_served(self):
        home, links = self.make_home(), self.tmpdir()
        p = write(os.path.join(home, "cache", "images", "a.png"), PNG)
        linked_home = os.path.join(links, "home")
        os.symlink(home, linked_home)
        self.assertEqual(serve(p, linked_home, resolving), (200, None, "image/png", PNG))
        self.assertEqual(serve(p, pathlib.Path(linked_home), resolving), (200, None, "image/png", PNG))

    def test_relative_result_refused(self):
        home = self.make_home()
        write(os.path.join(home, "cache", "images", "a.png"), PNG)
        self.assertEqual(serve("/x/a.png", home, returns("cache/images/a.png")), NOT_FOUND)
        self.assertEqual(serve("/x/a.png", home, returns(home[1:] + "/cache/images/a.png")), NOT_FOUND)

    def test_dotdot_result_refused(self):
        home = self.make_home()
        write(os.path.join(home, "cache", "images", "a.png"), PNG)
        v = home + "/cache/images/../images/a.png"
        self.assertEqual(serve("/x/a.png", home, returns(v)), NOT_FOUND)

    def test_dot_result_refused(self):
        home = self.make_home()
        write(os.path.join(home, "cache", "images", "a.png"), PNG)
        v = home + "/cache/images/./a.png"
        self.assertEqual(serve("/x/a.png", home, returns(v)), NOT_FOUND)

    def test_redundant_separator_result_refused(self):
        home = self.make_home()
        write(os.path.join(home, "cache", "images", "a.png"), PNG)
        for v in (home + "/cache//images/a.png", home + "/cache/images/a.png/"):
            with self.subTest(v=v[len(home):]):
                self.assertEqual(serve("/x/a.png", home, returns(v)), NOT_FOUND)

    def test_none_result_404(self):
        home = self.make_home()
        self.assertEqual(serve("/x/a.png", home, returns(None)), NOT_FOUND)

    def test_non_str_result_503(self):
        home = self.make_home()
        p = write(os.path.join(home, "cache", "images", "a.png"), PNG)
        self.assertEqual(serve(p, home, returns(pathlib.Path(p))), INCOMPATIBLE)
        self.assertEqual(serve(p, home, returns(p.encode())), INCOMPATIBLE)

    def test_validate_errors(self):
        home = self.make_home()
        p = write(os.path.join(home, "cache", "images", "a.png"), PNG)
        for exc, want in ((AttributeError, INCOMPATIBLE), (TypeError, INCOMPATIBLE), (ValueError, INTERNAL)):
            def raising(_p, exc=exc):
                raise exc("boom")
            with self.subTest(exc=exc.__name__):
                self.assertEqual(serve(p, home, raising), want)

    def test_missing_component_404(self):
        home = self.make_home()
        v = os.path.join(home, "cache", "images", "sub", "a.png")
        self.assertEqual(serve(v, home, returns(v)), NOT_FOUND)

    def test_directory_with_picture_name_404(self):
        home = self.make_home()
        v = os.path.join(home, "cache", "images", "x.png")
        os.mkdir(v)
        self.assertEqual(serve(v, home, returns(v)), NOT_FOUND)

    def test_fifo_refused_no_hang(self):
        home = self.make_home()
        fifo = os.path.join(home, "cache", "images", "pipe.png")
        os.mkfifo(fifo)
        out = []
        worker = threading.Thread(target=lambda: out.append(serve(fifo, home, returns(fifo))), daemon=True)
        worker.start()
        worker.join(10)
        if worker.is_alive():
            # Free the worker blocked in open: a writer appears, the open returns, the worker ends.
            w = os.open(fifo, os.O_WRONLY | os.O_NONBLOCK)
            try:
                worker.join(10)
                self.assertFalse(worker.is_alive())
            finally:
                os.close(w)
            self.fail("serve blocked on a FIFO")
        self.assertEqual(out, [NOT_FOUND])

    def test_opens_validated_name_not_input(self):
        home = self.make_home()
        a = write(os.path.join(home, "cache", "images", "a.png"), PNG + b"A")
        b = write(os.path.join(home, "cache", "images", "b.png"), PNG + b"B")
        self.assertEqual(serve(a, home, returns(b)), (200, None, "image/png", PNG + b"B"))


# --- read -------------------------------------------------------------------------------------------

class ReadTest(TempCase):
    def assertValid(self, fd):
        try:
            os.fstat(fd)
        except OSError:
            self.fail("the test's own descriptor was closed by the worker")

    def picture(self, name, data, sub=()):
        home = self.make_home()
        return home, write(os.path.join(home, "cache", "images", *sub, name), data)

    def test_exactly_cap_served(self):
        data = PNG + b"\x00" * (64 - len(PNG))
        home, p = self.picture("a.png", data)
        self.assertEqual(serve(p, home, resolving, cap=64), (200, None, "image/png", data))

    def test_cap_plus_one_413(self):
        home, p = self.picture("a.png", PNG + b"\x00" * (65 - len(PNG)))
        self.assertEqual(serve(p, home, resolving, cap=64), (413, "too_large", None, None))

    def test_grows_after_fstat_413(self):
        home, p = self.picture("a.png", PNG + b"\x00" * (65 - len(PNG)))
        real_fstat = os.fstat

        def small_fstat(fd):
            st = real_fstat(fd)
            return types.SimpleNamespace(st_mode=st.st_mode, st_size=min(st.st_size, 64))
        with mock.patch.object(os, "fstat", small_fstat):
            result = serve(p, home, resolving, cap=64)
        self.assertEqual(result, (413, "too_large", None, None))

    def test_signatures_give_content_type(self):
        for name, data, ctype in (("a.png", PNG, "image/png"), ("a.jpg", JPEG, "image/jpeg"),
                                  ("a.jpeg", JPEG, "image/jpeg"), ("a.gif", GIF7, "image/gif"),
                                  ("b.gif", GIF9, "image/gif"), ("a.webp", WEBP, "image/webp"),
                                  ("png-inside.jpg", PNG, "image/png")):
            with self.subTest(name=name):
                home, p = self.picture(name, data)
                self.assertEqual(serve(p, home, resolving), (200, None, ctype, data))

    def test_text_named_png_415(self):
        home, p = self.picture("a.png", b"just some ascii text\n")
        self.assertEqual(serve(p, home, resolving), (415, "not_a_picture", None, None))

    def test_header_only_files(self):
        for data, want in ((b"\x89PNG\r\n\x1a\n", (200, None, "image/png", b"\x89PNG\r\n\x1a\n")),
                           (b"\xff\xd8\xff", (200, None, "image/jpeg", b"\xff\xd8\xff")),
                           (b"RIFF\x00\x00\x00\x00WEB", (415, "not_a_picture", None, None)),
                           (b"", (415, "not_a_picture", None, None))):
            with self.subTest(size=len(data)):
                home, p = self.picture("a.png", data)
                self.assertEqual(serve(p, home, resolving), want)

    def test_fstat_or_read_error_500(self):
        def raise_oserror(*a, **k):
            raise OSError("injected")
        for name in ("fstat", "read"):
            with self.subTest(fails=name):
                home, p = self.picture("a.png", PNG)
                with FdTracker() as fds, mock.patch.object(os, name, raise_oserror):
                    result = serve(p, home, resolving)
                self.assertEqual(result, (500, "internal", None, None))
                body = json.loads(media_core.error_body(500, "internal"))
                self.assertEqual(body["error"]["code"], "internal")
                self.assertEqual(len(fds.opened), 4)
                fds.assert_each_closed_once(self)

    def test_tuple_shape(self):
        home, p = self.picture("a.png", PNG)
        text = write(os.path.join(home, "cache", "images", "t.png"), b"text")
        big = write(os.path.join(home, "cache", "images", "big.png"), PNG * 8)
        for validate, cap in ((resolving, CAP), (returns(None), CAP), (returns(b"x"), CAP),
                              (returns("rel/a.png"), CAP), (returns(text), CAP), (returns(big), 16)):
            status, code, ctype, data = serve(p, home, validate, cap)
            self.assertFalse(code is not None and data is not None)
            if status != 200:
                self.assertIsNone(ctype)
                self.assertIsNone(data)

    def test_descriptors_closed_on_success(self):
        home, p = self.picture("a.png", PNG, sub=("sub",))
        with FdTracker() as fds:
            self.assertEqual(serve(p, home, resolving)[0], 200)
        self.assertEqual(len(fds.opened), 5)
        fds.assert_each_closed_once(self)

    def test_descriptors_closed_on_each_failure(self):
        home, p = self.picture("a.png", PNG, sub=("sub",))
        # Opens in order: home, cache, images, sub, a.png. Fail each one after the home in turn.
        for fail_at in (1, 2, 3, 4):
            with self.subTest(fail_at=fail_at):
                with FdTracker(fail_at=fail_at) as fds:
                    self.assertEqual(serve(p, home, resolving), NOT_FOUND)
                self.assertEqual(len(fds.opened), fail_at)
                fds.assert_each_closed_once(self)
        with self.subTest("leaf is not a regular file"):
            v = os.path.join(home, "cache", "images", "dir.png")
            os.mkdir(v)
            with FdTracker() as fds:
                self.assertEqual(serve(v, home, returns(v)), NOT_FOUND)
            self.assertEqual(len(fds.opened), 4)
            fds.assert_each_closed_once(self)
        with self.subTest("read fails"):
            def raise_oserror(*a, **k):
                raise OSError("injected")
            with FdTracker() as fds, mock.patch.object(os, "read", raise_oserror):
                self.assertEqual(serve(p, home, resolving), INTERNAL)
            self.assertEqual(len(fds.opened), 5)
            fds.assert_each_closed_once(self)

    def test_reused_number_stays_valid(self):
        home, p = self.picture("a.png", PNG, sub=("sub",))
        spare = write(os.path.join(self.tmpdir(), "spare"), b"spare")
        reused = []
        real_open, real_close = os.open, os.close

        def close_then_reuse(fd):
            real_close(fd)
            reused.append((fd, real_open(spare, os.O_RDONLY | os.O_CLOEXEC)))
        with FdTracker(on_close=close_then_reuse) as fds:
            result = serve(p, home, resolving)
        (number, mine), = reused
        try:
            self.assertEqual(mine, number, "the OS did not hand back the released number")
            self.assertEqual(fds.closed.count(number), 1, "the released number was closed again")
            self.assertValid(mine)
            self.assertEqual(result, (200, None, "image/png", PNG))
        finally:
            try:
                real_close(mine)
            except OSError:
                pass
        fds.assert_each_closed_once(self)

    def test_close_error_not_repeated(self):
        home, p = self.picture("a.png", PNG)
        spare = write(os.path.join(self.tmpdir(), "spare"), b"spare")
        reused = []
        real_open, real_close = os.open, os.close

        def close_reuse_raise(fd):
            real_close(fd)
            reused.append((fd, real_open(spare, os.O_RDONLY | os.O_CLOEXEC)))
            raise OSError("injected")
        with FdTracker(on_close=close_reuse_raise) as fds:
            result = serve(p, home, resolving)
        (number, mine), = reused
        try:
            self.assertEqual(mine, number, "the OS did not hand back the released number")
            self.assertEqual(fds.closed.count(number), 1, "the released number was closed again")
            self.assertValid(mine)
            self.assertEqual(result, (500, "internal", None, None))
        finally:
            try:
                real_close(mine)
            except OSError:
                pass
        # Every other descriptor the worker opened was closed once; the failed number is not retried.
        fds.assert_each_closed_once(self)


# --- body -------------------------------------------------------------------------------------------

class Reader:
    """A fake request.content.read: hands out the chunks, never more than asked, records each call."""

    def __init__(self, chunks, endless=None):
        self.chunks, self.endless, self.calls = list(chunks), endless, []

    async def __call__(self, n):
        self.calls.append(n)
        if self.endless is not None:
            return self.endless[:n]
        if not self.chunks:
            return b""
        chunk = self.chunks.pop(0)
        if len(chunk) > n:
            chunk, rest = chunk[:n], chunk[n:]
            self.chunks.insert(0, rest)
        return chunk


def make_header(values):
    lower = {k.lower(): v for k, v in values.items() if v is not None}
    return lambda name: lower.get(name.lower())


def parse(body, content_type="application/json", length="auto", encoding=None, chunks=None, reader=None):
    headers = {"Content-Type": content_type, "Content-Encoding": encoding,
               "Content-Length": str(len(body)) if length == "auto" else length}
    reader = reader or Reader(chunks if chunks is not None else [body])
    return asyncio.run(media_core.parse_body(make_header(headers), reader))


def body_of(path):
    return json.dumps({"path": path}).encode()


class BodyTest(unittest.TestCase):
    def refused(self, *a, **k):
        with self.assertRaises(BodyRefused) as cm:
            parse(*a, **k)
        return cm.exception.status, cm.exception.code

    def accepted(self, *a, **k):
        try:
            return parse(*a, **k)
        except BodyRefused as e:
            self.fail("refused: %d %s" % (e.status, e.code))

    def assertBad(self, *a, **k):
        self.assertEqual(self.refused(*a, **k), (400, "bad_request"))

    def test_valid_body_split_over_two_reads(self):
        body = body_of("/srv/out/chart.png")
        reader = Reader([body[:7], body[7:]])
        self.assertEqual(self.accepted(body, reader=reader), "/srv/out/chart.png")
        self.assertGreaterEqual(len(reader.calls), 3)

    def test_valid_with_charset_utf8(self):
        body = body_of("/srv/out/chart.png")
        for ctype in ("application/json; charset=utf-8", "application/json;charset=utf-8",
                      'Application/JSON; Charset="UTF-8"', "application/json \t;\t charset=utf-8",
                      "application/json"):
            with self.subTest(ctype=ctype):
                self.assertEqual(self.accepted(body, content_type=ctype), "/srv/out/chart.png")

    def test_short_body_400(self):
        body = body_of("/srv/out/chart.png")
        self.assertBad(body[:-1], length=str(len(body)))

    def test_extra_byte_400(self):
        body = body_of("/srv/out/chart.png")
        self.assertBad(body + b" ", length=str(len(body)))

    def test_reads_stop_at_8193(self):
        body = body_of("/srv/out/chart.png")
        reader = Reader([], endless=b" " * 1000)
        self.assertBad(body, length="8192", reader=reader)
        self.assertLessEqual(len(reader.calls), 10)
        got = 0
        for n in reader.calls:
            self.assertLessEqual(n, 8193 - got)
            got += min(n, 1000)
        self.assertEqual(got, 8193)

    def test_content_length_rules(self):
        body = body_of("/srv/out/chart.png")
        n = len(body)
        half = str(n)[:1] + "_" + str(n)[1:]
        for length in (None, "8193", "+%d" % n, " %d" % n, half, "%d " % n, "-%d" % n, "", "\u0661\u0667"):
            with self.subTest(length=length):
                self.assertBad(body, length=length)
        padded = b'{"path":' + b" " * (8192 - len(body)) + body[8:]
        self.assertEqual(len(padded), 8192)
        self.assertEqual(self.accepted(padded, length="8192"), "/srv/out/chart.png")
        self.assertEqual(self.accepted(body, length="000" + str(n)), "/srv/out/chart.png")

    def test_content_encoding_rules(self):
        body = body_of("/srv/out/chart.png")
        self.assertBad(body, encoding="gzip")
        self.assertBad(body, encoding="identity, gzip")
        for encoding in (None, "identity", "IDENTITY"):
            with self.subTest(encoding=encoding):
                self.assertEqual(self.accepted(body, encoding=encoding), "/srv/out/chart.png")

    def test_content_type_rules(self):
        body = body_of("/srv/out/chart.png")
        for ctype in (None, "text/plain", "application/json; charset=latin-1", "application/json; v=1",
                      "application/json; charset=utf-8; charset=utf-8", "application/json; charset=utf-8; v=1",
                      "application/jsonx", "application/json;", "application/json; charset",
                      "application/json; charset=", 'application/json; charset="utf-8'):
            with self.subTest(ctype=ctype):
                self.assertBad(body, content_type=ctype)

    def test_json_rules(self):
        for body in (b'{"path":"/a\xff.png"}', b'\xef\xbb\xbf{"path":"/a.png"}', b"not json",
                     b'["/a.png"]', b'{"path":"/a.png","x":1}', b"{}", b'{"path":5}', b'{"path":null}',
                     b"[" * 8000, b'{"path":' + b"1" * 5000 + b"}"):
            with self.subTest(body=body[:24]):
                self.assertBad(body)

    def test_duplicate_path_400(self):
        self.assertBad(b'{"path":"/a.png","path":"/b.png"}')

    def test_lone_surrogate_400(self):
        self.assertBad(b'{"path":"/a\\ud800.png"}')

    def test_path_rules(self):
        for path in ("/", "/" + "a" * 1020 + ".png", "a.png", "srv/a.png", "/a\u0000.png", "/a\u001f.png",
                     "/a\u007f.png", "/a\u009f.png", "/a\n.png"):
            with self.subTest(n=len(path)):
                self.assertBad(body_of(path))
        self.assertEqual(self.refused(body_of("/x")), (415, "not_a_picture"))
        self.assertEqual(self.accepted(body_of("/a.png")), "/a.png")
        longest = "/" + "a" * 1019 + ".png"
        self.assertEqual(len(longest), 1024)
        self.assertEqual(self.accepted(body_of(longest)), longest)
        self.assertEqual(self.accepted(body_of("/a\u00a0b.png")), "/a\u00a0b.png")

    def test_non_picture_suffix_415(self):
        for path in ("/srv/out/report.pdf", "/srv/out/a.png.txt", "/srv/out.png/a",
                     "/srv/out/apng", "/srv/out/a.pn\u0261"):
            with self.subTest(path=path):
                self.assertEqual(self.refused(body_of(path)), (415, "not_a_picture"))
        for path in ("/srv/out/a.PNG", "/srv/out/a.JpEg", "/srv/out/a.jpg", "/srv/out/a.gif", "/srv/out/a.webp",
                     "/srv/out/.png"):
            with self.subTest(path=path):
                self.assertEqual(self.accepted(body_of(path)), path)

    def test_body_rules_before_suffix(self):
        self.assertBad(body_of("/srv/out/a\u0001.pdf"))
        self.assertBad(body_of("/srv/out/report.pdf"), content_type="text/plain")
        self.assertBad(body_of("srv/out/report.pdf"))
        self.assertBad(b'{"path":"/a.pdf","path":"/b.pdf"}')


# --- error bodies -----------------------------------------------------------------------------------

SPEC_CODES = ((400, "bad_request"), (404, "file_unavailable"), (413, "too_large"), (415, "not_a_picture"),
              (500, "internal"), (503, "unavailable"), (503, "busy"), (503, "hermes_incompatible"))

class ErrorBodyTest(TempCase):
    def test_every_code_fixed_body(self):
        for status, code in SPEC_CODES:
            with self.subTest(code=code):
                raw = media_core.error_body(status, code)
                self.assertIsInstance(raw, bytes)
                body = json.loads(raw.decode("utf-8"))
                self.assertEqual(list(body), ["error"])
                self.assertEqual(sorted(body["error"]), ["code", "message"])
                self.assertEqual(body["error"]["code"], code)
                message = body["error"]["message"]
                self.assertIsInstance(message, str)
                self.assertTrue(message and message.isascii())
                self.assertNotIn("-", message)
                self.assertNotRegex(message, "[\u2010-\u2015]")
                self.assertRegex(code, r"^[a-z0-9_]{1,64}$")
                self.assertEqual(raw, media_core.error_body(status, code))
        self.assertEqual(len({media_core.error_body(s, c) for s, c in SPEC_CODES}), len(SPEC_CODES))

    def test_unknown_code_not_echoed(self):
        canary = "canary" + "q" * 20
        for status, code in ((404, canary), (400, "file_unavailable")):
            with self.subTest(status=status):
                with self.assertRaises(ValueError) as cm:
                    media_core.error_body(status, code)
                self.assertNotIn(canary, repr(cm.exception.args))

    def test_no_input_echo(self):
        canary = "canary" + "q" * 20
        refusals = []

        def check_refusal(e):
            self.assertNotIn(canary, str(e))
            self.assertNotIn(canary, repr(e))
            self.assertNotIn(canary, repr(e.args))
            self.assertIsNone(e.__context__)
            self.assertIsNone(e.__cause__)
            self.assertNotIn(canary, media_core.error_body(e.status, e.code).decode())
            refusals.append(e.code)

        good = body_of("/srv/" + canary + ".png")
        for kwargs in ({"body": good, "encoding": "gzip"}, {"body": good, "content_type": "text/" + canary},
                       {"body": good, "length": canary}, {"body": good, "length": "8193"},
                       {"body": good + b" ", "length": str(len(good))}, {"body": good[:-1] + b"\xff}"},
                       {"body": b'{"path":"/' + canary.encode() + b'.png","path":"/b.png"}'},
                       {"body": body_of("/srv/" + canary + "\u0001.png")}, {"body": body_of(canary + ".png")},
                       {"body": b'{"path":"/' + canary.encode() + b'\\ud800.png"}'},
                       {"body": body_of("/srv/" + canary + ".pdf")}, {"body": b"[" * 8000 + canary.encode()},
                       {"body": b'{"path":"/' + canary.encode() + b'.png","x":1}'}):
            with self.subTest(case=sorted(kwargs)):
                with self.assertRaises(BodyRefused) as cm:
                    parse(**kwargs)
                check_refusal(cm.exception)
        self.assertIn("not_a_picture", refusals)

        home = self.make_home()
        images = os.path.join(home, "cache", "images")
        named = lambda suffix: os.path.join(images, canary + suffix)
        write(named(".png"), PNG)
        write(named("t.png"), b"text " + canary.encode())
        write(named("b.png"), PNG * 8)
        os.mkdir(named("d.png"))

        def raising(exc):
            def validate(_p):
                raise exc(canary)
            return validate
        cases = [(named(".png"), returns(None), CAP), (named(".png"), returns(named(".png").encode()), CAP),
                 (named(".png"), raising(AttributeError), CAP), (named(".png"), raising(ValueError), CAP),
                 (named(".png"), returns(canary + ".png"), CAP), (named(".png"), returns(named(".txt")), CAP),
                 (named(".png"), returns("/srv/" + canary + ".png"), CAP),
                 (named(".png"), returns(os.path.join(images, "missing", canary + ".png")), CAP),
                 (named("d.png"), resolving, CAP), (named("d.png"), returns(named("d.png")), CAP),
                 (named("b.png"), resolving, 16), (named("t.png"), resolving, CAP)]
        statuses = set()
        for path, validate, cap in cases:
            result = serve(path, home, validate, cap)
            statuses.add(result[0])
            self.assertNotIn(canary, repr(result))
            self.assertNotIn(canary, media_core.error_body(result[0], result[1]).decode())

        def raise_canary(*a, **k):
            raise OSError(canary)
        for name in ("fstat", "read"):
            with mock.patch.object(os, name, raise_canary):
                result = serve(named(".png"), home, resolving)
            self.assertEqual(result, INTERNAL)
        self.assertEqual(statuses, {404, 413, 415, 500, 503})

    def test_module_shape(self):
        with open(media_core.__file__, encoding="utf-8") as f:
            source = f.read()
        lines = re.findall(r"^PROTOCOL = ([0-9]+)$", source, re.M)
        self.assertEqual(lines, ["1"])
        self.assertEqual(source.count("PROTOCOL ="), 1)
        self.assertEqual(media_core.PROTOCOL, 1)
        self.assertNotIn("aiohttp", source)
        self.assertNotIn("logging", source)
        self.assertNotRegex(source, r"(?m)^\s*from\s+\.")
        self.assertNotRegex(source, r"(?m)^\s*import\s+(hermes|gateway|agent)")
        self.assertEqual(media_core.MAX_BYTES, 10485760)
        self.assertEqual(media_core.TYPES, ("png", "jpeg", "gif", "webp"))


if __name__ == "__main__":
    unittest.main()
