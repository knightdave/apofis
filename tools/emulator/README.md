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

`--app-test <Class>` runs one of the app's own instrumented tests (`app/src/androidTest`, runner
`xyz.rycerz.apofis.test/androidx.test.runner.AndroidJUnitRunner`) instead of the `:e2e` module, with no server and no
credential:

- At mode entry the script runs itself again from an empty environment plus a fixed list (`APP_ENV`: paths, home,
  locale, terminal, temp and runtime dirs, the ssh agent socket for logging in, and the harness's own settings), so no
  child inherits a credential under any name. The real key is never read; a disposable value feeds the export scan.
- Every ssh, including rsync's, turns off agent, X11 and (in this mode) all port forwarding on its command line,
  whatever the ssh configuration says. No tunnel.
- It builds the two APKs on the build host and uploads an explicit list (`app_upload_list`): the two APKs and the remote
  stage's own tracked files (its script, `flake.nix`, `flake.lock`, the version catalog) into a cleared
  `~/tmp/apofis-e2e/app-src`, from which the emulator shell starts as a path flake. Never a directory walk.
- The test writes its files to the app's external files directory (`files/blur`), pulled with the device files; only
  the fixed names in `APP_FILES` are staged, scanned and copied back like every other export.

`ChatLookupE2eTest` (in `:e2e`, run with `--test`) checks the chat screen's tags and labels and types without
sending: no model run.

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
6. Always, also on failure or interrupt: pulls the device files and a filtered logcat (tags `ApofisE2e`,
   `TestRunner`, `AndroidRuntime`), reports the session journal (only exact lines, a complete file, with a count),
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
tools/emulator/run-e2e.sh --full                       # every e2e test: about 7 model runs, 15 minutes
tools/emulator/run-e2e.sh --app-test BlurCostTest      # an instrumented test of the app itself; no tunnel, no key
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

An emulator cannot test real push delivery, haptics, blur and rendering quality on real GPUs, OEM battery
optimisation and app killers, real mobile network handovers, or biometric hardware. Airplane mode and screen-off
are simulated through `cmd connectivity` and key events, which is close to, but not the same as, a phone in a
pocket: airplane mode cuts the emulator's virtual Wi-Fi and cellular, so the app really loses the path to
`10.0.2.2` (the scenarios assert the status line shows it), and screen off does not put the emulator into Doze
within 40 s. The Connect screen cannot be captured in screenshots (`FLAG_SECURE`).
