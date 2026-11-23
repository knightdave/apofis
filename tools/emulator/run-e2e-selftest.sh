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
E2E_ROOT=$t
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
printf 'send %s connectAndChat\nchat connectAndChat %s %s,%s\nrun %s %s\n' "$K1" "$K1" "$K1" "$K9" "$R1" "$K1" >"$out/device/sessions.txt"
o=$(report_sessions 0 2>/dev/null)
check "journal: exact lines reported with their count" 'has "$o" "@@SESSIONS-END 3@@" && [ "$(grep -c "^@@J " <<<"$o")" = 3 ]'
printf 'chat connectAndChat %s %s' "$K1" "$K1" >"$out/device/sessions.txt"
check "journal: a cut-off last line is unknown" '[ "$(report_sessions 0 2>/dev/null)" = "$(printf "@@MSG journal-cut@@\n@@SESSIONS-UNKNOWN@@")" ]'
printf 'session %s connectAndChat\n' "$K1" >"$out/device/sessions.txt"
check "journal: an old-form line is malformed, nothing passed on" '[ "$(report_sessions 0 2>/dev/null)" = "$(printf "@@MSG journal-malformed@@\n@@SESSIONS-UNKNOWN@@")" ]'
rm -f "$out/device/sessions.txt"
check "journal: a missing journal is unknown" 'has "$(report_sessions 0 2>/dev/null)" "@@SESSIONS-UNKNOWN@@"'

