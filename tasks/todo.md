# Task Log

> One entry per task. Use the template from `references/examples.md`.
> Mark items [ ] → [x] as you go. Never delete entries — add new ones.

---

<!-- Add tasks below this line -->

## 2026-06-11 — LinkGuard improvements (security, robustness, tests, detection)

Plan: C:\Users\bizbo\.claude\plans\check-my-linkguard-app-magical-simon.md

- [x] 0. Baseline build green (cached Gradle 8.9 dist + JDK 17 Adoptium; 8.4 dist is an incomplete download; bundled JBR 21 breaks AGP 8.1 jlink)
- [x] 1. Move API keys gradle.properties → local.properties; dedupe buildConfigField; remove duplicate ML Kit dep
- [x] 2. allowBackup="false" in AndroidManifest.xml
- [x] 3. Consolidate extractDomain → util/DomainExtractor (orchestrator, HeuristicScanner, 3 providers)
- [x] 4a. Providers log + rethrow instead of silent swallow
- [x] 4b. ScanOrchestrator per-provider 8s timeouts; heuristics outside timeout; track externalCoverageMissing; skip caching unvetted results
- [x] 4c. ScoringEngine confidence downgrade when external coverage missing
- [x] 5. Tagalog/Taglish smishing patterns in HeuristicScanner
- [x] 6. PaymentQrValidator tag/length hardening; close ML Kit scanner clients in QrScannerActivity
- [x] 7. Unit tests written: DomainExtractor, HeuristicScanner, ScoringEngine, PaymentQrValidator, ScanOrchestrator (53 cases)
- [x] 8. Verify: :app:testDebugUnitTest green (54 tests, 0 failures) + :app:assembleDebug green (BuildConfig keys confirmed from local.properties)

## 2026-06-12 — In-app update check via GitHub Releases

- [x] UpdateChecker: GET releases/latest on github.com/pnormzkie/LinkGuard, parse tag/notes/apk asset, numeric version compare vs BuildConfig.VERSION_NAME
- [x] UpdateInstaller: DownloadManager download of the release APK + system install prompt on completion (REQUEST_INSTALL_PACKAGES)
- [x] MainActivity: silent check on app open; "Update Available" dialog with Download / Later (Later = re-asks next open); fallback to release page when no APK asset
- [x] Strings, manifest permission, lifecycle-runtime-ktx dep
- [x] Unit tests: UpdateCheckerTest (version compare + release JSON parsing, 13 cases)
- [x] Verify: tests green (67 total, 0 failures) + assembleDebug green
- [x] On-device (Pixel_7 emulator): no-release path verified — single silent HTTP 404, no crash, app opens normally
- [x] Fix found during device test: onCreate runs twice (DayNight recreate) → added once-per-process guard so the dialog can't stack
- [x] Version bumped to 1.2 (versionCode 3); APKs staged in release-staging\ (v1.1 for device, v1.2 for the GitHub release)
- [x] Published GitHub release v1.2 with APK via user-provided token (repo was empty — initialized with README commit first)
- [x] Full E2E verified on emulator: dialog shown (v1.2 vs v1.1) → Download → DownloadManager (recovered from transient emulator SSL errors) → install prompt → Play Protect scan → installed v1.2 (versionCode 3) → relaunch shows no dialog (already latest)
- [ ] USER ACTION: revoke the GitHub token pasted in chat (github.com/settings/tokens) — still valid as of 2026-06-12
- [x] Release keystore generated at C:\Users\bizbo\.android\linkguard-release.jks (credentials in local.properties); signingConfigs wired into app/build.gradle (conditional on local.properties)
- [x] Release build (R8 minified, 24.4 MB) smoke-tested on emulator: install, launch, full manual scan with Scan Report — no crashes
- [x] v1.2 GitHub release asset replaced with the release-signed APK
- [~] USER ACTION: back up C:\Users\bizbo\.android\linkguard-release.jks + its password — losing it means existing users must uninstall/reinstall forever
      PARTIAL (2026-06-13): local copy + integrity hash + README-RESTORE.txt created at C:\Users\bizbo\LinkGuard-keystore-backup\ (sha256 05D7E9F3...EC6BDF, copy verified). STILL TODO (user): move that folder OFF-machine and store the two keystore passwords in a password manager — a same-disk copy is not a real backup.

