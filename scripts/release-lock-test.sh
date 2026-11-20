#!/bin/sh
# Self-test for release-lock.sh with a fake gh API and a virtual clock: date reads
# it, sleep advances it by 60 s and can release the holder's lock at a set time.
set -eu
lock="$(cd "$(dirname "$0")" && pwd)/release-lock.sh"
tmp="$(mktemp -d)"
trap 'rm -f "$tmp"/bin/* "$tmp"/obj/*; rmdir "$tmp"/bin "$tmp"/obj; rm -f "$tmp"/*; rmdir "$tmp"' EXIT
mkdir "$tmp/bin" "$tmp/obj"
REAL_DATE="$(command -v date)"
export FAKE="$tmp" REAL_DATE PATH="$tmp/bin:$PATH"
export GITHUB_REPOSITORY=o/r GITHUB_SHA=base GITHUB_RUN_ID=1 LOCK_POLL_SECONDS=60
echo 0 > "$tmp/clock"; echo 0 > "$tmp/n"

cat > "$tmp/bin/date" <<'END'
#!/bin/sh
for a; do [ "$a" = -d ] && exec "$REAL_DATE" "$@"; done
exec "$REAL_DATE" -d "@$(cat "$FAKE/clock")" "$@"
END
cat > "$tmp/bin/sleep" <<'END'
#!/bin/sh
now=$(( $(cat "$FAKE/clock") + 60 )); echo "$now" > "$FAKE/clock"
if [ -f "$FAKE/release_at" ] && [ "$now" -ge "$(cat "$FAKE/release_at")" ]; then
    rm -f "$FAKE/ref" "$FAKE/release_at"
fi
END
# Objects: obj/<sha> holds the author date, then the parent. ref holds the lock sha.
cat > "$tmp/bin/gh" <<'END'
#!/bin/sh
shift; m=GET; path=; jq=; fields=
while [ $# -gt 0 ]; do case $1 in
    -X) m=$2; shift 2 ;;
    -f|-F) [ "$m" = GET ] && m=POST; fields="$fields
$2"; shift 2 ;;
    --jq) jq=$2; shift 2 ;;
    *) path=$1; shift ;;
esac; done
f() { printf '%s\n' "$fields" | awk -v k="$1=" 'index($0, k) == 1 { print substr($0, length(k) + 1) }'; }
mkobj() { n=$(( $(cat "$FAKE/n") + 1 )); echo "$n" > "$FAKE/n"; printf '%s\n%s\n' "$1" "$2" > "$FAKE/obj/c$n"; echo "c$n"; }
ref="$FAKE/ref"
case "$m $path" in
"POST repos/o/r/git/commits") mkobj "$(f 'author[date]')" "$(f 'parents[]')" ;;
"GET repos/o/r/git/commits/"*)
    sha=${path##*/}
    case $jq in
    .tree.sha) echo tree ;;
    *) [ -f "$FAKE/obj/$sha" ] && sed -n 1p "$FAKE/obj/$sha" ;;
    esac ;;
"GET repos/o/r/git/ref/locks/publish") [ -f "$ref" ] && cat "$ref" ;;
"POST repos/o/r/git/refs") [ ! -f "$ref" ] && f sha > "$ref" ;;
"PATCH repos/o/r/git/refs/locks/publish")
    [ -f "$ref" ] || exit 1
    if [ -f "$FAKE/race" ]; then # another breaker wins just before this update
        rm "$FAKE/race"; mkobj "$("$REAL_DATE" -u -d "@$(cat "$FAKE/clock")" +%Y-%m-%dT%H:%M:%SZ)" x > "$ref"; exit 1
    fi
    new=$(f sha)
    [ "$(sed -n 2p "$FAKE/obj/$new")" = "$(cat "$ref")" ] && echo "$new" > "$ref" ;;
"DELETE repos/o/r/git/refs/locks/publish") [ -f "$ref" ] && rm "$ref" ;;
*) echo "fake gh: unhandled $m $path" >&2; exit 1 ;;
esac
END
chmod +x "$tmp/bin/"*

fail=0
check() { if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1 (got '$2', want '$3')"; fail=1; fi; }
# run <name> acquire|release [env...]: runs as run <name>; its LOCK_SHA is the last in env.<name>.
run() {
    n=$1 a=$2; shift 2
    : >> "$tmp/env.$n"
    if env GITHUB_ENV="$tmp/env.$n" LOCK_SHA="$(sha "$n")" "$@" sh "$lock" "$a" > "$tmp/out" 2>&1; then echo 0; else echo 1; fi
}
sha() { sed -n 's/^LOCK_SHA=//p' "$tmp/env.$1" 2>/dev/null | tail -1; }
ref() { cat "$tmp/ref" 2>/dev/null || echo none; }
at() { echo "$1" > "$tmp/clock"; }

at 0
check "free lock acquired" "$(run a acquire)" 0
check "ref is ours" "$(ref)" "$(sha a)"
check "release" "$(run a release)" 0
check "release deleted the ref" "$(ref)" none

# Queue for 12 min, then publish for 9 min: 21 min after queueing, under 20 after acquiring.
at 0
run h acquire > /dev/null
echo 720 > "$tmp/release_at"
check "waiter acquires after the holder releases" "$(run w acquire)" 0
check "waiter holds the ref" "$(ref)" "$(sha w)"
check "waiter queued 12 min" "$(cat "$tmp/clock")" 720
at 1260
check "live lock after a long queue is not broken" "$(run x acquire LOCK_WAIT_SECONDS=120)" 1
check "waiter still holds the ref" "$(ref)" "$(sha w)"
check "waiter releases its lock" "$(run w release)" 0
check "ref gone" "$(ref)" none

at 0
run s acquire > /dev/null
at 1900
check "stale lock taken over" "$(run y acquire)" 0
check "taker holds the ref" "$(ref)" "$(sha y)"
check "crashed holder's release leaves it" "$(run s release)" 0
check "taker still holds the ref" "$(ref)" "$(sha y)"
run y release > /dev/null

at 0
run s2 acquire > /dev/null
at 1900
touch "$tmp/race"
check "losing a takeover race does not acquire" "$(run z acquire LOCK_WAIT_SECONDS=120)" 1
check "race winner keeps the ref" "$(sed -n 2p "$tmp/obj/$(ref)")" x

exit "$fail"
