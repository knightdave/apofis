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
  carries the run's nonce, so the chat's server title does. Right after each send, and again after the reply, the test
  journals its chat's ids: only rows of the app's own database created after the run started whose title (as the app
  gave it to the server: the first line of the chat's first message) contains the nonce. Chat items are found only by
  the app's test tags (`user:<key>`, `reply:<key>/...`, `step:<replyKey>:<i>`, `notice:<key>`, `composer.send`,
  `composer.stop`, `status.line`, exposed as resource ids): a reply counts only as the reply item right after this
  scenario's user message item, holding the marker; the running step must be a step of that reply whose own text is
  exactly the command. Interruptions are asserted positively (airplane mode setting on and the status line saying
  Offline; `dumpsys power` read and not Awake; the pid read, then `pidof` succeeding with no result and `/proc/<pid>`
  gone). The server's record must agree (prompt once, one final answer with the marker, the exact terminal command;
  for stop, that call's own result interrupted; for steer, exactly one steer row with exactly the steering text). The
  harness never approves anything: an approval block on screen fails the scenario without being touched.

## The two hosts

- **Build host**: has this repository, the Nix dev shell and Hermes at `http://127.0.0.1:8642`. It needs no KVM.
- **Emulator host** (default `obi-one`, override with `APOFIS_E2E_HOST`): any x86_64 machine with Nix (flakes),
  a usable `/dev/kvm`, passwordless `ssh`, about 15 GB free disk, 4 spare cores and 4 GB spare RAM.

## What a run does

0. Takes this host's lock (`flock`), then the emulator host's lock: `mkdir ~/tmp/apofis-e2e/.state/lock.d` (atomic)
   with `<run id> <start time>` inside. It does not depend on any connection and is removed in the cleanup once no e2e
   process is left. A lock older than 4 hours is taken over only when no e2e process (emulator, either remote mode of
   this script, our adb server) exists on the emulator host; a younger one rejects the run before anything changes.
   Records the listeners in the port ranges (5037, 5554-5585, 18642-18662) for the postcheck.
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
   hashed and made read-only; the checksums go to the build host. Without patterns (SSH lost), on a scan error or a
   match, the staged files are deleted and only a fixed message naming the stage is printed.
7. The build host copies exactly those names into `build/e2e/<run-id>/`, verifies every checksum, that nothing else
   arrived, and scans the copy. If any check fails, it deletes the files it copied in this run (by name, `rm -f`, then
   `rmdir`), prints a fixed message and exits non-zero. Then it removes the run directory on the emulator host.
8. Deletes the journaled sessions: each only after a `GET` on that exact id returns the same id, an `apofis-` id,
   with the run's nonce in its title (a turn still running is retried). For the steer scenario's session it also
   requires exactly one steer row with exactly the steering text; for the journaled stopped run, `GET /v1/runs/<id>`
   must name that session and say `cancelled`. Anything else is left alone and fails. A missing or unreadable journal
   fails the run ("sessions may remain") and reads nothing on the server.
9. Checks the emulator host unconditionally, within 240 s: no e2e process, no AVD, no run directory, no listener in the
   port ranges that was not there before ("could not check" fails). Then releases the lock, and compares `~`,
   `~/.android`, `~/.config`, `~/.cache`, `~/.local` and the user's `/tmp` entries with before the run.

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

Normally nothing is left: the script verifies it. If the postcheck reports e2e processes left on the emulator host,
the lock stays in place (`~/tmp/apofis-e2e/.state/lock.d`); stop those processes, then the next run takes the lock over
once it is older than 4 hours, or remove `lock.d/owner` and `rmdir lock.d` by hand. Leftover run directories are
removed by the next run. The AVD is deleted at the end of every run; a run finding one left behind deletes it first.

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
