# apofis-media

A Hermes plugin that lets the Apofis app show the pictures the agent saves and names in a reply. Without it the app shows such a picture as a file tile with "Copy file path".

## What it does

The plugin adds two routes to the Hermes API server, under the same API key the app already uses:

- `GET /apofis-media/v1/ping` says whether the plugin is installed and ready, with its protocol version and limits.
- `POST /apofis-media/v1/file` takes a JSON body `{"path": "<absolute path>"}` and answers the bytes of that picture.

It serves pictures from exactly two folders of the Hermes profile that answers the request: `cache/generated/images/` (where Hermes saves generated pictures) and `cache/images/` (its image cache, which also holds received pictures). The fence, in plain words:

- Hermes's own media delivery policy must allow the path first. The plugin then uses the file name Hermes resolved, never the name in the request.
- That resolved name must lie beneath one of the two real folders of the profile's Hermes home. A file beside them, in `cache/` itself, or anywhere else is refused.
- The request and the resolved name must both end in `.png`, `.jpg`, `.jpeg`, `.gif` or `.webp`.
- The file is opened folder by folder without following symbolic links, and it must be a regular file.
- The bytes must start with a PNG, JPEG, GIF or WebP signature. The content type of the answer comes from that signature.

Limits: at most 10 MiB (10,485,760 bytes) per picture; the four types png, jpeg, gif and webp; a request body of at most 8 KiB; two file reads at a time, and a request that waits more than 5 seconds for a free reader gets a "busy" answer.

Errors are fixed JSON bodies such as `{"error":{"code":"file_unavailable","message":"The file is not available."}}`. No answer echoes the request.

The plugin writes only two log lines: `apofis-media: ready` and `apofis-media: not ready (<reason>)`. It never logs a path, a file name, a request body, a key, a digest or the text of an error. Hermes itself may still log a path: with a Docker terminal, Hermes warns about every requested path that lies under no mounted container folder.

## What installing it means

> Installing this add-on lets anyone with this server's API key fetch files by path from this profile's `cache/generated/images/` and `cache/images/` folders, even files never mentioned in a chat. The second folder can contain received pictures. The read happens on the gateway host, even with a Docker terminal. It needs no model turn or tool approval and adds no chat transcript entry. Hermes must allow the path. The add-on returns at most 10 MiB from a regular file with a supported picture name and a PNG, JPEG, GIF or WebP signature. It does not sanitize the bytes, prove who made the file, or return a snapshot from the time of the reply. Files elsewhere are not served. Treat the key as an owner credential and keep private pictures out of these delivery folders if other clients hold that key.

A link or hard link that resolves into one of these folders is served like any file there; the add-on trusts how you administer your Hermes home.

## Requirements

- The Hermes API server is on (the app already needs it).
- Plugins run in process, which is the Hermes default.
- Compatibility: checked against the Hermes source at `a8fed44bbe` and with offline fakes in the tests. A live installation is the owner's first real run.
- A reverse proxy in front of the API server must forward the `/apofis-media/` prefix.
- The server URL in the app has no `/p/<profile>` part. The plugin's routes exist only at the top level, so a profile path never reaches them.

## Install (owner only)

Run the install on the gateway host, as the account the gateway runs as, with the gateway's `HERMES_HOME`. The installer writes into the `plugins` folder of the Hermes home of the account that runs it, so an install under another account or another Hermes home is never seen by the gateway. Before you run it, check `whoami` and `echo "$HERMES_HOME"` against the account and home the gateway runs with.

1. In the app, open Settings, Pictures. When it says Add-on needed on a pinned release build, it shows the exact command for that build. A release build can carry no pin (the packaging check leaves it empty when it fails), and then no command is shown: do not guess a revision. Use the template below only with the full 40-character commit of the intended release, checked independently, or stop. The command has this form:

   ```
   hermes plugins install knightdave/apofis/server/apofis-media --ref <commit> --enable
   ```

   Run it. If the install scan asks a question, read its findings and answer no when unsure. Do not add `--force`.

