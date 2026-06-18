package com.linkguard.app.domain.orchestrator

import android.util.Log
import com.linkguard.app.BuildConfig
import com.linkguard.app.domain.model.ScanResult
import com.linkguard.app.domain.model.ScanSignal
import com.linkguard.app.domain.scanner.HeuristicEngine
import com.linkguard.app.domain.scanner.SignalProvider
import com.linkguard.app.domain.scoring.ScoringEngine
import com.linkguard.app.util.AppConfig
import com.linkguard.app.util.DomainExtractor
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
    // Injected so cache-expiry behaviour is testable without real time passing.
    private val now: () -> Long = System::currentTimeMillis
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

        val domain = DomainExtractor.extract(url) ?: url
        val allSignals = mutableListOf<ScanSignal>()

        // 2. Run Heuristics — local and fast, always completes regardless of network state
        val heuristicSignals = runCatching { heuristicEngine.scan(url, messageText) }
            .onFailure { Log.e(TAG, "Heuristic engine failed${if (BuildConfig.DEBUG) " for $url" else ""}", it) }
            .getOrDefault(emptyList())
        allSignals.addAll(heuristicSignals)

        // 3. Parallel API checks, each with its own timeout so one slow provider
        //    cannot starve the others. null means the check itself failed.
        val sbDeferred = async { guarded("SafeBrowsing", url, reputationProvider) }
        val dnsDeferred = async { guarded("NextDNS", domain, domainSignalProvider) }
        val vtDeferred = async { guarded("VirusTotal", url, enrichmentProvider) }
        val haDeferred = async { guarded("HybridAnalysis", url, hybridAnalysisProvider) }
        val daDeferred = async { guarded("DomainAge", domain, domainAgeProvider) }

        val results = listOf(sbDeferred, dnsDeferred, vtDeferred, haDeferred, daDeferred).awaitAll()
        val externalCoverageMissing = results.all { it == null }
        results.filterNotNull().forEach { allSignals.addAll(it) }

        Log.d(
            TAG,
            "Signals Found -> SB: ${results[0]?.size ?: "failed"}, DNS: ${results[1]?.size ?: "failed"}, " +
                "VT: ${results[2]?.size ?: "failed"}, HA: ${results[3]?.size ?: "failed"}, " +
                "DA: ${results[4]?.size ?: "failed"}"
        )

        val finalVerdict = scoringEngine.evaluate(allSignals, externalCoverageMissing)
        val result = ScanResult(
            url = url,
            normalizedUrl = url.lowercase(),
            verdict = finalVerdict,
            timestamp = startedAt
        )

        // Don't cache unvetted results — retry external checks on the next scan.
        if (!externalCoverageMissing) {
            cachePut(cacheKey, CacheEntry(result, startedAt))
        }
        result
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
}