## 2026-06-12 — Update UX v1.4 (user feedback: alert only on force-stop; no visible version; no manual check)

- [x] Auto-check moved to onResume with 30-min throttle (AppConfig.UPDATE_CHECK_INTERVAL_MS) — alerts now fire on return-to-foreground, not just cold start; dialog ref guard prevents stacking; dialog dismissed in onDestroy
- [x] Version footer on home screen ("Version X.X • Tap to check for updates"); tap = manual check bypassing throttle → up-to-date/failed snackbar or update dialog
- [x] v1.3 published earlier (update-flow test release); v1.4 (versionCode 5) built, tests green (67), emulator-verified (footer text, manual-check snackbar), published to GitHub
- [ ] Awaiting user confirmation: phone 1.3 → 1.4 update via foreground alert or manual check

## 2026-06-12 — v1.5: Link Shield (click-time interception) + 9 more monitored apps

- [x] LinkNotificationService: added Signal, Line, Gmail, WeChat, KakaoTalk, Telegram X, Messenger Lite, X, TikTok (19 apps total)
- [x] LinkInterceptActivity: registers as browser handler (VIEW http/https); scans tapped link; SAFE → toast + auto-forward to real browser via CATEGORY_APP_BROWSER (loop-proof, chooser fallback excludes self); SUSPICIOUS/DANGER → block dialog with risk score, top flags, Don't Open / Open Anyway; scans saved to history as "Link Tap"; scan-failure fails open with "unverified" toast
- [x] Manifest: queries block for browser discovery; exported intercept activity (dialog theme, excluded from recents)
- [x] v1.5 (versionCode 6) built, 67 tests green, emulator-verified: safe link → Chrome opened; http://gcash-verify.com/claim → blocked (score 100, flags listed)
- [x] Published v1.5 to GitHub
- [ ] User: set LinkGuard as default browser on phone to enable Link Shield; confirm update chain 1.3→1.4→1.5

## 2026-06-12 — UI layer review (android-ui-reviewer) findings

