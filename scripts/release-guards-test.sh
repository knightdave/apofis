#!/bin/sh
# Usage: release-guards-test.sh <apksigner> <debug apk> [published apk]
# Self-tests of check-release-tag.sh, check-signing-cert.sh and tag-release.sh with
# temporary git repositories, throwaway SSH keys and keystores made here (no key is
# committed), a stub gh and a stub apksigner. Needs git, ssh-keygen, keytool, jq and bash.
# With a published APK it also checks that APK against release/signing-cert.sha256.
set -eu
[ $# -ge 2 ] || { echo "usage: release-guards-test.sh <apksigner> <debug apk> [published apk]" >&2; exit 2; }
here="$(cd "$(dirname "$0")" && pwd)"
apksigner=$1 debug_apk=$2 published=${3:-}
tmp="$(mktemp -d)"
trap 'find "$tmp" -delete' EXIT
mkdir "$tmp/bin"
# Nothing of the host's git or ssh setup takes part: no inherited repository or config
# variables, own global config, own home, no agent.
unset GIT_DIR GIT_WORK_TREE GIT_INDEX_FILE GIT_OBJECT_DIRECTORY GIT_COMMON_DIR GIT_CONFIG_PARAMETERS GIT_CONFIG_COUNT GIT_NAMESPACE SSH_AUTH_SOCK
export FAKE="$tmp" HOME="$tmp" GIT_CONFIG_GLOBAL="$tmp/gitconfig" GIT_CONFIG_NOSYSTEM=1 PATH="$tmp/bin:$PATH"

ssh-keygen -q -t ed25519 -N '' -C good -f "$tmp/good"
ssh-keygen -q -t ed25519 -N '' -C wrong -f "$tmp/wrong"
good_fpr=$(ssh-keygen -lf "$tmp/good.pub" | awk '{print $2}')
wrong_fpr=$(ssh-keygen -lf "$tmp/wrong.pub" | awk '{print $2}')
signer() { printf 't@example.com namespaces="git" %s\n' "$(cut -d' ' -f1,2 "$tmp/$1.pub")"; }
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

# The stub gh answers only `gh run list` with the repository, commit, workflow, branch and
# event tag-release.sh must pass (else it fails), from runs.json; it fails when gh_fail exists.
cat > "$tmp/bin/gh" <<'END'
#!/bin/sh
[ ! -f "$FAKE/gh_fail" ] || { echo "stub gh: HTTP 502" >&2; exit 1; }
[ "$1 $2" = "run list" ] || { echo "stub gh: unhandled $*" >&2; exit 1; }
shift 2; seen=" "; expr=
while [ $# -gt 0 ]; do [ "$1" != --jq ] || expr=$2; seen="$seen$1=$2 "; shift 2; done
for a in --repo="$(cat "$FAKE/repo")" --commit="$(cat "$FAKE/commit")" --workflow=ci.yml --branch=main --event=push; do
    case $seen in *" $a "*) ;; *) echo "stub gh: missing $a" >&2; exit 1 ;; esac
done
exec jq -r "$expr" "$FAKE/runs.json"
END
chmod +x "$tmp/bin/gh"

fail=0
check() {
    if [ "$2" = "$3" ]; then echo "ok   $1"; else echo "FAIL $1 (got '$2', want '$3')"; sed 's/^/     | /' "$tmp/out"; fail=1; fi
}
# st <command...>: 0 or 1 for the command's success, its output in out.
st() { if "$@" > "$tmp/out" 2>&1; then echo 0; else echo 1; fi; }
# said <text>: 1 when the last command's output contains the text (the reason it stopped).
said() { if grep -qF -- "$1" "$tmp/out"; then echo 1; else echo 0; fi; }

