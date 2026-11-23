#!/bin/sh
# Usage: release-guards-test.sh <apksigner> <debug apk> [published apk]
# Self-tests of check-release-tag.sh, check-signing-cert.sh and tag-release.sh with
# temporary git repositories, throwaway SSH keys and keystores made here (no key is
# committed) and a stub gh first on PATH. Needs git, ssh-keygen, keytool, jq and bash.
# With a published APK it also checks that APK against release/signing-cert.sha256.
set -eu
[ $# -ge 2 ] || { echo "usage: release-guards-test.sh <apksigner> <debug apk> [published apk]" >&2; exit 2; }
here="$(cd "$(dirname "$0")" && pwd)"
apksigner=$1 debug_apk=$2 published=${3:-}
tmp="$(mktemp -d)"
trap 'find "$tmp" -delete' EXIT
mkdir "$tmp/bin"
# Nothing of the host's git or ssh setup takes part: own global config, own home, no agent.
export FAKE="$tmp" HOME="$tmp" GIT_CONFIG_GLOBAL="$tmp/gitconfig" GIT_CONFIG_NOSYSTEM=1 PATH="$tmp/bin:$PATH"
unset SSH_AUTH_SOCK

ssh-keygen -q -t ed25519 -N '' -C good -f "$tmp/good"
ssh-keygen -q -t ed25519 -N '' -C wrong -f "$tmp/wrong"
good_fpr=$(ssh-keygen -lf "$tmp/good.pub" | awk '{print $2}')
wrong_fpr=$(ssh-keygen -lf "$tmp/wrong.pub" | awk '{print $2}')
printf 't@example.com namespaces="git" %s\n' "$(cut -d' ' -f1,2 "$tmp/good.pub")" > "$tmp/allowed"
cat > "$GIT_CONFIG_GLOBAL" <<END
[user]
	name = T
	email = t@example.com
	signingkey = $tmp/good
[gpg]
	format = ssh
[init]
	defaultBranch = main
[commit]
	gpgsign = false
[advice]
	detachedHead = false
END

# The stub gh answers only `gh run list ... --jq <expr>`, from runs.json, or fails when gh_fail exists.
cat > "$tmp/bin/gh" <<'END'
#!/bin/sh
[ ! -f "$FAKE/gh_fail" ] || { echo "stub gh: HTTP 502" >&2; exit 1; }
[ "$1 $2" = "run list" ] || { echo "stub gh: unhandled $*" >&2; exit 1; }
expr=; while [ $# -gt 0 ]; do [ "$1" != --jq ] || expr=$2; shift; done
exec jq -r "$expr" "$FAKE/runs.json"
END
chmod +x "$tmp/bin/gh"

fail=0
check() {
    if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1 (got '$2', want '$3')"; sed 's/^/     | /' "$tmp/out"; fail=1; fi
}
# st <command...>: 0 or 1 for the command's success, its output in out.
st() { if "$@" > "$tmp/out" 2>&1; then echo 0; else echo 1; fi; }

# fresh: a new origin (bare) and clone w: main is c1 then c2 (= main_sha), branch pr holds side.
n=0
fresh() {
    n=$((n + 1)); o="$tmp/o$n.git"; w="$tmp/w$n"
    git init -q --bare "$o"
    git init -q "$w"
    mkdir "$w/release"; cp "$tmp/allowed" "$w/release/allowed-signers"
    git -C "$w" add release; git -C "$w" commit -q -m c1; c1=$(git -C "$w" rev-parse HEAD)
    git -C "$w" commit -q --allow-empty -m c2; main_sha=$(git -C "$w" rev-parse HEAD)
    git -C "$w" checkout -q -b pr; git -C "$w" commit -q --allow-empty -m side; side=$(git -C "$w" rev-parse HEAD)
    git -C "$w" checkout -q main
    git -C "$w" remote add origin "$o"
    git -C "$w" push -q origin main pr 2> /dev/null
    git -C "$w" fetch -q origin
    printf 'Candidate passed: %s\n' "$main_sha" > "$tmp/ledger"
    runs "$(run "$main_sha" main push success)"
    rm -f "$tmp/gh_fail"
}
run() { printf '{"headSha":"%s","headBranch":"%s","event":"%s","status":"completed","conclusion":"%s"}' "$@"; }
runs() { printf '[%s]\n' "$1" > "$tmp/runs.json"; }
origin_tag() { git -C "$o" rev-parse -q --verify refs/tags/v0.1.0 || echo none; }

### check-release-tag.sh
fresh
guard() { (cd "$w" && sh "$here/check-release-tag.sh" "$@"); }
git -C "$w" tag -s v0.1.0 "$side" -m t
git -C "$w" tag v0.2.0 "$main_sha"
git -C "$w" -c user.signingkey="$tmp/wrong" tag -s v0.3.0 "$main_sha" -m t
git -C "$w" tag -s v0.4.0 "$c1" -m t
git -C "$w" tag -s v0.5.0 "$main_sha" -m t
git -C "$w" tag -s v0.1 "$main_sha" -m t
git -C "$w" tag -s x0.1.0 "$main_sha" -m t
check "guard refuses v0.1" "$(st guard v0.1 "$main_sha")" 1
check "guard refuses x0.1.0" "$(st guard x0.1.0 "$main_sha")" 1
check "guard refuses a commit not on origin/main" "$(st guard v0.1.0 "$side")" 1
check "guard refuses a lightweight tag" "$(st guard v0.2.0 "$main_sha")" 1
check "guard refuses a tag signed by another key" "$(st guard v0.3.0 "$main_sha")" 1
check "guard refuses a tag on another commit than the run's" "$(st guard v0.4.0 "$main_sha")" 1
check "guard accepts a signed vX.Y.Z on a main commit" "$(st guard v0.5.0 "$main_sha")" 0

### check-signing-cert.sh
key() { keytool -genkeypair -keystore "$tmp/$1.jks" -storepass password -alias "$1" -keyalg RSA -keysize 2048 -validity 2 -dname "CN=$1" > /dev/null 2>&1; }
digest() { keytool -exportcert -keystore "$tmp/$1.jks" -storepass password -alias "$1" | sha256sum | cut -c1-64; }
key k1; key k2
cp "$debug_apk" "$tmp/one.apk"
"$apksigner" sign --ks "$tmp/k1.jks" --ks-pass pass:password "$tmp/one.apk"
cp "$debug_apk" "$tmp/two.apk"
"$apksigner" sign --v3-signing-enabled false --v4-signing-enabled false \
    --ks "$tmp/k1.jks" --ks-pass pass:password --next-signer --ks "$tmp/k2.jks" --ks-pass pass:password "$tmp/two.apk"
d=$(digest k1)
printf '%s\n' "$d" > "$tmp/k1.sha256"
case $d in 0*) flip=1 ;; *) flip=0 ;; esac
printf '%s%s\n' "$flip" "${d#?}" > "$tmp/flipped.sha256"
printf '%s\n%s\n' "$d" "$d" > "$tmp/twice.sha256"
cert() { sh "$here/check-signing-cert.sh" "$apksigner" "$@"; }
check "cert check passes the recorded signer" "$(st cert "$tmp/one.apk" "$tmp/k1.sha256")" 0
check "cert check refuses one hex digit changed" "$(st cert "$tmp/one.apk" "$tmp/flipped.sha256")" 1
check "cert check refuses two signers (first recorded)" "$(st cert "$tmp/two.apk" "$tmp/k1.sha256")" 1
check "cert check refuses a digest file of two lines" "$(st cert "$tmp/one.apk" "$tmp/twice.sha256")" 1
check "cert check refuses a file apksigner cannot verify" "$(st cert "$tmp/k1.sha256" "$tmp/k1.sha256")" 1
check "cert check refuses the debug APK against the release certificate" "$(st cert "$debug_apk" "$here/../release/signing-cert.sha256")" 1
if [ -n "$published" ]; then
    check "cert check passes the published APK" "$(st cert "$published" "$here/../release/signing-cert.sha256")" 0
