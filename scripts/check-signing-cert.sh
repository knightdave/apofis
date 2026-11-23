#!/bin/sh
# Usage: check-signing-cert.sh <apksigner> <apk> <digest file>
# Exits non-zero unless the APK verifies with exactly one signer whose certificate
# SHA-256 digest equals the digest file's one line. A mismatch is a stop for the
# owner, never a reason to update the digest file (RELEASING.md).
set -eu
die() { echo "::error::Signing certificate check failed: $*" >&2; exit 1; }
[ $# -eq 3 ] || die "usage: check-signing-cert.sh <apksigner> <apk> <digest file>"
[ "$(wc -l < "$3")" -eq 1 ] || die "$3 must hold exactly one line"
want=$(cat "$3")
printf '%s\n' "$want" | grep -Eqx '[0-9a-f]{64}' || die "$3 does not hold a SHA-256 digest"
out=$("$1" verify -v --print-certs "$2") || die "apksigner verify failed for $2"
[ "$(printf '%s\n' "$out" | sed -n 's/^Number of signers: //p')" = 1 ] || die "$2 must have exactly one signer"
# Lines read "V2 Signer: ...", "V3.0 Signer: ..." or "V2 Signer #1: ...", by scheme; every one must match.
digests=$(printf '%s\n' "$out" | sed -n 's/^[^ ]* Signer[^:]*: certificate SHA-256 digest: //p' | sort -u)
[ "$digests" = "$want" ] || die "$2 is signed by '$digests', expected $want"
echo "Signing certificate of $2 matches $3."
