#!/usr/bin/env bash
# End-to-end tests on a headless emulator on another host. See tools/emulator/README.md.
#
#   tools/emulator/run-e2e.sh --smoke             connect tests and the harness self-tests, no model run
#   tools/emulator/run-e2e.sh --full              every e2e test: smoke, self-tests and the seven survival scenarios
#   tools/emulator/run-e2e.sh --scenario <name>   one survival scenario, e.g. stopDuringRun
#   tools/emulator/run-e2e.sh --test <Class[#method]>   any test in the e2e module
#
# Runs on the build host; it calls itself on the emulator host with --remote and --in-shell (internal).
# tools/emulator/run-e2e-selftest.sh sources it (APOFIS_E2E_LIB=1) to test its safeguards without any host.
set -euo pipefail

HOST=${APOFIS_E2E_HOST:-obi-one}
REMOTE_DIR=tmp/apofis-e2e          # relative to the remote home
HERMES_PORT=${APOFIS_HERMES_PORT:-8642}
APOFIS_E2E_CANARY=${APOFIS_E2E_CANARY:-}
BUILD_TIMEOUT=${APOFIS_BUILD_TIMEOUT:-1200}
BOOT_TIMEOUT=${APOFIS_BOOT_TIMEOUT:-300}
TEST_TIMEOUT=${APOFIS_TEST_TIMEOUT:-1800}
STALE_LOCK_AGE=14400               # 4 h: longer than any run (build, boot, tests and cleanup are bounded to about 75 min)
LEAK_EXIT=3
SCANFAIL_EXIT=4
CLEANUP_EXIT=5
DEVICE_OUT=/data/local/tmp/apofis-e2e
PKG=xyz.rycerz.apofis.e2e
RUNNER=$PKG/androidx.test.runner.AndroidJUnitRunner
AVD=apofis-e2e
IMAGE="system-images;android-37.0;default;x86_64"
SESSION_RE='apofis-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
RUN_RE='run_[0-9a-f]{32}'
# Any process of an e2e run on the emulator host: the emulator, either remote mode of this script, our adb server.
E2E_PROC_RE='-avd apofis-e2e|run-e2e\.sh --(remote|in-shell)|adb -P 5037 nodaemon'
PORT_RANGES='5037 5037 5554 5585 18642 18662' # adb server, emulator console/adb pairs, tunnel
SMOKE=(connectViaLoopback connectViaPrivateAddress privateAddressAsksForLocalNetwork)
SCENARIOS=(connectAndChat replySurvivesAirplaneMode replySurvivesScreenOff replySurvivesProcessDeath
  offlineMessageSendsItself steerDuringRun stopDuringRun)
SELFTESTS=(approvalBlockIsDetectedByItsHeadingOnly replyIsTheItemRightAfterThePromptsUserItem
  replyOfAnotherTurnOrWithoutTheMarkerDoesNotCount replyTextJoinsOnlyThatReplysRows
  stepMustBeOfThatReplyRunningAndShowExactlyTheCommand terminalCommandsComeFromToolCallArguments
  interruptedResultMustBelongToTheCommandsOwnCall steerRowsAreUnwrapped)
EVIDENCE=(2-airplane-offline 5-offline-waiting) # interruption screenshots, required with their scenarios

# The only files ever copied back: the instrumentation output, the result file, the filtered logcat and the
# screenshots of the named tests (their final and their failure screenshots) and the interruption evidence.
allowlist() {
  local t
  printf '%s\n' instrument.txt results.txt logcat.txt
  for t in "${SMOKE[@]}" "${SCENARIOS[@]}"; do printf '%s.png\nfailed-%s.png\n' "$t" "$t"; done
  for t in "${EVIDENCE[@]}"; do printf '%s.png\n' "$t"; done
}
allowed() { allowlist | grep -qxF -- "$1"; }

log() { printf '[e2e %s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die() { log "FAILED: $*"; exit 1; }

# Every stop and check command runs under bounded: TERM after <seconds>, KILL 5 s later. Nothing can hang.
bounded() { local t=$1; shift; timeout -k 5 "$t" "$@"; }
# True while process $1 exists and is not a zombie.
alive() { local s; s=$(sed 's/.*) //' "/proc/$1/stat" 2>/dev/null) || return 1; [ "${s%% *}" != Z ]; }
group_alive() { local p; for p in $(pgrep -g "$1"); do alive "$p" && return 0; done; return 1; }
# wait_gone <seconds> <test command...>: polls until the test fails (the thing is gone); bounded.
wait_gone() { local n=$(($1 * 2)); shift; while [ "$n" -gt 0 ]; do "$@" || return 0; sleep 0.5; n=$((n - 1)); done; ! "$@"; }
# Stops process $1: TERM, a bounded wait, then KILL. Never hangs.
stop_pid() {
  alive "$1" || return 0
  kill -TERM "$1" 2>/dev/null
  wait_gone 10 alive "$1" && return 0
  kill -KILL "$1" 2>/dev/null
  wait_gone 5 alive "$1"
}

# No process that ever holds key bytes may write a core dump; inherited by every child.
no_core_dumps() { ulimit -c 0 && [ "$(ulimit -c)" = 0 ]; }

# ---------------------------------------------------------------- shared with the emulator host (sent with declare -f)

# Removes a directory tree without rm -rf: files with rm -f, then the emptied directories with rmdir, deepest first.
remove_tree() {
  [ -e "$1" ] || return 0
  chmod -R u+w -- "$1" 2>/dev/null
  find "$1" -depth ! -type d -exec rm -f -- {} + 2>/dev/null
  find "$1" -depth -type d -exec rmdir -- {} + 2>/dev/null
  [ ! -e "$1" ]
}

# The emulator host's lock: a directory that mkdir creates atomically, holding "<run id> <start epoch>". A lock older
# than <stale seconds> is taken over only when no e2e process of any run exists. Prints TAKEN, TAKEN-STALE, BUSY...
# or ERROR. lock_take <state dir> <run id> <stale seconds> <process pattern>
lock_take() {
  local st=$1 id=$2 stale=$3 re=$4 now oid='' ostart='' r
  mkdir -p "$st" || { echo ERROR; return; }
  now=$(date +%s)
  if mkdir "$st/lock.d" 2>/dev/null; then
    if printf '%s %s\n' "$id" "$now" >"$st/lock.d/owner"; then echo TAKEN; else echo ERROR; fi
    return
  fi
  read -r oid ostart <"$st/lock.d/owner" 2>/dev/null
  # No owner file yet (its creator is between mkdir and write): the directory's own time counts.
  [[ $ostart =~ ^[0-9]+$ ]] || ostart=$(stat -c %Y "$st/lock.d" 2>/dev/null) || { echo ERROR; return; }
  if [ $((now - ostart)) -lt "$stale" ]; then echo "BUSY ${oid:-unknown} $((now - ostart))s"; return; fi
  pgrep -u "$(id -u)" -f -- "$re" >/dev/null
  r=$?
  case $r in 0) echo "BUSY-RUNNING ${oid:-unknown}"; return ;; 1) ;; *) echo ERROR; return ;; esac
  # Only one taker can rename the stale directory away.
  mv -T "$st/lock.d" "$st/lock.stale-$id" 2>/dev/null || { echo "BUSY ${oid:-unknown}"; return; }
  rm -f "$st/lock.stale-$id/owner"
  rmdir "$st/lock.stale-$id" 2>/dev/null || { echo ERROR; return; }
  mkdir "$st/lock.d" 2>/dev/null || { echo "BUSY"; return; }
  if printf '%s %s\n' "$id" "$now" >"$st/lock.d/owner"; then echo "TAKEN-STALE ${oid:-unknown}"; else echo ERROR; fi
}

