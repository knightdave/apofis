"""The apofis-media plugin for Hermes: two routes on the API server's aiohttp application, under the API
server key. GET /apofis-media/v1/ping says whether the plugin is ready; POST /apofis-media/v1/file answers
one picture from this profile's cache/generated/images/ or cache/images/ folder. The pure logic lives in
media_core. aiohttp and Hermes are imported inside functions, so loading the package imports neither.
"""
import asyncio
import contextvars
import inspect
import json
import logging

from . import media_core

# A fixed name, not __name__: a second Hermes home loads this package under a suffixed module name.
log = logging.getLogger("hermes_plugins.apofis_media")

PING = "/apofis-media/v1/ping"
FILE = "/apofis-media/v1/file"
WORKERS = 2
SLOT_WAIT = 5
NO_STORE = {"Cache-Control": "no-store"}
JSON = "application/json"


def _not_ready(reason):
    log.warning("apofis-media: not ready (%s)", reason)


def register(ctx):
    """Hermes entry point: ask the API server to call factory with its aiohttp application."""
    add = getattr(ctx, "register_platform_handler", None)
    if not callable(add):
        _not_ready("no_platform_handler")
        return
    add("api_server", factory)


def factory(native, adapter):
    """Add the routes to one API server application. Always returns normally: Hermes records a raising
    factory as wired and never calls it again."""
    try:
        _wire(native, adapter)
    except Exception:
        _not_ready("hermes_incompatible")


def _wire(native, adapter):
    # First: a frozen router (a plugin load after the API server started) takes no route at all.
    if getattr(native, "frozen", False) is True:
        return _not_ready("restart_needed")
    from aiohttp import web
    check = getattr(adapter, "_check_auth", None)
    if (not isinstance(native, web.Application) or not callable(native.router.add_route)
            or not callable(check) or inspect.iscoroutinefunction(check)):
        return _not_ready("hermes_incompatible")
    routes = _Routes(adapter, web)
    try:
        native.router.add_route("GET", PING, routes.ping)
    except Exception:
        return _not_ready("hermes_incompatible")
    try:
        from gateway.platforms.base import validate_media_delivery_path as validate
        from hermes_constants import get_hermes_home as home
        fits = callable(validate) and "path" in inspect.signature(validate).parameters and callable(home)
    except Exception:
        fits = False
    if not fits:
        return _not_ready("hermes_incompatible")
    routes.validate, routes.home = validate, home
    try:
        native.router.add_route("POST", FILE, routes.file)
    except Exception:
        return _not_ready("hermes_incompatible")
    routes.ready = True
    log.info("apofis-media: ready")


class _Routes:
    """The state of one factory call, so of one application: readiness, the worker permit and the Hermes
    functions. A later factory call for another app never shares it."""

    def __init__(self, adapter, web):
        self.adapter, self.web = adapter, web
        self.ready = False
        self.sem = asyncio.Semaphore(WORKERS)
        self.validate = self.home = None

    def _answer(self, body, content_type, status=200):
        # One complete body, so exactly one Content-Length: never streamed, chunked or compressed.
        return self.web.Response(status=status, body=body, content_type=content_type, headers=NO_STORE)

    def _error(self, status, code):
        return self._answer(media_core.error_body(status, code), JSON, status)

    def _auth(self, request):
        """None when the key is good, else the answer to send."""
        try:
            r = self.adapter._check_auth(request)
        except (AttributeError, TypeError):
            return self._error(503, "hermes_incompatible")
        except Exception:
            return self._error(500, "internal")
        if r is None:
            return None
        if isinstance(r, self.web.StreamResponse):
            return r
        return self._error(503, "hermes_incompatible")

    def _worker_done(self, fut):
        # Read the worker's outcome, so a raise in a worker whose request was cancelled reaches no default
        # handler; a live awaiter still gets the same exception through the shield.
        if not fut.cancelled():
            fut.exception()
        self.sem.release()

    async def ping(self, request):
        try:
            refused = self._auth(request)
            if refused is not None:
                return refused
            body = {"name": "apofis-media", "protocol": media_core.PROTOCOL, "ready": self.ready}
            if body["ready"]:
                body.update(max_bytes=media_core.MAX_BYTES, types=list(media_core.TYPES))
            else:
                body["reason"] = "hermes_incompatible"
            return self._answer(json.dumps(body, separators=(",", ":")).encode("utf-8"), JSON)
        except Exception:
            return self._error(500, "internal")

    async def file(self, request):
        try:
            return await self._file(request)
        except Exception:
            return self._error(500, "internal")

    async def _file(self, request):
        refused = self._auth(request)
        if refused is not None:
            return refused
        try:
            path = await media_core.parse_body(request.headers.get, request.content.read)
        except media_core.BodyRefused as e:
            return self._error(e.status, e.code)
        # The acquire is awaited in this task, not wrapped in another: a cancellation that arrives as a slot is
        # granted then ends this request, and the semaphore takes its slot back.
        try:
            async with asyncio.timeout(SLOT_WAIT):
                await self.sem.acquire()
        except TimeoutError:
            return self._error(503, "busy")
        held = True
        try:
            try:
                home = self.home()
            except (AttributeError, TypeError):
                return self._error(503, "hermes_incompatible")
            try:
                fut = asyncio.get_running_loop().run_in_executor(
                    None, contextvars.copy_context().run, media_core.serve, path, home, self.validate,
                    media_core.MAX_BYTES)
            except RuntimeError:
                return self._error(503, "unavailable")
            # The worker owns the permit from here: a cancelled request leaves it held until the thread ends.
            fut.add_done_callback(self._worker_done)
            held = False
            status, code, content_type, data = await asyncio.shield(fut)
        finally:
            if held:
                self.sem.release()
        if code is not None:
            return self._error(status, code)
        return self._answer(data, content_type)