Review scope: app/src/main/java/com/linkguard/app/ui/* + strings.xml (static read-only review; app not run).

- [x] #1 (was flagged S2 → corrected to S4) ThreatAlertActivity used resources.getIdentifier("ic_stat_suspicious") with an ic_dialog_info fallback. NOT a real strip risk — ScanDetailActivity.kt:58 already references R.drawable.ic_stat_suspicious directly, so shrinkResources keeps it. Replaced the runtime lookup with the direct compile-checked R.drawable reference + added the R import. Cleanup, not a bug fix.
- [x] #2 (S2) QrScannerActivity gallery decode moved off the main thread: processImageFromGallery now decodes via withContext(Dispatchers.IO) inside lifecycleScope; ML Kit client created only after a successful decode (no leak on cancel); user messages unchanged. Compiles (compileDebugKotlin green).
- [x] #3 (S2) QrScannerOverlayView rewritten: static scrim/hole/corners pre-rendered to a cached bitmap in onSizeChanged; laser driven by a ValueAnimator (start onAttached / cancel + recycle onDetached); removed the forced full-view LAYER_TYPE_SOFTWARE and the state-mutation+invalidate inside onDraw. Compiles green. RUNTIME VERIFIED on Pixel_7 (API emulator): assembleDebug installed (had to uninstall the prior release-signed build first), drove MainActivity → tapped btnScanQR → QrScannerActivity; two screenshots ~1s apart show the punched rounded hole + 4 green corners + laser at different Y (animator running), no crash. Screens: release-staging\qr_overlay.png, qr_overlay_2.png. Gallery-decode (#2) path not exercised in this run (camera overlay only).
- [x] #4 (was S2 → resolved: NO BUG) VERIFIED in data layer: ScanDao (ScanData.kt:51-75) and ScanRepository (ScanRepository.kt:10-28) are all `suspend`. Room runs suspend DAO queries on its own background executor regardless of the caller's dispatcher, so MainViewModel launching on viewModelScope(Main) does NOT do main-thread DB I/O. No ANR risk. Only residue: post-query .toDomain() Gson mapping resumes on Main, bounded by SCAN_HISTORY_LIMIT=50 rows — negligible, left as-is.
- [x] #5 (S3) Display strings externalized across 6 files into strings.xml (~30 new keys + reuse of existing delete_scan/delete/cancel/no_history_found). DATA values left untouched on purpose: sourceApp/senderInfo/category literals ("QR","Manual","Payment QR","Physical Code") are stored in the DB and compared in code (e.g. ScanDetailActivity app=="QR"), so externalizing them would corrupt rows/break logic. Format strings used for threat_alert_message (4 args), scan_meta_format (2), payment_qr_* (1). Build green (resources compiled, all R.string resolve). RUNTIME VERIFIED on Pixel_7 (screens in release-staging\):
  * ScanDetail SAFE: "SAFE LINK" + Source "Manual Scan" (state.png)
  * ScanDetail DANGER: scanned http://gcash-verify.com/claim → "DANGEROUS LINK" @100% (danger_detail.png) — status_dangerous_link
  * History/Main list: "✓ SAFE" + "⚠ DANGER" badges + "via Manual · <date>" (recent_item.png, clear_dialog.png) — status_badge_safe/danger + 2-arg scan_meta_format
  * History Clear-All dialog: "Clear All History" / "This will permanently delete all scan records. Continue?" / Cancel / Clear All (clear_dialog.png) — clear_all_history_title/confirm/action + cancel
  * QR screen: qr_align_instruction, qr_upload_from_gallery (qr_strings.png)
  Compile-only (same getString mechanism, low risk, not driven): ThreatAlert emergency popup (threat_alert_message 4-arg + titles/prefixes — needs the notification-listener popup), payment_qr_* snackbar (needs a payment QR), SUSPICIOUS variants (status_suspicious_link, status_badge_suspicious, suspicious_link_title), error toasts (camera_permission_denied, qr_none_in_image, qr_read_failed, qr_image_open_error), delete-scan long-press dialog (reuses already-verified strings).
- [x] #6 (S3) QrScannerActivity back button now sets contentDescription=getString(R.string.cd_back). RUNTIME VERIFIED via uiautomator: node content-desc="Back" exposed on the Button (TalkBack will announce it). Layout-XML image-view a11y audit still pending (see note below).
- [x] #7 (S4) QrScannerActivity back button margins now density-independent: topMargin/marginStart = 16.dpToPx() (were raw px 40/20). Compiles green; RUNTIME VERIFIED on Pixel_7 (release-staging\qr_overlay_3.png) — back arrow sits with a consistent inset below the status bar, no longer corner-jammed.
- [x] #9 (S4) MainViewModel.manualScan now sets _isScanning.value = true synchronously on the main thread (called from UI) before launching, closing the check-then-async-postValue double-tap window; reset still via postValue(false). Compiles green.
- Note: #8 (SetupActivity empty placeholder) already tracked below under the v1.5 task's "Unrelated issues."
- NOT covered: res/layout/*.xml not fully reviewed (RTL start/end, contentDescription, sp units); LinkInterceptActivity fail-open-on-scan-error is a security-reviewer item; no runtime/profiler verification.

## 2026-06-12 — Fail-open-on-scan-error: validated + Path-B fix

Validation (LinkInterceptActivity = default-browser link gate):
- Fail-open is JUSTIFIED for a default browser (fail-closed would block all link opening during any outage). It is layered, not blind: local heuristics run offline (ScanOrchestrator.kt:45-48 never throws; providers caught in guarded() :82-96), so known-bad is still blocked with no network. ScoringEngine does NOT downgrade the verdict when externalCoverageMissing — only confidence (ScoringEngine.kt:68-72).
- Safety property is unit-tested and GREEN (:app:testDebugUnitTest, 67 tests / 0 fail): "threat verdict without external coverage is capped at medium confidence" (CRITICAL stays THREAT) + "safe verdict without external coverage has low confidence" (allow-unknown w/ flag).
- Two paths: Path A (providers fail, heuristics run → SAFE-unvetted → open + "local checks only" toast — CORRECT). Path B (scan() / toLegacy() throws → nothing ran).

Bug found + fixed (Path B):
- [x] Path B previously auto-forwarded the link with toast "...local checks only" — misleading (nothing ran) AND silently opened an unchecked link. Now calls showUncheckedScreen(): shows the existing choice screen (tvStatus="Couldn't verify this link" yellow, tvReason=link_unchecked_reason, buttonRow with Don't Open / Open Anyway). Preserves availability (user can still Open Anyway) without silent allow. New strings link_unchecked_title/reason. Path A messaging left unchanged (it was accurate). Build green (assembleDebug).
- Runtime status: Path B not driven at runtime (the orchestrator is hardened against throwing, so forcing it needs code instrumentation — not done). showUncheckedScreen reuses the exact tvStatus/tvReason/buttonRow layout as showBlockScreen. The LinkInterceptActivity block/choice screen itself was not runtime-verified this session.

## 2026-06-13 — Ship-prep validation (v1.6)

- [x] Version bumped: versionCode 6→7, versionName "1.5"→"1.6" (app/build.gradle) — 1.5/code6 was already published.
- [x] Release build GREEN: :app:assembleRelease (R8 minify + shrinkResources + lintVitalRelease all pass). shrinkReleaseRes did NOT strip ic_stat_suspicious (finding #1 fix holds — now directly referenced). Only warning: pre-existing unused 'sender' param in ScanDetailActivity.kt:53 (not mine).
- [x] Release APK signed: app-release.apk 23.3 MB, V2 signer CN=LinkGuard O=pnormzkie C=PH (apksigner verify OK) — same release keystore as prior versions.
- [x] Release SMOKE TEST on Pixel_7 (R8-minified, signed): installed v1.6 (versionCode 7 confirmed via dumpsys), launched MainActivity (all UI renders), ran a full manual scan of google.com → SAFE, persisted (SAFE ITEMS: 1, "Last scan: 0 minutes ago"). Confirms the scan pipeline (heuristics/scoring/Room) survives R8. ScanDetail format strings not re-driven on release (R8 doesn't obfuscate resources; verified on debug).
- [x] PUBLISHED v1.6 to GitHub (2026-06-13, user-authorized to ship WITH keys embedded, same as prior releases — keys already public in v1.1-v1.5 so no new leak). Release id=338704639, tag v1.6, asset LinkGuard-v1.6.apk (23.3MB), published non-draft/non-prerelease. Verified releases/latest=v1.6 → in-app updater will offer it to v1.5 users. https://github.com/pnormzkie/LinkGuard/releases/tag/v1.6
- Remaining USER actions (decoupled from the release): #2 REVOKE the GitHub token used to publish (it was pasted in chat — exposed) — do now. #1 rotate + RESTRICT the 3 API keys (still embedded/extractable in the APK; restriction is the high-value mitigation — see security discussion). #3 finish keystore backup: move C:\Users\bizbo\LinkGuard-keystore-backup\ off-machine + passwords into a password manager. Optional long-term: backend proxy so keys leave the client entirely.

## 2026-06-13 — QA: provider error-semantics tests + DomainExtractor fallback tests

- [x] 1. Provider tests (SB/VT/HA/NextDNS) via OkHttp Interceptor fake in FakeHttp.kt (no new deps): HTTP error, malformed JSON, success parsing, network IOException — 29 new provider tests
- [x] 2. Probed VT network path: NOT JVM-testable — android.util.Base64 stub returns null under returnDefaultValues → NPE before any HTTP ("encodeToString(...) must not be null"). Gap documented in VirusTotalEnrichmentProviderTest header; fix option = refactor to java.util.Base64 (API 26+)
- [x] 3. DomainExtractorTest extended (+7 cases): credentials/scheme/port/case
- [x] 4. ScanOrchestratorTest provider-throw coverage confirmed adequate (all-fail → LOW confidence + EXTERNAL_CHECKS_UNAVAILABLE_REASON; one-fail → siblings collected)
- [x] 5. Full :app:testDebugUnitTest run: 118 tests, 6 failed — ALL 6 are deliberate bug-documenting tests (see below); 112 pass incl. all pre-existing suites

OPEN PRODUCT BUGS exposed by failing tests (hand to debugger; do NOT weaken the tests):
- [x] B1 (S2) SafeBrowsingReputationProvider.kt:39 — non-2xx (400/403/429/500) returns emptyList ⇒ API rejection looks like "checked clean". Test: SafeBrowsingReputationProviderTest."http error status fails loud..."
      FIXED 2026-06-13: non-2xx now throws IOException("Safe Browsing HTTP <code>"); blank 2xx body still = clean.
- [x] B2 (S3) HybridAnalysisProvider.kt:105-117 — 429/500 AND swallowed JSON parse errors return emptyList. (404 = legitimate no-report, kept.) Tests: HybridAnalysisProviderTest."server error status..." / "malformed json fails loud..."
      FIXED 2026-06-13: 404 → empty (kept); other non-2xx → throw IOException; JSON parse no longer swallowed (exception propagates).
- [x] B3 (S3) NextDnsDomainSignalProvider.kt:48-58 — non-2xx resolve ⇒ "not blocked" ⇒ emptyList. Test: NextDnsDomainSignalProviderTest."http error status fails loud..."
      FIXED 2026-06-13: non-2xx throws IOException inside try; known-tracker offline fallback in catch preserved (verified by passing fallback test).
- [x] B4 (S2) DomainExtractor.kt:24-25 fallback doesn't strip userinfo: "https://evil.com@good.com/%%%" → "evil.com@good.com"; "https://user:hunter2@evil.com:8080/%%%" → "user". Tests: DomainExtractorTest."fallback strips embedded credentials" / "fallback strips credentials with password and port"
      FIXED 2026-06-13: fallback now substringAfterLast("@") before substringBefore(":") + lowercase (was already in place from prior partial; verified by passing tests).
      Plus: VirusTotalEnrichmentProvider swapped android.util.Base64 → java.util.Base64 (URL-safe, no padding — identical output, JVM-testable). VT network path now covered (10 tests: success/404/non-2xx-throw/malformed-throw/network-throw). "untestable" KDoc note removed.
      Verify: :app:testDebugUnitTest = 125 tests, 0 failures (was 118 / 6 fail).

Unrelated issues found (not fixed this task):
- Legacy dead code in UrlScanner.kt (MasterScanner, VirusTotalScanner, SafeBrowsingScanner, NextDnsScanner) — candidates for deletion
- SetupActivity is an empty TODO placeholder
- Room DB has no Migration objects (fine at version 1; required before any schema change)
- AppConfig.kt lives at app/src/main/java/util/ instead of .../com/linkguard/app/util/ (compiles; cosmetic)
- API keys shipped in distributed zip — ROTATE VirusTotal / Safe Browsing / Hybrid Analysis keys

## 2026-06-13 — Code-review cleanup/hardening (5 items)

- [x] Item 1 (S2): Deleted dead scanner stack in UrlScanner.kt (VirusTotalScanner, SafeBrowsingScanner, NextDnsScanner, MasterScanner, sharedHttpClient) + now-unused imports. Kept UrlExtractor + HeuristicScanner (live). Verified no external refs; SafeBrowsingRequestBodyTest already targets the live provider.
- [x] Item 2 (S3): ScanOrchestrator cache TTL (15 min, AppConfig.SCAN_CACHE_TTL_MS); CacheEntry(result, cachedAt); expired = miss. Injected now:()->Long clock. Swapped android.util.LruCache → access-ordered LinkedHashMap LRU (Android stub always missed under JVM tests). Added tests: fresh hit (no re-call) / expired re-scan.
- [x] Item 3 (S4): Moved AppConfig.kt to com/linkguard/app/util/; removed empty src/main/java/util/.
- [x] Item 4 (S4): Created util/KnownDomains (union of trusted + tracker). Pointed HA, VT, NextDNS providers at it. VT gained 20 trusted domains (now skips lookup for them); HA/NextDNS unchanged. Provider tests still green.
- [x] Item 5 (S4): Gated full-URL/domain/sender logs behind BuildConfig.DEBUG (ScanOrchestrator, HybridAnalysisProvider, VirusTotalEnrichmentProvider, NextDnsDomainSignalProvider, SafeBrowsingReputationProvider, Receivers, LinkNotificationService). API keys still never logged.
- [x] Verify: :app:testDebugUnitTest = 127 tests, 0 failures (was 125; +2 cache tests). compileDebugKotlin green.

## 2026-06-13 — Code-review cleanup round 2 (5 items)

- [x] Item 1 (S4): Removed no-op BootReceiver (whole Receivers.kt deleted — see Item 2), its <receiver> manifest entry, and the RECEIVE_BOOT_COMPLETED permission. Grepped: no other use of the permission.
- [x] Item 2 (S3): Removed dead SmsReceiver. Only ref to Receivers.kt was the (now-removed) BootReceiver manifest entry; SmsReceiver had no manifest/perm/code refs. With both classes gone Receivers.kt was empty → deleted the file. DismissReceiver lives in util/ (unaffected). UrlExtractor still used by LinkNotificationService.
- [x] Item 3 (S3/security): PROFILE_ID → constructor param `profileId: String = BuildConfig.NEXTDNS_PROFILE_ID`; build.gradle reads NEXTDNS_PROFILE_ID from local.properties (same apiKey() pattern); local.properties NEXTDNS_PROFILE_ID=2d1fa2. Blank ⇒ skip resolve, offline tracker fallback (known tracker) else empty. Copy fixed: dropped "your NextDNS privacy settings (Profile: ...)" → "matches LinkGuard's ad/tracker filter list" / "LinkGuard's DNS threat-intelligence check". Test factory pins profileId="testprofile"; +2 blank-profile tests. NextDNS suite 9→11, 0 fail.
- [x] Item 4 (S4): Added trust-boundary KDoc to LinkInterceptActivity. Invariant VERIFIED — only the SAFE branch auto-forwards and it shows a user-visible safe/unverified toast first; null (unchecked) and blocked verdicts require an explicit tap. No silent-forward path found; no behavior change.
- [x] Item 5 (S3): Added btnCancelScan (outlined style, matches btnOpenAnyway neighbor) visible during scanning; cancels scanJob + finishes WITHOUT opening. onBackPressed == Cancel while scanning. Cancel hidden once block/unchecked verdict UI shows. New string btn_cancel_scan="Cancel".
- [x] Verify: :app:testDebugUnitTest = 129 tests, 0 failures/0 errors (was 127; +2 NextDNS). :app:assembleDebug BUILD SUCCESSFUL (manifest/layout/strings link clean). compileDebugKotlin green (only pre-existing HistoryActivity/ScanDetailActivity warnings).

## 2026-06-13 — NextDNS provider: profile was never applied (root-cause fix)

User report: "parang di gumagana yung provider detection."

- [x] Diagnosis: SB/VT/HA verified working end-to-end (live pipeline smoke: THREAT 100 on Google's phishing test URL; keys valid; keys baked into published v1.7 APK). Real defect isolated to NextDNS.
- [x] Root cause: dns.nextdns.io/resolve (JSON) ignores the profile entirely — bogus profiles return identical unfiltered answers, so "Blocked by NextDNS" API detection never fired.
- [x] Fix: switched provider to the RFC 8484 DoH wireformat endpoint dns.nextdns.io/{profile}; blocked = sinkhole A record (0.0.0.0/127.0.0.1) or RFC 8914 EDE filtered option (codes 15-18; NextDNS sends 17). Wireformat build/parse are pure companion functions, unit-tested.
- [x] Second bug found by live probe and fixed: bare NXDOMAIN (nonexistent domain) was flagged as "Blocked by NextDNS" — false positive; now requires EDE/sinkhole evidence.
- [x] Verify: NextDNS suite 15/15; full suite 133/133; assembleRelease green; live probe — doubleclick.net→NEXTDNS_BLOCK, example.com→clean, nonexistent domain→no signal.
- [x] Shipped in v1.8 (published 2026-06-13, tag v1.8)

## 2026-06-14 — PLAN: expand auto-scan beyond the 19-app allowlist ("any messaging app")

Status: PLAN ONLY (not started). Risk: HIGH — NotificationListenerService reads message
content (PII), sensitive permission, third-party API quotas.

Problem: `LinkNotificationService.kt:30,64` auto-scans notification links only from a
hardcoded `MONITORED_PACKAGES` set of 19 apps; anything else returns early at :64. The
tap path (`LinkInterceptActivity`, browser intent-filter) is already app-agnostic; only
the AUTOMATIC notification path is limited.

Chosen approach (recommended): denylist-by-default + user toggle. NOT a blind "scan
everything" — that risks Google Play notification-access policy + VirusTotal free-tier
quota (~4 req/min, ~500/day). Default state = (B) keep current 19 as seed, with an easy
"enable all apps" opt-in (opt-in is easier to defend in Play review / for privacy).
Existing safeguard to lean on: ScanOrchestrator has an LRU+TTL dedup cache
(`ScanOrchestrator.kt:33-59`, 200 entries / 15 min) so repeated same-URL scans don't
re-hit providers — but unique-URL floods still need Phase 3.

Phase 1 — core coverage  [DONE 2026-06-14]
- [x] Replaced the `MONITORED_PACKAGES` allowlist gate in LinkNotificationService with a
      denylist via NotificationFilter; removed the 19-package set. getAppLabel mapping kept
      (still has the else-fallback for unknown apps).
- [x] New pure `util/NotificationFilter.shouldScan(packageName, ownPackage, isOngoing,
      isGroupSummary)` — excludes self (loop guard), android/systemui/vending/gms; explicit
      list (no broad com.android.* prefix) so com.android.mms SMS still scans.
- [x] Harden: skip ongoing (sbn.isOngoing) and FLAG_GROUP_SUMMARY notifications.
- [x] Tests: app/src/test/.../util/NotificationFilterTest.kt (8 cases). Verify:
      :app:testDebugUnitTest = 141 tests, 0 failures/0 errors (was 133; +8). compileDebugKotlin green.
- NOTE: Phase 1 makes the default behaviour scan-all-except-excluded. The (B) seed-19 default
      + opt-in toggle is Phase 2 (MonitorPreferences/SetupActivity) and not yet wired.

On-device smoke (2026-06-14, Pixel_7 / API 34 emulator, debug build, notification listener
granted via `cmd notification allow_listener`):
- [x] PASS — posted a notification from pkg=com.android.shell (a package NEVER in the old
      19-app allowlist). LinkNotificationService scanned it end-to-end:
      "ScanOrchestrator: HA Search (...): HTTP 200" → "Signals Found -> SB:0,DNS:0,VT:0,HA:0"
      → non-SAFE verdict → ThreatAlertActivity launch attempted. Proves the denylist now
      covers apps outside the old 19. No crash.
- Test-harness gotcha (for on-device-smoke skill): `cmd notification post <tag> <text>`
      TRUNCATES the body at the first space (android.text became "pakitingnan"/"nanalo"/
      "verify"), so a URL in the body never reaches extras. Put the URL in the TITLE (-t,
      no spaces) — confirmed via `dumpsys notification --noredact`. Earlier empty-log runs
      were this, not a code defect.
- Evidence screenshot: tasks/phase1-smoke.png.

Unrelated observation (NOT Phase 1; do not fix here):
- On API 34 the background startActivity for ThreatAlertActivity was BAL_BLOCK'd
  ("Background activity launch blocked … ThreatAlertActivity"). The emergency full-screen
  popup may not launch from the background service on Android 14; the high-priority
  heads-up notification (USE_FULL_SCREEN_INTENT) is the sanctioned path. Worth a separate
  look — affects all scan sources, not just the widened set.

Phase 2 — user control + transparency (required to ship)  [DONE 2026-06-14]
- [x] New `util/MonitorPreferences.kt` (SharedPreferences "linkguard_monitor_prefs"):
      `scanAllApps` boolean, default FALSE = seed-19 mode (recommendation B).
- [x] NotificationFilter: added DEFAULT_MESSAGING_PACKAGES (the 19) + pure
      `isWithinScope(packageName, scanAllApps)` = scanAllApps || pkg in seed.
- [x] Service applies the scope gate AFTER the universal shouldScan exclusions
      (LinkNotificationService reads MonitorPreferences.scanAllApps).
- [x] UI: SwitchMaterial "Scan links from all apps" in the protection card
      (activity_main.xml) wired in MainActivity (state set before listener so restore
      doesn't fire snackbar); 4 new strings. NOTE: put the toggle on the main screen's
      protection card (the real settings hub) rather than the empty SetupActivity
      placeholder — matches the existing no-menu, card-based UI.
- [x] Tests: +4 isWithinScope cases. Verify: :app:testDebugUnitTest = 145 tests,
      0 failures (was 141; NotificationFilterTest 8->12). :app:assembleDebug green
      (layout/ViewBinding/strings link clean).
- NET EFFECT: default is back to seed-19 (privacy-safe). The "any app" coverage from
      Phase 1 is now opt-in via the toggle. Per-app overrides intentionally deferred.

Phase 3 — guardrails (anti-quota/abuse)
- [ ] Service-layer per-window rate-limit / scanned-URL dedup (on top of orchestrator cache)
      so a burst of UNIQUE urls can't blow a provider's daily quota.
- [ ] Coalesce duplicate notification re-posts.

Affected files: `service/LinkNotificationService.kt`; NEW `util/NotificationFilter.kt`,
`util/MonitorPreferences.kt`, `app/src/test/.../NotificationFilterTest.kt`;
`ui/SetupActivity.kt` + `res/layout` + `res/values/strings.xml`; optional `getAppLabel`
(:106) PackageManager label for unknown apps.

Landmines (verified in code):
- CRITICAL self-scan loop: LinkGuard's own threat-alert notifications contain the URL →
  must exclude own package or it scans its own alerts forever.
- PII: wider coverage = more URLs sent to VirusTotal/SafeBrowsing/HybridAnalysis. Keep
  `messageText` LOCAL-only (orchestrator passes url/domain to network providers, not the
  message body — `ScanOrchestrator.kt:72-75`).
- Noise/battery: many non-messaging apps put URLs in notifications → denylist + ongoing-skip.

Tests (match-to-risk): NotificationFilter unit (own ✗ / system ✗ / unknown messaging ✓ /
ongoing ✗); dedup/rate-limit unit; on-device smoke (new on-device-smoke skill) sending a
link from an app NOT in the old 19 (e.g. Slack/Teams) + confirm no self-loop.

Rollback: gated behind toggle, change localized to the service → `git revert`.
