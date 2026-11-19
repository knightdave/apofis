#!/bin/sh
# Usage: publish-release.sh <apk>   (run while holding the publish lock)
# Publishes only if APOFIS_VERSION_CODE is higher than every published versionCode.
# pre-N tags carry the code in their name, other releases in a "versionCode: N" line
# of their notes. A failed lookup fails the step. A stale main pre-release is skipped;
# a stale tag release fails.
set -eu
codes=$(gh api --paginate "repos/$GITHUB_REPOSITORY/releases" --jq '.[] |
  (.tag_name | capture("^pre-(?<n>[0-9]+)$").n) //
  ((.body // "") | capture("versionCode: (?<n>[0-9]+)").n) // empty')
newest=$(printf '%s\n' "$codes" | sort -n | tail -1)
if [ -n "$newest" ] && [ "$newest" -ge "$APOFIS_VERSION_CODE" ]; then
    if [ "$GITHUB_REF_TYPE" = tag ]; then
        echo "::error::versionCode $APOFIS_VERSION_CODE is not higher than the published $newest. Push a new tag to release."
        exit 1
    fi
    echo "::notice::Skipping pre-release: versionCode $APOFIS_VERSION_CODE is not higher than the published $newest."
    exit 0
fi

apk="apofis-$APOFIS_VERSION_NAME.apk"
cp "$1" "$apk"
title="Apofis $APOFIS_VERSION_NAME"
notes="versionCode: $APOFIS_VERSION_CODE"
if [ "$GITHUB_REF_TYPE" = tag ]; then
    gh release create "$RELEASE_TAG" "$apk" --title "$title" --notes "$notes" --generate-notes --verify-tag
else
    gh release create "$RELEASE_TAG" "$apk" --title "$title" --notes "$notes" --generate-notes --prerelease --target "$GITHUB_SHA"
fi
