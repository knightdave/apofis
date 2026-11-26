"""Offline tests for the apofis-media plugin glue (__init__.py), its manifest and its README.

The package is loaded the way Hermes loads a directory plugin. aiohttp and the Hermes modules are fakes
placed in sys.modules and removed afterwards: no real aiohttp, no Hermes module and no network of any kind.
"""
import asyncio
import contextvars
import gc
import importlib.util
import json
import logging
import os
import shutil
import socket
import sys
import tempfile
import threading
import types
import unittest
from unittest import mock

HERE = os.path.dirname(os.path.abspath(__file__))
NAME = "hermes_plugins.apofis_media"
PING = "/apofis-media/v1/ping"
FILE = "/apofis-media/v1/file"
HEAVY = ("aiohttp", "gateway", "hermes_constants")
FAKE_NAMES = ("aiohttp", "aiohttp.web", "gateway", "gateway.platforms", "gateway.platforms.base",
              "hermes_constants")

READY = (b'{"name":"apofis-media","protocol":1,"ready":true,"max_bytes":10485760,'
         b'"types":["png","jpeg","gif","webp"]}')
NOT_READY = b'{"name":"apofis-media","protocol":1,"ready":false,"reason":"hermes_incompatible"}'
PNG = b"\x89PNG\r\n\x1a\n" + b"\x00" * 8
REPORT = "/srv/out/report.pdf"
EXC_TEXT = "boom-canary-91c2"
PATHS = ["/srv/out/p%d.png" % i for i in range(8)]
# Built at run time, never a key-shaped literal.
WRONG_KEY = "w" * 40
CANARIES = [REPORT, EXC_TEXT, WRONG_KEY, "/srv/out/", "canary"] + PATHS

A = (logging.INFO, "apofis-media: ready")


def B(reason):
    return (logging.WARNING, "apofis-media: not ready (%s)" % reason)


# --- egress guard -----------------------------------------------------------------------------------
# Shape of server/apofis-push/test_apofis_push.py, without its loopback exception and with bind, sends
# and every name lookup added (as in test_media_core.py): these tests open no socket at all.

VIOLATIONS: list = []
_REAL = {}
_GUARDED_METHODS = ("connect", "connect_ex", "bind", "sendto", "sendmsg")
_GUARDED_FUNCTIONS = ("getaddrinfo", "gethostbyname", "gethostbyname_ex", "gethostbyaddr", "getnameinfo")

# --- log capture ------------------------------------------------------------------------------------

PLUGIN_RECORDS: list = []
ROOT_RECORDS: list = []
_LOG = {}
REASONS = ("restart_needed", "hermes_incompatible", "no_platform_handler")
TEMPLATES = {A} | {B(r) for r in REASONS}
PLUGIN_FILES = {os.path.join(HERE, "__init__.py"), os.path.join(HERE, "media_core.py")}


class _Keep(logging.Handler):
    def __init__(self, into):
        super().__init__(logging.NOTSET)
        self.into = into

    def emit(self, record):
        self.into.append(record)


def setUpModule():
    """Deny every connect, bind, datagram send and name lookup in this process, loopback included, and
    record every log record of the module's run."""
    def deny(kind):
        def blocked(*a, **k):
            VIOLATIONS.append(kind)
            raise PermissionError("network blocked by test guard: %s" % kind)
        return blocked
    for name in _GUARDED_METHODS:
        _REAL[name] = getattr(socket.socket, name)
        setattr(socket.socket, name, deny(name))
    for name in _GUARDED_FUNCTIONS:
        _REAL[name] = getattr(socket, name)
        setattr(socket, name, deny(name))
    plugin, root = logging.getLogger(NAME), logging.getLogger()
    _LOG["plugin_level"], _LOG["root_level"] = plugin.level, root.level
    _LOG["plugin"], _LOG["root"] = _Keep(PLUGIN_RECORDS), _Keep(ROOT_RECORDS)
    plugin.addHandler(_LOG["plugin"])
    root.addHandler(_LOG["root"])
    plugin.setLevel(logging.DEBUG)
    root.setLevel(logging.DEBUG)


def tearDownModule():
    for name in _GUARDED_METHODS:
        setattr(socket.socket, name, _REAL[name])
    for name in _GUARDED_FUNCTIONS:
        setattr(socket, name, _REAL[name])
    plugin, root = logging.getLogger(NAME), logging.getLogger()
    plugin.removeHandler(_LOG["plugin"])
    root.removeHandler(_LOG["root"])
    plugin.setLevel(_LOG["plugin_level"])
    root.setLevel(_LOG["root_level"])
    problems = []
    if VIOLATIONS:
        problems.append("network use attempted: %d" % len(VIOLATIONS))
    for r in PLUGIN_RECORDS:
        text = r.getMessage()
        if (r.levelno, text) not in TEMPLATES:
            problems.append("a plugin record outside the two templates")
        if r.exc_info or r.exc_text or r.stack_info:
            problems.append("a plugin record with exception or stack text")
        if any(c in text or c in repr(r.args) for c in CANARIES):
            problems.append("a plugin record holds a canary")
    for r in ROOT_RECORDS:
        if os.path.abspath(r.pathname) in PLUGIN_FILES and r.name != NAME:
            problems.append("a plugin file logged under another logger name")
    if problems:
        raise AssertionError("; ".join(sorted(set(problems))))


# --- fakes: aiohttp ---------------------------------------------------------------------------------
# Only call shapes that exist in aiohttp 3.14: Response(*, body, status, reason, text, headers,
# content_type, charset, ...), json_response(data, *, text, body, status, reason, headers, content_type,
# dumps), UrlDispatcher.add_route(method, path, handler, *, name, expect_handler), Application.frozen.

_SENTINEL = object()


class StreamResponse:
    def __init__(self, *, status=200, reason=None, headers=None):
        self.status = status
        self.headers = dict(headers or {})
        self.calls = []

    def enable_compression(self, *a, **k):
        self.calls.append("enable_compression")

    def enable_chunked_encoding(self, *a, **k):
        self.calls.append("enable_chunked_encoding")


