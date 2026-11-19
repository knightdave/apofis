#!/bin/sh
# Usage: release-lock.sh acquire|release
# A repository-wide publish lock: the git ref refs/locks/publish. Creating a ref
# through the API is atomic and fails if it already exists. The ref points to a
# lock commit whose author date records when the lock was taken, so a lock left by
# a crashed run can be broken after LOCK_STALE_SECONDS.
# Every attempt makes a fresh lock commit, so time spent queueing never counts
# against the holder's lease. A stale lock is taken over with a non-forced ref
# update to a child of the stale commit: that is a fast-forward only while the ref
# still points to the stale commit, so two breakers cannot both win.
# acquire writes LOCK_SHA to $GITHUB_ENV; release deletes the ref only if it still
# points to that LOCK_SHA.
set -eu
repo="repos/$GITHUB_REPOSITORY"
stale="${LOCK_STALE_SECONDS:-1200}"
wait="${LOCK_WAIT_SECONDS:-2400}"
poll="${LOCK_POLL_SECONDS:-15}"

held() { gh api "$repo/git/ref/locks/publish" --jq .object.sha 2>/dev/null; }

# new_lock <parent>: sets mine to a lock commit dated now. Recorded before use, so
# release can clean up even if this step is cancelled right after taking the ref;
# release only deletes the ref while it holds this sha. The last LOCK_SHA line wins.
new_lock() {
    mine=$(gh api "$repo/git/commits" -f message="publish lock for run $GITHUB_RUN_ID" \
        -f tree="$tree" -f "parents[]=$1" -f "author[name]=apofis-ci" \
        -f "author[email]=ci@invalid.example" -f "author[date]=$(date -u +%Y-%m-%dT%H:%M:%SZ)" --jq .sha)
    echo "LOCK_SHA=$mine" >> "$GITHUB_ENV"
}

case "$1" in
acquire)
    tree=$(gh api "$repo/git/commits/$GITHUB_SHA" --jq .tree.sha)
    deadline=$(( $(date +%s) + wait ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        new_lock "$GITHUB_SHA"
        if gh api "$repo/git/refs" -f ref=refs/locks/publish -f sha="$mine" > /dev/null 2>&1; then
            echo "Publish lock acquired ($mine)"
            exit 0
        fi
        # Any lookup failure here (lock just released, API error, odd date) only means retry.
        if other=$(held) && taken=$(gh api "$repo/git/commits/$other" --jq .author.date) &&
            [ -n "$taken" ] && taken_s=$(date -d "$taken" +%s); then
            age=$(( $(date +%s) - taken_s ))
            if [ "$age" -gt "$stale" ]; then
                echo "::warning::Breaking publish lock $other taken $age s ago"
                new_lock "$other"
                if gh api -X PATCH "$repo/git/refs/locks/publish" -f sha="$mine" -F force=false > /dev/null 2>&1; then
                    echo "Publish lock acquired ($mine)"
                    exit 0
                fi
            else
                echo "Publish lock held by $other for $age s; waiting"
            fi
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
