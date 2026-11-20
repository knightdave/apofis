#!/usr/bin/env bash
# shellcheck disable=SC2016 # each check's condition is shell code, evaluated by check
# Tests run-e2e.sh's safeguards on this host alone: no SSH, no emulator, no server and a fake key. Fake ssh and rsync
# commands (small scripts in a temp PATH directory) stand in for the hosts where a check needs them.
#
#   tools/emulator/run-e2e-selftest.sh
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=SCRIPTDIR/run-e2e.sh
APOFIS_E2E_LIB=1 source "$here/run-e2e.sh"

t=$(mktemp -d "${TMPDIR:-/tmp}/apofis-e2e-selftest.XXXXXX")
tmp=$t/tmp
mkdir "$tmp"
E2E_OUT=$t/build/e2e
key=SELFTESTfakeKEY0123456789abcdefSELFTESTfakeKEY0123456789abcdef # a fake key, never the real one
nonce=0a1b2c3d
passed=0 failed=0
# check <name> <condition as shell code>
check() { if eval "$2"; then passed=$((passed + 1)); echo "ok    $1"; else failed=$((failed + 1)); echo "FAIL  $1"; fi; }
has() { grep -qF -- "$2" <<<"$1"; }
lacks() { ! grep -qF -- "$2" <<<"$1"; }
lines_of() { grep -c . <<<"$1" || true; }
K1=apofis-0a1b2c3d-0000-4000-8000-000000000001
K9=apofis-0a1b2c3d-0000-4000-8000-000000000009
R1=run_0123456789abcdef0123456789abcdef

# A staged export: three files and their reported checksums.
make_export() {
  mkdir -p "$1"
  printf 'INSTRUMENTATION_CODE: -1\n' >"$1/instrument.txt"
  printf 'SurvivalE2eTest.connectAndChat PASS\n' >"$1/results.txt"
  printf 'I ApofisE2e: ok\n' >"$1/logcat.txt"
  export_files=()
  for f in instrument.txt results.txt logcat.txt; do export_files+=("$(sha256sum "$1/$f" | cut -d' ' -f1) $f"); done
}
nothing_kept() { [ ! -e "$E2E_OUT/$1" ] && [ ! -e "$E2E_OUT/.incoming-$1" ]; }

# 1. A change after the scan (here: after the checksums were reported) leaves nothing in build/e2e.
make_export "$t/src1"
printf 'planted change\n' >>"$t/src1/logcat.txt"
run_id=run1 rc=0
o=$(import_export "$t/src1/" 2>&1) || rc=$?
check "planted change after the scan: copy rejected, nothing kept" '[ "$rc" != 0 ] && nothing_kept run1'
check "planted change after the scan: fixed message only, no content" 'has "$o" "FAILED: stage checksum: the copied files were deleted" && lacks "$o" planted'

# 2. An unchanged export is copied, verified and published by one rename; no incoming directory is left.
make_export "$t/src2"
run_id=run2 rc=0
o=$(import_export "$t/src2/" 2>&1) || rc=$?
check "clean export: published, no incoming directory left" '[ "$rc" = 0 ] && cmp -s "$t/src2/logcat.txt" "$E2E_OUT/run2/logcat.txt" && [ ! -e "$E2E_OUT/.incoming-run2" ]'

# 3. The key in a copied file (checksums matching) is found by the local scan and everything copied is deleted.
make_export "$t/src3"
printf 'leak %s\n' "$key" >"$t/src3/logcat.txt"
export_files[2]="$(sha256sum "$t/src3/logcat.txt" | cut -d' ' -f1) logcat.txt"
run_id=run3 rc=0
o=$(import_export "$t/src3/" 2>&1) || rc=$?
check "key in the copy: deleted, nothing kept" '[ "$rc" != 0 ] && nothing_kept run3'
check "key in the copy: fixed message, no key, no name" 'has "$o" "stage local scan (key found)" && lacks "$o" "$key" && lacks "$o" "logcat.txt"'

# 3b. A listed file that became a directory on the source: never descended into, nothing kept.
make_export "$t/src3b"
rm -f "$t/src3b/logcat.txt"
mkdir "$t/src3b/logcat.txt"
printf 'x %s\n' "$key" >"$t/src3b/logcat.txt/inner"
run_id=run3b rc=0
o=$(import_export "$t/src3b/" 2>&1) || rc=$?
check "type change to a directory: not descended, nothing kept" '[ "$rc" != 0 ] && nothing_kept run3b'