class Response(StreamResponse):
    def __init__(self, *, body=None, status=200, reason=None, text=None, headers=None, content_type=None,
                 charset=None, zlib_executor_size=None, zlib_executor=None):
        if body is not None and text is not None:
            raise ValueError("body and text are not allowed together")
        if content_type is not None and "charset" in content_type:
            raise ValueError("charset must not be in content_type argument")
        if "content-type" in {k.lower() for k in (headers or {})} and (
                content_type is not None or charset is not None):
            raise ValueError("passing both Content-Type header and content_type or charset params is forbidden")
        super().__init__(status=status, reason=reason, headers=headers)
        self.text_given = text is not None
        if text is not None:
            body = text.encode(charset or "utf-8")
        self.body, self.content_type = body, content_type


def json_response(data=_SENTINEL, *, text=None, body=None, status=200, reason=None, headers=None,
                  content_type="application/json", dumps=json.dumps):
    if data is not _SENTINEL:
        text = dumps(data)
    return Response(text=text, body=body, status=status, reason=reason, headers=headers,
                    content_type=content_type)


class Router:
    def __init__(self):
        self.routes, self.fail, self.during = [], (), {}

    def add_route(self, method, path, handler, *, name=None, expect_handler=None):
        if path in self.during:
            self.during[path]()
        if path in self.fail:
            raise RuntimeError(EXC_TEXT)
        self.routes.append((method, path, handler))


class Application:
    def __init__(self):
        self.router = Router()
        self.frozen = False


WEB = types.ModuleType("aiohttp.web")
WEB.StreamResponse, WEB.Response, WEB.json_response, WEB.Application = (
    StreamResponse, Response, json_response, Application)


# --- fakes: Hermes ----------------------------------------------------------------------------------

ABSENT = object()
MISSING = object()


def validate_media_delivery_path(path, session_key=""):
    return os.path.realpath(path)


def get_hermes_home():
    return "/srv/no-home"


def fake_modules(validate=validate_media_delivery_path, home=get_hermes_home):
    aio = types.ModuleType("aiohttp")
    aio.__path__ = []
    aio.web = WEB
    gateway = types.ModuleType("gateway")
    gateway.__path__ = []
    platforms = types.ModuleType("gateway.platforms")
    platforms.__path__ = []
    base = types.ModuleType("gateway.platforms.base")
    gateway.platforms, platforms.base = platforms, base
    if validate is not ABSENT:
        base.validate_media_delivery_path = validate
    constants = types.ModuleType("hermes_constants")
    if home is not ABSENT:
        constants.get_hermes_home = home
    return {"aiohttp": aio, "aiohttp.web": WEB, "gateway": gateway, "gateway.platforms": platforms,
            "gateway.platforms.base": base, "hermes_constants": constants}


def _restore(name, old):
    if old is MISSING:
        sys.modules.pop(name, None)
    else:
        sys.modules[name] = old


def put_modules(test, mapping):
    """Set sys.modules entries for the test; MISSING removes one, None makes its import fail."""
    for name, mod in mapping.items():
        test.addCleanup(_restore, name, sys.modules.get(name, MISSING))
        if mod is MISSING:
            sys.modules.pop(name, None)
        else:
            sys.modules[name] = mod


def isolate_modules(test, match):
    """Save and evict every sys.modules entry whose name matches, as the real loader evicts; at cleanup remove
    every matching entry, also one added since, then put the saved ones back exactly."""
    saved = {k: v for k, v in list(sys.modules.items()) if match(k)}
    for k in saved:
        sys.modules.pop(k, None)

    def restore():
        for k in [k for k in list(sys.modules) if match(k)]:
            sys.modules.pop(k, None)
        sys.modules.update(saved)
    test.addCleanup(restore)


def is_verify(name):
    return name == "verify" or name.endswith(".verify")


class Cleanups:
    """A stand-in for a TestCase's cleanups, so a helper's own restore can be checked."""

    def __init__(self):
        self.fns = []

    def addCleanup(self, fn, *args):
        self.fns.append((fn, args))

    def run(self):
        while self.fns:
            fn, args = self.fns.pop()
            fn(*args)


def load(test, name=NAME):
    """Load the package as hermes_cli/plugins_loader.py does: a namespace parent, a synthetic package
    from __init__.py with the folder as __path__, then exec_module. Entries are removed afterwards."""
    test.addCleanup(_restore, "hermes_plugins", sys.modules.get("hermes_plugins", MISSING))
    isolate_modules(test, lambda k: k == name or k.startswith(name + "."))
    if "hermes_plugins" not in sys.modules:
        ns = types.ModuleType("hermes_plugins")
        ns.__path__ = []
        ns.__package__ = "hermes_plugins"
        sys.modules["hermes_plugins"] = ns
    spec = importlib.util.spec_from_file_location(
        name, os.path.join(HERE, "__init__.py"), submodule_search_locations=[HERE])
    mod = importlib.util.module_from_spec(spec)
    mod.__package__ = name
    mod.__path__ = [HERE]
    sys.modules[name] = mod
    spec.loader.exec_module(mod)
    return mod


class Adapter:
    def __init__(self, auth=None):
        self._check_auth = auth if auth is not None else (lambda request: None)


class Headers:
    def __init__(self, items):
        self._items = {k.lower(): v for k, v in items.items()}

    def get(self, name, default=None):
        return self._items.get(name.lower(), default)


class Content:
    def __init__(self, body):
        self.body, self.reads = body, 0

    async def read(self, n=-1):
        self.reads += 1
        chunk = self.body if n < 0 else self.body[:n]
        self.body = self.body[len(chunk):]
        return chunk


class Request:
    def __init__(self, path=None, *, body=None, headers=None):
        if body is None:
            body = json.dumps({"path": path}).encode() if path is not None else b""
        items = {"Content-Type": "application/json", "Content-Length": str(len(body))}
        items.update(headers or {})
        self.headers, self.content = Headers(items), Content(body)


def routes_of(app):
    return {path: handler for _, path, handler in app.router.routes}


def ask_ping(app):
    return asyncio.run(routes_of(app)[PING](Request())).body