# lock_release <state dir> <run id>: RELEASED, ABSENT, NOT-OURS or ERROR.
lock_release() {
  local oid=''
  [ -e "$1/lock.d" ] || { echo ABSENT; return; }
  read -r oid _ <"$1/lock.d/owner" 2>/dev/null
  [ "$oid" = "$2" ] || { echo NOT-OURS; return; }
  rm -f "$1/lock.d/owner"
  if rmdir "$1/lock.d" 2>/dev/null; then echo RELEASED; else echo ERROR; fi
}

lock_owned() { local oid=''; read -r oid _ <"$1/lock.d/owner" 2>/dev/null && [ "$oid" = "$2" ]; }

# What is left on the emulator host, independent of any record the run kept: e2e processes, the AVD, the run's
# directory and every listener in the port ranges. Each line says absent, PRESENT or ERROR; DONE at the end.
probe() {
  local st=$1 run=$2 ranges=$3 r l
  pgrep -u "$(id -u)" -f -- "$E2E_PROC_RE_ARG" >/dev/null
  r=$?
  case $r in 0) echo "processes PRESENT" ;; 1) echo "processes absent" ;; *) echo "processes ERROR" ;; esac
  if [ -e "$st/avd/apofis-e2e.avd" ] || [ -e "$st/avd/apofis-e2e.ini" ]; then echo "avd PRESENT"; else echo "avd absent"; fi
  if [ -e "$st/runs/$run" ]; then echo "rundir PRESENT"; else echo "rundir absent"; fi
  if l=$(ss -Hltn); then
    echo "listeners $(awk -v r="$ranges" 'BEGIN { n = split(r, a, " ") } { p = $4; sub(/.*:/, "", p); for (i = 1; i < n; i += 2) if (p + 0 >= a[i] && p + 0 <= a[i + 1]) print p }' <<<"$l" | sort -un | tr '\n' ' ')"
  else
    echo "listeners ERROR"
  fi
  echo DONE
}

# ================================================================ emulator host
proto=''      # fd of the channel to the build host (the SSH session's stdin): the key and the scan patterns arrive here
child=''      # the long step in progress (build, instrumentation), stopped by the cleanup
out='' state='' root='' key_sent='' logcat_ok=''
emu_pgid='' emu_serial='' adb_pid='' booted='' avd_made='' hb_pid=''

# A request or report to the build host, on stdout. Logs go to stderr; tool output goes to files only.
ask() { printf '@@%s@@\n' "$*"; }
# Runs a long step in the background without the protocol channel, so a signal can interrupt the wait.
bg() { "$@" </dev/null {proto}<&- & child=$!; local rc=0; wait "$child" || rc=$?; child=''; return "$rc"; }
# adb with a deadline (then KILL) and no stdin (adb shell would otherwise read the protocol channel).
adb() { bounded "${ADB_TIMEOUT:-60}" "$ADB_BIN" "$@" </dev/null; }
free_port() { local o; o=$(ss -Hltn "sport = :$1") || die "ss failed"; [ -z "$o" ]; }

