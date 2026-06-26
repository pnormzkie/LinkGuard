# PLAN — Credential-Form / Page-Content Check (roadmap item #5)

Status: PLAN ONLY — awaiting approval. No code written yet.
Date: 2026-06-27
Risk: MEDIUM–HIGH. Adds an outbound GET of the page BODY on the click-time path; touches
privacy (fetches HTML of a user-tapped link), performance (bounded fetch), and the verdict.
NOT a UI change — backend only. No new UI surface in the MVP.

## 1. Problem / goal

URL heuristics + reputation catch links whose *address* looks wrong (brand-spoof, NRD,
bad TLD, known-bad reputation). They are blind to **brand-less, zero-hour AI phishing**: a
freshly generated domain with a clean-looking address that serves a **password/login form**
to harvest credentials. Nothing in the URL betrays it; only the page behaviour does.

Goal: when a destination is **already untrusted and borderline-suspicious**, fetch its HTML
(bounded, read-only) and detect a **credential form** (a password input, esp. one that posts
to a different registrable domain). Treat that as a confirming signal that escalates the
verdict. Keep it OFF for trusted/official domains and for clearly-clean scans.

## 2. Why gated/conditional (the FP + privacy + perf crux)

A login form ALONE is not malicious — every legit site has one. Fetching the HTML of every
tapped link is a privacy/perf cost we should not pay. So the check is a **second-phase,
conditional amplifier**, not a standalone provider:

- **Never fetch** when `KnownDomains.isTrusted(domain)` (google/fb/banks/etc. — obvious logins).
- **Never fetch** clearly-clean scans (no risk signals at all) — nothing to confirm.
- **Only fetch** when phase-1 already produced suspicion on an untrusted host, i.e. the
  preliminary verdict is SUSPICIOUS+ OR a domain-risk signal fired (NRD / brand / suspicious
  TLD / homoglyph). A found credential form then CONFIRMS → escalate.

This minimizes fetches (only borderline cases), privacy exposure (clean + trusted never
fetched), and false positives (a legit unknown login page with no other risk is never fetched
and never flagged).

## 3. Where it fits (ScanOrchestrator — new phase 4)

```
cache → 1b redirect resolve → 2 heuristics → 3 parallel providers → [score phase-1]
   → 4 NEW: if (!trusted && phase-1 suspicious) → fetch+inspect final URL HTML
        → if credential form: add signal → [re-score]  → result
```

Insertion point: after `results.awaitAll()` and a preliminary `scoringEngine.evaluate(...)`,
gated, then a single re-evaluate if a content signal is added. Trusted-host skip already
matches the redirect/age providers.

## 4. Component design

`data/provider/CredentialFormInspector` (NOT a parallel SignalProvider — it is invoked
conditionally with the resolved URL, so it stays out of the always-on `awaitAll` set):

- `suspend fun inspect(finalUrl: String): List<ScanSignal>`
- Guards (reuse existing): non-http(s) → empty; `KnownDomains.isTrusted` → empty;
  private/loopback host (`HttpRedirectResolver.isPrivateOrLocalHost`) → empty (anti-SSRF).
- Fetch: `GET` with a **dedicated bounded client** — short timeout (~3s), and read **at most
  N bytes** of the body (e.g. 256 KB cap via a capped source/`peek`), then close. Read-only;
  body is parsed in-memory and never stored/logged.
- Detect (lightweight, dependency-free string/regex scan — NO JS execution, NO HTML DOM lib):
  - `<input ... type="password" ...>` present  → credential form.
  - `<form ... action="<abs-url on a different registrable domain>">` enclosing/near a
    password input → cross-domain credential POST (stronger), excluding known IdP hosts.
- Emit:
  - password input on untrusted host → **MEDIUM / score 25**, `CREDENTIAL_FORM_UNTRUSTED`,
    "Login form on an unverified site".
  - password input + cross-domain action → **STRONG / score 50**, `CREDENTIAL_FORM_EXFIL`,
    "This page sends your password to another site".
  - none found → empty.

Source: `LOCAL_HEURISTIC` (it is behavioural analysis of the page, not external reputation).

## 5. Scoring interaction (no ScoringEngine rule change needed)

Scores sum, so the gate + existing signals do the work:
- form(25) + NRD VERY_NEW(45) = 70 → THREAT.
- form(25) + suspicious-TLD(25) = 50 → SUSPICIOUS→ (with any third) THREAT.
- form(25) alone (only reached if phase-1 was already suspicious) → at least SUSPICIOUS.
- cross-domain exfil(50, STRONG) → SUSPICIOUS on its own, THREAT with any add.
Re-evaluate once after adding the content signal(s). (If testing shows we want a hard rule
"credential form + NRD → THREAT", add it then — start without it.)

## 6. Safety / invariants

- Read-only: never opens/renders; GET + bounded read; no auto-redirect (URL already resolved).
- Anti-SSRF: private/loopback/link-local hosts skipped (shared helper).
- Bounded: timeout + byte cap; runs under the orchestrator's per-step timeout too.
- Privacy: HTML parsed in memory, discarded; nothing about page content persisted or logged
  (debug logs gated by BuildConfig.DEBUG and must not include body text).
- Trusted/official domains never fetched.

## 7. Known limitations (state honestly)

- **JS-rendered forms are missed** — we do not execute JavaScript; only static HTML is scanned.
  Many phishing kits still ship static forms, so this has real value, but it is not complete.
- A determined kit can cloak by UA/geo or render the form via JS → not caught here.
- Adds latency to borderline scans (bounded). Clean/trusted scans are unaffected.

## 8. Tests (match coverage to risk)

`CredentialFormInspectorTest` (FakeHttp seam):
- password input present (untrusted) → MEDIUM signal.
- password input + cross-domain action → STRONG signal.
- no password input → empty.
- trusted domain → empty, no request made.
- private host → empty, no request.
- non-2xx / network error → empty or fail-soft (define: empty + logged; it is an amplifier,
  must not break the scan).
- body byte-cap respected (large body truncated, still parsed).
`ScanOrchestratorTest`:
- trusted host → inspector not invoked.
- clean phase-1 (no signals) → inspector not invoked.
- borderline phase-1 + credential form → verdict escalates; tapped URL preserved.
Full suite must stay green.

## 9. Risk & rollback

- Risk: MEDIUM–HIGH (network body fetch on click path + verdict change). Mitigated by gating,
  bounds, and trusted-skip.
- Rollback: feature is additive and isolated; a single flag (inject inspector = null, like
  `redirectResolver`) disables it without touching scoring. Revert = drop the phase-4 block.

## 10. Out of scope (separate items)

- JS/dynamic rendering, headless browser, screenshot/visual similarity.
- On-device ML (#4).
- Any new UI surface (could later show "this page asked for your password").
