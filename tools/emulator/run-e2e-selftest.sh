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
sessions_case() { # <mode> <passed scenarios> <journal lines...>: a verified results file listing them, and the journal
  local sc
  mode=$1 key_sent_local=1 sessions_state=ok published=1 run_id=sess
  mkdir -p "$E2E_OUT/sess"
  { echo SmokeE2eTest.connectViaLoopback PASS; for sc in $2; do echo "SurvivalE2eTest.$sc PASS"; done; } >"$E2E_OUT/sess/results.txt"
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
rm -f "$E2E_OUT/sess/results.txt"
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
# 12b. A cleanup with nothing to do stops its watchdog at once (before or after it has its own process group): exit 0
# well before the limit, and no sleep of it left. Three times, as the race is with the watchdog's start.
wd_ok=1
for i in 1 2 3; do
  lim=4.$i$$ s=$SECONDS rc=0
  APOFIS_E2E_LIB=1 bash -c '
    source "$1/run-e2e.sh"
    set +e
    TRAP_LIMIT=$2 tmp=$(mktemp -d)
    trap local_cleanup EXIT
    exit 0' _ "$here" "$lim" >"$t/out12b" 2>&1 || rc=$?
  { [ "$rc" = 0 ] && [ $((SECONDS - s)) -lt 3 ] && ! pgrep -fx "sleep $lim" >/dev/null; } || wd_ok=''
  pkill -fx "sleep $lim" || true
done
check "quick cleanup: the watchdog is stopped, exit 0 at once, no sleep left (3 runs)" "[ -n '$wd_ok' ]"

# 13. Every instrumentation test class writes its own result rows (Evidence), which the cleanup requires.
for f in "$here"/../../e2e/src/main/kotlin/xyz/rycerz/apofis/e2e/*.kt; do
  grep -q '^ *@Test' "$f" || continue
  check "$(basename "$f" .kt) writes result rows (Evidence rule)" 'grep -q "@get:Rule val evidence = Evidence(" "$f"'
done

remove_tree "$t"
echo "$passed passed, $failed failed"
[ "$failed" = 0 ]
