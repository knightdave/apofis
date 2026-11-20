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
REMOTE_TIMEOUT=$((BUILD_TIMEOUT + BOOT_TIMEOUT + TEST_TIMEOUT + 900)) # the whole remote stage, its cleanup included
SHUTDOWN_LIMIT=240                 # stopping and verifying the emulator host, in the build host's cleanup
TRAP_LIMIT=900                     # the build host's whole cleanup; past it, it reports failure and exits
LEAK_EXIT=3
SCANFAIL_EXIT=4
CLEANUP_EXIT=5
E2E_OUT=build/e2e                  # verified copies, relative to the repository
DEVICE_OUT=/data/local/tmp/apofis-e2e
PKG=xyz.rycerz.apofis.e2e
RUNNER=$PKG/androidx.test.runner.AndroidJUnitRunner
AVD=apofis-e2e
IMAGE="system-images;android-37.0;default;x86_64"
SESSION_RE='apofis-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}'
RUN_RE='run_[0-9a-f]{32}'
RUNID_RE='[0-9]{8}-[0-9]{6}-[0-9a-f]{4}'
# The processes of an e2e run on the emulator host: the emulator, our adb server, either remote mode of this script.
EMU_RE='-avd apofis-e2e'
ADB_RE='adb -P 5037 nodaemon'
SCRIPT_RE='run-e2e\.sh --(remote|in-shell)'
# The same, written so that it does not match the command line of the shell that runs it (for the manual check).
MANUAL_RE='[-]avd apofis-e2e|run-e2e[.]sh --(remote|in-shell)|adb -P 503[7] nodaemon'
PORT_RANGES='5037 5037 5554 5585 18642 18662' # adb server, emulator console/adb pairs, tunnel
SMOKE=(connectViaLoopback connectViaPrivateAddress privateAddressAsksForLocalNetwork)
SCENARIOS=(connectAndChat replySurvivesAirplaneMode replySurvivesScreenOff replySurvivesProcessDeath
  offlineMessageSendsItself steerDuringRun stopDuringRun)
SELFTESTS=(approvalBlockIsDetectedByItsHeadingOnly replyIsTheItemRightAfterThePromptsUserItem
  replyOfAnotherTurnOrWithoutTheMarkerDoesNotCount replyTextJoinsOnlyThatReplysRows
  stepMustBeOfThatReplyRunningAndShowExactlyTheCommand chatKeyIsReadFromTheChatScreensTagOnly
  steerReplyIsTheFinalReplyOfThisTurnOnly steeredTurnOnTheServerMustBeInOrderAndAlone
  terminalCommandsComeFromToolCallArguments interruptedResultMustBelongToTheCommandsOwnCall steerRowsAreUnwrapped)
EVIDENCE=(2-airplane-offline 5-offline-waiting) # interruption screenshots, required with their scenarios

# The emulator host never prints anything to the build host's terminal. It reports progress and failures as codes;
# the build host prints its own text for each known code and a fixed notice for anything else.
declare -A REMOTE_MSG=(
  [core]="FAILED: could not disable core dumps"
  [args]="FAILED: bad arguments"
  [not-locked]="FAILED: the emulator host lock is not this run's"
  [leftover]="FAILED: could not remove a leftover run directory"
  [ss]="FAILED: could not list listening ports"
  [build]="stage 3: building the app and test APKs (no key exists on the emulator host)"
  [build-failed]="FAILED: the build failed"
  [adb-port]="FAILED: port 5037 is in use (another adb server?); not sharing it"
  [emu-port]="FAILED: no free emulator port pair"
  [emulator]="stage 4: starting the emulator"
  [no-kvm]="FAILED: no KVM acceleration"
  [avd-old]="deleting the AVD a previous run left behind"
  [avd-old-failed]="FAILED: could not delete the old AVD"
  [avd-failed]="FAILED: avdmanager could not create the AVD"
  [adb-failed]="FAILED: the adb server did not start"
  [boot-exited]="FAILED: the emulator exited during boot"
  [boot-timeout]="FAILED: the emulator did not boot in time"
  [booted]="emulator booted"
  [device-failed]="FAILED: could not prepare the device"
  [reverse-failed]="FAILED: adb reverse failed"
  [hermes-failed]="FAILED: Hermes is not reachable from the emulator"
  [hermes-ok]="the emulator reaches Hermes through the tunnel"
  [install-failed]="FAILED: installing the APKs failed"
  [instrument]="stage 5: instrumentation"
  [instrument-failed]="FAILED: am instrument did not end normally (or timed out)"
  [instrument-done]="instrumentation finished"
  [logcat-failed]="FAILED: logcat could not be retrieved"
  [journal-unreadable]="FAILED: the session journal could not be retrieved"
  [journal-cut]="FAILED: the session journal is cut off"
  [journal-malformed]="FAILED: the session journal has a malformed line"
  [emu-stopped]="emulator stopped"
  [emu-remains]="FAILED: emulator processes remain"
  [adb-remains]="FAILED: the adb server remains"
  [avd-deleted]="AVD deleted (the app's storage with it)"
  [avd-delete-failed]="FAILED: could not delete the AVD"
  [writers-remain]="FAILED: emulator or adb processes remain; nothing is exported"
  [export-failed]="FAILED: stage export: could not stage the files"
  [scan-no-patterns]="FAILED: stage scan: no scan patterns from the build host; staged files deleted"
  [scan-bad-patterns]="FAILED: stage scan: bad scan patterns; staged files deleted"
  [scan-leak]="FAILED: stage scan: the key was found in the staged files; all staged files deleted"
  [scan-error]="FAILED: stage scan: the scan could not complete; all staged files deleted"
  [seal-failed]="FAILED: stage seal: unexpected staged entry, checksum or permission error; all staged files deleted"
  [sealed]="staged files scanned (key absent) and sealed"
  [raw-remove-failed]="FAILED: could not remove the run's raw files"
)

# The only files ever copied back: the instrumentation output, the result file, the filtered logcat and the
# screenshots of the named tests (their final and their failure screenshots) and the interruption evidence.
allowlist() {
  local t
  printf '%s\n' instrument.txt results.txt logcat.txt
  for t in "${SMOKE[@]}" "${SCENARIOS[@]}"; do printf '%s.png\nfailed-%s.png\n' "$t" "$t"; done
  for t in "${EVIDENCE[@]}"; do printf '%s.png\n' "$t"; done
}
allowed() { allowlist | grep -qxF -- "$1"; }

