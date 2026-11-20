#!/usr/bin/env bash
# End-to-end tests on a headless emulator on another host. See tools/emulator/README.md.
#
#   tools/emulator/run-e2e.sh --smoke   connect test (part A)
#   tools/emulator/run-e2e.sh --full    the survival scenarios (part B, not written yet)
#
# Runs on the build host; it calls itself on the emulator host with --remote (internal).
set -euo pipefail

HOST=${APOFIS_E2E_HOST:-obi-one}
REMOTE_DIR=tmp/apofis-e2e          # relative to the remote home
HERMES_PORT=${APOFIS_HERMES_PORT:-8642}
BOOT_TIMEOUT=${APOFIS_BOOT_TIMEOUT:-300}
TEST_TIMEOUT=${APOFIS_TEST_TIMEOUT:-1800}
LEAK_EXIT=3

log() { printf '[e2e %s] %s\n' "$(date +%H:%M:%S)" "$*" >&2; }
die() { log "FAILED: $*"; exit 1; }

# Prints the files under the given paths that contain $APOFIS_KEY (pattern read via -f, never argv; binary files too).
find_key() { grep -rlF -f <(printf '%s\n' "$APOFIS_KEY") "$@" 2>/dev/null || true; }

test_class() {
  case $1 in
    smoke) echo xyz.rycerz.apofis.e2e.SmokeE2eTest ;;
    *) die "unknown mode $1" ;;
  esac
}

# ---------------------------------------------------------------- emulator host
remote() {
  local mode=$1 tunnel_port=$2 run_id=$3
  local root=$HOME/$REMOTE_DIR state
  state=$root/.state
  local out=$state/runs/$run_id
  read -r APOFIS_KEY || die "no key on stdin"
  [ -n "$APOFIS_KEY" ] || die "empty key on stdin"
  export APOFIS_KEY
  mkdir -p "$out" "$state/android" "$state/avd" "$state/gradle" "$state/home" "$state/tmp"
  exec 9>"$state/lock"
  flock -n 9 || die "another run is active on $(hostname)"
  export ANDROID_USER_HOME=$state/android ANDROID_EMULATOR_HOME=$state/android ANDROID_AVD_HOME=$state/avd
  export GRADLE_USER_HOME=$state/gradle TMPDIR=$state/tmp JAVA_TOOL_OPTIONS=-Djava.io.tmpdir=$state/tmp
  cd "$root"
  exec nix develop .#emulator -c env HOME="$state/home" "$root/tools/emulator/run-e2e.sh" --in-shell "$mode" "$tunnel_port" "$out"
}

free_port() { ! ss -Hltn "sport = :$1" | grep -q .; }

emu_pgid=''
emu_serial=''
stop_emulator() {
  set +e
  if [ -n "$emu_pgid" ]; then
    log "stopping emulator $emu_serial"
    adb -s "$emu_serial" emu kill >/dev/null 2>&1
    for _ in $(seq 30); do pgrep -g "$emu_pgid" >/dev/null || break; sleep 1; done
    if pgrep -g "$emu_pgid" >/dev/null; then kill -TERM -- "-$emu_pgid"; sleep 5; kill -KILL -- "-$emu_pgid" 2>/dev/null; fi
    if pgrep -g "$emu_pgid" >/dev/null; then log "emulator processes remain:"; pgrep -a -g "$emu_pgid" >&2; else log "emulator stopped"; fi
  fi
  adb kill-server >/dev/null 2>&1
}

