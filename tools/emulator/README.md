# End-to-end tests on an emulator

`run-e2e.sh` runs the end-to-end tests of the `:e2e` module on a headless Android emulator on another machine,
against the real Hermes server on the build host.

`:e2e` is a separate, self-instrumenting test APK (`com.android.test`): the tests run in their own process, so
they can clear, force-stop and relaunch the app without killing themselves. They drive the app through UI Automator
and accessibility actions, find elements by the app's own string resources, and run shell commands as the device's
shell user. Tests:

- `HarnessSelfTest` (no app, no server): the harness's own decisions (approval block detection, reply and step
  identity by test tags, tool-call, interrupted-result and steer-row parsing) against canned screens and server rows.
- `SmokeE2eTest` (no model run): connect through loopback, connect to a private address with local network access
  granted, and the local network flow (asked first for a literal private address; a refusal still tries; the failed
  attempt offers "Allow local network access and retry", which connects once access is granted).
- `SurvivalE2eTest` (one model run each): `connectAndChat`, `replySurvivesAirplaneMode`, `replySurvivesScreenOff`,
  `replySurvivesProcessDeath`, `offlineMessageSendsItself`, `steerDuringRun`, `stopDuringRun`. Each starts from
  cleared app data (`pm clear`, checked), grants `ACCESS_LOCAL_NETWORK` and connects. Every prompt's first line
  carries the run's nonce, so the chat's server title does. The chat's identity comes from the chat the test opened:
  it reads the open chat's key K from the chat screen's tag `chat.key:<K>` before sending, journals `send <K>
  <scenario>` before pressing Send, and at the scenario's end (also when it failed) writes its one completing line
  `chat <scenario> <K> <ids>`: from the app's database, only the row whose key is K, its server ids (current first,
  then earlier ones), or `none` when the app never created it on the server. No other row is read or journaled. Chat items are found only by the app's test tags (`user:<key>`,
  `reply:<key>/...`, `step:<replyKey>:<i>:<tool>`, `notice:<key>`, `composer.send`, `composer.stop`, `status.line`,
  `chat.key:<key>`, exposed as resource ids): a reply counts only as the reply item right after this scenario's user
  message item, holding the marker; the running step must be a `terminal` step of that reply whose own text is exactly
  the command. For steer, the items must be the prompt, its reply, the steering message, then one final reply, and
  that final reply is the one checked. Interruptions are asserted positively (airplane mode setting on and the status line saying
  Offline; `dumpsys power` read and not Awake; the pid read, then `pidof` succeeding with no result and `/proc/<pid>`
  gone). The server's record must agree (prompt once, one final answer with the marker, the exact terminal command;
  for stop, that call's own result interrupted; for steer, in this order: the prompt, the command, exactly one steer
  row of exactly the steering text, then the last final answer ending with BANANA, with no other user turn). The
  harness never approves anything: an approval block on screen fails the scenario without being touched.
- `PushE2eTest` (Milestone 3, see "Push" below): `connectorLifecycle`, `hostilePushes` and `permissionAndTestPush` (no
  model run), and `pushWakesKilledApp` (one model run). `--full` never runs them: they need push provisioning.
- `ShareE2eTest`, `M4E2eTest` and `ImageE2eTest` (Milestone 4, see "Milestone 4" below): sharing into the app,
  temporary chats and the per-chat model choice. `--full` never runs them.

## The two hosts

- **Build host**: has this repository, the Nix dev shell and Hermes at `http://127.0.0.1:8642`. It needs no KVM.
- **Emulator host** (default `obi-one`, override with `APOFIS_E2E_HOST`): any x86_64 machine with Nix (flakes),
  a usable `/dev/kvm`, passwordless `ssh`, about 15 GB free disk, 4 spare cores and 4 GB spare RAM.

## What a run does

0. Takes this host's lock (`flock`), then the emulator host's lock `~/tmp/apofis-e2e/.state/lock.d`: a directory
   holding one file, `owner`, with the run id. It is created complete (a new directory renamed into place, which never
   replaces an existing lock) and does not depend on any connection. Taking it again with the same run id succeeds, so
   a lost answer is simply asked again. A lock of another run is never taken over: the run stops before changing
   anything and prints how to check that run's processes and remove its lock by hand (see below). The cleanup removes
   the lock only when it holds this run's id. Records the listeners in the port ranges (5037, 5554-5585, 18642-18662)
   for the postcheck.
