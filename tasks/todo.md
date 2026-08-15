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
- [x] On-device verified (Pixel_7/API 34): toggle OFF (default) → non-seed com.android.shell
      notification NOT scanned (0 scan lines); tapped the switch ON via uiautomator →
      same notification scanned (HA HTTP 200, Signals Found). Confirms UI and service share
      the same SharedPreferences instance (no restart needed).

Phase 3 — guardrails (anti-quota/abuse)  [DONE 2026-06-14]
- [x] New `util/ScanRateLimiter.kt` — sliding-window limiter (default 15 scans / 60s,
      injected clock, @Synchronized). Wired into LinkNotificationService: tryAcquire() per
      URL before launching a scan; over-cap URLs are skipped (debug-logged). Scope: ONLY the
      automatic notification path — tapped/manual scans don't go through it.
- [x] Coalesce duplicate notification re-posts: already provided by ScanOrchestrator's
      15-min verdict cache (same url+messageText = cache hit, no network). Documented in
      ScanRateLimiter KDoc; not re-implemented (would duplicate the cache and change the
      re-alert-on-repost behavior).
- [x] Tests: app/src/test/.../util/ScanRateLimiterTest.kt (4 cases: cap, block-over-cap,
      window-slide, partial-slide). Verify: :app:testDebugUnitTest = 149 tests, 0 failures
      (was 145; +4). compileDebugKotlin green.
- FUTURE (not done): a PERSISTENT daily cap (survives process restart, via SharedPreferences)
      for tighter alignment with VirusTotal's ~500/day. Current limiter is in-memory per
      process — guards acute floods, resets on restart.

## 2026-06-14 — Release v1.9 (versionCode 10): any-app scanning + toggle + rate limit

- [x] Bumped versionCode 9->10, versionName "1.8"->"1.9" (app/build.gradle). Pre-flight: live
      latest was v1.8, no drift.
- [x] Signed release build GREEN: :app:assembleRelease (R8 + shrinkResources + lintVitalRelease).
- [x] APK validated: apksigner cert SHA-256 = ead80ea1...74227357 (same release keystore);
      aapt versionCode=10 versionName=1.9 package com.linkguard.app. Local SHA-256 =
      32a756357544b769a379ceecb1aa178b221146d81ec5841cc73a2c917c685dee, size 24446131.
- [x] PUBLISHED to GitHub (release id=339131067, tag v1.9, asset LinkGuard-v1.9.apk).
      Upload note: curl AND PowerShell HttpClient repeatedly reset on the 24MB body in this
      session; small API calls fine. Set release to DRAFT during retries (no broken update
      prompt to users), then a curl retry succeeded; un-drafted + make_latest=true.
- [x] Live-verified: asset state=uploaded, server digest == local SHA-256; releases/latest=v1.9;
      public latest/download re-downloaded (resumable, network truncated large GETs too) and
      re-hashed IDENTICAL (24446131 bytes, 32a756...685dee). In-app updater will offer to v1.8.
- [x] Staged signed APK at release-staging/LinkGuard-v1.9.apk (repo convention).
- [ ] USER ACTION: REVOKE the GitHub PAT used to publish (pasted in chat — exposed). Do now.
- Standing (unchanged): rotate/restrict the 3 embedded API keys; API 34 BAL_BLOCK on
  background ThreatAlertActivity launch (separate issue).

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

## 2026-06-18 — v1.10: alert reliability + detection coverage + quota robustness

Plan: C:\Users\bizbo\.claude\plans\question-ano-pa-ang-idempotent-sketch.md
Scope (locked): safe-slice + network items. OUT: redirect/shortener resolution.

Phase A — alert delivery reliability (Android 13/14+)  [CODE DONE 2026-06-18; on-device PENDING]
- [x] A1. Request POST_NOTIFICATIONS at runtime (API 33+) from MainActivity. Root cause:
      manifest declared it but it was NEVER requested (grep = 0 hits) → no alert at all on
      13+ when denied. maybeRequestNotificationPermission() (onResume, once/process, only
      when listener is on) + requestNotificationPermission() from the gap dialog.
- [x] A2. canUseFullScreenIntent() on SDK>=34; if false, openFullScreenIntentSettings()
      deep-links to Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT (falls back to app
      notification settings).
- [x] A3. New pure util/AlertCapabilities.kt (sdkInt, notificationsEnabled, canUseFsi) →
      gaps; AlertCapabilitiesTest (6 cases) green.
- [x] A4. Surface gaps via showAlertDeliveryDialog() when protection is on (tap status).
      ThreatAlertHelper KDoc clarifies direct startActivity is best-effort; FSI notification
      is the guaranteed path. No manifest change (both perms already declared).
- [x] A-verify. ON-DEVICE Pixel_7 / API 34 (Android 14), debug APK, 2026-06-18:
      * Baseline: POST_NOTIFICATIONS granted=false at install (confirms the root-cause gap);
        USE_FULL_SCREEN_INTENT manifest-granted.
      * Enabled listener → launched MainActivity → POST_NOTIFICATIONS runtime dialog appeared
        ("Allow LinkGuard to send you notifications?"); tapped Allow → granted=true (appop allow).
        Proves the request now fires (it never did before). Screenshot tasks/phaseA-perm-prompt.png.
      * FSI gap: appops set USE_FULL_SCREEN_INTENT ignore → tapped Protection: ACTIVE →
        gap dialog "Threat alerts may not reach you" + full-screen message + "Full-screen alerts"
        action → deep-linked to com.android.settings/.AppManageFullScreenIntent. Screenshot
        tasks/phaseA-fsi-gap-dialog.png. (With FSI allowed, no dialog — correct.)
      * E2E: scanAllApps ON → posted notification title=http://gcash-verify.com/claim (pkg shell)
        → LinkGuard scanned (heuristics → DANGER) → posted alert on channel linkguard_danger_v3
        (importance HIGH, category=alarm, "⚠️ DANGEROUS LINK DETECTED") AND ThreatAlertActivity
        full-screen Displayed (+514ms, dismiss-keyguard) — NOT BAL-blocked once POST_NOTIFICATIONS
        is granted. In-app THREATS=1, "Last scan: 0 minutes ago". Screenshot tasks/phaseA-threat-alert.png.
      NOTE: the v1.9-era BAL_BLOCK observation was without the notification permission; with the
      Phase A grant flow the full-screen alert now launches on API 34. Phase A: VERIFIED.

Phase B — detection coverage, local (UrlScanner.kt only)  [DONE 2026-06-18]
- [x] B1. Rule 6b: xn-- punycode (+25, escalate +45 if java.net.IDN.toUnicode contains a
      brand) + hasMixedScript() via Character.UnicodeScript (+45 for Latin+Cyrillic/Greek
      in one label). All-one-script IDNs NOT flagged (avoids legit-IDN false positives).
- [x] B2. SMISHING_PATTERNS +9: courier release/processing/customs/shipping fee + parcel
      on-hold + bayad-padala; WFH/earn-daily/hiring; device-hacked/virus-detected/call-support.
- [x] B3. HeuristicScannerTest +7 (punycode, Cyrillic homoglyph, ascii negative, courier,
      WFH, tech-support, benign delivery negative). HeuristicScannerTest suite green.

Phase C — detection coverage, network (RDAP domain age)  [DONE 2026-06-18]
- [x] C1. DomainAgeProvider: rdap.org/domain/{registrable}; reads 'registration' event;
      skip KnownDomains.isTrusted; <7d STRONG(45)/7-30d WEAK(20); 404=no signal; other
      non-2xx + malformed JSON + network = fail loud; reuse SignalSource.DOMAIN_SIGNAL.
      registrableDomain() reduces to eTLD+1 with a small second-level-TLD list (com.ph, co.uk…).
- [x] C2. Wired 5th provider (daDeferred) into ScanOrchestrator (in awaitAll/coverage/log);
      registered DomainAgeProvider(okHttpClient) in ScannerProvider.
- [x] C3. DomainAgeProviderTest (9 cases, FakeHttp seam). Updated ScanOrchestratorTest
      orchestrator() helper (+domainAge param) and all-fail test (domainAge=failing too,
      since an empty domain-age result counts as coverage present). Both suites green.