remote() {
  local tests=$1 tunnel_port=$2 run_id=$3 nonce=$4 d
  no_core_dumps || die "could not disable core dumps"
  [[ $tests =~ ^(all|smoke|[A-Za-z0-9_#]+)$ && $tunnel_port =~ ^[0-9]+$ && $run_id =~ ^[0-9]{8}-[0-9]{6}-[0-9a-f]{4}$ && $nonce =~ ^[0-9a-f]{8}$ ]] ||
    die "bad arguments"
  root=$HOME/$REMOTE_DIR
  state=$root/.state
  lock_owned "$state" "$run_id" || die "the emulator host lock is not this run's; run tools/emulator/run-e2e.sh on the build host"
  # Leftovers of interrupted runs (and the lock files of older versions of this script) go first.
  for d in "$state"/runs/*/; do [ ! -d "$d" ] || remove_tree "${d%/}" || die "could not remove a leftover run directory"; done
  rm -f "$state/lock" "$state/run.lock"
  out=$state/runs/$run_id
  mkdir -p "$out" "$state/android" "$state/avd" "$state/gradle" "$state/home" "$state/tmp" "$state/xdg/config" \
    "$state/xdg/cache" "$state/xdg/data" "$state/xdg/state"
  mkdir -p "$state/xdg/runtime"
  chmod 700 "$state/xdg/runtime"
  # Every Android, Gradle, Java and temp location points inside $state (Nix keeps its own cache in the real home).
  export ANDROID_USER_HOME=$state/android ANDROID_EMULATOR_HOME=$state/android ANDROID_AVD_HOME=$state/avd
  export ANDROID_TMP=$state/tmp GRADLE_USER_HOME=$state/gradle TMPDIR=$state/tmp TMP=$state/tmp TEMP=$state/tmp
  # -XX:-UsePerfData: the JVM would otherwise write /tmp/hsperfdata_<user> whatever java.io.tmpdir says.
  export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=$state/tmp -XX:-UsePerfData"
  cd "$root"
  exec nix develop .#emulator -c env HOME="$state/home" XDG_CONFIG_HOME="$state/xdg/config" XDG_CACHE_HOME="$state/xdg/cache" \
    XDG_DATA_HOME="$state/xdg/data" XDG_STATE_HOME="$state/xdg/state" XDG_RUNTIME_DIR="$state/xdg/runtime" \
    "$root/tools/emulator/run-e2e.sh" --in-shell "$tests" "$tunnel_port" "$out" "$nonce"
}

# Writes a heartbeat to the build host every 5 s; when the connection is gone the write fails and the run is stopped.
heartbeat() {
  local main=$1
  trap '' PIPE
  while sleep 5; do printf '@@HB@@\n' 2>/dev/null || { kill -TERM "$main" 2>/dev/null; exit 0; }; done
}

in_shell() {
  local tests=$1 tunnel_port=$2 nonce=$4 p filter start
  out=$3
  state=${out%/runs/*}
  root=${state%/.state}
  no_core_dumps || die "could not disable core dumps"
  exec {proto}<&0 </dev/null
  ADB_BIN=$(command -v adb)
  trap finish EXIT
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM
  trap 'exit 141' PIPE
  ask "PID $$"
  heartbeat $$ </dev/null {proto}<&- &
  hb_pid=$!
  case $tests in
    all) filter="-e package $PKG" ;;
    smoke) filter="-e class $PKG.SmokeE2eTest,$PKG.HarnessSelfTest" ;;
    *) filter="-e class $PKG.$tests" ;;
  esac

  log "stage 3: building the app and test APKs (no key exists on this host)"
  bg bounded "$BUILD_TIMEOUT" ./gradlew --no-daemon --no-configuration-cache --console=plain \
    -Pkotlin.compiler.execution.strategy=in-process :app:assembleDebug :e2e:assembleDebug >"$out/gradle.log" 2>&1 ||
    die "build failed"

  # adb's default server port must be free: never share a server.
  free_port 5037 || die "port 5037 is in use (another adb server?); not sharing it"
  local ep=''
  for p in $(seq 5554 2 5584); do free_port "$p" && free_port $((p + 1)) && { ep=$p; break; }; done
  [ -n "$ep" ] || die "no free emulator port pair"
  emu_serial=emulator-$ep
  export ANDROID_SERIAL=$emu_serial

  log "stage 4: emulator (console port $ep, adb port $((ep + 1)))"
  bounded 60 emulator -accel-check >"$out/accel-check.txt" 2>&1 </dev/null || die "no KVM acceleration"
  if [ -e "$ANDROID_AVD_HOME/$AVD.ini" ] || [ -e "$ANDROID_AVD_HOME/$AVD.avd" ]; then
    log "deleting the AVD a previous run left behind"
    delete_avd || die "could not delete the old AVD"
  fi
  avd_made=1
  echo no | bounded 300 avdmanager create avd -n "$AVD" -k "$IMAGE" -d pixel_7 >"$out/avdmanager.log" 2>&1 || die "avdmanager failed"
  # Our own adb server in the foreground, so it is stopped by its pid.
  setsid "$ADB_BIN" -P 5037 nodaemon server </dev/null >"$out/adb-server.log" 2>&1 {proto}<&- &
  adb_pid=$!
  wait_gone 15 free_port 5037 || die "the adb server did not start"
  # A clean environment: no inherited variables, every writable location inside $state, no crash reports, no
  # metrics and no core dumps (inherited from this shell). It starts long before the key exists in this shell.
  local envs=() v
  for v in PATH HOME ANDROID_HOME ANDROID_SDK_ROOT ANDROID_USER_HOME ANDROID_EMULATOR_HOME ANDROID_AVD_HOME ANDROID_TMP \
    TMPDIR TMP TEMP XDG_CONFIG_HOME XDG_CACHE_HOME XDG_DATA_HOME XDG_STATE_HOME XDG_RUNTIME_DIR; do
    [ -z "${!v-}" ] || envs+=("$v=${!v}")
  done
  start=$SECONDS
  setsid env -i "${envs[@]}" emulator -avd "$AVD" -port "$ep" -no-window -no-audio -no-snapshot \
    -wipe-data -no-boot-anim -no-metrics -crash-report-mode disabled -accel on -gpu swiftshader_indirect -cores 4 \
    -memory 4096 </dev/null >"$out/emulator.log" 2>&1 {proto}<&- &
  emu_pgid=$!
  while [ $((SECONDS - start)) -lt "$BOOT_TIMEOUT" ]; do
    group_alive "$emu_pgid" || die "emulator exited during boot"
    [ "$(ADB_TIMEOUT=10 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && { booted=1; break; }
    sleep 3
  done
  [ -n "$booted" ] || die "emulator did not boot within ${BOOT_TIMEOUT}s"
  log "booted in $((SECONDS - start))s; $(grep -m1 KVM "$out/accel-check.txt")"
  # adbd logs every shell command line: silenced before anything else runs (the key never is on one, but still).
  # The session journal starts empty here: a missing journal at the end means it was lost, never "no sessions".
  [ "$(adb shell 'setprop log.tag.adbd S; settings put global window_animation_scale 0; settings put global transition_animation_scale 0;
    settings put global animator_duration_scale 0; settings put system screen_off_timeout 1800000;
    input keyevent 82; mkdir -p '"$DEVICE_OUT"' && : > '"$DEVICE_OUT/sessions.txt"' && [ -f '"$DEVICE_OUT/sessions.txt"' ] && echo ready' |
    tr -d '\r')" = ready ] || die "could not prepare the device"

  # Two ways in: the emulator's host alias 10.0.2.2 (a private address: the app needs local network access on
  # API 37), and the device's loopback, which adb reverse maps to the tunnel (needs no permission; also used by
  # the tests' own API calls).
  adb reverse "tcp:$tunnel_port" "tcp:$tunnel_port" >/dev/null || die "adb reverse failed"
  local hermes_url=http://127.0.0.1:$tunnel_port direct_url=http://10.0.2.2:$tunnel_port health=''
  for _ in $(seq 30); do
    # The sleep keeps nc's write side open: adb reverse drops the connection on a half-close.
    health=$(adb shell "(printf 'GET /health HTTP/1.0\\r\\n\\r\\n'; sleep 2) | nc -w 5 127.0.0.1 $tunnel_port | head -1" 2>/dev/null | tr -d '\r')
    [[ $health == *" 200 "* ]] && break
    sleep 2
  done
  [[ $health == *" 200 "* ]] || die "Hermes not reachable from the emulator at $hermes_url"
  log "emulator reaches Hermes at $hermes_url"
  ADB_TIMEOUT=180 adb install -r -t app/build/outputs/apk/debug/app-debug.apk >"$out/install.log" 2>&1 || die "installing the app failed"
  ADB_TIMEOUT=180 adb install -r -t e2e/build/outputs/apk/debug/e2e-debug.apk >>"$out/install.log" 2>&1 || die "installing the tests failed"
  start=$(adb shell date +%s | tr -d '\r')
  [[ $start =~ ^[0-9]+$ ]] || die "could not read the device clock"

  log "stage 5: instrumentation ($tests, Hermes at $direct_url and $hermes_url, nonce $nonce)"
  key_sent=1 # from here on the app may have created sessions: the journal must come back
  ask KEY
  # The key is read only inside this pipeline's first element, a subshell that hands it to adb's stdin and exits; the
  # device's shell reads it into a variable and passes it to am instrument. It is never on a host command line, in
  # an environment, in a file, or in this shell's own variables.
  { IFS= read -r -t 120 -u "$proto" k && [ -n "$k" ] && printf '%s\n' "$k"; } |
    bounded "$TEST_TIMEOUT" "$ADB_BIN" shell "IFS= read -r k && [ -n \"\$k\" ] && exec am instrument -w -r -e apofisKey \"\$k\" \
-e apofisUrl $hermes_url -e apofisDirectUrl $direct_url -e apofisNonce $nonce -e apofisStart $start $filter $RUNNER || echo 'no key: instrumentation not started'" \
    >"$out/instrument.txt" 2>&1 {proto}<&- &
  child=$!
  local irc=0
  wait "$child" || irc=$?
  child=''
  [ "$irc" = 0 ] || die "am instrument ended with exit $irc (124: timed out)"
  log "instrumentation finished"
}

# Collects the device files, logcat and the session journal; stops every writer; deletes the AVD (and with it the
# app's storage, the only place the key may rest); stages the allowlisted files, deletes everything else of this
# run, and scans and seals the staged files before the build host may copy them.
finish() {
  local rc=$? pull_rc=1 s
  set +e
  trap '' HUP INT TERM PIPE
  [ -z "$child" ] || stop_pid "$child"
  if [ -n "$booted" ]; then
    ADB_TIMEOUT=90 adb pull "$DEVICE_OUT" "$out/device" >/dev/null 2>&1
    pull_rc=$?
    if ADB_TIMEOUT=60 adb logcat -d -v threadtime ApofisE2e:V TestRunner:V AndroidRuntime:E '*:S' >"$out/logcat.txt" 2>/dev/null &&
      [ -s "$out/logcat.txt" ]; then logcat_ok=1; else log "FAILED: logcat could not be retrieved"; rc=$CLEANUP_EXIT; fi
  fi
  report_sessions "$pull_rc"
  local writers_gone=1
  stop_emulator || { writers_gone=''; rc=$CLEANUP_EXIT; }
  if [ -n "$avd_made" ]; then
    if delete_avd; then log "AVD deleted (the app's storage with it)"; else log "FAILED: could not delete the AVD"; rc=$CLEANUP_EXIT; fi
  fi
  if [ -n "$writers_gone" ]; then
    export_run
    s=$?
    [ "$s" = 0 ] || rc=$s
  else
    log "FAILED: emulator or adb processes remain; nothing is exported"
  fi
  # Everything but the sealed export goes; the build host removes the export after copying it.
  for s in "$out"/* "$out"/.[!.]*; do
    [ "$s" = "$out/export" ] || [ ! -e "$s" ] || remove_tree "$s" || { log "FAILED: could not remove the run's raw files"; rc=$CLEANUP_EXIT; }
  done
  [ -z "$hb_pid" ] || stop_pid "$hb_pid"
  exit "$rc"
}

# Reports the session journal. Only lines of the exact journal forms are passed on, and only when every line is one
# and the file ends with a line break, so nothing else from the device can reach the build host this way.
report_sessions() {
  local f=$out/device/sessions.txt line lines=()
  if [ -z "$key_sent" ]; then ask SESSIONS-NONE; return; fi # the key never left the build host: no app session can exist
  if [ "$1" != 0 ] || [ ! -f "$f" ] || [ -L "$f" ]; then log "FAILED: the session journal could not be retrieved"; ask SESSIONS-UNKNOWN; return; fi
  if [ -s "$f" ] && [ "$(tail -c1 "$f" | od -An -tx1 | tr -d ' ')" != 0a ]; then log "FAILED: the session journal is cut off"; ask SESSIONS-UNKNOWN; return; fi
  while IFS= read -r line; do
    if [[ $line =~ ^session\ $SESSION_RE\ [A-Za-z]{1,40}$ || $line =~ ^run\ $RUN_RE\ $SESSION_RE$ ]]; then lines+=("$line")
    else log "FAILED: the session journal has a malformed line"; ask SESSIONS-UNKNOWN; return; fi
  done <"$f"
  for line in "${lines[@]}"; do ask "J $line"; done
  ask "SESSIONS-END ${#lines[@]}"
}

stop_emulator() {
  local ok=0
  if [ -n "$emu_pgid" ]; then
    log "stopping emulator $emu_serial"
    ADB_TIMEOUT=10 adb -s "$emu_serial" emu kill >/dev/null 2>&1
    if ! wait_gone 30 group_alive "$emu_pgid"; then
      kill -TERM -- "-$emu_pgid" 2>/dev/null
      wait_gone 10 group_alive "$emu_pgid" || { kill -KILL -- "-$emu_pgid" 2>/dev/null; wait_gone 5 group_alive "$emu_pgid"; }
    fi
    if group_alive "$emu_pgid"; then log "emulator processes remain"; ok=1; else log "emulator stopped"; fi
  fi
  if [ -n "$adb_pid" ]; then
    ADB_TIMEOUT=10 adb kill-server >/dev/null 2>&1
    wait_gone 10 alive "$adb_pid" || stop_pid "$adb_pid"
    if alive "$adb_pid"; then log "adb server $adb_pid remains"; ok=1; fi
  fi
  return "$ok"
}

delete_avd() {
  bounded 120 avdmanager delete avd -n "$AVD" >>"$out/avdmanager.log" 2>&1 </dev/null
  [ ! -e "$ANDROID_AVD_HOME/$AVD.avd" ] && [ ! -e "$ANDROID_AVD_HOME/$AVD.ini" ]
}

# Stages the allowlisted files in $out/export, scans them, seals them and reports their checksums.
export_run() {
  local exp=$out/export
  mkdir "$exp" || { log "FAILED: stage export: could not create the staging directory"; return "$SCANFAIL_EXIT"; }
  stage "$exp" || { log "FAILED: stage export: could not stage the files"; remove_tree "$exp"; return "$SCANFAIL_EXIT"; }
  scan_seal_report "$exp"
}

# Copies the allowlisted files into $1: regular files only, never links.
stage() {
  local exp=$1 f b v t
  copy() { [ -f "$1" ] && [ ! -L "$1" ] || return 0; cp -- "$1" "$exp/$2"; }
  copy "$out/instrument.txt" instrument.txt || return 1
  [ -z "$logcat_ok" ] || copy "$out/logcat.txt" logcat.txt || return 1
  if [ -d "$out/device" ]; then
    for t in "${SMOKE[@]}" "${SCENARIOS[@]}" "${EVIDENCE[@]}" "${SMOKE[@]/#/failed-}" "${SCENARIOS[@]/#/failed-}"; do
      copy "$out/device/$t.png" "$t.png" || return 1
    done
    # One result file from the tests' own: "<Class>.<test> PASS|FAIL" for each well-formed one.
    for f in "$out"/device/results/*.txt; do
      b=${f##*/}
      b=${b%.txt}
      [[ $b =~ ^[A-Za-z0-9]+\.[A-Za-z0-9]+$ ]] && [ -f "$f" ] && [ ! -L "$f" ] || continue
      v=$(head -c 8 "$f" | tr -d '\n')
      case $v in PASS | FAIL) printf '%s %s\n' "$b" "$v" ;; esac
    done | sort >"$exp/results.txt" || return 1
  fi
}

# Scans every staged file for the key (patterns from the build host, read now), then hashes them, makes them and
# their directory read-only and reports "FILE <sha256> <name>" lines and "EXPORT-END <count>". Any failure deletes
# the staged files and prints only a fixed message naming the stage.
scan_seal_report() {
  local exp=$1 n p pats=() rc=0 i lines=() files=() h f
  ask PATTERNS
  IFS= read -r -t 60 -u "$proto" n && [[ $n =~ ^[1-9][0-9]?$ ]] || { log "FAILED: stage scan: no scan patterns from the build host; staged files deleted"; remove_tree "$exp"; return "$SCANFAIL_EXIT"; }
  for ((i = 0; i < n; i++)); do
    IFS= read -r -t 10 -u "$proto" p && [ "${#p}" -ge 16 ] || { log "FAILED: stage scan: bad scan patterns; staged files deleted"; remove_tree "$exp"; return "$SCANFAIL_EXIT"; }
    pats+=("$p")
  done
  scan_dir "$exp" "${pats[@]}" || rc=$?
  pats=()
  case $rc in
    1) ;;
    0) log "FAILED: stage scan: the key was found in the staged files; all staged files deleted"; remove_tree "$exp"; return "$LEAK_EXIT" ;;
    *) log "FAILED: stage scan: the scan could not complete; all staged files deleted"; remove_tree "$exp"; return "$SCANFAIL_EXIT" ;;
  esac
  for f in "$exp"/*; do
    [ -e "$f" ] || continue
    if [ ! -f "$f" ] || [ -L "$f" ] || ! allowed "${f##*/}" || ! h=$(sha256sum "$f"); then
      log "FAILED: stage seal: unexpected staged entry or checksum error; all staged files deleted"; remove_tree "$exp"; return "$SCANFAIL_EXIT"
    fi
    lines+=("FILE ${h%% *} ${f##*/}")
    files+=("$f")
  done
  chmod a-w -- "${files[@]}" "$exp" 2>/dev/null || { log "FAILED: stage seal: could not make the staged files read-only"; remove_tree "$exp"; return "$SCANFAIL_EXIT"; }
  for f in "${lines[@]}"; do ask "$f"; done
  ask "EXPORT-END ${#lines[@]}"
  log "staged files scanned (key absent) and sealed: ${#lines[@]}"
}

