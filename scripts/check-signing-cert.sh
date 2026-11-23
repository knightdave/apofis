#!/bin/sh
# Usage: check-signing-cert.sh <apksigner> <apk> <digest file>
# Exits non-zero unless the APK verifies with exactly one signer whose certificate
# SHA-256 digest equals the digest file's one line. A mismatch is a stop for the
# owner, never a reason to update the digest file (RELEASING.md).
set -eu
die() { echo "::error::Signing certificate check failed: $*" >&2; exit 1; }
[ $# -eq 3 ] || die "usage: check-signing-cert.sh <apksigner> <apk> <digest file>"
want=$(head -c 64 "$3") || die "cannot read $3"
# The file's bytes must be exactly the 64 hex digits and one newline (no CR, NUL or second record).
printf '%s\n' "$want" | grep -Eqx '[0-9a-f]{64}' && printf '%s\n' "$want" | cmp -s - "$3" ||
    die "$3 must be exactly one line of 64 lower-case hex digits"
out=$("$1" verify -v --print-certs "$2") || die "apksigner verify failed for $2"
[ "$(printf '%s\n' "$out" | sed -n 's/^Number of signers: //p')" = 1 ] || die "$2 must have exactly one signer"
# Every certificate record, whatever its label (scheme, signer number, hybrid, source stamp), must match.
digests=$(printf '%s\n' "$out" | sed -n 's/^.*certificate SHA-256 digest: //p' | sort -u)
[ "$digests" = "$want" ] || die "$2 is signed by '$digests', expected $want"
echo "Signing certificate of $2 matches $3."