# 4. A scan error on the emulator host reports only the fixed code and deletes the staged files.
mkdir -p "$t/exp4"
printf 'secret-looking name\n' >"$t/exp4/instrument.txt"
chmod 000 "$t/exp4/instrument.txt"
exec {proto}< <(key_patterns)
rc=0; o=$(scan_seal "$t/exp4" 2>&1) || rc=$?
exec {proto}<&-
check "scan error: scan exit, only the request and the fixed code" '[ "$rc" = "$SCANFAIL_EXIT" ] && [ "$o" = "$(printf "@@PATTERNS@@\n@@MSG scan-error@@")" ]'
check "scan error: staged files deleted" '[ ! -e "$t/exp4" ]'
# The same with the key in a staged file, and with no patterns (the build host gone).
mkdir -p "$t/exp5"
printf 'x %s\n' "$key" >"$t/exp5/logcat.txt"
exec {proto}< <(key_patterns)
rc=0; o=$(scan_seal "$t/exp5" 2>&1) || rc=$?
exec {proto}<&-
check "key staged: leak exit, fixed code, nothing kept" '[ "$rc" = "$LEAK_EXIT" ] && [ "$o" = "$(printf "@@PATTERNS@@\n@@MSG scan-leak@@")" ] && [ ! -e "$t/exp5" ]'
mkdir -p "$t/exp6"
printf 'x\n' >"$t/exp6/logcat.txt"
exec {proto}</dev/null
rc=0; o=$(scan_seal "$t/exp6" 2>&1) || rc=$?
exec {proto}<&-
check "no patterns (SSH gone): staged files deleted" '[ "$rc" = "$SCANFAIL_EXIT" ] && [ ! -e "$t/exp6" ] && has "$o" "@@MSG scan-no-patterns@@"'
# A clean export is sealed: read-only, checksums kept for the end (not printed yet).
make_export "$t/exp7"
exec {proto}< <(key_patterns)
scan_seal "$t/exp7" >"$t/exp7.out" 2>&1 && rc=0 || rc=$?
exec {proto}<&-
o=$(cat "$t/exp7.out")
check "clean staging: sealed read-only, 3 checksums kept, none printed yet" '[ "$rc" = 0 ] && [ ! -w "$t/exp7" ] && [ ! -w "$t/exp7/logcat.txt" ] && [ "${#manifest[@]}" = 3 ] && lacks "$o" "@@FILE"'
check "every remote code has a text on the build host" 'for c in $(grep -o "rmsg [a-z0-9-]*\|rdie [a-z0-9-]*" "$here/run-e2e.sh" | cut -d" " -f2 | sort -u); do [ -n "${REMOTE_MSG[$c]+x}" ] || exit 1; done'

# 5. The session journal on the emulator host: only exact lines, complete file, or nothing at all is reported.
out=$t/rs
mkdir -p "$out/device"
key_sent=1
printf 'send %s connectAndChat\nsession %s %s connectAndChat\nrun %s %s\n' "$K1" "$K1" "$K1" "$R1" "$K1" >"$out/device/sessions.txt"
o=$(report_sessions 0 2>/dev/null)
check "journal: exact lines reported with their count" 'has "$o" "@@SESSIONS-END 3@@" && [ "$(grep -c "^@@J " <<<"$o")" = 3 ]'
printf 'session %s %s connectAndChat' "$K1" "$K1" >"$out/device/sessions.txt"
check "journal: a cut-off last line is unknown" '[ "$(report_sessions 0 2>/dev/null)" = "$(printf "@@MSG journal-cut@@\n@@SESSIONS-UNKNOWN@@")" ]'
printf 'session %s connectAndChat\n' "$K1" >"$out/device/sessions.txt"
check "journal: an old-form line is malformed, nothing passed on" '[ "$(report_sessions 0 2>/dev/null)" = "$(printf "@@MSG journal-malformed@@\n@@SESSIONS-UNKNOWN@@")" ]'
rm -f "$out/device/sessions.txt"
check "journal: a missing journal is unknown" 'has "$(report_sessions 0 2>/dev/null)" "@@SESSIONS-UNKNOWN@@"'

# 6. Cleanup without a complete journal fails and reads nothing on the server.
# shellcheck disable=SC2329 # replaces the real server call
hermes() { echo "$*" >>"$t/server-calls"; echo 200; }
mode=smoke
for s in '' unknown bad; do
  key_sent_local=1 sessions_state=$s journal=()
  rc=0; o=$(check_sessions 2>&1) || rc=$?
  check "journal state '${s:-missing}': cleanup fails with the fixed message" '[ "$rc" = 1 ] && has "$o" "sessions this run created may remain"'
