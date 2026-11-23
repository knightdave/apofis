#!/bin/sh
# Usage: check-release-tag.sh <ref name> <commit>
# The release job's tag guard: exits non-zero unless the name is vX.Y.Z, the commit is
# an ancestor of origin/main, and refs/tags/<name> is an annotated tag on that commit
# signed by the key in that commit's release/allowed-signers. Guards against mistakes;
# needs the full history and the tag object (checkout with fetch-depth: 0).
set -eu
die() { echo "::error::Release tag refused: $*" >&2; exit 1; }
[ $# -eq 2 ] || die "usage: check-release-tag.sh <ref name> <commit>"
name=$1 commit=$2
case $name in *[!v0-9.]*) die "$name is not vX.Y.Z" ;; esac # no newline can reach grep
printf '%s\n' "$name" | grep -Eqx 'v[0-9]+\.[0-9]+\.[0-9]+' || die "$name is not vX.Y.Z"
git merge-base --is-ancestor "$commit" origin/main || die "$commit is not on origin/main"
[ "$(git cat-file -t "refs/tags/$name")" = tag ] || die "$name is not an annotated tag"
tagged=$(git rev-parse -q --verify "refs/tags/$name^{commit}") || die "$name has no commit"
[ "$tagged" = "$(git rev-parse -q --verify "$commit^{commit}")" ] || die "$name does not point at $commit"
signers=$(mktemp)
trap 'rm -f "$signers"' EXIT
git show "$commit:release/allowed-signers" > "$signers" || die "no release/allowed-signers at $commit"
git -c gpg.ssh.allowedSignersFile="$signers" tag -v "$name" > /dev/null 2>&1 || die "$name is not signed by the key in release/allowed-signers"
echo "Release tag $name on $commit accepted."
