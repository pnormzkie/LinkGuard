# Lessons Log

> One entry per mistake. Use the template from `references/examples.md`.
> Never delete entries — mark superseded ones with status: superseded by [DATE — TITLE].
> Review all `active` entries at the start of each session.

---

<!-- Add lessons below this line -->

## 2026-09-18 — Verify notification adapters at the production boundary
**Mistake:** Initially treated EXTRA_MESSAGES as a nested Bundle and marked the change done after compilation; pure string tests did not exercise Android extras. Also repeated blocked shell attempts instead of maintaining a clear verification checkpoint.
**Context:** v1.30 notification and privacy fixes. Corrected EXTRA_MESSAGES to direct Parcelable[] and shared the Bundle adapter between the service and instrumentation tests.
**Rule:** Compilation does not verify a framework data contract. Tests must call the production adapter, not duplicate it. Installation/boot failures mean tests did not run, not that they passed. After an execution blocker is established, preserve the gate and report it without repeated claims of completion.
**Status:** active

## 2026-09-18 — Preserve approval and delegation boundaries
**Mistake:** Edited notification visibility during an earlier read-only audit (reverted before approval), then selected isolated agents for uncommitted original-tree work, preventing useful implementation.
**Context:** v1.30 audit and approved follow-up batch.
**Rule:** Read-only requests never authorize edits. Use the approved scope after explicit approval; do not select worktree isolation unless requested, and never send agents outside a denied workspace boundary.
**Status:** active

## 2026-06-12 — Build environment constraints on this machine
**Mistake:** Assumed gradlew.bat existed and that Android Studio's bundled JBR could build the project; first two build attempts failed.
**Context:** LinkGuard improvements task — baseline build before code changes.
**Rule:** This project has no Gradle wrapper script. Build with the cached Gradle 8.9 dist (`%USERPROFILE%\.gradle\wrapper\dists\gradle-8.9-bin\...\bin\gradle.bat`) and JDK 17 (`C:\Program Files\Eclipse Adoptium\jdk-17.0.18.8-hotspot`) — the bundled JBR is Java 21, which breaks AGP 8.1's jlink JdkImageTransform. Sandboxed shells also time out on dependency downloads; run Gradle unsandboxed.
**Status:** active

## 2026-08-15 — Trusted IdP exception for page-brand signals
**Mistake:** A new page-brand impersonation signal treated an untrusted page posting a password form to Google as brand impersonation, breaking an existing OAuth regression test.
**Context:** AI-phishing static HTML inspection expansion in `HttpCredentialFormInspector`.
**Rule:** When adding brand/content signals, distinguish a page claiming a brand from a legitimate trusted identity-provider form target; test both local phishing forms and trusted OAuth-style destinations.
**Status:** active

## 2026-08-15 — Test signals need distinct rule identities
**Mistake:** Existing scoring/orchestrator test helpers assigned one synthetic rule ID to different findings, so exact-rule deduplication initially collapsed valid test evidence and mapper groups.
**Context:** App-only phishing hardening; duplicate-score protection in `ScoringEngine`.
**Rule:** Test fixtures representing different findings must use distinct rule IDs. Production deduplication must include rule ID, source, and title so only exact repeated evidence is collapsed.
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

## 2026-06-20 — Mockup + explicit approval BEFORE editing UI; don't treat a scope question as sign-off
**Mistake:** For a "make the UI more futuristic" request I asked a scope/intensity question (AskUserQuestion), then went straight to editing layout + Kotlin and only showed the result after building. The user had approved no plan and had seen no preview image. This repo's established UI workflow is mockup-first: prior UI changes (grouped flags, vendor names, block-screen redesign) each produced a rendered mockup PNG and got explicit approval BEFORE any code edit.
**Context:** Futuristic refresh of activity_main.xml (home screen). User: "wala pa akong inapproved na plan diretso ka agad nag-edit... wala ka ngang pinakita muna na output image kung ano hitsura."
**Rule:** For any UI visual change, produce a visual preview (rendered HTML→PNG mockup, or annotated before/after) and get EXPLICIT approval of the plan before editing any file. A scope/intensity question is requirements-gathering, NOT sign-off. Show the picture, wait for "go," then implement. Applies even when the change looks low-risk/surgical.
**Status:** active

## 2026-09-21 — A trust flag about the HOST must not waive evidence about the PATH
**Mistake:** `UrlScanner.analyze` computed one `isOfficialDomain` from the host and used it to skip 14 of 16 rules, including rules that read the path and the message text. Because `KnownDomains.isTrusted` matches all subdomains, any page an attacker published on `sites.google.com` / `docs.google.com` / `github.com` scored 0 with zero flags, while the same path on an untrusted host scored DANGER 65. The class doc already stated the correct invariant ("trust does not bypass URL-specific checks because legitimate services can host user-controlled content") — the code contradicted its own documented contract, and no test covered it because nothing fails when a scanner goes quiet.
**Context:** Deep evaluation audit of v1.31, found by probing the real scanner rather than reading rule-by-rule. The same audit found rule 6 using a raw `contains` where rule 5 used a token-boundary test, and `extractPath` dropping the query whenever the URL had no `/`.
**Rule:** Classify every detection rule by WHAT its evidence is about — host, path/query, or message — and gate it only on a trust signal of the same kind. Host reputation may waive host rules; it never waives attacker-controlled path or query evidence. When a host is trusted AND serves user-authored pages, keep path rules on. Corollary: when two rules solve the same matching problem (brand-in-string), they must share one helper — a second hand-rolled `contains` is where the false positives come back. Corollary 2: a false NEGATIVE is invisible in a green suite; probe a security scanner with known-bad inputs and read the score, don't infer it from the code.
**Status:** active
