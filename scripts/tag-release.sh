#!/bin/sh
# Usage, from the main checkout, always taken from the tested commit (RELEASING.md):
#   GIT_NO_REPLACE_OBJECTS=1 git fetch --no-tags --no-prune --no-prune-tags --refmap= origin refs/heads/main &&
#     GIT_NO_REPLACE_OBJECTS=1 git show <SHA>:scripts/tag-release.sh | bash -s -- <version> <SHA> <key fingerprint> <ledger file>
# Creates and pushes the signed annotated tag v<version> on <SHA> only when every check
# passes; otherwise it stops with a message and pushes nothing. Never retags, never deletes.
# A run that finishes ends with a line starting "tag-release:".
# The body is one function so a shell reading this script from stdin has parsed all of it
# before any command runs (no command can swallow the rest of the script).
main() {
    set -eu
    export GIT_NO_REPLACE_OBJECTS=1 # git reads the objects it pushes, not replacements
    die() { echo "tag-release: $*. Nothing pushed." >&2; exit 1; }
    [ $# -eq 4 ] || die "usage: tag-release.sh <version> <tested SHA> <expected key fingerprint> <ledger file>"
    version=$1 sha=$2 fpr=$3 ledger=$4 tag=v$1
    case $version in *[!0-9.]*) die "version $version is not X.Y.Z" ;; esac
    printf '%s\n' "$version" | grep -Eqx '[0-9]+\.[0-9]+\.[0-9]+' || die "version $version is not X.Y.Z"
    printf '%s\n' "$sha" | grep -Eqx '[0-9a-f]{40}' || die "$sha is not a full commit SHA"
    case $fpr in SHA256:*) ;; *) die "expected fingerprint $fpr is not SHA256:..." ;; esac

    # The ledger is a regular file: bash would open a path like /dev/tcp/host/port as a socket.
    [ -f "$ledger" ] || die "the ledger $ledger is not a regular file"
    # One reader decides: the last line starting "Candidate passed: " is exactly the wanted one.
    # The shell opens the ledger: an awk operand like "want=" would be an assignment, not a file.
    awk -v want="Candidate passed: $sha" '/^Candidate passed: / { l = $0 } END { exit !(l == want) }' < "$ledger" ||
        die "the last 'Candidate passed: ' line of $ledger is not exactly 'Candidate passed: $sha'"
    # fetch <ref>: that one ref into FETCH_HEAD only: no tags, no pruning, no configured refspecs.
    fetch() { git fetch --no-tags --no-prune --no-prune-tags --refmap= origin "$1"; }
    # remote_id <ref>: the object id origin holds for exactly that ref now, or nothing.
    remote_id() { refs=$(git ls-remote origin "$1") && printf '%s\n' "$refs" | awk -v r="$1" '$2 == r { print $1 }'; }
    fetch refs/heads/main || die "git fetch of main failed"
    main_id=$(remote_id refs/heads/main) || die "git ls-remote origin failed"
    [ "$main_id" = "$sha" ] || die "main on origin is '$main_id', not $sha"
    repo=$(git remote get-url origin) || die "origin has no URL"
    [ "$(git remote get-url --push --all origin)" = "$repo" ] || die "origin's push URL differs from its fetch URL"
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

    local_obj=$(git rev-parse -q --verify "refs/tags/$tag") || local_obj=
    obj=$(remote_id "refs/tags/$tag") || die "git ls-remote origin failed"
    if [ -n "$obj" ]; then
        [ -z "$local_obj" ] || [ "$local_obj" = "$obj" ] || die "a different local tag $tag exists; the owner removes it"
        fetch "refs/tags/$tag" || die "cannot fetch $tag from origin"
        [ "$(git rev-parse -q --verify FETCH_HEAD)" = "$obj" ] || die "origin's $tag changed while it was read"
        check "$obj"
        echo "tag-release: $tag is already on origin ($obj), on $sha, signed by $fpr. Nothing to do."
        return 0
    fi
    obj=$local_obj
    if [ -z "$obj" ]; then
        git tag -s "$tag" "$sha" -m "Apofis $version" || die "creating the signed tag $tag failed (no unsigned tag is made)"
        obj=$(git rev-parse -q --verify "refs/tags/$tag") || die "$tag vanished after creation"
    fi
    check "$obj"
    # A typed source: a ref that happens to be named like the id cannot be picked instead.
    if git push --no-follow-tags origin "$obj^{tag}:refs/tags/$tag" && back=$(remote_id "refs/tags/$tag") && [ "$back" = "$obj" ]; then
        echo "tag-release: pushed $tag ($obj) on $sha, signed by $fpr."
    else
        echo "tag-release: the push of $tag failed or was not confirmed; origin may hold it. Run the same command again: it checks origin and never retags." >&2
        exit 1
    fi
}
main "$@"
