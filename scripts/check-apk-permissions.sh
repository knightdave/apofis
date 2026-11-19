#!/bin/sh
# Usage: check-apk-permissions.sh <aapt2> <apk>
# Rule: the app requests no platform permission other than INTERNET. The only other
# allowed entry is AndroidX's app-private signature permission that guards
# non-exported dynamic receivers. Every requesting tag counts (uses-permission,
# uses-permission-sdk-23, ...); a requesting line that cannot be parsed fails.
set -eu
allowed="android.permission.INTERNET
xyz.rycerz.apofis.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
dump="$("$1" dump permissions "$2")"
lines="$(printf '%s\n' "$dump" | grep '^uses-' || true)"
[ -n "$lines" ] || { echo "No permissions found in $2" >&2; exit 1; }
requested="$(printf '%s\n' "$lines" | sed -n "s/^uses-permission[a-z0-9-]*: name='\([^']*\)'.*/\1/p")"
if [ "$(printf '%s\n' "$lines" | wc -l)" -ne "$(printf '%s\n' "$requested" | wc -l)" ]; then
    echo "Unrecognised permission lines in $2:" >&2
    printf '%s\n' "$lines" >&2
    exit 1
fi
extra="$(printf '%s\n' "$requested" | grep -vxF "$allowed" || true)"
if [ -n "$extra" ]; then
    echo "Disallowed permissions in $2:" >&2
    echo "$extra" >&2
    exit 1
fi
echo "Permissions OK:"
printf '%s\n' "$lines"