fi

### tag-release.sh, read from stdin as RELEASING.md runs it
tagrel() { (cd "$w" && bash -s -- "$@" < "$here/tag-release.sh"); }

fresh; echo "no candidate yet" > "$tmp/ledger"
check "tag-release refuses a ledger without a Candidate passed line" "$(st tagrel 0.1.0 "$main_sha" "$good_fpr" "$tmp/ledger")" 1
check "  and pushes nothing" "$(origin_tag)" none

fresh; printf 'Candidate passed: %s\nCandidate passed: %s\n' "$main_sha" "$side" > "$tmp/ledger"
check "tag-release refuses when the last Candidate passed line names another SHA" "$(st tagrel 0.1.0 "$main_sha" "$good_fpr" "$tmp/ledger")" 1
check "  and pushes nothing" "$(origin_tag)" none

fresh; printf 'Candidate passed: %s\n' "$c1" > "$tmp/ledger"; runs "$(run "$c1" main push success)"
check "tag-release refuses when origin/main is not the SHA" "$(st tagrel 0.1.0 "$c1" "$good_fpr" "$tmp/ledger")" 1
check "  and pushes nothing" "$(origin_tag)" none

fresh
runs "$(run "$main_sha" pr pull_request success),$(run "$main_sha" v0.1.0 push success),$(run "$main_sha" main push failure),$(run "$c1" main push success)"
check "tag-release refuses without a green push run on main for the SHA" "$(st tagrel 0.1.0 "$main_sha" "$good_fpr" "$tmp/ledger")" 1
check "  and pushes nothing" "$(origin_tag)" none

