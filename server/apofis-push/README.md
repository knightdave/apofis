# apofis-push: the Hermes hook that wakes Apofis

## What it does

When a reply the phone started finishes, or Hermes asks for an approval, Hermes runs this script
from its `hooks:` block. The script POSTs a contentless wake to the phone's ntfy endpoint:

```
POST https://ntfy.sh/up<12 characters>?up=1
Content-Type: application/json
X-UnifiedPush: 1

{"session_id":"apofis-<uuid>","kind":"finished"}
```

`kind` is `finished` or `needs_input`; an API approval Hermes could not tie to a session sends
`{"kind":"needs_input"}`. The app never reads this body: any message only makes it run its own
check of the replies it started.

- **No Firebase.** `X-UnifiedPush: 1` makes ntfy treat the message as UnifiedPush, which skips its
  Firebase relay. ntfy hands it only to the ntfy app on the phone, over the app's own connection.
- **Privacy.** No title, text, command, tool name or run id is sent. ntfy.sh sees the session id,
  the time and the phone's topic. The log never holds endpoints, paths, record text or stdin.
- **Trust boundary.** A registration is a hidden Hermes session the app creates with the profile's
  API key. Anyone holding that key can create one with a topic of their choice, so the hook would
  also wake that topic, and many such records can crowd out real phones. ntfy does not tie a topic
  to one phone: whoever subscribes to the topic receives the session ids, event kinds and timing.
  The origin allowlist still stops a record from sending anywhere but `https://ntfy.sh`.
