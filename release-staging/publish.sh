#!/usr/bin/env bash
# Publishes a staged LinkGuard release to GitHub Releases (the in-app updater reads
# releases/latest: tag_name, body, and the first .apk asset).
#   usage: bash release-staging/publish.sh <version> [--dry-run]      e.g. 1.36
# Needs: release-staging/LinkGuard-v<version>.apk + .sha256 (from stage-release.sh),
# release-staging/release-notes-v<version>.md, and HEAD pushed to origin.
# Idempotent: safe to rerun after a partial failure. The token (git credential) is never
# printed. --dry-run makes no authenticated or writing call.
set -euo pipefail
cd "$(dirname "$0")/.."

V="${1:?usage: publish.sh <version> [--dry-run]}"
DRY="${2:-}"
TAG="v$V"
ASSET="LinkGuard-v$V.apk"
APK="release-staging/$ASSET"
NOTES="release-staging/release-notes-v$V.md"
REPO=pnormzkie/LinkGuard
API="https://api.github.com/repos/$REPO"

fail() { echo "FAIL: $*"; exit 1; }

[ -f "$APK" ] || fail "$APK missing (run stage-release.sh $V first)"
[ -f "$APK.sha256" ] || fail "$APK.sha256 missing"
[ -f "$NOTES" ] || fail "$NOTES missing"
EXPECTED=$(tr -d '\r\n ' < "$APK.sha256")
[ "$(sha256sum "$APK" | cut -d' ' -f1 | tr 'a-f' 'A-F')" = "$EXPECTED" ] || fail "$APK does not match its .sha256"

BRANCH=$(git rev-parse --abbrev-ref HEAD)
git fetch -q origin "$BRANCH"
[ "$(git rev-parse HEAD)" = "$(git rev-parse "origin/$BRANCH")" ] || fail "HEAD is not pushed to origin/$BRANCH"
git ls-files --error-unmatch "$NOTES" >/dev/null 2>&1 || fail "$NOTES is not committed (Standing Rule)"
echo "PREFLIGHT OK tag=$TAG branch=$BRANCH head=$(git rev-parse --short HEAD) sha256=$EXPECTED"

TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

if [ "$DRY" = "--dry-run" ]; then
    curl -s "$API/releases/latest" | python -c "import json,sys; print('PUBLIC_LATEST', json.load(sys.stdin).get('tag_name'))"
    code=$(curl -s -o /dev/null -w '%{http_code}' "$API/releases/tags/$TAG")
    echo "PUBLIC_RELEASE_$TAG http=$code (404 = not published yet; drafts are invisible here)"
    echo "DRY_RUN: no release created, nothing uploaded"
    exit 0
fi

TOKEN=$(printf 'protocol=https\nhost=github.com\n\n' | git credential fill | sed -n 's/^password=//p')
[ -n "$TOKEN" ] || { echo "RESULT: NO_TOKEN"; exit 2; }
AUTH="Authorization: Bearer $TOKEN"

