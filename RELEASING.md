# Releasing Apofis

Pre-releases need nothing: every push to `main` builds, signs and publishes `0.1.0-pre.N` (tag `pre-N`, versionCode `N` = the CI run number). This file is about normal releases, tags `vX.Y.Z`.

## The rules that never change

- A release tag points only at the commit that passed CI and the full emulator scenario set (the candidate). Any later commit on `main` must pass that set again before it can be tagged.
- A pushed tag is never moved or deleted, not even when its release job failed before publishing.
- The APK signing key never changes. Its certificate digest is in `release/signing-cert.sha256`; a release whose certificate differs is never published, and the file is never changed to make one pass.
- Tags are signed by the SSH key in `release/allowed-signers` (fingerprint `SHA256:PtPAxwgKEbi47CtvfmAp8yRCRrzf+3lyQBtfrIWH7NM`). If signing fails, no tag is pushed; a tag signed with any other key is a new decision for the owner.

## The freeze

From the moment a candidate is recorded, nothing merges to `main` except a commit that replaces the candidate (a fix needed by the scenario set, or a release repair under "Rollback" below). That commit becomes the new candidate and reruns the full scenario set. The freeze ends when the release job is green and both checks under "Verify" have passed. If the outcome is "no tag" or "tag not pushed", the freeze stays until the owner decides.

## Make the tag

Only one procedure makes a tag, for the controller and for the owner alike. It runs `scripts/tag-release.sh` taken from the tested commit itself, never from a working tree, from the main checkout:

```sh
git fetch origin && git show <SHA>:scripts/tag-release.sh | bash -s -- <version> <SHA> SHA256:PtPAxwgKEbi47CtvfmAp8yRCRrzf+3lyQBtfrIWH7NM <ledger file>
```

It stops with a message and pushes nothing unless, in order:

1. the last line of the ledger file that starts with `Candidate passed: ` is exactly `Candidate passed: <SHA>`;
2. `git fetch origin` succeeds and `origin/main` is exactly `<SHA>`;
3. the newest push run of `ci.yml` on `main` for `<SHA>` succeeded (`gh run list --branch main`, so a pull request run or the tag's own run never counts);
4. the allowed signers are read from `<SHA>:release/allowed-signers`, not from the working tree;
5. if `v<version>` already exists here or on `origin`, it is an annotated tag on `<SHA>` signed by the expected key; it is then pushed if `origin` lacks it, and otherwise the script stops. It never retags or deletes;
6. otherwise it creates the signed annotated tag `v<version>` on `<SHA>` (`git tag -s`, message `Apofis <version>`), verifies it against the allowed signers, compares the fingerprint with the expected one and pushes the tag.

If signing fails, nothing is pushed: put "tag not pushed: signing failed" and the filled-in command in the handoff, to be run from the main checkout once signing works.

## The tag guard in CI

The release job checks every ref before it touches a secret. A tag must match `^v[0-9]+\.[0-9]+\.[0-9]+$`, its commit must be an ancestor of `origin/main`, and the tag must be annotated, on that commit, and signed by the key in that commit's `release/allowed-signers` (`scripts/check-release-tag.sh`). A push to any branch other than `main` stops. The guard catches mistakes; it is not a trust boundary, since anyone who can push a tag can also change the workflow. A GitHub tag protection rule for `v*` is the owner's option.

After the build, `scripts/check-signing-cert.sh` requires exactly one signer whose certificate digest equals `release/signing-cert.sha256`, before the publish lock is taken.

## Verify

1. The release job is green. `gh release view v<version> --json isPrerelease,assets,body` shows `isPrerelease: false`, one asset `apofis-<version>.apk` and a `versionCode: N` line. N is greater than every `versionCode:` value in the notes of the earlier releases:

   ```sh
   gh api --paginate repos/knightdave/apofis/releases --jq '.[] | select(.tag_name != "v<version>") | .body'
   ```

2. `gh api repos/knightdave/apofis/releases/latest --jq .tag_name` prints `v<version>`. Download the asset (`gh release download v<version> -p '*.apk'`) and check it:

   ```sh
   scripts/check-signing-cert.sh "$ANDROID_HOME/build-tools/<version>/apksigner" apofis-<version>.apk release/signing-cert.sha256
   "$ANDROID_HOME/build-tools/<version>/aapt2" dump badging apofis-<version>.apk | head -1
   ```

   The badging shows package `xyz.rycerz.apofis`, `versionName='<version>'` and the versionCode N of step 1. The build-tools version is the one `scripts/sdk-versions.sh` prints.

## Rollback

- A transient failure of a release not yet published: `gh run rerun <run id>`.
- A failure a rerun cannot fix abandons that tag; it stays where it is. If the tested commit is unchanged, the next patch tag goes on the same SHA. If the fix lands on `main`, it is a new candidate that passes the full scenario set first.
- A certificate mismatch is a stop for the owner. Never change `release/signing-cert.sha256` to get past it.
- A published bad release: publish the next patch tag with a higher versionCode first, then mark the bad release as a pre-release and check that `releases/latest` names the new tag.
