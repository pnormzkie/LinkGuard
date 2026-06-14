---
name: on-device-smoke
description: Use when running an on-device smoke test on an Android emulator via adb — build and install the debug APK, drive the app UI by element bounds, and capture screenshots plus logcat as evidence.
disable-model-invocation: true
---

## What This Skill Does

Runs a real on-device behavioral check on the Android emulator and collects EVIDENCE
(screenshots + logcat) — never sign off on behavior from code inspection alone.

## Environment
- adb: `C:\Users\bizbo\AppData\Local\Android\Sdk\platform-tools\adb.exe`
- emulator: `C:\Users\bizbo\AppData\Local\Android\Sdk\emulator\emulator.exe`, AVD `Pixel_7`
- Build JDK: `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"` (JDK 17+)
- App: package `com.linkguard.app`, launcher activity `.ui.MainActivity`
- Screen `1080x2400` (density 420; tap coordinates depend on this).

## Procedure

### 1. Build + install the debug APK
- `$env:JAVA_HOME = "C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot"`
- This repo has NO `gradlew` wrapper. Build with the cached Gradle 8.9:
  `& "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.9-bin\90cnw93cvbtalezasaz0blq0a\gradle-8.9\bin\gradle.bat" assembleDebug --console=plain`
- `adb install -r app/build/outputs/apk/debug/app-debug.apk`
- `adb logcat -c` (clear, so evidence is clean)

### 2. Boot the emulator (if needed)
- `adb devices` → if empty: `C:\Users\bizbo\AppData\Local\Android\Sdk\emulator\emulator.exe -avd Pixel_7 -no-snapshot-load -no-boot-anim` (background).
- Wait: `adb wait-for-device`, then poll `adb shell getprop sys.boot_completed` until `1`.

### 3. Launch fresh
- `adb shell am force-stop com.linkguard.app` then `adb shell monkey -p com.linkguard.app -c android.intent.category.LAUNCHER 1`. Wait ~6s.

### 4. Drive the UI — DO NOT guess coordinates
Widgets MOVE when the soft keyboard is up. Get exact bounds:
- `adb shell uiautomator dump /sdcard/ui.xml` → pull → grep the target element's `bounds="[x1,y1][x2,y2]"`. Tap the CENTER of those bounds.
- Re-dump after the keyboard opens (input/submit buttons shift up). Don't reuse stale coordinates.

### 5. Type + send (input is flaky — verify before submitting)
- Tap the input, wait ~2s.
- `adb shell input text "your%squery"` — `%s` = space; a literal `?` becomes `%3F` (delete with `input keyevent 67`).
- **Screenshot and CONFIRM the field shows the exact text BEFORE submitting** (a mis-tap can trigger something else).
- Tap submit using the keyboard-up bounds from step 4.

### 6. Capture evidence
- Screenshot: `adb shell screencap -p /sdcard/s.png` → `adb pull /sdcard/s.png tasks/<name>.png`.
- Logs: `adb logcat -d -s ScanOrchestrator` → capture the lines that prove the behavior under test. (Other useful tags: `LinkNotifService`, `UpdateChecker`, `UpdateInstaller`.)
- Save screenshots under `tasks/`.

### 7. Report
- State what was tested, the evidence (logcat line + screenshot path), pass/fail, and anything NOT covered.

## Gotchas (generic, keep these)
- Fresh-launch taps can hit the wrong control — always verify field content before submitting.
- Some network calls fail on emulator images with `CertPathValidatorException` (trust anchor) — note it; usually an emulator quirk, not a code bug.
- A cold-boot "System UI isn't responding" ANR is the emulator, not the app.
- Shell env (e.g. JAVA_HOME) does NOT persist across terminals — re-set it each session.