# The session journal's line forms: a send in chat <key> (written before the send), a server id of chat <key>, and
# the run the app followed for chat <key>.
journal_line_ok() {
  [[ $1 =~ ^send\ $SESSION_RE\ [A-Za-z]{1,40}$ || $1 =~ ^session\ $SESSION_RE\ $SESSION_RE\ [A-Za-z]{1,40}$ ||
    $1 =~ ^run\ $RUN_RE\ $SESSION_RE$ ]]
}

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
SHARED_FNS=(remove_tree lock_take lock_release lock_owned path_state listeners_now probe)

# Removes a directory tree without rm -rf: files with rm -f, then the emptied directories with rmdir, deepest first.
remove_tree() {
  [ -e "$1" ] || return 0
  chmod -R u+w -- "$1" 2>/dev/null
  find "$1" -depth ! -type d -exec rm -f -- {} + 2>/dev/null
  find "$1" -depth -type d -exec rmdir -- {} + 2>/dev/null
  [ ! -e "$1" ]
}

# The emulator host's lock: the directory lock.d holding one file, owner, with the run id. It is born complete (a new
# directory renamed into place, which never replaces an existing lock) and taking it again with the same run id
# succeeds, so a lost answer is recovered by asking again. A lock of another run is never taken over.
# lock_take <state dir> <run id>: prints TAKEN, BUSY <owner> or ERROR.
lock_take() {
  local st=$1 id=$2 new=$1/lock.new-$2 oid=''
  mkdir -p "$st" || { echo ERROR; return; }
  if [ ! -e "$st/lock.d" ]; then
    if mkdir -p "$new" && printf '%s\n' "$id" >"$new/owner"; then mv -T "$new" "$st/lock.d" 2>/dev/null; fi
    rm -f "$new/owner"
    rmdir "$new" 2>/dev/null # left only when the rename failed
  fi
  read -r oid <"$st/lock.d/owner" 2>/dev/null
  if [ "$oid" = "$id" ]; then echo TAKEN
  elif [ -n "$oid" ]; then echo "BUSY $oid"
  elif [ -e "$st/lock.d" ]; then echo "BUSY unknown"
  else echo ERROR; fi
}

# lock_release <state dir> <run id>: RELEASED, ABSENT, NOT-OURS or ERROR. Moves the lock away first, so lock.d never
# exists empty.
lock_release() {
  local oid=''
  [ -e "$1/lock.d" ] || { echo ABSENT; return; }
  read -r oid <"$1/lock.d/owner" 2>/dev/null
  [ "$oid" = "$2" ] || { echo NOT-OURS; return; }
  mv -T "$1/lock.d" "$1/lock.old-$2" 2>/dev/null || { echo ERROR; return; }
  rm -f "$1/lock.old-$2/owner"
  if rmdir "$1/lock.old-$2" 2>/dev/null; then echo RELEASED; else echo ERROR; fi
}

lock_owned() { local oid=''; read -r oid <"$1/lock.d/owner" 2>/dev/null && [ "$oid" = "$2" ]; }

# present, absent (the nearest existing ancestor is a searchable directory) or error (could not tell).
path_state() {
  local d
  if [ -e "$1" ] || [ -L "$1" ]; then echo present; return; fi
  d=$(dirname -- "$1")
  while [ ! -e "$d" ]; do d=$(dirname -- "$d"); done
  if [ -d "$d" ] && [ -x "$d" ]; then echo absent; else echo error; fi
}

# LISTENERS=<comma separated ports in the ranges>, LISTENERS=none or LISTENERS=error.
listeners_now() {
  local l p
  l=$(ss -Hltn) || { echo LISTENERS=error; return; }
  p=$(awk -v r="$1" 'BEGIN { n = split(r, a, " ") } { p = $4; sub(/.*:/, "", p); for (i = 1; i < n; i += 2) if (p + 0 >= a[i] && p + 0 <= a[i + 1]) print p + 0 }' <<<"$l" |
    sort -un | paste -sd, -)
  echo "LISTENERS=${p:-none}"
}

