# PLAN + MOCKUP — Redirect / Shortener Resolution (roadmap item #1)

Status: PLAN ONLY — awaiting approval. No code written yet.
Date: 2026-06-26
Risk: MEDIUM (adds an outbound network call to the suspicious host; sits on the click-time
critical path; changes what URL the whole pipeline scores). NOT a UI-first change — the core
is backend; one small UI surface (show resolved destination) is mocked separately.

## 1. Problem / goal

Today LinkGuard only **flags** a shortener ("URL shortener detected — destination hidden",
`UrlScanner.kt:343`) but never follows it. So for `bit.ly/x`, `tinyurl/...`, link wrappers,
or any 3xx redirect chain, the heuristics AND all 5 reputation providers see only the
**wrapper**, not the real malicious destination. AI-era scams lean on this (cheap, unique
wrappers in front of mass-generated landing domains).

Goal: before scoring, resolve the tapped URL to its **final destination** (safely, with
strict guards), then run the existing pipeline on the *resolved* URL so heuristics + Safe
Browsing/VirusTotal/HybridAnalysis/NextDNS/DomainAge all evaluate the true destination.
Keep the original tapped URL for display/history, and surface "tapped → goes to" to the user.

Out of scope (separate roadmap items): page-content/credential-form analysis (#5),
on-device ML (#4), meta-refresh/JavaScript redirects (HTTP 3xx only here).

## 2. Where it fits

```
                          ScanOrchestrator.scan(tappedUrl, messageText)
                                            │
                  ┌─────────────────────────┴─────────────────────────┐
   BEFORE (now)   │                          AFTER (proposed)          │
                  ▼                                                     ▼
        ┌──────────────────┐                        ┌──────────────────────────────┐
        │ 1. cache check    │                        │ 0. RESOLVE redirects (NEW)    │
        │ 2. heuristics(url)│                        │    tappedUrl → finalUrl + chain│
        │ 3. 5 providers(url│                        │    (skip if host is official)  │
        │    / domain)      │                        ├──────────────────────────────┤
        │ 4. scoring         │                       │ 1. cache check (key=tappedUrl) │
        │ 5. cache           │                       │ 2. heuristics(finalUrl)        │
        └──────────────────┘                        │ 3. 5 providers(finalUrl/domain)│
                                                     │ + redirect signal(s)           │
                                                     │ 4. scoring                     │
                                                     │ 5. cache                       │
                                                     └──────────────────────────────┘
```

Resolution is a single sequential step *before* the parallel provider block (the providers
need the final URL). It runs on a tight total budget so it does not wreck click-time UX.

## 3. Component: RedirectResolver (NEW)

`data/provider/RedirectResolver.kt` — constructor takes an OkHttpClient configured with
`followRedirects(false)` + `followSslRedirects(false)` so WE control every hop.

Returns:
```
data class RedirectResolution(
    val finalUrl: String,            // last URL reached (== input if no redirect)
    val hops: List<String>,          // ordered chain incl. input and final
    val crossedDomains: Boolean,     // final registrable domain != input's
    val outcome: Outcome             // RESOLVED | NO_REDIRECT | MAX_HOPS | LOOP |
)                                    //   BLOCKED_SCHEME | BLOCKED_PRIVATE_HOST |
                                     //   TIMEOUT | ERROR
```

Algorithm (per hop):
1. Stop if hops ≥ `REDIRECT_MAX_HOPS` (→ MAX_HOPS) or total elapsed ≥
   `REDIRECT_TOTAL_BUDGET_MS` (→ TIMEOUT). Return best-known finalUrl so far.
2. Issue **HEAD** (fall back to GET on 405/501); **never read the body** — only the status +
   `Location` header; cancel the call after headers. No cookies, no credentials, neutral
   User-Agent.
3. If status is not 3xx with a `Location` → done (NO_REDIRECT / RESOLVED).
4. Resolve `Location` against the current URL. Validate the next URL:
   - scheme must be http/https (else BLOCKED_SCHEME — `intent:`/`javascript:`/`data:`/`file:`).
   - host must NOT be loopback/private/link-local/unique-local (BLOCKED_PRIVATE_HOST) — don't
     let a hostile redirect probe the user's LAN/router.
   - if already visited → LOOP.
5. Append to chain; continue.

### Safety guards (explicit)
- **No execution / no rendering / no auto-open** — read-only header walk; verdict still gates
  opening exactly as today.
- followRedirects disabled → no hidden hops; we cap + inspect each one.
- HEAD + no-body → minimal side effects and no payload download.
- Private/loopback/link-local/ULA blocklist → no SSRF-style LAN probing from the device.
- Loop set + max hops + total time budget → bounded work.
- **Skip resolution entirely when the tapped host is already official/whitelisted**
  (reuse the trusted-domain check) → no latency for google.com etc.
- On any failure/timeout for a **shortener**, KEEP the existing "destination hidden" flag and
  treat it as still-suspicious (fail-safe: unknown ≠ safe).

### Known residuals (documented, not fixed here)
- Resolving means the **device contacts the suspicious server** (reveals IP, may consume
  one-time tokens). A browser opening it would do the same; we do it without opening/rendering.
  Mitigated with HEAD/no-cookies. A backend resolver proxy would remove this (future).
- meta-refresh / JS redirects not followed (no body parse) — only HTTP 3xx.

## 4. Orchestrator integration (`ScanOrchestrator.kt`)

- Inject `redirectResolver` as a constructor param with a no-op default (mirrors the existing
  `now: () -> Long` testability pattern), wired in `ScannerProvider`.
- New step 0 inside `scan()`: if host not official, `resolution = redirectResolver.resolve(url)`;
  let `scanUrl = resolution.finalUrl`. Use `scanUrl` for heuristics + the 5 providers + domain
  extraction. Keep `url` (tapped) for `ScanResult.url` (display/history); add `resolvedUrl`.
- Add redirect signal(s) to `allSignals` (see §5).
- Cache key stays `url + messageText` (same tapped link → same verdict).

## 5. Scoring signals (reuse `SignalSource.LOCAL_HEURISTIC`)

- `REDIRECT_RESOLVED` (info/low) — "Shortened link goes to <finalDomain>" (transparency).
- `REDIRECT_CROSS_DOMAIN` (weak/medium) — chain crosses domains / N hops.
- `REDIRECT_UNRESOLVED` (medium) — shortener/redirect could not be resolved (keep hidden-
  destination suspicion; do NOT treat as safe).
- The big win needs no new rule: existing CRITICAL signals (Safe Browsing etc.) now fire on
  the **final** URL.

## 6. UI surfacing (small — separate mock + approval before editing any XML)

Block screen + Scan Detail "TAPPED LINK" box shows the hop when it differs:

```
  TAPPED LINK
  bit.ly/3xK9p            ← what the user tapped
  ↳ goes to
  secure-login.amaz0n-verify.ru   ← resolved destination (mono, level-tinted)
```
History stays as-is (store `resolvedUrl` if cheap; else display tapped). Per the
mockup-first rule, a rendered PNG of this will be produced for approval BEFORE touching layout
files.

## 7. Config (`AppConfig.kt`)
- `REDIRECT_MAX_HOPS = 5`
- `REDIRECT_TOTAL_BUDGET_MS = 3000` (sequential, pre-providers — keep click-time tolerable)
- `REDIRECT_PER_HOP_TIMEOUT_MS = 1500`

## 8. Files to change
- NEW `data/provider/RedirectResolver.kt`
- `ScanOrchestrator.kt` (resolve step + resolvedUrl + redirect signals)
- `ScannerProvider.kt` (resolver + a no-redirect OkHttpClient)
- `AppConfig.kt` (3 constants)
- `domain/model/ScanResult.kt` (+ `resolvedUrl: String? = null`)
- (later, gated) UI: `activity_link_intercept.xml` / `ScanDetailActivity` for the hop display
- Tests (below)

## 9. Tests (match-to-risk)
- `RedirectResolverTest` (FakeHttp seam, same pattern as provider tests): no-redirect
  passthrough; single 301/302; multi-hop chain; MAX_HOPS exceeded; loop; non-http scheme
  blocked; private/loopback host blocked; HEAD→GET fallback (405); timeout → best-known +
  TIMEOUT; cross-domain flag.
- `ScanOrchestratorTest`: resolved URL is what heuristics/providers receive; official host
  skips resolution; unresolved shortener stays suspicious; tapped URL preserved on ScanResult.
- Verify: full `:app:testDebugUnitTest` green; `assembleDebug` green.

## 10. Risk / rollback
- Risk: extra latency on the click path (bounded by budget); contacting the hostile host
  (residual §3); a buggy resolver could mis-resolve → mitigated by tests + fail-safe (keep
  suspicion on failure, never downgrade).
- Rollback: resolver behind a single call site in the orchestrator + default no-op; `git revert`
  is clean. Optional kill-switch constant `REDIRECT_RESOLUTION_ENABLED`.

## 11. Sequencing
1. [DONE 2026-06-26] RedirectResolver + tests (pure, no UI).
2. [DONE 2026-06-26] Orchestrator wiring + signals + tests.
3. [DONE 2026-06-26] UI hop display — PNG mock approved (tasks/mockup-redirect-hop.png) → implemented.
4. [PENDING] Release as its own version (on-device smoke DONE — see below).

### On-device smoke (2026-06-26, Pixel_7 / API 34 emulator, debug build)
- Emulator had to run HEADLESS (`-no-window -gpu swiftshader_indirect`) — the windowed boot crashed
  on `Failed to load opengl32sw` (host GL), unrelated to the app.
- Resolver wiring CONFIRMED: every scan logs `RedirectResolver` attempting the exact tapped URL.
- Fail-safe CONFIRMED across 4 real failure modes — DNS failure (httpbin.org), slow-IPv6 connect
  timeout (nghttp2.org), cleartext blocked by the app's network-security policy (http→10.0.2.2),
  and read timeout (httpstat.us). Each → graceful fallback to the original URL, scan completes,
  verdict renders, NO crash. (The 1.5s/3s budget is production-appropriate but too tight for the
  emulator's slow IPv6 TLS — an emulator artifact, not a defect.)
- SUCCESS path CAPTURED with a TEST-ONLY budget bump (8s/12s, reverted after): tapped
  `https://nghttp2.org/httpbin/redirect-to?url=https://example.com/` → resolver followed to
  `https://example.com/`; providers + heuristics scored the RESOLVED destination (logcat:
  `HA Search (https://example.com/)` + HA Suspicious match); block screen showed the populated
  "↳ GOES TO https://example.com/" row, verdict-tinted. Screenshot: tasks/smoke-redirect-resolved.png.
- Budget constants REVERTED to 3000/1500; full unit suite re-run GREEN after revert.
- Finding (documented): the app's cleartext policy means redirect resolution only follows HTTPS
  hops; http targets are blocked (the tapped http link still gets the "Unencrypted HTTP" flag).

### Step 3 record (UI)
- Mock: tasks/mockup-redirect-hop.html/.png (approved).
- `data/ScanData.kt` legacy ScanResult +`resolvedUrl` (NOT persisted — like flagGroups; no migration).
- `ScanMapper.toLegacy` carries resolvedUrl; `AppConfig.Extras.RESOLVED_URL` added.
- Block screen (`activity_link_intercept.xml` + LinkInterceptActivity.setTappedLink): "↳ Goes to"
  label + mono resolved URL inside the TAPPED LINK chip, verdict-tinted, shown only when redirected
  (SAFE/SUSPICIOUS/DANGER); unchecked screen passes null → hidden.
- Scan Detail (`activity_scan_detail.xml` + ScanDetailActivity): same row in the URL card; fresh
  scans pass resolvedUrl via newIntent(result); history-loaded results have null → hidden.
- String `link_goes_to_label`. Tests: ScanMapperTest +2 (carry-through + null). VERIFY: 241 tests
  0 failures; assembleDebug GREEN. NOT yet on-device-verified.

## 12. Implementation record (Steps 1–2, backend only)

Files added:
- `domain/model/RedirectResolution.kt` — RedirectResolution + RedirectOutcome (8 outcomes).
- `domain/scanner/RedirectResolver.kt` — interface (domain layer, mirrors SignalProvider).
- `data/provider/HttpRedirectResolver.kt` — OkHttp impl: HEAD (GET fallback), no body read,
  HttpUrl.resolve() for relative/scheme handling, anti-SSRF host block (IP-literal parse, no DNS
  for named hosts), loop/hop/time bounds. Never throws (failures → ERROR + best-known URL).

Files changed:
- `domain/model/ScanResult.kt` — +`resolvedUrl: String? = null`.
- `util/AppConfig.kt` — REDIRECT_MAX_HOPS=5, REDIRECT_TOTAL_BUDGET_MS=3000, REDIRECT_PER_HOP_TIMEOUT_MS=1500.
- `domain/orchestrator/ScanOrchestrator.kt` — optional `redirectResolver` (null = legacy
  behaviour); step 1b resolves before heuristics/providers (skips trusted hosts via
  KnownDomains.isTrusted); scores the resolved URL; `redirectSignals()` adds CROSS_DOMAIN(weak10)
  / UNRESOLVED(medium15) / BLOCKED_TARGET(strong40); sets resolvedUrl; cache key stays tapped URL.
- `ScannerProvider.kt` — dedicated `redirectHttpClient` (followRedirects=false, 1.5s timeouts) +
  HttpRedirectResolver wired into the orchestrator. All 3 entry points (tap/manual/notification)
  inherit it automatically.

Tests: `HttpRedirectResolverTest` (11: no-redirect, single, multi-hop, loop, max-hops, blocked
scheme, blocked private host, HEAD→GET fallback, timeout, same-domain not cross, network error)
+ `ScanOrchestratorTest` (+3: resolved URL scanned & tapped preserved, trusted host skips,
blocked-target signal). VERIFY: full suite = 239 tests, 0 failures; assembleDebug GREEN.

RESIDUAL: device contacts the suspicious host on resolve (HEAD/no-cookies; documented); HTTP 3xx
only (no meta-refresh/JS); named host → private IP not blocked (would need DNS). UI hop display
(Step 3) not started — needs PNG mock + approval before any XML.