# fresh: a new origin (bare) and clone w: main is c1 then c2 (= main_sha), branch pr holds side.
n=0
fresh() {
    n=$((n + 1)); o="$tmp/o$n.git"; w="$tmp/w$n"
    git init -q --bare "$o"
    git init -q "$w"
    mkdir "$w/release"; signer good > "$w/release/allowed-signers"
    git -C "$w" add release; git -C "$w" commit -q -m c1; c1=$(git -C "$w" rev-parse HEAD)
    git -C "$w" commit -q --allow-empty -m c2; main_sha=$(git -C "$w" rev-parse HEAD)
    git -C "$w" checkout -q -b pr; git -C "$w" commit -q --allow-empty -m side; side=$(git -C "$w" rev-parse HEAD)
    git -C "$w" checkout -q main
    git -C "$w" remote add origin "$o"
    git -C "$w" push -q origin main pr 2> /dev/null
    git -C "$w" fetch -q origin
    printf 'Candidate passed: %s\n' "$main_sha" > "$tmp/ledger"
    printf '%s\n' "$o" > "$tmp/repo"; printf '%s\n' "$main_sha" > "$tmp/commit"
    runs "$(run "$main_sha" main push success)"
    rm -f "$tmp/gh_fail"
}
run() { printf '{"headSha":"%s","headBranch":"%s","event":"%s","status":"completed","conclusion":"%s"}' "$@"; }
runs() { printf '[%s]\n' "$1" > "$tmp/runs.json"; }
origin_tag() { git -C "$o" rev-parse -q --verify "refs/tags/${1:-v0.1.0}" || echo none; }
local_tag() { git -C "$w" rev-parse -q --verify "refs/tags/${1:-v0.1.0}" || echo none; }

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
git -C "$w" update-ref refs/tags/v0.1.1 "$(git -C "$w" rev-parse v0.5.0)"
git -C "$w" tag -s v0.6.0 "$c1" -m t
check "guard refuses v0.1" "$(st guard v0.1 "$main_sha")" 1
check "guard refuses x0.1.0" "$(st guard x0.1.0 "$main_sha")" 1
check "guard refuses a commit not on origin/main" "$(st guard v0.1.0 "$side")" 1
check "guard refuses a lightweight tag" "$(st guard v0.2.0 "$main_sha")" 1
check "guard refuses a tag signed by another key" "$(st guard v0.3.0 "$main_sha")" 1
check "guard refuses a tag on another commit than the run's" "$(st guard v0.4.0 "$main_sha")" 1
check "guard refuses v0.1.1 pointing at the signed object of v0.5.0" "$(st guard v0.1.1 "$main_sha")" 1
check "guard accepts a signed vX.Y.Z on a main commit" "$(st guard v0.5.0 "$main_sha")" 0
check "guard accepts a signed vX.Y.Z on an older ancestor of origin/main" "$(st guard v0.6.0 "$c1")" 0
signer wrong >> "$w/release/allowed-signers" # uncommitted: only the working tree trusts it
check "guard refuses a key only in the working tree's allowed-signers" "$(st guard v0.3.0 "$main_sha")" 1

### check-signing-cert.sh
key() { keytool -genkeypair -keystore "$tmp/$1.jks" -storepass password -alias "$1" -keyalg RSA -keysize 2048 -validity 2 -dname "CN=$1" > /dev/null 2>&1; }
digest() { keytool -exportcert -keystore "$tmp/$1.jks" -storepass password -alias "$1" | sha256sum | cut -c1-64; }
key k1; key k2
cp "$debug_apk" "$tmp/one.apk"
"$apksigner" sign --ks "$tmp/k1.jks" --ks-pass pass:password "$tmp/one.apk"
cp "$debug_apk" "$tmp/two.apk"
"$apksigner" sign --v3-signing-enabled false --v4-signing-enabled false \
    --ks "$tmp/k1.jks" --ks-pass pass:password --next-signer --ks "$tmp/k2.jks" --ks-pass pass:password "$tmp/two.apk"
