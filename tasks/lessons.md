# Lessons Log

> One entry per mistake. Use the template from `references/examples.md`.
> Never delete entries — mark superseded ones with status: superseded by [DATE — TITLE].
> Review all `active` entries at the start of each session.

---

<!-- Add lessons below this line -->

## 2026-06-12 — Build environment constraints on this machine
**Mistake:** Assumed gradlew.bat existed and that Android Studio's bundled JBR could build the project; first two build attempts failed.
**Context:** LinkGuard improvements task — baseline build before code changes.
**Rule:** This project has no Gradle wrapper script. Build with the cached Gradle 8.9 dist (`%USERPROFILE%\.gradle\wrapper\dists\gradle-8.9-bin\...\bin\gradle.bat`) and JDK 17 (`C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot`) — the bundled JBR is Java 21, which breaks AGP 8.1's jlink JdkImageTransform. Sandboxed shells also time out on dependency downloads; run Gradle unsandboxed.
**Status:** active

## 2026-06-12 — Verify audit findings before planning fixes
**Mistake:** Initial code audit reported two issues that were already fixed (camera executor shutdown existed in onDestroy; PaymentQrValidator already bounds-checked TLV parsing).
**Context:** LinkGuard improvements task — exploration phase findings fed into planning.
**Rule:** Before planning a fix from an audit/exploration report, re-read the exact lines cited. Plan only against verified current code.
**Status:** active

## 2026-06-13 — android.util.* stubs silently block JVM test coverage
**Mistake:** None this time (caught by an empirical probe before writing assertions), but the constraint nearly produced vacuously-passing tests: with `returnDefaultValues = true`, `android.util.Base64.encodeToString` returns null, so VirusTotalEnrichmentProvider throws NPE before any HTTP work — an "HTTP 500 fails loud" test would pass for the wrong reason.
**Context:** QA task adding provider error-semantics tests; VT network path turned out untestable on JVM.
**Rule:** Before asserting network/parsing behavior of code that calls `android.*` APIs in a JVM unit test, probe the path empirically and check the exception type/source. If a stub default (null/0/false) short-circuits the path, document the gap instead of writing a test that passes vacuously. OkHttp Interceptor on the injected client is the established no-dependency HTTP fake for this repo (FakeHttp.kt).
**Status:** active

## 2026-06-12 — Check cross-references before assigning a severity
**Mistake:** Flagged ThreatAlertActivity's `resources.getIdentifier("ic_stat_suspicious")` as an S2 release-blocker (drawable could be stripped by `shrinkResources`), without first checking whether the drawable was referenced elsewhere. It is — ScanDetailActivity.kt:58 uses `R.drawable.ic_stat_suspicious` directly — so it is never stripped. The finding was real as a code smell but the severity was wrong.
**Context:** UI-layer review of LinkGuard; reported during the android-ui-reviewer pass.
**Rule:** Severity claims that depend on "X is unreferenced/unused" (dead-resource stripping, dead code, unused export) require a repo-wide reference check (grep R.drawable.<name> / the symbol) BEFORE assigning severity. Related to the audit-verification lesson above — second occurrence of stating a finding without checking the surrounding evidence.
**Status:** active

## 2026-06-19 — A taller dialog redesign can push action buttons off-screen
**Mistake:** Redesigned the LinkInterceptActivity block screen (a wrap_content, dialog-themed window) to be much taller — badge + 120dp score ring + URL chip + up to 3 flag-row cards — without a scroll container. On the DANGER verdict (3 flags) the content exceeded the screen height, so the DON'T OPEN / OPEN ANYWAY decision buttons were clipped off-screen and unreachable. Caught only by on-device screenshot, not by the build (it compiles fine) or by the shorter SUSPICIOUS case (which fit).
**Context:** Block-screen redesign on Pixel_7/API34. The original compact layout (single tvReason line) fit; the redesign did not, and a floating/dialog window clips rather than scrolls.
**Rule:** When a layout's height grows and it lives in a wrap_content / dialog / non-scrolling window — especially a decision dialog whose buttons are the only safe exit — wrap it in a ScrollView (or pin the action row) so the primary actions can never be pushed off-screen. Verify the WORST-CASE content size on-device (most flags / longest text), not just the happy/short case.
**Status:** active

## 2026-06-19 — Floating Dialog theme clips at rest; per-button autosize looks uneven
**Mistake:** Used Theme.AppCompat.DayNight.Dialog for the link-intercept verdict UI and tried to fit two side-by-side decision buttons with per-button autosize. Result: (1) the floating dialog window caps content height and CLIPS the last row at rest (the buttons showed half-cut even when scrollable — a ScrollView alone did not prevent the clipped resting position); (2) `app:autoSizeTextType="uniform"` sizes each button independently, so a longer label ("OPEN ANYWAY") shrank below a shorter one ("DON'T OPEN") and the pair looked uneven.
**Context:** LinkInterceptActivity block-screen redesign, caught by user on Pixel_7/API34 screenshots.
**Rule:** For a custom card/verdict activity that must scroll AND keep action buttons fully visible, use a NON-floating full-screen translucent theme (own FrameLayout scrim + centered ScrollView card), not a floating *.Dialog theme — floating windows clip rather than reserve space. For paired buttons that must read as equal, do NOT rely on autosize (it is per-view); give them the same fixed text size and equal width, or stack them full-width. Verify the worst-case label on-device.
**Status:** active
