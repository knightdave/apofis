"""Opt-in named check (Task 1 Step 5): the README's hook command, rendered for a throwaway home, run
through Hermes' own shell-hook runner (agent.shell_hooks.run_once) for both events.

Run on the Hermes host only, never in CI:
  /home/hermes/.hermes/hermes-agent/venv/bin/python -I -B server/apofis-push/hermes_wire_check.py [--keep]

It never touches the real Hermes home: a fresh HOME and HERMES_HOME under ~/tmp, a private WAL
database, a loopback fake ntfy (records name only the loopback origin and the run's install id).
It checks, by stat only, that no file changed under the Hermes install tree
(/home/hermes/.hermes/hermes-agent, recursively), under /home/hermes/.hermes/agent-hooks
(recursively) and the two hook configuration files (config.yaml, shell-hooks-allowlist.json). It does
not cover the rest of /home/hermes/.hermes, which the running gateway writes to. Every hook child is
accounted for by the suite's own `account` routine; the throwaway home is removed afterwards, whatever
the outcome, unless accounting fails (then it is kept and reported) or --keep is given.
"""
import sys

sys.dont_write_bytecode = True

import http.server  # noqa: E402
import json  # noqa: E402
import os  # noqa: E402
import re  # noqa: E402
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
sys.path.insert(0, HERE)
from apofis_push import CHILD_HARD  # noqa: E402
from test_apofis_push import Quarantine, account, slots_free  # noqa: E402  (one cleanup routine for suite and check)
INSTALLED = "/home/hermes/.hermes/agent-hooks/apofis_push.py"
RELEASE_OPTS = "--allow-origin https://ntfy.sh"
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


def readme_command():
    """The one command the README installs: identical in all four places (two hooks, two approvals)."""
    with open(os.path.join(HERE, "README.md"), encoding="utf-8") as f:
        text = f.read()
    yaml_cmds = re.findall(r'- command: "([^"]+)"', text)
    json_cmds = re.findall(r'"command": "([^"]+)"', text)
    assert len(yaml_cmds) == 2 and len(json_cmds) == 2 and len(set(yaml_cmds + json_cmds)) == 1, \
        (yaml_cmds, json_cmds)
    cmd = yaml_cmds[0]
    assert cmd == "python3 -I %s hook %s" % (INSTALLED, RELEASE_OPTS), cmd
    return cmd


def tree_state():
    """(path -> (mtime_ns, size, inode)) for the scope in the module docstring; stat only."""
    state = {}
    for top in (AGENT, HERMES + "/agent-hooks"):
        for root, dirs, files in os.walk(top):
            for name in dirs + files:
                p = os.path.join(root, name)
                try:
                    st = os.lstat(p)
                except OSError:
                    continue
                state[p] = (st.st_mtime_ns, st.st_size, st.st_ino)
    for p in (HERMES + "/config.yaml", HERMES + "/shell-hooks-allowlist.json", HERMES + "/agent-hooks"):
        try:
            st = os.lstat(p)
            state[p] = (st.st_mtime_ns, st.st_size, st.st_ino)
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


def wait(cond, what):
    end = time.monotonic() + WATCHDOG
    while time.monotonic() < end:
        if cond():
            return
        time.sleep(0.02)
    raise SystemExit("FAIL: timed out waiting for " + what)


def teardown(slots, script, stop, home, keep, settle=WATCHDOG, hold=CHILD_HARD + WATCHDOG):
    """Release the fake (`stop`) and the home only after every sender slot is confirmed free. Accounting
    (`settle` seconds for a normal exit) and, after a failed accounting, waiting for the slots (up to
    `hold`, past the child's hard deadline) both hold back an interrupt until they finish. Failed
    accounting keeps the home; slots still held after `hold` also keep the fake. Both exit 1."""
    interrupted = None
    quarantine = None
    while True:
        try:
            if quarantine is None:
                account(slots, script, settle)
                break
            free = slots_free(slots, hold)
            break
        except KeyboardInterrupt as e:
            interrupted = e
        except Quarantine as e:
            quarantine = e
    if quarantine is None:
        stop()
        if not keep:
            shutil.rmtree(home)
        if interrupted:
            raise interrupted
        return
    if free:
        stop()
        print("FAIL: %s; slots freed later; kept the throwaway home %s" % (quarantine, home))
    else:
        print("FAIL: %s; slots still held after %ss; kept the throwaway home %s and the fake's port until exit"
              % (quarantine, hold, home))
    raise SystemExit(1)


