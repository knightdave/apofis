#!/bin/sh
# Usage, from the main checkout, always taken from the tested commit (RELEASING.md):
#   git fetch origin && git show <SHA>:scripts/tag-release.sh | bash -s -- <version> <SHA> <key fingerprint> <ledger file>
# Creates and pushes the signed annotated tag v<version> on <SHA> only when every check
# passes; otherwise it stops with a message and pushes nothing. Never retags, never deletes.
# The body is one function so a shell reading this script from stdin has parsed all of it
# before any command runs (no command can swallow the rest of the script).
main() {
    set -eu
    die() { echo "tag-release: $*. Nothing pushed." >&2; exit 1; }
    [ $# -eq 4 ] || die "usage: tag-release.sh <version> <tested SHA> <expected key fingerprint> <ledger file>"
    version=$1 sha=$2 fpr=$3 ledger=$4 tag=v$1
    case $version in *[!0-9.]*) die "version $version is not X.Y.Z" ;; esac
    printf '%s\n' "$version" | grep -Eqx '[0-9]+\.[0-9]+\.[0-9]+' || die "version $version is not X.Y.Z"
    printf '%s\n' "$sha" | grep -Eqx '[0-9a-f]{40}' || die "$sha is not a full commit SHA"
    case $fpr in SHA256:*) ;; *) die "expected fingerprint $fpr is not SHA256:..." ;; esac

    last=$(grep '^Candidate passed: ' "$ledger" | tail -n 1 | sed 's/[[:space:]]*$//')
    [ "$last" = "Candidate passed: $sha" ] || die "the last 'Candidate passed: ' line of $ledger does not name $sha"
    git fetch origin || die "git fetch origin failed"
    [ "$(git rev-parse -q --verify 'refs/remotes/origin/main^{commit}')" = "$sha" ] || die "origin/main is not $sha"
    green=$(gh run list --commit "$sha" --workflow ci.yml --branch main --event push --limit 20 \
        --json headSha,headBranch,event,status,conclusion \
        --jq "[.[] | select(.headSha == \"$sha\" and .headBranch == \"main\" and .event == \"push\")] | first // empty | select(.status == \"completed\" and .conclusion == \"success\") | \"green\"") \
        || die "gh run list failed"
    [ "$green" = green ] || die "the newest CI run on main for $sha is not a success"

    signers=$(mktemp)
    trap 'rm -f "$signers"' EXIT
    git show "$sha:release/allowed-signers" > "$signers" || die "no release/allowed-signers at $sha"

    # verify: the tag is annotated, on $sha, and signed by the allowed key with the expected fingerprint.
    verify() {
        [ "$(git cat-file -t "refs/tags/$tag")" = tag ] || die "$tag is not an annotated tag"
        [ "$(git rev-parse -q --verify "refs/tags/$tag^{commit}")" = "$sha" ] || die "$tag points at another commit; never retag"
        # Only stderr: the tag message on stdout cannot fake the verdict.
        out=$(git -c gpg.ssh.allowedSignersFile="$signers" tag -v "$tag" 2>&1 > /dev/null) || die "$tag does not verify against release/allowed-signers at $sha"
        got=$(printf '%s\n' "$out" | sed -n 's/^Good "git" signature for .* key \(SHA256:[A-Za-z0-9+/]*\)$/\1/p')
        [ "$got" = "$fpr" ] || die "$tag is signed by '$got', expected $fpr"
    }

    if git ls-remote --exit-code origin "refs/tags/$tag" > /dev/null; then
        on_origin=yes
    else
        rc=$? # 2 means no such ref; anything else is an error
        [ "$rc" -eq 2 ] || die "git ls-remote origin failed"
        on_origin=no
    fi
    if [ "$on_origin" = yes ]; then
        # On origin: bring it here (refused if a different local tag exists), verify, stop.
        git fetch origin "refs/tags/$tag:refs/tags/$tag" || die "cannot fetch $tag from origin without replacing a local tag"
        verify
        echo "tag-release: $tag is already on origin, on $sha, signed by $fpr. Nothing to do."
        return 0
    fi
    if git rev-parse -q --verify "refs/tags/$tag" > /dev/null; then
        verify
        echo "tag-release: $tag exists here and verifies; pushing it."
    else
        git tag -s "$tag" "$sha" -m "Apofis $version" || die "creating the signed tag $tag failed (no unsigned tag is made)"
        verify
    fi
    git push origin "refs/tags/$tag:refs/tags/$tag" || die "git push of $tag failed"
    echo "tag-release: pushed $tag on $sha, signed by $fpr."
}
main "$@"
