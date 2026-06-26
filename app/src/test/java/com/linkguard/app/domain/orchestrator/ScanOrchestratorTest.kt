package com.linkguard.app.domain.orchestrator

import com.linkguard.app.domain.model.Confidence
import com.linkguard.app.domain.model.RedirectOutcome
import com.linkguard.app.domain.model.RedirectResolution
import com.linkguard.app.domain.model.ScanSignal
import com.linkguard.app.domain.model.SignalSource
import com.linkguard.app.domain.model.SignalStrength
import com.linkguard.app.domain.model.Verdict
import com.linkguard.app.domain.scanner.HeuristicEngine
import com.linkguard.app.domain.scanner.RedirectResolver
import com.linkguard.app.domain.scanner.SignalProvider
import com.linkguard.app.domain.scoring.ScoringEngine
import com.linkguard.app.util.AppConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class ScanOrchestratorTest {

    private class FakeProvider(
        private val block: suspend (String) -> List<ScanSignal>
    ) : SignalProvider {
        override suspend fun fetchSignals(input: String): List<ScanSignal> = block(input)
    }

    private class FakeHeuristic(
        private val block: suspend (String) -> List<ScanSignal> = { emptyList() }
    ) : HeuristicEngine {
        override suspend fun scan(url: String, messageText: String?): List<ScanSignal> = block(url)
    }

    private class FakeRedirectResolver(
        private val block: suspend (String) -> RedirectResolution
    ) : RedirectResolver {
        override suspend fun resolve(url: String): RedirectResolution = block(url)
    }

    private fun signal(
        score: Int,
        strength: SignalStrength = SignalStrength.MEDIUM,
        source: SignalSource = SignalSource.EXTERNAL_REPUTATION,
        title: String = "Test signal"
    ) = ScanSignal(
        ruleId = "TEST_RULE",
        title = title,
        description = "test",
        strength = strength,
        source = source,
        score = score
    )

    private fun orchestrator(
        heuristic: HeuristicEngine = FakeHeuristic(),
        reputation: SignalProvider = FakeProvider { emptyList() },
        domain: SignalProvider = FakeProvider { emptyList() },
        enrichment: SignalProvider = FakeProvider { emptyList() },
        hybrid: SignalProvider = FakeProvider { emptyList() },
        domainAge: SignalProvider = FakeProvider { emptyList() },
        now: () -> Long = { 0L },
        redirect: RedirectResolver? = null
    ) = ScanOrchestrator(
        heuristicEngine = heuristic,
        reputationProvider = reputation,
        domainSignalProvider = domain,
        enrichmentProvider = enrichment,
        scoringEngine = ScoringEngine(),
        hybridAnalysisProvider = hybrid,
        domainAgeProvider = domainAge,
        now = now,
        redirectResolver = redirect
    )

    @Test
    fun `hung provider times out while siblings are still collected`() = runTest {
        val sbSignal = signal(100, SignalStrength.CRITICAL, title = "Flagged by Google Safe Browsing")
        val orchestrator = orchestrator(
            reputation = FakeProvider { listOf(sbSignal) },
            domain = FakeProvider { awaitCancellation() } // never returns — must be timed out
        )

        val result = orchestrator.scan("https://example.com")

        assertTrue(result.verdict.signals.contains(sbSignal))
        assertEquals(Verdict.THREAT, result.verdict.verdict)
        // Three of four providers answered, so coverage is not missing
        assertNotEquals(Confidence.LOW, result.verdict.confidence)
    }

    @Test
    fun `all providers failing yields low confidence unvetted verdict`() = runTest {
        val failing = FakeProvider { throw IOException("offline") }
        val orchestrator = orchestrator(
            reputation = failing, domain = failing, enrichment = failing,
            hybrid = failing, domainAge = failing
        )

        val result = orchestrator.scan("https://example.com")

        assertEquals(Verdict.SAFE, result.verdict.verdict)
        assertEquals(Confidence.LOW, result.verdict.confidence)
        assertTrue(
            result.verdict.secondaryReasons
                .contains(ScoringEngine.EXTERNAL_CHECKS_UNAVAILABLE_REASON)
        )
    }

    @Test
    fun `one failing provider does not abort the others`() = runTest {
        val vtSignal = signal(45, SignalStrength.STRONG, SignalSource.ENRICHMENT, "3 Vendors Flagged")
        val orchestrator = orchestrator(
            reputation = FakeProvider { throw IOException("offline") },
            enrichment = FakeProvider { listOf(vtSignal) }
        )

        val result = orchestrator.scan("https://example.com")

        assertTrue(result.verdict.signals.contains(vtSignal))
        assertNotEquals(Confidence.LOW, result.verdict.confidence)
    }

    @Test
    fun `heuristic failure does not abort the scan`() = runTest {
        val sbSignal = signal(100, SignalStrength.CRITICAL, title = "Flagged by Google Safe Browsing")
        val orchestrator = orchestrator(
            heuristic = FakeHeuristic { throw IllegalStateException("boom") },
            reputation = FakeProvider { listOf(sbSignal) }
        )

        val result = orchestrator.scan("https://example.com")

        assertEquals(Verdict.THREAT, result.verdict.verdict)
        assertTrue(result.verdict.signals.contains(sbSignal))
    }

    @Test
    fun `fresh cached entry is a hit and providers are not called again`() = runTest {
        var calls = 0
        val clock = arrayOf(0L) // mutable time source
        val orchestrator = orchestrator(
            reputation = FakeProvider { calls++; emptyList() },
            now = { clock[0] }
        )

        orchestrator.scan("https://example.com")
        assertEquals(1, calls)

        // Still inside the TTL window — second scan must serve from cache, no new call.
        clock[0] = AppConfig.SCAN_CACHE_TTL_MS - 1
        orchestrator.scan("https://example.com")
        assertEquals(1, calls)
    }

    @Test
    fun `expired cached entry triggers a re-scan`() = runTest {
        var calls = 0
        val clock = arrayOf(0L)
        val orchestrator = orchestrator(
            reputation = FakeProvider { calls++; emptyList() },
            now = { clock[0] }
        )

        orchestrator.scan("https://example.com")
        assertEquals(1, calls)

        // Past the TTL — the cached verdict is stale, so the providers run again.
        clock[0] = AppConfig.SCAN_CACHE_TTL_MS
        orchestrator.scan("https://example.com")
        assertEquals(2, calls)
    }

    @Test
    fun `provider receives extracted domain not full url`() = runTest {
        var receivedInput: String? = null
        val orchestrator = orchestrator(
            domain = FakeProvider { input ->
                receivedInput = input
                emptyList()
            }
        )

        orchestrator.scan("https://www.example.com/path?q=1")

        assertEquals("example.com", receivedInput)
    }

    @Test
    fun `resolved destination is what gets scanned, tapped url preserved`() = runTest {
        var heuristicInput: String? = null
        var reputationInput: String? = null
        var domainInput: String? = null
        val orchestrator = orchestrator(
            heuristic = FakeHeuristic { heuristicInput = it; emptyList() },
            reputation = FakeProvider { reputationInput = it; emptyList() },
            domain = FakeProvider { domainInput = it; emptyList() },
            redirect = FakeRedirectResolver {
                RedirectResolution(
                    finalUrl = "https://evil.example/landing",
                    hops = listOf(it, "https://evil.example/landing"),
                    crossedDomains = true,
                    outcome = RedirectOutcome.RESOLVED
                )
            }
        )

        val result = orchestrator.scan("https://sho.rt/a")

        assertEquals("https://evil.example/landing", heuristicInput)
        assertEquals("https://evil.example/landing", reputationInput)
        assertEquals("evil.example", domainInput) // providers see the resolved domain
        assertEquals("https://sho.rt/a", result.url) // tapped URL kept for display/history
        assertEquals("https://evil.example/landing", result.resolvedUrl)
    }

    @Test
    fun `trusted host skips redirect resolution`() = runTest {
        val orchestrator = orchestrator(
            redirect = FakeRedirectResolver { error("must not resolve a trusted host") }
        )

        val result = orchestrator.scan("https://google.com/safe")

        assertEquals(Verdict.SAFE, result.verdict.verdict) // completes; resolver never called
    }

    @Test
    fun `redirect to a blocked target adds a strong signal`() = runTest {
        val orchestrator = orchestrator(
            redirect = FakeRedirectResolver {
                RedirectResolution(
                    finalUrl = it,
                    hops = listOf(it),
                    crossedDomains = false,
                    outcome = RedirectOutcome.BLOCKED_PRIVATE_HOST
                )
            }
        )

        val result = orchestrator.scan("https://sho.rt/a")

        assertTrue(result.verdict.signals.any { it.ruleId == "REDIRECT_BLOCKED_TARGET" })
    }
}
