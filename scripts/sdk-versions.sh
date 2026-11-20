#!/bin/sh
# Prints `key=value` lines (sdk packages, build-tools version) read from the
# version catalog, for $GITHUB_OUTPUT. Keeps CI on the same SDK pins as Gradle and Nix.
set -eu
toml="$(dirname "$0")/../gradle/libs.versions.toml"
get() { sed -n "s/^$1 = \"\(.*\)\"$/\1/p" "$toml"; }
compile="$(get compileSdk).$(get compileSdkMinor)"
tools="$(get buildTools)"
[ -n "$compile" ] && [ -n "$tools" ] || { echo "SDK versions missing from $toml" >&2; exit 1; }
echo "packages=platform-tools platforms;android-$compile build-tools;$tools"
echo "build_tools=$tools"