# 6. Journal completeness is decided first: no server request for anything that is not a complete, matched entry.
# The stub plays the server for this run's sessions and counts every request.
# shellcheck disable=SC2329 # replaces the real server call
hermes() {
  local id=${2#api/sessions/}
  echo "$1 $2" >>"$t/server-calls"
  case $1 in
    GET) if grep -qx "$id" "$t/deleted" 2>/dev/null; then echo 404; else printf '{"session":{"id":"%s","title":"Test %s-1"}}' "$id" "$nonce" >"$3"; echo 200; fi ;;
    DELETE) echo "$id" >>"$t/deleted"; printf '{"id":"%s","deleted":true}' "$id" >"$3"; echo 200 ;;
  esac
}
requests() { [ -e "$t/server-calls" ] && grep -c . "$t/server-calls" || echo 0; }
# The am instrument -r report of the result rows on stdin ("Class.method PASS|FAIL"), ending normally.
instrument_for() {
  local row c m v
  while read -r row v; do
    c=${row%%.*} m=${row#*.}
    printf 'INSTRUMENTATION_STATUS: class=xyz.rycerz.apofis.e2e.%s\nINSTRUMENTATION_STATUS: test=%s\nINSTRUMENTATION_STATUS_CODE: 1\n' "$c" "$m"
    printf 'INSTRUMENTATION_STATUS: class=xyz.rycerz.apofis.e2e.%s\nINSTRUMENTATION_STATUS: test=%s\nINSTRUMENTATION_STATUS_CODE: %s\n' "$c" "$m" "$([ "$v" = PASS ] && echo 0 || echo -2)"
  done
  printf 'INSTRUMENTATION_CODE: -1\n'
}
sessions_case() { # <mode> <passed scenarios> <journal lines...>: a verified results file listing them, the matching
  local sc        # instrumentation report, and the journal
  mode=$1 key_sent_local=1 sessions_state=ok published=1 run_id=sess
  mkdir -p "$E2E_OUT/sess"
  { echo SmokeE2eTest.connectViaLoopback PASS; for sc in $2; do echo "SurvivalE2eTest.$sc PASS"; done; } >"$E2E_OUT/sess/results.txt"
  instrument_for <"$E2E_OUT/sess/results.txt" >"$E2E_OUT/sess/instrument.txt"
  shift 2
  journal=("$@")
  rm -f "$t/server-calls" "$t/deleted"
  rc=0; o=$(check_sessions 2>&1) || rc=$?
}
for s in '' unknown bad; do
  sessions_case smoke ''
  sessions_state=$s
  rc=0; o=$(check_sessions 2>&1) || rc=$?
  check "journal state '${s:-missing}': fails with the fixed message, no request" '[ "$rc" = 1 ] && has "$o" "sessions this run created may remain" && [ "$(requests)" = 0 ]'
done
sessions_case scenario connectAndChat
check "empty journal with a passing sending scenario: fails, no server request" '[ "$rc" = 1 ] && has "$o" "may remain" && [ "$(requests)" = 0 ]'
sessions_case scenario '' "send $K1 connectAndChat"
check "a send without its chat line: fails, no server request" '[ "$rc" = 1 ] && has "$o" "may remain" && [ "$(requests)" = 0 ]'
sessions_case scenario connectAndChat "send $K1 connectAndChat" "chat connectAndChat $K1 none"
check "a chat line without an id (none): fails, no server request" '[ "$rc" = 1 ] && [ "$(requests)" = 0 ]'
sessions_case scenario connectAndChat "send $K1 connectAndChat" "chat connectAndChat $K9 $K9"
check "a chat line for another key (unmatched): fails, no request for it" '[ "$rc" = 1 ] && [ "$(requests)" = 0 ]'
sessions_case full 'connectAndChat stopDuringRun' "send $K1 connectAndChat" "chat connectAndChat $K1 $K1" "send $K9 stopDuringRun" "run $R1 $K9"
check "one complete and one incomplete entry: fails, requests only for the complete one" '[ "$rc" = 1 ] && has "$o" "may remain" && [ "$(requests)" -gt 0 ] && ! grep -q "$K9\|$R1" "$t/server-calls" && grep -qx "DELETE api/sessions/$K1" "$t/server-calls"'
sessions_case scenario connectAndChat "send $K1 connectAndChat" "chat connectAndChat $K1 $K1" "chat connectAndChat $K1 $K1,$K9"
check "two chat lines for one scenario: fails, no request" '[ "$rc" = 1 ] && [ "$(requests)" = 0 ]'
sessions_case scenario connectAndChat "send $K1 connectAndChat" "chat connectAndChat $K1 $K1"
check "a complete, matched entry: proved, deleted and checked gone; passes" '[ "$rc" = 0 ] && [ "$(cat "$t/server-calls")" = "$(printf "GET api/sessions/%s\nDELETE api/sessions/%s\nGET api/sessions/%s" "$K1" "$K1" "$K1")" ]'
sessions_case smoke ''
check "nothing sent (smoke): complete and empty journal passes, no request" '[ "$rc" = 0 ] && has "$o" "complete and empty" && [ "$(requests)" = 0 ]'
sessions_case smoke ''
printf 'SmokeE2eTest.connectViaLoopback PASS\nSurvivalE2eTest.connectAndChat PASS\n' | instrument_for >"$E2E_OUT/sess/instrument.txt"
rc=0; o=$(check_sessions 2>&1) || rc=$?
check "results file omits a scenario the instrumentation reported passing, empty journal: fails, no request" '[ "$rc" = 1 ] && has "$o" "does not match what the instrumentation reported" && has "$o" "may remain" && lacks "$o" "complete and empty" && [ "$(requests)" = 0 ]'
sessions_case smoke ''
printf 'SmokeE2eTest.connectViaLoopback FAIL\n' | instrument_for >"$E2E_OUT/sess/instrument.txt"
rc=0; o=$(check_sessions 2>&1) || rc=$?
check "results file says PASS where the instrumentation reported a failure: fails, no request" '[ "$rc" = 1 ] && has "$o" "does not match" && [ "$(requests)" = 0 ]'
sessions_case smoke ''
rm -f "$E2E_OUT/sess/instrument.txt"
rc=0; o=$(check_sessions 2>&1) || rc=$?
check "no instrumentation report to reconcile with: fails, no request" '[ "$rc" = 1 ] && has "$o" "does not match" && [ "$(requests)" = 0 ]'
sessions_case scenario connectAndChat "send $K1 connectAndChat" "chat connectAndChat $K1 $K1"
printf 'SmokeE2eTest.connectViaLoopback PASS\nSurvivalE2eTest.connectAndChat PASS\nSurvivalE2eTest.stopDuringRun PASS\n' | instrument_for >"$E2E_OUT/sess/instrument.txt"
rm -f "$t/server-calls" "$t/deleted"
rc=0; o=$(check_sessions 2>&1) || rc=$?
check "mismatch with a complete entry: fails, still proves and deletes only the journaled session" '[ "$rc" = 1 ] && has "$o" "may remain" && [ "$(cat "$t/server-calls")" = "$(printf "GET api/sessions/%s\nDELETE api/sessions/%s\nGET api/sessions/%s" "$K1" "$K1" "$K1")" ]'
sessions_case scenario ''
published=''
rc=0; o=$(check_sessions 2>&1) || rc=$?
check "results not verified (nothing published), empty journal: fails, no request" '[ "$rc" = 1 ] && has "$o" "may remain" && [ "$(requests)" = 0 ]'
sessions_case scenario ''
rm -f "$E2E_OUT/sess/results.txt"
rc=0; o=$(check_sessions 2>&1) || rc=$?
check "missing results.txt and an empty journal: fails, sessions may remain, no request" '[ "$rc" = 1 ] && has "$o" "may remain" && [ "$(requests)" = 0 ]'
for m in smoke scenario; do
  sessions_case "$m" ''
  : >"$E2E_OUT/sess/results.txt"
  rc=0; o=$(check_sessions 2>&1) || rc=$?
  check "empty results.txt ($m): fails, sessions may remain, no request" '[ "$rc" = 1 ] && has "$o" "may remain" && [ "$(requests)" = 0 ]'
done
sessions_case scenario ''
chmod 000 "$E2E_OUT/sess/results.txt"
rc=0; o=$(check_sessions 2>&1) || rc=$?
chmod 600 "$E2E_OUT/sess/results.txt"
check "unreadable results.txt: fails, no request" '[ "$rc" = 1 ] && [ "$(requests)" = 0 ]'
sessions_case scenario ''
printf 'something else\n' >"$E2E_OUT/sess/results.txt"
rc=0; o=$(check_sessions 2>&1) || rc=$?
check "results.txt that is not a results list: fails, no request" '[ "$rc" = 1 ] && [ "$(requests)" = 0 ]'
sessions_case smoke ''
# shellcheck disable=SC2329 # the row validator fails (exit 2) on a well-formed file; every other grep is real
rc=0; o=$(grep() { if [ "$1" = -qvE ]; then return 2; fi; command grep "$@"; }; check_sessions 2>&1) || rc=$?
check "results validator error (grep exit 2): fails, no verified results, no request" '[ "$rc" = 1 ] && has "$o" "no verified results file" && [ "$(requests)" = 0 ]'
rm -f "$E2E_OUT/sess/results.txt" "$E2E_OUT/sess/instrument.txt"
rmdir "$E2E_OUT/sess"
key_sent_local='' sessions_state='' journal=() published=''
rm -f "$t/server-calls"
check "key never sent: no sessions possible, no request" 'check_sessions 2>/dev/null && [ "$(requests)" = 0 ]'
unset -f hermes
mode=smoke

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
rc=0; m=$(verify_probe "${o/AVD=absent/AVD=unknown}" 2>&1) || rc=$?
check "postcheck: an inaccessible or unexpected value fails" '[ "$rc" = 1 ] && has "$m" "AVD=absent"'
check "postcheck: an empty report (SSH failed) fails" '! verify_probe "" 2>/dev/null'
mkdir -p "$t/st9/lock.d"
printf 'run-x\n' >"$t/st9/lock.d/owner"
o=$(probe "$t/st9" run-x "$PORT_RANGES" "${r#LISTENERS=}" "$EMU_RE" "$ADB_RE" "$SCRIPT_RE")
check "probe: this run's lock still there is LOCK=held, fails" 'has "$o" LOCK=held && ! verify_probe "$o" 2>/dev/null'
mkdir -p "$t/st9/noaccess"
chmod 000 "$t/st9/noaccess"
check "probe: an unsearchable directory is unknown, not absent" '[ "$(path_state "$t/st9/noaccess/x")" = unknown ]'
chmod 700 "$t/st9/noaccess"
# The port listing: a failing ss, or output that does not parse, gives PORTS=unknown, never closed.
mkdir -p "$t/ssfail" "$t/ssodd"
printf '#!/bin/sh\nexit 1\n' >"$t/ssfail/ss"
printf '#!/bin/sh\necho "LISTEN 0 4096 127.0.0.1:5037 0.0.0.0:*"\necho "LISTEN 0 4096 garbled"\n' >"$t/ssodd/ss"
chmod +x "$t/ssfail/ss" "$t/ssodd/ss"
o=$(PATH="$t/ssfail:$PATH" probe "$t/nostate" run-x "$PORT_RANGES" none "$EMU_RE" "$ADB_RE" "$SCRIPT_RE")
check "probe: a failing port listing gives PORTS=unknown and fails" 'has "$o" PORTS=unknown && lacks "$o" PORTS=closed && ! verify_probe "$o" 2>/dev/null'
o=$(PATH="$t/ssodd:$PATH" probe "$t/nostate" run-x "$PORT_RANGES" none "$EMU_RE" "$ADB_RE" "$SCRIPT_RE")
check "probe: an unparsable port listing gives PORTS=unknown and fails" 'has "$o" PORTS=unknown && ! verify_probe "$o" 2>/dev/null'
mkdir -p "$t/ssok"
printf '#!/bin/sh\necho "LISTEN 0 4096 127.0.0.1:5037 0.0.0.0:*"\n' >"$t/ssok/ss"
chmod +x "$t/ssok/ss"
o=$(PATH="$t/ssok:$PATH" probe "$t/nostate" run-x "$PORT_RANGES" none "$EMU_RE" "$ADB_RE" "$SCRIPT_RE")
check "probe: a parsed listing with a new port in the ranges gives PORTS=open (control)" 'has "$o" PORTS=open && [ "$(PATH="$t/ssok:$PATH" listeners_now "$PORT_RANGES")" = LISTENERS=5037 ]'
# Each step of each probe failing in turn gives unknown, never absent, released or closed (port 5037 is listening).
for c in awk sort paste dirname id pgrep; do
  mkdir -p "$t/fail-$c"
  printf '#!/bin/sh\nexit 3\n' >"$t/fail-$c/$c"
  chmod +x "$t/fail-$c/$c"
done
for c in awk sort paste; do
  o=$(PATH="$t/fail-$c:$t/ssok:$PATH" probe "$t/nostate" run-x "$PORT_RANGES" 5037 "$EMU_RE" "$ADB_RE" "$SCRIPT_RE")
  check "probe: $c failing in the port listing gives PORTS=unknown, not closed" 'has "$o" PORTS=unknown && ! verify_probe "$o" 2>/dev/null'
done
o=$(PATH="$t/fail-dirname:$PATH" probe "$t/nostate" run-x "$PORT_RANGES" none "$EMU_RE" "$ADB_RE" "$SCRIPT_RE")
check "probe: dirname failing gives AVD, RUNDIR and LOCK unknown, not absent" 'has "$o" AVD=unknown && has "$o" RUNDIR=unknown && has "$o" LOCK=unknown && ! verify_probe "$o" 2>/dev/null'
for c in id pgrep; do
  o=$(PATH="$t/fail-$c:$PATH" probe "$t/nostate" run-x "$PORT_RANGES" none "$EMU_RE" "$ADB_RE" "$SCRIPT_RE")
  check "probe: $c failing gives the process facts unknown, not absent" 'has "$o" EMULATOR=unknown && has "$o" ADB=unknown && has "$o" REMOTE=unknown && ! verify_probe "$o" 2>/dev/null'
done

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
# Copies the listed files like the real one and leaves a temporary file holding the key in its --temp-dir, then
# stays busy (or, with FAKE_KILL, kills itself) so the import is cut off mid-copy.
for a in "$@"; do case $a in --files-from=*) list=${a#*=} ;; --temp-dir=*) td=${a#*=} ;; esac; done
src=${*: -2:1} dst=${*: -1}
while IFS= read -r f; do cp -- "$src/$f" "$dst/$f"; done <"$list"
printf 'partial %s\n' "$FAKE_KEY" >"${td:?no --temp-dir}/.logcat.txt.Ab12Cd"
[ -z "${FAKE_KILL:-}" ] || kill -KILL $$
sleep 3
EOF
chmod +x "$t/bin/rsync"
make_export "$t/src11"
printf '%s\n' "${export_files[@]}" >"$t/files11"
set -m # job control: the child gets SIGINT like a foreground run would
FAKE_KEY=$key PATH="$t/bin:$PATH" APOFIS_E2E_LIB=1 bash -c '
  source "$1/run-e2e.sh"
  set +e
  tmp=$(mktemp -d) E2E_ROOT=${2%/build/e2e} E2E_OUT=$2 run_id=run11 key=$3
  mapfile -t export_files <"$4"
  exec {orig_out}>&1 {orig_err}>&2 # as main does
  trap local_cleanup EXIT
  trap "on_signal 130" INT
  import_export "$5/"
  echo "import returned" >&2' _ "$here" "$E2E_OUT" "$key" "$t/files11" "$t/src11" >"$t/out11" 2>&1 &
pid=$!
set +m
sleep 1.5
check "interrupt during import: the incoming files (and a temporary file) exist mid-import" '[ -e "$E2E_OUT/.incoming-run11/logcat.txt" ] && [ -e "$E2E_OUT/.incoming-run11/.logcat.txt.Ab12Cd" ]'
kill -INT "$pid"
rc=0; wait "$pid" || rc=$?
check "interrupt during import: exit 130, no final directory, no incoming files" '[ "$rc" = 130 ] && nothing_kept run11 && lacks "$(cat "$t/out11")" "import returned"'
check "interrupt inside a redirected call: the cleanup's messages still reach the terminal" 'has "$(cat "$t/out11")" "the key never left this host"'

# 11b. A killed copy (the receiver dies mid-file, leaving a temporary file with the key): the incoming directory is gone.
make_export "$t/src11b"
run_id=run11b rc=0
o=$(FAKE_KILL=1 FAKE_KEY=$key PATH="$t/bin:$PATH" import_export "$t/src11b/" 2>&1) || rc=$?
check "killed copy: fails, the incoming directory with its temporary file is absent, nothing kept" '[ "$rc" != 0 ] && nothing_kept run11b && has "$o" "stage copy"'
# The pattern delete refuses anything but an incoming directory directly inside build/e2e.
mkdir -p "$t/other/.incoming-x" "$E2E_OUT/notincoming"
touch "$t/other/.incoming-x/keep" "$E2E_OUT/notincoming/keep"
incoming=$t/other/.incoming-x rc=0; drop_incoming 2>/dev/null || rc=$?
check "incoming cleanup: refuses a directory outside build/e2e" '[ "$rc" = 1 ] && [ -e "$t/other/.incoming-x/keep" ]'
incoming=$E2E_OUT/notincoming rc=0; drop_incoming 2>/dev/null || rc=$?
check "incoming cleanup: refuses a name without .incoming-" '[ "$rc" = 1 ] && [ -e "$E2E_OUT/notincoming/keep" ]'

# 11c. TERM while the incoming directory is being deleted: it ends up gone, or left and reported; never silently left.
real_find=$(command -v find)
mkdir -p "$t/slowfind"
printf '#!/bin/sh\nsleep 1.5\nexec %s "$@"\n' "$real_find" >"$t/slowfind/find"
chmod +x "$t/slowfind/find"
term_case() { # <name> <how the child reaches drop_incoming: direct|cleanup>
  mkdir -p "$E2E_OUT/.incoming-$1"
  printf 'partial %s\n' "$key" >"$E2E_OUT/.incoming-$1/.logcat.txt.Ab12Cd"
  PATH="$t/slowfind:$PATH" APOFIS_E2E_LIB=1 bash -c '
    source "$1/run-e2e.sh"
    set +e
    tmp=$(mktemp -d) E2E_ROOT=$2 E2E_OUT=$2/build/e2e run_id=$3 incoming=$2/build/e2e/.incoming-$3
    exec {orig_out}>&1 {orig_err}>&2
    trap local_cleanup EXIT
    trap "on_signal 143" TERM
    case $4 in direct) drop_incoming; echo "drop returned" >&2 ;; cleanup) exit 0 ;; esac' _ "$here" "$t" "$1" "$2" >"$t/out-$1" 2>&1 &
  pid=$!
  sleep 0.7
  kill -TERM "$pid"
  rc=0; wait "$pid" || rc=$?
}
term_case runT1 direct
check "TERM during the import's delete: exit 143, the incoming directory is gone" '[ "$rc" = 143 ] && [ ! -e "$E2E_OUT/.incoming-runT1" ]'
term_case runT2 cleanup
check "TERM during the cleanup's delete: ignored, the incoming directory is gone" '[ "$rc" = 0 ] && [ ! -e "$E2E_OUT/.incoming-runT2" ]'
# A delete that cannot complete is reported with the directory's name and fails the run.
mkdir -p "$E2E_OUT/.incoming-runT3/sub"
touch "$E2E_OUT/.incoming-runT3/sub/x"
chmod 500 "$E2E_OUT/.incoming-runT3/sub"
run_id=runT3 incoming=$E2E_OUT/.incoming-runT3 rc=0
o=$(chmod() { :; }; drop_incoming 2>&1) || rc=$?
command chmod 700 "$E2E_OUT/.incoming-runT3/sub"
check "a delete that fails: non-zero, names the directory to remove by hand, keeps the record" '[ "$rc" = 1 ] && has "$o" "could not delete $E2E_OUT/.incoming-runT3" && has "$o" "by hand"'
incoming=$E2E_OUT/.incoming-runT3
drop_incoming
check "the kept record lets a retry delete it" '[ ! -e "$E2E_OUT/.incoming-runT3" ] && [ -z "$incoming" ]'