async def settle(cond):
    """Yield to the loop until cond() holds, a bounded number of times; no real-time wait."""
    for _ in range(500):
        if cond():
            return True
        await asyncio.sleep(0)
    return cond()


def waiting(sem):
    return sum(1 for w in (sem._waiters or ()) if not w.done())


class Base(unittest.TestCase):
    def mark(self):
        return len(PLUGIN_RECORDS)

    def logged(self, mark):
        return [(r.levelno, r.getMessage()) for r in PLUGIN_RECORDS[mark:]]

    def wire(self, *, validate=validate_media_delivery_path, home=get_hermes_home, auth=None, app=None):
        put_modules(self, fake_modules(validate, home))
        self.mod = load(self)
        self.app = app if app is not None else Application()
        self.mod.factory(self.app, Adapter(auth))
        self.handlers = routes_of(self.app)
        return self.handlers

    def file(self, request):
        return asyncio.run(self.handlers[FILE](request))

    def ping(self, request=None):
        return asyncio.run(self.handlers[PING](request or Request()))

    def assertError(self, r, status, code):
        self.assertIsInstance(r, Response)
        self.assertEqual((r.status, r.body), (status, self.mod.media_core.error_body(status, code)))

    def home_dir(self):
        home = tempfile.mkdtemp(prefix="apofis-plugin-")
        self.addCleanup(shutil.rmtree, home)
        CANARIES.append(home)
        os.makedirs(os.path.join(home, "cache", "images"))
        return home

    def picture(self, home, *names):
        path = os.path.join(home, *names)
        with open(path, "wb") as f:
            f.write(PNG)
        return path


# --- tests ------------------------------------------------------------------------------------------

class ManifestTest(unittest.TestCase):
    def test_manifest_lines(self):
        with open(os.path.join(HERE, "plugin.yaml"), encoding="utf-8") as f:
            lines = [line for line in f.read().splitlines() if line.strip()]
        self.assertEqual(lines, [
            "name: apofis-media",
            "version: 1.0.0",
            "description: Lets the Apofis app fetch pictures from this profile's picture folders with the API"
            " server key.",
            "kind: standalone",
        ])

    def test_register_line(self):
        with open(os.path.join(HERE, "__init__.py"), encoding="utf-8") as f:
            self.assertTrue(any(line.startswith("def register(ctx") for line in f.read().splitlines()))


class LoadsLikeHermesTest(Base):
    def test_loads_as_synthetic_package(self):
        path = list(sys.path)
        put_modules(self, fake_modules())
        mod = load(self)
        self.assertEqual(sys.path, path)
        self.assertEqual(mod.media_core.__name__, NAME + ".media_core")
        self.assertIs(mod.media_core, sys.modules[NAME + ".media_core"])

    def test_no_heavy_import_at_load(self):
        put_modules(self, {name: MISSING for name in FAKE_NAMES})
        tried = []

        class Refuse:
            def find_spec(self, name, path=None, target=None):
                if name.split(".")[0] in HEAVY:
                    tried.append(name)
                    raise ImportError("heavy import refused by the test")
                return None
        finder = Refuse()
        sys.meta_path.insert(0, finder)
        self.addCleanup(sys.meta_path.remove, finder)
        got = []
        ctx = types.SimpleNamespace(register_platform_handler=lambda platform, f: got.append(platform))
        try:
            load(self).register(ctx)
        except ImportError:
            self.fail("loading or registering imported aiohttp or Hermes")
        self.assertEqual(tried, [])
        self.assertEqual(got, ["api_server"])

    def test_never_imports_verify(self):
        isolate_modules(self, is_verify)
        seen = []

        class Recorder:
            def find_spec(self, name, path=None, target=None):
                if name.rsplit(".", 1)[-1] == "verify":
                    seen.append(name)
                return None
        recorder = Recorder()
        sys.meta_path.insert(0, recorder)
        self.addCleanup(sys.meta_path.remove, recorder)
        put_modules(self, fake_modules())
        mod = load(self)
        got = []
        mod.register(types.SimpleNamespace(register_platform_handler=lambda p, f: got.append(f)))
        app = Application()
        got[0](app, Adapter())
        self.assertEqual(len(app.router.routes), 2)
        self.assertEqual(seen, [])

    def test_loader_helper_evicts_and_restores(self):
        sentinel = types.ModuleType("sentinel")
        late = NAME + ".late_submodule"
        before = sys.modules.get(NAME, MISSING)
        put_modules(self, {NAME + ".media_core": sentinel, late: MISSING})
        put_modules(self, fake_modules())
        c = Cleanups()
        try:
            mod = load(c)
            self.assertIsNot(mod.media_core, sentinel, "a preloaded submodule was not evicted")
            sys.modules[late] = types.ModuleType(late)
        finally:
            c.run()
        self.assertIs(sys.modules[NAME + ".media_core"], sentinel)
        self.assertFalse(late in sys.modules, "a submodule added after the load was left behind")
        self.assertIs(sys.modules.get(NAME, MISSING), before)

    def test_module_isolation_restores_exactly(self):
        sentinel = types.ModuleType("verify")
        put_modules(self, {"apofis_sentinel.verify": sentinel, "apofis_new.verify": MISSING})
        c = Cleanups()
        try:
            isolate_modules(c, is_verify)
            self.assertFalse("apofis_sentinel.verify" in sys.modules)
            sys.modules["apofis_new.verify"] = types.ModuleType("verify")
        finally:
            c.run()
        self.assertIs(sys.modules["apofis_sentinel.verify"], sentinel)
        self.assertFalse("apofis_new.verify" in sys.modules, "a new verifier entry was left behind")