done
key_sent_local=1 sessions_state=ok journal=("send $K1 connectAndChat")
rc=0; o=$(check_sessions 2>&1) || rc=$?
check "journal empty after a send (no id of the chat): fails, sessions may remain" '[ "$rc" = 1 ] && has "$o" "journal is incomplete" && has "$o" "may remain"'
key_sent_local=1 sessions_state=ok journal=("send $K1 connectAndChat" "session $K9 $K9 connectAndChat")
rc=0; o=$(check_sessions 2>&1) || rc=$?
check "an id of another chat does not complete a send: fails" '[ "$rc" = 1 ] && has "$o" "journal is incomplete"'
rm -f "$t/server-calls"
key_sent_local=1 sessions_state=ok journal=("run $R1 $K9")
rc=0; o=$(check_sessions 2>&1) || rc=$?
check "a journaled run of a session not proved this run's: not read, fails" '[ "$rc" = 1 ] && [ ! -e "$t/server-calls" ]'
key_sent_local=1 sessions_state=ok journal=()
rc=0; o=$(check_sessions 2>&1) || rc=$?
check "nothing sent (smoke): complete and empty journal passes, no server call" '[ "$rc" = 0 ] && has "$o" "complete and empty" && [ ! -e "$t/server-calls" ]'
key_sent_local='' sessions_state=''
check "key never sent: no sessions possible, no read" 'check_sessions 2>/dev/null && [ ! -e "$t/server-calls" ]'
unset -f hermes

# 6b. The steered turn on the server, from canned records (the build host's jq check).
steer=$(steering_text)
w=$'[OUT-OF-BAND USER MESSAGE - a direct message from the user]\n'"$steer"$'\n[/OUT-OF-BAND USER MESSAGE]'
mkrec() { jq -n --arg id "$K1" --arg w "$w" "{session_id: \$id, data: $1}" >"$t/rec.json"; }
U='{role: "user", content: "Test 0a1b2c3d-6"}' S='{role: "user", display_kind: "steer", content: $w}'
F='{role: "assistant", finish_reason: "stop", content: "Done apofis-e2e-6 BANANA"}' C='{role: "assistant", content: "", tool_calls: []}'
mkrec "[$U, $C, $S, $F]"
check "steer record: prompt, call, steer row, final ending BANANA passes" 'steer_ok_json "$K1" "$steer" "$t/rec.json"'
mkrec "[$U, $C, $F, $S]"
check "steer record: final before the steer fails" '! steer_ok_json "$K1" "$steer" "$t/rec.json"'
mkrec "[$U, $S, $U, $F]"
check "steer record: another user turn after the steer fails" '! steer_ok_json "$K1" "$steer" "$t/rec.json"'
mkrec "[$U, $S, $S, $F]"
check "steer record: two steer rows fail" '! steer_ok_json "$K1" "$steer" "$t/rec.json"'