2. Check the installer's output before anything else. The panel's `Location` line must be `<the gateway's Hermes home>/plugins/apofis-media`, and a line must say `Plugin apofis-media enabled.` Stop here, without a restart, if the Location names another home, if the install was refused, if it says the plugin stays installed but disabled or was installed but not enabled, or if any error shows. The running gateway keeps working as before. Remove only a copy you confirmed this attempt installed: check its Location, then run `hermes plugins remove apofis-media` under that account and Hermes home; if nothing was installed, skip removal, and if the folder already existed or you cannot tell, stop and leave it alone. Fix the cause before repeating step 1 under the gateway's account and Hermes home.

3. Only after both lines were seen, restart the gateway once:

   ```
   hermes gateway restart
   ```

4. In the app, open Settings, Pictures and tap Check again. It should say Ready. If it says Could not verify, the gateway may still be starting: wait a few seconds and tap Check again once more. If it then still does not say Ready: stop. Look at the last `apofis-media:` line in `logs/gateway.log` written after this restart, if there is one. Do not go on to the verifier and do not restart again.

The installer's last line may say that the gateway reloaded its plugins and that the add-on is active in the running gateway now. For this add-on that is not true. The reload calls the plugin with the API server's router after the server has started, when no route can be added any more, and the plugin logs `apofis-media: not ready (restart_needed)`. Routes are added only when the API server starts, so the one restart of step 3 is needed anyway. A `not ready (restart_needed)` line from before the restart is that reload and says nothing about the new start.

The plugin's lines are normally in `logs/gateway.log` under the gateway's Hermes home. At a configured log level of WARNING the ready line is not written, so its absence proves nothing: readiness is checked in the app.

## Verify

Run the verifier only after the app said Ready. On the gateway host, as the gateway's account, run the installed `verify.py` by its path (the `Location` the installer printed, then `/verify.py`), and read its exit status right after it:

```
python3 -I <Location>/verify.py --port N [--host IP] [--home DIR]; echo "exit=$?"
```

- `--port N` (required): the port of this profile's own API server listener. Point it at that listener, or at a tunnel to it, never at another loopback service: the verifier sends your key there.
- `--host IP` (default `127.0.0.1`): a numeric loopback address. A name such as `localhost` is refused. If the API server does not listen on loopback, tunnel its port to a loopback port on the gateway host first and pass that port.
- `--home DIR` (default: two levels above the folder that contains `verify.py`, which is the Hermes home the add-on is installed in): the home whose `cache/images` folder holds the two test files.

Run it in a real terminal session: without a terminal to read the key from, it stops with `FAIL key`. It needs Python 3.10 or newer, because it treats a socket timeout as `TimeoutError`, which holds only from 3.10 on. It asks for the API key at the `API key:` prompt, without echo; nothing is created and nothing is sent before the key is read. A healthy run finishes in moments. A listener that accepts at once and then stays silent usually costs about 40 seconds in all. The verifier uses a 60-second deadline for accepting network results. A receive already in progress can finish after it; setup and cleanup are outside that deadline. It creates two test files in `<home>/cache/images`, so that folder must be private to you, with no other writer.

Three lines can stand alone, before any check runs:

- `FAIL arguments` (exit 2): an option it does not recognise or a value it refuses, for example a missing `--port`, one that is not a number or is outside 1 to 65535, a `--host` that is not a numeric loopback address, or a `--home` that cannot be resolved. Unambiguous abbreviations of the long options are accepted.
- `FAIL key` (exit 2): an empty key, a key that is not one line of printable ASCII, or no terminal, end of input or Ctrl-C at the prompt.
- `FAIL setup` (exit 1): a failure before the checks start, not a diagnosis of the folder. Causes include: `cache/images` is missing, or `cache` or `images` is a link; the wrong account or `--home`; a test file could not be created or written; the requests could not be prepared after the files were made; interrupt handling could not be set up; an interruption during setup; or another error before the first line. Hermes creates `cache/images` with mode 0700 the first time it uses its image cache. Only when you have confirmed that it is missing under the right home, with neither `cache` nor `images` a link, create it as the gateway's account and run the verifier again (create `cache` the same way first if that is missing too):

  ```
  mkdir -m 0700 -- "<Hermes home>/cache/images"
  ```

  If either is a link, stop and look at why before going on.

