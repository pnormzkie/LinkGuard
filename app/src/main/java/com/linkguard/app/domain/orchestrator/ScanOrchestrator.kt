package com.linkguard.app.domain.orchestrator

import android.util.Log
import com.linkguard.app.BuildConfig
import com.linkguard.app.domain.model.RedirectOutcome
import com.linkguard.app.domain.model.RedirectResolution
import com.linkguard.app.domain.model.ScanResult
import com.linkguard.app.domain.model.ScanSignal
import com.linkguard.app.domain.model.SignalSource
import com.linkguard.app.domain.model.SignalStrength
import com.linkguard.app.domain.model.Verdict
import com.linkguard.app.domain.scanner.CredentialFormInspector
import com.linkguard.app.domain.scanner.HeuristicEngine
import com.linkguard.app.domain.scanner.RedirectResolver
import com.linkguard.app.domain.scanner.SignalProvider
import com.linkguard.app.domain.scoring.ScoringEngine
import com.linkguard.app.util.AppConfig
import com.linkguard.app.util.DomainExtractor
import com.linkguard.app.util.KnownDomains
import com.linkguard.app.util.UrlPrivacy
import kotlinx.coroutines.*
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Orchestrates the full scanning pipeline including heuristics, reputation,
 * domain signals, and enrichment with timeout handling and failure isolation.
 */