# scan_dir <dir> <patterns...>: grep's own exit (0 found, 1 clean, else error); prints nothing, ever.
scan_dir() {
  local d=$1
  shift
  grep -rlaF -f <(printf '%s\n' "$@") -- "$d" >/dev/null 2>&1
}

# ================================================================ build host
key='' nonce='' run_id='' tmp='' interrupted='' key_sent_local='' mode='' arg=''
tun_fd='' tunnel_pid='' tunnel_port='' rin='' rout='' remote_pid='' remote_shell_pid=''
sessions_state='' journal=() export_files=() export_state='' locked='' ports_before='' procs_absent=''

rsh() { bounded "${RSH_TIMEOUT:-60}" ssh -o BatchMode=yes "$HOST" "$@"; }
# Runs a function of this script on the emulator host: rsh_fn <function> <args...>.
rsh_fn() { { declare -f "$1"; printf '%q ' "$@"; echo; } | rsh bash -s; }

# The scan patterns: a count, then the key raw, URL-encoded, and base64 (standard and URL alphabet) at the three
# byte alignments, without the edge characters that depend on surrounding bytes.
key_patterns() {
  local p=() i b f=xx enc='' c
  p+=("$key")
  for ((i = 0; i < ${#key}; i++)); do
    c=${key:i:1}
    case $c in [A-Za-z0-9._~-]) enc+=$c ;; *) enc+=$(printf '%%%02X' "'$c") ;; esac
  done
  [ "$enc" = "$key" ] || p+=("$enc")
  for i in 0 1 2; do
    b=$(printf '%s%s' "${f:0:i}" "$key" | base64 -w0)
    [ "$i" = 0 ] || b=${b:4}
    b=${b:0:${#b}-4}
    p+=("$b" "$(printf '%s' "$b" | tr '+/' '-_')")
  done
  # For testing the leak path with a disposable canary instead of the key (see the README).
  [ "${#APOFIS_E2E_CANARY}" -lt 16 ] || p+=("$APOFIS_E2E_CANARY")
  printf '%s\n' "${#p[@]}" "${p[@]}"
}

# hermes <method> <path> <body-file>: calls the Hermes API on the build host; prints the HTTP status.
hermes() {
  bounded 30 curl -s -m 20 -o "$3" -w '%{http_code}' -X "$1" -H @<(printf 'Authorization: Bearer %s\n' "$key") \
    "http://127.0.0.1:$HERMES_PORT/$2"
}

steering_text() { printf 'Test %s-6 steer: end your reply with the word BANANA' "$nonce"; }

# Checks and deletes the sessions the journal names; reads nothing on the server that the journal does not name.
# Each session is first proved to be this run's (a GET on that id returns that id, an app id, with this run's nonce in
# its title). Then, for the steer scenario, its messages must hold exactly one steer row with exactly the steering
# text; for a journaled run, its status must name that session and be cancelled. Then the session is deleted.
check_sessions() {
  local l id scen run body ok=0 n code steer_ok='' stop_ok='' verified=() v
  if [ -z "$key_sent_local" ]; then log "sessions: the key never left this host, so the app created none"; return 0; fi
  if [ "$sessions_state" != ok ]; then
    log "FAILED: the session journal is missing or unreadable; sessions this run created may remain on the server"
    return 1
  fi
  [ "${#journal[@]}" -gt 0 ] || log "sessions: the journal is complete and empty"
  body=$(mktemp)
  for l in "${journal[@]}"; do
    [ "${l%% *}" = session ] || continue
    read -r id scen <<<"${l#session }"
    [[ $id =~ ^$SESSION_RE$ ]] || { log "FAILED: sessions: a malformed id; left alone"; ok=1; continue; }
    for v in "${verified[@]}"; do [ "$v" != "$id" ] || continue 2; done
    code=$(hermes GET "api/sessions/$id" "$body") || code=error
    if [ "$code" = 404 ]; then log "sessions: $id not on the server (never sent, or already gone)"; continue; fi
    if [ "$code" != 200 ] || ! jq -e --arg id "$id" --arg n "$nonce" \
      '.session | .id == $id and (.id | startswith("apofis-")) and ((.title // "") | contains($n))' "$body" >/dev/null 2>&1; then
      log "FAILED: sessions: $id answered $code without this run's identity; left alone"
      ok=1
      continue
    fi
    verified+=("$id")
    if [ "$scen" = steerDuringRun ]; then
      code=$(hermes GET "api/sessions/$id/messages?order=oldest&include_compacted=true" "$body") || code=error
      if [ "$code" = 200 ] && jq -e --arg id "$id" --arg s "$(steering_text)" --arg end $'\n[/OUT-OF-BAND USER MESSAGE]' '
        .session_id == $id and ([.data[] | select(.role == "user" and .display_kind == "steer") | .content // ""] as $c |
          ($c | length) == 1 and ($c[0] | test("^\\[OUT-OF-BAND USER MESSAGE[^\\]\n]*\\]\n")) and ($c[0] | endswith($end)) and
          (($c[0] | sub("^\\[OUT-OF-BAND USER MESSAGE[^\\]\n]*\\]\n"; "") | rtrimstr($end)) == $s))' "$body" >/dev/null 2>&1; then
        log "server: $id holds one steer row with exactly the steering text"
        steer_ok=1
      else
        log "FAILED: server: $id does not hold exactly one steer row with exactly the steering text (HTTP $code)"
        ok=1
      fi
    fi
  done
  for l in "${journal[@]}"; do
    [ "${l%% *}" = run ] || continue
    read -r run id <<<"${l#run }"
    [[ $run =~ ^$RUN_RE$ ]] || { ok=1; continue; }
    for v in "${verified[@]}"; do
      [ "$v" = "$id" ] || continue
      code=$(hermes GET "v1/runs/$run" "$body") || code=error
      if [ "$code" = 200 ] && jq -e --arg r "$run" --arg id "$id" '.run_id == $r and .session_id == $id and .status == "cancelled"' "$body" >/dev/null 2>&1; then
        log "server: the stopped run of $id is cancelled"
        stop_ok=1
      else
        log "FAILED: server: the journaled run of $id is not cancelled (HTTP $code)"
        ok=1
      fi
      continue 2
    done
    log "FAILED: server: a journaled run names a session that is not this run's; not read"
    ok=1
  done
  for id in "${verified[@]}"; do
    for n in 1 2 3 4 5 6; do
      code=$(hermes DELETE "api/sessions/$id" "$body") || code=error
      [ "$code" = 409 ] || break # 409: a turn is still active; it ends shortly after a stop
      sleep 10
    done
    if [ "$code" = 200 ] && jq -e --arg id "$id" '.id == $id and .deleted == true' "$body" >/dev/null 2>&1 &&
      [ "$(hermes GET "api/sessions/$id" "$body")" = 404 ]; then
      log "sessions: $id (title with nonce $nonce) deleted and verified gone"
    else
      log "FAILED: sessions: deleting $id answered $code; it may remain"
      ok=1
    fi
  done
  rm -f "$body"
  # The scenarios that must have left these server records.
  if [[ $mode == full || ($mode == scenario && $arg == steerDuringRun) ]] && [ -z "$steer_ok" ]; then log "FAILED: server: no checked steer row"; ok=1; fi
  if [[ $mode == full || ($mode == scenario && $arg == stopDuringRun) ]] && [ -z "$stop_ok" ]; then log "FAILED: server: no checked cancelled run"; ok=1; fi
  return "$ok"
}

# Lists the user's home and config entries and /tmp entries owned by the user on the emulator host.
listing() {
  # shellcheck disable=SC2016
  rsh 'set -e; u=$(id -u)
    echo "## ~"; ls -A ~
    for d in .android .config .cache .local .local/share .local/state; do echo "## ~/$d"; [ ! -d ~/$d ] || ls -A ~/$d; done
    echo "## /tmp owned by the user"; find /tmp -mindepth 1 -maxdepth 1 -user "$u" -printf "%f\n" | sort'
}

# Listeners in the port ranges on the emulator host now (from probe), or "ERROR".
listeners() { local r; r=$(probe_remote) || { echo ERROR; return; }; sed -n 's/^listeners //p' <<<"$r"; }
probe_remote() { E2E_PROC_RE_ARG=$E2E_PROC_RE RSH_TIMEOUT=${1:-30} rsh_probe; }
rsh_probe() { { declare -f probe; printf 'E2E_PROC_RE_ARG=%q probe %q %q %q\n' "$E2E_PROC_RE" "$REMOTE_DIR/.state" "$run_id" "$PORT_RANGES"; } | rsh bash -s; }

# Unconditional checks of the emulator host: waits (240 s at most) until no e2e process is left, removes this run's
# directory, then requires no e2e process, no AVD, no run directory and no listener in the port ranges that was not
# there before the run. "Could not check" is a failure.
postcheck() {
  local deadline=$((SECONDS + 240)) report='' left t
  while :; do
    left=$((deadline - SECONDS))
    [ "$left" -gt 0 ] || break
    t=$((left < 30 ? left : 30))
    report=$(probe_remote "$t") || report="ssh ERROR"
    grep -qx 'processes absent' <<<"$report" && break
    sleep 5
  done
  if grep -qx 'processes absent' <<<"$report"; then
    # Nothing of the run writes any more: its directory (the copied export, or what an interrupted run left) goes.
    rsh_fn remove_tree "$REMOTE_DIR/.state/runs/$run_id" >/dev/null 2>&1 || log "FAILED: could not remove this run's directory on $HOST"
    report=$(probe_remote 30) || report="ssh ERROR"
  fi
  log "postcheck on $HOST: $(tr '\n' ';' <<<"$report")"
  ! grep -qx 'processes absent' <<<"$report" || procs_absent=1
  if grep -q ERROR <<<"$report" || ! grep -qx DONE <<<"$report"; then log "FAILED: could not verify that nothing is left on $HOST"; return 1; fi
  if grep -q PRESENT <<<"$report"; then log "FAILED: something of this run is left on $HOST (see above)"; return 1; fi
  local now p extra=''
  now=$(sed -n 's/^listeners //p' <<<"$report")
  for p in $now; do [[ " $ports_before " == *" $p "* ]] || extra+="$p "; done
  [ -z "$extra" ] || { log "FAILED: new listeners on $HOST: $extra"; return 1; }
  log "verified on $HOST: no e2e process, AVD, run directory or new listener left"
}

local_cleanup() {
  local rc=$? bad='' r
  set +e
  trap '' INT TERM HUP
  if [ -n "$remote_pid" ]; then stop_pid "$remote_pid" && wait "$remote_pid" 2>/dev/null; fi
  [ -z "$rin" ] || exec {rin}>&-
  [ -z "$rout" ] || exec {rout}<&-
  [ -z "$key" ] || check_sessions || bad=1
  if [ -n "$tun_fd" ]; then exec {tun_fd}>&-; wait_gone 10 alive "$tunnel_pid" || stop_pid "$tunnel_pid"; fi
  if [ -n "$locked" ]; then
    postcheck || bad=1
    if [ -z "$procs_absent" ]; then
      log "FAILED: the emulator host lock stays held: e2e processes remain or could not be checked; it can be taken over after $((STALE_LOCK_AGE / 3600)) h when none is left"
    else
      r=$(rsh_fn lock_release "$REMOTE_DIR/.state" "$run_id" 2>/dev/null) || r=ERROR
      if [ "$r" = RELEASED ]; then log "emulator host lock released"; else log "FAILED: emulator host lock: $r"; bad=1; fi
    fi
    listing >"$tmp/after.txt" 2>&1 || { log "FAILED: could not list the emulator host's home after the run"; bad=1; }
    local new
    new=$(comm -13 <(sort "$tmp/before.txt") <(sort "$tmp/after.txt"))
    if [ -n "$new" ]; then log "FAILED: new entries outside ~/$REMOTE_DIR on $HOST: $(tr '\n' ' ' <<<"$new")"; bad=1
    else log "verified on $HOST: no new entries in ~, ~/.android, ~/.config, ~/.cache, ~/.local or the user's /tmp entries"; fi
  fi
  for p in "$tunnel_pid" "$remote_pid"; do
    [ -z "$p" ] || ! alive "$p" || { log "FAILED: local process $p is still running"; bad=1; }
  done
  if [ -n "$tmp" ]; then rm -f "$tmp/in" "$tmp/out" "$tmp/tunnel.out" "$tmp/before.txt" "$tmp/after.txt" "$tmp/files"; rmdir "$tmp"; fi
  key=''
  [ -z "$bad" ] || { [ "$rc" != 0 ] || rc=1; }
  exit "$rc"
}

on_signal() {
  if [ -n "$remote_pid" ] && [ -z "$interrupted" ] && alive "$remote_pid"; then
    interrupted=$1
    log "interrupted: stopping the run on $HOST; nothing will be copied back"
    if [ -n "$remote_shell_pid" ]; then
      # shellcheck disable=SC2029 # the pid is meant to expand here
      RSH_TIMEOUT=20 rsh "grep -qa -- --in-shell /proc/$remote_shell_pid/cmdline && kill -TERM $remote_shell_pid" ||
        { log "could not signal the remote run; closing the connection instead"; stop_pid "$remote_pid"; }
    else
      stop_pid "$remote_pid" # not started yet: its first write fails and it cleans up
    fi
  else
    exit "$1"
  fi
}

# Runs the remote part and serves its requests. The remote stdout carries only protocol lines; its logs come on stderr.
run_remote() {
  local tests=$1 line='' partial='' r deadline='' n
  mkfifo -m 600 "$tmp/in" "$tmp/out"
  # shellcheck disable=SC2029 # the arguments are meant to expand here
  bounded $((BUILD_TIMEOUT + BOOT_TIMEOUT + TEST_TIMEOUT + 900)) ssh -o BatchMode=yes -o ServerAliveInterval=15 \
    -o ServerAliveCountMax=4 "$HOST" "cd ~/$REMOTE_DIR && exec tools/emulator/run-e2e.sh --remote $tests $tunnel_port $run_id $nonce" \
    <"$tmp/in" >"$tmp/out" &
  remote_pid=$!
  exec {rin}>"$tmp/in" {rout}<"$tmp/out"
  while :; do
    if [ -n "$interrupted" ]; then
      [ -n "$deadline" ] || deadline=$((SECONDS + 300))
      [ "$SECONDS" -lt "$deadline" ] || { log "the remote cleanup did not finish in time"; break; }
    fi
    if IFS= read -r -t 5 -u "$rout" line; then
      line=$partial$line
      partial=''
      case $line in
        '@@HB@@') ;;
        '@@PID '*'@@') r=${line#@@PID }; r=${r%@@}; [[ $r =~ ^[0-9]+$ ]] && remote_shell_pid=$r ;;
        '@@KEY@@') if [ -z "$interrupted" ]; then key_sent_local=1; printf '%s\n' "$key" >&"$rin" || true; else printf '\n' >&"$rin" || true; fi ;;
        '@@PATTERNS@@') key_patterns >&"$rin" || true ;;
        '@@J '*'@@')
          r=${line#@@J }; r=${r%@@}
          if [[ $r =~ ^session\ $SESSION_RE\ [A-Za-z]{1,40}$ || $r =~ ^run\ $RUN_RE\ $SESSION_RE$ ]]; then journal+=("$r"); else sessions_state=bad; fi ;;
        '@@SESSIONS-END '*'@@')
          n=${line#@@SESSIONS-END }; n=${n%@@}
          if [ "$sessions_state" != bad ] && [ "$n" = "${#journal[@]}" ]; then sessions_state=ok; else sessions_state=bad; fi ;;
        '@@SESSIONS-NONE@@') sessions_state=none ;;
        '@@SESSIONS-UNKNOWN@@') sessions_state=unknown ;;
        '@@FILE '*'@@')
          r=${line#@@FILE }; r=${r%@@}
          if [[ $r =~ ^[0-9a-f]{64}\ [A-Za-z0-9.-]+$ ]] && allowed "${r#* }"; then export_files+=("$r"); else export_state=bad; fi ;;
        '@@EXPORT-END '*'@@')
          n=${line#@@EXPORT-END }; n=${n%@@}
          if [ "$export_state" != bad ] && [ "$n" = "${#export_files[@]}" ]; then export_state=ok; else export_state=bad; fi ;;
        *) log "an unexpected line from $HOST (not shown)" ;;
      esac
    else
      r=$?
      # A 5 s tick or a signal whose trap has run; read keeps what it got of a line so far, so nothing is lost.
      [ "$r" -gt 128 ] && { partial+=$line; continue; }
      break # end of the remote output
    fi
  done
  exec {rin}>&- {rout}<&-
  rin='' rout=''
  r=0
  wait "$remote_pid" || r=$?
  remote_pid=''
  return "$r"
}