Otherwise it normally prints nine lines, each `PASS` or `FAIL`. If writing its output fails, printing stops, the lines already written remain, and the run cannot pass.

- `V1`: ping without a key is refused with 401 `gateway_auth_failed`.
- `V2`: ping with a random wrong key is refused the same way.
- `V3`: a file request without a key is refused the same way.
- `V4`: a file request with a random wrong key is refused the same way.
- `V5`: ping with your key says `apofis-media`, protocol 1, ready, a positive byte limit and `png` among its types.
- `V6`: the test PNG comes back as `image/png`, byte for byte.
- `V7`: the test text file with a `.png` name is refused with 415 `not_a_picture`.
- `V8`: a name that was never created is refused with 404 `file_unavailable`.
- `V9`: cleanup succeeded (both test files removed, every descriptor it opened closed) and no interrupt was seen when cleanup ended. Each file is removed only after a check that its name still points at the file the verifier created; that check comes before the removal and is not atomic with it. `V9` can be FAIL although both files are gone.

Your key is sent only after `V1` to `V4` all passed; otherwise `V5` to `V8` fail without a request. A pass needs both: nine `PASS` lines and exit status 0. An interrupt while the last line is written, or as normal Ctrl-C handling is being restored, can leave nine `PASS` lines with exit 1, so read the `exit=` line too. `exit=2` means nothing was created or sent; `exit=1` means the run started and something failed. The verifier never prints a body, a path or the key.

What a full pass establishes: under the private-folder precondition, these exchanges with the listener you selected and that cleanup. Both routes refused a missing and a wrong key; only then was your key sent, and the add-on reported protocol 1 and ready, returned the test PNG byte for byte, and refused the text file and the missing name with the expected codes. It does not establish who owns that port or authorization in general, nor the fence in general, that the folder is private, safety against a same-user rename during removal, how the model formats replies, how the app draws or caches pictures, or your proxy.

Each of the four negative requests that reaches Hermes's key check makes it write an "API server rejected invalid API key" warning. Four are expected; a request whose connection never got that far writes none.

### Reading a failed run

A `FAIL` line says that a check did not pass. It does not say why: the request may have been answered in another way, may have timed out, or may not have been sent at all (after a failed `V1` to `V4`, after the deadline, or after Ctrl-C). The patterns below name possible causes to check. None of them alone is a reason to reinstall, remove, restart again or change the key.

