"""Opt-in named check (Task 1 Step 5): the README's hook command, rendered for a throwaway home, run
through Hermes' own shell-hook runner (agent.shell_hooks.run_once) for both events.

Run on the Hermes host only, never in CI:
  /home/hermes/.hermes/hermes-agent/venv/bin/python -I -B server/apofis-push/hermes_wire_check.py

It never touches the real Hermes home: a fresh HOME and HERMES_HOME under ~/tmp, a private WAL
database, a loopback fake ntfy, and a check that no file of the Hermes install tree changed.
"""
import sys

sys.dont_write_bytecode = True

import http.server  # noqa: E402
import json  # noqa: E402
import os  # noqa: E402
import shutil  # noqa: E402
import sqlite3  # noqa: E402
import subprocess  # noqa: E402
import tempfile  # noqa: E402
import threading  # noqa: E402
import time  # noqa: E402

HERMES = "/home/hermes/.hermes"
AGENT = HERMES + "/hermes-agent"
PY = AGENT + "/venv/bin/python"
# The gateway's PATH (H7, users/hermes/dots.nix hermesPath).
GATEWAY_PATH = ("/home/hermes/.local/bin:/home/hermes/.nix-profile/bin:/etc/profiles/per-user/hermes/bin:"
                "/run/wrappers/bin:/run/current-system/sw/bin")
HERE = os.path.dirname(os.path.abspath(__file__))
APP = "apofis-1b4e28ba-2fa1-11d2-883f-0016d3cca427"
INSTALL = "0f8fad5b-d9cb-469f-a165-70867728950e"
WATCHDOG = 30.0

CHILD = r"""
import json, sys
sys.dont_write_bytecode = True
sys.path.insert(0, %r)
from agent.shell_hooks import ShellHookSpec, run_once
r = run_once(ShellHookSpec(event=sys.argv[1], command=sys.argv[2], timeout=5), json.loads(sys.argv[3]))
print("WIRE:" + json.dumps({k: r[k] for k in ("returncode", "timed_out", "error", "stdout", "stderr")}))
""" % AGENT

EVENTS = [
    ("on_session_end", {"session_id": APP, "task_id": "t", "turn_id": "u", "completed": True, "failed": False,
                        "interrupted": False, "turn_exit_reason": "completed", "model": "m",
                        "platform": "api_server"}, "finished"),
    ("on_human_input_request", {"kind": "approval", "request_id": "r", "session_id": APP, "session_key": "",
                                "platform": "api_server", "prompt": ""}, "needs_input"),
]


def tree_state():
    """(path -> (mtime_ns, size)) for the install tree and the hook configuration files; stat only."""
    state = {}
    for root, dirs, files in os.walk(AGENT):
        for name in dirs + files:
            p = os.path.join(root, name)
            try:
                st = os.lstat(p)
            except OSError:
                continue
            state[p] = (st.st_mtime_ns, st.st_size)
    for p in (HERMES + "/config.yaml", HERMES + "/shell-hooks-allowlist.json", HERMES + "/agent-hooks"):
        try:
            st = os.lstat(p)
            state[p] = (st.st_mtime_ns, st.st_size)
        except FileNotFoundError:
            state[p] = None
    return state


class Fake:
    def __init__(self):
        outer = self
        self.requests = []

        class H(http.server.BaseHTTPRequestHandler):
            def do_POST(self):
                body = self.rfile.read(int(self.headers.get("Content-Length", "0")))
                outer.requests.append((self.path, {k.lower(): v for k, v in self.headers.items()}, body))
                self.send_response(200)
                self.send_header("Content-Length", "0")
                self.end_headers()

            def log_message(self, *a):
                pass

        self.httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), H)
        self.port = self.httpd.server_address[1]
        threading.Thread(target=self.httpd.serve_forever, daemon=True).start()


def free(path):
    import fcntl
    try:
        fd = os.open(path, os.O_RDWR)
    except FileNotFoundError:
        return True
    try:
        fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
        return True
    except OSError:
        return False
    finally:
        os.close(fd)


def wait(cond, what):
    end = time.monotonic() + WATCHDOG
    while time.monotonic() < end:
        if cond():
            return
        time.sleep(0.02)
    raise SystemExit("FAIL: timed out waiting for " + what)


