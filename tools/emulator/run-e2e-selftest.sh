#!/usr/bin/env bash
# shellcheck disable=SC2016 # each check's condition is shell code, evaluated by check
# Tests run-e2e.sh's safeguards on this host alone: no SSH, no emulator, no server and a fake key.
#
#   tools/emulator/run-e2e-selftest.sh
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
# shellcheck source=SCRIPTDIR/run-e2e.sh
APOFIS_E2E_LIB=1 source "$here/run-e2e.sh"
set +eu

t=$(mktemp -d "${TMPDIR:-/tmp}/apofis-e2e-selftest.XXXXXX")
tmp=$t/tmp
mkdir "$tmp"
key=SELFTESTfakeKEY0123456789abcdefSELFTESTfakeKEY0123456789abcdef # a fake key, never the real one
nonce=0a1b2c3d
passed=0 failed=0
# check <name> <condition as shell code>
check() { if eval "$2"; then passed=$((passed + 1)); echo "ok    $1"; else failed=$((failed + 1)); echo "FAIL  $1"; fi; }
has() { grep -qF -- "$2" <<<"$1"; }
lacks() { ! grep -qF -- "$2" <<<"$1"; }
only_fixed() { [ "$(grep -c . <<<"$1")" = "$2" ]; } # the output has exactly this many lines

# A staged export: three files and their reported checksums.
make_export() {
  mkdir -p "$1"
  printf 'INSTRUMENTATION_CODE: -1\n' >"$1/instrument.txt"
  printf 'SurvivalE2eTest.connectAndChat PASS\n' >"$1/results.txt"
  printf 'I ApofisE2e: ok\n' >"$1/logcat.txt"
  export_files=()
  for f in instrument.txt results.txt logcat.txt; do export_files+=("$(sha256sum "$1/$f" | cut -d' ' -f1) $f"); done
}

# 1. A change after the scan (here: after the checksums were reported) leaves nothing in build/e2e.
make_export "$t/src1"
printf 'planted change\n' >>"$t/src1/logcat.txt"
o=$(import_export "$t/src1/" "$t/build/e2e/run1" 2>&1); rc=$?
check "planted change after the scan: copy rejected" '[ "$rc" != 0 ]'
check "planted change after the scan: nothing left in build/e2e" '[ ! -e "$t/build/e2e/run1" ]'
check "planted change after the scan: fixed message only" 'has "$o" "FAILED: stage checksum: the copied files were deleted"'
check "planted change after the scan: no file content shown" 'lacks "$o" "planted"'

# 2. An unchanged export is copied and verified.
make_export "$t/src2"
o=$(import_export "$t/src2/" "$t/build/e2e/run2" 2>&1); rc=$?
check "clean export: copied" '[ "$rc" = 0 ] && cmp -s "$t/src2/logcat.txt" "$t/build/e2e/run2/logcat.txt"'

# 3. The key in a copied file (checksums matching) is found by the local scan and everything copied is deleted.
make_export "$t/src3"
printf 'leak %s\n' "$key" >"$t/src3/logcat.txt"
export_files[2]="$(sha256sum "$t/src3/logcat.txt" | cut -d' ' -f1) logcat.txt"
o=$(import_export "$t/src3/" "$t/build/e2e/run3" 2>&1); rc=$?
check "key in the copy: deleted, nothing left" '[ "$rc" != 0 ] && [ ! -e "$t/build/e2e/run3" ]'
check "key in the copy: fixed message, no key, no name" 'has "$o" "stage local scan (key found)" && lacks "$o" "$key" && lacks "$o" "logcat.txt"'

# 4. A scan error on the emulator host prints only the fixed message and deletes the staged files.
mkdir -p "$t/exp4"
printf 'secret-looking name\n' >"$t/exp4/instrument.txt"
chmod 000 "$t/exp4/instrument.txt"
exec {proto}< <(key_patterns)
o=$(scan_seal_report "$t/exp4" 2>&1); rc=$?
exec {proto}<&-
check "scan error: fails with the scan exit" '[ "$rc" = "$SCANFAIL_EXIT" ]'
check "scan error: only the request and the fixed message" 'only_fixed "$o" 2'
check "scan error: fixed message" 'has "$o" "FAILED: stage scan: the scan could not complete; all staged files deleted"'
check "scan error: no file name or diagnostics" 'lacks "$o" "instrument.txt" && lacks "$o" "Permission"'
check "scan error: staged files deleted" '[ ! -e "$t/exp4" ]'
# The same with the key in a staged file, and with no patterns (the build host gone).
mkdir -p "$t/exp5"
printf 'x %s\n' "$key" >"$t/exp5/logcat.txt"
exec {proto}< <(key_patterns)
o=$(scan_seal_report "$t/exp5" 2>&1); rc=$?
exec {proto}<&-
check "key staged: leak exit, fixed message, nothing kept" '[ "$rc" = "$LEAK_EXIT" ] && only_fixed "$o" 2 && lacks "$o" logcat && lacks "$o" "$key" && [ ! -e "$t/exp5" ]'
mkdir -p "$t/exp6"
printf 'x\n' >"$t/exp6/logcat.txt"
exec {proto}</dev/null
o=$(scan_seal_report "$t/exp6" 2>&1); rc=$?
exec {proto}<&-
check "no patterns (SSH gone): staged files deleted" '[ "$rc" = "$SCANFAIL_EXIT" ] && [ ! -e "$t/exp6" ] && has "$o" "stage scan: no scan patterns"'
# A clean export is sealed: read-only, one FILE line per file, then the count.
make_export "$t/exp7"
exec {proto}< <(key_patterns)
o=$(scan_seal_report "$t/exp7" 2>/dev/null); rc=$?
exec {proto}<&-
check "clean staging: sealed read-only and reported" '[ "$rc" = 0 ] && [ ! -w "$t/exp7" ] && [ ! -w "$t/exp7/logcat.txt" ] && has "$o" "@@EXPORT-END 3@@" && [ "$(grep -c '\''^@@FILE [0-9a-f]\{64\} '\'' <<<"$o")" = 3 ]'