class RegistrationTest(Base):
    def test_no_platform_handler(self):
        put_modules(self, fake_modules())
        mod = load(self)
        m = self.mark()
        self.assertIsNone(mod.register(types.SimpleNamespace()))
        self.assertEqual(self.logged(m), [B("no_platform_handler")])

    def test_frozen_restart_needed(self):
        put_modules(self, fake_modules())
        mod = load(self)

        async def async_auth(request):
            return None

        def plain():
            return types.SimpleNamespace(frozen=True, router=Router())

        def no_add():
            app = Application()
            app.frozen, app.router.add_route = True, 5
            return app
        cases = {
            "frozen": (Application, Adapter()),
            "not an Application": (plain, Adapter()),
            "add_route not callable": (no_add, Adapter()),
            "no _check_auth": (Application, object()),
            "async _check_auth": (Application, Adapter(async_auth)),
        }
        for label, (make, adapter) in cases.items():
            with self.subTest(label):
                app = make()
                app.frozen = True
                m = self.mark()
                mod.factory(app, adapter)
                self.assertEqual(app.router.routes, [])
                self.assertEqual(self.logged(m), [B("restart_needed")])
        with self.subTest("frozen = 1 is not frozen"):
            app = Application()
            app.frozen = 1
            m = self.mark()
            mod.factory(app, Adapter())
            self.assertEqual(len(app.router.routes), 2)
            self.assertEqual(self.logged(m), [A])

    def test_outcome_one_reasons(self):
        async def async_auth(request):
            return None

        def no_add():
            app = Application()
            app.router.add_route = 5
            return app
        cases = {
            "not an Application": (lambda: types.SimpleNamespace(frozen=False, router=Router()), Adapter()),
            "add_route not callable": (no_add, Adapter()),
            "no _check_auth": (Application, object()),
            "_check_auth not callable": (Application, types.SimpleNamespace(_check_auth=5)),
            "async _check_auth": (Application, Adapter(async_auth)),
            "aiohttp does not import": (Application, Adapter()),
        }
        for label, (make, adapter) in cases.items():
            with self.subTest(label):
                modules = fake_modules()
                if label == "aiohttp does not import":
                    modules["aiohttp"] = modules["aiohttp.web"] = None
                put_modules(self, modules)
                mod = load(self)
                app = make()
                m = self.mark()
                self.assertIsNone(mod.factory(app, adapter))
                self.assertEqual(app.router.routes if callable(app.router.add_route) else [], [])
                self.assertEqual(self.logged(m), [B("hermes_incompatible")])

    def test_ping_add_raises_adds_nothing(self):
        app = Application()
        app.router.fail = (PING,)
        m = self.mark()
        self.wire(app=app)
        self.assertEqual(app.router.routes, [])
        self.assertEqual(self.logged(m), [B("hermes_incompatible")])

    def test_prerequisite_fails_ping_not_ready(self):
        def no_path(p, session_key=""):
            return p
        cases = {
            "no validate_media_delivery_path": dict(validate=ABSENT),
            "validate without a path parameter": dict(validate=no_path),
            "validate not callable": dict(validate=5),
            "no get_hermes_home": dict(home=ABSENT),
            "get_hermes_home not callable": dict(home=5),
            "hermes_constants does not import": None,
            "gateway.platforms.base does not import": None,
        }
        for label, kwargs in cases.items():
            with self.subTest(label):
                if kwargs is None:
                    modules = fake_modules()
                    modules["hermes_constants" if "constants" in label else "gateway.platforms.base"] = None
                    put_modules(self, modules)
                    self.mod = load(self)
                    self.app = Application()
                    m = self.mark()
                    self.mod.factory(self.app, Adapter())
                    self.handlers = routes_of(self.app)
                else:
                    m = self.mark()
                    self.wire(**kwargs)
                self.assertEqual([(meth, path) for meth, path, _ in self.app.router.routes], [("GET", PING)])
                self.assertEqual(self.ping().body, NOT_READY)
                self.assertEqual(self.logged(m), [B("hermes_incompatible")])

    def test_file_add_raises_ping_not_ready(self):
        app = Application()
        app.router.fail = (FILE,)
        m = self.mark()
        self.wire(app=app)
        self.assertEqual([path for _, path, _ in app.router.routes], [PING])
        self.assertEqual(self.ping().body, NOT_READY)
        self.assertEqual(self.logged(m), [B("hermes_incompatible")])

    def test_ready_after_both(self):
        m = self.mark()
        self.wire()
        self.assertEqual([(meth, path) for meth, path, _ in self.app.router.routes],
                         [("GET", PING), ("POST", FILE)])
        self.assertEqual(self.ping().body, READY)
        self.assertEqual(self.logged(m), [A])

    def test_ready_published_after_file_route(self):
        app = Application()
        during = []
        app.router.during[FILE] = lambda: during.append(ask_ping(app))
        self.wire(app=app)
        self.assertEqual(during, [NOT_READY])
        self.assertEqual(self.ping().body, READY)

    def test_factory_never_raises(self):
        class Hostile:
            def __getattr__(self, name):
                raise RuntimeError(EXC_TEXT)
        put_modules(self, fake_modules())
        mod = load(self)
        app = Application()
        m = self.mark()
        try:
            result = mod.factory(app, Hostile())
        except Exception:
            self.fail("the factory raised")
        self.assertIsNone(result)
        self.assertEqual(app.router.routes, [])
        self.assertEqual(self.logged(m), [B("hermes_incompatible")])

    def test_second_factory_call_isolated(self):
        for label, first, second in (("ready, then not ready", True, False),
                                     ("not ready, then ready", False, True)):
            with self.subTest(label):
                put_modules(self, fake_modules())
                mod = load(self)
                apps = []
                for ready in (first, second):
                    put_modules(self, fake_modules() if ready else fake_modules(validate=ABSENT))
                    app = Application()
                    mod.factory(app, Adapter())
                    apps.append(app)
                want = [READY if first else NOT_READY, READY if second else NOT_READY]
                self.assertEqual([ask_ping(app) for app in apps], want)
                sems = [routes_of(app)[PING].__self__.sem for app in apps]
                self.assertIsNot(sems[0], sems[1], "two apps share one worker permit")
                with self.subTest("then a frozen app"):
                    frozen = Application()
                    frozen.frozen = True
                    m = self.mark()
                    mod.factory(frozen, Adapter())
                    self.assertEqual(self.logged(m), [B("restart_needed")])
                    self.assertEqual(frozen.router.routes, [])
                    self.assertEqual([ask_ping(app) for app in apps], want)


