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