d=$(digest k1) d2=$(digest k2)
printf '%s\n' "$d" > "$tmp/k1.sha256"
case $d in 0*) flip=1 ;; *) flip=0 ;; esac
printf '%s%s\n' "$flip" "${d#?}" > "$tmp/flipped.sha256"
# A verifying APK whose compatibility record is the pinned signer and whose hybrid record is not.
cat > "$tmp/bin/hybrid-apksigner" <<END
#!/bin/sh
printf 'Verifies\nNumber of signers: 1\nV3.0 Signer: certificate SHA-256 digest: $d\nV3.2 Hybrid PQC Signer: certificate SHA-256 digest: $d2\n'
END
chmod +x "$tmp/bin/hybrid-apksigner"
cert() { sh "$here/check-signing-cert.sh" "$apksigner" "$@"; }
check "cert check passes the recorded signer" "$(st cert "$tmp/one.apk" "$tmp/k1.sha256")" 0
check "cert check refuses one hex digit changed" "$(st cert "$tmp/one.apk" "$tmp/flipped.sha256")" 1
check "  at the digest compare" "$(said 'is signed by')" 1
check "cert check refuses two signers (first recorded)" "$(st cert "$tmp/two.apk" "$tmp/k1.sha256")" 1
check "cert check refuses another certificate on a hybrid record" "$(st sh "$here/check-signing-cert.sh" hybrid-apksigner x.apk "$tmp/k1.sha256")" 1
check "  at the digest compare" "$(said 'is signed by')" 1
check "cert check refuses a file apksigner cannot verify" "$(st cert "$tmp/k1.sha256" "$tmp/k1.sha256")" 1
check "cert check refuses the debug APK against the release certificate" "$(st cert "$debug_apk" "$here/../release/signing-cert.sha256")" 1
check "  at the digest compare" "$(said 'is signed by')" 1
for pin in crlf noeol twice nul; do
    case $pin in
    crlf) printf '%s\r\n' "$d" ;;
    noeol) printf '%s' "$d" ;;
    twice) printf '%s\n%s\n' "$d" "$d" ;;
    nul) printf '%s\000\n' "$d" ;;
    esac > "$tmp/$pin.sha256"
    check "cert check refuses a pin file with $pin" "$(st cert "$tmp/one.apk" "$tmp/$pin.sha256")" 1
    check "  at the pin check" "$(said 'must be exactly one line')" 1
done
if [ -n "$published" ]; then
    check "cert check passes the published APK" "$(st cert "$published" "$here/../release/signing-cert.sha256")" 0
fi

### tag-release.sh, read from stdin as RELEASING.md runs it
tagrel() { (cd "$w" && bash -s -- "$@" < "$here/tag-release.sh"); }
# refused <name> [sha]: the run stopped, pushed nothing and created no local tag.
refused() {
    check "$1" "$(st tagrel 0.1.0 "${2:-$main_sha}" "$good_fpr" "$tmp/ledger")" 1
    check "  pushes nothing and creates no local tag" "$(origin_tag) $(local_tag)" "none none"
}

fresh; echo "no candidate yet" > "$tmp/ledger"
refused "tag-release refuses a ledger without a Candidate passed line"
fresh; printf 'Candidate passed: %s\nCandidate passed: %s\n' "$main_sha" "$side" > "$tmp/ledger"
refused "tag-release refuses when the last Candidate passed line names another SHA"
fresh; printf 'Candidate passed: %s \n' "$main_sha" > "$tmp/ledger"
refused "tag-release refuses a Candidate passed line with trailing space"
fresh; printf 'Candidate passed: %s\r\n' "$main_sha" > "$tmp/ledger"
refused "tag-release refuses a Candidate passed line ending in CRLF"
fresh; printf 'Candidate passed: %s\000\n' "$main_sha" > "$tmp/ledger"
refused "tag-release refuses a Candidate passed line with a NUL after the SHA"
fresh
check "tag-release refuses a missing ledger file named want=" "$(st tagrel 0.1.0 "$main_sha" "$good_fpr" "want=")" 1
check "  pushes nothing and creates no local tag" "$(origin_tag) $(local_tag)" "none none"
fresh
check "tag-release refuses a ledger that is not a regular file" "$(st tagrel 0.1.0 "$main_sha" "$good_fpr" "$tmp")" 1
check "  because it is not a regular file" "$(said "is not a regular file")" 1
check "  pushes nothing and creates no local tag" "$(origin_tag) $(local_tag)" "none none"
fresh; printf 'Candidate passed: %s\n' "$c1" > "$tmp/ledger"; runs "$(run "$c1" main push success)"; printf '%s\n' "$c1" > "$tmp/commit"
refused "tag-release refuses when main on origin is not the SHA" "$c1"
# main moved on origin while this clone's fetch refspec no longer updates its stale origin/main.
fresh; git -C "$w" commit -q --allow-empty -m c3; git -C "$w" push -q origin main; git -C "$w" update-ref refs/remotes/origin/main "$main_sha"
git -C "$w" config remote.origin.fetch +refs/heads/pr:refs/remotes/origin/pr
refused "tag-release refuses when only a stale origin/main names the SHA"
fresh
# Newest first; only the last one is the push run on main for the SHA, and it failed.
runs "$(run "$c1" main push success),$(run "$main_sha" main pull_request success),$(run "$main_sha" v0.1.0 push success),$(run "$main_sha" main push failure)"
refused "tag-release refuses without a green push run on main for the SHA"
fresh; touch "$tmp/gh_fail"
refused "tag-release refuses when gh fails"
fresh; git init -q --bare "$tmp/elsewhere$n.git"; git -C "$w" config remote.origin.pushurl "$tmp/elsewhere$n.git"
refused "tag-release refuses when origin's push URL is another repository"
check "  and that repository gains no tag" "$(git -C "$tmp/elsewhere$n.git" for-each-ref refs/tags)" ""