Phase D — robustness / quota  [DONE 2026-06-18]
- [x] D1. DailyScanCounter (Store seam + injected clock; SharedPreferences via create();
      DEFAULT_MAX_PER_DAY=400; UTC-midnight rollover). Gated in LinkNotificationService AFTER
      the sliding window; automatic path only (manual/tapped never call it).
- [x] D2. RetryInterceptor on okHttpClient (ScannerProvider): one retry on connection
      IOException + 5xx; NEVER 4xx/429; injected sleeper for tests.
- [x] D3. DailyScanCounterTest (3: cap, rollover, restart-persistence) +
      RetryInterceptorTest (5: 5xx retried, 429 not, 4xx not, 200 not, IOException retried+propagates).

VERIFY (2026-06-18): :app:testDebugUnitTest = 179 tests, 0 failures/0 errors (was 149; +30:
AlertCapabilities 6, HeuristicScanner +7, DomainAgeProvider 9, DailyScanCounter 3,
RetryInterceptor 5). :app:assembleDebug BUILD SUCCESSFUL (manifest/strings/resources link
clean, APK packaged). Cached Gradle 8.9 + Adoptium JDK 17, unsandboxed.

ON-DEVICE (2026-06-18): Phase A VERIFIED on Pixel_7 / API 34 (see A-verify above) — perm
prompt, FSI gap dialog + deep-link, and full E2E DANGER alert (notification + full-screen)
all confirmed. Restored emulator state (notifications granted, FSI appop=allow).

RESIDUAL / NOT DONE:
- Phase C live RDAP probe (fresh vs old domain; outage → coverage-missing not false-SAFE) not
  run this session — covered by unit tests only.

## 2026-06-18 — Release v1.10 (versionCode 11): SHIPPED + PUBLISHED

- [x] Bumped versionCode 10→11, versionName "1.9"→"1.10" (app/build.gradle). Pre-flight:
      live latest was v1.9, no drift. isNewerVersion is part-wise int compare → [1,10]>[1,9] ✓.
- [x] Signed release GREEN: :app:assembleRelease (R8 + shrinkResources + lintVitalRelease).
- [x] APK verified: aapt versionCode=11 versionName=1.10; apksigner V2 cert SHA-256
      ead80ea1…74227357 (SAME release keystore as v1.9 → existing users can update);
      size 24453063, SHA-256 3039F844775D4397BA39CC0AAC3FEC068AD9FF9871135E9AA6B2A0CABEF6E13C.
- [x] Staged release-staging/LinkGuard-v1.10.apk + RELEASE-NOTES-v1.10.md. Committed (5f4eb8f).
- [x] PUBLISHED to GitHub pnormzkie/LinkGuard: created draft → uploaded asset (state=uploaded)
      → un-drafted + make_latest. release id=341387174, tag v1.10.
- [x] Verified: releases/latest=v1.10; public download re-hashed IDENTICAL (24453063 bytes,
      3039F844…E13C). In-app updater will offer v1.10 to v1.9 users.
      https://github.com/pnormzkie/LinkGuard/releases/tag/v1.10
- [ ] USER ACTION: REVOKE the GitHub PAT pasted in chat (github.com/settings/tokens) — exposed.
- Standing (unchanged): rotate/restrict the 3 embedded API keys; off-machine keystore backup.

## 2026-06-19 — Block-screen redesign (level-aware) + Scan Detail flag consistency

Approved direction (memory: block-screen-redesign-pending). User answers (2026-06-19):
implement the redesign; apply level-aware flag rows to BOTH the block screen AND Scan Detail.
Risk: LOW (UI-only). Scope: do NOT touch scan pipeline, ScoringEngine, providers, mapper,
repository, or button behavior. Mockup: tasks/mockup-block-screen-v2.png.

- [x] 1. Redesigned activity_link_intercept.xml to the Scan Detail visual language: status
      badge [icon + label] → score ring (% + RISK) → URL chip (TAPPED LINK, mono) → WHY THIS
      WAS FLAGGED + up to 3 flag rows → unchanged buttons. Kept scanning state (progress + url
      + Cancel) and unchecked state (badge + chip + reason).
- [x] 2. Shared ui/FlagStyle.kt: styleFlagRow(ItemFlagBinding, color) tints icon, text, and
      the bg_flag_item glow (fill+stroke) to the verdict color via the runtime GradientDrawable
      (no new drawables; covers red/yellow/green).
- [x] 3. LinkInterceptActivity: showBlockScreen() level-aware (yellow SUSPICIOUS / red DANGER);
      showUncheckedScreen() reuses the new layout (badge + chip + reason, no ring/flags).
      Reuses status_suspicious_link / status_dangerous_link. hideScanningState() helper.
- [x] 4. ScanDetailActivity.setupFlags() now calls styleFlagRow → flag ICON + background are
      level-aware too (text was already level-colored; icon/bg were hardcoded red).
- [x] 5. New strings: link_tapped_label, risk_label, why_flagged_header.
- [x] 5b. BUG FOUND & FIXED during on-device verify (S2): the taller DANGER verdict (3 flags)
      overflowed the wrap_content dialog window and pushed DON'T OPEN / OPEN ANYWAY off-screen
      (unreachable — no scroll). Wrapped the layout in a ScrollView so decision buttons are
      always reachable. Re-verified: buttons reachable after a short scroll.
- [x] 5c. Button label fit (user-reported S4): the two weighted buttons truncated/wrapped
      ("OPEN ANYWAY" → "OPEN", or 2-line wrap). Fixed by trimming default insets + 4dp padding
      and autosizing text (maxLines=1, autoSize 11–14sp uniform) so both labels render full on
      one line, no truncation, density-safe. Verified on-device (suspicious + danger).
- [x] 6. Verify: :app:assembleDebug GREEN; :app:testDebugUnitTest GREEN (no logic change).
      On-device Pixel_7/API34 (debug): block SUSPICIOUS (yellow, tasks/block-screen-suspicious.png),
      block DANGER (red + buttons reachable, tasks/block-screen-danger.png), Scan Detail
      SUSPICIOUS (yellow flag row, tasks/scandetail-suspicious.png), Scan Detail DANGER
      (red flags, no regression, tasks/scandetail-danger.png).
- Residual: unused strings link_blocked_title / link_suspicious_title / link_risk_format are
      now dead (kept to stay surgical; shrinkResources strips them on release). Unchecked
      (scan-failed) screen not driven at runtime — orchestrator is hardened against throwing;
      it reuses the same verified badge/chip layout.

## 2026-06-19 — Block-screen button model: verdict-aware actions (user UX decisions)

User design decisions: (1) SAFE → show a green verdict card with Open Link / Close instead
of silent auto-open, so an accidental tap can be backed out of; (2) DANGER → no co-equal
"Open Anyway" — demote it to a confirm-gated low-emphasis link. Risk: MEDIUM (changes the
default-browser SAFE auto-forward behavior; security-sensitive trust boundary). UI/flow only.

- [x] Buttons reworked to role-based btnPrimary (cyan, recommended action) / btnSecondary
      (outlined, alternative), wired per-verdict instead of fixed Don't Open/Open Anyway.
- [x] SAFE: showSafeScreen() — green badge (ic_stat_safe) + green ring + chip + "passed
      safety checks" / "local checks only" note; Open Link (cyan) + Close (neutral text_muted).
      NO auto-open anymore. Removed the SAFE Toast + openInBrowser auto-forward in scanAndDecide.
- [x] DANGER: btnSecondary hidden; new tvDangerOverride link ("I understand the risk — open
      anyway", text_muted) → AlertDialog confirm (danger_confirm_title/message, Cancel /
      Open Anyway) before opening. SUSPICIOUS + unchecked keep co-equal Don't Open / Open
      anyway (red), no confirm.
- [x] Trust-boundary KDoc updated: activity now NEVER auto-opens; every verdict (incl. SAFE)
      requires an explicit tap. Strengthens the no-silent-forward invariant.