REL_ID=$(curl -s -H "$AUTH" "$API/releases?per_page=30" | python -c "
import json,sys
m=[r for r in json.load(sys.stdin) if r.get('tag_name')=='$TAG']
print(m[0]['id'] if m else 'NEW')
")
if [ "$REL_ID" = "NEW" ]; then
    python - "$NOTES" "$TAG" "$BRANCH" "$TMP/payload.json" <<'EOF'
import io, json, sys
notes, tag, branch, out = sys.argv[1:]
body = io.open(notes, encoding="utf-8").read()
p = {"tag_name": tag, "target_commitish": branch, "name": "LinkGuard " + tag,
     "body": body, "draft": True, "prerelease": False}
io.open(out, "w", encoding="utf-8").write(json.dumps(p))
EOF
    REL_ID=$(curl -s -X POST -H "$AUTH" -H "Content-Type: application/json" -d @"$TMP/payload.json" "$API/releases" \
        | python -c "import json,sys; print(json.load(sys.stdin)['id'])")
    echo "DRAFT_CREATED id=$REL_ID"
else
    echo "REUSING id=$REL_ID"
fi

ASSET_STATE=$(curl -s -H "$AUTH" "$API/releases/$REL_ID" | python -c "
import json,sys
a=[x for x in json.load(sys.stdin).get('assets',[]) if x.get('name')=='$ASSET']
print(a[0]['state'] if a else 'NONE')
")
if [ "$ASSET_STATE" != "uploaded" ]; then
    if [ "$ASSET_STATE" != "NONE" ]; then
        # A 'starter' asset is an incomplete upload; delete it and upload again.
        ASSET_ID=$(curl -s -H "$AUTH" "$API/releases/$REL_ID" | python -c "
import json,sys
print([x for x in json.load(sys.stdin)['assets'] if x['name']=='$ASSET'][0]['id'])")
        curl -s -X DELETE -H "$AUTH" "$API/releases/assets/$ASSET_ID" > /dev/null
        echo "DELETED_INCOMPLETE_ASSET state=$ASSET_STATE"
    fi
    # Uploads must go to the release's upload_url (uploads.github.com), not api.github.com.
    UP_URL=$(curl -s -H "$AUTH" "$API/releases/$REL_ID" | python -c "import json,sys; print(json.load(sys.stdin)['upload_url'].split('{')[0])")
    curl -s -X POST -H "$AUTH" -H "Content-Type: application/vnd.android.package-archive" \
        --data-binary @"$APK" "$UP_URL?name=$ASSET" > /dev/null
    echo "ASSET_UPLOADED"
else
    echo "ASSET_ALREADY_PRESENT"
fi

curl -s -X PATCH -H "$AUTH" -H "Content-Type: application/json" \
    -d '{"draft":false,"prerelease":false,"make_latest":"true"}' "$API/releases/$REL_ID" > /dev/null
echo "RELEASE_PUBLISHED id=$REL_ID"

# Verify through the same public path the app uses. That endpoint is served with
# "Cache-Control: max-age=60", so right after publishing it can still show the previous
# release (seen on v1.36); wait for the cache to turn over before calling it a failure.
URL=""
for attempt in 1 2 3 4 5 6 7; do
    curl -s "$API/releases/latest" > "$TMP/latest.json"
    URL=$(python - "$TMP/latest.json" "$TAG" "$ASSET" 2>/dev/null <<'EOF'
import json, sys
r = json.load(open(sys.argv[1], encoding="utf-8"))
a = [x for x in r.get("assets", []) if x.get("name") == sys.argv[3]]
if r.get("tag_name") == sys.argv[2] and a and a[0]["state"] == "uploaded":
    print(a[0]["browser_download_url"])
EOF
    ) || true
    [ -n "$URL" ] && break
    echo "WAITING for releases/latest to show $TAG (public cache, attempt $attempt)"
    sleep 15
done
URL=$(python - "$TMP/latest.json" "$TAG" "$ASSET" <<'EOF'
import json, sys
r = json.load(open(sys.argv[1], encoding="utf-8"))
a = [x for x in r.get("assets", []) if x.get("name") == sys.argv[3]]
print("LATEST tag=%s state=%s size=%s" % (r.get("tag_name"), a[0]["state"] if a else "MISSING",
      a[0]["size"] if a else "-"), file=sys.stderr)
if r.get("tag_name") != sys.argv[2] or not a or a[0]["state"] != "uploaded":
    sys.exit(1)
print(a[0]["browser_download_url"])
EOF
) || fail "releases/latest is not $TAG with an uploaded $ASSET"
curl -sL -o "$TMP/verify.apk" "$URL"
GOT=$(sha256sum "$TMP/verify.apk" | cut -d' ' -f1 | tr 'a-f' 'A-F')
echo "DOWNLOADED_SHA256=$GOT"
echo "EXPECTED_SHA256=$EXPECTED"
[ "$GOT" = "$EXPECTED" ] || fail "downloaded APK does not match the staged build"
echo "RESULT: PUBLISHED_AND_VERIFIED $TAG"
