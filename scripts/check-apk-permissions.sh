#!/bin/sh
# Usage: check-apk-permissions.sh <aapt2> <apk>
# Rule: the app requests no platform permission other than INTERNET. The only other
# allowed entry is AndroidX's app-private signature permission that guards
# non-exported dynamic receivers.
set -eu
allowed="android.permission.INTERNET
xyz.rycerz.apofis.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
requested="$("$1" dump permissions "$2" | sed -n "s/^uses-permission: name='\(.*\)'.*/\1/p")"
[ -n "$requested" ] || { echo "No permissions found in $2" >&2; exit 1; }
extra="$(printf '%s\n' "$requested" | grep -vxF "$allowed" || true)"
if [ -n "$extra" ]; then
    echo "Disallowed permissions in $2:" >&2
    echo "$extra" >&2
    exit 1
fi
echo "Permissions OK:"
echo "$requested"
