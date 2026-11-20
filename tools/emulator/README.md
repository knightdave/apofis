# End-to-end tests on an emulator

`run-e2e.sh` runs the end-to-end tests of the `:e2e` module on a headless Android emulator on another machine,
against the real Hermes server on the build host.

`:e2e` is a separate, self-instrumenting test APK (`com.android.test`): the tests run in their own process, so
they can clear, force-stop and relaunch the app without killing themselves. They drive the app through UI Automator
and accessibility actions, find elements by the app's own string resources, and run shell commands as the device's
shell user. Tests:

- `SmokeE2eTest` (no model run): connect through loopback, connect to a private address with local network access
  granted, and the local network request itself (rationale shown, refusal shown as a field error, connect after
  the grant).
- `SurvivalE2eTest` (one model run each): `connectAndChat`, `replySurvivesAirplaneMode`, `replySurvivesScreenOff`,
  `replySurvivesProcessDeath`, `offlineMessageSendsItself`, `steerDuringRun`, `stopDuringRun`. Each starts from
  cleared app data (`pm clear`), grants `ACCESS_LOCAL_NETWORK` with `pm grant`, connects, checks the app opened a
  new empty chat, asserts on visible text and saves a screenshot. An approval request is answered with Allow once
  only when it shows exactly the command the test asked for.

## The two hosts

- **Build host**: has this repository, the Nix dev shell and Hermes at `http://127.0.0.1:8642`. It needs no KVM.
- **Emulator host** (default `obi-one`, override with `APOFIS_E2E_HOST`): any x86_64 machine with Nix (flakes),
  a usable `/dev/kvm`, passwordless `ssh`, about 15 GB free disk, 4 spare cores and 4 GB spare RAM.

## What a run does

1. `rsync`s the working tree (minus `.git`, `.claude/` and everything in `.gitignore`) to `~/tmp/apofis-e2e/` on the
   emulator host and `git add`s it there, so the flake sees exactly the tracked and new files.
2. Opens a reverse tunnel `ssh -R 127.0.0.1:<port>:127.0.0.1:8642`, with the first free port from 18642 to 18662,
   bound to the emulator host's loopback only. The app reaches it at `http://10.0.2.2:<port>`, the emulator's host
   alias (a private address: on API 37 the app needs local network access, which the tests grant). After boot,
   `adb reverse` also maps the same port on the device's loopback, `http://127.0.0.1:<port>`: used by the loopback
   smoke test and by the tests' own API calls (session cleanup), which need no permission.
3. In `nix develop .#emulator` (the `emulator` shell in `flake.nix`: emulator, platform-tools, build tools and the
   AOSP `android-37.0;default;x86_64` image) it creates the AVD `apofis-e2e` if missing and boots it headless with
   `-no-window -no-audio -no-snapshot -wipe-data -accel on -cores 4 -memory 4096`, on the first free console/adb port
   pair from 5554/5555, and its own adb server on 5037 (AGP's test engine only uses that port, so the run refuses to
   start when 5037 is taken, rather than share or kill someone else's adb server). Boot is bounded by
   `APOFIS_BOOT_TIMEOUT` (300 s).
4. Runs `./gradlew :e2e:connectedDebugAndroidTest` for the chosen tests, with the two URLs as Gradle properties and
   the API key in the environment variable `APOFIS_KEY` (read by `e2e/build.gradle.kts`). Bounded by
   `APOFIS_TEST_TIMEOUT` (1800 s).
5. Collects the Gradle log, logcat, emulator log, the test report (HTML and XML), screenshots and the list of
   session ids the tests created (`device/sessions.txt`), checks them and the remote build output, Gradle home and
   temp directory for the key, copies them to `build/e2e/<run-id>/`, stops the emulator and closes the tunnel. Then
   it checks on the build host that every recorded session is gone (`GET` returns 404); a leftover is deleted only
   when its id starts with `apofis-` and its title is one the tests set, and fails the run. Cleanup runs from a `trap`, also when a step fails, and the script checks
   afterwards that no emulator process and no tunnel port remain. A run with zero executed tests counts as failed.

The script exits non-zero when anything fails. Every run wipes the emulator's data, so two runs in a row do not
affect each other; a lock prevents two runs at once on the emulator host.

## Running

```sh
tools/emulator/run-e2e.sh --smoke                      # SmokeE2eTest, no model run
tools/emulator/run-e2e.sh --scenario stopDuringRun     # one survival scenario, one model run
tools/emulator/run-e2e.sh --test 'SmokeE2eTest#connectViaLoopback'
tools/emulator/run-e2e.sh --full                       # every e2e test: about 7 model runs, 15 minutes
```

The first run downloads the emulator and system image into the emulator host's Nix store (about 2 GB, a few
minutes) and Gradle's dependencies into `~/tmp/apofis-e2e/.state/gradle`. Later runs take a few minutes.

## Safety rules

- The API key is read from `~/.hermes/.env` on the build host and sent to the remote script on stdin. It is never
  on a command line on the emulator host, never written there, and never printed. Gradle runs with
  `--no-configuration-cache` and without daemons so nothing derived from it is kept. If the key is found in any
  artifact or remote build file, the run fails and nothing is copied back.
- The Connect screen sets `FLAG_SECURE`, so screenshots are taken only after connecting.
- On the emulator host everything lives in `~/tmp/apofis-e2e/`: `ANDROID_USER_HOME`, `ANDROID_AVD_HOME`,
  `ANDROID_EMULATOR_HOME`, `GRADLE_USER_HOME`, `TMPDIR` and `HOME` for the run all point into its `.state/`. No
  `sudo`, no services, no changes to the user's configuration.
- Emulator console, adb and the tunnel listen on 127.0.0.1 only.
- Tests create Hermes sessions only through the app (ids start with `apofis-`). After `pm clear` the app's
  database holds only the chats the test created (nothing lists the server's sessions yet), so the ids read from it
  with `run-as ... sqlite3` are exactly the test's. Each is deleted through the API after a `GET` confirms the
  prefix and the title. Prompts only run `sleep N; echo apofis-e2e-<n>`.

## Cleaning up by hand

On the emulator host, if a run was interrupted:

```sh
pgrep -u "$(id -u)" -a '^(qemu-system|emulator|netsimd|adb)'   # then kill the listed PIDs
ss -ltnp | grep -E ':(5037|1864[2-9]|186[5-6][0-9]|555[4-9]|55[6-8][0-9]) '
```

On the build host, a leftover tunnel is an `ssh -N ... -R 127.0.0.1:18642:...` process: `pkill -f 'ssh -N .*-R 127.0.0.1:186'`.

To remove everything from the emulator host:

```sh
cd ~/tmp/apofis-e2e
nix develop .#emulator -c env ANDROID_AVD_HOME="$PWD/.state/avd" avdmanager delete avd -n apofis-e2e
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
