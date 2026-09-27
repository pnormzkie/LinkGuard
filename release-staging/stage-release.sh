#!/usr/bin/env bash
# Builds, tests and stages the signed release APK for one version. Changes nothing remote.
#   usage: bash release-staging/stage-release.sh <version>      e.g. 1.36
# Preconditions: app/build.gradle already bumped to <version>, and
# release-staging/release-notes-v<version>.md written (<= 9 lines / 550 chars).
# Output: release-staging/LinkGuard-v<version>.apk (+ .sha256). Refuses to overwrite one.
set -euo pipefail
cd "$(dirname "$0")/.."

V="${1:?usage: stage-release.sh <version, e.g. 1.36>}"
EXPECTED_CERT=ead80ea17110eb419e824a969807e2309bb11e5424e6eb81e0a07b1874227357
export JAVA_HOME="C:/Program Files/Eclipse Adoptium/jdk-17.0.18.8-hotspot"   # JDK 17, not Studio's JBR 21
GRADLE="$USERPROFILE/.gradle/wrapper/dists/gradle-8.9-bin/90cnw93cvbtalezasaz0blq0a/gradle-8.9/bin/gradle.bat"
BT=$(ls -d "$LOCALAPPDATA"/Android/Sdk/build-tools/* | sort -V | tail -1)
NOTES="release-staging/release-notes-v$V.md"
APK="release-staging/LinkGuard-v$V.apk"

fail() { echo "FAIL: $*"; exit 1; }

grep -q "versionName \"$V\"" app/build.gradle || fail "app/build.gradle versionName is not $V (bump first)"
[ -f "$NOTES" ] || fail "$NOTES missing"
# The in-app update dialog shows this body above "Update now"; longer bodies bury the button.
python - "$NOTES" <<'EOF' || exit 1
import io, sys
text = io.open(sys.argv[1], encoding="utf-8").read().rstrip("\n")
lines, chars = text.count("\n") + 1, len(text)
print(f"NOTES {lines} lines, {chars} chars")
if lines > 9 or chars > 550:
    sys.exit("FAIL: release notes exceed 9 lines / 550 chars")
EOF
[ -e "$APK" ] && fail "$APK already exists; a published APK must never be rebuilt over"

cmd //c "$GRADLE" -p . clean :app:testDebugUnitTest :app:assembleRelease -q
python - <<'EOF'
import glob, re
t = f = 0
for p in glob.glob("app/build/test-results/testDebugUnitTest/*.xml"):
    m = re.search(r'tests="(\d+)" skipped="\d+" failures="(\d+)" errors="(\d+)"', open(p, encoding="utf8").read())
    t += int(m[1]); f += int(m[2]) + int(m[3])
print(f"TESTS {t} run, {f} failed")
if f or not t:
    raise SystemExit("FAIL: unit tests")
EOF

[ -f app/build/outputs/apk/release/app-release.apk ] ||
    fail "no signed app-release.apk (RELEASE_* signing entries missing from local.properties?)"
cp app/build/outputs/apk/release/app-release.apk "$APK"
CERT=$(cmd //c "$BT/apksigner.bat" verify --print-certs "$APK" | sed -n 's/.*certificate SHA-256 digest: //p' | head -1 | tr -d '\r')
if [ "$CERT" != "$EXPECTED_CERT" ]; then
    rm -f "$APK"
    fail "signing cert $CERT != release key $EXPECTED_CERT (installs would be rejected)"
fi
BADGE=$("$BT/aapt.exe" dump badging "$APK" | head -1 | tr -d '\r')
if ! echo "$BADGE" | grep -q "name='com.linkguard.app' .*versionName='$V'"; then
    rm -f "$APK"
    fail "APK badge does not match $V: $BADGE"
fi

SHA=$(sha256sum "$APK" | cut -d' ' -f1 | tr 'a-f' 'A-F')
echo "$SHA" > "$APK.sha256"
echo "STAGED $APK $(wc -c < "$APK") bytes"
echo "SHA256 $SHA"
echo "BADGE $BADGE"