fresh; touch "$tmp/gh_fail"
check "tag-release refuses when gh fails" "$(st tagrel 0.1.0 "$main_sha" "$good_fpr" "$tmp/ledger")" 1
check "  and pushes nothing" "$(origin_tag)" none

fresh; git -C "$w" tag -s v0.1.0 "$c1" -m t
check "tag-release refuses an existing tag on another commit" "$(st tagrel 0.1.0 "$main_sha" "$good_fpr" "$tmp/ledger")" 1
check "  and pushes nothing" "$(origin_tag)" none
check "  and leaves the tag where it was" "$(git -C "$w" rev-parse 'v0.1.0^{commit}')" "$c1"

fresh; git -C "$w" tag v0.1.0 "$main_sha"
check "tag-release refuses an existing lightweight tag" "$(st tagrel 0.1.0 "$main_sha" "$good_fpr" "$tmp/ledger")" 1
check "  and pushes nothing" "$(origin_tag)" none

fresh; git -C "$w" config user.signingkey "$tmp/wrong"
check "tag-release refuses a signature by a key not allowed" "$(st tagrel 0.1.0 "$main_sha" "$wrong_fpr" "$tmp/ledger")" 1
check "  and pushes nothing" "$(origin_tag)" none

fresh
check "tag-release refuses a signature whose fingerprint is not the expected one" "$(st tagrel 0.1.0 "$main_sha" "$wrong_fpr" "$tmp/ledger")" 1
check "  and pushes nothing" "$(origin_tag)" none

fresh; git -C "$w" tag -s v0.1.0 "$main_sha" -m t; before=$(git -C "$w" rev-parse v0.1.0)
check "tag-release accepts an existing tag at the SHA with the right signer" "$(st tagrel 0.1.0 "$main_sha" "$good_fpr" "$tmp/ledger")" 0
check "  and pushes that tag, not a new one" "$(origin_tag)" "$before"

fresh
check "tag-release creates, verifies and pushes a new tag" "$(st tagrel 0.1.0 "$main_sha" "$good_fpr" "$tmp/ledger")" 0
check "  annotated" "$(git -C "$o" cat-file -t refs/tags/v0.1.0)" tag
check "  on the SHA" "$(git -C "$o" rev-parse 'refs/tags/v0.1.0^{commit}')" "$main_sha"
pushed=$(origin_tag)
check "tag-release run again stops without a change" "$(st tagrel 0.1.0 "$main_sha" "$good_fpr" "$tmp/ledger")" 0
check "  origin keeps the same tag" "$(origin_tag)" "$pushed"

exit "$fail"