in_shell() {
  local mode=$1 tunnel_port=$2 out=$3 class p
  class=$(test_class "$mode")
  local avd=apofis-e2e image="system-images;android-37.0;default;x86_64"

  # AGP's test engine always talks to the adb server on 5037, so it must be free: this run starts and stops its own.
  free_port 5037 || die "port 5037 is in use (another adb server?); not sharing it"
  local ep=''
  for p in $(seq 5554 2 5584); do free_port "$p" && free_port $((p + 1)) && { ep=$p; break; }; done
  [ -n "$ep" ] || die "no free emulator port pair"
  emu_serial=emulator-$ep
  export ANDROID_SERIAL=$emu_serial
  trap stop_emulator EXIT
  # A dropped SSH connection shows up as SIGPIPE on the next log line; clean up then.
  trap 'trap "" PIPE; exit 129' HUP INT TERM PIPE

  log "stage 3: emulator (console port $ep, adb port $((ep + 1)))"
  emulator -accel-check >"$out/accel-check.txt" 2>&1 || { cat "$out/accel-check.txt" >&2; die "no KVM acceleration"; }
  if ! emulator -list-avds | grep -qx "$avd"; then
    log "creating AVD $avd ($image)"
    echo no | avdmanager create avd -n "$avd" -k "$image" -d pixel_7 >"$out/avdmanager.log" 2>&1 || { cat "$out/avdmanager.log" >&2; die "avdmanager failed"; }
  fi
  local start=$SECONDS
  # -wipe-data: every run starts from a fresh device, so runs do not depend on each other.
  setsid emulator -avd "$avd" -port "$ep" -no-window -no-audio -no-snapshot -wipe-data -no-boot-anim \
    -accel on -gpu swiftshader_indirect -cores 4 -memory 4096 >"$out/emulator.log" 2>&1 &
  emu_pgid=$!
  local booted=''
  while [ $((SECONDS - start)) -lt "$BOOT_TIMEOUT" ]; do
    pgrep -g "$emu_pgid" >/dev/null || { tail -20 "$out/emulator.log" >&2; die "emulator exited during boot"; }
    [ "$(timeout 10 adb -s "$emu_serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 ] && { booted=1; break; }
    sleep 3
  done
  [ -n "$booted" ] || { tail -20 "$out/emulator.log" >&2; die "emulator did not boot within ${BOOT_TIMEOUT}s"; }
  log "booted in $((SECONDS - start))s; $(grep -m1 KVM "$out/accel-check.txt")"
  # adbd logs every shell command, and am instrument carries the key: silence adbd before running tests.
  adb shell 'setprop log.tag.adbd S; settings put global window_animation_scale 0; settings put global transition_animation_scale 0;
    settings put global animator_duration_scale 0; input keyevent 82; mkdir -p /data/local/tmp/apofis-e2e'

  # The app's connections to the emulator's host alias 10.0.2.2 time out (the shell's work; cause not yet known),
  # while loopback works: adb reverse maps the device's 127.0.0.1 port to the tunnel.
  adb reverse "tcp:$tunnel_port" "tcp:$tunnel_port" >/dev/null
  local hermes_url=http://127.0.0.1:$tunnel_port health=''
  for _ in $(seq 30); do
    # The sleep keeps nc's write side open: adb reverse drops the connection on a half-close.
    health=$(adb shell "(printf 'GET /health HTTP/1.0\\r\\n\\r\\n'; sleep 2) | nc -w 5 127.0.0.1 $tunnel_port | head -1" 2>&1 | tr -d '\r')
    [[ $health == *" 200 "* ]] && break
    sleep 2
  done
  [[ $health == *" 200 "* ]] || die "Hermes not reachable from the emulator at $hermes_url (last: ${health:-no answer})"
  log "emulator reaches Hermes at $hermes_url: $health"

  log "stage 4: connectedDebugAndroidTest ($class, Hermes at $hermes_url)"
  local rc=0
  rm -f app/build/outputs/androidTest-results/connected/debug/*.xml # results of an earlier run must not count
  # No configuration cache and no daemons: nothing derived from the key's environment is kept on disk or in memory.
  timeout "$TEST_TIMEOUT" ./gradlew --no-daemon --no-configuration-cache --console=plain \
    -Pkotlin.compiler.execution.strategy=in-process \
    "-Pandroid.testInstrumentationRunnerArguments.apofisUrl=$hermes_url" \
    "-Pandroid.testInstrumentationRunnerArguments.class=$class" \
    connectedDebugAndroidTest >"$out/gradle.log" 2>&1 || rc=$?
  tail -5 "$out/gradle.log" >&2
  # AGP reports success with zero tests when it loses the device, so count them.
  local counts
  counts=$(grep -ho '<testsuites tests="[0-9]*" failures="[0-9]*" errors="[0-9]*"' app/build/outputs/androidTest-results/connected/debug/*.xml 2>/dev/null || true)
  log "results: ${counts:-none}"
  [[ $counts =~ tests=\"[1-9][0-9]*\"\ failures=\"0\"\ errors=\"0\" ]] || { [ "$rc" != 0 ] || rc=1; }

  log "collecting artifacts"
  adb logcat -d >"$out/logcat.txt" 2>&1 || true
  adb pull /data/local/tmp/apofis-e2e "$out/screenshots" >/dev/null 2>&1 || true
  cp -r app/build/reports/androidTests/connected "$out/report" 2>/dev/null || true
  cp -r app/build/outputs/androidTest-results/connected "$out/results" 2>/dev/null || true

  local leaks
  leaks=$(find_key "$out" app/build build .gradle "$HOME" "$ANDROID_USER_HOME" "$TMPDIR" "$GRADLE_USER_HOME")
  if [ -n "$leaks" ]; then
    log "the API key was found in these files on $(hostname); not copying artifacts back:"
    while IFS= read -r f; do log "  $f"; done <<<"$leaks"
    exit "$LEAK_EXIT"
  fi
  log "key absent from artifacts, build output, Gradle home and temp files"
  [ "$rc" = 0 ] || die "tests failed or timed out (exit $rc), see gradle.log"
}

# ---------------------------------------------------------------- build host
tunnel_pid=''
tunnel_port=''
local_cleanup() {
  local rc=$? left=''
  set +e
  if [ -n "$tunnel_pid" ]; then
    kill "$tunnel_pid" 2>/dev/null
    wait "$tunnel_pid" 2>/dev/null
    log "tunnel closed"
  fi
  if [ -n "$tunnel_port" ]; then
    # shellcheck disable=SC2029 # the port is meant to expand here
    ssh "$HOST" "ss -Hltn 'sport = :$tunnel_port' | grep -q ." && { log "port $tunnel_port still listens on $HOST"; left=1; }
    # shellcheck disable=SC2016
    ssh "$HOST" 'pgrep -u "$(id -u)" -a "^(qemu-system.*|emulator.*|netsimd|adb|crashpad_handle.*)$"' >&2 \
      && { log "emulator or adb processes remain on $HOST"; left=1; }
    [ -n "$left" ] || log "checked: no tunnel port, emulator or adb process left on $HOST"
  fi
  [ -z "$left" ] || exit 1
  exit "$rc"
}

main() {
  local mode=''
  case ${1:-} in
    --smoke) mode=smoke ;;
    --full) die "full mode: the survival scenarios arrive with part B of task 12" ;;
    --remote) shift; remote "$@"; return ;;
    --in-shell) shift; in_shell "$@"; return ;;
    *) echo "usage: $0 --smoke | --full" >&2; exit 2 ;;
  esac
  cd "$(git -C "$(dirname "$0")" rev-parse --show-toplevel)"
  local run_id key
  run_id=$(date +%Y%m%d-%H%M%S)
  key=$(grep -E '^API_SERVER_KEY=' ~/.hermes/.env | cut -d= -f2- | tr -d "\"'")
  [ -n "$key" ] || die "API_SERVER_KEY not found in ~/.hermes/.env"
  trap local_cleanup EXIT

  log "stage 1: rsync to $HOST:~/$REMOTE_DIR"
  # shellcheck disable=SC2029 # REMOTE_DIR is meant to expand here
  ssh "$HOST" "mkdir -p ~/$REMOTE_DIR && cd ~/$REMOTE_DIR && { [ -d .git ] || git init -q; } && echo /.state/ > .git/info/exclude"
  rsync -a --delete --filter=':- .gitignore' --exclude=/.git --exclude=/.state/ ./ "$HOST:$REMOTE_DIR/"
  # The flake only sees files git knows about; .gitignore keeps build output out of the Nix store copy.
  # shellcheck disable=SC2029
  ssh "$HOST" "cd ~/$REMOTE_DIR && git add -A"

  log "stage 2: reverse tunnel to Hermes"
  # shellcheck disable=SC2016
  tunnel_port=$(ssh "$HOST" 'for p in $(seq 18642 18662); do ss -Hltn "sport = :$p" | grep -q . || { echo "$p"; exit 0; }; done; exit 1') \
    || die "no free tunnel port on $HOST"
  ssh -N -o ExitOnForwardFailure=yes -o ServerAliveInterval=15 -R "127.0.0.1:$tunnel_port:127.0.0.1:$HERMES_PORT" "$HOST" &
  tunnel_pid=$!
  local ok=''
  for _ in $(seq 20); do
    # shellcheck disable=SC2029
    ssh "$HOST" "timeout 3 bash -c 'exec 3<>/dev/tcp/127.0.0.1/$tunnel_port'" 2>/dev/null && { ok=1; break; }
    kill -0 "$tunnel_pid" 2>/dev/null || die "tunnel exited"
    sleep 1
  done
  [ -n "$ok" ] || die "tunnel port $tunnel_port did not open on $HOST"
  log "tunnel up: $HOST 127.0.0.1:$tunnel_port -> 127.0.0.1:$HERMES_PORT"

  local rc=0
  # The key travels on stdin only: it is not on any command line and not written to disk on the remote host.
  # shellcheck disable=SC2029
  timeout $((BOOT_TIMEOUT + TEST_TIMEOUT + 3600)) ssh "$HOST" \
    "cd ~/$REMOTE_DIR && exec tools/emulator/run-e2e.sh --remote $mode $tunnel_port $run_id" <<<"$key" || rc=$?

  [ "$rc" = "$LEAK_EXIT" ] && die "API key leaked on $HOST (see above); artifacts left there"
  log "stage 5: copying artifacts to build/e2e/$run_id"
  mkdir -p build/e2e
  rsync -a "$HOST:$REMOTE_DIR/.state/runs/$run_id/" "build/e2e/$run_id/" || die "copying artifacts failed"
  APOFIS_KEY=$key
  [ -z "$(find_key "build/e2e/$run_id")" ] || die "API key found in build/e2e/$run_id"
  [ "$rc" = 0 ] || die "remote run failed (exit $rc); artifacts in build/e2e/$run_id"
  log "PASSED; artifacts in build/e2e/$run_id"
}

main "$@"