# What is left on the emulator host, independent of any record the run kept. One token per fact, then DONE:
# EMULATOR, ADB, REMOTE (processes), AVD, RUNDIR = absent|present|error; LOCK = released|held|error (held: still
# this run's); PORTS = closed|open|error (open: a listener in the ranges that was not there before the run).
# probe <state dir> <run id> <ranges> <ports before> <emulator re> <adb re> <script re>
probe() {
  local st=$1 run=$2 before=,$4, r name re a b p l oid=''
  for name in EMULATOR ADB REMOTE; do
    case $name in EMULATOR) re=$5 ;; ADB) re=$6 ;; *) re=$7 ;; esac
    r=0
    pgrep -u "$(id -u)" -f -- "$re" >/dev/null || r=$?
    case $r in 0) echo "$name=present" ;; 1) echo "$name=absent" ;; *) echo "$name=error" ;; esac
  done
  a=$(path_state "$st/avd/apofis-e2e.avd") b=$(path_state "$st/avd/apofis-e2e.ini")
  if [ "$a" = absent ] && [ "$b" = absent ]; then echo AVD=absent
  elif [ "$a" = present ] || [ "$b" = present ]; then echo AVD=present
  else echo AVD=error; fi
  echo "RUNDIR=$(path_state "$st/runs/$run")"
  case $(path_state "$st/lock.d") in
    absent) echo LOCK=released ;;
    present) read -r oid <"$st/lock.d/owner" 2>/dev/null
      if [ "$oid" = "$run" ]; then echo LOCK=held; elif [ -n "$oid" ]; then echo LOCK=released; else echo LOCK=error; fi ;;
    *) echo LOCK=error ;;
  esac
  l=$(listeners_now "$3")
  l=${l#LISTENERS=}
  if [ "$l" = error ]; then echo PORTS=error
  else
    r=closed
    for p in ${l//,/ }; do [ "$p" = none ] || [[ $before == *",$p,"* ]] || r=open; done
    echo "PORTS=$r"
  fi
  echo DONE
}

# ================================================================ emulator host
proto=''      # fd of the channel to the build host (the SSH session's stdin): the key and the scan patterns arrive here
child=''      # the long step in progress (build, instrumentation), stopped by the cleanup
out='' state='' root='' key_sent='' logcat_ok='' rid='' manifest=()
emu_pgid='' emu_serial='' adb_pid='' booted='' avd_made='' hb_pid=''

# A request or report to the build host, on stdout (the only output the build host reads; stderr is discarded).
ask() { printf '@@%s@@\n' "$*"; }
rmsg() { ask "MSG $1"; }
rdie() { rmsg "$1"; exit 1; }
# Runs a long step in the background without the protocol channel, so a signal can interrupt the wait.
bg() { "$@" </dev/null {proto}<&- & child=$!; local rc=0; wait "$child" || rc=$?; child=''; return "$rc"; }
# adb with a deadline (then KILL) and no stdin (adb shell would otherwise read the protocol channel).
adb() { bounded "${ADB_TIMEOUT:-60}" "$ADB_BIN" "$@" </dev/null; }
free_port() { local o; o=$(ss -Hltn "sport = :$1") || rdie ss; [ -z "$o" ]; }

remote() {
  local tests=$1 tunnel_port=$2 run_id=$3 nonce=$4 d
  no_core_dumps || rdie core
  [[ $tests =~ ^(all|smoke|[A-Za-z0-9_#]+)$ && $tunnel_port =~ ^[0-9]+$ && $run_id =~ ^$RUNID_RE$ && $nonce =~ ^[0-9a-f]{8}$ ]] ||
    rdie args
  root=$HOME/$REMOTE_DIR
  state=$root/.state
  lock_owned "$state" "$run_id" || rdie not-locked
  # Leftovers of interrupted runs (and the lock files of older versions of this script) go first.
  for d in "$state"/runs/*/; do [ ! -d "$d" ] || remove_tree "${d%/}" || rdie leftover; done
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
  rid=${out##*/}
  no_core_dumps || rdie core
  [[ $rid =~ ^$RUNID_RE$ ]] || rdie args
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

  rmsg build
  bg bounded "$BUILD_TIMEOUT" ./gradlew --no-daemon --no-configuration-cache --console=plain \
    -Pkotlin.compiler.execution.strategy=in-process :app:assembleDebug :e2e:assembleDebug >"$out/gradle.log" 2>&1 ||
    rdie build-failed

  # adb's default server port must be free: never share a server.
  free_port 5037 || rdie adb-port
  local ep=''
  for p in $(seq 5554 2 5584); do free_port "$p" && free_port $((p + 1)) && { ep=$p; break; }; done
  [ -n "$ep" ] || rdie emu-port
  emu_serial=emulator-$ep
  export ANDROID_SERIAL=$emu_serial

  rmsg emulator
  bounded 60 emulator -accel-check >"$out/accel-check.txt" 2>&1 </dev/null || rdie no-kvm
  if [ -e "$ANDROID_AVD_HOME/$AVD.ini" ] || [ -e "$ANDROID_AVD_HOME/$AVD.avd" ]; then
    rmsg avd-old
    delete_avd || rdie avd-old-failed
  fi
  avd_made=1
  echo no | bounded 300 avdmanager create avd -n "$AVD" -k "$IMAGE" -d pixel_7 >"$out/avdmanager.log" 2>&1 || rdie avd-failed
  # Our own adb server in the foreground, so it is stopped by its pid.
  setsid "$ADB_BIN" -P 5037 nodaemon server </dev/null >"$out/adb-server.log" 2>&1 {proto}<&- &
  adb_pid=$!
  wait_gone 15 free_port 5037 || rdie adb-failed
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
    group_alive "$emu_pgid" || rdie boot-exited
    [ "$(ADB_TIMEOUT=10 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && { booted=1; break; }
    sleep 3
  done
  [ -n "$booted" ] || rdie boot-timeout
  rmsg booted
  # adbd logs every shell command line: silenced before anything else runs (the key never is on one, but still).
  # The session journal starts empty here: a missing journal at the end means it was lost, never "no sessions".
  [ "$(adb shell 'setprop log.tag.adbd S; settings put global window_animation_scale 0; settings put global transition_animation_scale 0;
    settings put global animator_duration_scale 0; settings put system screen_off_timeout 1800000;
    input keyevent 82; mkdir -p '"$DEVICE_OUT"' && : > '"$DEVICE_OUT/sessions.txt"' && [ -f '"$DEVICE_OUT/sessions.txt"' ] && echo ready' |
    tr -d '\r')" = ready ] || rdie device-failed

  # Two ways in: the emulator's host alias 10.0.2.2 (a private address: the app needs local network access on
  # API 37), and the device's loopback, which adb reverse maps to the tunnel (needs no permission; also used by
  # the tests' own API calls).
  adb reverse "tcp:$tunnel_port" "tcp:$tunnel_port" >/dev/null 2>&1 || rdie reverse-failed
  local hermes_url=http://127.0.0.1:$tunnel_port direct_url=http://10.0.2.2:$tunnel_port health=''
  for _ in $(seq 30); do
    # The sleep keeps nc's write side open: adb reverse drops the connection on a half-close.
    health=$(adb shell "(printf 'GET /health HTTP/1.0\\r\\n\\r\\n'; sleep 2) | nc -w 5 127.0.0.1 $tunnel_port | head -1" 2>/dev/null | tr -d '\r')
    [[ $health == *" 200 "* ]] && break
    sleep 2
  done
  [[ $health == *" 200 "* ]] || rdie hermes-failed
  rmsg hermes-ok
  ADB_TIMEOUT=180 adb install -r -t app/build/outputs/apk/debug/app-debug.apk >"$out/install.log" 2>&1 || rdie install-failed
  ADB_TIMEOUT=180 adb install -r -t e2e/build/outputs/apk/debug/e2e-debug.apk >>"$out/install.log" 2>&1 || rdie install-failed

  rmsg instrument
  key_sent=1 # from here on the app may have created sessions: the journal must come back
  ask KEY
  # The key is read only inside this pipeline's first element, a subshell that hands it to adb's stdin and exits; the
  # device's shell reads it into a variable and passes it to am instrument. It is never on a host command line, in
  # an environment, in a file, or in this shell's own variables.
  { IFS= read -r -t 120 -u "$proto" k && [ -n "$k" ] && printf '%s\n' "$k"; } |
    bounded "$TEST_TIMEOUT" "$ADB_BIN" shell "IFS= read -r k && [ -n \"\$k\" ] && exec am instrument -w -r -e apofisKey \"\$k\" \
-e apofisUrl $hermes_url -e apofisDirectUrl $direct_url -e apofisNonce $nonce $filter $RUNNER || echo 'no key: instrumentation not started'" \
    >"$out/instrument.txt" 2>&1 {proto}<&- &
  child=$!
  local irc=0
  wait "$child" || irc=$?
  child=''
  [ "$irc" = 0 ] || rdie instrument-failed
  rmsg instrument-done
}

# Collects the device files, logcat and the session journal; stops every writer; deletes the AVD (and with it the
# app's storage, the only place the key may rest); stages the allowlisted files, deletes everything else of this
# run, and scans and seals the staged files. Only when all of that succeeded it reports their checksums, and then,
# as its very last output line, the completion token "COMPLETE <run id> <file count>".
finish() {
  local rc=$? pull_rc=1 s
  set +e
  trap '' HUP INT TERM PIPE
  [ -z "$child" ] || stop_pid "$child"
  if [ -n "$booted" ]; then
    ADB_TIMEOUT=90 adb pull "$DEVICE_OUT" "$out/device" >/dev/null 2>&1
    pull_rc=$?
    if ADB_TIMEOUT=60 adb logcat -d -v threadtime ApofisE2e:V TestRunner:V AndroidRuntime:E '*:S' >"$out/logcat.txt" 2>/dev/null &&
      [ -s "$out/logcat.txt" ]; then logcat_ok=1; else rmsg logcat-failed; rc=$CLEANUP_EXIT; fi
  fi
  report_sessions "$pull_rc"
  local writers_gone=1
  stop_emulator || { writers_gone=''; rc=$CLEANUP_EXIT; }
  if [ -n "$avd_made" ]; then
    if delete_avd; then rmsg avd-deleted; else rmsg avd-delete-failed; rc=$CLEANUP_EXIT; fi
  fi
  [ -z "$hb_pid" ] || stop_pid "$hb_pid" # no heartbeat from here on: the completion token must be the last line
  if [ -n "$writers_gone" ]; then
    export_run
    s=$?
    [ "$s" = 0 ] || rc=$s
  else
    rmsg writers-remain
  fi
  # Everything but the sealed export goes; the build host's postcheck removes the run directory after copying.
  for s in "$out"/* "$out"/.[!.]*; do
    [ "$s" = "$out/export" ] || [ ! -e "$s" ] || remove_tree "$s" || { rmsg raw-remove-failed; rc=$CLEANUP_EXIT; }
  done
  if [ "$rc" = 0 ] && [ "${#manifest[@]}" -gt 0 ]; then
    for s in "${manifest[@]}"; do ask "$s"; done
    ask "COMPLETE $rid ${#manifest[@]}"
  else
    remove_tree "$out/export"
  fi
  exit "$rc"
}

# Reports the session journal. Only lines of the exact journal forms are passed on, and only when every line is one
# and the file ends with a line break, so nothing else from the device can reach the build host this way.
report_sessions() {
  local f=$out/device/sessions.txt line lines=()
  if [ -z "$key_sent" ]; then ask SESSIONS-NONE; return; fi # the key never left the build host: no app session can exist
  if [ "$1" != 0 ] || [ ! -f "$f" ] || [ -L "$f" ]; then rmsg journal-unreadable; ask SESSIONS-UNKNOWN; return; fi
  if [ -s "$f" ] && [ "$(tail -c1 "$f" | od -An -tx1 | tr -d ' ')" != 0a ]; then rmsg journal-cut; ask SESSIONS-UNKNOWN; return; fi
  while IFS= read -r line; do
    if journal_line_ok "$line"; then lines+=("$line"); else rmsg journal-malformed; ask SESSIONS-UNKNOWN; return; fi
  done <"$f"
  for line in "${lines[@]}"; do ask "J $line"; done
  ask "SESSIONS-END ${#lines[@]}"
}

stop_emulator() {
  local ok=0
  if [ -n "$emu_pgid" ]; then
    ADB_TIMEOUT=10 adb -s "$emu_serial" emu kill >/dev/null 2>&1
    if ! wait_gone 30 group_alive "$emu_pgid"; then
      kill -TERM -- "-$emu_pgid" 2>/dev/null
      wait_gone 10 group_alive "$emu_pgid" || { kill -KILL -- "-$emu_pgid" 2>/dev/null; wait_gone 5 group_alive "$emu_pgid"; }
    fi
    if group_alive "$emu_pgid"; then rmsg emu-remains; ok=1; else rmsg emu-stopped; fi
  fi
  if [ -n "$adb_pid" ]; then
    ADB_TIMEOUT=10 adb kill-server >/dev/null 2>&1
    wait_gone 10 alive "$adb_pid" || stop_pid "$adb_pid"
    if alive "$adb_pid"; then rmsg adb-remains; ok=1; fi
  fi
  return "$ok"
}

delete_avd() {
  bounded 120 avdmanager delete avd -n "$AVD" >>"$out/avdmanager.log" 2>&1 </dev/null
  [ ! -e "$ANDROID_AVD_HOME/$AVD.avd" ] && [ ! -e "$ANDROID_AVD_HOME/$AVD.ini" ]
}

# Stages the allowlisted files in $out/export, scans them and seals them; the checksums go to $manifest.
export_run() {
  local exp=$out/export
  mkdir "$exp" || { rmsg export-failed; return "$SCANFAIL_EXIT"; }
  stage "$exp" || { rmsg export-failed; remove_tree "$exp"; return "$SCANFAIL_EXIT"; }
  scan_seal "$exp"
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

# Scans every staged file for the key (patterns from the build host, read now), then hashes them and makes them and
# their directory read-only; "FILE <sha256> <name>" lines go to $manifest. Any failure deletes the staged files and
# reports only a fixed code.
scan_seal() {
  local exp=$1 n p pats=() rc=0 i lines=() files=() h f
  manifest=()
  ask PATTERNS
  IFS= read -r -t 60 -u "$proto" n && [[ $n =~ ^[1-9][0-9]?$ ]] || { rmsg scan-no-patterns; remove_tree "$exp"; return "$SCANFAIL_EXIT"; }
  for ((i = 0; i < n; i++)); do
    IFS= read -r -t 10 -u "$proto" p && [ "${#p}" -ge 16 ] || { rmsg scan-bad-patterns; remove_tree "$exp"; return "$SCANFAIL_EXIT"; }
    pats+=("$p")
  done
  scan_dir "$exp" "${pats[@]}" || rc=$?
  pats=()
  case $rc in
    1) ;;
    0) rmsg scan-leak; remove_tree "$exp"; return "$LEAK_EXIT" ;;
    *) rmsg scan-error; remove_tree "$exp"; return "$SCANFAIL_EXIT" ;;
  esac
  for f in "$exp"/*; do
    [ -e "$f" ] || continue
    if [ ! -f "$f" ] || [ -L "$f" ] || ! allowed "${f##*/}" || ! h=$(sha256sum "$f" 2>/dev/null); then
      rmsg seal-failed; remove_tree "$exp"; return "$SCANFAIL_EXIT"
    fi
    lines+=("FILE ${h%% *} ${f##*/}")
    files+=("$f")
  done
  chmod a-w -- "${files[@]}" "$exp" 2>/dev/null || { rmsg seal-failed; remove_tree "$exp"; return "$SCANFAIL_EXIT"; }
  manifest=("${lines[@]}")
  rmsg sealed
}

# scan_dir <dir> <patterns...>: grep's own exit (0 found, 1 clean, else error); prints nothing, ever.
scan_dir() {
  local d=$1
  shift
  grep -rlaF -f <(printf '%s\n' "$@") -- "$d" >/dev/null 2>&1
}

# ================================================================ build host
key='' nonce='' run_id='' tmp='' interrupted='' key_sent_local='' mode='' arg=''
tun_fd='' tunnel_pid='' tunnel_port='' rin='' rout='' remote_pid='' remote_shell_pid='' last_line=''
sessions_state='' journal=() export_files=() export_state='' lock_tried='' locked='' released='' ports_before=''
incoming='' wd_pid=''

# Every SSH call: output captured by the caller (never printed), stderr discarded by the caller.
rsh() { bounded "${RSH_TIMEOUT:-60}" ssh -o BatchMode=yes "$HOST" "$@"; }
# Runs one of the shared functions on the emulator host: rsh_fn <function> <args...>.
rsh_fn() { { declare -f "${SHARED_FNS[@]}"; printf '%q ' "$@"; echo; } | rsh bash -s; }

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

# The steered session's record on the server: exactly one steer row of exactly the steering text, every ordinary
# user row before it, and after it the last final answer, ending with the word BANANA.
# shellcheck disable=SC2016 # jq code
STEER_JQ='
  def unwrap: sub("^\\[OUT-OF-BAND USER MESSAGE[^\\]\n]*\\]\n"; "") | rtrimstr($end);
  (.data | to_entries) as $e
  | [$e[] | select(.value.role == "user" and .value.display_kind == "steer")] as $s
  | [$e[] | select(.value.role == "assistant" and .value.finish_reason == "stop" and ((.value.content // "") != ""))] as $f
  | .session_id == $id and ($s | length) == 1
    and ($s[0].value.content | test("^\\[OUT-OF-BAND USER MESSAGE[^\\]\n]*\\]\n")) and ($s[0].value.content | endswith($end))
    and ($s[0].value.content | unwrap) == $text
    and ([$e[] | select(.value.role == "user" and .value.display_kind != "steer") | .key] | all(. < $s[0].key))
    and ($f | length) > 0 and ($f | last | .key) > $s[0].key and ($f | last | .value.content | test("\\bBANANA\\W*$"))'
steer_ok_json() { jq -e --arg id "$1" --arg text "$2" --arg end $'\n[/OUT-OF-BAND USER MESSAGE]' "$STEER_JQ" "$3" >/dev/null 2>&1; }

# Checks and deletes the sessions the journal names; reads nothing on the server that the journal does not name.
# Every "send <chat>" must have at least one journaled server id of that chat, otherwise sessions may remain. Each
# id is first proved to be this run's (a GET on that id returns that id, an app id, with this run's nonce in its
# title). Then, for the steer scenario, its record must show the steered turn; for a journaled run, its status must
# name that session and be cancelled. Then the session is deleted and checked gone.
check_sessions() {
  local l k id scen run body ok=0 n code steer_ok='' stop_ok='' verified=() v incomplete=''
  if [ -z "$key_sent_local" ]; then log "sessions: the key never left this host, so the app created none"; return 0; fi
  if [ "$sessions_state" != ok ]; then
    log "FAILED: the session journal is missing or unreadable; sessions this run created may remain on the server"
    return 1
  fi
  for l in "${journal[@]}"; do
    [ "${l%% *}" = send ] || continue
    read -r k _ <<<"${l#send }"
    printf '%s\n' "${journal[@]}" | grep -q "^session $k " || incomplete=1
  done
  if [ -n "$incomplete" ]; then
    log "FAILED: the session journal is incomplete (a message was sent, but no server id of its chat was journaled); sessions this run created may remain on the server"
    ok=1
  elif [ "${#journal[@]}" = 0 ]; then
    log "sessions: the journal is complete and empty (nothing was sent)"
  fi
  body=$(mktemp)
  for l in "${journal[@]}"; do
    [ "${l%% *}" = session ] || continue
    read -r k id scen <<<"${l#session }"
    [[ $id =~ ^$SESSION_RE$ ]] || { log "FAILED: sessions: a malformed id; left alone"; ok=1; continue; }
    for v in "${verified[@]}"; do [ "$v" != "$id" ] || continue 2; done
    code=$(hermes GET "api/sessions/$id" "$body") || code=error
    if [ "$code" = 404 ]; then log "sessions: $id not on the server (already gone)"; continue; fi
    if [ "$code" != 200 ] || ! jq -e --arg id "$id" --arg n "$nonce" \
      '.session | .id == $id and (.id | startswith("apofis-")) and ((.title // "") | contains($n))' "$body" >/dev/null 2>&1; then
      log "FAILED: sessions: $id answered $code without this run's identity; left alone"
      ok=1
      continue
    fi
    verified+=("$id")
    if [ "$scen" = steerDuringRun ]; then
      code=$(hermes GET "api/sessions/$id/messages?order=oldest&include_compacted=true" "$body") || code=error
      if [ "$code" = 200 ] && steer_ok_json "$id" "$(steering_text)" "$body"; then
        log "server: $id holds one steer row of exactly the steering text, then the final answer ending with BANANA"
        steer_ok=1
      else
        log "FAILED: server: $id does not show the steered turn in order (HTTP $code)"
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
  if [[ $mode == full || ($mode == scenario && $arg == steerDuringRun) ]] && [ -z "$steer_ok" ]; then log "FAILED: server: no checked steer turn"; ok=1; fi
  if [[ $mode == full || ($mode == scenario && $arg == stopDuringRun) ]] && [ -z "$stop_ok" ]; then log "FAILED: server: no checked cancelled run"; ok=1; fi
  return "$ok"
}

# Lists the user's home and config entries and /tmp entries owned by the user on the emulator host (never printed).
listing() {
  # shellcheck disable=SC2016
  rsh 'set -e; u=$(id -u)
    echo "## ~"; ls -A ~
    for d in .android .config .cache .local .local/share .local/state; do echo "## ~/$d"; [ ! -d ~/$d ] || ls -A ~/$d; done
    echo "## /tmp owned by the user"; find /tmp -mindepth 1 -maxdepth 1 -user "$u" -printf "%f\n" | sort' 2>/dev/null
}

lock_help() {
  log "FAILED: the emulator host lock $1. This script never takes a lock over or removes another run's lock."
  log "  If that run is dead, check that none of its processes is alive (this must print nothing):"
  log "    ssh $HOST \"pgrep -u \\\$(id -u) -af -- '$MANUAL_RE'\""
  log "  then remove the lock (one file and its directory):"
  log "    ssh $HOST 'rm -f ~/$REMOTE_DIR/.state/lock.d/owner && rmdir ~/$REMOTE_DIR/.state/lock.d'"
}

# Stage 0: takes the emulator host lock for this run id. Asking twice is safe (the same id gets TAKEN again), so a
# lost answer is retried. A lock of another run stops the run here, before anything is changed.
take_lock() {
  local r='' o _
  lock_tried=1
  for _ in 1 2; do
    r=$(rsh_fn lock_take "$REMOTE_DIR/.state" "$run_id" 2>/dev/null) || r=''
    [ -z "$r" ] || break
  done
  case $r in
    TAKEN) locked=1 ;;
    BUSY\ *) o=${r#BUSY }; [[ $o =~ ^$RUNID_RE$ ]] || o=unknown; lock_help "is held by run $o"; exit 1 ;;
    *) die "stage 0: no valid answer from $HOST when taking its lock" ;;
  esac
}

# The postcheck's expected report, token by token.
PROBE_EXPECTED=(EMULATOR=absent ADB=absent REMOTE=absent AVD=absent RUNDIR=absent LOCK=released PORTS=closed DONE)

# verify_probe <report>: the report must be exactly the expected tokens; names the facts it could not confirm.
verify_probe() {
  local t missing=''
  [ "$1" = "$(printf '%s\n' "${PROBE_EXPECTED[@]}")" ] && return 0
  for t in "${PROBE_EXPECTED[@]}"; do grep -qxF -- "$t" <<<"$1" || missing+=" $t"; done
  log "FAILED: postcheck on $HOST: not confirmed:${missing:- (unexpected extra output)}"
  return 1
}
procs_gone() { grep -qx EMULATOR=absent <<<"$1" && grep -qx ADB=absent <<<"$1" && grep -qx REMOTE=absent <<<"$1"; }
probe_remote() {
  RSH_TIMEOUT=$1 rsh_fn probe "$REMOTE_DIR/.state" "$run_id" "$PORT_RANGES" "$ports_before" "$EMU_RE" "$ADB_RE" "$SCRIPT_RE" 2>/dev/null
}

# Within one deadline: waits until no e2e process is left on the emulator host, removes this run's directory,
# releases the lock (only when it holds this run's id), and verifies every expected token. Running out of time,
# a missing token or an unexpected value is a failure.
postcheck() {
  local deadline=$((SECONDS + SHUTDOWN_LIMIT)) report='' r t
  budget() { local l=$((deadline - SECONDS)); [ "$l" -le 30 ] || l=30; [ "$l" -gt 0 ] && echo "$l"; }
  while t=$(budget); do
    report=$(probe_remote "$t") || report=''
    procs_gone "$report" && break
    sleep 3
  done
  if procs_gone "$report"; then
    t=$(budget) && RSH_TIMEOUT=$t rsh_fn remove_tree "$REMOTE_DIR/.state/runs/$run_id" >/dev/null 2>&1
    if t=$(budget); then
      r=$(RSH_TIMEOUT=$t rsh_fn lock_release "$REMOTE_DIR/.state" "$run_id" 2>/dev/null) || r=''
      if [ "$r" = RELEASED ]; then released=1; log "emulator host lock released"; fi
    fi
    while t=$(budget); do
      report=$(probe_remote "$t") || report=''
      verify_probe "$report" 2>/dev/null && break
      sleep 3
    done
  fi
  if ! verify_probe "$report"; then
    [ -n "$released" ] || lock_help "may still be held by this run ($run_id): its processes could not be verified gone"
    return 1
  fi
  log "verified on $HOST: no e2e process, AVD, run directory or new listener left, lock released"
}

# Removes what this run left in build/e2e unverified and its temp files, and stops the cleanup's watchdog.
end_local() {
  drop_incoming
  if [ -n "$wd_pid" ]; then kill -TERM "$wd_pid" 2>/dev/null; wait "$wd_pid" 2>/dev/null; wd_pid=''; fi
  if [ -n "$tmp" ]; then rm -f "$tmp/in" "$tmp/out" "$tmp/before.txt" "$tmp/after.txt" "$tmp/files"; rmdir "$tmp"; tmp=''; fi
}

cleanup_overrun() {
  log "FAILED: the cleanup did not finish within ${TRAP_LIMIT}s and stopped there; sessions or emulator host checks may be incomplete"
  [ -z "$lock_tried" ] || [ -n "$released" ] || lock_help "may still be held by this run ($run_id)"
  wd_pid=''
  end_local
  key=''
  exit 1
}

local_cleanup() {
  local rc=$? bad='' r new
  set +e
  # The whole cleanup is bounded: a watchdog interrupts it after TRAP_LIMIT seconds. It starts before the signals are
  # ignored, so it can still be stopped with TERM.
  trap cleanup_overrun ALRM
  (
    trap 'kill "$s" 2>/dev/null; exit 0' TERM
    sleep "$TRAP_LIMIT" & s=$!
    wait "$s" && kill -ALRM $$
  ) 2>/dev/null &
  wd_pid=$!
  trap '' INT TERM HUP
  if [ -n "$remote_pid" ]; then stop_pid "$remote_pid" && wait "$remote_pid" 2>/dev/null; fi
  [ -z "$rin" ] || exec {rin}>&-
  [ -z "$rout" ] || exec {rout}<&-
  if [ -n "$tun_fd" ]; then exec {tun_fd}>&-; wait_gone 10 alive "$tunnel_pid" || stop_pid "$tunnel_pid"; fi
  if [ -n "$locked" ]; then
    postcheck || bad=1
  elif [ -n "$lock_tried" ]; then
    # The lock answer was lost or refused: remove the lock only if it holds this run's id.
    r=$(RSH_TIMEOUT=30 rsh_fn lock_release "$REMOTE_DIR/.state" "$run_id" 2>/dev/null) || r=''
    case $r in
      RELEASED) released=1; log "emulator host lock (taken before its answer was lost) released" ;;
      NOT-OURS | ABSENT) released=1 ;;
      *) lock_help "may be held by this run ($run_id)"; bad=1 ;;
    esac
  fi
  [ -z "$key" ] || check_sessions || bad=1
  if [ -n "$locked" ]; then
    if listing >"$tmp/after.txt"; then
      new=$(comm -13 <(sort "$tmp/before.txt") <(sort "$tmp/after.txt") | grep -c .)
      if [ "$new" != 0 ]; then log "FAILED: $new new entries in ~, ~/.android, ~/.config, ~/.cache, ~/.local or the user's /tmp entries on $HOST"; bad=1
      else log "verified on $HOST: no new entries in ~, ~/.android, ~/.config, ~/.cache, ~/.local or the user's /tmp entries"; fi
    else
      log "FAILED: could not list the emulator host's home after the run"; bad=1
    fi
  fi
  for r in "$tunnel_pid" "$remote_pid"; do
    [ -z "$r" ] || ! alive "$r" || { log "FAILED: local process $r is still running"; bad=1; }
  done
  end_local
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
      RSH_TIMEOUT=20 rsh "grep -qa -- --in-shell /proc/$remote_shell_pid/cmdline && kill -TERM $remote_shell_pid" >/dev/null 2>&1 ||
        { log "could not signal the remote run; closing the connection instead"; stop_pid "$remote_pid"; }
    else
      stop_pid "$remote_pid" # not started yet: its first write fails and it cleans up
    fi
  else
    exit "$1"
  fi
}

remote_msg() {
  if [[ $1 =~ ^[a-z0-9-]{1,30}$ ]] && [ -n "${REMOTE_MSG[$1]+x}" ]; then log "$HOST: ${REMOTE_MSG[$1]}"
  else log "an unexpected line from $HOST (not shown)"; fi
}

# Runs the remote part and serves its requests. Only its stdout is read, as protocol lines; its stderr is discarded.
run_remote() {
  local tests=$1 line='' partial='' r deadline='' n
  mkfifo -m 600 "$tmp/in" "$tmp/out"
  # shellcheck disable=SC2029 # the arguments are meant to expand here
  bounded "$REMOTE_TIMEOUT" ssh -o BatchMode=yes -o ServerAliveInterval=15 \
    -o ServerAliveCountMax=4 "$HOST" "cd ~/$REMOTE_DIR && exec tools/emulator/run-e2e.sh --remote $tests $tunnel_port $run_id $nonce" \
    <"$tmp/in" >"$tmp/out" 2>/dev/null &
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
      last_line=$line
      case $line in
        '@@HB@@') ;;
        '@@MSG '*'@@') r=${line#@@MSG }; remote_msg "${r%@@}" ;;
        '@@PID '*'@@') r=${line#@@PID }; r=${r%@@}; [[ $r =~ ^[0-9]+$ ]] && remote_shell_pid=$r ;;
        '@@KEY@@') if [ -z "$interrupted" ]; then key_sent_local=1; printf '%s\n' "$key" >&"$rin" || true; else printf '\n' >&"$rin" || true; fi ;;
        '@@PATTERNS@@') key_patterns >&"$rin" || true ;;
        '@@J '*'@@')
          r=${line#@@J }; r=${r%@@}
          if journal_line_ok "$r"; then journal+=("$r"); else sessions_state=bad; fi ;;
        '@@SESSIONS-END '*'@@')
          n=${line#@@SESSIONS-END }; n=${n%@@}
          if [ "$sessions_state" != bad ] && [ "$n" = "${#journal[@]}" ]; then sessions_state=ok; else sessions_state=bad; fi ;;
        '@@SESSIONS-NONE@@') sessions_state=none ;;
        '@@SESSIONS-UNKNOWN@@') sessions_state=unknown ;;
        '@@FILE '*'@@')
          r=${line#@@FILE }; r=${r%@@}
          if [ -z "$export_state" ] && [[ $r =~ ^[0-9a-f]{64}\ [A-Za-z0-9.-]+$ ]] && allowed "${r#* }"; then export_files+=("$r"); else export_state=bad; fi ;;
        '@@COMPLETE '*'@@')
          r=${line#@@COMPLETE }; r=${r%@@}
          if [ -z "$export_state" ] && [ "$r" = "$run_id ${#export_files[@]}" ]; then export_state=ok; else export_state=bad; fi ;;
        *) log "an unexpected line from $HOST (not shown)" ;;
      esac
    else
      r=$?
      # A 5 s tick or a signal whose trap has run; read keeps what it got of a line so far, so nothing is lost.
      [ "$r" -gt 128 ] && { partial+=$line; continue; }
      partial+=$line
      [ -z "$partial" ] || last_line=$partial # an unfinished last line: never the completion token
      break # end of the remote output
    fi
  done
  exec {rin}>&- {rout}<&-
  rin='' rout=''
  r=0
  wait "$remote_pid" 2>/dev/null || r=$?
  remote_pid=''
  return "$r"
}