class HandlerOrderTest(Base):
    def denying(self):
        key = "k" * 40
        CANARIES.append(key)
        denied = json_response({"error": {"code": "gateway_auth_failed"}}, status=401)

        def check(request):
            return None if request.headers.get("Authorization") == "Bearer " + key else denied
        return check, denied

    def test_no_key_401_before_any_read(self):
        check, denied = self.denying()
        self.wire(auth=check)
        for path in (PING, FILE):
            with self.subTest(path):
                request = Request(PATHS[0])
                self.assertIs(asyncio.run(self.handlers[path](request)), denied)
                self.assertEqual(request.content.reads, 0)

    def test_wrong_key_401_before_any_read(self):
        check, denied = self.denying()
        self.wire(auth=check)
        for path in (PING, FILE):
            with self.subTest(path):
                request = Request(PATHS[0], headers={"Authorization": "Bearer " + WRONG_KEY})
                self.assertIs(asyncio.run(self.handlers[path](request)), denied)
                self.assertEqual(request.content.reads, 0)

    def test_auth_other_value_503_no_file_work(self):
        calls = []
        self.wire(auth=lambda request: "ok", validate=lambda path: calls.append(path))
        for path in (PING, FILE):
            with self.subTest(path):
                request = Request(PATHS[0])
                self.assertError(asyncio.run(self.handlers[path](request)), 503, "hermes_incompatible")
                self.assertEqual(request.content.reads, 0)
        self.assertEqual(calls, [])

    def test_auth_raises(self):
        for exc, status, code in ((AttributeError, 503, "hermes_incompatible"),
                                  (TypeError, 503, "hermes_incompatible"),
                                  (RuntimeError, 500, "internal")):
            def check(request, exc=exc):
                raise exc(EXC_TEXT)
            self.wire(auth=check)
            for path in (PING, FILE):
                with self.subTest(exc=exc.__name__, path=path):
                    request = Request(PATHS[0])
                    self.assertError(asyncio.run(self.handlers[path](request)), status, code)
                    self.assertEqual(request.content.reads, 0)

    def test_bad_body_400_no_slot(self):
        self.wire()
        sem = self.handlers[FILE].__self__.sem
        acquired = []
        real = sem.acquire

        def acquire():
            acquired.append(1)
            return real()
        sem.acquire = acquire
        for label, request in (("content type", Request(PATHS[0], headers={"Content-Type": "text/plain"})),
                               ("extra key", Request(body=b'{"path":"/srv/out/p0.png","x":1}')),
                               ("short body", Request(PATHS[0], headers={"Content-Length": "200"}))):
            with self.subTest(label):
                self.assertError(self.file(request), 400, "bad_request")
        self.assertEqual(acquired, [])

    def test_non_picture_415_no_validate(self):
        calls = []
        self.wire(validate=lambda path: calls.append(path))
        self.assertError(self.file(Request(REPORT)), 415, "not_a_picture")
        self.assertEqual(calls, [])

    def test_scoped_validator_sees_request_context(self):
        var = contextvars.ContextVar("apofis_test_profile")
        homes = {"alpha": self.home_dir(), "beta": self.home_dir()}
        seen, served = [], []
        loop_thread = threading.get_ident()

        def validate(path):
            seen.append((var.get("unset"), threading.get_ident() != loop_thread))
            return None
        self.wire(validate=validate, home=lambda: homes[var.get()])
        real = self.mod.media_core.serve

        def serve(path, home, validate, cap):
            served.append((home, cap))
            return real(path, home, validate, cap)

        async def call():
            token = var.set("beta")
            try:
                return await self.handlers[FILE](Request(PATHS[0]))
            finally:
                var.reset(token)
        with mock.patch.object(self.mod.media_core, "serve", serve):
            r = asyncio.run(call())
        self.assertEqual(seen, [("beta", True)])
        self.assertEqual(served, [(homes["beta"], self.mod.media_core.MAX_BYTES)])
        self.assertError(r, 404, "file_unavailable")

    def test_success_answer(self):
        home = self.home_dir()
        self.wire(home=lambda: home)
        r = self.file(Request(self.picture(home, "cache", "images", "pic.png")))
        self.assertEqual((r.status, r.body, r.content_type), (200, PNG, "image/png"))
        self.assertEqual(r.headers, {"Cache-Control": "no-store"})

    def test_refusal_tuple_maps_to_fixed_body(self):
        home = self.home_dir()
        self.wire(home=lambda: home)
        r = self.file(Request(self.picture(home, "outside.png")))
        self.assertError(r, 404, "file_unavailable")
        self.assertEqual(r.content_type, "application/json")

    def test_executor_shut_down_503(self):
        self.wire()
        sem = self.handlers[FILE].__self__.sem

        async def call():
            def refuse(*a, **k):
                raise RuntimeError(EXC_TEXT)
            asyncio.get_running_loop().run_in_executor = refuse
            return await self.handlers[FILE](Request(PATHS[0]))
        self.assertError(asyncio.run(call()), 503, "unavailable")
        self.assertEqual(sem._value, 2)

    def test_home_lookup_raises(self):
        for exc, status, code in ((TypeError, 503, "hermes_incompatible"), (OSError, 500, "internal")):
            with self.subTest(exc.__name__):
                def home(exc=exc):
                    raise exc(EXC_TEXT)
                self.wire(home=home)
                self.assertError(self.file(Request(PATHS[0])), status, code)
                self.assertEqual(self.handlers[FILE].__self__.sem._value, 2)

    def test_ping_not_ready_and_ready_bodies(self):
        self.wire()
        r = self.ping()
        self.assertEqual((r.status, r.body, r.content_type), (200, READY, "application/json"))
        self.wire(validate=ABSENT)
        r = self.ping()
        self.assertEqual((r.status, r.body, r.content_type), (200, NOT_READY, "application/json"))

    def test_every_answer_is_a_plain_response(self):
        """One complete body, so exactly one Content-Length: never a StreamResponse, never chunked or
        compressed."""
        answers = {}
        home = self.home_dir()
        self.wire(home=lambda: home)
        answers["ping ready"] = self.ping()
        answers["success"] = self.file(Request(self.picture(home, "cache", "images", "pic.png")))
        answers["400"] = self.file(Request(PATHS[0], headers={"Content-Type": "text/plain"}))
        answers["415 body"] = self.file(Request(REPORT))
        for status, code in ((404, "file_unavailable"), (413, "too_large"), (415, "not_a_picture"),
                             (500, "internal"), (503, "hermes_incompatible")):
            with mock.patch.object(self.mod.media_core, "serve", lambda *a, s=status, c=code: (s, c, None, None)):
                answers["serve %d %s" % (status, code)] = self.file(Request(PATHS[0]))

        async def unavailable():
            def refuse(*a, **k):
                raise RuntimeError(EXC_TEXT)
            asyncio.get_running_loop().run_in_executor = refuse
            return await self.handlers[FILE](Request(PATHS[0]))
        answers["503 unavailable"] = asyncio.run(unavailable())

        async def never():
            await asyncio.get_running_loop().create_future()
        with mock.patch.object(self.mod, "SLOT_WAIT", 0.01):
            with mock.patch.object(self.handlers[FILE].__self__.sem, "acquire", never):
                answers["503 busy"] = self.file(Request(PATHS[0]))

        for exc, label in ((TypeError, "home 503"), (OSError, "home 500")):
            def home_raises(exc=exc):
                raise exc(EXC_TEXT)
            self.wire(home=home_raises)
            answers[label] = self.file(Request(PATHS[0]))

        def auth_raises(request):
            raise RuntimeError(EXC_TEXT)
        self.wire(auth=lambda request: "ok")
        answers["auth 503"] = self.ping()
        self.wire(auth=auth_raises)
        answers["auth 500"] = self.file(Request(PATHS[0]))
        self.wire(validate=ABSENT)
        answers["ping not ready"] = self.ping()
        for label, r in answers.items():
            with self.subTest(label):
                self.assertIs(type(r), Response)
                self.assertFalse(r.text_given)
                self.assertIsInstance(r.body, bytes)
                self.assertTrue(r.body)
                self.assertEqual(r.calls, [])
                self.assertEqual(set(r.headers), {"Cache-Control"})