class ScanOrchestrator(
    private val heuristicEngine: HeuristicEngine,
    private val reputationProvider: SignalProvider,
    private val domainSignalProvider: SignalProvider,
    private val enrichmentProvider: SignalProvider,
    private val scoringEngine: ScoringEngine,
    private val hybridAnalysisProvider: SignalProvider,
    private val domainAgeProvider: SignalProvider,
    private val urlHausProvider: SignalProvider,
    // Injected so cache-expiry behaviour is testable without real time passing.
    private val now: () -> Long = System::currentTimeMillis,
    // Optional: when present, the tapped URL is followed to its true destination before scoring.
    // Null keeps the legacy behaviour (score the URL as-is).
    private val redirectResolver: RedirectResolver? = null,
    // Optional: when present, a borderline-suspicious untrusted destination has its page HTML
    // inspected for a credential form (a confirming amplifier). Null disables the check.
    private val credentialFormInspector: CredentialFormInspector? = null
) {

    companion object {
        private const val TAG = "ScanOrchestrator"
    }

    private data class CacheEntry(val result: ScanResult, val cachedAt: Long)

    // Access-ordered LRU bounded to RECENT_SCAN_CACHE_SIZE. A plain LinkedHashMap is used
    // (not android.util.LruCache) so the cache is exercised under JVM unit tests, where the
    // Android stub would always miss. Guarded by synchronized for cross-thread safety.
    private val scanCache = object : LinkedHashMap<String, CacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: Map.Entry<String, CacheEntry>): Boolean =
            size > AppConfig.RECENT_SCAN_CACHE_SIZE
    }

    private fun cacheGet(key: String): CacheEntry? = synchronized(scanCache) { scanCache[key] }

    private fun cachePut(key: String, entry: CacheEntry) {
        synchronized(scanCache) { scanCache[key] = entry }
    }

    suspend fun scan(
        url: String,
        messageText: String? = null,
        allowNetworkChecks: Boolean = true
    ): ScanResult = supervisorScope {
        // 1. Check Cache — entries past the TTL are treated as a miss and re-scanned
        //    so a stale verdict can't outlive a domain going bad.
        // NUL cannot appear in a URL, so (url, text) pairs map to distinct keys.
        val cacheKey = url + "\u0000" + (messageText ?: "")
        val startedAt = now()
        cacheGet(cacheKey)?.let { cached ->
            if (startedAt - cached.cachedAt < AppConfig.SCAN_CACHE_TTL_MS) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Cache hit for: $url")
                return@supervisorScope cached.result.copy(timestamp = startedAt)
            }
        }

        // 1b. Resolve redirects/shorteners to the true destination so heuristics + every
        //     provider score the real landing page, not just a wrapper. Normal trusted URLs
        //     skip this for latency, but redirect-capable query parameters are still resolved
        //     to prevent trusted-host open-redirect bypasses. The verdict still gates opening.
        val originalDomain = DomainExtractor.extract(url)
        // A trusted link wrapper (google.com/url?q=, l.facebook.com/l.php?u=, youtube.com/redirect?q=)
        // carries its destination in a parameter and answers with its own interstitial instead of a
        // redirect, so following it never reached the destination: the wrapper scored SAFE 0%
        // whatever it pointed at. The carried address is scanned as the link's destination instead.
        val wrapped = wrappedDestination(url)
        val resolution = if (wrapped != null) {
            unwrapped(url, wrapped, redirectResolver?.takeIf { allowNetworkChecks }?.resolve(wrapped))
        } else redirectResolver
            ?.takeIf {
                allowNetworkChecks &&
                    (!KnownDomains.isTrusted(originalDomain) || hasRedirectParameter(url))
            }
            ?.resolve(url)
        val scanUrl = resolution?.finalUrl ?: url

        val domain = DomainExtractor.extract(scanUrl) ?: scanUrl
        val allSignals = mutableListOf<ScanSignal>()

        // 2. Run Heuristics — local and fast, always completes regardless of network state
        val heuristicSignals = runCatching { heuristicEngine.scan(scanUrl, messageText) }
            .onFailure { Log.e(TAG, "Heuristic engine failed${if (BuildConfig.DEBUG) " for $scanUrl" else ""}", it) }
            .getOrDefault(emptyList())
        allSignals.addAll(heuristicSignals)
        resolution?.let { allSignals.addAll(redirectSignals(it)) }

        // 2b. Everything above scores the destination. A host that answered this check with a
        //     redirect can answer a browser with something else: a phishing server sends
        //     security checks to the real brand's site and everyone else to its fake page. So
        //     what such a host's own name says about it, and what the message says, still
        //     counts after it forwarded. A finding the destination already has is not repeated.
        val forwarders = resolution?.let { unvettedForwarders(it) }.orEmpty()
        forwarders.forEach { hop ->
            runCatching { heuristicEngine.scan(hop, messageText) }
                .onFailure { Log.e(TAG, "Heuristic engine failed${if (BuildConfig.DEBUG) " for $hop" else ""}", it) }
                .getOrDefault(emptyList())
                .filter { isForwarderEvidence(it.ruleId) }
                .forEach { finding ->
                    if (allSignals.none { it.ruleId == finding.ruleId }) allSignals.add(finding)
                }
        }
        // A link that serves an Android app file, even when nothing in the URL says ".apk".
        if (resolution?.finalContentType?.startsWith(APK_CONTENT_TYPE, ignoreCase = true) == true &&
            allSignals.none { it.ruleId == "LOCAL_DANGEROUS_FILE_TYPE_APK" }
        ) {
            allSignals.add(
                ScanSignal(
                    ruleId = "DOWNLOADS_ANDROID_APP",
                    title = "Link downloads an Android app",
                    description = "This link delivers an app file (APK) instead of a web page — apps from outside the Play Store are a common way to spread malware.",
                    strength = SignalStrength.MEDIUM,
                    source = SignalSource.LOCAL_HEURISTIC,
                    score = 25,
                    matchedValue = resolution.finalUrl
                )
            )
        }

        // Start bounded static inspection alongside providers only when local/redirect evidence
        // has not already decided THREAT. Eligibility is side-effect-free and retains all fetch
        // safeguards inside the inspector.
        val localEvidenceIsThreat =
            scoringEngine.evaluate(allSignals, externalCoverageMissing = false).verdict == Verdict.THREAT
        val contentDeferred = credentialFormInspector
            ?.takeIf { allowNetworkChecks && !localEvidenceIsThreat && it.isEligible(scanUrl) }
            ?.let { async { inspectContent(scanUrl) } }

        // 3. Parallel API checks, each with its own timeout so one slow provider
        //    cannot starve the others. null means the check itself failed.
        //    Full-URL providers receive a sanitized copy (no userinfo, no fragment) —
        //    URL credentials and browser-local fragments never leave the device.
        val lookupUrl = UrlPrivacy.sanitizeForExternalLookup(scanUrl)
        // For the same reason the blocklists and the domain age are asked about each forwarding
        // host too, not only about the destination: otherwise nothing was ever asked about the
        // link the user received, and a blocklisted link read SAFE.
        val forwarderLookups = forwarders.mapNotNull { hop ->
            UrlPrivacy.sanitizeForExternalLookup(hop)?.takeIf { it != lookupUrl }
                ?.let { it to DomainExtractor.extract(hop) }
        }
        val results = if (allowNetworkChecks && lookupUrl != null) {
            val checks = mutableListOf(
                async { guarded("SafeBrowsing", lookupUrl, reputationProvider) },
                async { guarded("NextDNS", domain, domainSignalProvider) },
                async { guarded("VirusTotal", lookupUrl, enrichmentProvider) },
                async { guarded("HybridAnalysis", lookupUrl, hybridAnalysisProvider) },
                async { guarded("DomainAge", domain, domainAgeProvider) },
                async { guarded("URLhaus", lookupUrl, urlHausProvider) }
            )
            forwarderLookups.forEach { (hopUrl, hopDomain) ->
                checks += async { guarded("SafeBrowsing (forwarding link)", hopUrl, reputationProvider) }
                checks += async { guarded("VirusTotal (forwarding link)", hopUrl, enrichmentProvider) }
                checks += async { guarded("URLhaus (forwarding link)", hopUrl, urlHausProvider) }
                if (hopDomain != null) {
                    checks += async { guarded("DomainAge (forwarding link)", hopDomain, domainAgeProvider) }
                }
            }
            checks.awaitAll()
        } else {
            // Automatic notification quotas protect network/API resources, never the cheap local
            // detector. null keeps the verdict visibly local-only and prevents safe-result caching.
            List<List<ScanSignal>?>(PROVIDER_COUNT) { null }
        }
        val successfulProviderCount = results.count { it != null }
        val externalCoverageMissing = successfulProviderCount == 0
        val externalCoveragePartial = successfulProviderCount in 1 until results.size
        results.take(PROVIDER_COUNT).filterNotNull().forEach { allSignals.addAll(it) }
        // From a forwarding link only decisive provider findings count. A weak one (host-level
        // history, one vendor) is supporting evidence that click-trackers also carry, so it
        // would turn every resolved link suspicious. A finding both ends share is counted
        // once, at the stronger of the two.
        results.drop(PROVIDER_COUNT).filterNotNull().flatten()
            .filter { it.strength == SignalStrength.STRONG || it.strength == SignalStrength.CRITICAL }
            .forEach { tapped ->
                val sameRule = allSignals.firstOrNull { it.ruleId == tapped.ruleId }
                if (sameRule == null) {
                    allSignals.add(tapped)
                } else if (tapped.score > sameRule.score) {
                    allSignals.remove(sameRule)
                    allSignals.add(tapped)
                }
            }

        Log.d(
            TAG,
            "Signals Found -> SB: ${results[0]?.size ?: "failed"}, DNS: ${results[1]?.size ?: "failed"}, " +
                "VT: ${results[2]?.size ?: "failed"}, HA: ${results[3]?.size ?: "failed"}, " +
                "DA: ${results[4]?.size ?: "failed"}, UH: ${results[5]?.size ?: "failed"}"
        )

        // 4. Include completed page evidence only when provider/heuristic evidence is not already
        //    THREAT. This preserves the historical flags and scoring for provider-confirmed threats.
        var finalVerdict = scoringEngine.evaluate(
            allSignals,
            externalCoverageMissing = externalCoverageMissing,
            externalCoveragePartial = externalCoveragePartial
        )
        val contentSignals = contentDeferred?.await().orEmpty()
        if (finalVerdict.verdict != Verdict.THREAT) {
            if (BuildConfig.DEBUG) {
                Log.d(TAG, "Credential-form check ran for $domain -> found ${contentSignals.size} signal(s)")
            }
            if (contentSignals.isNotEmpty()) {
                allSignals.addAll(contentSignals)
                finalVerdict = scoringEngine.evaluate(
                    allSignals,
                    externalCoverageMissing = externalCoverageMissing,
                    externalCoveragePartial = externalCoveragePartial
                )
            }
        }

        val result = ScanResult(
            url = url,
            normalizedUrl = scanUrl.lowercase(),
            verdict = finalVerdict,
            timestamp = startedAt,
            resolvedUrl = resolution?.takeIf { it.redirected }?.finalUrl,
            // Not for an unwrapped link: opening it still goes through the wrapper, so the
            // platform's own warning page stays in front of the destination.
            verifiedResolvedUrl = resolution
                ?.takeIf { wrapped == null && it.redirected && it.outcome == RedirectOutcome.RESOLVED }
                ?.finalUrl
        )

        // Cache only fully vetted results. Partial provider outages must be retried on the next
        // scan instead of preserving an overconfident clean verdict for the full TTL. An
        // incomplete redirect resolution (timeout/loop/hop limit) also stays uncached: the
        // landing page was never verified, so the verdict may change on a retry.
        val resolutionComplete = resolution == null ||
            resolution.outcome == RedirectOutcome.NO_REDIRECT ||
            resolution.outcome == RedirectOutcome.RESOLVED
        if (resolutionComplete && !externalCoverageMissing && !externalCoveragePartial) {
            cachePut(cacheKey, CacheEntry(result, startedAt))
        }
        result
    }

    /**
     * Turns a redirect resolution into scoring signals. The big win needs no rule here — the
     * providers + heuristics already ran on the final URL. These only capture the redirect act
     * itself: a cross-domain hop (weak), an unverifiable chain (medium, keeps suspicion), or a
     * redirect to a non-web/internal target (strong — a classic abuse pattern).
     */
    private fun redirectSignals(resolution: RedirectResolution): List<ScanSignal> {
        val finalDomain = DomainExtractor.extract(resolution.finalUrl) ?: resolution.finalUrl
        return when (resolution.outcome) {
            RedirectOutcome.RESOLVED ->
                if (resolution.crossedDomains) listOf(
                    ScanSignal(
                        ruleId = "REDIRECT_CROSS_DOMAIN",
                        title = "Link redirects to another site",
                        description = "This link forwards through a redirect to $finalDomain.",
                        strength = SignalStrength.WEAK,
                        source = SignalSource.LOCAL_HEURISTIC,
                        score = 10,
                        matchedValue = resolution.finalUrl
                    )
                ) else emptyList()

            // A chain that stopped early leaves the true destination unverified; a failure or
            // timeout on the very first hop is just an unreachable site, not a redirect signal.
            // Warning about a dead link is noise: it never resolved for the user either.
            RedirectOutcome.MAX_HOPS, RedirectOutcome.LOOP ->
                if (resolution.redirected) listOf(unresolvedSignal(resolution.finalUrl)) else emptyList()

            // A slow or failed hop that never left the tapped site is that site's own redirect
            // (www/apex, http/https) on a slow server; the last URL reached is still scanned.
            // Stalling on the first hop already scores nothing, so this opens no new evasion.
            RedirectOutcome.TIMEOUT, RedirectOutcome.ERROR ->
                if (resolution.redirected && resolution.crossedDomains) {
                    listOf(unresolvedSignal(resolution.finalUrl))
                } else emptyList()

            RedirectOutcome.BLOCKED_SCHEME, RedirectOutcome.BLOCKED_PRIVATE_HOST -> listOf(
                ScanSignal(
                    ruleId = "REDIRECT_BLOCKED_TARGET",
                    title = "Link redirects to an unsafe target",
                    description = "This link tries to forward to a non-web or internal address — a common abuse pattern.",
                    strength = SignalStrength.STRONG,
                    source = SignalSource.LOCAL_HEURISTIC,
                    score = 40,
                    matchedValue = resolution.finalUrl
                )
            )

            RedirectOutcome.NO_REDIRECT -> emptyList()
        }
    }

    private fun unresolvedSignal(finalUrl: String) = ScanSignal(
        // Shared with ScoringEngine, which keys the category wording on this id.
        ruleId = ScoringEngine.REDIRECT_UNRESOLVED_RULE_ID,
        title = "Redirect destination could not be verified",
        description = "The link forwards through redirects that could not be fully followed — the real destination is unknown.",
        // STRONG, not MEDIUM: when the chain stops early the landing page was never checked.
        // A 15-point MEDIUM signal scored SAFE and got cached as a vetted clean verdict.
        strength = SignalStrength.STRONG,
        source = SignalSource.LOCAL_HEURISTIC,
        score = 25,
        matchedValue = finalUrl
    )

    /** Bounded + fail-soft wrapper around the credential-form inspector (an amplifier that
     *  must never break the scan). The inspector is itself fail-soft; the timeout guards the
     *  click-time path if a body fetch hangs. */
    private suspend fun inspectContent(scanUrl: String): List<ScanSignal> = try {
        withTimeout(AppConfig.CONTENT_FETCH_TIMEOUT_MS) {
            credentialFormInspector?.inspect(scanUrl) ?: emptyList()
        }
    } catch (e: TimeoutCancellationException) {
        Log.w(TAG, "Credential-form inspection timed out")
        emptyList()
    } catch (e: CancellationException) {
        throw e // never swallow real cancellation
    } catch (e: Exception) {
        Log.w(TAG, "Credential-form inspection failed: ${e.message}")
        emptyList()
    }

    private suspend fun guarded(
        name: String,
        input: String,
        provider: SignalProvider
    ): List<ScanSignal>? = try {
        withTimeout(AppConfig.PROVIDER_TIMEOUT_MS) { provider.fetchSignals(input) }
    } catch (e: TimeoutCancellationException) {
        Log.w(TAG, "$name timed out after ${AppConfig.PROVIDER_TIMEOUT_MS}ms${if (BuildConfig.DEBUG) " for $input" else ""}")
        null
    } catch (e: CancellationException) {
        throw e // never swallow real cancellation
    } catch (e: Exception) {
        Log.w(TAG, "$name check failed${if (BuildConfig.DEBUG) " for $input" else ""}: ${e.message}")
        null
    }

    private fun hasRedirectParameter(url: String): Boolean {
        val parsed = url.toHttpUrlOrNull() ?: return false
        val parameterNames = parsed.queryParameterNames.mapTo(mutableSetOf()) { it.lowercase() }
        if (parameterNames.any { it in REDIRECT_PARAMETER_NAMES }) return true

        // Several major trusted services use short, endpoint-specific parameter names. Keep
        // these scoped to their actual redirect paths so ordinary searches such as /search?q=
        // do not incur redirect probing or get mistaken for open redirects.
        // Matched as the site or any subdomain: the exact-name check missed l.facebook.com and
        // m.youtube.com, the hosts these links actually use.
        val host = parsed.host
        val path = parsed.encodedPath.lowercase()
        return when {
            isWithin(host, "google.com") && path == "/url" -> "q" in parameterNames
            isWithin(host, "facebook.com") && path == "/l.php" -> "u" in parameterNames
            isWithin(host, "youtube.com") && path == "/redirect" -> "q" in parameterNames
            else -> false
        }
    }

    private fun isWithin(host: String?, site: String): Boolean =
        host != null && (host == site || host.endsWith(".$site"))

    /** The http(s) address a trusted link wrapper carries, or null when [url] is not one. */
    private fun wrappedDestination(url: String): String? {
        val parsed = url.toHttpUrlOrNull() ?: return null
        val path = parsed.encodedPath.lowercase()
        val (_, _, parameters) = LINK_WRAPPERS
            .firstOrNull { (site, wrapperPath, _) -> isWithin(parsed.host, site) && path == wrapperPath }
            ?: return null
        return parameters.firstNotNullOfOrNull { parsed.queryParameter(it) }
            ?.takeIf { it.toHttpUrlOrNull() != null }
    }

    /**
     * The wrapper as the first hop in front of its carried [destination]. The destination is
     * known even when it cannot be fetched, so the result is RESOLVED unless the destination's
     * own chain left it and then failed — that keeps its outcome and unresolved-redirect signal.
     */
    private fun unwrapped(wrapperUrl: String, destination: String, inner: RedirectResolution?): RedirectResolution {
        val site = LINK_WRAPPERS.first { isWithin(wrapperUrl.toHttpUrlOrNull()?.host, it.first) }.first
        val finalUrl = inner?.finalUrl ?: destination
        val finished = inner == null || !inner.redirected ||
            inner.outcome == RedirectOutcome.NO_REDIRECT || inner.outcome == RedirectOutcome.RESOLVED
        return RedirectResolution(
            finalUrl = finalUrl,
            hops = listOf(wrapperUrl) + (inner?.hops ?: listOf(destination)),
            crossedDomains = !isWithin(finalUrl.toHttpUrlOrNull()?.host, site),
            outcome = if (finished) RedirectOutcome.RESOLVED else inner!!.outcome,
            finalContentType = inner?.finalContentType
        )
    }

    // site, wrapper path, parameters that carry the destination
    private val LINK_WRAPPERS = listOf(
        Triple("google.com", "/url", listOf("q", "url")),
        Triple("facebook.com", "/l.php", listOf("u")),
        Triple("youtube.com", "/redirect", listOf("q"))
    )

    private val APK_CONTENT_TYPE = "application/vnd.android.package-archive"

    private val REDIRECT_PARAMETER_NAMES = setOf(
        "url", "redirect", "redirect_uri", "redirect_url", "target", "dest", "destination",
        "continue", "next", "return", "return_to", "returnurl", "goto", "out"
    )

    private val PROVIDER_COUNT = 6

    /**
     * The addresses in the chain that sent this check on to another site and could have sent
     * a browser somewhere else. Not the destination itself, not a hop on the destination's own
     * site (www/https), and not a known forwarding service (a trusted site, a public
     * shortener, a brand's own short link, an app-install link). One address per host, at
     * most [MAX_FORWARDERS_CHECKED]: each costs four provider lookups.
     */
    private fun unvettedForwarders(resolution: RedirectResolution): List<String> {
        if (!resolution.redirected) return emptyList()
        val finalSite = DomainExtractor.extract(resolution.finalUrl)?.let(DomainExtractor::site)
        return resolution.hops.dropLast(1)
            .mapNotNull { hop -> DomainExtractor.extract(hop)?.let { host -> hop to host } }
            .filter { (_, host) ->
                DomainExtractor.site(host) != finalSite && !KnownDomains.isKnownForwarder(host)
            }
            .distinctBy { (_, host) -> host }
            .take(MAX_FORWARDERS_CHECKED)
            .map { (hop, _) -> hop }
    }

    private val MAX_FORWARDERS_CHECKED = 2

    /**
     * The local findings that say who a forwarding host claims to be, plus the wording of the
     * message. The shape of its address (subdomain depth, hyphens, random labels, length,
     * encoding, keywords, plain http) is left out on purpose: click-trackers share all of it.
     */
    private fun isForwarderEvidence(ruleId: String): Boolean =
        ruleId in FORWARDER_RULES || FORWARDER_RULE_PREFIXES.any { ruleId.startsWith(it) }

    private val FORWARDER_RULES = setOf(
        "LOCAL_ELEVATED_RISK_TLD", "LOCAL_IP_ADDRESS_HOST", "LOCAL_FAKE_COM_LABEL",
        "LOCAL_NUMBER_SUBSTITUTION_LOOKALIKE", "LOCAL_PUNYCODE_BRAND_LOOKALIKE",
        "LOCAL_MIXED_SCRIPT_DOMAIN", "LOCAL_SMISHING_MESSAGE_PATTERN"
    )

    private val FORWARDER_RULE_PREFIXES = listOf(
        "LOCAL_BRAND_SPOOF_", "LOCAL_BRAND_IN_SUBDOMAIN_", "LOCAL_TYPOSQUAT_", "LOCAL_BRAND_ON_FREE_HOSTING_"
    )
}