1. `rsync`s the working tree (minus `.git`, `.claude/` and everything in `.gitignore`) to `~/tmp/apofis-e2e/` and
   `git add`s it there, so the flake sees exactly the tracked and new files.
2. Opens a reverse tunnel `ssh -R 127.0.0.1:<port>:127.0.0.1:8642` (first free port from 18642) and checks with `ss`
   that the actual listener is `127.0.0.1:<port>` only. Its stdin is held by the script, so it closes when the script
   dies. The app reaches Hermes at `http://10.0.2.2:<port>`; `adb reverse` maps the same port to the device's
   loopback for the loopback smoke test and the tests' own read-only API calls.
3. On the emulator host, removes leftover run directories of interrupted runs, then in `nix develop .#emulator` builds
   and installs `app-debug.apk` and `e2e-debug.apk`. No key exists on the emulator host at this point, and Gradle
   never sees it.
4. Creates the AVD `apofis-e2e`, starts its own adb server (`adb -P 5037 nodaemon server`, stopped by pid) and the
   emulator with `env -i` (only paths it needs, all inside `~/tmp/apofis-e2e/.state`), `-no-metrics
   -crash-report-mode disabled`, `-no-window -no-snapshot -wipe-data -accel on -cores 4 -memory 4096`. Creates the
   empty session journal on the device.
5. Asks the build host for the key over the SSH session's stdin. A subshell reads it and pipes it to `adb shell`,
   whose device shell reads it and passes it to `am instrument -w -r -e apofisKey ...`. The key is never on a host
   command line, in an environment, in a file or in a long-lived shell variable on the emulator host. Every shell on
   either host that can receive key bytes runs with `ulimit -c 0` (checked) before the key is read.