fresh; git -C "$w" tag -s v0.1.0 "$c1" -m t
check "tag-release refuses an existing tag on another commit" "$(st tagrel 0.1.0 "$main_sha" "$good_fpr" "$tmp/ledger")" 1
check "  and pushes nothing" "$(origin_tag)" none
check "  and leaves the tag where it was" "$(git -C "$w" rev-parse 'v0.1.0^{commit}')" "$c1"

fresh; git -C "$w" tag v0.1.0 "$main_sha"
check "tag-release refuses an existing lightweight tag" "$(st tagrel 0.1.0 "$main_sha" "$good_fpr" "$tmp/ledger")" 1
check "  and pushes nothing" "$(origin_tag)" none

fresh; git -C "$w" tag -s v0.1.0 "$main_sha" -m t; git -C "$w" update-ref refs/tags/v0.1.1 "$(local_tag)"
check "tag-release refuses v0.1.1 pointing at the signed object of v0.1.0" "$(st tagrel 0.1.1 "$main_sha" "$good_fpr" "$tmp/ledger")" 1
check "  and pushes nothing" "$(origin_tag v0.1.1)" none

fresh; signer wrong >> "$w/release/allowed-signers"; git -C "$w" config user.signingkey "$tmp/wrong"
check "tag-release refuses a key only in the working tree's allowed-signers" "$(st tagrel 0.1.0 "$main_sha" "$wrong_fpr" "$tmp/ledger")" 1
check "  and pushes nothing" "$(origin_tag)" none

fresh
check "tag-release refuses a signature whose fingerprint is not the expected one" "$(st tagrel 0.1.0 "$main_sha" "$wrong_fpr" "$tmp/ledger")" 1
check "  and pushes nothing" "$(origin_tag)" none

fresh; git -C "$w" tag -s v0.1.0 "$c1" -m t; git -C "$w" push -q origin v0.1.0; git -C "$w" tag -d v0.1.0 > /dev/null; bad=$(origin_tag)
check "tag-release refuses a wrong tag that exists only on origin" "$(st tagrel 0.1.0 "$main_sha" "$good_fpr" "$tmp/ledger")" 1
check "  and origin keeps it unchanged" "$(origin_tag)" "$bad"

fresh; git -C "$w" tag -s v0.1.0 "$main_sha" -m t; git -C "$w" push -q origin v0.1.0; git -C "$w" tag -d v0.1.0 > /dev/null; good=$(origin_tag)
check "tag-release accepts a right tag that exists only on origin" "$(st tagrel 0.1.0 "$main_sha" "$good_fpr" "$tmp/ledger")" 0
check "  and creates nothing new" "$(origin_tag) $(local_tag)" "$good none"