# 7. The emulator host lock directory, through the same shipping as over SSH (run locally here).
# shellcheck disable=SC2329 # runs the shipped functions locally instead of over SSH
rsh() { echo call >>"$t/rsh-calls"; "$@"; }
st=$t/state
check "lock: free lock taken" '[ "$(rsh_fn lock_take "$st" run-a)" = TAKEN ] && lock_owned "$st" run-a'
check "lock: the same run id takes its own lock again (a lost answer is recovered)" '[ "$(rsh_fn lock_take "$st" run-a)" = TAKEN ] && [ -z "$(ls -A "$st" | grep -v "^lock.d$")" ]'
check "lock: another run id is refused with the owner named" '[ "$(rsh_fn lock_take "$st" run-b)" = "BUSY run-a" ] && lock_owned "$st" run-a'
check "lock: another run cannot release it" '[ "$(rsh_fn lock_release "$st" run-b)" = NOT-OURS ] && lock_owned "$st" run-a'
REMOTE_DIR=$t/rd
mkdir -p "$REMOTE_DIR/.state/lock.d"
printf '20261120-101659-80f8\n' >"$REMOTE_DIR/.state/lock.d/owner"
run_id=20261120-111111-aaaa lock_tried='' locked=''
rm -f "$t/rsh-calls"
rc=0; o=$( (take_lock) 2>&1) || rc=$?
check "lock of another run id: the run stops at stage 0 before any change" '[ "$rc" != 0 ] && [ "$(cat "$REMOTE_DIR/.state/lock.d/owner")" = 20261120-101659-80f8 ] && [ "$(ls -A "$REMOTE_DIR/.state")" = lock.d ] && [ "$(grep -c . "$t/rsh-calls")" = 1 ]'
check "lock of another run id: fixed message with the owner and the manual commands" 'has "$o" "held by run 20261120-101659-80f8" && has "$o" "never takes a lock over" && has "$o" "pgrep -u \\\$(id -u) -af -- '\''[-]avd apofis-e2e|" && has "$o" "rm -f ~/$REMOTE_DIR/.state/lock.d/owner && rmdir ~/$REMOTE_DIR/.state/lock.d"'
rm -f "$REMOTE_DIR/.state/lock.d/owner"
rc=0; o=$( (take_lock) 2>&1) || rc=$?
check "lock directory without an owner: refused as unknown, left alone" '[ "$rc" != 0 ] && has "$o" "held by run unknown" && [ -d "$REMOTE_DIR/.state/lock.d" ]'
rmdir "$REMOTE_DIR/.state/lock.d"
rc=0; (take_lock && lock_owned "$REMOTE_DIR/.state" "$run_id") >/dev/null 2>&1 || rc=$?
check "take_lock: a free lock is taken for this run id" '[ "$rc" = 0 ]'
check "lock: owner releases it, nothing is left" '[ "$(rsh_fn lock_release "$st" run-a)" = RELEASED ] && [ -z "$(ls -A "$st")" ]'
unset -f rsh
REMOTE_DIR=tmp/apofis-e2e

# 8. A hanging stop command is killed by its timeout; a process ignoring TERM is killed by stop_pid.
s=$SECONDS rc=0
bounded 2 bash -c 'trap "" TERM; sleep 60' 2>/dev/null || rc=$?
check "bounded: a command ignoring TERM is killed (exit 137) within 9 s" '[ "$rc" = 137 ] && [ $((SECONDS - s)) -le 9 ]'
bash -c 'trap "" TERM; sleep 60' &
pid=$!
s=$SECONDS
{ stop_pid "$pid" || true; } 2>/dev/null
check "stop_pid: a process ignoring TERM is gone within 17 s" '! alive "$pid" && [ $((SECONDS - s)) -le 17 ]'
{ wait "$pid" || true; } 2>/dev/null

# 9. The postcheck: one token per fact, all required with their expected values.
r=$(listeners_now "$PORT_RANGES")
o=$(probe "$t/nostate" run-x "$PORT_RANGES" "${r#LISTENERS=}" "$EMU_RE" "$ADB_RE" "$SCRIPT_RE")
check "probe: every token on a clean host, accepted" 'verify_probe "$o" 2>/dev/null || { echo "$o" | tr "\n" " "; false; }'
rc=0; m=$(verify_probe "$(grep -v "^RUNDIR=" <<<"$o")" 2>&1) || rc=$?
check "postcheck: a missing token fails and is named" '[ "$rc" = 1 ] && has "$m" "not confirmed: RUNDIR=absent"'
rc=0; m=$(verify_probe "${o/AVD=absent/AVD=error}" 2>&1) || rc=$?
check "postcheck: an inaccessible or unexpected value fails" '[ "$rc" = 1 ] && has "$m" "AVD=absent"'
check "postcheck: an empty report (SSH failed) fails" '! verify_probe "" 2>/dev/null'
mkdir -p "$t/st9/lock.d"
printf 'run-x\n' >"$t/st9/lock.d/owner"
o=$(probe "$t/st9" run-x "$PORT_RANGES" "${r#LISTENERS=}" "$EMU_RE" "$ADB_RE" "$SCRIPT_RE")
check "probe: this run's lock still there is LOCK=held, fails" 'has "$o" LOCK=held && ! verify_probe "$o" 2>/dev/null'
mkdir -p "$t/st9/noaccess"
chmod 000 "$t/st9/noaccess"
check "probe: an unsearchable directory is error, not absent" '[ "$(path_state "$t/st9/noaccess/x")" = error ]'
chmod 700 "$t/st9/noaccess"