class Gate:
    """A fake serve that blocks each worker until the test opens the gate of its path."""

    def __init__(self, loop):
        self.loop, self.entered = loop, asyncio.Queue()
        self.gates = {p: threading.Event() for p in PATHS}
        self.lock = threading.Lock()
        self.running = self.peak = 0

    def serve(self, path, home, validate, cap):
        with self.lock:
            self.running += 1
            self.peak = max(self.peak, self.running)
        self.loop.call_soon_threadsafe(self.entered.put_nowait, path)
        try:
            opened = self.gates[path].wait(10)
        finally:
            with self.lock:
                self.running -= 1
        return (200, None, "image/png", PNG) if opened else (500, "internal", None, None)

    def open_all(self):
        for gate in self.gates.values():
            gate.set()

    async def next_entered(self):
        return await asyncio.wait_for(self.entered.get(), 10)


class PermitTest(Base):
    def run_with_gate(self, scenario, slot_wait=None):
        """Wire a ready app and run scenario(gate, start, sem) on a fresh loop; every gate is opened and
        every task ended before the loop closes. Each free count is asserted inside the scenario, while
        the workers it names are still blocked."""
        self.wire()
        handler = self.handlers[FILE]
        sem = handler.__self__.sem

        async def main():
            gate = Gate(asyncio.get_running_loop())
            tasks = []

            def start(path):
                task = asyncio.ensure_future(handler(Request(path)))
                tasks.append(task)
                return task
            with mock.patch.object(self.mod.media_core, "serve", gate.serve):
                try:
                    return await scenario(gate, start, sem)
                finally:
                    gate.open_all()
                    await asyncio.wait_for(asyncio.gather(*tasks, return_exceptions=True), 10)
        with mock.patch.object(self.mod, "SLOT_WAIT", slot_wait or self.mod.SLOT_WAIT):
            result = asyncio.run(main())
        self.assertEqual(sem._value, 2)
        return result

    def test_third_waiter_busy_no_release(self):
        async def scenario(gate, start, sem):
            start(PATHS[0])
            start(PATHS[1])
            self.assertEqual({await gate.next_entered(), await gate.next_entered()}, {PATHS[0], PATHS[1]})
            third = await start(PATHS[2])
            self.assertEqual((sem._value, gate.running), (0, 2), "a permit was released by the busy waiter")
            self.assertError(third, 503, "busy")
        self.run_with_gate(scenario, slot_wait=0.05)

    def test_waiter_cancel_releases_nothing(self):
        async def scenario(gate, start, sem):
            start(PATHS[0])
            start(PATHS[1])
            await gate.next_entered()
            await gate.next_entered()
            third = start(PATHS[2])
            self.assertTrue(await settle(lambda: waiting(sem) == 1))
            third.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await third
            self.assertEqual((sem._value, gate.running), (0, 2), "a cancelled waiter released a permit")
        self.run_with_gate(scenario)

    def test_cancelled_request_keeps_permit_until_thread_ends(self):
        async def scenario(gate, start, sem):
            first = start(PATHS[0])
            second = start(PATHS[1])
            await gate.next_entered()
            await gate.next_entered()
            first.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await first
            self.assertEqual((sem._value, gate.running), (0, 2), "the cancelled request released its permit")
            third = start(PATHS[2])
            self.assertTrue(await settle(lambda: waiting(sem) == 1))
            gate.gates[PATHS[0]].set()
            self.assertEqual(await gate.next_entered(), PATHS[2])
            gate.open_all()
            self.assertEqual(((await second).status, (await third).status), (200, 200))
        self.run_with_gate(scenario)

    def test_peak_workers_two(self):
        async def scenario(gate, start, sem):
            tasks = {p: start(p) for p in PATHS[:5]}
            entered, released = 0, []
            for size in (2, 2, 1):
                batch = [await gate.next_entered() for _ in range(size)]
                entered += size
                # A released worker's thread has left serve once its request has answered.
                await asyncio.wait_for(asyncio.gather(*(tasks[p] for p in released)), 10)
                self.assertTrue(await settle(lambda: gate.running == size and waiting(sem) == 5 - entered))
                for path in batch:
                    gate.gates[path].set()
                released += batch
            self.assertEqual([(await t).status for t in tasks.values()], [200] * 5)
            self.assertEqual(gate.peak, 2)
        self.run_with_gate(scenario)