# Removes the files this run copied into <dir> (only allowlisted names, rm -f) and then the directory (rmdir).
discard_copy() {
  local f
  while IFS= read -r f; do rm -f -- "$1/$f"; done < <(allowlist)
  rmdir -- "$1" 2>/dev/null
  [ ! -e "$1" ] || log "FAILED: $1 has entries this run did not copy; left alone"
}

# import_export <source> <dir>: copies exactly the reported files into <dir> (new), verifies each checksum, that
# nothing else arrived, and scans the copy; on any failure deletes what was copied and prints a fixed message.
import_export() {
  local src=$1 dst=$2 e h f fail=''
  { mkdir -p "${dst%/*}" && mkdir -- "$dst"; } || { log "FAILED: stage copy: could not create $dst; nothing copied"; return 1; }
  for e in "${export_files[@]}"; do printf '%s\n' "${e#* }"; done >"$tmp/files"
  bounded 600 rsync -r --times --chmod=Du+rwx,Fu+rw --files-from="$tmp/files" "$src" "$dst/" >/dev/null 2>&1 || fail="copy"
  if [ -z "$fail" ]; then
    for e in "${export_files[@]}"; do
      h=${e%% *} f=${e#* }
      [ -f "$dst/$f" ] && [ ! -L "$dst/$f" ] && [ "$(sha256sum "$dst/$f" | cut -d' ' -f1)" = "$h" ] || { fail="checksum"; break; }
    done
  fi
  [ -n "$fail" ] || [ "$(find "$dst" -mindepth 1 | wc -l)" = "${#export_files[@]}" ] || fail="checksum"
  if [ -z "$fail" ]; then
    local src_rc=0
    scan_dir "$dst" "$(key_patterns | tail -n +2)" || src_rc=$?
    case $src_rc in 1) ;; 0) fail="local scan (key found)" ;; *) fail="local scan (could not complete)" ;; esac
  fi
  [ -z "$fail" ] && return 0
  discard_copy "$dst"
  log "FAILED: stage $fail: the copied files were deleted from $dst"
  return 1
}

