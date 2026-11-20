#!/usr/bin/env bash
# End-to-end tests on a headless emulator on another host. See tools/emulator/README.md.
#
#   tools/emulator/run-e2e.sh --smoke             connect tests and the harness self-tests, no model run
#   tools/emulator/run-e2e.sh --full              every e2e test: smoke, self-tests and the seven survival scenarios
#   tools/emulator/run-e2e.sh --scenario <name>   one survival scenario, e.g. stopDuringRun
#   tools/emulator/run-e2e.sh --test <Class[#method]>   any test in the e2e module
#
# Runs on the build host; it calls itself on the emulator host with --remote and --in-shell (internal).
set -euo pipefail

HOST=${APOFIS_E2E_HOST:-obi-one}
REMOTE_DIR=tmp/apofis-e2e          # relative to the remote home
HERMES_PORT=${APOFIS_HERMES_PORT:-8642}
APOFIS_E2E_CANARY=${APOFIS_E2E_CANARY:-}
BUILD_TIMEOUT=${APOFIS_BUILD_TIMEOUT:-1200}
BOOT_TIMEOUT=${APOFIS_BOOT_TIMEOUT:-300}
TEST_TIMEOUT=${APOFIS_TEST_TIMEOUT:-1800}
LEAK_EXIT=3
SCANFAIL_EXIT=4
CLEANUP_EXIT=5
DEVICE_OUT=/data/local/tmp/apofis-e2e
PKG=xyz.rycerz.apofis.e2e
RUNNER=$PKG/androidx.test.runner.AndroidJUnitRunner
AVD=apofis-e2e
IMAGE="system-images;android-37.0;default;x86_64"
SESSION_RE='^apofis-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'
SMOKE=(connectViaLoopback connectViaPrivateAddress privateAddressAsksForLocalNetwork)
SCENARIOS=(connectAndChat replySurvivesAirplaneMode replySurvivesScreenOff replySurvivesProcessDeath
  offlineMessageSendsItself steerDuringRun stopDuringRun)