class SlotAndWorkerTest(Base):
    def test_limits_are_the_spec_values(self):
        self.wire()
        self.assertEqual((self.mod.SLOT_WAIT, self.mod.WORKERS), (5, 2))
        self.assertEqual(self.handlers[FILE].__self__.sem._value, 2)

    def test_handler_passes_slot_wait_to_its_timeout(self):
        self.wire()
        recorded, real = [], asyncio.timeout

        def timeout(delay):
            recorded.append(delay)
            return real(delay)
        with mock.patch.object(asyncio, "timeout", timeout):
            self.file(Request(PATHS[0]))
        self.assertEqual(recorded, [self.mod.SLOT_WAIT])

    def test_cancel_right_after_submission_keeps_the_permit(self):
        """The request is cancelled after exactly one step, so right after its worker was submitted: the
        permit stays with the worker (no await between submission and the done callback)."""
        self.wire()
        handler = self.handlers[FILE]
        sem = handler.__self__.sem
        gate = threading.Event()

        def serve(*a):
            gate.wait(10)
            return (200, None, "image/png", PNG)

        async def main():
            released = asyncio.Event()
            real_release = sem.release

            def release():
                real_release()
                released.set()
            sem.release = release
            with mock.patch.object(self.mod.media_core, "serve", serve):
                try:
                    task = asyncio.ensure_future(handler(Request(PATHS[0])))
                    await asyncio.sleep(0)  # the request has run exactly one step
                    task.cancel()
                    with self.assertRaises(asyncio.CancelledError):
                        await task
                    self.assertEqual(sem._value, 1, "the cancelled request released the permit its worker holds")
                    gate.set()
                    # The release follows the worker thread's end, so wait for it, bounded, then settle.
                    await asyncio.wait_for(released.wait(), 10)
                    self.assertTrue(await settle(lambda: sem._value == 2))
                finally:
                    gate.set()
        asyncio.run(main())
        self.assertEqual(sem._value, 2)

    def test_cancel_as_slot_is_granted(self):
        """A slot is granted and the waiting request is cancelled in the same loop turn: the request ends
        cancelled, does no file work and keeps no permit."""
        homes, served = [], []

        def home():
            homes.append(1)
            return "/srv/no-home"
        self.wire(home=home)
        handler = self.handlers[FILE]
        sem = handler.__self__.sem

        def serve(*a):
            served.append(1)
            return (500, "internal", None, None)

        async def main():
            await sem.acquire()
            await sem.acquire()
            task = asyncio.ensure_future(handler(Request(PATHS[0])))
            self.assertTrue(await settle(lambda: waiting(sem) == 1))
            sem.release()
            task.cancel()
            with self.assertRaises(asyncio.CancelledError):
                await task
            self.assertEqual(sem._value, 1, "the cancelled request kept the granted slot")
            sem.release()
        with mock.patch.object(self.mod.media_core, "serve", serve):
            asyncio.run(main())
        self.assertEqual((homes, served), ([], []))
        self.assertEqual(sem._value, 2)

    def control_run(self, exc):
        """A worker that raises exc after its request was cancelled. Returns the loop's exception-handler
        contexts, after a collection, and the final free count."""
        self.wire()
        handler = self.handlers[FILE]
        sem = handler.__self__.sem
        gate, contexts = threading.Event(), []

        async def main():
            loop = asyncio.get_running_loop()
            loop.set_exception_handler(lambda lp, context: contexts.append(context))
            entered, released = asyncio.Event(), asyncio.Event()
            real_release = sem.release

            def release():
                real_release()
                released.set()
            sem.release = release

            def serve(*a):
                loop.call_soon_threadsafe(entered.set)
                if not gate.wait(10):
                    return (500, "internal", None, None)
                raise exc()
            with mock.patch.object(self.mod.media_core, "serve", serve):
                try:
                    task = asyncio.ensure_future(handler(Request(PATHS[0])))
                    await asyncio.wait_for(entered.wait(), 10)
                    task.cancel()
                    with self.assertRaises(asyncio.CancelledError):
                        await task
                    gate.set()
                    await asyncio.wait_for(released.wait(), 10)
                    del task
                    gc.collect()
                finally:
                    gate.set()
        try:
            asyncio.run(main())
        finally:
            gc.collect()
        return contexts, sem._value

    def test_control_exception_after_cancel_is_observed(self):
        for exc in (KeyboardInterrupt, SystemExit):
            with self.subTest(exc.__name__):
                contexts, free = self.control_run(exc)
                self.assertEqual([c.get("message") for c in contexts], [])
                self.assertEqual(free, 2)

    def test_control_exception_reaches_live_awaiter(self):
        """The awaiting request gets the worker's own exception object, and nothing is left unobserved."""
        for raised in (KeyboardInterrupt(), SystemExit(3)):
            with self.subTest(type(raised).__name__):
                self.wire()
                handler = self.handlers[FILE]
                contexts, caught = [], []

                def serve(*a):
                    raise raised

                async def main():
                    asyncio.get_running_loop().set_exception_handler(lambda lp, c: contexts.append(c))
                    try:
                        await handler(Request(PATHS[0]))
                    except (KeyboardInterrupt, SystemExit) as e:
                        caught.append(e)
                with mock.patch.object(self.mod.media_core, "serve", serve):
                    asyncio.run(main())
                gc.collect()
                self.assertEqual(len(caught), 1)
                self.assertIs(caught[0], raised, "the awaiter got a replacement, not the worker's exception")
                self.assertEqual([c.get("message") for c in contexts], [])
                self.assertEqual(handler.__self__.sem._value, 2)


