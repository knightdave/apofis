#!/bin/sh
# Usage, from the main checkout, always taken from the tested commit (RELEASING.md):
#   git fetch origin && git show <SHA>:scripts/tag-release.sh | bash -s -- <version> <SHA> <key fingerprint> <ledger file>
# Creates and pushes the signed annotated tag v<version> on <SHA> only when every check
# passes; otherwise it stops with a message and pushes nothing. Never retags, never deletes.
# Every run that gets past the shell ends with a line starting "tag-release:".
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

    last=$(awk '/^Candidate passed: / { l = $0 } END { print l }' "$ledger") || die "cannot read $ledger"
    [ "$last" = "Candidate passed: $sha" ] || die "the last 'Candidate passed: ' line of $ledger is not exactly 'Candidate passed: $sha'"
    git fetch --no-tags origin || die "git fetch origin failed"
    # remote_id <ref>: the object id origin holds for exactly that ref now, or nothing.
    remote_id() {
        refs=$(git ls-remote origin "$1") || die "git ls-remote origin $1 failed"
        printf '%s\n' "$refs" | awk -v r="$1" '$2 == r { print $1 }'
    }
    main_id=$(remote_id refs/heads/main) || exit 1
    [ "$main_id" = "$sha" ] || die "main on origin is '$main_id', not $sha"
    repo=$(git remote get-url origin) || die "origin has no URL"
    green=$(gh run list --repo "$repo" --commit "$sha" --workflow ci.yml --branch main --event push --limit 20 \
        --json headSha,headBranch,event,status,conclusion \
        --jq "[.[] | select(.headSha == \"$sha\" and .headBranch == \"main\" and .event == \"push\")] | first // empty | select(.status == \"completed\" and .conclusion == \"success\") | \"green\"") \
        || die "gh run list failed"
    [ "$green" = green ] || die "the newest CI run on main for $sha is not a success"

    signers=$(mktemp)
    trap 'rm -f "$signers"' EXIT
    git show "$sha:release/allowed-signers" > "$signers" || die "no release/allowed-signers at $sha"

    # check <object id>: the one object this run accepts: an annotated tag named $tag, on $sha,
    # signed by the allowed key with the expected fingerprint. Everything works on the id, not the name.
    check() {
        [ "$(git cat-file -t "$1")" = tag ] || die "$tag is not an annotated tag"
        [ "$(git cat-file tag "$1" | sed -n '/^$/q; s/^tag //p')" = "$tag" ] || die "the object at $tag is a tag of another name"
        [ "$(git rev-parse -q --verify "$1^{commit}")" = "$sha" ] || die "$tag points at another commit; never retag"
        # Only stderr: the tag message on stdout cannot fake the verdict.
        out=$(git -c gpg.ssh.allowedSignersFile="$signers" verify-tag "$1" 2>&1 > /dev/null) || die "$tag does not verify against release/allowed-signers at $sha"
        got=$(printf '%s\n' "$out" | sed -n 's/^Good "git" signature for .* key \(SHA256:[A-Za-z0-9+/]*\)$/\1/p')
        [ "$got" = "$fpr" ] || die "$tag is signed by '$got', expected $fpr"
    }

    obj=$(remote_id "refs/tags/$tag") || exit 1
    if [ -n "$obj" ]; then
        git fetch --no-tags origin "refs/tags/$tag" || die "cannot fetch $tag from origin"
        check "$obj"
        echo "tag-release: $tag is already on origin ($obj), on $sha, signed by $fpr. Nothing to do."
        return 0
    fi
    obj=$(git rev-parse -q --verify "refs/tags/$tag") || {
        git tag -s "$tag" "$sha" -m "Apofis $version" || die "creating the signed tag $tag failed (no unsigned tag is made)"
        obj=$(git rev-parse -q --verify "refs/tags/$tag") || die "$tag vanished after creation"
    }
    check "$obj"
    git push --no-follow-tags origin "$obj:refs/tags/$tag" || {
        echo "tag-release: the push of $tag failed or was not confirmed; origin may hold it. Run the same command again: it checks origin and never retags." >&2
        exit 1
    }
    echo "tag-release: pushed $tag ($obj) on $sha, signed by $fpr."
}
main "$@"