- [x] New strings: btn_open_link, btn_close, link_safe_verified, link_safe_local_only,
      link_danger_override, danger_confirm_title, danger_confirm_message. Now-dead:
      link_safe_toast, link_unverified_toast (SAFE no longer toasts).
- [x] Verify: :app:assembleDebug GREEN; :app:testDebugUnitTest GREEN. On-device Pixel_7/API34:
      SAFE (google.com) green card Open Link/Close, no auto-open (tasks/screen-safe.png);
      SUSPICIOUS two co-equal buttons (tasks/screen-suspicious.png); DANGER dominant Don't Open
      + demoted override (tasks/screen-danger.png) → confirm dialog (tasks/screen-danger-confirm.png).
- DEFERRED (optional, not requested to build now): "Auto-open safe links" preference for
      browser-mode users who want zero friction on safe taps.

- [x] FOLLOW-UP (user-reported): (a) SAFE buttons were clipped ("putol") at the bottom and
      (b) SUSPICIOUS buttons looked uneven. Root causes: (a) the floating
      Theme.AppCompat.DayNight.Dialog window caps content height and clips the last row at
      rest; (b) per-button autosize gave the two labels different text sizes. Fix:
      * New Theme.LinkGuard.Intercept (full-screen, translucent, NON-floating) + manifest
        switch for LinkInterceptActivity only (ThreatAlertActivity untouched).
      * Layout = FrameLayout scrim (#CC000000) → centered rounded ScrollView card
        (new drawable bg_intercept_card) → content. Short verdicts center fully; tall ones
        scroll. No more clipping.
      * Buttons restructured to a VERTICAL stack, full-width (match_parent), fixed 15sp,
        removed autosize/insets/maxLines hacks → always equal, full labels, no truncation.
      Verified on-device: SAFE (screen-safe.png), SUSPICIOUS (screen-suspicious.png),
      DANGER (screen-danger.png) — all centered, buttons equal and fully visible.
      :app:assembleDebug + :app:testDebugUnitTest GREEN.

## 2026-06-19 — PLAN: grouped + collapsible flags by category (block + Scan Detail)

User idea (image): group flags by category ("Heuristic", "Vendors flagged") with a count;
default collapsed; tap a group → expand its detail flags. User chose: plan + mockup first
(mockup rendered → tasks/mockup-grouped-flags.png / .html), scope = block screen + Scan Detail,
HISTORY STAYS FLAT (no DB migration). Risk: MEDIUM-low (additive mapper/model + UI; NO change
to scoring/verdict/providers/DB schema).

Data finding (verified): per-flag category already exists upstream — domain/model/ScanSignal.kt
has `source: SignalSource` (LOCAL_HEURISTIC / EXTERNAL_REPUTATION / ENRICHMENT / DOMAIN_SIGNAL).
ScoringEngine keeps full `signals` in ScanVerdict, BUT ScanMapper.toLegacy() (line 22) FLATTENS
to `flags: List<String>` (secondaryReasons = signal titles), dropping the source. The legacy
data/ScanData.kt ScanResult + Room entity only store flat strings. So grouping needs the source
carried through the mapper to the UI (it is lost today).

Category mapping (proposed): LOCAL_HEURISTIC → "Heuristic"; EXTERNAL_REPUTATION + ENRICHMENT →
"Vendors flagged"; DOMAIN_SIGNAL → "Domain checks"; the EXTERNAL_CHECKS_UNAVAILABLE note → its
own muted line (not a group).

Plan — DONE 2026-06-19 (user: "Go, make no mistake"; auto-expand-when-single approved):
- [x] 1. Model: FlagGroup(category, items) (@Parcelize) + optional flagGroups: List<FlagGroup>
      = emptyList() on data/ScanResult. NOT persisted (toEntity/toDomain untouched) → no DB
      migration. (data/ScanData.kt)
- [x] 2. Mapper: toLegacy() builds flagGroups from verdict.signals grouped by source→label
      (LOCAL_HEURISTIC→Heuristic; EXTERNAL_REPUTATION+ENRICHMENT→Vendors flagged;
      DOMAIN_SIGNAL→Domain checks), items = distinct titles, order [Heuristic, Vendors flagged,
      Domain checks]. Flat `flags` (secondaryReasons) unchanged. (domain/mapper/ScanMapper.kt)
- [x] 3. UI shared: item_flag_group.xml (header: icon + category + count pill + chevron) +
      item_flag_group_line.xml + ic_chevron + bg_count_pill drawables. addFlagGroup() /
      addFlagNote() in ui/FlagStyle.kt — level-tinted, tap toggles items + rotates chevron.
- [x] 4. LinkInterceptActivity.populateFlags(result) + ScanDetailActivity.setupFlags(flags,
      groups): groups non-empty → collapsible groups (lone group auto-expands; 2+ start
      collapsed) + ungrouped flat flags shown as muted notes; else → flat list (history).
      ScanDetailActivity.newIntent now passes Extras.FLAG_GROUPS (parcelable list);
      AppConfig.Extras.FLAG_GROUPS added.
- [x] 5. Tests: ScanMapperTest (6 cases: heuristic-only, vendor merge, mixed-order, empty,
      dedupe, flat-flags-unchanged). Full suite 185 tests, 0 fail (was 179; +6).
- [x] 6. Verify: assembleDebug + testDebugUnitTest GREEN. On-device Pixel_7/API34:
      block DANGER → Heuristic(4) group auto-expanded (grp-block-collapsed.png); tapped a
      vendors group → COLLAPSED, chevron ▸ (grp-vendors-collapsed.png); fresh manual scan →
      Scan Detail grouped (grp-detail.png); DB history item → FLAT fallback, no crash
      (hist-detail.png). Summary: grouped-flags-states.png.
- NET: history scans stay flat (no category persisted) as agreed; fresh scans (block + detail)
      group by category, default-collapsed except a lone group. No scoring/verdict/provider/DB
      change. Tap target = whole header row; expand state is session-only (not persisted).

## 2026-06-19 — Release v1.11 (versionCode 12): SHIPPED + PUBLISHED

- [x] Bumped versionCode 11→12, versionName "1.10"→"1.11". Pre-flight: live latest was v1.10,
      no drift. isNewerVersion part-wise: [1,11] > [1,10] ✓.
- [x] Signed release GREEN: :app:assembleRelease (R8 + shrinkResources + lintVitalRelease).
- [x] APK verified: aapt versionCode=12 versionName=1.11; apksigner V2 cert SHA-256
      ead80ea1…74227357 (SAME release keystore → existing users update in place); size 24466356,
      SHA-256 4daa63fda86a0f5eb2ce25c033a70f8a61fe546b8e373f16c60b9b14cd46eef0.
- [x] Release SMOKE on Pixel_7/API34 (R8-minified, signed): installed v12, launched MainActivity,
      drove link-tap block screen → DANGER 100% with grouped Heuristic(4) collapsible flags +
      confirm-gated override. Proves R8 keeps Parcelable FlagGroup/ViewBinding/scoring. (rel-block.png)
- [x] Committed 64810da; staged release-staging/LinkGuard-v1.11.apk + RELEASE-NOTES-v1.11.md.
- [x] PUBLISHED to GitHub pnormzkie/LinkGuard: draft (id 341945358) → uploaded asset
      (state=uploaded, size match) → un-drafted + make_latest. tag v1.11.
- [x] Verified: releases/latest=v1.11; public asset re-downloaded byte-identical (24466356 bytes,
      4daa63fd…46eef0). In-app updater will offer v1.11 to v1.10 users.
      https://github.com/pnormzkie/LinkGuard/releases/tag/v1.11
- [ ] USER ACTION: REVOKE the GitHub PAT pasted in chat (github.com/settings/tokens) — exposed.
- Standing (unchanged): rotate/restrict the 3 embedded API keys; off-machine keystore backup.

## 2026-06-19 — v1.12: specific vendor names + history grouping (DB migration)

User feedback on v1.11: "Vendors flagged 1" vs "2 Vendors Flagged" is confusing — show the
SPECIFIC vendors when tapped; and make History grouped too. User approved a sample mockup
(tasks/mockup-vendor-names.png) + safe DB migration.

- [x] Vendor names: VirusTotalEnrichmentProvider now parses last_analysis_results, collects the
      engine names with category=="malicious" → metadata["vendor_names"] (joined "||"). Title
      summary kept as fallback. No scoring change (still one VT signal).
- [x] Mapper: FlagGroup gained a true `count` (header stays honest when the list is capped).
      toFlagGroups expands a signal's vendor_names into named items; GROUP_ITEM_CAP=8 then a
      "+N more" line. Heuristic/Domain signals still contribute their title.
- [x] Bug caught on-device + fixed: the VT title ("2 Vendors Flagged") was showing as a
      redundant orphan note below the group (group items are now engine names, so the title
      wasn't "covered"). Changed both screens to surface ONLY the
      EXTERNAL_CHECKS_UNAVAILABLE_REASON meta-note outside groups.
- [x] History grouping via SAFE DB migration: ScanHistoryEntity gained nullable `flagGroups`
      TEXT; DB version 1→2 with MIGRATION_1_2 = `ALTER TABLE scan_history ADD COLUMN flagGroups
      TEXT` (non-destructive). toEntity serializes (null when empty), toDomain deserializes
      (null/parse-error → empty → flat fallback). Nullable column matches Room's expected schema
      so migration validation passes.
- [x] Tests: +7 → 192 total, 0 failures. VirusTotalEnrichmentProviderTest (engine-name capture,
      missing-results), ScanMapperTest (vendor expansion, +N-more cap), new ScanDataMapperTest
      (round-trip, empty→null, pre-v2 null→empty).
- [x] ON-DEVICE (Pixel_7/API34) on the REAL upgrade path — installed release v1.12 OVER release
      v1.11 (same key, in-place): MIGRATION_1_2 ran, THREATS count preserved (1→1), no crash.
      gov-ltms block screen → "Vendors flagged (2)" → Bfore.Ai PreCrime / Seclookup (live VT),
      no redundant note (vendor-block2.png). New scan grouped in History detail (hist-grouped.png).
      Old pre-v2 gcash row → flat fallback, no crash (hist-flat.png).
- [x] Release v1.12 (versionCode 13): assembleRelease GREEN (R8+shrink+lintVital); aapt
      versionCode=13 versionName=1.12; apksigner V2 cert ead80ea1…74227357 (same key); size
      24470456, SHA-256 fd1d7649bd9dba4fa174954792a25c86ca1375d1abccdbf380b596da224db244.
      Staged release-staging/LinkGuard-v1.12.apk + RELEASE-NOTES-v1.12.md.
- [x] PUBLISHED to GitHub (release id 342004410, tag v1.12, make_latest). releases/latest=v1.12;
      public download re-hashed IDENTICAL (24470456 bytes, fd1d7649...db244).
      https://github.com/pnormzkie/LinkGuard/releases/tag/v1.12
- [ ] USER ACTION: REVOKE the v1.12 PAT pasted in chat (and confirm the v1.11 one is revoked).

## 2026-06-19 — In-app update UX: live progress % + offline resilience + hero notes card

User request (Taglish): during update show a pop-up with download PERCENTAGE; stay safe if
the internet drops mid-download; and make the "update available" notes pop-up a simple-but-
beautiful UI. User chose: Hero notes card design + mockup-first (approved mockup
lg_update_mockup.png — hero card + 3 progress states). Risk: MEDIUM (touches the update
download path, which is security-sensitive — signature verify before install MUST stay).

Design constraint: do NOT change the security-critical install path in UpdateInstaller
(trusted-host allow-list + signatureMatchesInstalledApp before ACTION_VIEW). Progress
polling is ADDITIVE for the UI only; the existing BroadcastReceiver still triggers the
verified install on completion.

Plan — DONE 2026-06-19:
- [x] 1. UpdateInstaller: pure `DownloadStatus` sealed type + pure `mapStatus(status, reason,
      soFar, total)` + `percentOf` (0 when total unknown, clamped 0..100). downloadAndInstall
      now RETURNS the downloadId (NO_DOWNLOAD=-1 on untrusted/refused). Added `queryStatus(ctx,
      id)` (DownloadManager cursor → mapStatus; missing row → Failed) and `cancel(ctx, id)`.
      Security-critical install path (allow-list + signatureMatchesInstalledApp) UNTOUCHED —
      polling is UI-only.
- [x] 2. Hero notes dialog: dialog_update_available.xml (download icon, "Update available",
      version + "You're on X", divider, notes container, full-width cyan Update now, Later),
      wrapped in ScrollView so the button is never clipped. Pure `formatReleaseNotes(raw)` in
      new ReleaseNotesFormat.kt → header/bullet NoteLines (strips #, **bold**, `code`, links,
      checksum footer, rules; capped at 15).
- [x] 3. Progress dialog: dialog_update_progress.xml — one card, progressGroup (title, big %,
      bytes, LinearProgressIndicator, status row, Cancel) morphs to amber Paused ("Waiting for
      connection…") and failedGroup (red circle icon + message + Retry + Dismiss).
- [x] 4. MainActivity: showUpdateDialog → hero layout (notes via populateNotes); startUpdateDownload
      → progress dialog + lifecycleScope poll loop (350ms, queryStatus on Dispatchers.IO);
      renderProgress maps Running/Paused/Pending to cyan/amber UI; Cancel/Retry/Dismiss wired;
      stops on Succeeded (dismiss; verified install prompt takes over) / Failed (showFailedState).
      Poll cancelled on dialog dismiss + onDestroy. htmlUrl fallback kept (no-asset + refused-URL).
- [x] 5. Drawables: bg_btn_outline_cyan, ic_download, ic_cloud_off, bg_circle_red_dim; +1 color
      text_secondary; ~14 new strings; item_update_note.xml row.
- [x] 6. Tests: UpdateDownloadStatusTest (9: percent edge cases + mapStatus per state) +
      ReleaseNotesFormatTest (9: headers, bullets, emphasis/link strip, noise drop, cap).
      Full suite 210 tests, 0 failures (was 192; +18). :app:assembleDebug GREEN.
- [x] 7. On-device Pixel_7/API34 — built a throwaway debug stamped versionName 1.11 (reverted
      immediately) so the live checker offered v1.12, installed fresh, drove the REAL flow:
      * Hero card renders with real v1.12 notes parsed to bold headers + cyan-dot bullets,
        scrolls, Update now + Later reachable (tasks/update-hero-card.png).
      * Update now → progress dialog live: 0% / "69 KB / 23.3 MB" / Downloading… / Cancel
        (tasks/update-downloading.png). Real 23.3 MB GitHub asset.
      * Cut wifi mid-flight → amber "Update paused / Waiting for connection…" (tasks/update-paused.png);
        restored wifi → auto-resumed back to cyan Downloading (tasks/update-resumed.png).
      * On completion the dialog auto-dismisses and the signature gate REFUSED the install
        (logcat "Refusing to install update: signature verification failed") because the
        downloaded release-signed v1.12 ≠ debug-signed test build — proves the security path
        is intact and the new UI didn't bypass it.
      * Restored emulator to release v1.12; build.gradle reverted to versionName "1.12" (no diff).
- Residual: Failed/Retry UI not driven live (hard to force STATUS_FAILED on emulator) — covered
      by unit test (mapStatus→Failed, missing-row→Failed) + the layout compiles/links. Now-dead
      strings: update_download, update_available_msg, update_downloading (old AlertDialog path).

## 2026-06-20 — Release v1.13 (versionCode 14): update UX (progress % + offline resilience)

- [x] Bumped versionCode 13→14, versionName "1.12"→"1.13". Pre-flight: live latest was v1.12,
      no drift. isNewerVersion part-wise: [1,13] > [1,12] ✓.
- [x] Full unit suite + signed release GREEN: :app:testDebugUnitTest (210 tests, 0 fail) +
      :app:assembleRelease (R8 + shrinkResources + lintVitalRelease).
- [x] APK verified: aapt versionCode=14 versionName=1.13; apksigner V2 cert SHA-256
      ead80ea1…74227357 (SAME release key → existing users update in place); size 24487113,
      SHA-256 7fd72e5c3ff29dc176ae7c5a111835197718032a4adae7bbb27883487dcd2416.
- [x] Release SMOKE on Pixel_7/API34 (R8-minified, signed): installed v1.13 in-place OVER
      release v1.12 (adb install -r, same key) → versionCode=14 confirmed; MainActivity renders
      fully, no crash (tasks/rel13_smoke via emulator). Proves R8 keeps the new dialogs/
      ViewBinding/coroutine poll. (Update dialog itself can't be driven on the latest build —
      already verified on the debug 1.11 run above.)
- [x] Staged release-staging/LinkGuard-v1.13.apk (hash matches) + RELEASE-NOTES-v1.13.md.
- [x] PUBLISHED to GitHub pnormzkie/LinkGuard (release id 342033291, tag v1.13): created draft
      → uploaded asset (state=uploaded, size 24487113) → un-drafted + make_latest. releases/latest
      =v1.13; public download re-hashed IDENTICAL (24487113 bytes, 7fd72e5c…2416). In-app updater
      will offer v1.13 to v1.12 users. https://github.com/pnormzkie/LinkGuard/releases/tag/v1.13
- [ ] USER ACTION: REVOKE the v1.13 PAT pasted in chat (github.com/settings/tokens) — exposed.
- Standing (unchanged): rotate/restrict the 3 embedded API keys; off-machine keystore backup.

## 2026-06-20 — Futuristic UI refresh: MainActivity home screen (subtle/refined)

User (Taglish, with screenshot): "Ano kailangan e improve sa UI na ito? Gusto ko mas
futuristic design." Chose: full scope (all 3 phases), intensity = subtle/refined.
Risk: LOW (UI layer only — drawables, activity_main.xml, MainActivity animation/tint wiring).
Scope: do NOT touch MainViewModel, scanner, scoring, providers, navigation, data.

CORRECTION logged: the hero rotating ring (ivShieldPulse) + icon pulse (ivMainIcon) are
ALREADY animated in MainActivity.setupAnimations() (lines 158-184). The XML
"<!-- Rotating/Pulse ring placeholder -->" comment is STALE. Phase 1 redirected to the
genuinely-missing element: a live pulsing status dot beside "Protection: ACTIVE".

- [x] Phase 1: pulsing status dot (statusDot View + dotPulseAnimator alpha pulse; color
      tracks green/red in updateProtectionStatus via backgroundTintList).
- [x] Phase 2: hero card gradient interior (bg_hero_gradient); stat-card gradient fills
      (bg_stat_grad_danger/warn/safe, clipped by card corners); scan-button cyan→green
      gradient (bg_scan_btn_gradient + app:backgroundTint="@null").
- [x] Phase 3: stat numbers → monospace + letterSpacing (24→26sp); subtle top cyan glow
      (bg_deep_glow on ScrollView); cyan accent bars (bg_accent_bar) before SECURITY TOOLS
      / RECENT ACTIVITY.
- [x] Verify: :app:assembleDebug BUILD SUCCESSFUL (1m58s) — resources link + compileDebugKotlin
      clean; only pre-existing warnings. No logic change → unit suite unaffected.
- [ ] PENDING on-device screenshot QA: render check (esp. MaterialButton gradient + card-corner
      clipping of gradients), protection ON vs OFF dot color, dark mode, font scale 200%.
- New files: bg_deep_glow, bg_hero_gradient, bg_stat_grad_{danger,warn,safe},
      bg_scan_btn_gradient, bg_accent_bar. Edited: activity_main.xml, MainActivity.kt.
- Minor: protection-badge tap target is now the text glyphs (was the full pill); onClick still
      on tvProtectionStatus, behavior preserved.

UPDATE 2026-06-20 — user reviewed mockups (rendered HTML→PNG: Options A/B/C, hybrids AC/BC,
Modern1/2) and chose **Option A (Neon HUD) + History redesign**. Built on top of the subtle base:
- [x] Home Option A: bg_grid_glow (tiled grid_tile.png + top cyan glow) background; neon cyan
      borders on hero + all tool cards (@color/neon_border_cyan); per-level neon borders on the
      3 stat cards (stat_border_red/yellow/green); existing radar ring + gradient button + mono
      numbers + accent bars + status dot retained. Removed superseded bg_deep_glow.xml.
- [x] History redesign: bg_grid_glow background; neon summary-card border; item_scan mono URL +
      mono score; ScanHistoryAdapter sets per-item card strokeColor = level color @ 0x66 alpha
      (ColorUtils) — green SAFE / red DANGER neon row borders.
- [x] VERIFY: :app:assembleDebug BUILD SUCCESSFUL (28s). ON-DEVICE Pixel_7/API34 (debug):
      Home renders Option A (tasks/optionA-home.png); ran 2 real manual scans
      (gcash-verify.com→DANGER 100%, google.com→SAFE 0%) → History shows grid bg, glowing
      summary, mono rows with per-level neon borders (tasks/optionA-history.png).
- New files: res/drawable-nodpi/grid_tile.png, drawable/bg_grid_glow.xml; +4 colors.
      Edited: activity_main.xml, activity_history.xml, item_scan.xml, ScanHistoryAdapter.kt, colors.xml.
- NOT yet done (other screens): Scan Detail, Block, Threat Alert, QR, dialogs still original style.
- Residual: protection-badge tap target minor note (above) still applies. Not committed.

UPDATE 2026-06-20 (later) — user reviewed MANY rendered mockups and chose **Option E (Bold
Blocks)** for the WHOLE app ("buo muna. Go"). Mockups approved: tasks/mockup-E-blocks-v2.png
(Home, centered tiles, distinct icons, white Suspicious text), tasks/mockup-E-fullapp.png
(Home/History/Scan Report/Block), tasks/mockup-E-alert.png (Threat Alert).
Design language E: screen bg #0B0D12; cards #141821 rounded; hero = blue→teal linear gradient;
primary = #0089FF; verdict solids red #E5384F / amber #D9870A / green #13A766; white text;
neutral elevation (NOT colored glow); stat tiles = solid color, centered icon badge + number + label.
Approach: RE-SKIN existing layouts (keep ALL view IDs + logic), replacing Option A neon styling.
- [x] E tokens: 16 colors (e_bg/e_card/e_blue/e_teal/e_red/e_amber/e_green/…) + ~20 drawables
      (bg_e_hero gradient, bg_e_icon_badge[_round/_blue/_green], bg_e_field, bg_e_btn_blue/_white,
      bg_e_pill_white/_solid, bg_e_card/_inner, bg_e_alert, bg_e_tab[_active], bg_e_bar/_score/_verdict).
- [x] Home (activity_main.xml rewritten) + MainActivity: removed ivShieldPulse/ivMainIcon + rotation/
      pulse animators (kept dotPulseAnimator); pill text forced white, dot carries state colour.
- [x] History (activity_history.xml + item_scan.xml + ScanHistoryAdapter: white text on tinted
      pill/score/bar, e_red/e_amber/e_green) + HistoryActivity.updateTabStyles → E tabs.
- [x] Scan Detail (activity_scan_detail.xml + ScanDetailActivity: solid verdict card, white ring/badge).
- [x] Block (activity_link_intercept.xml + LinkInterceptActivity: colour refs → E palette, blue
      primary; ALL IDs preserved, no logic change).
- [x] Threat Alert (activity_threat_alert.xml → solid red card, white icon badge, white VIEW FULL
      REPORT on red text; ThreatAlertActivity untouched).
- [x] VERIFY: :app:assembleDebug BUILD SUCCESSFUL. ON-DEVICE Pixel_7/API34 — all 5 screens captured:
      tasks/E-home.png, E-history.png, E-scandetail.png, E-block.png, E-alert.png; combined
      tasks/E-fullapp-real.png. (Block via VIEW intent; Threat Alert via shell notification + scanAllApps ON.)
- Removed Option A artefacts superseded by E: bg_deep_glow already gone; A neon drawables/colors
      (grid_tile, bg_grid_glow, bg_hero_gradient, bg_stat_grad_*, bg_scan_btn_gradient, bg_accent_bar,
      neon_border_* colors) are now UNUSED — left in tree (shrinkResources strips on release); can delete.
- COMMITTED to master (local, no push): ade4337 (Option E redesign, 32 files).

UPDATE 2026-06-20 (B then A) — post-redesign polish + coverage, per user "B muna tapos A":
- [x] B4 externalize: 13 new strings (home_protected, security_tools, scan_report, risk_score,
      scanned_url, copy_url_btn, details_label, detail_category, detail_source, risk_flags,
      no_risk_flags, threat_alert_view_report, threat_alert_dismiss) referenced from Home/ScanDetail/Alert.
- [x] B7 theme: windowBackground/colorBackground/status+nav bar -> e_bg; colorPrimary -> e_blue (themes.xml).
- [x] B5 tests: :app:testDebugUnitTest GREEN (no logic change). B6: :app:assembleRelease GREEN
      (lintVitalRelease + shrinkResources pass — no blocking lint). Home re-verified on device.
- [x] COMMIT 129729d (B: externalize + theme, 5 files).
- [x] A1 QR: overlay corners + laser #00E676 -> #0089FF (QrScannerOverlayView); gallery button
      surface/cyan -> e_card/e_blue (QrScannerActivity). Verified blue on device (tasks/E-qr.png).
- [x] A2 update dialogs: available + progress + note row cyan -> e_blue, surfaces/text -> E;
      MainActivity.renderProgress Running/Pending e_blue, Paused e_amber. (build-verified; not live-driven)
- [x] A3 block "TAPPED LINK" box: bg_url_input (teal border) -> bg_e_inner.
- [x] Fixed: dialog_update_available used app:tint without xmlns:app -> switched to android:tint.
- [x] A1-A3 build GREEN (assembleDebug + assembleRelease). COMMIT 28fb3c8 (A, 7 files).
- Commits on master: ade4337 (E) -> 129729d (B) -> 28fb3c8 (A). No push.
- Still original (low priority): SetupActivity (empty placeholder); dead old drawables remain
      (shrinkResources strips on release). Update dialogs not live-screenshot-verified (need a
      newer published version to trigger) but are a pure colour re-skin of the working v1.13 dialogs.

## 2026-06-20 — Release v1.14 (versionCode 15): Bold Blocks UI redesign — SHIPPED + PUBLISHED

- [x] #1 fix: hero headline reflects protection state (home_protected / home_unprotected),
      not a static "You're protected". Commit 8d22873.
- [x] #2 release-signed smoke test (Pixel_7/API34, R8-minified): Home renders + headline shows
      "Protection is off" on fresh install; block screen DANGER 100% with grouped Parcelable
      flags + dark TAPPED LINK box. ViewBinding/scoring/Parcelable survive R8.
- [x] Bumped versionCode 14→15, versionName 1.13→1.14 (pre-flight: live latest was v1.13, no drift).
- [x] Signed assembleRelease GREEN (clean build, R8+shrink+lintVital). APK: aapt versionCode=15
      versionName=1.14; apksigner V2 cert ead80ea1…74227357 (SAME key → in-place update);
      size 24498752, SHA-256 703E871E1AB345FF00C0AA7D43FE42FA0344F678C37B808D52F41819437C0BBD.
- [x] Committed a6b012a; staged release-staging/LinkGuard-v1.14.apk + RELEASE-NOTES-v1.14.md.
- [x] PUBLISHED to GitHub pnormzkie/LinkGuard: draft (id 342358058) → uploaded asset
      (state=uploaded, size match) → un-drafted + make_latest. tag v1.14.
- [x] Verified: releases/latest=v1.14; public download re-hashed byte-identical (24498752 bytes,
      703E871E…0BBD). In-app updater will offer v1.14 to v1.13 users.
      https://github.com/pnormzkie/LinkGuard/releases/tag/v1.14
- [ ] USER ACTION: REVOKE the GitHub PAT pasted in chat (github.com/settings/tokens) — exposed.
- Standing (unchanged): rotate/restrict the 3 embedded API keys; off-machine keystore backup.

## 2026-06-26 — Heuristic false-positive fixes: boundary-aware matching (UrlScanner.kt)

Trigger: user reported account.battle.net flagged 100%/DANGER (false positive). Audit found a
cluster of unanchored substring matches. Scope (user-approved): #1–#5 below. OUT (separate):
adding battle.net to OFFICIAL_DOMAINS; typosquat/CDN/threshold tuning (#6–#9). Risk: MEDIUM
(verdict logic). No change to scoring weights, providers, mapper, DB, or UI.

- [x] #1 Dangerous file ext (UrlScanner.kt:447) — `urlLower.contains(ext)` matched ".bat" inside
      "account.battle.net". FIXED: urlReferencesFileType() — ext not followed by [a-z0-9].
- [x] #2 Brand spoof (UrlScanner.kt:298) — `domain.contains(brand)` matched "ups" in "startups",
      "apple" in "pineapple". FIXED: hostContainsBrand() left-boundary; concatenations (paypalverify) kept.
- [x] #3 Brand-in-subdomain (UrlScanner.kt:359) — same substring flaw (groups.google.com → "ups").
      FIXED: same hostContainsBrand() on the subdomain label.
- [x] #4 Phishing keywords (UrlScanner.kt:281-291) — `.contains(kw)`: "php" matched ".php" on every
      PHP site; "last" matched "elastic" etc. FIXED: containsKeyword() whole-token + removed "php".
- [x] #5 Suspicious TLD (UrlScanner.kt:264) — `urlLower.contains(tld)` matched TLDs inside embedded
      redirect URLs. FIXED: `domain.endsWith(tld)` (mirrors rule 12's anchored check).
- [x] #6 Official-domain whitelist was exact-host only (UrlScanner.kt:253) — legit subdomains
      (accounts.google.com, support.apple.com) ran the full gauntlet. FIXED: isOfficialDomain now
      `domain == it || domain.endsWith(".$it")`. Leading dot blocks suffix-spoof (secure-google.com).
- [x] Whitelist: added ADDITIONAL_TRUSTED_DOMAINS (battle.net, blizzard.com, steampowered.com,
      steamcommunity.com, discord.com, epicgames.com, riotgames.com) → folded into ALL_OFFICIAL_DOMAINS.
      battle.net now SAFE (the reported case fully cleared). Brand-spoof data (ALL_BRANDS) untouched.
- [x] Regression tests in HeuristicScannerTest.kt (+12 total: 9 boundary + battle.net SAFE +
      legit-subdomain SAFE + suffix-spoof still flagged). VERIFY: :app:testDebugUnitTest = 222 tests,
      0 failures. compileDebugKotlin clean (only pre-existing warnings).
- DEFERRED (per user): #7 typosquat word collisions, #8 CDN gibberish, #9 keyword/threshold tuning —
      higher risk of weakening real detection; separate careful pass with test data. No release yet.

  SECURITY REVIEW (user: "baka mabypass ng sophisticated attacks") — 2026-06-26:
  - Found a real gap my own #2/#3 left-boundary fix introduced: it MISSED glued-prefix spoofs like
    "verifypaypal.com"/"paypalsupport.com" (brand not at left boundary). RESTORED with affix-aware
    looksLikeBrandSpoof(): flags brand as a full label, with a separator, or glued to a phishing
    affix (BRAND_AFFIXES: secure/login/verify/support/account/... — dropped short ones like ph/id/app
    to avoid endsWith collisions e.g. "graph"). Excludes dictionary words (startups/pineapple/appleton).
  - Re-reviewed the other changes for evasion: #1 ext boundary is right-side only (real .bat/.exe still
    caught, incl. double-ext invoice.pdf.exe); #5 TLD endsWith matches the true TLD (attacker can't hide
    it); #6 whitelist uses ".$it" leading dot so battle.net.evil.com is NOT trusted, and *.battle.net
    needs real subdomain control. #4 keyword word-boundary intentionally drops weak glued-keyword hits
    (keywords are a weak +10/+15 signal; reputation providers are the real backbone).
  - Tests +4 (verifypaypal/paypalsupport/secure-amazon flagged; appleton not). VERIFY: full suite =
    225 tests, 0 failures (HeuristicScannerTest 36). compileDebugKotlin clean.
  - Residual (accepted): brand glued to a NON-affix gibberish word (e.g. "paypalxyz.com") relies on
    other layers (TLD/reputation); open-redirect on a whitelisted domain's own URL not re-scanned.

## 2026-06-26 — Release v1.15 (versionCode 16): SHIPPED + PUBLISHED

Bundles: heuristic FP fixes (#1–#6 + affix-aware brand-spoof + battle.net/major-brand whitelist)
AND redirect/shortener resolution (RedirectResolver + orchestrator + block/Scan-Detail "Goes to" UI).

- [x] Pre-flight: live latest was v1.14, source 15/1.14 — no drift. Bumped versionCode 15→16,
      versionName 1.14→1.15. isNewerVersion("1.15","1.14")=true → v1.14 users get the prompt.
- [x] Signed assembleRelease GREEN (R8 + shrinkResources + lintVital). apksigner V2 cert SHA-256
      ead80ea1…74227357 (SAME key → in-place update). aapt versionCode=16 versionName=1.15.
      APK SHA-256 BFACDF70F2C7BE5708120A1DA8C3BF8D889C3071C0466652CB0E61A7335D57EE, size 24503476.
- [x] On-device smoke (Pixel_7/API34, headless): redirect resolution end-to-end (tapped nghttp2
      redirect → resolved example.com, scored at destination, "Goes to" row) + 4-way fail-safe.
      Test-only timeout bump used for the success capture, REVERTED (3000/1500); suite re-run green.
- [x] Committed 36bb4d0 (master); staged release-staging/LinkGuard-v1.15.apk + RELEASE-NOTES-v1.15.md.
- [x] PUBLISHED to GitHub pnormzkie/LinkGuard: release id=345378974, tag v1.15, make_latest=true.
      Asset LinkGuard-v1.15.apk state=uploaded, digest matches local.
- [x] Live-verified: releases/latest=v1.15; public download re-hashed byte-identical
      (24503476 bytes, bfacdf70…d57ee). In-app updater (versionName compare, first *.apk asset)
      will offer v1.15 to v1.14 users. https://github.com/pnormzkie/LinkGuard/releases/tag/v1.15
- [ ] USER ACTION: REVOKE the GitHub PAT pasted in chat (github.com/settings/tokens) — exposed. Do now.
- Note: no git remote configured locally — the source COMMIT (36bb4d0) is local only; the RELEASE
      (APK) is what reaches users. Push source separately if desired.
- Standing (unchanged): rotate/restrict the 3 embedded API keys; off-machine keystore backup.

## 2026-06-27 — Roadmap #2: add URLhaus (abuse.ch) as a 6th provider

Honest scoping: VirusTotal already aggregates ~70 engines (incl. PhishTank/OpenPhish), so most
"extra feeds" are redundant. Only URLhaus has clear marginal value (fresh malware-distribution
hosts, lightweight per-host lookup). PhishTank/OpenPhish/Google Web Risk skipped (redundant or
paid/registration with low marginal gain). User provided a free abuse.ch Auth-Key.

- [x] New `data/provider/UrlHausDomainProvider.kt` (SignalProvider): POST /v1/host/ with
      `host=<domain>` + Auth-Key header. query_status "ok" + online URL → CRITICAL/100; ok + all
      offline → STRONG/50; "no_results"/"invalid_host" → clean empty; blank key → no-op; trusted
      domain → skip; non-2xx + auth-error status + network → fail loud (rethrow). Mirrors DomainAge.
- [x] Key wiring: `URLHAUS_AUTH_KEY` in local.properties (gitignored) → build.gradle buildConfigField
      → AppConfig.URLHAUS_AUTH_KEY. Same pattern as the other keys.
- [x] Wired as 6th provider in ScanOrchestrator (async + awaitAll + "UH:" log) + ScannerProvider.
- [x] Tests: UrlHausDomainProviderTest (8) + ScanOrchestratorTest helper/all-fail updated for the
      6th provider. VERIFY: 249 tests, 0 failures.
- [x] Live probe: key VALID — POST host/ example.com → HTTP 200, query_status no_results.
- [x] RELEASED as v1.16 (versionCode 17) — see block below.
- CONSIDERATION (surface to user): the Auth-Key ships in the APK via BuildConfig (extractable, like
  the other 3 keys) and is tied to ONE abuse.ch account — all installs share its quota and abuse.ch
  could revoke it. Same client-side-key tradeoff as VT/SB/HA; durable fix = backend proxy.

## 2026-06-27 — Release v1.16 (versionCode 17): URLhaus feed — SHIPPED + PUBLISHED

Ships roadmap #2 (URLhaus 6th provider). User-facing: one more live malware-distribution feed.

- [x] Pre-flight: source was 16/1.15 (== published v1.15). Bumped versionCode 16→17,
      versionName 1.15→1.16. isNewerVersion("1.16","1.15")=true → v1.15 users get the prompt.
      (Live-latest re-check deferred: unauthenticated API rate-limited; will confirm with PAT.)
- [x] Full unit suite GREEN: 249 tests, 0 failures, 0 errors (testDebugUnitTest).
- [x] Signed assembleRelease GREEN (R8 + shrinkResources + lintVital). apksigner V2 cert SHA-256
      ead80ea1…74227357 (SAME key → in-place update). aapt versionCode=17 versionName=1.16.
      APK SHA-256 1ac68eb0b0249f8f99bab3ddf34483177210c85810a65bd4914a9b6029a3f5ff, size 24503476.
- [x] Staged release-staging/LinkGuard-v1.16.apk (hash matches build) + RELEASE-NOTES-v1.16.md.
      Committed 18537d8 (master, local only — no git remote configured).
- [x] PUBLISHED to GitHub pnormzkie/LinkGuard: release id=345556579, tag v1.16, make_latest=true.
      Asset LinkGuard-v1.16.apk state=uploaded, digest sha256:1ac68eb0…a3f5ff (matches local build).
      (Matched the real v1.15 convention: ONE asset named LinkGuard-vX.Y.apk, no version.json —
      the in-app updater reads the releases API and picks the first *.apk asset's download URL.)
- [x] Live-verified: releases/latest=v1.16; public download re-hashed byte-identical
      (24503476 bytes, 1ac68eb0…a3f5ff). In-app updater will offer v1.16 to v1.15 users.
      https://github.com/pnormzkie/LinkGuard/releases/tag/v1.16
- [ ] USER ACTION: REVOKE both GitHub PATs pasted in chat (v1.15 + v1.16) — github.com/settings/tokens.
- Standing (unchanged): rotate/restrict the 4 embedded API keys (now incl. URLhaus); off-machine
      keystore backup; durable fix for client-side keys = backend proxy.

## 2026-06-27 — Roadmap #5: credential-form / page-content check — IMPLEMENTED + TESTED (not released)

Honest re-scoping this session: #3 (NRD+brand) found already-covered (brand-spoof is STRONG→60→
THREAT; scores sum), and #6 (TLS cert) found moot (Android's TLS stack already enforces the strong
cert checks; "no HTTPS" already flagged at UrlScanner.kt:326; readable cert attrs are FP-heavy).
So we went to #5 — the real remaining gap: brand-less zero-hour AI phishing (clean URL, login form).
See tasks/plan-credential-form.md (approved: conditional-fetch design).

- [x] New `domain/scanner/CredentialFormInspector` interface + `data/provider/HttpCredentialFormInspector`.
      Conditional, read-only, bounded GET of the resolved page HTML; static-HTML detection only.
      `<input type=password>` on untrusted host → MEDIUM/25 (CREDENTIAL_FORM_UNTRUSTED); password +
      form action posting to a different registrable domain (excluding trusted IdPs) → STRONG/50
      (CREDENTIAL_FORM_EXFIL). Guards: trusted-host skip, anti-SSRF private-host skip, non-HTML skip,
      256 KB body cap (peekBody), 3 s timeout, fail-soft (any error → empty, never breaks scan).
- [x] Orchestrator phase 4 (gated): fetch ONLY when phase-1 verdict == SUSPICIOUS AND host untrusted
      (THREAT already decided; SAFE not worth the privacy/perf cost). Found form → re-score.
      Disabled by injecting inspector = null (like redirectResolver). ScannerProvider wires a
      dedicated short-timeout, no-redirect contentHttpClient.
- [x] Config: AppConfig.CONTENT_FETCH_TIMEOUT_MS=3000, CONTENT_MAX_BYTES=256 KB.
- [x] Tests: HttpCredentialFormInspectorTest (9: medium/exfil/IdP-not-exfil/no-password/non-html/
      trusted-skip/private-skip/non-2xx/network-fail) + ScanOrchestratorTest (4: escalate / clean-not-
      inspected / trusted-not-inspected / already-threat-not-inspected). FakeHttp + clientReturningHtml.
      VERIFY: full suite = 262 tests, 0 failures, 0 errors.
- LIMITATION (honest): static HTML only — JavaScript-rendered forms are NOT detected. Many phishing
      kits still ship static forms (real value) but this is not complete coverage.
- [x] On-device smoke (Pixel_7/API34 headless, real network) — PASS, no issues. Drove via
      `am start .ui.LinkInterceptActivity VIEW <url>`; DEBUG log added in phase-4 ("Credential-form
      check ran for X -> found N", gated by BuildConfig.DEBUG, kept). Evidence:
      - github.com/login (trusted) → inspector SKIPPED (no fetch). ✓
      - example.com (untrusted, HA-flagged Suspicious) → ran, real GET+parse, found 0 (no form) → unchanged. ✓
      - the-internet.herokuapp.com/login → phase-1 SUSPICIOUS ("login" kw) → ran, real GET+parse of live
        page → found 1 (password form) → CREDENTIAL_FORM_UNTRUSTED, surfaced in UI as "Login form on an
        unverified site", verdict SUSPICIOUS 50%. tasks/smoke-credform-herokuapp.png. ✓
      No crashes/exceptions. (DomainAge RDAP 403 = unrelated pre-existing rate-limit, fail-soft.)
      EXFIL cross-domain variant = unit-tested only (no benign real target); same parse path.
- [x] Release v1.17 (versionCode 18): bumped 17→18 / 1.16→1.17. Signed assembleRelease GREEN.
      apksigner V2 cert ead80ea1…74227357 (SAME key → in-place update). aapt versionCode=18 versionName=1.17.
      APK SHA-256 f2b8e9ba92a4b08ace57d0f779e6042169aeaece7fba14e36b0040a3d044498a, size per staging.
      Staged release-staging/LinkGuard-v1.17.apk (hash matches) + RELEASE-NOTES-v1.17.md.
- [x] Committed 97d85df (master, local only — no git remote).
- [x] PUBLISHED to GitHub pnormzkie/LinkGuard: release id=345567938, tag v1.17, make_latest.
      Asset LinkGuard-v1.17.apk state=uploaded, digest sha256:f2b8e9ba…44498a (matches local).
- [x] Live-verified: releases/latest=v1.17; public download re-hashed byte-identical
      (24507572 bytes, f2b8e9ba…44498a). In-app updater will offer v1.17 to v1.16 users.
      https://github.com/pnormzkie/LinkGuard/releases/tag/v1.17
- [ ] USER ACTION: REVOKE all 3 GitHub PATs pasted in chat (v1.15 + v1.16 + v1.17) — github.com/settings/tokens.

## 2026-08-15 — ChatGPT URL false-positive fix

- [x] Added `chatgpt.com` and `openai.com` to the curated trusted-domain list.
- [x] Added regression coverage for `/advanced-account-security?originator=android_app_homepage_beacon`.
- [x] Verify focused and full JVM unit tests: `:app:testDebugUnitTest` passed.

## 2026-08-15 — AI-phishing static page detection

- [x] Extended bounded static HTML inspection for OTP/payment forms, hidden sensitive fields,
      urgent account language, brand impersonation, obfuscated scripts, cross-domain frames,
      and urgent executable downloads.
- [x] Added trusted ChatGPT/OpenAI domain consistency and trusted identity-provider exceptions.
- [x] Expanded orchestrator inspection to untrusted non-THREAT destinations so clean-looking
      phishing pages can be checked without inspecting trusted hosts or already-dangerous URLs.
- [x] Added inspector and orchestrator regression tests.
- [x] Verification: focused tests, full `:app:testDebugUnitTest`, and `:app:assembleDebug` pass.

## 2026-08-15 — App-only advanced phishing hardening

- [x] 1. Add benchmark-style malicious and legitimate regression fixtures.
- [x] 2. Replace attribute-order-sensitive HTML extraction with bounded structured parsing.
- [x] 3. Centralize page-brand identities and trusted authentication targets.
- [x] 4. Detect multi-step sensitive forms, SVG/data payloads, ClickFix, overlays, clipboard use,
      delayed redirects, and encoded/dynamic form behavior without executing JavaScript.
- [x] 5. Calibrate generic keyword evidence so one weak word cannot change the verdict alone;
      exact duplicate signals no longer inflate score or confidence.
- [x] 6. Inspect redirect-capable trusted URLs without broadly scanning normal trusted pages.
- [x] 7. Expand PH/Taglish message and QR regression coverage within current architecture.
- [x] 8. Verify focused tests, full JVM suite (286 tests), debug build, and release build.

## 2026-08-15 — Release v1.18

- [x] Pre-flight: latest public release is v1.17; bump source to versionCode 19 / versionName 1.18.
- [x] Full suite GREEN (286 tests) + signed release build GREEN (R8/shrink/lintVital).
      APK: versionCode 19, versionName 1.18, V2 signer ead80ea1…27357 (same key),
      size 24591136, SHA-256 262F445CB70D09BE7187E3ABD2E1F94BEE9FCA9081AF0C1D5286C7A9BE4AB0D2.
- [x] Staged byte-identical `release-staging/LinkGuard-v1.18.apk`; committed as b702a61.
- [x] Published v1.18 as latest GitHub Release; public API reports a production release and
      `LinkGuard-v1.18.apk` (24591136 bytes). Public download re-hashed byte-identical:
      262F445CB70D09BE7187E3ABD2E1F94BEE9FCA9081AF0C1D5286C7A9BE4AB0D2.
      https://github.com/pnormzkie/LinkGuard/releases/tag/v1.18

## 2026-08-16 — Hybrid scan concurrency optimization

- [x] Add side-effect-free page-inspection eligibility using the inspector's existing safeguards.
- [x] Overlap eligible bounded page inspection with the six provider checks using structured concurrency.
- [x] Preserve historical provider-THREAT flags by excluding page findings when other evidence is already THREAT.
- [x] Add parity, timeout/failure, redirect, cache, cancellation, privacy, and virtual-time regression tests.
- [x] Verify focused orchestrator tests, full JVM suite (299 tests), debug/release builds, and final surgical diff.

### Final audit follow-up

- [x] Enforce a strict 3-second total content-call and coroutine budget with active Call cancellation.
- [x] Block public-looking hostnames that resolve to private, loopback, link-local, or unique-local IPs.
- [x] Add deterministic deadline, provider-THREAT latency, HTTP cancellation, and injected-DNS tests.
- [x] Re-run focused/full tests, debug/release builds, signer verification, and read-only diff audit.

### Redirect DNS anti-SSRF closure

- [x] Share the centralized validating DNS policy with the dedicated redirect client.
- [x] Prove private, mixed, empty, failed, and public DNS handling without real network access.
- [x] Prove unsafe redirect DNS fails before transport and remains fail-soft through orchestration.
- [x] Re-run focused/full tests, debug/release builds, diff check, signer check, and final audit.

## 2026-08-16 — Cancellation-aware external provider HTTP calls

- [x] Add one race-safe cancellable OkHttp execution helper with caller-owned successful responses.
- [x] Route all six external providers through the helper without changing requests or parsing.
- [x] Prevent RetryInterceptor from retrying or backing off after Call cancellation.
- [x] Add deterministic helper/retry tests and retain orchestrator timeout/cancellation regression coverage.
- [x] Run provider parsing tests, focused tests, full JVM suite, debug/release builds, and final diff audit.