class LogTest(Base):
    def test_logger_name_fixed(self):
        put_modules(self, fake_modules())
        mod = load(self, NAME + "__home_0123abcd")
        mark = len(ROOT_RECORDS)
        mod.register(types.SimpleNamespace())
        records = [r for r in ROOT_RECORDS[mark:] if r.getMessage().startswith("apofis-media:")]
        self.assertEqual([r.name for r in records], [NAME])

    def test_templates_and_levels(self):
        put_modules(self, fake_modules())
        mod = load(self)
        m = self.mark()
        mod.factory(Application(), Adapter())
        mod.register(types.SimpleNamespace())
        frozen = Application()
        frozen.frozen = True
        mod.factory(frozen, Adapter())
        mod.factory(types.SimpleNamespace(router=Router()), Adapter())
        self.assertEqual(self.logged(m), [
            (logging.INFO, "apofis-media: ready"),
            (logging.WARNING, "apofis-media: not ready (no_platform_handler)"),
            (logging.WARNING, "apofis-media: not ready (restart_needed)"),
            (logging.WARNING, "apofis-media: not ready (hermes_incompatible)"),
        ])
        self.assertEqual({r.name for r in PLUGIN_RECORDS[m:]}, {NAME})


PARAGRAPH = (
    "Installing this add-on lets anyone with this server's API key fetch files by path from this profile's"
    " `cache/generated/images/` and `cache/images/` folders, even files never mentioned in a chat. The second"
    " folder can contain received pictures. The read happens on the gateway host, even with a Docker terminal."
    " It needs no model turn or tool approval and adds no chat transcript entry. Hermes must allow the path."
    " The add-on returns at most 10 MiB from a regular file with a supported picture name and a PNG, JPEG, GIF"
    " or WebP signature. It does not sanitize the bytes, prove who made the file, or return a snapshot from the"
    " time of the reply. Files elsewhere are not served. Treat the key as an owner credential and keep private"
    " pictures out of these delivery folders if other clients hold that key.")
SENTENCE = ("A link or hard link that resolves into one of these folders is served like any file there; the"
            " add-on trusts how you administer your Hermes home.")


PASS_RULE = "A pass needs both: nine `PASS` lines and exit status 0."
PINNED = "When it says Add-on needed on a pinned release build, it shows the exact command for that build."
EXIT_LINE = 'python3 -I <Location>/verify.py --port N [--host IP] [--home DIR]; echo "exit=$?"'
NO_ROUTES = ("`V1` to `V8` `FAIL` with `V9` `PASS`: the verifier did not verify any of the four expected refusals, so"
             " your key was not sent.")
WRONG_KEY_PICTURE = ("`V1` to `V4` `PASS`, then `V5` to `V8` `FAIL` with `V9` `PASS`: the verifier verified all four"
                     " expected refusals, but no keyed check passed.")
STOPS = (
    "Stop here, without a restart, if the Location names another home, if the install was refused, if it says the"
    " plugin stays installed but disabled or was installed but not enabled, or if any error shows.",
    "If it then still does not say Ready: stop.",
    "Do not go on to the verifier and do not restart again.",
    "On a mismatch, a refusal, a disabled result or an error: stop, no restart.",
)
INSTALL_ORDER = (
    "Check the installer's output before anything else.",
    "Only after both lines were seen, restart the gateway once:",
    "In the app, open Settings, Pictures and tap Check again.",
    "Run the verifier only after the app said Ready.",
)
NO_FORCE = ("Do not add `--force`.", "Run the prepared command, without `--force`,",
            "Do not update with `--force`:")
KEY_GATE = "Your key is sent only after `V1` to `V4` all passed; otherwise `V5` to `V8` fail without a request."


def _flat(lines):
    return " ".join(" ".join(lines).split())


class ReadmeTest(unittest.TestCase):
    def readme(self):
        with open(os.path.join(HERE, "README.md"), encoding="utf-8") as f:
            return f.read()

    def test_consent_paragraph_word_for_word(self):
        lines = self.readme().splitlines()
        found = False
        i = 0
        while i < len(lines):
            if not lines[i].startswith(">"):
                i += 1
                continue
            j = i
            while j < len(lines) and lines[j].startswith(">"):
                j += 1
            quote = _flat(line[1:] for line in lines[i:j])
            k = j
            while k < len(lines) and not lines[k].strip():
                k += 1
            end = k
            while end < len(lines) and lines[end].strip():
                end += 1
            if quote == PARAGRAPH:
                self.assertEqual(_flat(lines[k:end]), SENTENCE)
                found = True
            i = j
        self.assertTrue(found, "the consent paragraph is not a block quote in README.md")

    def test_pass_rule(self):
        self.assertIn(PASS_RULE, _flat(self.readme().splitlines()))

    def test_command_only_on_a_pinned_build(self):
        self.assertIn(PINNED, _flat(self.readme().splitlines()))

    def test_exit_status_read_after_the_run(self):
        self.assertIn(EXIT_LINE, [line.strip() for line in self.readme().splitlines()])

    def test_reading_no_add_on_routes(self):
        self.assertIn(NO_ROUTES, _flat(self.readme().splitlines()))

    def test_reading_key_refused_by_the_add_on_listener(self):
        self.assertIn(WRONG_KEY_PICTURE, _flat(self.readme().splitlines()))

    def test_stops_without_a_restart(self):
        text = _flat(self.readme().splitlines())
        for sentence in STOPS:
            with self.subTest(sentence[:40]):
                self.assertIn(sentence, text)

    def test_install_step_order(self):
        text = _flat(self.readme().splitlines())
        at = [text.find(sentence) for sentence in INSTALL_ORDER]
        self.assertNotIn(-1, at, "an install step sentence is missing")
        self.assertEqual(at, sorted(at), "output check, restart, Check again and the verifier are out of order")

    def test_never_force(self):
        text = _flat(self.readme().splitlines())
        for sentence in NO_FORCE:
            with self.subTest(sentence):
                self.assertIn(sentence, text)

    def test_key_sent_only_after_the_four_negative_checks(self):
        self.assertIn(KEY_GATE, _flat(self.readme().splitlines()))

    def test_readme_has_no_dash_characters(self):
        text = self.readme()
        self.assertNotIn("\u2013", text)
        self.assertNotIn("\u2014", text)


if __name__ == "__main__":
    unittest.main()
