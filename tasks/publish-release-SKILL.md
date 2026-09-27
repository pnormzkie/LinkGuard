---
name: publish-release
description: Use when releasing a new LinkGuard version to GitHub Releases (in-app update flow). Bumps the version, writes and commits short release notes, stages the signed APK with stage-release.sh, pushes, publishes with publish.sh, and verifies the update on a device.
disable-model-invocation: true
argument-hint: <version, e.g. 1.36>
---

## What this skill does

Ships version `$ARGUMENTS` so existing installs get the in-app "Update available" dialog.
A release is done only when the LIVE asset hash equals the local build AND the update
dialog was checked on a device. Never call it done from a build or code inspection alone.

## How the app consumes a release (verify in `update/UpdateChecker.kt` if in doubt)

- It reads `https://api.github.com/repos/pnormzkie/LinkGuard/releases/latest`.
- `tag_name` (`v1.36`) must be numerically newer than the installed `versionName`.
- `body` is rendered above the dialog buttons: keep it <= 9 lines / 550 chars, or
  "Update now" is pushed below the fold (v1.33 shipped ~3,600 chars and buried it).
- The first `.apk` asset is downloaded; its host must be github.com (allow-list in
  `update/UpdateInstaller.kt`). There is no `version.json`.

## Repo facts

- Release key: `C:\Users\bizbo\.android\linkguard-release.jks` (alias `linkguard`), passwords in
  `local.properties` (gitignored). Cert SHA-256 `ead80ea1...227357`; a different cert means
  every existing install rejects the update. Losing the keystore ends updates.
- Build: no gradlew; cached Gradle 8.9 + Adoptium JDK 17 (scripts set this). Run unsandboxed.
- Branch: `release/v1.30-source` (the release's `target_commitish` is the current branch).
- Token: `git credential fill` for github.com. Never paste or print a token.
- Standing Rules: `release-staging/release-notes-v<X>.md` is committed; APKs and per-version
  scripts stay untracked.

## Procedure

1. **Pre-flight.** `git status` clean except known local files; unit tests green on HEAD.
   `curl -s .../releases/latest` → confirm `$ARGUMENTS` is newer than the live tag.
2. **Bump.** In `app/build.gradle`: `versionCode` +1, `versionName "$ARGUMENTS"`.
   Commit: `Bump to $ARGUMENTS (versionCode N)`.
3. **Notes.** Write `release-staging/release-notes-v$ARGUMENTS.md`: one headline line, 2-4
   bullets in plain user language, then `N tests. Updates in place over v<prev> — your scan
   history is kept.` Commit: `Add the v$ARGUMENTS release notes`.
4. **Stage.** `bash release-staging/stage-release.sh $ARGUMENTS`. It checks the version and
   notes length, runs clean tests + assembleRelease, verifies cert and badge, and writes the
   APK + `.sha256`. It refuses to overwrite an existing APK. Stop on any FAIL.
5. **Push.** `git push origin <branch>`, then
   `bash release-staging/publish.sh $ARGUMENTS --dry-run` (read-only preflight).
6. **Publish.** `bash release-staging/publish.sh $ARGUMENTS`. Must end with
   `RESULT: PUBLISHED_AND_VERIFIED v$ARGUMENTS` (downloaded SHA-256 == staged). It is
   idempotent; rerun after a partial failure. The auto-mode permission check blocks push +
   publish until Norman says so explicitly in the current turn ("publish now" / "publish
   muna"); a yes to "commit and release?" was NOT enough for v1.38. Do not retry around a
   block: ask, or have him run it himself (bash mode: type `!` first).
7. **Device check.** Throwaway emulator image (never the user's AVD data):
   `emulator -avd Pixel_7 -data <scratch>/x.img -wipe-data -no-snapshot`, install the
   PREVIOUS release APK, launch → screenshot the dialog: every note line visible and
   "Update now" on screen without scrolling. Tap Update now → grant "install unknown apps"
   once (fresh device) → return with the in-app flow, not BACK (BACK cancels the installer)
   → confirm `versionName` and that the installed `base.apk` hash equals the asset.
   Smoke: `https://google.com` SAFE, `https://testsafebrowsing.appspot.com/s/phishing.html`
   DANGEROUS.
   The emulator needs ~3 GB free RAM. With less, it hangs adb-"offline" and Claude Code may
   kill it; then use Norman's phone (RZCT10CY6ED, same release key, `install -r` keeps
   history) only if he agrees, and don't drive it while he is using it.
8. **Record.** Append a "vX published" block to `tasks/todo.md` (commits, tag → sha, release
   id, asset size + SHA-256, device evidence, anything not verified). Commit + push it.

## Common failures

- jlink / JdkImageTransform or AGP resolve errors → wrong JDK (must be JDK 17).
- `no signed app-release.apk` → RELEASE_* entries missing from `local.properties`.
- Asset `state=starter` → incomplete upload; publish.sh deletes and re-uploads it.
- Upload 404/422 → upload went to api.github.com; it must use the release `upload_url`.
- `releases/latest` still shows the old tag right after publishing → 60s cache;
  publish.sh waits it out.
- Dialog shows but a note line looks missing in a `uiautomator` dump → check the
  screenshot; quotes in the text break naive grep parsing.
