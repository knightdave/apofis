#!/bin/sh
# Usage: check-apk-permissions.sh <aapt2> <apk>
# Rule: the app requests no platform permission other than INTERNET. The only other
# allowed entry is AndroidX's app-private signature permission that guards
# non-exported dynamic receivers. Every requesting tag counts (uses-permission,
# uses-permission-sdk-23, ...). Fails closed: any line it cannot parse fails, and
# it passes only after INTERNET was positively recognised.
set -eu
dump="$("$1" dump permissions "$2")"
internet=0
bad=""
while IFS= read -r line; do
    case "$line" in
    "package: "* | "permission: "*) continue ;;
    uses-permission*)
        name=$(printf '%s\n' "$line" | sed -n "s/^uses-permission[a-z0-9-]*: name='\([^']*\)'.*/\1/p")
        case "$name" in
        android.permission.INTERNET) internet=1 ;;
        xyz.rycerz.apofis.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION) ;;
        "") bad="$bad
unparsable: $line" ;;
        *) bad="$bad
disallowed: $name" ;;
        esac
        ;;
    *) bad="$bad
unparsable: $line" ;;
    esac
done <<END
$dump
END
[ "$internet" -eq 1 ] || bad="$bad
missing: android.permission.INTERNET"
if [ -n "$bad" ]; then
    echo "Permission check failed for $2:$bad" >&2
    exit 1
fi
echo "Permissions OK:"
printf '%s\n' "$dump"