6. Always, also on failure or interrupt: pulls the device files and a filtered logcat (tags `ApofisE2e`, `ApofisWake`, `ApofisShare`,
   `TestRunner`, `AndroidRuntime`, errors of `ActivityManager`), reports the session journal (only exact lines, a complete file, with a count),
   stops the emulator and adb server (every stop and check under `timeout` with a kill fallback), deletes the AVD (the
   app's storage, the only place the key rests, goes with it). Only when every writer is gone, it stages a fixed
   allowlist in `.state/runs/<run-id>/export/`: `instrument.txt`, `results.txt` (one line per test result file),
   `logcat.txt` and the screenshots named in the scenario list. Everything else of the run is deleted. The staged files
   are scanned for the key in raw, URL-encoded and base64 forms (patterns sent by the build host at that moment), then
   hashed and made read-only. Only when all of this succeeded does it send the checksums and, as its very last line,
   the completion token `COMPLETE <run id> <count>`. Without patterns (SSH lost), on a scan error or a match, the staged
   files are deleted and only a fixed code naming the stage is sent.
7. The build host copies the export only when the remote stage exited 0, its last output line is exactly this run's
   completion token and the checksum lines before it parse. A timeout, a dropped connection, a line after the token or
   anything else copies nothing. It copies exactly those names into `build/e2e/.incoming-<run-id>/` (never descending
   into a directory), verifies every checksum, that nothing else arrived, and scans the copy, then renames it to
   `build/e2e/<run-id>/` in one step. rsync writes its temporary files only inside the incoming
   directory. On any failure or interrupt it deletes everything inside the incoming directory, whatever the name
   (`find -mindepth 1 -delete`, then `rmdir`), and forgets the directory only once it is verified gone; a delete that
   fails ends the run non-zero, naming the directory to remove by hand. Before creating, deleting or renaming, `build`,
   `build/e2e` and the incoming directory must be real directories (no symbolic links), and the incoming directory's
   canonical path must be exactly `<repository>/build/e2e/.incoming-<run-id>`. A `build/e2e/<run-id>/` therefore exists
   only when fully verified. The cleanup ignores further INT, TERM and HUP from its first line.
8. Decides first whether the journal is complete. It needs positive evidence of what ran: the verified copy's
   `results.txt`, present, readable, non-empty and a results list; without it the run fails ("sessions may remain")
   whatever the journal says. Every scenario that passed must have a send line, and every
   scenario with a send line must have exactly one `chat` line for the same key with server ids (a `chat` line also
   needs its send). Otherwise the run fails ("sessions may remain"), and no server request is made for any incomplete
   or unmatched entry. Then deletes the sessions of the complete entries: each only after a `GET` on that exact id returns the same id, an `apofis-` id,
   with the run's nonce in its title (a turn still running is retried). For the steer scenario's session it also
   requires the steered turn in order (exactly one steer row with exactly the steering text, every ordinary user row
   before it, the last final answer after it ending with BANANA); for the journaled stopped run, `GET /v1/runs/<id>`
   must name that session and say `cancelled`. Anything else is left alone and fails. A missing or unreadable journal fails
   the run ("sessions may remain") with no server request at all.
9. Within one 240 s deadline: waits until no e2e process is left on the emulator host, removes this run's directory,
   releases the lock (only if it holds this run's id), and requires one token per fact with its expected value:
   `EMULATOR=absent ADB=absent REMOTE=absent AVD=absent RUNDIR=absent LOCK=released PORTS=closed DONE` (PORTS: no
   listener in the port ranges that was not there before). Every probe answers absent/present/unknown (`unknown`
   when any step failed or its output did not parse; each step's status is checked on its own, not a pipeline's last;
   ports are `closed` only after a parsed listing). A missing token,
   an `unknown` value or running out of time fails. Then it compares `~`, `~/.android`, `~/.config`, `~/.cache`, `~/.local` and the user's `/tmp` entries with
   before the run. The whole cleanup on the build host is limited to 15 minutes; past that it reports failure and
   exits instead of hanging.

No output of the emulator host ever reaches the terminal: every SSH call's output is captured and never printed (the
remote's stderr is discarded). The emulator host reports progress and failures as fixed codes, which the build host
prints as its own text; anything else is shown only as "an unexpected line (not shown)". The listing comparison
reports only a count. Test failures' stack traces are printed from the verified copy of `instrument.txt`.

`--full` passes only when every smoke test, every self-test (by name) and all seven scenarios ran and passed, and each
smoke and scenario test left its screenshot, a PASS line in `results.txt`, the interruption screenshots
(`2-airplane-offline.png`, `5-offline-waiting.png`) and logcat. A partial suite fails.

The remote side sends a heartbeat every 5 s; when the connection is gone it stops itself and cleans up (nothing can be
exported without the build host's scan patterns). Ctrl-C or SIGTERM on the build host stops the remote run the same
way and still waits for its cleanup and checks.

## Running

```sh
tools/emulator/run-e2e.sh --smoke                      # SmokeE2eTest and HarnessSelfTest, no model run
tools/emulator/run-e2e.sh --scenario stopDuringRun     # one survival scenario, one model run
tools/emulator/run-e2e.sh --test 'SmokeE2eTest#connectViaLoopback'
tools/emulator/run-e2e.sh --full                       # every e2e test but PushE2eTest and the Milestone 4 classes: about 7 model runs, 15 minutes
tools/emulator/run-e2e.sh --push                       # the push scenarios with a local fake ntfy, no model run
tools/emulator/run-e2e.sh --push-model                 # pushWakesKilledApp, connectAndChat, replySurvivesProcessDeath
tools/emulator/run-e2e.sh --m4                         # the Milestone 4 share and temporary chat scenarios: no live transport, no model run
tools/emulator/run-e2e.sh --m4-model                   # imageSendConfirms, temporaryChatGoneAfterLeaving, modelChoiceReachesRun, replySurvivesProcessDeath
tools/emulator/run-e2e-selftest.sh                     # the script's own safeguards, locally: no host, no key
```

The first run downloads the emulator and system image into the emulator host's Nix store (about 2 GB, a few
minutes) and Gradle's dependencies into `~/tmp/apofis-e2e/.state/gradle`. Later runs take a few minutes.

## Safety rules

- The API key is read from `~/.hermes/.env` on the build host and sent only when the instrumentation starts (see
  step 5). It is never printed. To test the leak path without the key, set `APOFIS_E2E_CANARY` to a string of at
  least 16 characters: it is added to the scan patterns, and a file containing it on the emulator host fails the run.
- The Connect screen sets `FLAG_SECURE`, so screenshots are taken only after connecting.
- On the emulator host everything lives in `~/tmp/apofis-e2e/` (Nix keeps using its own cache in the real home).
  No `sudo`, no services, no changes to the user's configuration. Emulator console, adb and the tunnel listen on
  127.0.0.1 only.
- Tests create Hermes sessions only through the app (ids start with `apofis-`), and the build host deletes only
  sessions whose identity it has proved (step 8). Prompts only run `sleep N # apofis-e2e-<n>` (the marker is a shell
  comment, so the command stays one segment and the app's running step shows it whole).

## Push (Milestone 3)

The push pass runs before the owner installs the hook, against Hermes as it is, with a local fake ntfy. Nothing it runs
contacts ntfy.sh or any other push host.

| Invocation | Selects | Push provisioning | Model starts |
|---|---|---|---|
| `--smoke` | `SmokeE2eTest`, `HarnessSelfTest` | no | 0 |
| `--full`, `--scenario <name>` | as before (never `PushE2eTest`), outside the ledger | no | as before |
| `--push` | `PushE2eTest` `connectorLifecycle`, `hostilePushes`, `permissionAndTestPush` | yes | 0 |
| `--push-model` | `PushE2eTest#pushWakesKilledApp`, `SurvivalE2eTest#connectAndChat`, `SurvivalE2eTest#replySurvivesProcessDeath` | the push method | at most 3 |
| `--test PushE2eTest#pushWakesKilledApp` | that method alone: gate 2 | the push method | at most 1 (reserves 1) |
| `--m4` | `ShareE2eTest#shareTextAndImageOpensNewChat`, `ShareE2eTest#pixelCapJustOverRefused`, `M4E2eTest#temporaryToggleMarks`, with no live transport (Milestone 4) | no | 0 |
| `--m4-model` | `ImageE2eTest#imageSendConfirms`, `M4E2eTest#temporaryChatGoneAfterLeaving`, `M4E2eTest#modelChoiceReachesRun`, `SurvivalE2eTest#replySurvivesProcessDeath` (Milestone 4) | no | at most 4 |
| `--test <Class#method>` | a push method or a method of `--push-model` or `--m4-model`: as its row, reserving 1; any other test of the module: as before | per row | per row |
| anything else (`--test PushE2eTest`, `--test ImageE2eTest`, `--test M4E2eTest`, a suite name such as `--test push-model` or `--test m4-model`, an unknown class, method or scenario, extra arguments) | refused before any action | | |

- **Identities.** Before anything else the build host mints one install id (a random UUID) per push scenario, the topic
  (`up` and 12 random letters and digits) and, once the tunnel port is known, a port free on both hosts. For each
  install it lists the server's records with `apofis_push.py check --state-db ~/.hermes/state.db --only-install <id>
  --ids --log -` and a run-private `HERMES_HOME`: the listing must succeed and be empty, or the run stops. This listing
  is the only thing that opens the live database, read-only.
- **The fake ntfy** (`fake_ntfy.py`) runs on the build host with a minimal environment in a run-private directory,
  listens on `127.0.0.1:<port>` only, and reaches the device through a second reverse tunnel and `adb reverse`. It
  queues messages for the topic (`POST /<topic>`, at most 64 of at most 4096 bytes) and hands the oldest to the tests
  (`GET /<topic>/poll`); at most 8 requests at once. `POST /trigger` (gate 2, at most 3 times, one at a time, only with
  an empty topic) runs the real hook in its private home with a fixed anonymous approval event against `fixture.db`, a
  synthetic database it builds in its directory with one hidden record of `pushWakesKilledApp`'s install naming the
  fake. It answers once the hook's detached child has released its slot and exited (killing its process group past
  30 s), and 200 only with proof that this invocation's child posted exactly once to this fake (one new hook log line
  `matched regs=1 <this origin>=200`, exactly one message received meanwhile, the fixed `{"kind":"needs_input"}`). On
  stop it admits no further trigger, waits for a hook parent still starting, and kills any child still holding a slot.
  The harness starts it as its own child and passes its own pid (`--owner-pid`): the fake starts only if that process
  is its parent, and checks about twice a second that it still is. If the harness dies without its cleanup, the fake
  is reparented, stops (bounded teardown, killing any hook child still holding a slot) and, when every child is
  accounted for, removes the files it created in its run directory; the harness's own temporary directory on the
  build host stays (as on main). The cleanup stops it, requires "stopped: child released|killed" and its port closed here (otherwise it
  fails and keeps the private directory for inspection); the postcheck covers the tunnel's port there.
- **The distributor** is the e2e APK (`FakeDistributor`, accepted only by debug builds): it answers the app's REGISTER
  with `NEW_ENDPOINT http://127.0.0.1:<port>/<topic>?up=1` and sends MESSAGE, UNREGISTERED and TEMP_UNAVAILABLE for the
  tests. `AppDriver.freshStart` writes the scenario's `install_id`, `e2e_nonce` (the run nonce) and `e2e_origin` into the
  cleared app's `shared_prefs/push.xml` and reads them back before the launch; anything else fails the scenario before
  push can be turned on. The debug app then titles every record `<id> op-<marker> e2e-<nonce>` and commits each create
  intent with the id's reservation. Nothing logs the push prefs, an endpoint or a token: diagnostics are fixed
  categories, and the distributor logs only a known action from the app.
- **What the scenarios claim.** The device checks the records it expects by id (exact id, source, hidden flag and title
  against the app's own intent, and for the current record the app's stored record and endpoint), compared and never
  logged; the exact namespace is the host's listing at the end of the run. For a foreign distributor's migration the
  claim is: Off in Settings, an UNREGISTER with the old token, no further REGISTER at the test distributor and no stored
  mention of the foreign package; where an outgoing REGISTER goes is covered by the app's unit tests. "No notification"
  means none is active after the delivery. Every quiet delivery needs a new push-caused wake check that took no run.
- **Intents and cleanup (rule C3).** At each push scenario's end (also on failure) and before every `pm clear`, the tests
  journal `pushintent <id> <title>` for every intent in the app's prefs, or `pushintent-missing <scenario>` when they
  cannot be read. The app never deletes a push record, so the harness's cleanup is what removes them: for each journaled
  intent whose id is of one of this run's installs and whose title ends with this run's nonce, `GET` (404: nothing to
  do), then only for `source == "api_server"` and exactly the journaled title, `DELETE` and `GET` 404. An install with a
  missing export is not cleaned; nothing is ever deleted without an intent. Then every install is listed again: any
  record left fails the run with "push records may remain", naming it, without deleting it.
- **The ledger.** Every model method's selection (`--push-model`, `--m4-model` and their `--test` selections) first
  reserves its proved upper bound (1 per method) in `~/.local/state/apofis-e2e/model-budget-m4` (Milestone 4; Milestone
  3's `model-budget-m3` is closed and stays untouched), shared by every worktree and appended under `flock`
  (`reserve <run id> <method> <bound>`), and refuses when the counted starts plus the new bounds would pass 10, the ledger
  is unreadable, not exactly in its two line forms or torn, or a proposal repeats a run and method or one already
  reserved (one parser reads existing and proposed entries). A reservation belongs to the invocation that made it:
  only it may change it. Once the key was handed to the device the reservation stays at its bound whatever was
  observed; it is released (`observed <run id> <method> 0`) only when the key never left the build host. Every
  model-capable run, ledgered or not (`--full`, `--scenario`,
  model-capable `--test`), prints how often `apofis_push.py` appears in `~/.hermes/config.yaml` and the allowlist (count
  only, "unknown" when a file cannot be read): with the hook installed, each model run can send one contentless wake
  naming a test chat to the owner's ntfy endpoint.
- **Logs.** The exported logcat adds the app's debug tag `ApofisWake` (one line per wake check: cause, runs, settled,
  failed, outcome; no ids).

## Milestone 4

| Invocation | Selects | Model starts (proved upper bound) |
|---|---|---|
| `--m4` | `ShareE2eTest#shareTextAndImageOpensNewChat`, `ShareE2eTest#pixelCapJustOverRefused`, `M4E2eTest#temporaryToggleMarks` | 0 |
| `--m4-model` | `ImageE2eTest#imageSendConfirms`, `M4E2eTest#temporaryChatGoneAfterLeaving`, `M4E2eTest#modelChoiceReachesRun`, `SurvivalE2eTest#replySurvivesProcessDeath` | 4 (one message each, no steer) |
| `--test M4E2eTest#modelPickIsReachable` | the model sheet through the slash palette: the alternative picked and stored, Server default restored, nothing sent | 0 (no reservation) |

- **The budget.** At most 10 model starts in the whole milestone, across all worktrees and retries, reserved in
  `~/.local/state/apofis-e2e/model-budget-m4` (see "The ledger" above): `--m4-model` and every single `--test` of a
  model method, `SurvivalE2eTest#replySurvivesProcessDeath` included. Settlement is Milestone 3's rule: a reservation is
  released only when the key never left the build host; once it was handed to the device it stays at its bound (a
  crash, a 404 at counting, a refusal before admission and `modelChoiceReachesRun` whatever its outcome). Observed starts
  come from the run's own log lines (the cleanup's session lines and each method's `ApofisE2e` lines), never from the
  server. `--m4-model` is reserved once: when the ledger already holds a reservation (released or not) of any of its
  methods, it is refused before any reservation. The check reads the ledger while this host's lock is held, which every
  invocation by this user takes before it reserves, so no other invocation can reserve or release in between. Retries then go method by method with `--test <Class#method>`, in the
  table's order (not enforced). During Milestone 4, `--full`, `--scenario <name>`, `--test SurvivalE2eTest` and `--test
  SurvivalE2eTest#<another method>` are not to be used: they still start model runs (and print the hook warning) but are
  outside the ledger, so their starts would not be counted.
- **No live transport (`--m4`).** Each method clears the app's data, connects through the emulator's host alias (this
  setup connect reaches the server), then turns airplane mode on and waits until the setting is on and the status line
  says Offline before its first action. It never sends and makes no request of its own. Its end clears the app's data
  (checked; it also stops the process) before airplane mode goes off, so nothing it did can reach the server on a
  reconnect. On the build host `--m4` fails when the session journal holds any line at all.
- **The share fixtures.** The e2e APK serves three generated PNGs from `FixtureImageProvider` (authority
  `xyz.rycerz.apofis.e2e.fixtures`, not exported, `grantUriPermissions`): 640 x 480 for the caption shares, 8001 x 8000
  (64,008,000 pixels, one row past the decoder's cap) and 8000 x 8000 (exactly the cap). They are solid 1-bit grayscale
  PNGs of a few kilobytes, written by a streaming encoder, never through a Bitmap; `pixelCapJustOverRefused` first checks
  through its own resolver that both large ones are within the byte cap and sized as named. The tests share them with
  `ACTION_SEND` and the read grant in ClipData, started from the test process. No app manifest names the provider
  (checked by the self-test), and the e2e APK's manifest is never merged into the app.
- **The share log check.** After `--m4` the exported logcat (tag `ApofisShare`, the app's debug line per refused image)
  must hold exactly one `ApofisShare: image <reason>` line, and its reason must be `TOO_LARGE`: the 8001 x 8000 share's.
- **The two reads.** After `--m4` passes, the build host reads `GET /v1/toolsets` and `GET /api/model/options` with the
  key, each reduced at once to fixed booleans: `image_gen_enabled` (an enabled toolset offers `image_generate`) and, for
  `providers`, `model`, `provider`, `slug`, `models`, `capabilities`, `usage`, `limit` and `warning`, whether the answer
  holds that key (at the top level or on a provider row). It logs only `<key>=true|false|unknown` (`unknown` for a
  non-200 answer or a body that does not parse); no body, value or status is printed or kept.
- **The model picker check.** `modelPickIsReachable` needs the slash palette (Task 8a-2), so it is in neither suite.
  Run it once as `--test M4E2eTest#modelPickIsReachable` (no reservation) before the first run that includes
  `modelChoiceReachesRun`: it opens the model sheet through the palette, picks the same alternative that method would,
  proves the chat's stored choice, restores Server default the same way and sends nothing. Both methods use one picker
  that proves the sheet gone and the stored choice before anything is sent. Both run with the driver's fixed
  diagnostics on for their whole length (setup, picker, Send, every wait): a failure names its step only, never the
  screen (the sheet lists model and provider names).
- **The choice outcome.** `modelChoiceReachesRun` logs one of `proved`, `served differs`, `not proved: no runtime`,
  `not proved: no alternative` (recorded results), or `not admitted: zero starts`, `unknown: run record unreadable`,
  `unproved failure, starts unknown` (failures). `not admitted: zero starts` rests on the app's own mark: the app marks a
  send as attempted before the request and clears the mark only on a definite refusal or a failure before the request
  left; the request may have reached the server and been refused. No word claims a status code. A run record that cannot
  be read is read again on a timeout, a dropped connection or a 5xx while time is left; an invalid or foreign record is
  never trusted; the identity-checked session evidence (counts only) is logged whatever the record gave.
- **Leaving a chat.** `imageSendConfirms` and `temporaryChatGoneAfterLeaving` leave their temporary chat only after
  the phone itself has settled the run (no run it follows, no pending send for the chat) and then every id the app will
  delete for it (its current id, its lineage and its key, as the app builds them) is journaled and proved this run's
  by a GET of that id with this run's nonce in its title; one unproved id fails the method before the leave, and
  nothing else runs between the proof and the tap. An id the server adds to that chat between the proof and the
  phone's delete (the app's refresh after a finish can still write the row) is deleted without its own proof; a chat
  of one short message gets no new id. After the leave, `temporaryChatGoneAfterLeaving` judges by the server's answers
  for each id, the phone's own row for that key and the note in the next chat; it does not observe the drawer: that
  the drawer draws only local rows is pinned by the app's unit tests.
- **Errors of the app.** The device hides error dialogs (`hide_error_dialogs`, so a launcher not responding just after
  boot cannot cover the app). The run therefore fails when the exported logcat holds the system's line for a fatal
  exception (`AndroidRuntime`) or a not-responding (`ActivityManager`, `ANR in`) of the app or its test process, with a
  fixed message and no log text. Another app's not-responding does not fail it; a force-stop or a kill (the survival
  test's) logs neither.
- **Temporary chats.** Their ids (`apofis-tmp-<uuid>`) are app ids for the journal and the cleanup. A journaled id that
  answers 404 at cleanup counts as gone (the app deleted it): it passes the cleanup, which is not by itself a pass of
  the scenario.
- **What the exported logcat names.** By design it names this run's test texts (prompts, captions) and journaled
  session ids: Task 13b needs the ids as proof of ownership. The harness's own new lines (the share check, the two
  reads, the ledger and the refusals) name none of them, and never a model or provider (checked with canaries by the
  self-test).

## Cleaning up by hand

Normally nothing is left: the script verifies it. If the postcheck cannot verify that the run's processes are gone,
the lock stays in place (`~/tmp/apofis-e2e/.state/lock.d`) and the script prints these commands. The script never
removes a lock it does not own, so a lock left by a dead run stays until you remove it:

```sh
ssh obi-one "pgrep -u \$(id -u) -af -- '[-]avd apofis-e2e|run-e2e[.]sh --(remote|in-shell)|adb -P 503[7] nodaemon'" # must print nothing
ssh obi-one 'rm -f ~/tmp/apofis-e2e/.state/lock.d/owner && rmdir ~/tmp/apofis-e2e/.state/lock.d'
```

Leftover run directories are removed by the next run. The AVD is deleted at the end of every run; a run finding one
left behind deletes it first.

To remove everything from the emulator host:

```sh
cd ~/tmp/apofis-e2e
nix develop .#emulator -c env ANDROID_AVD_HOME="$PWD/.state/avd" avdmanager delete avd -n apofis-e2e # if one is left
# then delete ~/tmp/apofis-e2e (run artifacts in .state/runs, Gradle caches in .state/gradle) with your file manager
# or rm; the emulator and image stay in the Nix store until the next garbage collection.
```

## Known limits

An emulator cannot test real push delivery through ntfy (the fake stands in for it), haptics, blur and rendering quality on real GPUs, OEM battery
optimisation and app killers, real mobile network handovers, or biometric hardware. Airplane mode and screen-off
are simulated through `cmd connectivity` and key events, which is close to, but not the same as, a phone in a
pocket: airplane mode cuts the emulator's virtual Wi-Fi and cellular, so the app really loses the path to
`10.0.2.2` (the scenarios assert the status line shows it), and screen off does not put the emulator into Doze
within 40 s. The Connect screen cannot be captured in screenshots (`FLAG_SECURE`).
