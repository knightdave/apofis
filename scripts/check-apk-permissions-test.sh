#!/bin/sh
# Self-test for check-apk-permissions.sh using fake aapt2 outputs.
set -eu
check="$(cd "$(dirname "$0")" && pwd)/check-apk-permissions.sh"
tmp="$(mktemp -d)"
trap 'rm -f "$tmp"/fake && rmdir "$tmp"' EXIT
fail=0

# expect <want-exit> <name> <aapt2 output>
expect() {
    printf '#!/bin/sh\ncat <<"END"\n%s\nEND\n' "$3" > "$tmp/fake"
    chmod +x "$tmp/fake"
    if "$check" "$tmp/fake" test.apk > /dev/null 2>&1; then got=0; else got=1; fi
    if [ "$got" -eq "$1" ]; then echo "ok   $2"; else echo "FAIL $2 (exit $got, want $1)"; fail=1; fi
}

expect 0 allowed "package: xyz.rycerz.apofis
uses-permission: name='android.permission.INTERNET'
permission: xyz.rycerz.apofis.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION
uses-permission: name='xyz.rycerz.apofis.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'"
expect 0 workmanager "package: xyz.rycerz.apofis
uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.ACCESS_NETWORK_STATE'
uses-permission: name='android.permission.WAKE_LOCK'
uses-permission: name='android.permission.RECEIVE_BOOT_COMPLETED'
uses-permission: name='xyz.rycerz.apofis.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'"
# Android 17 needs ACCESS_LOCAL_NETWORK to reach a server on a LAN or private address.
expect 0 local-network "package: xyz.rycerz.apofis
uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.ACCESS_LOCAL_NETWORK'"
expect 1 nearby-wifi "uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.NEARBY_WIFI_DEVICES'"
expect 1 foreground-service "uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.WAKE_LOCK'
uses-permission: name='android.permission.FOREGROUND_SERVICE'"
expect 1 camera "uses-permission: name='android.permission.INTERNET'
uses-permission: name='android.permission.CAMERA'"
expect 1 camera-sdk-23 "uses-permission: name='android.permission.INTERNET'
uses-permission-sdk-23: name='android.permission.CAMERA'"
expect 1 camera-max-sdk "uses-permission: name='android.permission.CAMERA' maxSdkVersion='30'"
expect 1 unparsable "uses-permission: name='android.permission.INTERNET'
uses-permission-something CAMERA"
expect 1 empty "package: xyz.rycerz.apofis"
expect 1 only-unparsable "uses-permission CAMERA"
expect 1 unknown-line "uses-permission: name='android.permission.INTERNET'
uses-library: name='foo'"
expect 1 no-internet "uses-permission: name='xyz.rycerz.apofis.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'"

exit "$fail"