# 11d. build/e2e replaced by a symbolic link: refused, nothing outside the repository touched.
mkdir -p "$t/sym/build" "$t/elsewhere/.incoming-runS"
ln -s "$t/elsewhere" "$t/sym/build/e2e"
touch "$t/elsewhere/.incoming-runS/keep"
saved_root=$E2E_ROOT saved_out=$E2E_OUT
E2E_ROOT=$t/sym E2E_OUT=$t/sym/build/e2e run_id=runS incoming=$t/sym/build/e2e/.incoming-runS rc=0
o=$(drop_incoming 2>&1) || rc=$?
check "symlinked build/e2e: the delete is refused with a fixed message, nothing outside touched" '[ "$rc" = 1 ] && has "$o" "refusing to delete" && [ -e "$t/elsewhere/.incoming-runS/keep" ]'
make_export "$t/srcS"
run_id=runS2 incoming='' rc=0
o=$(import_export "$t/srcS/" 2>&1) || rc=$?
check "symlinked build/e2e: the import is refused, nothing created outside" '[ "$rc" = 1 ] && has "$o" "not a real directory" && [ "$(ls -A "$t/elsewhere")" = .incoming-runS ]'
E2E_ROOT=$saved_root E2E_OUT=$saved_out incoming=''
rm -f "$t/sym/build/e2e"

# 11e. A lookup error is not absence: with build/e2e unsearchable the incoming directory cannot be seen, so nothing
# proves it gone; the delete fails, names it and keeps the record.
mkdir -p "$t/eacc/build/e2e/.incoming-runE"
touch "$t/eacc/build/e2e/.incoming-runE/.logcat.txt.Ab12Cd"
command chmod 000 "$t/eacc/build/e2e"
E2E_ROOT=$t/eacc E2E_OUT=$t/eacc/build/e2e run_id=runE incoming=$t/eacc/build/e2e/.incoming-runE rc=0
drop_incoming >"$t/outE" 2>&1 || rc=$? # in this shell, so a cleared record would show
command chmod 700 "$t/eacc/build/e2e"
o=$(cat "$t/outE")
check "lookup error during the delete: fails, names the directory, keeps the record" '[ "$rc" = 1 ] && has "$o" ".incoming-runE" && has "$o" "by hand" && [ "$incoming" = "$t/eacc/build/e2e/.incoming-runE" ] && [ -e "$t/eacc/build/e2e/.incoming-runE/.logcat.txt.Ab12Cd" ]'
E2E_ROOT=$saved_root E2E_OUT=$saved_out incoming=''
# Absence needs a successful listing that lacks the literal name: not formatted ls output, not a failed listing.
mkdir -p "$E2E_OUT/.incoming-runQ"
touch "$E2E_OUT/.incoming-runQ/.logcat.txt.Ab12Cd"
check "a present entry under QUOTING_STYLE=shell-always is not reported gone" '! QUOTING_STYLE=shell-always listed_absent "$E2E_OUT/.incoming-runQ"'
# shellcheck disable=SC2329 # listings that fail
check "a failing listing is not reported gone" '! (find() { return 1; }; ls() { return 2; }; listed_absent "$E2E_OUT/.incoming-runQ") && ! (find() { return 1; }; ls() { return 2; }; listed_absent "$E2E_OUT/.incoming-none")'
run_id=runQ incoming=$E2E_OUT/.incoming-runQ rc=0
export QUOTING_STYLE=shell-always
drop_incoming >/dev/null 2>&1 || rc=$?
unset QUOTING_STYLE
check "QUOTING_STYLE=shell-always: the incoming directory is still deleted" '[ "$rc" = 0 ] && [ -z "$incoming" ] && [ ! -e "$E2E_OUT/.incoming-runQ" ]'
check "absence proved by a listing that lacks the name" 'listed_absent "$E2E_OUT/.incoming-runQ"'
# An import whose delete fails never claims that nothing was kept.
make_export "$t/srcE"
printf 'planted change\n' >>"$t/srcE/logcat.txt"
run_id=runE2 rc=0
# shellcheck disable=SC2329 # a delete that fails
o=$(drop_incoming() { log "FAILED: could not delete it; remove it by hand"; return 1; }; import_export "$t/srcE/" 2>&1) || rc=$?
check "failed delete after a failed import: no claim that nothing was kept" '[ "$rc" = 1 ] && has "$o" "by hand" && lacks "$o" "nothing kept"'
remove_tree "$E2E_OUT/.incoming-runE2"