# 10. The remote stage: the export is copied only after exit 0 with this run's completion token as the last line,
# after a valid manifest. A fake ssh plays the emulator host.
mkdir -p "$t/bin"
cat >"$t/bin/ssh" <<'EOF'
#!/usr/bin/env bash
h=0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef
echo "@@FILE $h instrument.txt@@"
case $FAKE in
  ok) echo "@@COMPLETE $FAKE_RUN 1@@" ;;
  notoken) ;;
  othertoken) echo "@@COMPLETE 20261120-000000-0000 1@@" ;;
  after) echo "@@COMPLETE $FAKE_RUN 1@@"; echo "@@HB@@" ;;
  partial) echo "@@COMPLETE $FAKE_RUN 1@@"; printf '@@HB' ;;
  timeout) echo "@@COMPLETE $FAKE_RUN 1@@"; exec sleep 30 ;;
  drop) echo "@@COMPLETE $FAKE_RUN 1@@"; exit 255 ;;
  fail) echo "@@COMPLETE $FAKE_RUN 1@@"; exit 5 ;;
esac
exit 0
EOF
chmod +x "$t/bin/ssh"
remote_case() {
  rm -f "$tmp/in" "$tmp/out"
  export_files=() export_state='' last_line='' remote_pid='' remote_shell_pid=''
  run_id=20261120-121212-abcd
  rc=0
  FAKE=$1 FAKE_RUN=$run_id PATH="$t/bin:$PATH" run_remote smoke >/dev/null 2>&1 || rc=$?
  remote_complete "$rc"
}
REMOTE_TIMEOUT=2
check "remote stage: exit 0, manifest and this run's token last: copy allowed (control)" 'remote_case ok'
check "remote stage: exit 0 but no completion token: nothing copied" '! remote_case notoken'
check "remote stage: another run id in the token: nothing copied" '! remote_case othertoken'
check "remote stage: a line after the token: nothing copied" '! remote_case after'
check "remote stage: an unfinished line after the token: nothing copied" '! remote_case partial'
check "remote stage: timeout after the token: nothing copied" '! remote_case timeout && [ "$rc" = 124 ]'
check "remote stage: dropped connection after the token: nothing copied" '! remote_case drop'
check "remote stage: failure exit after the token: nothing copied" '! remote_case fail'
REMOTE_TIMEOUT=60

# 11. An interrupt (SIGINT) during the import: the real traps leave no final directory and no incoming files.
cat >"$t/bin/rsync" <<'EOF'
#!/usr/bin/env bash
# Copies the listed files like the real one, then stays busy, so the interrupt arrives mid-import.
for a in "$@"; do case $a in --files-from=*) list=${a#*=} ;; esac; done
src=${*: -2:1} dst=${*: -1}
while IFS= read -r f; do cp -- "$src/$f" "$dst/$f"; done <"$list"
sleep 3
EOF
chmod +x "$t/bin/rsync"
make_export "$t/src11"
printf '%s\n' "${export_files[@]}" >"$t/files11"
set -m # job control: the child gets SIGINT like a foreground run would
PATH="$t/bin:$PATH" APOFIS_E2E_LIB=1 bash -c '
  source "$1/run-e2e.sh"
  set +e
  tmp=$(mktemp -d) E2E_OUT=$2 run_id=run11 key=$3
  mapfile -t export_files <"$4"
  trap local_cleanup EXIT
  trap "on_signal 130" INT
  import_export "$5/"
  echo "import returned" >&2' _ "$here" "$E2E_OUT" "$key" "$t/files11" "$t/src11" >"$t/out11" 2>&1 &
pid=$!
set +m
sleep 1.5
check "interrupt during import: the incoming files exist mid-import" '[ -e "$E2E_OUT/.incoming-run11/logcat.txt" ]'
kill -INT "$pid"
rc=0; wait "$pid" || rc=$?
check "interrupt during import: exit 130, no final directory, no incoming files" '[ "$rc" = 130 ] && nothing_kept run11 && lacks "$(cat "$t/out11")" "import returned"'

# 12. The cleanup has an overall limit: a hanging step ends in a reported failure, not a hang.
s=$SECONDS rc=0
APOFIS_E2E_LIB=1 bash -c '
  source "$1/run-e2e.sh"
  set +e
  TRAP_LIMIT=2 tmp=$(mktemp -d) key=x
  check_sessions() { while :; do sleep 0.2; done; }
  trap local_cleanup EXIT
  exit 0' _ "$here" >"$t/out12" 2>&1 || rc=$?
check "cleanup over its limit: exit 1 with the fixed message within 10 s" '[ "$rc" = 1 ] && has "$(cat "$t/out12")" "did not finish within 2s" && [ $((SECONDS - s)) -le 10 ]'

remove_tree "$t"
echo "$passed passed, $failed failed"
[ "$failed" = 0 ]