- **Never blocks Hermes.** The hook returns at once and does no work on the database. It hands the
  work to at most two background children per Hermes home and drops the wake when both are busy (the
  app's 15 minute check covers it). A child starts no new request after 20 s and is ended by the
  kernel at 25 s whatever it is doing (except I/O the kernel itself cannot interrupt). Each request gets 5 s. Errors are swallowed and the hook prints nothing.
- **Read-only.** The child opens `state.db` with a read-only SQLite connection, reads the phone
  registrations (hidden `apofis-push-*` sessions), closes it before any network, and never writes or
  deletes records; records older than 90 days are ignored. Like any SQLite reader of a WAL database,
  SQLite may create or reuse the `-wal` and `-shm` files next to it; that never changes the
  database's content. Its own files (two slot locks and the log) live in `~/.hermes/agent-hooks/`.
  It sends only to an allowed https origin (here `https://ntfy.sh`), never follows redirects and
  ignores proxy settings.
- **Limits.** At most 32 phones are served; `regs=32+` in the log means more than 32 were found and
  only the newest 32 were woken. If Hermes holds more than 4096 push records from the last 90 days,
  only the newest 4096 are considered.

## Requirements

- `python3` 3.9 or newer on the gateway's `PATH` (standard library only), Linux.
- Tested against Hermes Agent `a8fed44bbe`.

## Install (owner only)

**Install only after the agent reports the emulator pass done.** On c3po, from an up-to-date
`main`:

Order: the script is in place and verified before any hook entry exists, so no hook can point at a
missing script. Every step only adds what is missing, so re-running an interrupted install from the
step that failed converges. Each `Verify:` command prints `ok` exactly when the step is done.

1. In the shell you use for this, define the hook command and two helpers (also used by Rollback).
   `cfg FILE` prints each config entry for the command with the event line above it; `apr FILE`
   prints the events approved for the command. Both print nothing for an absent file and fail for an
   unreadable or malformed one:

   ```sh
   c='python3 -I /home/hermes/.hermes/agent-hooks/apofis_push.py hook --allow-origin https://ntfy.sh'
   cfg() { [ ! -e "$1" ] || { grep -B1 -F "$c" "$1"; [ $? -le 1 ]; }; }
   apr() { [ ! -e "$1" ] || python3 -I -c 'import json, sys; a = json.load(open(sys.argv[1]))["approvals"]; print("\n".join(sorted(e["event"] for e in a if e.get("command") == sys.argv[2])))' "$1" "$c"; }
   ```

2. Make a backup set for this installation. `mkdir` without `-p` fails if the name exists, so a
   retry never overwrites an earlier set; the set counts only once `COMPLETE` exists. Stop on any error:

   ```sh
   h=~/.hermes; b=$h/apofis-push-backup-$(date +%Y%m%d%H%M%S)
   mkdir "$b" || exit 1
   for f in agent-hooks/apofis_push.py config.yaml shell-hooks-allowlist.json; do
     if [ -e "$h/$f" ]; then cp -p "$h/$f" "$b/$(basename "$f")" || exit 1; fi
   done
   touch "$b/COMPLETE"
   ```

   Verify: `[ -e "$b/COMPLETE" ] && echo ok`

3. Put the script in place:

   ```sh
   mkdir -p ~/.hermes/agent-hooks
   install -m 0755 server/apofis-push/apofis_push.py /home/hermes/.hermes/agent-hooks/apofis_push.py
   ```

   Verify: `cmp server/apofis-push/apofis_push.py ~/.hermes/agent-hooks/apofis_push.py && echo ok`

4. Add to `~/.hermes/config.yaml` whichever of these two entries is not there yet (keep every other
   `hooks:` entry):

   ```yaml
   hooks:
     on_session_end:
       - command: "python3 -I /home/hermes/.hermes/agent-hooks/apofis_push.py hook --allow-origin https://ntfy.sh"
         timeout: 5
     on_human_input_request:
       - command: "python3 -I /home/hermes/.hermes/agent-hooks/apofis_push.py hook --allow-origin https://ntfy.sh"
         timeout: 5
   ```

   Use the absolute path exactly as shown: Hermes expands `~` only at the very start of a command,
   so `python3 -I ~/.hermes/...` would not find the script.

   Verify: `x=$(cfg ~/.hermes/config.yaml) && [ "$(echo "$x" | grep -cE '^ *(on_session_end|on_human_input_request):$')" = 2 ] && echo ok`

5. Approve both hooks in `~/.hermes/shell-hooks-allowlist.json` (mode 0600). Create the file if it
   does not exist; otherwise add whichever of the two entries is missing to `approvals` and keep the
   others. The command must be byte-identical to the one in `config.yaml`:

   ```json
   {
     "approvals": [
       {"event": "on_session_end", "command": "python3 -I /home/hermes/.hermes/agent-hooks/apofis_push.py hook --allow-origin https://ntfy.sh"},
       {"event": "on_human_input_request", "command": "python3 -I /home/hermes/.hermes/agent-hooks/apofis_push.py hook --allow-origin https://ntfy.sh"}
     ]
   }
   ```

   ```sh
   chmod 600 ~/.hermes/shell-hooks-allowlist.json
   ```

   Verify: `x=$(apr ~/.hermes/shell-hooks-allowlist.json) && [ "$x" = "$(printf 'on_human_input_request\non_session_end')" ] && echo ok`

The gateway picks up hook changes within about 30 seconds.

## Verify

- `hermes hooks list` shows both hooks as allowed (it only reads the configuration).
- After the phone has registered (Apofis, Settings, Notifications shows "ntfy"):

  ```sh
  python3 -I /home/hermes/.hermes/agent-hooks/apofis_push.py check --allow-origin https://ntfy.sh
  ```

  prints the read-only open and one line per phone, for example
  `apofis-push-0f8fad5b...-1 valid https://ntfy.sh age=0d`. It never prints the topic and makes no
  network request.
- After a reply finishes, `tail -n 5 ~/.hermes/agent-hooks/apofis-push.log` shows
  `on_session_end matched regs=1 https://ntfy.sh=200`.
- **Do not run `hermes hooks test` or `hermes hooks doctor` for this check.** `hermes hooks test
  <event>` runs every hook configured for that event, whether or not it is approved, with sample or
  custom payloads (`hermes_cli/hooks.py:179-221`). `doctor` runs every approved hook
  (`:309-325`). With the default samples this script happens to send nothing, but other hooks run
  too, and a custom payload file can make this one send.

Log outcomes: `matched` (with one `<origin>=<HTTP status or error>` per phone, or
`refused:<reason>=skipped` for a record that fails the endpoint rule), `skipped db` (database not
readable), `skipped db-busy` (locked past the busy timeout), `skipped slot` (the child could not verify or publish its slot), `skipped db-budget` (the read took over 1.5 s), `skipped session`
(not a chat the app started), `deadline regs=<n>` (requests still running at 20 s).

## Rollback

Use the backup set of the installation you undo, `b=~/.hermes/apofis-push-backup-<stamp>` (one with
`COMPLETE`), and the helpers from install step 1. Hook entries go first and are verified gone before
the script is touched; rollback never deletes the script, so no hook can point at a missing script. An entry counts as added by that
installation only if the backed-up file did not have it. Every step is safe to re-run, so an
interrupted rollback converges.

1. In `~/.hermes/config.yaml`, remove each `apofis_push.py` entry that `$b/config.yaml` did not have;
   keep everything else.
   Verify: `x=$(cfg ~/.hermes/config.yaml) && y=$(cfg "$b/config.yaml") && [ "$x" = "$y" ] && echo ok`
2. The same for `~/.hermes/shell-hooks-allowlist.json` against `$b/shell-hooks-allowlist.json`. If the
   backup has no allowlist and the file now approves nothing else, delete it.
   Verify: `x=$(apr ~/.hermes/shell-hooks-allowlist.json) && y=$(apr "$b/shell-hooks-allowlist.json") && [ "$x" = "$y" ] && echo ok`
3. Only when 1 and 2 print `ok`, and only if `$b/apofis_push.py` exists, put the earlier script back:
   `install -m 0755 "$b/apofis_push.py" ~/.hermes/agent-hooks/apofis_push.py`.
   Verify: `cmp "$b/apofis_push.py" ~/.hermes/agent-hooks/apofis_push.py && echo ok`
   Otherwise leave the script in place: a script no hook refers to is inert, and you may delete it
   (with its `apofis-push.*` files) by hand once `hooks:` in `config.yaml` no longer mentions it in any form.
4. Restore `$b/config.yaml` or `$b/shell-hooks-allowlist.json` whole only if nothing else changed in
   that file since, because a restore also undoes later, unrelated edits and approvals.

If the hooks do not register or unregister within a few minutes, restart the gateway when convenient.

## Development

Tests (also run in CI):

```sh
python3 -I -m unittest discover -s server/apofis-push -p 'test_*.py' -v
```

`vectors.json` holds the endpoint rule's test vectors, shared with the app's `EndpointTest`; keep it
byte-identical. `hermes_wire_check.py` is an opt-in check on the Hermes host that runs the rendered
hook command through Hermes' own hook runner in a throwaway home.
