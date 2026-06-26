package com.linkguard.app.domain.orchestrator

import android.util.Log
import com.linkguard.app.BuildConfig
import com.linkguard.app.domain.model.RedirectOutcome
import com.linkguard.app.domain.model.RedirectResolution
import com.linkguard.app.domain.model.ScanResult
import com.linkguard.app.domain.model.ScanSignal
import com.linkguard.app.domain.model.SignalSource
import com.linkguard.app.domain.model.SignalStrength
import com.linkguard.app.domain.scanner.HeuristicEngine
import com.linkguard.app.domain.scanner.RedirectResolver
import com.linkguard.app.domain.scanner.SignalProvider
import com.linkguard.app.domain.scoring.ScoringEngine
import com.linkguard.app.util.AppConfig
import com.linkguard.app.util.DomainExtractor
import com.linkguard.app.util.KnownDomains
import kotlinx.coroutines.*

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
    private val redirectResolver: RedirectResolver? = null
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

    suspend fun scan(url: String, messageText: String? = null): ScanResult = supervisorScope {
        // 1. Check Cache — entries past the TTL are treated as a miss and re-scanned
        //    so a stale verdict can't outlive a domain going bad.
        val cacheKey = url + (messageText ?: "")
        val startedAt = now()
        cacheGet(cacheKey)?.let { cached ->
            if (startedAt - cached.cachedAt < AppConfig.SCAN_CACHE_TTL_MS) {
                if (BuildConfig.DEBUG) Log.d(TAG, "Cache hit for: $url")
                return@supervisorScope cached.result.copy(timestamp = startedAt)
            }
        }

        // 1b. Resolve redirects/shorteners to the true destination so heuristics + every
        //     provider score the real landing page, not just a wrapper. Skipped for trusted
        //     hosts (no need + saves latency). The verdict still gates opening; we never open.
        val resolution = redirectResolver
            ?.takeIf { !KnownDomains.isTrusted(DomainExtractor.extract(url)) }
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

        // 3. Parallel API checks, each with its own timeout so one slow provider
        //    cannot starve the others. null means the check itself failed.
        val sbDeferred = async { guarded("SafeBrowsing", scanUrl, reputationProvider) }
        val dnsDeferred = async { guarded("NextDNS", domain, domainSignalProvider) }
        val vtDeferred = async { guarded("VirusTotal", scanUrl, enrichmentProvider) }
        val haDeferred = async { guarded("HybridAnalysis", scanUrl, hybridAnalysisProvider) }
        val daDeferred = async { guarded("DomainAge", domain, domainAgeProvider) }
        val uhDeferred = async { guarded("URLhaus", domain, urlHausProvider) }

        val results = listOf(sbDeferred, dnsDeferred, vtDeferred, haDeferred, daDeferred, uhDeferred).awaitAll()
        val externalCoverageMissing = results.all { it == null }
        results.filterNotNull().forEach { allSignals.addAll(it) }

        Log.d(
            TAG,
            "Signals Found -> SB: ${results[0]?.size ?: "failed"}, DNS: ${results[1]?.size ?: "failed"}, " +
                "VT: ${results[2]?.size ?: "failed"}, HA: ${results[3]?.size ?: "failed"}, " +
                "DA: ${results[4]?.size ?: "failed"}, UH: ${results[5]?.size ?: "failed"}"
        )

        val finalVerdict = scoringEngine.evaluate(allSignals, externalCoverageMissing)
        val result = ScanResult(
            url = url,
            normalizedUrl = scanUrl.lowercase(),
            verdict = finalVerdict,
            timestamp = startedAt,
            resolvedUrl = resolution?.takeIf { it.redirected }?.finalUrl
        )

        // Don't cache unvetted results — retry external checks on the next scan.
        if (!externalCoverageMissing) {
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

            RedirectOutcome.MAX_HOPS, RedirectOutcome.LOOP, RedirectOutcome.TIMEOUT ->
                listOf(unresolvedSignal(resolution.finalUrl))
            // A transport error mid-chain leaves the true destination unverified; a failure on
            // the very first hop is just an unreachable site, not a redirect signal.
            RedirectOutcome.ERROR ->
                if (resolution.redirected) listOf(unresolvedSignal(resolution.finalUrl)) else emptyList()

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
        ruleId = "REDIRECT_UNRESOLVED",
        title = "Redirect destination could not be verified",
        description = "The link forwards through redirects that could not be fully followed.",
        strength = SignalStrength.MEDIUM,
        source = SignalSource.LOCAL_HEURISTIC,
        score = 15,
        matchedValue = finalUrl
    )

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
}
