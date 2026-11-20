# End-to-end tests on an emulator

`run-e2e.sh` runs the end-to-end tests of the `:e2e` module on a headless Android emulator on another machine,
against the real Hermes server on the build host.

`:e2e` is a separate, self-instrumenting test APK (`com.android.test`): the tests run in their own process, so
they can clear, force-stop and relaunch the app without killing themselves. They drive the app through UI Automator
and accessibility actions, find elements by the app's own string resources, and run shell commands as the device's
shell user. Tests:

- `HarnessSelfTest` (no app, no server): the harness's own decisions (approval binding, reply and command-step
  matching, tool-call parsing) against canned screens and server rows.
- `SmokeE2eTest` (no model run): connect through loopback, connect to a private address with local network access
  granted, and the local network flow (asked first for a literal private address; a refusal still tries; the failed
  attempt offers "Allow local network access and retry", which connects once access is granted).
- `SurvivalE2eTest` (one model run each): `connectAndChat`, `replySurvivesAirplaneMode`, `replySurvivesScreenOff`,
  `replySurvivesProcessDeath`, `offlineMessageSendsItself`, `steerDuringRun`, `stopDuringRun`. Each starts from
  cleared app data (`pm clear`, checked), grants `ACCESS_LOCAL_NETWORK`, connects, and journals the new chat's id on
  the device before sending. Every prompt's first line carries the run's nonce, so the chat's server title does.
  Interruptions are asserted positively (airplane mode setting on and the app saying Offline; `dumpsys power` read
  and not Awake; the pid read, then `pidof` succeeding with no result and `/proc/<pid>` gone). A reply counts only
  as the assistant reply paragraph below that turn's prompt, holding the scenario's marker; the running step must
  show the scenario's own command. The server's record of the chat must agree (prompt once, one final answer with
  the marker, the exact terminal command called). Allow once is tapped only when the approval block's own command
  field equals the scenario's command; any other approval is denied and fails the test.

## The two hosts

- **Build host**: has this repository, the Nix dev shell and Hermes at `http://127.0.0.1:8642`. It needs no KVM.
- **Emulator host** (default `obi-one`, override with `APOFIS_E2E_HOST`): any x86_64 machine with Nix (flakes),
  a usable `/dev/kvm`, passwordless `ssh`, about 15 GB free disk, 4 spare cores and 4 GB spare RAM.

## What a run does

0. Takes this host's lock, then the emulator host's lock (held by an SSH session for the whole run, released only
   after the remote run has finished cleaning up). A second run is rejected here, before anything is changed.
1. `rsync`s the working tree (minus `.git`, `.claude/` and everything in `.gitignore`) to `~/tmp/apofis-e2e/` and
   `git add`s it there, so the flake sees exactly the tracked and new files.
2. Opens a reverse tunnel `ssh -R 127.0.0.1:<port>:127.0.0.1:8642` (first free port from 18642) and checks with `ss`
   that the actual listener is `127.0.0.1:<port>` only. Its stdin is held by the script, so it closes when the script
   dies. The app reaches Hermes at `http://10.0.2.2:<port>`; `adb reverse` maps the same port to the device's
   loopback for the loopback smoke test and the tests' own read-only API calls.
3. In `nix develop .#emulator` builds and installs `app-debug.apk` and `e2e-debug.apk`. No key exists on the
   emulator host at this point, and Gradle never sees it.
4. Creates the AVD `apofis-e2e`, starts its own adb server (`adb -P 5037 nodaemon server`, stopped by pid) and the
   emulator with `env -i` (only paths it needs, all inside `~/tmp/apofis-e2e/.state`), `-no-metrics
   -crash-report-mode disabled`, no core dumps, `-no-window -no-snapshot -wipe-data -accel on -cores 4 -memory 4096`.
5. Asks the build host for the key over the SSH session's stdin. A subshell reads it and pipes it to `adb shell`,
   whose device shell reads it and passes it to `am instrument -w -r -e apofisKey ...`. The key is never on a host
   command line, in an environment, in a file or in a long-lived shell variable on the emulator host.
6. Always, also on failure or interrupt: pulls the device files (screenshots, result files, the session journal)
   and logcat, stops the emulator and adb server (bounded waits, then TERM and KILL), deletes the AVD (the app's
   storage, the only place the key rests, goes with it), then scans the whole `~/tmp/apofis-e2e/` (Gradle home,
   project `.gradle` and build output, every run's artifacts, logs) for the key in raw, URL-encoded and base64 forms,
   with patterns the build host sends at that moment. A match deletes the files and fails; a scan error fails.
   Only a clean scan reports a manifest; the build host then copies exactly that set to `build/e2e/<run-id>/`,
   verifies it and scans it again before printing anything from it. An interrupted run copies nothing back.
7. Deletes the journaled sessions: each only after a `GET` on that exact id returns the same id, an `apofis-` id,
   with the run's nonce in its title (a turn still running is retried). Anything else is left alone and fails. If the
   journal could not be read, the run fails and says sessions may remain.
8. Checks the emulator host: lock released, the run's process group, adb server, ports and tunnel port verified
   absent (a probe that cannot run fails), and no new entries in `~`, `~/.android`, `~/.config`, `~/.cache`,
   `~/.local` or the user's `/tmp` entries compared with before the run.

`--full` passes only when every smoke test, the self-tests and all seven scenarios ran and passed, and each smoke and
scenario test left its screenshot (`device/<test>.png`), its own result file and logcat. A partial suite fails.

The remote side sends a heartbeat every 5 s; when the connection is gone it stops itself and cleans up. Ctrl-C or
SIGTERM on the build host stops the remote run the same way and still waits for its cleanup and checks.

## Running

```sh
tools/emulator/run-e2e.sh --smoke                      # SmokeE2eTest and HarnessSelfTest, no model run
tools/emulator/run-e2e.sh --scenario stopDuringRun     # one survival scenario, one model run
tools/emulator/run-e2e.sh --test 'SmokeE2eTest#connectViaLoopback'
tools/emulator/run-e2e.sh --full                       # every e2e test: about 7 model runs, 15 minutes
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
  sessions whose identity it has proved (step 7). Prompts only run `sleep N # apofis-e2e-<n>` (the marker is a shell
  comment, so the command stays one segment and the app's running step shows it whole).

## Cleaning up by hand

Normally nothing is left: the script verifies it. If it reports something left on the emulator host, the postcheck
names the process group, pid or port of that run (`~/tmp/apofis-e2e/.state/runs/<run-id>/owned.txt`); stop exactly
those. The AVD is deleted at the end of every run; a run finding one left behind deletes it first.

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
