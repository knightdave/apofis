#!/usr/bin/env bash
# Usage: check-apk-permissions.sh <aapt2> <apk>
# Rule: the app requests only INTERNET (talking to the server) plus three normal,
# no-prompt permissions WorkManager needs to send queued messages when the
# connection returns: ACCESS_NETWORK_STATE (network constraint), WAKE_LOCK (keeps
# the CPU awake while a send runs) and RECEIVE_BOOT_COMPLETED (reschedules pending
# sends after a reboot). ACCESS_LOCAL_NETWORK (runtime, API 37+) is allowed because Android 17 blocks apps that
# target it from LAN and private addresses without it, and a self-hosted server is often on one; the app asks for it
# before connecting to a literal private address, and offers it after a failed connection attempt. POST_NOTIFICATIONS
# (runtime, API 33+) is allowed because Android 13 shows no notification without it, and the app notifies when a reply
# it started finishes or needs an answer; it is asked once, at the first send. The only other allowed entry is AndroidX's app-private
# signature permission that guards non-exported dynamic receivers. Every requesting tag counts (uses-permission,
# uses-permission-sdk-23, ...). Fails closed: any line it cannot parse fails, and
# it passes only after INTERNET was positively recognised.
set -euo pipefail
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
        android.permission.ACCESS_NETWORK_STATE | android.permission.WAKE_LOCK | android.permission.RECEIVE_BOOT_COMPLETED) ;;
        android.permission.ACCESS_LOCAL_NETWORK) ;; # servers on the local network (see above)
        android.permission.POST_NOTIFICATIONS) ;; # reply finished / needs your answer (see above)
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