# 11f. Checks before create: a repository path that cannot be resolved refuses before anything is created.
mkdir "$t/rp"
E2E_ROOT=$t/rp E2E_OUT=$t/rp/build/e2e run_id=runR incoming='' rc=0
# shellcheck disable=SC2329 # canonical paths unavailable
o=$(realpath() { return 1; }; import_export "$t/srcS/" 2>&1) || rc=$?
check "unresolvable repository path: refused, nothing created" '[ "$rc" = 1 ] && [ -z "$(ls -A "$t/rp")" ]'
E2E_ROOT=$t/gone E2E_OUT=$t/gone/build/e2e run_id=runR rc=0
o=$(import_export "$t/srcS/" 2>&1) || rc=$?
check "missing repository directory: refused, nothing created" '[ "$rc" = 1 ] && [ ! -e "$t/gone" ]'
E2E_ROOT=$saved_root E2E_OUT=$saved_out incoming=''

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
# 12b. The watchdog's cancel works and leaves no sleep behind, whatever the interleaving. Fakes: a sleep that records
# its pid (the only processes this test signals), a bash that starts late (the cancel arrives before the watchdog
# runs), a setsid that acts late, and a kill that delivers each target separately, 1 s apart (as if the cleanup were
# descheduled between them: the earlier group-then-pid cancel left the sleep running here).
real_bash=$(command -v bash) real_sleep=$(command -v sleep) real_setsid=$(command -v setsid)
mkdir -p "$t/wdbin" "$t/wdlate"
mkfifo "$t/wdfifo"
printf '#!/bin/sh\necho $$ >>"$WD_PIDS"\nexec %s "$@"\n' "$real_sleep" >"$t/wdbin/sleep"
printf '#!%s\nexec 9<>"$WD_FIFO"; read -r -t 1.5 -u 9 _; exec %s "$@"\n' "$real_bash" "$real_setsid" >"$t/wdbin/setsid"
printf '#!%s\nexec 9<>"$WD_FIFO"; read -r -t 1.5 -u 9 _; exec %s "$@"\n' "$real_bash" "$real_bash" >"$t/wdlate/bash"
chmod +x "$t/wdbin/sleep" "$t/wdbin/setsid" "$t/wdlate/bash"
wd_case() { # <name> <extra PATH dir or ""> <split kill: 1 or "">
  : >"$t/wdpids-$1"
  s=$SECONDS rc=0
  WD_PIDS=$t/wdpids-$1 WD_FIFO=$t/wdfifo APOFIS_E2E_LIB=1 bash -c '
    source "$1/run-e2e.sh"
    set +e
    TRAP_LIMIT=6 tmp=$(mktemp -d)
    PATH=$2$3:$PATH
    if [ -n "$4" ]; then
      exec 9<>"$WD_FIFO"
      kill() { local sig=$1 a; shift; [ "$1" != -- ] || shift; for a; do read -r -t 1 -u 9 _; builtin kill "$sig" -- "$a"; done; }
    fi
    trap local_cleanup EXIT
    exit 0' _ "$here" "${2:+$2:}" "$t/wdbin" "$3" >"$t/out-$1" 2>&1 || rc=$?
  # shellcheck disable=SC2034 # read by check
  el=$((SECONDS - s))
  read -r -t 2.5 -u 8 _ 8<>"$t/wdfifo" || true # past every fake delay: a surviving watchdog would have started its sleep
  alive_sleeps=''
  while read -r p; do if kill -0 "$p" 2>/dev/null; then alive_sleeps+=" $p"; kill -KILL "$p"; fi; done <"$t/wdpids-$1"
}
wd_case wd-late "$t/wdlate" ''
check "watchdog cancelled before it starts: exit 0 at once, no sleep ever left" '[ "$rc" = 0 ] && [ "$el" -lt 3 ] && [ -z "$alive_sleeps" ]'
wd_case wd-split '' 1
check "watchdog cancel delayed past its start (split signals, late setsid): exit 0, no sleep left" '[ "$rc" = 0 ] && [ "$el" -lt 5 ] && [ -z "$alive_sleeps" ]'

# 12c. A watchdog that cannot be armed (no FIFO) warns and the whole cleanup still runs, without a limit: the lock is
# released and the sessions step runs. The lock release takes 1 s, so an alarm from a failed watchdog would cut it.
rc=0
STEPS=$t/steps12c APOFIS_E2E_LIB=1 bash -c '
  source "$1/run-e2e.sh"
  set +e
  TRAP_LIMIT=30 tmp=$(mktemp -d) key=x lock_tried=1 run_id=runW
  mkfifo() { return 1; }
  rsh_fn() { sleep 1; echo lock >>"$STEPS"; echo RELEASED; }
  check_sessions() { echo sessions >>"$STEPS"; }
  trap local_cleanup EXIT
  exit 0' _ "$here" >"$t/out12c" 2>&1 || rc=$?
check "watchdog FIFO cannot be made: warns, releases the lock and runs the sessions step, exit 0" '[ "$rc" = 0 ] && has "$(cat "$t/out12c")" "WARNING: could not arm" && lacks "$(cat "$t/out12c")" "did not finish" && [ "$(cat "$t/steps12c" 2>/dev/null)" = "$(printf "lock\nsessions")" ]'