# The results as "Class#method code" lines from am instrument -r output (0 passed; -1, -2, -4 failed; -3 ignored).
results() {
  tr -d '\r' <"$1" | awk '
    /^INSTRUMENTATION_STATUS: class=/ { c = substr($0, index($0, "=") + 1); sub(/.*\./, "", c) }
    /^INSTRUMENTATION_STATUS: test=/ { t = substr($0, index($0, "=") + 1) }
    /^INSTRUMENTATION_STATUS_CODE: / { if ($2 != 1) print c "#" t, $2 }
    /^INSTRUMENTATION_CODE: / { print "INSTRUMENTATION_CODE", $2 }'
}

# Requires every expected test to have run and passed with its evidence, and every test that ran to have passed.
evaluate() {
  local dir=$1 res expected=() t c bad=0 m
  [ -f "$dir/instrument.txt" ] || { log "  missing instrument.txt"; return 1; }
  res=$(results "$dir/instrument.txt")
  case $mode in
    smoke) for t in "${SMOKE[@]}"; do expected+=("SmokeE2eTest#$t"); done ;;
    full) for t in "${SMOKE[@]}"; do expected+=("SmokeE2eTest#$t"); done
          for t in "${SCENARIOS[@]}"; do expected+=("SurvivalE2eTest#$t"); done ;;
    scenario) expected=("SurvivalE2eTest#$arg") ;;
    test) [[ $arg != *'#'* ]] || expected=("$arg") ;;
  esac
  if [[ $mode == smoke || $mode == full ]]; then for t in "${SELFTESTS[@]}"; do expected+=("HarnessSelfTest#$t"); done; fi
  log "results:"
  while read -r t c; do
    [ "$t" = INSTRUMENTATION_CODE ] && continue
    case $c in 0) log "  pass  $t" ;; -3) log "  SKIP  $t"; bad=1 ;; *) log "  FAIL  $t (code $c)"; bad=1 ;; esac
  done <<<"$res"
  grep -qx 'INSTRUMENTATION_CODE -1' <<<"$res" || { log "  the instrumentation did not complete (no INSTRUMENTATION_CODE: -1)"; bad=1; }
  grep -qv INSTRUMENTATION_CODE <<<"$res" || { log "  no test ran"; bad=1; }
  for t in "${expected[@]}"; do
    m=${t#*#}
    grep -qx "$t 0" <<<"$res" || { log "  MISSING or not passed: $t"; bad=1; }
    [[ $t == SmokeE2eTest#* || $t == SurvivalE2eTest#* ]] || continue # only these save evidence
    [ -s "$dir/$m.png" ] || { log "  missing screenshot $m.png"; bad=1; }
    grep -qx "${t/\#/.} PASS" "$dir/results.txt" 2>/dev/null || { log "  missing PASS in results.txt for $t"; bad=1; }
    case $m in
      replySurvivesAirplaneMode) [ -s "$dir/2-airplane-offline.png" ] || { log "  missing interruption evidence 2-airplane-offline.png"; bad=1; } ;;
      offlineMessageSendsItself) [ -s "$dir/5-offline-waiting.png" ] || { log "  missing interruption evidence 5-offline-waiting.png"; bad=1; } ;;
    esac
  done
  [ -s "$dir/logcat.txt" ] || { log "  missing logcat.txt (logcat retrieval failed)"; bad=1; }
  if [ "$bad" != 0 ]; then
    grep -A12 '^INSTRUMENTATION_STATUS: stack=' "$dir/instrument.txt" | head -60 >&2
    return 1
  fi
}