def main():
    before = tree_state()
    base = os.path.expanduser("~/tmp")
    os.makedirs(base, exist_ok=True)
    t = tempfile.mkdtemp(prefix="apofis-wire-", dir=base)
    home = os.path.join(t, ".hermes")
    os.makedirs(os.path.join(home, "agent-hooks"))
    script = os.path.join(home, "agent-hooks", "apofis_push.py")
    shutil.copy(os.path.join(HERE, "apofis_push.py"), script)
    os.chmod(script, 0o755)
    fake = Fake()
    origin = "http://127.0.0.1:%d" % fake.port
    db = sqlite3.connect(os.path.join(home, "state.db"), isolation_level=None)  # held open: -wal and -shm exist
    db.execute("PRAGMA journal_mode=WAL")
    db.executescript("""
        CREATE TABLE system_prompts (hash TEXT PRIMARY KEY, prompt TEXT NOT NULL);
        CREATE TABLE sessions (id TEXT PRIMARY KEY, source TEXT NOT NULL, title TEXT, model_config TEXT,
            system_prompt TEXT, system_prompt_hash TEXT, parent_session_id TEXT, started_at REAL NOT NULL,
            ended_at REAL, end_reason TEXT, archived INTEGER NOT NULL DEFAULT 0, hidden INTEGER NOT NULL DEFAULT 0);
    """)
    now = time.time()
    rid = "apofis-push-%s-1" % INSTALL
    db.execute("INSERT INTO sessions (id, source, started_at) VALUES (?, 'api_server', ?)", (APP, now))
    db.execute("INSERT INTO sessions (id, source, hidden, title, system_prompt, started_at) "
               "VALUES (?, 'api_server', 1, ?, ?, ?)",
               (rid, rid + " op-0123456789abcdef",
                json.dumps({"v": 1, "endpoint": origin + "/upWIRECHECK012?up=1"}), now))
    env = {"HOME": t, "HERMES_HOME": home, "PATH": GATEWAY_PATH}
    command = "python3 -I %s hook --allow-http-origin %s --delay 0" % (script, origin)
    print("throwaway home:", t)
    print("rendered command:", command.replace(t, "<T>"))

    def run_once(event, command, kwargs):
        p = subprocess.run([PY, "-I", "-B", "-c", CHILD, event, command, json.dumps(kwargs)], env=env, cwd=t,
                           capture_output=True, text=True, timeout=120)
        lines = [ln for ln in p.stdout.splitlines() if ln.startswith("WIRE:")]
        if p.returncode != 0 or not lines:
            raise SystemExit("FALLBACK: Hermes could not be imported in isolation (exit %d): %s"
                             % (p.returncode, p.stderr[-2000:]))
        return json.loads(lines[-1][5:])

    for event, kwargs, kind in EVENTS:
        n = len(fake.requests)
        r = run_once(event, command, kwargs)
        print(event, r)
        assert r == {"returncode": 0, "timed_out": False, "error": None, "stdout": "", "stderr": ""}, r
        wait(lambda: len(fake.requests) > n, "the POST for " + event)
        wait(lambda: free(os.path.join(home, "apofis-push.slot0.lock"))
             and free(os.path.join(home, "apofis-push.slot1.lock")), "the child to end")
        assert len(fake.requests) == n + 1, fake.requests
        path, headers, body = fake.requests[-1]
        assert path == "/upWIRECHECK012?up=1", path
        assert headers.get("x-unifiedpush") == "1", headers
        assert body == json.dumps({"session_id": APP, "kind": kind}, separators=(",", ":")).encode(), body
        print("  POST ok:", body.decode())

    # The ~/.hermes/... form: Hermes expands only a leading ~, so after "python3 -I" it stays literal.
    n = len(fake.requests)
    tilde = "python3 -I ~/.hermes/agent-hooks/apofis_push.py hook --allow-http-origin %s --delay 0" % origin
    r = run_once(EVENTS[0][0], tilde, EVENTS[0][1])
    print("tilde form:", {k: r[k] for k in ("returncode", "timed_out", "error")}, r["stderr"].strip()[:120])
    assert r["returncode"] not in (0, None) and not r["timed_out"], r
    time.sleep(1.0)
    assert len(fake.requests) == n, "the tilde form must not post"

    with open(os.path.join(home, "apofis-push.log")) as f:
        print("hook log:\n" + f.read().replace(origin, "http://127.0.0.1:<port>"), end="")
    db.close()
    after = tree_state()
    changed = sorted(p for p in set(before) | set(after) if before.get(p) != after.get(p))
    assert not changed, "Hermes install tree changed: %s" % changed[:20]
    print("Hermes install tree unchanged (%d entries)" % len(after))
    print("PASS")


if __name__ == "__main__":
    main()