# 13. Every instrumentation test class writes its own result rows (Evidence), which the cleanup requires.
for f in "$here"/../../e2e/src/main/kotlin/xyz/rycerz/apofis/e2e/*.kt; do
  grep -q '^ *@Test' "$f" || continue
  check "$(basename "$f" .kt) writes result rows (Evidence rule)" 'grep -q "@get:Rule val evidence = Evidence(" "$f"'
done

# 14. Milestone 3: the selection matrix, the model-start ledger, push provisioning and push record cleanup (rule C3).
sel() { push=() models=() tests=''; select_tests "$@"; }
check "selection: an unknown selection is refused" '! sel bogus && ! sel test PushE2eTest && ! sel test PushE2eTest#nope && ! sel test PushE2eTestX#connectorLifecycle'
check "selection: --full never selects a push test nor a ledgered method, and its filter excludes PushE2eTest" \
  'sel full && [ "$tests" = all ] && [ "${#push[@]}" = 0 ] && [ "${#models[@]}" = 0 ] && [ "$(instrument_filter all)" = "-e package $PKG -e notClass $PKG.PushE2eTest" ]'
check "selection: --push provisions the three gate 1 scenarios, no model start" \
  'sel push && [ "${push[*]}" = "connectorLifecycle hostilePushes permissionAndTestPush" ] && [ "${#models[@]}" = 0 ] && [ "$(instrument_filter push)" = "-e class $PKG.PushE2eTest#connectorLifecycle,$PKG.PushE2eTest#hostilePushes,$PKG.PushE2eTest#permissionAndTestPush" ]'
check "selection: --push-model reserves the three model methods and provisions only the push one" \
  'sel push-model && [ "${models[*]}" = "${PUSH_MODEL[*]}" ] && [ "${push[*]}" = pushWakesKilledApp ] && [ "$(instrument_filter push-model)" = "-e class $PKG.PushE2eTest#pushWakesKilledApp,$PKG.SurvivalE2eTest#connectAndChat,$PKG.SurvivalE2eTest#replySurvivesProcessDeath" ]'
check "selection: --test PushE2eTest#x provisions like its row" \
  'sel test PushE2eTest#hostilePushes && [ "${push[*]}" = hostilePushes ] && [ "${#models[@]}" = 0 ] && sel test PushE2eTest#pushWakesKilledApp && [ "${push[*]}" = pushWakesKilledApp ] && [ "${models[*]}" = PushE2eTest#pushWakesKilledApp ]'
check "selection: --test of a model method of the survival suite is ledgered, without push" \
  'sel test SurvivalE2eTest#replySurvivesProcessDeath && [ "${models[*]}" = SurvivalE2eTest#replySurvivesProcessDeath ] && [ "${#push[@]}" = 0 ]'
check "selection: suite names are never --test arguments" '! sel test push-model && ! sel test push && ! sel test all && ! sel test smoke'
check "selection: an unknown class, method or scenario is refused" '! sel test FooE2eTest && ! sel test SurvivalE2eTest#nope && ! sel test survivalE2eTest && ! sel scenario bogus && ! sel scenario "connectAndChat x"'
check "selection: the legacy survival selections keep working, unledgered but model-capable, with their filters" \
  'sel test SurvivalE2eTest && [ -n "$model_capable" ] && [ "${#models[@]}" = 0 ] && [ "$(instrument_filter "$tests")" = "-e class $PKG.SurvivalE2eTest" ] && sel scenario steerDuringRun && [ -n "$model_capable" ] && [ "${#models[@]}" = 0 ] && [ "$(instrument_filter "$tests")" = "-e class $PKG.SurvivalE2eTest#steerDuringRun" ] && sel test SurvivalE2eTest#steerDuringRun && [ -n "$model_capable" ] && [ "${#models[@]}" = 0 ]'
check "selection: --full is model-capable; smoke, push and their methods are not" \
  'sel full && [ -n "$model_capable" ] && sel smoke && [ -z "$model_capable" ] && sel push && [ -z "$model_capable" ] && sel test SmokeE2eTest#connectViaLoopback && [ -z "$model_capable" ] && sel test PushE2eTest#hostilePushes && [ -z "$model_capable" ] && sel test HarnessSelfTest && [ -z "$model_capable" ]'
check "selection: gate 2 (--test PushE2eTest#pushWakesKilledApp) reserves 1, provisions push, and runs only that method" \
  'sel test PushE2eTest#pushWakesKilledApp && [ "${models[*]}" = PushE2eTest#pushWakesKilledApp ] && [ "${MODEL_BOUND[${models[0]}]}" = 1 ] && [ "${push[*]}" = pushWakesKilledApp ] && [ -n "$model_capable" ] && [ "$(instrument_filter "$tests")" = "-e class $PKG.PushE2eTest#pushWakesKilledApp" ]'
for a in "--test push-model" "--push extra" "--test" "--test SurvivalE2eTest#connectAndChat extra" "--scenario bogus"; do
  read -ra words <<<"$a"
  # The first step after the checks is stubbed to stop: a refusal that failed ends there, before any lock or the key.
  # shellcheck disable=SC2329
  rc=0; o=$( (no_core_dumps() { echo "PASSED THE CHECKS"; return 1; }; main "${words[@]}") 2>&1) || rc=$?
  check "main: '$a' refused before any side effect" '[ "$rc" != 0 ] && lacks "$o" "PASSED THE CHECKS"'
done
mkdir -p "$t/hw/.hermes"
printf 'x apofis_push.py\ny apofis_push.py\n' >"$t/hw/.hermes/config.yaml"
o=$(HOME=$t/hw hook_warning 2>&1)
check "hook warning: counts, and 0 for a missing file" 'has "$o" "config.yaml=2" && has "$o" "shell-hooks-allowlist.json=0"'
chmod 000 "$t/hw/.hermes/config.yaml"
o=$(HOME=$t/hw hook_warning 2>&1)
chmod 600 "$t/hw/.hermes/config.yaml"
check "hook warning: an unreadable file is unknown, not 0" 'has "$o" "config.yaml=unknown"'
check "selection: every ledgered method has a proved bound of 1" 'for m in "${PUSH_MODEL[@]}"; do [ "${MODEL_BOUND[$m]}" = 1 ] || exit 1; done; [ "${#MODEL_BOUND[@]}" = 3 ]'

L=$t/ledger/model-budget
# shellcheck disable=SC2034 # read by check
R1=20261123-120000-aaaa R2=20261123-130000-bbbb R3=20261123-140000-cccc
check "ledger: a first reservation creates the ledger and records it" \
  'ledger_reserve "$L" 8 $R1 PushE2eTest#pushWakesKilledApp=1 SurvivalE2eTest#connectAndChat=1 SurvivalE2eTest#replySurvivesProcessDeath=1 && [ "$(grep -c "^reserve $R1 " "$L")" = 3 ] && [ "$(ledger_total "$L")" = 3 ]'
check "ledger: observed starts are recorded apart from the reservation and replace its bound" \
  'ledger_observe "$L" $R1 SurvivalE2eTest#connectAndChat 0 && grep -qx "reserve $R1 SurvivalE2eTest#connectAndChat 1" "$L" && grep -qx "observed $R1 SurvivalE2eTest#connectAndChat 0" "$L" && [ "$(ledger_total "$L")" = 2 ]'
check "ledger: an observation is recorded once per reservation, never without one" \
  '! ledger_observe "$L" $R1 SurvivalE2eTest#connectAndChat 1 && ! ledger_observe "$L" $R2 SurvivalE2eTest#connectAndChat 1 && [ "$(ledger_total "$L")" = 2 ]'
cp "$L" "$t/ledger.before"
check "ledger: a reservation that would pass 8 is refused and appends nothing" \
  'ledger_reserve "$L" 8 $R2 PushE2eTest#pushWakesKilledApp=6 && ! ledger_reserve "$L" 8 $R3 SurvivalE2eTest#connectAndChat=1 && [ "$(ledger_total "$L")" = 8 ] && ! grep -q $R3 "$L"'
cp "$t/ledger.before" "$L"
printf 'reserve %s X#y 1\nsomething else\n' $R3 >>"$L"
check "ledger: a malformed line refuses every reservation" '! ledger_total "$L" >/dev/null && ! ledger_reserve "$L" 8 $R2 X#y=1 && ! grep -q $R2 "$L"'
cp "$t/ledger.before" "$L"
printf 'spent %s X#y 1\n' $R3 >>"$L"
check "ledger: a well-formed line of another kind refuses too" '! ledger_reserve "$L" 8 $R2 X#y=1'
cp "$t/ledger.before" "$L"
printf 'reserve %s PushE2eTest#pushWakesKilledApp 1\n' $R1 >>"$L"
check "ledger: a second reservation of one run's method is ambiguous: refused" '! ledger_reserve "$L" 8 $R2 X#y=1'
cp "$t/ledger.before" "$L"
printf 'observed %s X#y 1\n' $R3 >>"$L"
check "ledger: an observation without its reservation is ambiguous: refused" '! ledger_reserve "$L" 8 $R2 X#y=1'
cp "$t/ledger.before" "$L"
chmod 000 "$L"
check "ledger: an unreadable ledger refuses" '! ledger_reserve "$L" 8 $R2 X#y=1'
chmod 600 "$L"
cp "$t/ledger.before" "$L"
printf 'reserve %s X#y 1' $R3 >>"$L" # torn: no line break
check "ledger: a torn last line refuses, appending nothing" '! ledger_total "$L" >/dev/null && ! ledger_reserve "$L" 8 $R2 X#y=1 && ! grep -q $R2 "$L"'
cp "$t/ledger.before" "$L"
check "ledger: a proposal that repeats a run and method, or one already reserved, is refused" \
  '! ledger_reserve "$L" 8 $R2 X#y=1 X#y=1 && ! ledger_reserve "$L" 8 $R1 SurvivalE2eTest#connectAndChat=1 && cmp -s "$L" "$t/ledger.before"' 
check "ledger: a method without a proved bound cannot be reserved" '! ledger_reserve "$L" 8 $R2 SurvivalE2eTest#steerDuringRun= && ! ledger_reserve "$L" 8 $R2 SurvivalE2eTest#steerDuringRun'
mkfifo "$t/held" "$t/release"
( flock 9; echo held >"$t/held"; read -r _ <"$t/release" ) 9>>"$L.lock" &
holder=$!
read -r _ <"$t/held" # the holder has the lock now
( sleep 2; echo go >"$t/release" ) &
s=$SECONDS
check "ledger: appends wait for the shared flock" 'ledger_reserve "$L" 8 $R2 X#y=1 && [ $((SECONDS - s)) -ge 1 ]'
wait "$holder"
check "ledger: the shared path is outside any checkout" '[ "$LEDGER" = "$HOME/.local/state/apofis-e2e/model-budget-m3" ] && [ "$LEDGER_LIMIT" = 8 ]'

# Push records: a synthetic database stands in for the live one, read by the real listing (the real script).
PUSH_SCRIPT=$here/../../server/apofis-push/apofis_push.py
LIVE_DB=$t/live/state.db
mkdir -p "$t/live"
python3 -I - "$LIVE_DB" <<'EOF'
import sqlite3, sys
c = sqlite3.connect(sys.argv[1])
c.executescript("""CREATE TABLE system_prompts (hash TEXT PRIMARY KEY, prompt TEXT NOT NULL);
CREATE TABLE sessions (id TEXT PRIMARY KEY, source TEXT NOT NULL, model_config TEXT, system_prompt TEXT,
  system_prompt_hash TEXT, parent_session_id TEXT, started_at REAL NOT NULL, end_reason TEXT, title TEXT,
  hidden INTEGER NOT NULL DEFAULT 0);""")
c.close()
EOF
row() { python3 -I -c 'import sqlite3, sys; c = sqlite3.connect(sys.argv[1]); c.execute("INSERT INTO sessions (id, source, hidden, started_at, title) VALUES (?, ?, 1, 0, ?)", sys.argv[2:5]); c.commit()' "$LIVE_DB" "$@"; }
IA=0a1b2c3d-0000-4000-8000-00000000000a IB=0a1b2c3d-0000-4000-8000-00000000000b IF=0a1b2c3d-0000-4000-8000-0000000000ff
push_dir=$t/pushdir
mkdir -p "$push_dir/check-home"
check "listing: an empty namespace lists nothing" 'o=$(push_listing $IA) && [ -z "$o" ]'
row "apofis-push-$IA-1" api_server "apofis-push-$IA-1 op-0123456789abcdef e2e-$nonce"
check "listing: a record under the install is listed by id only" 'o=$(push_listing $IA) && [ "$o" = "apofis-push-$IA-1" ] && o=$(push_listing $IB) && [ -z "$o" ]'
check "listing: a failed listing (no database) fails, never empty" '! (LIVE_DB=$t/live/missing.db push_listing $IA)'
python3 -I -c 'import sqlite3, sys; c = sqlite3.connect(sys.argv[1]); c.executemany("INSERT INTO sessions (id, source, hidden, started_at) VALUES (?, ?, 1, 0)", [("apofis-push-%s-%d" % (sys.argv[2], i), "api_server") for i in range(1, 34)]); c.commit()' "$LIVE_DB" "$IB"
check "listing: an incomplete listing (more than 32 records, exit 4) fails" '! push_listing $IB >/dev/null'
printf 'print("apofis-push-%s-1\\tt\\tapi_server\\t1")\n' "$IA" >"$t/noheader.py"
check "listing: output without the read-only header fails, whatever the exit" '! (PUSH_SCRIPT=$t/noheader.py push_listing $IA)'
printf 'import sys\nprint("python 3\\nSSL_CERT_FILE unset\\norigins none\\ndb opened read-only")\nsys.exit(3)\n' >"$t/badexit.py"
check "listing: a failing exit fails, even after a complete-looking header" '! (PUSH_SCRIPT=$t/badexit.py push_listing $IA)'
check "listing: the run-private home stays empty" '[ -z "$(ls -A "$push_dir/check-home")" ]'
# shellcheck disable=SC2329 # the namespace check before launch, with a listing that finds a record
nonempty_case() { push=(connectorLifecycle hostilePushes); tmp=$t/prov; mkdir -p "$tmp"; push_listing() { [ "$1" != "${install_of[hostilePushes]:-}" ] || echo "apofis-push-$1-1"; }; provision_push; }
rc=0; o=$( (nonempty_case) 2>&1) || rc=$?
check "provisioning: a namespace that is not empty stops the run before launch" '[ "$rc" != 0 ] && has "$o" "already has records on the server; nothing started"'
# shellcheck disable=SC2329
failing_case() { push=(connectorLifecycle); tmp=$t/prov2; mkdir -p "$tmp"; push_listing() { return 1; }; provision_push; }
rc=0; o=$( (failing_case) 2>&1) || rc=$?
check "provisioning: a failed listing stops the run before launch" '[ "$rc" != 0 ] && has "$o" "listing of connectorLifecycle'"'"'s new install failed"'
# shellcheck disable=SC2329
three_case() { push=("${PUSH_TESTS[@]}"); tmp=$t/prov3; mkdir -p "$tmp"; push_listing() { :; }; provision_push >/dev/null 2>&1; printf '%s\n' "$push_installs" "$push_topic"; }
o=$(three_case)
check "provisioning: one distinct install per scenario (no retired id is ever expected again) and a topic" \
  '[ "$(head -1 <<<"$o" | tr , "\n" | cut -d= -f2 | sort -u | grep -cE "^$UUID_RE$")" = 3 ] && [[ $(tail -1 <<<"$o") =~ ^up[A-Za-z0-9]{12}$ ]] && push_args_ok 18650 "$(tail -1 <<<"$o")" "$(head -1 <<<"$o")"'
check "push arguments: anything but the exact forms is refused" '! push_args_ok 18650 up123 "a=$IA" && ! push_args_ok 18650 upAbC123xyz09q "a=$IA;x" && ! push_args_ok - upAbC123xyz09q - && push_args_ok - - -'

T1="apofis-push-$IA-1 op-0123456789abcdef e2e-$nonce"
check "journal: a push intent and a missing export are journal lines" \
  'journal_line_ok "pushintent apofis-push-$IA-1 $T1" && journal_line_ok "pushintent-missing hostilePushes"'
check "journal: an intent whose title names another id, or without the marker, is malformed" \
  '! journal_line_ok "pushintent apofis-push-$IA-2 $T1" && ! journal_line_ok "pushintent apofis-push-$IA-1 apofis-push-$IA-1 e2e-$nonce" && ! journal_line_ok "pushintent apofis-push-$IA-01 apofis-push-$IA-01 op-0123456789abcdef e2e-$nonce"'

# The cleanup against a stub server holding rows by id (title and source), counting every request.
# shellcheck disable=SC2329 # replaces the real server call
hermes() {
  local id=${2#api/sessions/}
  echo "$1 $2" >>"$t/server-calls"
  case $1 in
    GET) if grep -q "^$id	" "$t/server-rows" 2>/dev/null; then
           jq -n --arg id "$id" --arg s "$(grep "^$id	" "$t/server-rows" | cut -f2)" --arg t "$(grep "^$id	" "$t/server-rows" | cut -f3)" \
             '{session: {id: $id, source: $s, title: $t}}' >"$3"; echo 200; else echo 404; fi ;;
    DELETE) grep -v "^$id	" "$t/server-rows" >"$t/rows.new"; mv "$t/rows.new" "$t/server-rows"; printf '{"id":"%s","deleted":true}' "$id" >"$3"; echo 200
            [ "${CONFIRM_FAILS:-}" != 1 ] || { echo 1 >"$t/confirm-fails"; } ;;
  esac
  # A transfer that fails after the status was printed (the confirming GET after a DELETE, when asked).
  if [ "$1" = GET ] && [ -e "$t/confirm-fails" ] && grep -q "^DELETE api/sessions/$id$" "$t/server-calls"; then rm -f "$t/confirm-fails"; return 28; fi
}
# shellcheck disable=SC2329 # the listing, from the stub server's rows
push_listing() { [ "${LISTING_FAILS:-}" != 1 ] || return 1; grep "^apofis-push-$1-" "$t/server-rows" | cut -f1 || true; }
push_case() { # <journal lines...>: two installs (A for connectorLifecycle, B for hostilePushes), the rows as set up
  push=(connectorLifecycle hostilePushes) install_of=([connectorLifecycle]=$IA [hostilePushes]=$IB)
  key_sent_local=1 sessions_state=ok journal=("$@")
  rm -f "$t/server-calls"
  rc=0; o=$(check_push 2>&1) || rc=$?
}
srow() { printf '%s\t%s\t%s\n' "$1" "${2:-api_server}" "${3:-$1 op-0123456789abcdef e2e-$nonce}" >>"$t/server-rows"; }
calls() { cat "$t/server-calls" 2>/dev/null; }
: >"$t/server-rows"; srow "apofis-push-$IA-1"; srow "apofis-push-$IB-1"
push_case "pushintent apofis-push-$IB-1 apofis-push-$IB-1 op-0123456789abcdef e2e-$nonce" "pushintent apofis-push-$IA-1 $T1"
check "cleanup: scenarios in reversed order: each one's own -1 is proved, deleted and checked gone; passes" \
  '[ "$rc" = 0 ] && [ "$(calls)" = "$(printf "GET api/sessions/%s\nDELETE api/sessions/%s\nGET api/sessions/%s\n" apofis-push-$IB-1 apofis-push-$IB-1 apofis-push-$IB-1 apofis-push-$IA-1 apofis-push-$IA-1 apofis-push-$IA-1 | head -c -1)" ] && [ ! -s "$t/server-rows" ]'
: >"$t/server-rows"; srow "apofis-push-$IF-1"
push_case "pushintent apofis-push-$IF-1 apofis-push-$IF-1 op-0123456789abcdef e2e-$nonce"
check "cleanup: a forged intent naming a valid hidden record under another install: zero requests" '[ "$rc" = 1 ] && [ -z "$(calls)" ] && grep -q "apofis-push-$IF-1" "$t/server-rows"'
: >"$t/server-rows"; srow "apofis-push-$IA-3" api_server "apofis-push-$IA-3"
push_case
check "cleanup: a canonical row under this run's install without an intent and marker: reported, not deleted" \
  '[ "$rc" = 1 ] && has "$o" "push records may remain (not deleted): apofis-push-$IA-3" && ! grep -q DELETE <<<"$(calls)" && grep -q "apofis-push-$IA-3" "$t/server-rows"'
: >"$t/server-rows"; srow "apofis-push-$IA-1" api_server "apofis-push-$IA-1 op-ffffffffffffffff e2e-$nonce"
push_case "pushintent apofis-push-$IA-1 $T1"
check "cleanup: a journaled id whose server title is not the journaled one: left alone, reported" \
  '[ "$rc" = 1 ] && [ "$(calls)" = "GET api/sessions/apofis-push-$IA-1" ] && has "$o" "may remain (not deleted): apofis-push-$IA-1"'
: >"$t/server-rows"; srow "apofis-push-$IA-1" cli
push_case "pushintent apofis-push-$IA-1 $T1"
check "cleanup: a journaled id whose server row is not api_server: left alone" '[ "$rc" = 1 ] && ! grep -q DELETE <<<"$(calls)"'
: >"$t/server-rows"; srow "apofis-push-$IA-01"
push_case
check "cleanup: a malformed suffix row: reported, not deleted" '[ "$rc" = 1 ] && has "$o" "apofis-push-$IA-01" && [ -z "$(calls)" ]'
: >"$t/server-rows"; srow "apofis-push-$IA-1"
push_case "pushintent apofis-push-$IA-1 apofis-push-$IA-1 op-0123456789abcdef e2e-00000000"
check "cleanup: an intent with another run's nonce: zero requests, reported" '[ "$rc" = 1 ] && [ -z "$(calls)" ] && has "$o" "apofis-push-$IA-1"'
: >"$t/server-rows"; srow "apofis-push-$IA-1"
push_case "pushintent-missing connectorLifecycle" "pushintent apofis-push-$IA-1 $T1"
check "cleanup: an install whose export failed is not cleaned at all, and reported" \
  '[ "$rc" = 1 ] && [ -z "$(calls)" ] && has "$o" "intents of connectorLifecycle could not be read" && has "$o" "may remain (not deleted): apofis-push-$IA-1"'
: >"$t/server-rows"; srow "apofis-push-$IA-1"
push=(connectorLifecycle hostilePushes) install_of=([connectorLifecycle]=$IA [hostilePushes]=$IB)
key_sent_local=1 sessions_state=unknown journal=("pushintent apofis-push-$IA-1 $T1")
rm -f "$t/server-calls"
rc=0; o=$(check_push 2>&1) || rc=$?
check "cleanup: an unknown journal deletes nothing and still lists" '[ "$rc" = 1 ] && [ -z "$(calls)" ] && has "$o" "no push record is deleted" && has "$o" "may remain (not deleted): apofis-push-$IA-1"'
: >"$t/server-rows"
push_case "pushintent apofis-push-$IA-1 $T1"
check "cleanup: an intent whose record was never created: one GET, nothing else; passes" '[ "$rc" = 0 ] && [ "$(calls)" = "GET api/sessions/apofis-push-$IA-1" ]'
: >"$t/server-rows"; srow "apofis-push-$IA-1"
CONFIRM_FAILS=1 push_case "pushintent apofis-push-$IA-1 $T1"
check "cleanup: a confirming GET whose transfer failed after printing 404 is not taken as gone" '[ "$rc" = 1 ] && has "$o" "deleting apofis-push-$IA-1 answered" && lacks "$o" "deleted and verified gone"'
: >"$t/server-rows"
push=(connectorLifecycle hostilePushes) install_of=([connectorLifecycle]=$IA) key_sent_local=1 sessions_state=ok journal=()
rc=0; o=$(check_push 2>&1) || rc=$?
check "cleanup: an install never minted (the run stopped before) is not listed nor reported" '[ "$rc" = 0 ] && lacks "$o" hostilePushes && has "$o" "no record left under connectorLifecycle"'
LISTING_FAILS=1 push_case
check "cleanup: a failed final listing fails the run and is never taken as empty" '[ "$rc" = 1 ] && has "$o" "push records may remain: the listing" && lacks "$o" "no record left"'
unset -f hermes push_listing

# pushWakesKilledApp is under the same send/chat completeness rule as the survival scenarios.
mkdir -p "$E2E_OUT/sess"
printf 'PushE2eTest.pushWakesKilledApp PASS\n' >"$E2E_OUT/sess/results.txt"
instrument_for <"$E2E_OUT/sess/results.txt" >"$E2E_OUT/sess/instrument.txt"
mode=push-model key_sent_local=1 sessions_state=ok published=1 run_id=sess journal=()
# shellcheck disable=SC2329
hermes() { echo "$1 $2" >>"$t/server-calls"; echo 404; }
rm -f "$t/server-calls"
rc=0; o=$(check_sessions 2>&1) || rc=$?
check "completeness: pushWakesKilledApp passed without send and chat lines: fails, no request" '[ "$rc" = 1 ] && has "$o" "sessions this run created may remain" && [ ! -e "$t/server-calls" ]'
unset -f hermes
rm -f "$E2E_OUT/sess/results.txt" "$E2E_OUT/sess/instrument.txt"
rmdir "$E2E_OUT/sess"

# A lost send line: check_sessions fails completeness, observe_starts keeps the bound, and the cap holds.
mkdir -p "$E2E_OUT/sess"
printf 'SurvivalE2eTest.connectAndChat PASS\n' >"$E2E_OUT/sess/results.txt"
instrument_for <"$E2E_OUT/sess/results.txt" >"$E2E_OUT/sess/instrument.txt"
LEDGER=$t/ledger-cap models=(SurvivalE2eTest#connectAndChat) mode=test key_sent_local=1 sessions_state=ok published=1 run_id=sess journal=() journal_complete='' starts=()
ledger_reserve "$LEDGER" 8 20261123-100000-0000 X#y=7
ledger_reserve "$LEDGER" 8 $R1 SurvivalE2eTest#connectAndChat=1
run_id=$R1
# shellcheck disable=SC2329
hermes() { echo "$1 $2" >>"$t/server-calls"; echo 404; }
(check_sessions; observe_starts) >"$t/i4.out" 2>&1 || true
check "observed: after a failed completeness check nothing lowers the reservation, and the cap still refuses" \
  'has "$(cat "$t/i4.out")" "may remain" && ! grep -q "^observed" "$LEDGER" && [ "$(ledger_total "$LEDGER")" = 8 ] && ! ledger_reserve "$LEDGER" 8 $R2 SurvivalE2eTest#connectAndChat=1'
unset -f hermes
rm -f "$E2E_OUT/sess/results.txt" "$E2E_OUT/sess/instrument.txt"
rmdir "$E2E_OUT/sess"
# The count: only a complete, matching record with the one sent turn counts.
mkdir -p "$E2E_OUT/sess"
printf 'SurvivalE2eTest.connectAndChat PASS\n' >"$E2E_OUT/sess/results.txt"
instrument_for <"$E2E_OUT/sess/results.txt" >"$E2E_OUT/sess/instrument.txt"
count_case() { # <messages answer as jq program on $id>
  LEDGER=$t/ledger-count-$RANDOM models=(SurvivalE2eTest#connectAndChat) mode=test key_sent_local=1 sessions_state=ok published=1 run_id=sess
  journal=("send $K1 connectAndChat" "chat connectAndChat $K1 $K1") journal_complete='' starts=()
  ledger_reserve "$LEDGER" 8 $R1 SurvivalE2eTest#connectAndChat=1
  MSG=$1
  (run_id=sess; check_sessions; run_id=$R1; observe_starts) >"$t/count.out" 2>&1 || true
}
# shellcheck disable=SC2329 # the server for count_case: the session proves this run's identity, messages as asked
hermes() {
  case "$1 $2" in
    GET\ api/sessions/*/messages*) jq -n --arg id "$K1" "$MSG" >"$3"; echo 200 ;;
    GET\ api/sessions/*) if [ -e "$t/gone" ]; then rm -f "$t/gone"; echo 404; else printf '{"session":{"id":"%s","title":"Test %s-1"}}' "$K1" "$nonce" >"$3"; echo 200; fi ;;
    DELETE*) printf '{"id":"%s","deleted":true}' "$K1" >"$3"; : >"$t/gone"; echo 200 ;;
  esac
}
OK_MSG='{object: "list", session_id: $id, data: [{role: "user"}, {role: "assistant"}], pagination: {limit: 500, returned: 2}}'
count_case "$OK_MSG"
check "observed: a complete record with the sent turn counts its user turns (1)" 'grep -qx "observed $R1 SurvivalE2eTest#connectAndChat 1" "$LEDGER"'
count_case '{object: "list", session_id: $id, data: [{role: "user"}], pagination: {limit: 500, returned: 2}}'
check "observed: a page that does not match its own count keeps the bound" '! grep -q "^observed" "$LEDGER"'
count_case '{object: "list", session_id: $id, data: [{role: "assistant"}], pagination: {limit: 500, returned: 1}}'
check "observed: a record without the sent turn (still materializing) keeps the bound" '! grep -q "^observed" "$LEDGER"'
count_case '{object: "list", session_id: "apofis-other", data: [{role: "user"}], pagination: {limit: 500, returned: 1}}'
check "observed: an answer for another session keeps the bound" '! grep -q "^observed" "$LEDGER"'
unset -f hermes
rm -f "$E2E_OUT/sess/results.txt" "$E2E_OUT/sess/instrument.txt"
rmdir "$E2E_OUT/sess"
mode=smoke journal_complete=''

# Observed starts after the run.
LEDGER=$t/ledger2 models=(SurvivalE2eTest#connectAndChat PushE2eTest#pushWakesKilledApp) run_id=$R1
ledger_reserve "$LEDGER" 8 $R1 SurvivalE2eTest#connectAndChat=1 PushE2eTest#pushWakesKilledApp=1
key_sent_local='' starts=()
observe_starts >/dev/null 2>&1
check "observed: no app ran (key never sent): 0 for each reserved method" '[ "$(grep -c "^observed $R1 .* 0$" "$LEDGER")" = 2 ]'
LEDGER=$t/ledger3
ledger_reserve "$LEDGER" 8 $R1 SurvivalE2eTest#connectAndChat=1 PushE2eTest#pushWakesKilledApp=1
key_sent_local=1 sessions_state=ok journal_complete=1 journal=("send $K1 pushWakesKilledApp") starts=([connectAndChat]=1)
observe_starts >/dev/null 2>&1
check "observed: a counted method is recorded; one that sent but was not counted keeps its reservation" \
  'grep -qx "observed $R1 SurvivalE2eTest#connectAndChat 1" "$LEDGER" && ! grep -q "^observed $R1 PushE2eTest#pushWakesKilledApp" "$LEDGER" && [ "$(ledger_total "$LEDGER")" = 2 ]'
LEDGER=$HOME/.local/state/apofis-e2e/model-budget-m3 models=() key_sent_local='' sessions_state='' journal=() starts=()

# The fake ntfy as the harness starts and stops it: listening, then its port closed.
push=(connectorLifecycle) install_of=([connectorLifecycle]=$IA) push_dir=$t/fakerun push_topic=upAbC123xyz09q
mkdir -p "$push_dir/fake"
push_port=$(python3 -I -c 'import socket; s = socket.socket(); s.bind(("127.0.0.1", 0)); print(s.getsockname()[1])')
cd "$here/../.."
start_fake || true
cd - >/dev/null
check "fake: started by the harness, listening on its port" '[ -n "$fake_pid" ] && [ -n "$(ss -Hltn "sport = :$push_port")" ]'
stop_fake >"$t/stop.out" 2>&1 && rc=0 || rc=$? # in this shell: the fake is its child
o=$(cat "$t/stop.out")
check "fake: stopped, every child accounted for, its port closed" '[ "$rc" = 0 ] && has "$o" "port $push_port closed" && [ -z "$(ss -Hltn "sport = :$push_port")" ] && [ -z "$fake_pid" ]'
# The harness dying without its cleanup: the pipe it holds closes, and the fake stops by itself.
cd "$here/../.."
start_fake || true
cd - >/dev/null
# shellcheck disable=SC2034 # read by check
gone_pid=$fake_pid
exec {fake_fd}>&-
check "fake: when the pipe the harness holds closes (the harness died), the fake stops and closes its port" \
  'wait_gone 30 alive "$gone_pid" && [ -z "$(ss -Hltn "sport = :$push_port")" ] && grep -qx "stopped: child released, owner gone" "$push_dir/fake.log"'
stop_pid "$gone_pid" # only if that check failed: never leave a fake behind
fake_pid='' fake_fd=''
python3 -I -c 'import socket, time; s = socket.socket(); s.bind(("127.0.0.1", int(__import__("sys").argv[1]))); s.listen(); time.sleep(30)' "$push_port" &
squatter=$!
wait_gone 10 eval '[ -z "$(ss -Hltn "sport = :$push_port")" ]' # until it listens
bash -c 'trap "" TERM; echo stopped: child released; sleep 1' >"$push_dir/fake.log" &
fake_pid=$!
stop_fake >"$t/stop.out" 2>&1 && rc=0 || rc=$?
o=$(cat "$t/stop.out")
check "fake: a port still open after the stop fails" '[ "$rc" = 1 ] && has "$o" "did not stop cleanly"'
kill "$squatter" 2>/dev/null; wait "$squatter" 2>/dev/null || true
keep_push=''
bash -c 'echo stopped: child unaccounted; exit 1' >"$push_dir/fake.log" &
fake_pid=$!
stop_fake >"$t/stop.out" 2>&1 && rc=0 || rc=$?
o=$(cat "$t/stop.out")
check "fake: a child unaccounted for fails and keeps the evidence for inspection" '[ "$rc" = 1 ] && has "$o" "kept for inspection: $push_dir" && [ -n "$keep_push" ]'
tmp=$t/endlocal
mkdir -p "$tmp"
end_local
check "fake: the kept evidence survives the end of the run" '[ -e "$push_dir/fake.log" ]'
push=() install_of=() push_dir='' push_port='' fake_pid='' keep_push=''
rc=0
APOFIS_E2E_LIB=1 bash -c '
  source "$1/run-e2e.sh"
  set +e
  TRAP_LIMIT=2 tmp=$(mktemp -d) key=x
  install_of=([connectorLifecycle]=0a1b2c3d-0000-4000-8000-00000000000a)
  check_sessions() { while :; do sleep 0.2; done; }
  trap local_cleanup EXIT
  exit 0' _ "$here" >"$t/out-overrun" 2>&1 || rc=$?
check "cleanup over its limit with push installs: says push records may remain, naming the installs" \
  '[ "$rc" = 1 ] && has "$(cat "$t/out-overrun")" "push records may remain under the installs 0a1b2c3d-0000-4000-8000-00000000000a"'

remove_tree "$t"
echo "$passed passed, $failed failed"
[ "$failed" = 0 ]
