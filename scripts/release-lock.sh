#!/bin/sh
# Usage: release-lock.sh acquire|release
# A repository-wide publish lock: the git ref refs/locks/publish. Creating a ref
# through the API is atomic and fails if it already exists. The ref points to an
# annotated tag object whose tagger date records when the lock was taken, so a
# lock left by a crashed run can be broken after LOCK_STALE_SECONDS.
# acquire writes LOCK_SHA to $GITHUB_ENV; release deletes the ref only if it still
# points to that LOCK_SHA.
set -eu
repo="repos/$GITHUB_REPOSITORY"
stale="${LOCK_STALE_SECONDS:-1200}"
wait="${LOCK_WAIT_SECONDS:-2400}"
poll="${LOCK_POLL_SECONDS:-15}"

held() { gh api "$repo/git/ref/locks/publish" --jq .object.sha 2>/dev/null; }

case "$1" in
acquire)
    mine=$(gh api "$repo/git/tags" -f tag=publish-lock -f message="publish lock for run $GITHUB_RUN_ID" \
        -f object="$GITHUB_SHA" -f type=commit -f "tagger[name]=apofis-ci" \
        -f "tagger[email]=ci@invalid.example" -f "tagger[date]=$(date -u +%Y-%m-%dT%H:%M:%SZ)" --jq .sha)
    # Recorded before trying, so release can clean up even if this step is cancelled
    # right after creating the ref. release only deletes the ref while it holds this sha.
    echo "LOCK_SHA=$mine" >> "$GITHUB_ENV"
    deadline=$(( $(date +%s) + wait ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if gh api "$repo/git/refs" -f ref=refs/locks/publish -f sha="$mine" > /dev/null 2>&1; then
            echo "Publish lock acquired ($mine)"
            exit 0
        fi
        # Any lookup failure here (lock just released, API error, odd date) only means retry.
        if other=$(held) && taken=$(gh api "$repo/git/tags/$other" --jq .tagger.date) &&
            [ -n "$taken" ] && taken_s=$(date -d "$taken" +%s); then
            age=$(( $(date +%s) - taken_s ))
            if [ "$age" -gt "$stale" ] && [ "$(held)" = "$other" ]; then
                echo "::warning::Breaking publish lock $other taken $age s ago"
                gh api -X DELETE "$repo/git/refs/locks/publish" > /dev/null || true
                continue
            fi
            echo "Publish lock held by $other for $age s; waiting"
        fi
        sleep "$poll"
    done
    echo "::error::Could not acquire the publish lock within $wait s"
    exit 1
    ;;
release)
    [ -n "${LOCK_SHA:-}" ] || { echo "No lock held"; exit 0; }
    if [ "$(held || true)" = "$LOCK_SHA" ]; then
        gh api -X DELETE "$repo/git/refs/locks/publish" > /dev/null
        echo "Publish lock released"
    else
        echo "::warning::Publish lock no longer ours; not deleting"
    fi
    ;;
*)
    echo "usage: $0 acquire|release" >&2
    exit 2
    ;;
esac
