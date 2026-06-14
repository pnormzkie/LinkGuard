---
name: publish-release
description: Use when publishing a new app release to GitHub Releases (in-app update flow). Bumps version, builds and validates the SIGNED APK, writes version.json, uploads assets via curl, and live-verifies the public path.
disable-model-invocation: true
argument-hint: [changelog]
---

## What This Skill Does

Publishes a new release to GitHub Releases so existing installs get the in-app
"Update available" prompt. **Never call a release done on code inspection alone — finish
every verification step and confirm the LIVE artifacts match the local build.**

## Repo facts (verify, don't assume)

- Repo: `pnormzkie/LinkGuard`.
- Keystore: `C:\Users\bizbo\.android\linkguard-release.jks` (alias `linkguard`). Passwords in `local.properties` (gitignored, `RELEASE_STORE_*` / `RELEASE_KEY_*`). LOSS = no future updates.
- Expected signing cert SHA-256: `ead80ea17110eb419e824a969807e2309bb11e5424e6eb81e0a07b1874227357`. A mismatch = installs REJECTED — STOP.
- Build JDK: `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"` (JDK 17). Android Studio's bundled JBR is Java 21 and breaks AGP 8.1.0's jlink JdkImageTransform — do NOT use it.
- `versionCode`/`versionName` live in `app/build.gradle` (single source of truth; this repo uses Groovy, not `.kts`).
- Publishing needs a fine-grained PAT (Contents: read/write). It is exposed once pasted in chat — tell the user to REVOKE it right after.

## Procedure

### 1. Pre-flight (avoid version drift)
- Read current source `versionCode`/`versionName` from `app/build.gradle`.
- Check live latest: `curl -s https://api.github.com/repos/pnormzkie/LinkGuard/releases/latest | grep tag_name`.
- Confirm source versionCode == published. If a parallel session shipped higher, re-baseline.
- **Bump FIRST**: increment `versionCode` by 1 and `versionName`.

### 2. Build the signed APK
- `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"`
- This repo has NO `gradlew` wrapper. Build with the cached Gradle 8.9:
  `& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat" assembleRelease --console=plain` → `app/build/outputs/apk/release/app-release.apk`

### 3. Validate the APK (build-tools apksigner/aapt)
- `apksigner verify --print-certs app-release.apk` → cert SHA-256 MUST equal `ead80ea17110eb419e824a969807e2309bb11e5424e6eb81e0a07b1874227357`.
- `aapt dump badging app-release.apk` → versionCode/versionName MUST match the bump (package `com.linkguard.app`).
- Record `Get-FileHash app-release.apk -Algorithm SHA256` (to match live later).

### 4. Write release/version.json
- Fields: `versionCode` (int), `versionName`, `apkUrl` (`https://github.com/pnormzkie/LinkGuard/releases/latest/download/app-release.apk`), `changelog` (user-facing, plain language, ASCII only — escape quotes as `\"`, newlines as `\n`).
- Write it DIRECTLY (not via a PS `Set-Content -Encoding utf8` helper — PS 5.1 writes a BOM and mojibakes non-ASCII).
- Verify: first byte is `{` (no BOM), 0 non-ASCII bytes, and it parses with the right code/name.

### 5. Publish + upload assets
- Create the release: `POST /repos/pnormzkie/LinkGuard/releases` with `tag_name=vX.Y.Z`, `name=vX.Y.Z`, `body=<changelog>`, `make_latest=true`.
- **Upload the APK with `curl`, NOT PowerShell `Invoke-RestMethod`.** IRM corrupts the binary upload ("underlying connection was closed") and leaves the asset in `state=starter` (broken download):
  ```bash
  curl -sS -X POST -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/vnd.android.package-archive" \
    --data-binary @app/build/outputs/apk/release/app-release.apk \
    "https://uploads.github.com/repos/pnormzkie/LinkGuard/releases/<id>/assets?name=app-release.apk"
  ```
- Upload `version.json` (tiny) with `Content-Type: application/json`.
- Confirm BOTH assets report `state=uploaded` (a `starter` asset is incomplete → delete + re-upload via curl). The APK asset `digest` should equal the local SHA-256.

### 6. Live-verify (public path)
- `releases/latest` tag == `vX.Y.Z`.
- `latest/download/version.json` → correct `versionCode`/`versionName`.
- Download `latest/download/app-release.apk` → SHA-256 IDENTICAL to local, full expected byte size (proves not stale/truncated).

### 7. Post-release
- Tell the user: **REVOKE the PAT now**.
- Update `tasks/todo.md` with a SHIPPED block (id, tag, SHA-256, verification).
- Remind: next release, bump versionCode FIRST (check live latest first).

## Common failures (generic, keep these)
- AGP "could not resolve com.android.tools.build:gradle" / jlink JdkImageTransform failure = wrong JDK. Set JAVA_HOME to the Adoptium JDK 17, not the Android Studio JBR (Java 21).
- APK asset stuck `state=starter` = PS upload corruption. Delete + re-upload via curl; verify hash.
- version.json mojibake / BOM = `Set-Content -Encoding utf8` on PS5.1. Write directly, ASCII only.