# 5. The session journal on the emulator host: only exact lines, complete file, or nothing at all is reported.
out=$t/rs
mkdir -p "$out/device"
key_sent=1
printf 'session apofis-0a1b2c3d-0000-4000-8000-000000000001 connectAndChat\nrun run_0123456789abcdef0123456789abcdef apofis-0a1b2c3d-0000-4000-8000-000000000001\n' >"$out/device/sessions.txt"
o=$(report_sessions 0 2>/dev/null)
check "journal: exact lines reported with their count" 'has "$o" "@@SESSIONS-END 2@@" && [ "$(grep -c '\''^@@J '\'' <<<"$o")" = 2 ]'
printf 'session apofis-0a1b2c3d-0000-4000-8000-000000000001 connectAndChat' >"$out/device/sessions.txt"
check "journal: a cut-off last line is unknown" 'has "$(report_sessions 0 2>/dev/null)" "@@SESSIONS-UNKNOWN@@"'
printf 'session apofis-0a1b2c3d-0000-4000-8000-000000000001 connectAndChat\nsomething else\n' >"$out/device/sessions.txt"
check "journal: a malformed line is unknown, nothing passed on" '[ "$(report_sessions 0 2>/dev/null)" = "@@SESSIONS-UNKNOWN@@" ]'
rm -f "$out/device/sessions.txt"
check "journal: a missing journal is unknown" '[ "$(report_sessions 0 2>/dev/null)" = "@@SESSIONS-UNKNOWN@@" ]'

# 6. Cleanup without a readable journal fails and reads nothing on the server.
# shellcheck disable=SC2329 # replaces the real server call
hermes() { echo "$*" >>"$t/server-calls"; echo 200; }
mode=smoke
for s in '' unknown bad; do
  key_sent_local=1 sessions_state=$s journal=()
  o=$(check_sessions 2>&1); rc=$?
  check "journal state '${s:-missing}': cleanup fails with the fixed message" '[ "$rc" = 1 ] && has "$o" "sessions this run created may remain"'
done
key_sent_local=1 sessions_state=ok journal=("run run_0123456789abcdef0123456789abcdef apofis-0a1b2c3d-0000-4000-8000-000000000009")
o=$(check_sessions 2>&1); rc=$?
check "a journaled run of a session not proved this run's: not read, fails" '[ "$rc" = 1 ]'
check "no server read for anything the journal does not prove" '[ ! -e "$t/server-calls" ]'
key_sent_local='' sessions_state=''
check "key never sent: no sessions possible, no read" 'check_sessions'
unset -f hermes

# 7. The emulator host lock directory, through the same shipping as over SSH (run locally here).
# shellcheck disable=SC2329 # runs the shipped functions locally instead of over SSH
rsh() { "$@"; }
st=$t/state
check "lock: free lock taken" '[ "$(rsh_fn lock_take "$st" run-a 100 "$E2E_PROC_RE")" = TAKEN ]'
r=$(rsh_fn lock_take "$st" run-b 100 "$E2E_PROC_RE")
check "lock: live lock refused" '[ "${r%% *}" = BUSY ]'
check "lock: another run cannot release it" '[ "$(rsh_fn lock_release "$st" run-b)" = NOT-OURS ]'
printf 'run-a %s\n' $(($(date +%s) - 200)) >"$st/lock.d/owner"
bash -c 'exec -a "selftest run-e2e.sh --in-shell dummy" bash -c "sleep 30; :"' &
dummy=$!
sleep 0.5
r=$(rsh_fn lock_take "$st" run-b 100 "$E2E_PROC_RE")
check "lock: stale lock with an e2e process alive refused" '[ "${r%% *}" = BUSY-RUNNING ]'
kill "$dummy"
wait "$dummy" 2>/dev/null
r=$(rsh_fn lock_take "$st" run-b 100 "$E2E_PROC_RE")
check "lock: stale lock without any e2e process taken over" '[ "$r" = "TAKEN-STALE run-a" ] && lock_owned "$st" run-b'
check "lock: owner releases it, the directory is gone" '[ "$(rsh_fn lock_release "$st" run-b)" = RELEASED ] && [ ! -e "$st/lock.d" ]'
unset -f rsh

# 8. A hanging stop command is killed by its timeout; a process ignoring TERM is killed by stop_pid.
s=$SECONDS
bounded 2 bash -c 'trap "" TERM; sleep 60' 2>/dev/null; rc=$?
check "bounded: a command ignoring TERM is killed (exit 137) within 9 s" '[ "$rc" = 137 ] && [ $((SECONDS - s)) -le 9 ]'
bash -c 'trap "" TERM; sleep 60' &
p=$!
s=$SECONDS
stop_pid "$p"
check "stop_pid: a process ignoring TERM is gone within 17 s" '! alive "$p" && [ $((SECONDS - s)) -le 17 ]'
{ wait "$p"; } 2>/dev/null

# 9. The postcheck probe reports every item and ends with DONE, with no ledger at all.
o=$(E2E_PROC_RE_ARG=$E2E_PROC_RE probe "$t/nostate" run-x "$PORT_RANGES")
check "probe: unconditional items and DONE" 'has "$o" "processes " && has "$o" "avd absent" && has "$o" "rundir absent" && has "$o" "listeners " && has "$o" DONE'

remove_tree "$t"
echo "$passed passed, $failed failed"
[ "$failed" = 0 ]
