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

1. Back up everything this installation replaces or edits, under one unique stamp, before
   changing anything. If any backup fails, stop here:

   ```sh
   stamp=$(date +%Y%m%d%H%M%S); h=~/.hermes; note=$h/apofis-push.install-$stamp
   mkdir -p $h/agent-hooks && : > $note || exit 1
   for f in agent-hooks/apofis_push.py config.yaml shell-hooks-allowlist.json; do
     if [ -e $h/$f ]; then cp -p --no-clobber $h/$f $h/$f.bak-apofis-$stamp && echo "backup $f" >> $note || exit 1
     else echo "absent $f" >> $note; fi
   done
   ```

2. Copy the script:

   ```sh
   install -m 0755 server/apofis-push/apofis_push.py /home/hermes/.hermes/agent-hooks/apofis_push.py
   ```

3. Merge into `~/.hermes/config.yaml` (keep any other `hooks:` entries):

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

4. Approve both hooks in `~/.hermes/shell-hooks-allowlist.json` (mode 0600). Create the file if it
   does not exist; otherwise add the two entries to `approvals` and keep the others. The command must
   be byte-identical to the one in `config.yaml`:

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

Use the newest `~/.hermes/apofis-push.install-<stamp>` note. Remove exactly the two `apofis_push.py`
entries from `config.yaml` and the two from the allowlist (if the note says the allowlist was
`absent` and it now holds only those two entries, delete it). For the script: if the note says
`backup agent-hooks/apofis_push.py`, restore `apofis_push.py.bak-apofis-<stamp>` over it; if it says
`absent`, delete it and the `apofis-push.*` files next to it. Restore a whole config or allowlist
backup only if nothing else changed in that file since, because a restore also undoes later,
unrelated edits and approvals. If the hooks do not register or unregister within a few minutes,
restart the gateway when convenient.

## Development

Tests (also run in CI):

```sh
python3 -I -m unittest discover -s server/apofis-push -p 'test_*.py' -v
```

`vectors.json` holds the endpoint rule's test vectors, shared with the app's `EndpointTest`; keep it
byte-identical. `hermes_wire_check.py` is an opt-in check on the Hermes host that runs the rendered
hook command through Hermes' own hook runner in a throwaway home.