- `V1` to `V8` `FAIL` with `V9` `PASS`: the verifier did not verify any of the four expected refusals, so your key was not sent. Possible causes, to check in this order: the gateway is not running; `--port` or `--host` is not this profile's own API server listener or a tunnel to it (`--host ::1` for an IPv6 loopback listener); a proxy or another service answers there; the add-on is not installed, not enabled, or the gateway was not restarted once after the install. Hermes checks the key inside each route, so a path with no route gets the web framework's plain 404 before any key check.
- `V1` to `V4` `PASS`, then `V5` to `V8` `FAIL` with `V9` `PASS`: the verifier verified all four expected refusals, but no keyed check passed. Possible causes: the key you typed is not this listener's key; the connection was lost or the deadline passed after `V4`; the listener is not the one you meant. Before you type the key again, confirm that `--host` and `--port` name this profile's own API server listener. Then tap Check again in Settings, Pictures: Ready confirms that the app can now ping its configured server with its stored key, not why the earlier verifier run failed.
- `V1` and `V2` `PASS` with `V3` to `V8` `FAIL`: the verifier verified both ping refusals but neither file refusal, so it sent no keyed request. One cause is an add-on that is loaded but not ready (its ping exists, its file route does not); its `not ready` line in `logs/gateway.log`, written after this start, gives the reason. A connection lost after `V2` gives the same lines.
- After `V1` to `V4` pass, read each failed keyed check as a result the verifier could not verify: `V5`, the ready ping described above; `V6`, the exact test PNG as `image/png`; `V7`, a 415 refusal with `not_a_picture`; `V8`, a 404 refusal with `file_unavailable`. `V6` and `V7` `FAIL` with `V8` `PASS` fits a `--home` that is not the gateway's Hermes home, among other causes. If a failure repeats and nothing here explains it, record the lines and the exit status and report them.
- Ctrl-C does not undo an already decided result. Checks not yet decided fail; cleanup is attempted, and `V9` fails if the interrupt was seen at its cleanup decision. The run exits 1.
- If a run is cut off (a lost connection, a kill), two small files whose names start with `apofis-verify-` can remain in `cache/images`. They are harmless: remove them by hand, or leave them to Hermes's hourly image cache sweep, which removes files older than 24 hours there (unless a populated legacy `image_cache` folder is used in its place).

Known limits of the verifier:

- If the process is killed or receives an unhandled termination signal after fixture creation, it can leave one or both fixtures behind.
- A Ctrl-C during a connection attempt takes effect when the attempt ends, at most 10 s later: no request is sent after it.
- The verifier accepts single-line printable ASCII keys; Hermes can accept other configured keys that this verifier will refuse.
- Every answer must carry exactly one Content-Length; chunked, encoded and close-delimited answers fail.
- A process of the same user that renames or replaces a fixture between the identity check and the unlink can still win.

## Update

Prepare the new install before you remove anything:

1. Get the full 40-character commit of the new Apofis release from its release page, and check it independently: 40 hexadecimal characters, and the commit that release names.
2. Prepare the pinned command for that commit. Settings, Pictures shows the command only on a pinned release build while it says Add-on needed; in every other status, and on a build without a pin, it shows none. Then write the command from the template under Install with that commit; never guess a revision. If you cannot get and check the commit, stop and remove nothing.

Then:

3. `hermes plugins remove apofis-media`, on the gateway host as in Install. It must print `Plugin apofis-media removed from` followed by the `plugins` folder of the gateway's Hermes home. The running gateway still serves the old add-on until it restarts.
4. Run the prepared command, without `--force`, and check its output as in step 2 of Install. On a mismatch, a refusal, a disabled result or an error: stop, no restart. If you stop there, the old add-on is gone from disk and the running gateway keeps its old routes until its next restart. Before retrying the prepared update command, fix the cause and use the removal check in Install step 2; do not repeat step 4 over an existing folder. If you leave the update unfinished, tap Check again after the gateway next restarts: only Add-on needed on a pinned release build shows an install command; if you resume the update, use the commit you checked in step 1.
5. `hermes gateway restart`, once. Then check Ready in the app as in step 4 of Install.

Do not update with `--force`: it accepts a caution verdict of the install scan without asking.

## Rollback

`hermes plugins remove apofis-media`, then `hermes gateway restart`. The app then shows pictures that are not on the phone yet as file tiles again. Pictures already fetched stay on the phones.

## Development

The tests run offline, with fakes in place of aiohttp and Hermes and no network:

```
python3 -I -m unittest discover -s server/apofis-media -p 'test_*.py' -v
```

`media_core.py` holds the pure logic (body check, fence, bounded read, error bodies), `__init__.py` wires it into Hermes, and `verify.py` is the owner's verifier, which the plugin never imports.

If Hermes ever ships its own route for fetching delivered files, only the app's transport and the source of its status change; the picture folders and the app's picture store stay as they are.