main() {
  local tests='' r
  case ${1:-} in
    --smoke) tests=smoke mode=smoke ;;
    --full) tests=all mode=full ;;
    --scenario) arg=${2:?scenario name} tests=SurvivalE2eTest#$arg mode=scenario ;;
    --test) arg=${2:?test class} tests=$arg mode=test ;;
    --remote) shift; remote "$@"; return ;;
    --in-shell) shift; in_shell "$@"; return ;;
    *) echo "usage: $0 --smoke | --full | --scenario <name> | --test <Class[#method]>" >&2; exit 2 ;;
  esac
  [[ $tests =~ ^[A-Za-z0-9_#]+$ ]] || die "bad test name: $tests"
  no_core_dumps || die "could not disable core dumps"
  # The first action: this host's lock, before anything else is touched here or on the emulator host.
  exec 7>"${XDG_RUNTIME_DIR:-/tmp}/apofis-e2e.lock"
  flock -n 7 || die "another run-e2e.sh is active on this host"
  cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
  run_id=$(date +%Y%m%d-%H%M%S)-$(od -An -N2 -tx1 /dev/urandom | tr -d ' \n')
  nonce=$(od -An -N4 -tx1 /dev/urandom | tr -d ' \n')
  tmp=$(mktemp -d)
  trap local_cleanup EXIT
  trap 'on_signal 130' INT
  trap 'on_signal 143' TERM
  trap 'on_signal 129' HUP
  trap ':' PIPE
  key=$(grep -E '^API_SERVER_KEY=' ~/.hermes/.env | cut -d= -f2- | tr -d "\"'")
  [ "${#key}" -ge 16 ] || die "API_SERVER_KEY not found in ~/.hermes/.env"
  log "run $run_id, nonce $nonce"

  log "stage 0: the emulator host's lock"
  r=$(rsh_fn lock_take "$REMOTE_DIR/.state" "$run_id" "$STALE_LOCK_AGE" "$E2E_PROC_RE" 2>/dev/null) || r="ERROR (ssh)"
  case $r in
    TAKEN) locked=1 ;;
    TAKEN-STALE*) locked=1; log "took over a stale lock (${r#TAKEN-STALE }): older than $((STALE_LOCK_AGE / 3600)) h, no e2e process left" ;;
    BUSY*) die "the emulator host is busy with another run: $r" ;;
    *) die "could not take the emulator host lock: $r" ;;
  esac
  listing >"$tmp/before.txt" 2>&1 || die "could not list the emulator host's home"
  ports_before=$(listeners)
  [ "$ports_before" != ERROR ] || die "could not list the emulator host's listeners"

  log "stage 1: rsync to $HOST:~/$REMOTE_DIR"
  # shellcheck disable=SC2029
  rsh "cd ~/$REMOTE_DIR && { [ -d .git ] || git init -q; } && echo /.state/ > .git/info/exclude" || die "could not prepare $HOST"
  bounded 600 rsync -a --delete --filter=':- .gitignore' --exclude=/.git --exclude=/.state/ ./ "$HOST:$REMOTE_DIR/" || die "rsync failed"
  # The flake only sees files git knows about; .gitignore keeps build output out of the Nix store copy.
  # shellcheck disable=SC2029
  RSH_TIMEOUT=120 rsh "cd ~/$REMOTE_DIR && git add -A" || die "git add failed on $HOST"

  log "stage 2: reverse tunnel to Hermes"
  # shellcheck disable=SC2016
  tunnel_port=$(rsh 'for p in $(seq 18642 18662); do o=$(ss -Hltn "sport = :$p") || exit 1; [ -n "$o" ] || { echo "$p"; exit 0; }; done; exit 1') ||
    die "no free tunnel port on $HOST"
  # Its stdin is held by this script: when this script dies for any reason, the remote cat ends and the tunnel closes.
  exec {tun_fd}> >(exec ssh -o BatchMode=yes -o ExitOnForwardFailure=yes -o ServerAliveInterval=15 \
    -R "127.0.0.1:$tunnel_port:127.0.0.1:$HERMES_PORT" "$HOST" 'exec cat >/dev/null' >"$tmp/tunnel.out" 2>&1)
  tunnel_pid=$!
  local listen=''
  for _ in $(seq 20); do
    # shellcheck disable=SC2029
    listen=$(RSH_TIMEOUT=20 rsh "ss -Hltn 'sport = :$tunnel_port'" | awk '{print $4}' | sort -u) || listen=''
    [ -z "$listen" ] || break
    alive "$tunnel_pid" || die "the tunnel exited"
    sleep 1
  done
  # The requested bind is not enough: the server's SSH policy could widen it, so the actual listener is checked.
  [ "$listen" = "127.0.0.1:$tunnel_port" ] || die "tunnel listens on '${listen:-nothing}', not only 127.0.0.1:$tunnel_port"
  log "tunnel up: $HOST 127.0.0.1:$tunnel_port -> 127.0.0.1:$HERMES_PORT (verified loopback only)"

  local rc=0
  run_remote "$tests" || rc=$?
  if [ -n "$interrupted" ]; then log "interrupted: nothing copied back from $HOST"; exit "$interrupted"; fi
  [ "$rc" != 255 ] || die "stage remote run: the SSH connection failed; nothing copied back"
  [ "$export_state" = ok ] || die "stage export: the remote run ended (exit $rc) without a sealed export; nothing copied back"

  log "copying the ${#export_files[@]} scanned files to build/e2e/$run_id"
  import_export "$HOST:$REMOTE_DIR/.state/runs/$run_id/export/" "build/e2e/$run_id" || exit "$SCANFAIL_EXIT"
  log "copied and verified (checksums match, key absent from the copy)"
  [ "$rc" = 0 ] || die "the remote run failed (exit $rc); scanned files in build/e2e/$run_id"
  evaluate "build/e2e/$run_id" || die "tests failed or evidence is missing; files in build/e2e/$run_id"
  log "PASSED; files in build/e2e/$run_id (server checks and cleanup follow)"
}

[ -n "${APOFIS_E2E_LIB:-}" ] || main "$@"