def main():
    keep = "--keep" in sys.argv[1:]
    readme = readme_command()
    before = tree_state()
    base = os.path.expanduser("~/tmp")
    os.makedirs(base, exist_ok=True)
    t = tempfile.mkdtemp(prefix="apofis-wire-", dir=base)
    home = os.path.join(t, ".hermes")
    state = os.path.join(home, "agent-hooks")
    os.makedirs(state)
    script = os.path.join(state, "apofis_push.py")
    slots = [os.path.join(state, "apofis-push.slot%d.lock" % i) for i in (0, 1)]
    shutil.copy(os.path.join(HERE, "apofis_push.py"), script)
    os.chmod(script, 0o755)
    fake = Fake()
    origin = "http://127.0.0.1:%d" % fake.port
    db = sqlite3.connect(os.path.join(home, "state.db"), isolation_level=None)
    try:
        db.execute("PRAGMA journal_mode=WAL")
        db.executescript("""
            CREATE TABLE system_prompts (hash TEXT PRIMARY KEY, prompt TEXT NOT NULL);
            CREATE TABLE sessions (id TEXT PRIMARY KEY, source TEXT NOT NULL, title TEXT, model_config TEXT,
                system_prompt TEXT, system_prompt_hash TEXT, parent_session_id TEXT, started_at REAL NOT NULL,
                ended_at REAL, end_reason TEXT, archived INTEGER NOT NULL DEFAULT 0,
                hidden INTEGER NOT NULL DEFAULT 0);
        """)
        now = time.time()
        rid = "apofis-push-%s-1" % INSTALL
        db.execute("INSERT INTO sessions (id, source, started_at) VALUES (?, 'api_server', ?)", (APP, now))
        db.execute("INSERT INTO sessions (id, source, hidden, title, system_prompt, started_at) "
                   "VALUES (?, 'api_server', 1, ?, ?, ?)",
                   (rid, rid + " op-0123456789abcdef",
                    json.dumps({"v": 1, "endpoint": origin + "/upWIRECHECK012?up=1"}), now))
        env = {"HOME": t, "HERMES_HOME": home, "PATH": GATEWAY_PATH}
        # The README command with only the fixture path and the agent-run options substituted.
        command = readme.replace(INSTALLED, script).replace(
            RELEASE_OPTS, "--allow-http-origin %s --delay 0 --only-install %s" % (origin, INSTALL))
        print("throwaway home:", t)
        print("README command:", readme)
        print("rendered command:", command.replace(t, "<T>").replace(origin, "http://127.0.0.1:<port>"))

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
            assert account(slots, script) == "ended"
            assert len(fake.requests) == n + 1, fake.requests
            path, headers, body = fake.requests[-1]
            assert path == "/upWIRECHECK012?up=1", path
            assert headers.get("x-unifiedpush") == "1", headers
            assert body == json.dumps({"session_id": APP, "kind": kind}, separators=(",", ":")).encode(), body
            print("  POST ok:", body.decode())

        # The ~/.hermes/... form: Hermes expands only a leading ~, so after "python3 -I" it stays literal;
        # Python cannot open the script, so no hook process and no child exist (slots untouched).
        n = len(fake.requests)
        slot_state = {e.name: e.stat().st_mtime_ns for e in os.scandir(state)}
        tilde = command.replace(script, "~/.hermes/agent-hooks/apofis_push.py")
        r = run_once(EVENTS[0][0], tilde, EVENTS[0][1])
        print("tilde form:", {k: r[k] for k in ("returncode", "timed_out", "error")}, r["stderr"].strip()[:120])
        assert r["returncode"] == 2 and not r["timed_out"] and "can't open file" in r["stderr"], r
        assert {e.name: e.stat().st_mtime_ns for e in os.scandir(state)} == slot_state and len(fake.requests) == n

        with open(os.path.join(state, "apofis-push.log")) as f:
            print("hook log:\n" + f.read().replace(origin, "http://127.0.0.1:<port>"), end="")
    finally:
        try:
            teardown(slots, script, fake.httpd.shutdown, t, keep)  # first: nothing may skip accounting
        finally:
            db.close()
    after = tree_state()
    changed = sorted(p for p in set(before) | set(after) if before.get(p) != after.get(p))
    assert not changed, "Hermes install tree changed: %s" % changed[:20]
    print("unchanged by stat: %s (recursive), %s/agent-hooks (recursive), config.yaml, "
          "shell-hooks-allowlist.json (%d entries)" % (AGENT, HERMES, len(after)))
    print("PASS" + ("" if keep else " (throwaway home removed)"))


if __name__ == "__main__":
    main()