fresh; git -C "$w" tag -s v0.1.0 "$main_sha" -m t; git -C "$w" push -q origin v0.1.0; git -C "$w" tag -d v0.1.0 > /dev/null; good=$(origin_tag)
git -C "$w" tag -s v0.1.0 "$main_sha" -m "another object"; mine=$(local_tag)
check "tag-release refuses a different local tag when origin holds the right one" "$(st tagrel 0.1.0 "$main_sha" "$good_fpr" "$tmp/ledger")" 1
check "  and leaves both unchanged" "$(origin_tag) $(local_tag)" "$good $mine"

fresh; git -C "$w" tag -s v0.1.0 "$main_sha" -m t; before=$(local_tag); printf 'Candidate passed: %s' "$main_sha" > "$tmp/ledger"
check "tag-release accepts an existing tag at the SHA (ledger without a final newline)" "$(st tagrel 0.1.0 "$main_sha" "$good_fpr" "$tmp/ledger")" 0
check "  and pushes that tag, not a new one" "$(origin_tag)" "$before"

fresh; git -C "$w" tag -s v0.1.0 "$main_sha" -m t; before=$(local_tag); git -C "$w" update-ref "refs/heads/$before" "$c1"
check "tag-release pushes the verified object despite a ref named like its id" "$(st tagrel 0.1.0 "$main_sha" "$good_fpr" "$tmp/ledger")" 0
check "  origin holds that object" "$(origin_tag)" "$before"

fresh; git -C "$w" config fetch.prune true; git -C "$w" config fetch.pruneTags true
git -C "$w" tag -s v0.1.0 "$main_sha" -m t; before=$(local_tag); git -C "$w" tag -a other "$c1" -m o; other=$(local_tag other)
check "tag-release with fetch pruning on reuses the local-only tag" "$(st tagrel 0.1.0 "$main_sha" "$good_fpr" "$tmp/ledger")" 0
check "  origin holds that object" "$(origin_tag)" "$before"
check "  and the unrelated local tag is still there" "$(local_tag other)" "$other"

# An unsigned v0.1.0 replaced, for local reads only, by a signed object of the same name.
fresh; git -C "$w" tag -s v0.1.0 "$main_sha" -m good; signed=$(local_tag); git -C "$w" tag -d v0.1.0 > /dev/null
git -C "$w" tag -a v0.1.0 "$main_sha" -m unsigned; git -C "$w" replace "$(local_tag)" "$signed"
check "tag-release checks the object itself, not its replacement" "$(st tagrel 0.1.0 "$main_sha" "$good_fpr" "$tmp/ledger")" 1
check "  and pushes nothing" "$(origin_tag)" none

# origin accepts the push, then drops the tag: the read-back must not confirm it.
fresh; printf '#!/bin/sh\ngit update-ref -d refs/tags/v0.1.0\n' > "$o/hooks/post-receive"; chmod +x "$o/hooks/post-receive"
check "tag-release does not confirm a push origin does not hold" "$(st tagrel 0.1.0 "$main_sha" "$good_fpr" "$tmp/ledger")" 1
check "  and says so" "$(said 'failed or was not confirmed')" 1

# With push.followTags on and an unrelated annotated tag on a main ancestor.
fresh; git -C "$w" config push.followTags true; git -C "$w" tag -a other "$c1" -m o
check "tag-release creates, verifies and pushes a new tag" "$(st tagrel 0.1.0 "$main_sha" "$good_fpr" "$tmp/ledger")" 0
check "  annotated" "$(git -C "$o" cat-file -t refs/tags/v0.1.0)" tag
check "  on the SHA" "$(git -C "$o" rev-parse 'refs/tags/v0.1.0^{commit}')" "$main_sha"
check "  and origin holds exactly that one tag" "$(git -C "$o" for-each-ref --format='%(refname) %(objectname)' refs/tags)" "refs/tags/v0.1.0 $(local_tag)"
pushed=$(origin_tag)
check "tag-release run again stops without a change" "$(st tagrel 0.1.0 "$main_sha" "$good_fpr" "$tmp/ledger")" 0
check "  origin keeps the same tag" "$(origin_tag)" "$pushed"

exit "$fail"