log() { printf '[e2e %s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die() { log "FAILED: $*"; exit 1; }

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

# ================================================================ emulator host
proto=''      # fd of the channel to the build host (the SSH session's stdin): the key and the scan patterns arrive here
child=''      # the long step in progress (build, instrumentation), stopped by the cleanup
out='' state='' root=''
emu_pgid='' emu_serial='' adb_pid='' booted='' avd_made='' hb_pid=''

# A request or report to the build host, on stdout. Logs go to stderr; tool output goes to files only.
ask() { printf '@@%s@@\n' "$*"; }
# Resources this run started, read by the build host's postcheck.
own() { printf '%s\n' "$*" >>"$out/owned.txt"; }
# Runs a long step in the background without the protocol channel or the lock, so a signal can interrupt the wait.
bg() { "$@" </dev/null {proto}<&- 8>&- & child=$!; local rc=0; wait "$child" || rc=$?; child=''; return "$rc"; }
# adb with a deadline and no stdin (adb shell would otherwise read the protocol channel).
adb() { timeout "${ADB_TIMEOUT:-60}" "$ADB_BIN" "$@" </dev/null; }
free_port() { local o; o=$(ss -Hltn "sport = :$1") || die "ss failed"; [ -z "$o" ]; }

remote() {
  local tests=$1 tunnel_port=$2 run_id=$3 nonce=$4
  [[ $tests =~ ^(all|smoke|[A-Za-z0-9_#]+)$ && $tunnel_port =~ ^[0-9]+$ && $run_id =~ ^[0-9]{8}-[0-9]{6}-[0-9a-f]{4}$ && $nonce =~ ^[0-9a-f]{8}$ ]] ||
    die "bad arguments"
  root=$HOME/$REMOTE_DIR
  state=$root/.state
  # The build host holds this lock for the whole run; refuse to run outside it.
  ! flock -n "$state/lock" true || die "the emulator host lock is not held; run tools/emulator/run-e2e.sh on the build host"
  out=$state/runs/$run_id
  [ ! -e "$out" ] || die "run directory $out exists"
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
  local tests=$1 tunnel_port=$2 nonce=$4 p filter
  out=$3
  state=${out%/runs/*}
  root=${state%/.state}
  exec {proto}<&0 </dev/null
  exec 8>"$state/run.lock"
  flock -w 60 8 || die "the previous run on $(hostname) is still cleaning up"
  ADB_BIN=$(command -v adb)
  trap finish EXIT
  trap 'exit 129' HUP
  trap 'exit 130' INT
  trap 'exit 143' TERM
  trap 'exit 141' PIPE
  ask "PID $$"
  heartbeat $$ </dev/null {proto}<&- 8>&- &
  hb_pid=$!
  own "tunnel-port=$tunnel_port"
  case $tests in
    all) filter="-e package $PKG" ;;
    smoke) filter="-e class $PKG.SmokeE2eTest,$PKG.HarnessSelfTest" ;;
    *) filter="-e class $PKG.$tests" ;;
  esac

  log "stage 3: building the app and test APKs (no key exists on this host)"
  bg timeout "$BUILD_TIMEOUT" ./gradlew --no-daemon --no-configuration-cache --console=plain \
    -Pkotlin.compiler.execution.strategy=in-process :app:assembleDebug :e2e:assembleDebug >"$out/gradle.log" 2>&1 ||
    die "build failed (gradle.log is shown after the leak scan)"

  # AGP is not used to run tests any more, but adb's default server port must still be free: never share a server.
  free_port 5037 || die "port 5037 is in use (another adb server?); not sharing it"
  local ep=''
  for p in $(seq 5554 2 5584); do free_port "$p" && free_port $((p + 1)) && { ep=$p; break; }; done
  [ -n "$ep" ] || die "no free emulator port pair"
  emu_serial=emulator-$ep
  export ANDROID_SERIAL=$emu_serial
  own "port=5037"
  own "port=$ep"
  own "port=$((ep + 1))"

  log "stage 4: emulator (console port $ep, adb port $((ep + 1)))"
  emulator -accel-check >"$out/accel-check.txt" 2>&1 </dev/null || die "no KVM acceleration (accel-check.txt)"
  if [ -e "$ANDROID_AVD_HOME/$AVD.ini" ]; then
    log "deleting the AVD a previous run left behind"
    delete_avd || die "could not delete the old AVD"
  fi
  avd_made=1
  echo no | timeout 300 avdmanager create avd -n "$AVD" -k "$IMAGE" -d pixel_7 >"$out/avdmanager.log" 2>&1 || die "avdmanager failed"
  # Our own adb server in the foreground, so it is stopped by its pid.
  setsid "$ADB_BIN" -P 5037 nodaemon server </dev/null >"$out/adb-server.log" 2>&1 {proto}<&- 8>&- &
  adb_pid=$!
  own "pid=$adb_pid"
  wait_gone 15 free_port 5037 || die "the adb server did not start"
  # A clean environment: no inherited variables, every writable location inside $state, no crash reports, no
  # metrics and no core dumps. It starts long before the key exists in this shell.
  local envs=() v
  for v in PATH HOME ANDROID_HOME ANDROID_SDK_ROOT ANDROID_USER_HOME ANDROID_EMULATOR_HOME ANDROID_AVD_HOME ANDROID_TMP \
    TMPDIR TMP TEMP XDG_CONFIG_HOME XDG_CACHE_HOME XDG_DATA_HOME XDG_STATE_HOME XDG_RUNTIME_DIR; do
    [ -z "${!v-}" ] || envs+=("$v=${!v}")
  done
  local start=$SECONDS
  (ulimit -c 0 && exec setsid env -i "${envs[@]}" emulator -avd "$AVD" -port "$ep" -no-window -no-audio -no-snapshot \
    -wipe-data -no-boot-anim -no-metrics -crash-report-mode disabled -accel on -gpu swiftshader_indirect -cores 4 \
    -memory 4096) </dev/null >"$out/emulator.log" 2>&1 {proto}<&- 8>&- &
  emu_pgid=$!
  own "pgid=$emu_pgid"
  while [ $((SECONDS - start)) -lt "$BOOT_TIMEOUT" ]; do
    group_alive "$emu_pgid" || die "emulator exited during boot (emulator.log)"
    [ "$(ADB_TIMEOUT=10 adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && { booted=1; break; }
    sleep 3
  done
  [ -n "$booted" ] || die "emulator did not boot within ${BOOT_TIMEOUT}s"
  log "booted in $((SECONDS - start))s; $(grep -m1 KVM "$out/accel-check.txt")"
  # adbd logs every shell command line: silenced before anything else runs (the key never is on one, but still).
  adb shell 'setprop log.tag.adbd S; settings put global window_animation_scale 0; settings put global transition_animation_scale 0;
    settings put global animator_duration_scale 0; settings put system screen_off_timeout 1800000;
    input keyevent 82; mkdir -p '"$DEVICE_OUT" >/dev/null

  # Two ways in: the emulator's host alias 10.0.2.2 (a private address: the app needs local network access on
  # API 37), and the device's loopback, which adb reverse maps to the tunnel (needs no permission; also used by
  # the tests' own API calls).
  adb reverse "tcp:$tunnel_port" "tcp:$tunnel_port" >/dev/null
  local hermes_url=http://127.0.0.1:$tunnel_port direct_url=http://10.0.2.2:$tunnel_port health=''
  for _ in $(seq 30); do
    # The sleep keeps nc's write side open: adb reverse drops the connection on a half-close.
    health=$(adb shell "(printf 'GET /health HTTP/1.0\\r\\n\\r\\n'; sleep 2) | nc -w 5 127.0.0.1 $tunnel_port | head -1" 2>&1 | tr -d '\r')
    [[ $health == *" 200 "* ]] && break
    sleep 2
  done
  [[ $health == *" 200 "* ]] || die "Hermes not reachable from the emulator at $hermes_url (last: ${health:-no answer})"
  log "emulator reaches Hermes at $hermes_url: $health"
  ADB_TIMEOUT=180 adb install -r -t app/build/outputs/apk/debug/app-debug.apk >"$out/install.log" 2>&1 || die "installing the app failed"
  ADB_TIMEOUT=180 adb install -r -t e2e/build/outputs/apk/debug/e2e-debug.apk >>"$out/install.log" 2>&1 || die "installing the tests failed"

  log "stage 5: instrumentation ($tests, Hermes at $direct_url and $hermes_url, nonce $nonce)"
  ask KEY
  # The key is read only inside this pipeline's first element, a subshell that hands it to adb's stdin and exits; the
  # device's shell reads it into a variable and passes it to am instrument. It is never on a host command line, in
  # an environment, in a file, or in this shell's own variables.
  { IFS= read -r -t 120 -u "$proto" k && [ -n "$k" ] && printf '%s\n' "$k"; } |
    timeout "$TEST_TIMEOUT" "$ADB_BIN" shell "IFS= read -r k && [ -n \"\$k\" ] && exec am instrument -w -r -e apofisKey \"\$k\" \
-e apofisUrl $hermes_url -e apofisDirectUrl $direct_url -e apofisNonce $nonce $filter $RUNNER || echo 'no key: instrumentation not started'" \
    >"$out/instrument.txt" 2>&1 {proto}<&- 8>&- &
  child=$!
  local irc=0
  wait "$child" || irc=$?
  child=''
  [ "$irc" = 0 ] || die "am instrument ended with exit $irc (124: timed out)"
  log "instrumentation finished"
}

# Device files, logcat and the session journal are collected; then every writer is stopped, the AVD (and with it the
# app's storage, the only place the key may rest) is deleted, and everything is scanned for the key before the
# build host may print or copy anything.
finish() {
  local rc=$? pull_rc=1
  set +e
  trap '' HUP INT TERM PIPE
  [ -z "$child" ] || stop_pid "$child"
  if [ -n "$booted" ]; then
    ADB_TIMEOUT=90 adb pull "$DEVICE_OUT" "$out/device" >"$out/pull.log" 2>&1
    pull_rc=$?
    ADB_TIMEOUT=60 adb logcat -d >"$out/logcat.txt" 2>&1 || log "logcat dump failed"
  fi
  report_sessions "$pull_rc"
  stop_emulator || rc=$CLEANUP_EXIT
  if [ -n "$avd_made" ]; then
    if delete_avd; then log "AVD deleted (the app's storage with it)"; else log "FAILED: could not delete the AVD"; rc=$CLEANUP_EXIT; fi
  fi
  scan
  local s=$?
  [ "$s" = 0 ] || rc=$s
  [ -z "$hb_pid" ] || stop_pid "$hb_pid"
  exit "$rc"
}

# Reports the journaled session ids. Only lines that are exactly an app session id are passed on, so nothing else
# from the device can reach the build host this way.
report_sessions() {
  local f=$out/device/sessions.txt bad=0 id
  if [ -z "$booted" ]; then ask SESSIONS-OK; return; fi # the app never ran: it cannot have created a session
  if [ "$1" != 0 ]; then log "FAILED: could not pull the device files (pull.log)"; ask SESSIONS-UNKNOWN; return; fi
  if [ -e "$f" ]; then
    while IFS= read -r id; do
      if [[ $id =~ $SESSION_RE ]]; then ask "SESSION $id"; else bad=$((bad + 1)); fi
    done <"$f"
  fi
  # No journal after a successful pull: no test reached its first send, which always comes after journaling.
  if [ "$bad" = 0 ]; then ask SESSIONS-OK; else log "FAILED: $bad malformed journal lines"; ask SESSIONS-UNKNOWN; fi
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
    if group_alive "$emu_pgid"; then log "emulator processes remain: $(pgrep -g "$emu_pgid" | tr '\n' ' ')"; ok=1; else log "emulator stopped"; fi
  fi
  if [ -n "$adb_pid" ]; then
    ADB_TIMEOUT=10 adb kill-server >/dev/null 2>&1
    wait_gone 10 alive "$adb_pid" || stop_pid "$adb_pid"
    if alive "$adb_pid"; then log "adb server $adb_pid remains"; ok=1; fi
  fi
  return "$ok"
}

delete_avd() {
  timeout 120 avdmanager delete avd -n "$AVD" >>"$out/avdmanager.log" 2>&1 </dev/null
  [ ! -e "$ANDROID_AVD_HOME/$AVD.avd" ] && [ ! -e "$ANDROID_AVD_HOME/$AVD.ini" ]
}

# Scans the whole work directory (sources, Gradle home, project .gradle and build output, the AVD directory, every
# run's artifacts, emulator log, logcat and instrumentation output) and the places earlier versions wrote outside
# it. The patterns (raw, URL-encoded and base64 forms of the key) come from the build host now, so the key does not
# wait in memory here during the run. Exit: 0 clean, LEAK_EXIT found (the files are deleted), SCANFAIL_EXIT error.
scan() {
  local n p pats=() extra=() hits rc=0 i
  ask PATTERNS
  IFS= read -r -t 60 -u "$proto" n && [[ $n =~ ^[1-9][0-9]?$ ]] || { log "FAILED: no scan patterns from the build host; nothing scanned"; return "$SCANFAIL_EXIT"; }
  for ((i = 0; i < n; i++)); do
    IFS= read -r -t 10 -u "$proto" p && [ "${#p}" -ge 16 ] || { log "FAILED: bad scan patterns"; return "$SCANFAIL_EXIT"; }
    pats+=("$p")
  done
  for p in "/tmp/android-$(id -un)" "/tmp/hsperfdata_$(id -un)" "/tmp/adb.$(id -u).log" "${root%/"$REMOTE_DIR"}/.android"; do
    [ ! -e "$p" ] || extra+=("$p")
  done
  log "scanning $root ${extra[*]} for the key"
  hits=$(grep -rlF -f <(printf '%s\n' "${pats[@]}") -- "$root" "${extra[@]}" 2>"$state/tmp/scan-errors.txt") || rc=$?
  pats=()
  case $rc in
    1) ;;
    0)
      log "the API key was found in these files on $(hostname); deleting them, copying nothing back:"
      while IFS= read -r p; do log "  $p"; rm -f -- "$p"; done <<<"$hits"
      return "$LEAK_EXIT" ;;
    *)
      log "FAILED: the scan itself failed (exit $rc); a failed scan is not a pass:"
      head -5 "$state/tmp/scan-errors.txt" >&2
      return "$SCANFAIL_EXIT" ;;
  esac
  (cd "$out" && find . -type f ! -name manifest.sha256 -print0 | sort -z | xargs -0 -r sha256sum) >"$out/manifest.sha256"
  log "key absent from $root and the old outside locations"
  ask "SCAN-OK $(sha256sum "$out/manifest.sha256" | cut -d' ' -f1)"
}

# ================================================================ build host
key='' nonce='' run_id='' tmp='' interrupted=''
lock_fd='' lock_pid='' tun_fd='' tunnel_pid='' tunnel_port='' rin='' rout='' remote_pid='' remote_shell_pid=''
scan_ok='' sessions_state='' session_ids=() locked='' remote_started=''

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

# Prints the files under the given paths that contain the key; exit 1 only when the scan ran and found nothing.
find_key() { grep -rlF -f <(key_patterns | tail -n +2) -- "$@"; }

# Calls the Hermes session API on the build host: hermes <method> <id> <body-file>; prints the HTTP status.
hermes() {
  curl -s -m 20 -o "$3" -w '%{http_code}' -X "$1" -H @<(printf 'Authorization: Bearer %s\n' "$key") \
    "http://127.0.0.1:$HERMES_PORT/api/sessions/$2"
}

# Deletes the sessions the journal names, each only after the server's own record proves it is this run's: a GET on
# that exact id returns that id, an app id, with this run's nonce in its title. Anything else is left alone and fails.
check_sessions() {
  local id code body ok=0 n
  case $sessions_state in
    ok) ;;
    unknown) log "FAILED: the session journal could not be read; sessions this run created may remain on the server"; return 1 ;;
    *) [ -n "$remote_started" ] || return 0 # nothing ran remotely, so the app never ran
       log "FAILED: no session report from $HOST; sessions this run created may remain on the server"; return 1 ;;
  esac
  [ "${#session_ids[@]}" -gt 0 ] || { log "sessions: none journaled"; return 0; }
  body=$(mktemp)
  for id in "${session_ids[@]}"; do
    [[ $id =~ $SESSION_RE ]] || { log "sessions: skipping a malformed id"; ok=1; continue; }
    code=$(hermes GET "$id" "$body") || code=error
    if [ "$code" = 404 ]; then log "sessions: $id not on the server (never sent, or already gone)"; continue; fi
    if [ "$code" != 200 ] || ! jq -e --arg id "$id" --arg n "$nonce" \
      '.session | .id == $id and (.id | startswith("apofis-")) and ((.title // "") | contains($n))' "$body" >/dev/null 2>&1; then
      log "FAILED: sessions: $id answered $code without this run's identity; left alone"
      ok=1
      continue
    fi
    for n in 1 2 3 4 5 6; do
      code=$(hermes DELETE "$id" "$body") || code=error
      [ "$code" = 409 ] || break # 409: a turn is still active; it ends shortly after a stop
      sleep 10
    done
    if [ "$code" = 200 ] && jq -e --arg id "$id" '.id == $id and .deleted == true' "$body" >/dev/null 2>&1 &&
      [ "$(hermes GET "$id" "$body")" = 404 ]; then
      log "sessions: $id (title with nonce $nonce) deleted and verified gone"
    else
      log "FAILED: sessions: deleting $id answered $code; it may remain"
      ok=1
    fi
  done
  rm -f "$body"
  return "$ok"
}

# Lists the user's home and config entries and /tmp entries owned by the user on the emulator host.
listing() {
  # shellcheck disable=SC2016
  timeout 60 ssh -o BatchMode=yes "$HOST" 'set -e; u=$(id -u)
    echo "## ~"; ls -A ~
    for d in .android .config .cache .local .local/share .local/state; do echo "## ~/$d"; [ ! -d ~/$d ] || ls -A ~/$d; done
    echo "## /tmp owned by the user"; find /tmp -mindepth 1 -maxdepth 1 -user "$u" -printf "%f\n" | sort'
}

# Asks the emulator host what is left of this run. Each line ends in absent/free (verified), PRESENT/HELD (left
# over) or ERROR (could not check); the script ends with DONE when it ran to the end.
probe() {
  # shellcheck disable=SC2016
  timeout 60 ssh -o BatchMode=yes "$HOST" bash -s -- "$run_id" "${tunnel_port:-none}" <<'EOF'
out=$HOME/tmp/apofis-e2e/.state/runs/$1
cd "$HOME/tmp/apofis-e2e/.state" || { echo "state dir ERROR"; exit 0; }
for l in lock run.lock; do if flock -n "$l" true; then echo "$l free"; else echo "$l HELD"; fi; done
port() { local o; if o=$(ss -Hltn "sport = :$1"); then [ -z "$o" ] && echo "port $1 absent" || echo "port $1 PRESENT"; else echo "port $1 ERROR"; fi; }
[ "$2" = none ] || port "$2"
if [ -e "$out/owned.txt" ]; then
  while IFS= read -r l; do
    v=${l#*=}
    case $l in
      pgid=*) pgrep -g "$v" >/dev/null; r=$?; case $r in 0) echo "group $v PRESENT";; 1) echo "group $v absent";; *) echo "group $v ERROR";; esac ;;
      pid=*) if [ -e "/proc/$v" ]; then echo "pid $v PRESENT"; else echo "pid $v absent"; fi ;;
      port=*|tunnel-port=*) port "$v" ;;
    esac
  done <"$out/owned.txt"
fi
pgrep -u "$(id -u)" -f -- '-avd apofis-e2e|run-e2e\.sh --in-shell|adb -P 5037 nodaemon' >/dev/null; r=$?
case $r in 0) echo "e2e processes PRESENT";; 1) echo "e2e processes absent";; *) echo "e2e processes ERROR";; esac
echo DONE
EOF
}

# Waits (bounded) for the remote run to release the host lock, then requires every probe line to be verified absent.
postcheck() {
  local report='' i
  for i in $(seq 48); do
    report=$(probe) || report="ssh ERROR"
    grep -qx 'lock free' <<<"$report" && grep -qx 'run.lock free' <<<"$report" && break
    sleep 5
  done
  log "postcheck on $HOST: $(tr '\n' ';' <<<"$report")"
  if grep -q 'ERROR' <<<"$report" || ! grep -qx DONE <<<"$report"; then log "FAILED: could not verify that nothing is left on $HOST"; return 1; fi
  if grep -qE 'PRESENT|HELD' <<<"$report"; then log "FAILED: something of this run is left on $HOST (see above)"; return 1; fi
  log "verified on $HOST: no process, listener or lock of this run is left"
}

local_cleanup() {
  local rc=$? bad=''
  set +e
  trap '' INT TERM HUP
  [ -z "$remote_pid" ] || { stop_pid "$remote_pid"; wait "$remote_pid" 2>/dev/null; }
  [ -z "$rin" ] || exec {rin}>&-
  [ -z "$rout" ] || exec {rout}<&-
  [ -z "$key" ] || check_sessions || bad=1
  if [ -n "$tun_fd" ]; then exec {tun_fd}>&-; wait_gone 10 alive "$tunnel_pid" || stop_pid "$tunnel_pid"; fi
  if [ -n "$lock_fd" ]; then exec {lock_fd}>&-; fi
  if [ -n "$locked" ]; then
    postcheck || bad=1
    listing >"$tmp/after.txt" 2>&1 || { log "FAILED: could not list the emulator host's home after the run"; bad=1; }
    local new
    new=$(comm -13 <(sort "$tmp/before.txt") <(sort "$tmp/after.txt"))
    if [ -n "$new" ]; then log "FAILED: new entries outside ~/$REMOTE_DIR on $HOST: $(tr '\n' ' ' <<<"$new")"; bad=1
    else log "verified on $HOST: no new entries in ~, ~/.android, ~/.config, ~/.cache, ~/.local or the user's /tmp entries"; fi
  fi
  [ -z "$lock_pid" ] || { wait_gone 30 alive "$lock_pid" || stop_pid "$lock_pid"; }
  for p in "$tunnel_pid" "$lock_pid" "$remote_pid"; do
    [ -z "$p" ] || ! alive "$p" || { log "FAILED: local process $p is still running"; bad=1; }
  done
  if [ -n "$tmp" ]; then rm -f "$tmp/in" "$tmp/out" "$tmp/lock.out" "$tmp/tunnel.out" "$tmp/before.txt" "$tmp/after.txt"; rmdir "$tmp"; fi
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
      timeout 20 ssh -o BatchMode=yes "$HOST" "grep -qa -- --in-shell /proc/$remote_shell_pid/cmdline && kill -TERM $remote_shell_pid" ||
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
  local tests=$1 line='' partial='' r deadline=''
  mkfifo -m 600 "$tmp/in" "$tmp/out"
  # shellcheck disable=SC2029 # the arguments are meant to expand here
  timeout $((BUILD_TIMEOUT + BOOT_TIMEOUT + TEST_TIMEOUT + 900)) ssh -o BatchMode=yes -o ServerAliveInterval=15 \
    -o ServerAliveCountMax=4 "$HOST" "cd ~/$REMOTE_DIR && exec tools/emulator/run-e2e.sh --remote $tests $tunnel_port $run_id $nonce" \
    <"$tmp/in" >"$tmp/out" &
  remote_pid=$!
  remote_started=1
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
        '@@KEY@@') if [ -z "$interrupted" ]; then printf '%s\n' "$key" >&"$rin" || true; else printf '\n' >&"$rin" || true; fi ;;
        '@@PATTERNS@@') key_patterns >&"$rin" || true ;;
        '@@SESSION '*'@@') r=${line#@@SESSION }; r=${r%@@}; [[ $r =~ $SESSION_RE ]] && session_ids+=("$r") ;;
        '@@SESSIONS-OK@@') sessions_state=ok ;;
        '@@SESSIONS-UNKNOWN@@') sessions_state=unknown ;;
        '@@SCAN-OK '*'@@') r=${line#@@SCAN-OK }; scan_ok=${r%@@} ;;
        *) printf '[%s stdout] %s\n' "$HOST" "$line" >&2 ;;
      esac
    else
      r=$?
      # A 5 s tick or a signal whose trap has run; read keeps what it got of a line so far, so nothing is lost.
      [ "$r" -gt 128 ] && { partial+=$line; continue; }
      break                        # end of the remote output
    fi
  done
  exec {rin}>&- {rout}<&-
  rin='' rout=''
  r=0
  wait "$remote_pid" || r=$?
  remote_pid=''
  return "$r"
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
  local dir=$1 mode=$2 arg=$3 res expected=() t c bad=0 m
  res=$(results "$dir/instrument.txt")
  case $mode in
    smoke) for t in "${SMOKE[@]}"; do expected+=("SmokeE2eTest#$t"); done ;;
    full) for t in "${SMOKE[@]}"; do expected+=("SmokeE2eTest#$t"); done
          for t in "${SCENARIOS[@]}"; do expected+=("SurvivalE2eTest#$t"); done ;;
    scenario) expected=("SurvivalE2eTest#$arg") ;;
    test) [[ $arg != *'#'* ]] || expected=("$arg") ;;
  esac
  log "results:"
  while read -r t c; do
    [ "$t" = INSTRUMENTATION_CODE ] && continue
    case $c in 0) log "  pass  $t" ;; -3) log "  SKIP  $t"; bad=1 ;; *) log "  FAIL  $t (code $c)"; bad=1 ;; esac
  done <<<"$res"
  grep -qx 'INSTRUMENTATION_CODE -1' <<<"$res" || { log "  the instrumentation did not complete (no INSTRUMENTATION_CODE: -1)"; bad=1; }
  grep -qv INSTRUMENTATION_CODE <<<"$res" || { log "  no test ran"; bad=1; }
  [[ $mode != smoke && $mode != full ]] || grep -q '^HarnessSelfTest#' <<<"$res" || { log "  the harness self-tests did not run"; bad=1; }
  for t in "${expected[@]}"; do
    m=${t#*#}
    grep -qx "$t 0" <<<"$res" || { log "  MISSING or not passed: $t"; bad=1; }
    [[ $t == SmokeE2eTest#* || $t == SurvivalE2eTest#* ]] || continue # only these save evidence
    [ -s "$dir/device/$m.png" ] || { log "  missing screenshot device/$m.png"; bad=1; }
    [ "$(cat "$dir/device/results/${t/\#/.}.txt" 2>/dev/null)" = PASS ] || { log "  missing PASS result file for $t"; bad=1; }
  done
  [ -s "$dir/logcat.txt" ] || { log "  missing logcat.txt"; bad=1; }
  if [ "$bad" != 0 ]; then
    grep -A12 '^INSTRUMENTATION_STATUS: stack=' "$dir/instrument.txt" | head -60 >&2
    return 1
  fi
}

# The copy must be exactly the set the remote scan approved: the manifest it reported, every file in it, no other file.
verify_copy() {
  local h f n=0
  [ "$(sha256sum "$1/manifest.sha256" | cut -d' ' -f1)" = "$scan_ok" ] || return 1
  while read -r h f; do
    [ "$(sha256sum "$1/$f" | cut -d' ' -f1)" = "$h" ] || return 1
    n=$((n + 1))
  done <"$1/manifest.sha256"
  [ "$(find "$1" -type f ! -name manifest.sha256 | wc -l)" = "$n" ]
}

main() {
  local tests='' mode='' arg=''
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
  # shellcheck disable=SC2029 # REMOTE_DIR is meant to expand here
  exec {lock_fd}> >(exec ssh -o BatchMode=yes "$HOST" "mkdir -p ~/$REMOTE_DIR/.state && cd ~/$REMOTE_DIR/.state &&
    exec flock -n -E 75 lock sh -c 'echo locked; cat >/dev/null; flock -w 900 run.lock true'" >"$tmp/lock.out" 2>&1)
  lock_pid=$!
  for _ in $(seq 60); do grep -qx locked "$tmp/lock.out" 2>/dev/null && { locked=1; break; }; alive "$lock_pid" || break; sleep 0.5; done
  [ -n "$locked" ] || die "the emulator host is busy with another run (or ssh failed): $(tr '\n' ' ' <"$tmp/lock.out")"
  listing >"$tmp/before.txt" 2>&1 || die "could not list the emulator host's home"

  log "stage 1: rsync to $HOST:~/$REMOTE_DIR"
  # shellcheck disable=SC2029
  timeout 60 ssh -o BatchMode=yes "$HOST" "cd ~/$REMOTE_DIR && { [ -d .git ] || git init -q; } && echo /.state/ > .git/info/exclude"
  timeout 600 rsync -a --delete --filter=':- .gitignore' --exclude=/.git --exclude=/.state/ ./ "$HOST:$REMOTE_DIR/"
  # The flake only sees files git knows about; .gitignore keeps build output out of the Nix store copy.
  # shellcheck disable=SC2029
  timeout 120 ssh -o BatchMode=yes "$HOST" "cd ~/$REMOTE_DIR && git add -A"

  log "stage 2: reverse tunnel to Hermes"
  # shellcheck disable=SC2016
  tunnel_port=$(timeout 60 ssh -o BatchMode=yes "$HOST" 'for p in $(seq 18642 18662); do o=$(ss -Hltn "sport = :$p") || exit 1; [ -n "$o" ] || { echo "$p"; exit 0; }; done; exit 1') ||
    die "no free tunnel port on $HOST"
  # Its stdin is held by this script: when this script dies for any reason, the remote cat ends and the tunnel closes.
  exec {tun_fd}> >(exec ssh -o BatchMode=yes -o ExitOnForwardFailure=yes -o ServerAliveInterval=15 \
    -R "127.0.0.1:$tunnel_port:127.0.0.1:$HERMES_PORT" "$HOST" 'exec cat >/dev/null' >"$tmp/tunnel.out" 2>&1)
  tunnel_pid=$!
  local listen=''
  for _ in $(seq 20); do
    # shellcheck disable=SC2029
    listen=$(timeout 20 ssh -o BatchMode=yes "$HOST" "ss -Hltn 'sport = :$tunnel_port'" | awk '{print $4}' | sort -u) || listen=''
    [ -z "$listen" ] || break
    alive "$tunnel_pid" || die "tunnel exited: $(tr '\n' ' ' <"$tmp/tunnel.out")"
    sleep 1
  done
  # The requested bind is not enough: the server's SSH policy could widen it, so the actual listener is checked.
  [ "$listen" = "127.0.0.1:$tunnel_port" ] || die "tunnel listens on '${listen:-nothing}', not only 127.0.0.1:$tunnel_port"
  log "tunnel up: $HOST 127.0.0.1:$tunnel_port -> 127.0.0.1:$HERMES_PORT (verified loopback only)"

  local rc=0
  run_remote "$tests" || rc=$?
  if [ -n "$interrupted" ]; then log "interrupted: nothing copied back from $HOST"; exit "$interrupted"; fi
  [ -n "$scan_ok" ] || die "the remote run ended (exit $rc) without a passing leak scan; nothing copied back"

  log "copying the scanned artifacts to build/e2e/$run_id"
  mkdir -p build/e2e
  timeout 600 rsync -a "$HOST:$REMOTE_DIR/.state/runs/$run_id/" "build/e2e/$run_id/" || die "copying artifacts failed"
  verify_copy "build/e2e/$run_id" || die "the copied artifacts differ from the scanned set"
  local hits src=0
  hits=$(find_key "build/e2e/$run_id") || src=$?
  case $src in
    1) log "key absent from the local copy" ;;
    0) while IFS= read -r f; do rm -f -- "$f"; done <<<"$hits"; die "the API key was found in the local copy; those files were deleted" ;;
    *) die "the local leak scan failed (exit $src)" ;;
  esac
  log "gradle.log (tail):"
  tail -3 "build/e2e/$run_id/gradle.log" >&2
  [ "$rc" = 0 ] || die "the remote run failed (exit $rc); scanned artifacts in build/e2e/$run_id"
  evaluate "build/e2e/$run_id" "$mode" "$arg" || die "tests failed or evidence is missing; artifacts in build/e2e/$run_id"
  log "PASSED; artifacts in build/e2e/$run_id"
}

main "$@"
