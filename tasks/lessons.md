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

## 2026-09-22 — Displayed copy must be derived from the evidence, not from the code path that produced it
**Mistake:** The scan detail screen showed "Detected by multiple security vendors" next to a "Vendors flagged: 1" chip, because `ScoringEngine` picked the category string from the signal's `SignalSource` alone and hardcoded the word "multiple". The same pattern appeared in three more places: `VirusTotalEnrichmentProvider` rendering "1 Vendors Flagged" (the guard only rejects 0), `ScoringEngine` labelling any non-critical `EXTERNAL_REPUTATION` hit "Known phishing or malicious site" — including a URLhaus record whose own description says every tracked URL is now offline — and the DANGER notification channel described as "confirmed malicious links" while firing on heuristics-only verdicts. A stale `// Case 2: VirusTotal` comment marked the origin: the branch was written when VirusTotal was the only `ENRICHMENT` provider, and Hybrid Analysis was later added to the same source without revisiting the wording.
**Context:** Norman questioned the wording on a `https://Notion.com` scan. Investigating the copy surfaced a larger defect underneath it (see below); the wording was the visible symptom of a system that described its own conclusions from the wrong variable.
**Rule:** A user-facing claim about quantity, certainty, or attribution must be computed from the same data the UI shows for it, never from the branch that produced it. If a string says "multiple", "confirmed", "known", or "N vendors", the count or the confirmation must be read from the signals at render time — and the two places that display it must share one derivation, so they cannot disagree on screen. Corollary: when a second provider is added to an existing `SignalSource`, re-read every string keyed on that source; a comment naming one provider is the tell that the branch was never generalised.
**Status:** active

## 2026-09-22 — A provider's verdict LABEL must not set signal strength; the SCORE must
**Mistake:** `HybridAnalysisProvider` graded any non-trusted report `SignalStrength.STRONG` whenever Falcon Sandbox returned the label "Suspicious" or "Malicious", regardless of `threat_score`. `ScoringEngine` treats STRONG as a verdict override, so a report scoring 28 produced `adjustedScore = 14` and a SUSPICIOUS badge at the same time: the screen displayed "14% RISK SCORE" directly above "SUSPICIOUS LINK". The two numbers came from different inputs — the ring from the score, the badge from the label — so the app contradicted itself and no test caught it because every existing provider test used a score of 60+ where both paths happen to agree.
**Context:** Found while auditing the copy defect above. Reproduced by arithmetic against the screenshot (14 = 28/2, the only path to a SUSPICIOUS verdict at that score), then confirmed against `KnownDomains.TRUSTED_DOMAINS`, which does not contain `notion.com`.
**Rule:** Derive signal strength from the quantitative field, not the vendor's free-text label; let the label decide only whether a signal is emitted at all. Where a strength value can override a numeric threshold downstream, test the BOUNDARY and the LOW end — a suite that only exercises high scores cannot see the disagreement, because that is exactly where label and score stop agreeing. Corollary: if two UI elements are fed by two different derivations of the same judgement, they will eventually contradict each other on screen; assert the pair together.
**Status:** active

## 2026-09-22 — "Handles both cases" is not the same as "tells both cases apart"
**Mistake:** During the copy audit I inspected `LinkInterceptActivity.showSafeScreen`, saw that `ScoringEngine.isCoverageWarning` matched BOTH the missing-coverage and partial-coverage reasons, and reported the line as correct ("saklaw ang parehong missing at partial, kaya hindi nagsisinungaling"). The opposite was true: matching both and emitting ONE string is what made it lie. After a scan where five of six providers answered, the screen told the user "Checked with local rules only — online verification was unavailable." The domain layer had the distinction right (`ScanOrchestrator` computes missing as 0 successes, partial as 1..n-1); the UI flattened it. I only caught it because the on-device retest of an unrelated fix put the false sentence on screen in my own evidence.
**Context:** Found while retesting the Hybrid Analysis strength fix on a Pixel_7 emulator, one step after I had cleared the same line by reading it.
**Rule:** When a predicate collapses several states into one boolean, do not clear the call site on the strength of the predicate's coverage — ask what the caller EMITS for each state it swallowed. A boolean that answers "is this one of N situations?" cannot drive a message that must name WHICH. Where the domain layer already distinguishes states, the UI reading them through an any()/boolean helper is the smell. Corollary: prefer an exhaustive `when` over a boolean at the point of display, so adding a state forces the compiler to demand its wording. Corollary 2: a static read of a shared helper is the weakest evidence available for a display defect — put the screen in front of yourself.
**Status:** active

## 2026-09-22 — A virtual-time test clock silently deletes the network layer it is supposed to exercise
**Mistake:** The new accuracy corpus went green on its first run — 21/21 — and the number was false. Written with `runTest`, the virtual clock advanced past the 8s `withTimeout` inside `ScanOrchestrator.guarded` the instant the provider suspended onto `Dispatchers.IO`, so every real provider was cancelled before it answered. The recorded Hybrid Analysis payload never reached the scan; notion.com scored 0% instead of the 14% it scores on-device, and "SAFE" read as a pass. The regression test for the session's headline bug was passing because its input had been thrown away.
**Context:** Building the false-positive corpus after shipping the HybridAnalysis strength fix. Caught only because the on-device score (14%) was known and the harness printed 0% — without that prior number the suite would have looked like proof.
**Rule:** `runTest` and any virtual-time dispatcher will cancel work that crosses onto a real dispatcher under a `withTimeout`, and the cancellation surfaces as an empty/failed result rather than an error. Use `runBlocking` for any test that drives real provider code, real HTTP seams, or anything wrapped in a production timeout. Corollary — the general rule: every test that depends on an input arriving must ASSERT THAT IT ARRIVED before asserting what it caused. Pin the observable evidence of delivery (here: `CoverageState.FULL` and the exact contributed score), or the test silently degrades into checking the default. Corollary 2: an all-green first run of a brand-new measurement is a claim about the measurement, not about the system; prove the harness can go red by reintroducing the defect it was built for.
**Status:** active