# The export may be copied only when the remote stage exited 0, its last output line was exactly this run's
# completion token, and the manifest before it parsed. A timeout, a dropped connection or anything else copies nothing.
remote_complete() {
  [ "$1" = 0 ] && [ "$export_state" = ok ] && [ "$last_line" = "@@COMPLETE $run_id ${#export_files[@]}@@" ]
}

# Removes the files this run copied into the incoming directory (only allowlisted names: rm -f, or rmdir for an
# empty directory a type change left), then the directory itself.
drop_incoming() {
  local f
  [ -n "$incoming" ] && [ -d "$incoming" ] || { incoming=''; return 0; }
  while IFS= read -r f; do
    if [ -d "$incoming/$f" ] && [ ! -L "$incoming/$f" ]; then rmdir -- "$incoming/$f" 2>/dev/null; else rm -f -- "$incoming/$f"; fi
  done < <(allowlist)
  rmdir -- "$incoming" 2>/dev/null || log "FAILED: $incoming has entries this run did not copy; left alone"
  incoming=''
}

# import_export <source>: copies exactly the reported files into $E2E_OUT/.incoming-<run id> (new, never descending
# into directories), verifies each checksum, that nothing else arrived, and scans the copy; then publishes it with one
# rename as $E2E_OUT/<run id>. On any failure or interrupt the incoming files are deleted (here or in the cleanup).
import_export() {
  local src=$1 final=$E2E_OUT/$run_id e h f fail=''
  if [ -e "$final" ] || [ -e "$E2E_OUT/.incoming-$run_id" ] || ! mkdir -p "$E2E_OUT"; then
    log "FAILED: stage copy: could not create a new incoming directory in $E2E_OUT; nothing copied"
    return 1
  fi
  incoming=$E2E_OUT/.incoming-$run_id
  mkdir -- "$incoming" || { incoming=''; log "FAILED: stage copy: could not create $E2E_OUT/.incoming-$run_id; nothing copied"; return 1; }
  for e in "${export_files[@]}"; do printf '%s\n' "${e#* }"; done >"$tmp/files"
  bounded 600 rsync --times --chmod=Fu+rw --files-from="$tmp/files" "$src" "$incoming/" >/dev/null 2>&1 || fail="copy"
  if [ -z "$fail" ]; then
    for e in "${export_files[@]}"; do
      h=${e%% *} f=${e#* }
      [ -f "$incoming/$f" ] && [ ! -L "$incoming/$f" ] && [ "$(sha256sum "$incoming/$f" | cut -d' ' -f1)" = "$h" ] || { fail="checksum"; break; }
    done
  fi
  [ -n "$fail" ] || [ "$(find "$incoming" -mindepth 1 | wc -l)" = "${#export_files[@]}" ] || fail="checksum"
  if [ -z "$fail" ]; then
    local src_rc=0
    scan_dir "$incoming" "$(key_patterns | tail -n +2)" || src_rc=$?
    case $src_rc in 1) ;; 0) fail="local scan (key found)" ;; *) fail="local scan (could not complete)" ;; esac
  fi
  if [ -z "$fail" ] && mv -T -- "$incoming" "$final"; then incoming=''; return 0; fi
  drop_incoming
  log "FAILED: stage ${fail:-publish}: the copied files were deleted; nothing kept in $E2E_OUT"
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
    # From the verified copy (checksums matched, key absent): the test failures' stack traces.
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
  take_lock
  listing >"$tmp/before.txt" || die "could not list the emulator host's home"
  r=$(RSH_TIMEOUT=30 rsh_fn listeners_now "$PORT_RANGES" 2>/dev/null) || r=''
  [[ $r =~ ^LISTENERS=(none|[0-9]+(,[0-9]+)*)$ ]] || die "could not list the emulator host's listeners"
  ports_before=${r#LISTENERS=}

  log "stage 1: rsync to $HOST:~/$REMOTE_DIR"
  # shellcheck disable=SC2029
  rsh "cd ~/$REMOTE_DIR && { [ -d .git ] || git init -q; } && echo /.state/ > .git/info/exclude" >/dev/null 2>&1 || die "could not prepare $HOST"
  bounded 600 rsync -a --delete --filter=':- .gitignore' --exclude=/.git --exclude=/.state/ ./ "$HOST:$REMOTE_DIR/" >/dev/null 2>&1 ||
    die "rsync failed"
  # The flake only sees files git knows about; .gitignore keeps build output out of the Nix store copy.
  # shellcheck disable=SC2029
  RSH_TIMEOUT=120 rsh "cd ~/$REMOTE_DIR && git add -A" >/dev/null 2>&1 || die "git add failed on $HOST"

  log "stage 2: reverse tunnel to Hermes"
  # shellcheck disable=SC2016
  tunnel_port=$(rsh 'for p in $(seq 18642 18662); do o=$(ss -Hltn "sport = :$p") || exit 1; [ -n "$o" ] || { echo "$p"; exit 0; }; done; exit 1' 2>/dev/null) ||
    tunnel_port=''
  [[ $tunnel_port =~ ^18[0-9]{3}$ ]] || die "no free tunnel port on $HOST"
  # Its stdin is held by this script: when this script dies for any reason, the remote cat ends and the tunnel closes.
  exec {tun_fd}> >(exec ssh -o BatchMode=yes -o ExitOnForwardFailure=yes -o ServerAliveInterval=15 \
    -R "127.0.0.1:$tunnel_port:127.0.0.1:$HERMES_PORT" "$HOST" 'exec cat >/dev/null' >/dev/null 2>&1)
  tunnel_pid=$!
  local listen=''
  for _ in $(seq 20); do
    # shellcheck disable=SC2029
    listen=$(RSH_TIMEOUT=20 rsh "ss -Hltn 'sport = :$tunnel_port'" 2>/dev/null | awk '{print $4}' | sort -u) || listen=''
    [ -z "$listen" ] || break
    alive "$tunnel_pid" || die "the tunnel exited"
    sleep 1
  done
  # The requested bind is not enough: the server's SSH policy could widen it, so the actual listener is checked.
  [ "$listen" = "127.0.0.1:$tunnel_port" ] || die "the tunnel does not listen on 127.0.0.1:$tunnel_port only"
  log "tunnel up: $HOST 127.0.0.1:$tunnel_port -> 127.0.0.1:$HERMES_PORT (verified loopback only)"

  local rc=0
  run_remote "$tests" || rc=$?
  if [ -n "$interrupted" ]; then log "interrupted: nothing copied back from $HOST"; exit "$interrupted"; fi
  remote_complete "$rc" ||
    die "stage remote run: exit $rc without this run's completion token after a valid manifest; nothing copied back"
  log "copying the ${#export_files[@]} scanned files to $E2E_OUT/$run_id"
  import_export "$HOST:$REMOTE_DIR/.state/runs/$run_id/export/" || exit "$SCANFAIL_EXIT"
  log "copied and verified (checksums match, key absent from the copy)"
  evaluate "$E2E_OUT/$run_id" || die "tests failed or evidence is missing; files in $E2E_OUT/$run_id"
  log "PASSED; files in $E2E_OUT/$run_id (server checks and cleanup follow)"
}

[ -n "${APOFIS_E2E_LIB:-}" ] || main "$@"
