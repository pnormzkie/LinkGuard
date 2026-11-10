package com.linkguard.app.domain.orchestrator

import com.linkguard.app.data.provider.HostSafetyValidator
import com.linkguard.app.data.provider.HttpRedirectResolver
import com.linkguard.app.domain.model.Confidence
import com.linkguard.app.domain.model.RedirectOutcome
import com.linkguard.app.domain.model.RedirectResolution
import com.linkguard.app.domain.model.ScanSignal
import com.linkguard.app.domain.model.SignalSource
import com.linkguard.app.domain.model.SignalStrength
import com.linkguard.app.domain.model.Verdict
import com.linkguard.app.domain.scanner.CredentialFormInspector
import com.linkguard.app.domain.scanner.HeuristicEngine
import com.linkguard.app.domain.scanner.LegacyHeuristicEngine
import com.linkguard.app.domain.scanner.RedirectResolver
import com.linkguard.app.domain.scanner.SignalProvider
import com.linkguard.app.domain.scoring.ScoringEngine
import com.linkguard.app.util.AppConfig
import com.linkguard.app.util.DomainExtractor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicInteger

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

    private class FakeCredentialFormInspector(
        private val eligible: (String) -> Boolean = { true },
        private val block: suspend (String) -> List<ScanSignal>
    ) : CredentialFormInspector {
        override fun isEligible(finalUrl: String): Boolean = eligible(finalUrl)
        override suspend fun inspect(finalUrl: String): List<ScanSignal> = block(finalUrl)
    }

    private fun signal(
        score: Int,
        strength: SignalStrength = SignalStrength.MEDIUM,
        source: SignalSource = SignalSource.EXTERNAL_REPUTATION,
        title: String = "Test signal"
    ) = ScanSignal(
        ruleId = "TEST_${title.filter { it.isLetterOrDigit() }}",
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
        urlhaus: SignalProvider = FakeProvider { emptyList() },
        now: () -> Long = { 0L },
        redirect: RedirectResolver? = null,
        credential: CredentialFormInspector? = null
    ) = ScanOrchestrator(
        heuristicEngine = heuristic,
        reputationProvider = reputation,
        domainSignalProvider = domain,
        enrichmentProvider = enrichment,
        scoringEngine = ScoringEngine(),
        hybridAnalysisProvider = hybrid,
        domainAgeProvider = domainAge,
        urlHausProvider = urlhaus,
        now = now,
        redirectResolver = redirect,
        credentialFormInspector = credential
    )

    @Test
    fun `external lookups strip credentials and fragments but local analysis preserves input`() = runTest {
        val original = "https://user:secret@example.com/login?q=42#private"
        val inputs = mutableListOf<String>()
        var localInput = ""
        val recorder = FakeProvider { inputs.add(it); emptyList() }
        val scanner = orchestrator(
            heuristic = FakeHeuristic { localInput = it; emptyList() },
            reputation = recorder, enrichment = recorder, hybrid = recorder, urlhaus = recorder
        )

        val result = scanner.scan(original)

        assertEquals(List(4) { "https://example.com/login?q=42" }, inputs)
        assertEquals(original, localInput)
        assertEquals(original, result.url)
    }

    @Test
    fun `unparseable URL never reaches external providers and is not cached`() = runTest {
        var calls = 0
        var localCalls = 0
        val recorder = FakeProvider { calls++; emptyList() }
        val scanner = orchestrator(
            heuristic = FakeHeuristic { localCalls++; emptyList() },
            reputation = recorder, enrichment = recorder, hybrid = recorder, urlhaus = recorder,
            domain = recorder, domainAge = recorder
        )
        val input = "https://user:secret@example.com:invalid/login#private"

        scanner.scan(input)
        scanner.scan(input)

        assertEquals(0, calls)
        assertEquals(2, localCalls)
    }

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
            hybrid = failing, domainAge = failing, urlhaus = failing
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
    fun `network quota exhaustion still runs local detection without network or caching`() = runTest {
        var heuristicCalls = 0
        var providerCalls = 0
        var redirectCalls = 0
        var contentCalls = 0
        val localThreat = signal(
            score = 60,
            strength = SignalStrength.STRONG,
            source = SignalSource.LOCAL_HEURISTIC,
            title = "Local brand spoof"
        )
        val countingProvider = FakeProvider { providerCalls++; emptyList() }
        val orchestrator = orchestrator(
            heuristic = FakeHeuristic { heuristicCalls++; listOf(localThreat) },
            reputation = countingProvider,
            domain = countingProvider,
            enrichment = countingProvider,
            hybrid = countingProvider,
            domainAge = countingProvider,
            urlhaus = countingProvider,
            redirect = FakeRedirectResolver {
                redirectCalls++
                error("redirect must not run in local-only mode")
            },
            credential = FakeCredentialFormInspector {
                contentCalls++
                error("content inspection must not run in local-only mode")
            }
        )

        val first = orchestrator.scan("https://spoof.test", allowNetworkChecks = false)
        val second = orchestrator.scan("https://spoof.test", allowNetworkChecks = false)

        assertEquals(Verdict.THREAT, first.verdict.verdict)
        assertEquals(Verdict.THREAT, second.verdict.verdict)
        assertEquals(2, heuristicCalls) // local-only results are deliberately not cached
        assertEquals(0, providerCalls)
        assertEquals(0, redirectCalls)
        assertEquals(0, contentCalls)
        assertTrue(first.verdict.secondaryReasons.contains(ScoringEngine.EXTERNAL_CHECKS_UNAVAILABLE_REASON))
    }

    @Test
    fun `partial provider coverage lowers confidence and is not cached`() = runTest {
        var successfulCalls = 0
        var failedCalls = 0
        val failing = FakeProvider { failedCalls++; throw IOException("offline") }
        val orchestrator = orchestrator(
            reputation = FakeProvider { successfulCalls++; emptyList() },
            domain = failing,
            enrichment = failing,
            hybrid = failing,
            domainAge = failing,
            urlhaus = failing
        )

        val first = orchestrator.scan("https://partial-coverage.test")
        val second = orchestrator.scan("https://partial-coverage.test")

        assertEquals(Verdict.SAFE, first.verdict.verdict)
        assertEquals(Confidence.MEDIUM, first.verdict.confidence)
        assertTrue(first.verdict.secondaryReasons.contains(ScoringEngine.EXTERNAL_CHECKS_PARTIAL_REASON))
        assertEquals(2, successfulCalls)
        assertEquals(10, failedCalls)
        assertEquals(first.verdict, second.verdict)
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
    fun `cache key keeps url and message text apart`() = runTest {
        var calls = 0
        val orchestrator = orchestrator(reputation = FakeProvider { calls++; emptyList() })

        // "…/ab" + no text and "…/a" + text "b" concatenate to the same string; they are different scans.
        orchestrator.scan("https://example.com/ab")
        assertEquals(1, calls)
        orchestrator.scan("https://example.com/a", "b")
        assertEquals(2, calls)
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
        val heuristicInputs = mutableListOf<String>()
        val reputationInputs = mutableListOf<String>()
        var domainInput: String? = null
        val orchestrator = orchestrator(
            heuristic = FakeHeuristic { heuristicInputs.add(it); emptyList() },
            reputation = FakeProvider { reputationInputs.add(it); emptyList() },
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

        // The destination is scored; the link that forwarded there is examined after it.
        assertEquals(listOf("https://evil.example/landing", "https://sho.rt/a"), heuristicInputs)
        // The destination is looked up; since the chain left the tapped site, so is the tapped link.
        assertEquals(setOf("https://evil.example/landing", "https://sho.rt/a"), reputationInputs.toSet())
        assertEquals("evil.example", domainInput) // providers see the resolved domain
        assertEquals("https://sho.rt/a", result.url) // tapped URL kept for display/history
        assertEquals("https://evil.example/landing", result.resolvedUrl)
        assertEquals("https://evil.example/landing", result.verifiedResolvedUrl)
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
    fun `trusted host with redirect parameter is resolved and final host is scanned`() = runTest {
        var resolvedInput: String? = null
        var heuristicInput: String? = null
        val orchestrator = orchestrator(
            heuristic = FakeHeuristic { heuristicInput = it; emptyList() },
            redirect = FakeRedirectResolver {
                resolvedInput = it
                RedirectResolution(
                    finalUrl = "https://credential-harvest.test/login",
                    hops = listOf(it, "https://credential-harvest.test/login"),
                    crossedDomains = true,
                    outcome = RedirectOutcome.RESOLVED
                )
            }
        )

        // Google's real redirect endpoint uses q=, while ordinary /search?q= links stay exempt.
        val tapped = "https://google.com/url?q=https%3A%2F%2Fcredential-harvest.test%2Flogin"
        orchestrator.scan(tapped)

        // The real endpoint answers 200 with an interstitial (checked 2026-11-02), so the carried
        // address is what gets resolved; the destination is scanned either way.
        assertEquals("https://credential-harvest.test/login", resolvedInput)
        assertEquals("https://credential-harvest.test/login", heuristicInput)
    }

    @Test
    fun `ordinary trusted search query does not trigger redirect resolution`() = runTest {
        val orchestrator = orchestrator(
            redirect = FakeRedirectResolver { error("ordinary search must not be resolved") }
        )

        val result = orchestrator.scan("https://google.com/search?q=linkguard")

        assertEquals(Verdict.SAFE, result.verdict.verdict)
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

    @Test
    fun `providers start only after stalled redirect reaches three second deadline`() = runTest {
        val providerStarts = AtomicInteger()
        val provider = FakeProvider { providerStarts.incrementAndGet(); emptyList() }
        val orchestrator = orchestrator(
            reputation = provider,
            domain = provider,
            enrichment = provider,
            hybrid = provider,
            domainAge = provider,
            urlhaus = provider,
            redirect = FakeRedirectResolver {
                delay(AppConfig.REDIRECT_TOTAL_BUDGET_MS)
                RedirectResolution(it, listOf(it), false, RedirectOutcome.TIMEOUT)
            }
        )

        val scan = launch { orchestrator.scan("https://redirect-stall.test/path") }
        runCurrent()
        advanceTimeBy(AppConfig.REDIRECT_TOTAL_BUDGET_MS - 1)
        runCurrent()
        assertEquals(0, providerStarts.get())

        advanceTimeBy(1)
        runCurrent()
        assertEquals(6, providerStarts.get())
        assertEquals(AppConfig.REDIRECT_TOTAL_BUDGET_MS, testScheduler.currentTime)
        scan.join()
    }

    @Test
    fun `parent cancellation during redirect never starts providers`() = runTest {
        val providerStarts = AtomicInteger()
        val provider = FakeProvider { providerStarts.incrementAndGet(); emptyList() }
        val orchestrator = orchestrator(
            reputation = provider,
            domain = provider,
            enrichment = provider,
            hybrid = provider,
            domainAge = provider,
            urlhaus = provider,
            redirect = FakeRedirectResolver { awaitCancellation() }
        )

        val scan = launch { orchestrator.scan("https://redirect-cancel.test/path") }
        runCurrent()
        scan.cancelAndJoin()

        assertEquals(0, providerStarts.get())
    }

    @Test
    fun `incomplete redirect resolution is suspicious not safe and never cached`() = runTest {
        var redirectCalls = 0
        val destination = "https://destination.test/partial"
        val orchestrator = orchestrator(
            redirect = FakeRedirectResolver { original ->
                redirectCalls++
                RedirectResolution(
                    finalUrl = destination,
                    hops = listOf(original, destination),
                    crossedDomains = true,
                    outcome = RedirectOutcome.TIMEOUT
                )
            }
        )

        val first = orchestrator.scan("https://source.test/start")

        assertEquals(Verdict.SUSPICIOUS, first.verdict.verdict)
        assertEquals(25, first.verdict.finalScore)
        assertEquals(setOf("REDIRECT_UNRESOLVED"), first.verdict.signals.map { it.ruleId }.toSet())
        assertEquals(destination, first.resolvedUrl)
        assertEquals(null, first.verifiedResolvedUrl)

        // An unverified destination must not be cached as a vetted verdict: the second scan
        // must re-resolve instead of returning the stored SUSPICIOUS verdict.
        val second = orchestrator.scan("https://source.test/start")
        assertEquals(2, redirectCalls)
        assertEquals(first.verdict.finalScore, second.verdict.finalScore)
    }

    @Test
    fun `unreachable site that never redirected is not an unresolved-redirect signal`() = runTest {
        // A first-hop timeout means the site never answered — the user cannot reach it either,
        // so it is an unreachable site, not a redirect chain whose destination stayed unknown.
        val orchestrator = orchestrator(
            redirect = FakeRedirectResolver { original ->
                RedirectResolution(
                    finalUrl = original,
                    hops = listOf(original),
                    crossedDomains = false,
                    outcome = RedirectOutcome.TIMEOUT
                )
            }
        )

        val result = orchestrator.scan("https://dead-domain.test/news")

        assertEquals(emptySet<String>(), result.verdict.signals.map { it.ruleId }.toSet())
        assertEquals(Verdict.SAFE, result.verdict.verdict)
    }

    @Test
    fun `a same-site chain that stalls or fails is not an unresolved-redirect signal`() = runTest {
        // Seen on-device 2026-09-23: Mynimo.com -> www.mynimo.com and www.snapple.com ->
        // snapple.com timed out on slow servers; www.kalibrr.com -> http://www.kalibrr.com/home
        // failed on the cleartext policy. The last URL reached is scanned either way, and the
        // chain never left the tapped site.
        for (outcome in listOf(RedirectOutcome.TIMEOUT, RedirectOutcome.ERROR)) {
            val orchestrator = orchestrator(
                redirect = FakeRedirectResolver { original ->
                    RedirectResolution(
                        finalUrl = "https://www.same-site.test/",
                        hops = listOf(original, "https://www.same-site.test/"),
                        crossedDomains = false,
                        outcome = outcome
                    )
                }
            )

            val result = orchestrator.scan("https://same-site.test/")

            assertEquals("$outcome", emptySet<String>(), result.verdict.signals.map { it.ruleId }.toSet())
            assertEquals("$outcome", Verdict.SAFE, result.verdict.verdict)
        }
    }

    @Test
    fun `redirect loop and hop exhaustion are suspicious not safe`() = runTest {
        val loopResolution = RedirectResolution(
            finalUrl = "https://loop.test/a",
            hops = listOf("https://source.test/start", "https://loop.test/a", "https://loop.test/b"),
            crossedDomains = true,
            outcome = RedirectOutcome.LOOP
        )
        val hopResolution = RedirectResolution(
            finalUrl = "https://destination.test/deep",
            hops = List(6) { "https://hop$it.test/x" },
            crossedDomains = true,
            outcome = RedirectOutcome.MAX_HOPS
        )
        val orchestrator = orchestrator(
            redirect = FakeRedirectResolver { original ->
                if (original.contains("loop")) loopResolution else hopResolution
            }
        )

        assertEquals(Verdict.SUSPICIOUS, orchestrator.scan("https://loop.test/start").verdict.verdict)
        assertEquals(Verdict.SUSPICIOUS, orchestrator.scan("https://hops.test/start").verdict.verdict)
    }

    @Test
    fun `mid-chain transport error is suspicious resolved outcome stays safe`() = runTest {
        val errorResolution = RedirectResolution(
            finalUrl = "https://destination.test/stalled",
            hops = listOf("https://source.test/start", "https://destination.test/stalled"),
            crossedDomains = true,
            outcome = RedirectOutcome.ERROR
        )
        val orchestrator = orchestrator(
            redirect = FakeRedirectResolver { errorResolution }
        )

        assertEquals(Verdict.SUSPICIOUS, orchestrator.scan("https://source.test/start").verdict.verdict)

        // A chain that fully resolved to a clean destination keeps its SAFE verdict
        // (historical behavior; guards against over-blocking).
        val resolvedResolution = RedirectResolution(
            finalUrl = "https://destination.test/ok",
            hops = listOf("https://source.test/start", "https://destination.test/ok"),
            crossedDomains = true,
            outcome = RedirectOutcome.RESOLVED
        )
        val resolvedOrchestrator = orchestrator(
            redirect = FakeRedirectResolver { resolvedResolution }
        )
        assertEquals(Verdict.SAFE, resolvedOrchestrator.scan("https://source.test/start").verdict.verdict)
    }

    @Test
    fun `credential form on a borderline untrusted destination escalates to threat`() = runTest {
        val nrd = signal(45, SignalStrength.STRONG, SignalSource.DOMAIN_SIGNAL, "Newly registered domain")
        var inspected: String? = null
        val orchestrator = orchestrator(
            domainAge = FakeProvider { listOf(nrd) }, // phase-1 = SUSPICIOUS (STRONG, score 45)
            credential = FakeCredentialFormInspector {
                inspected = it
                listOf(signal(25, SignalStrength.MEDIUM, SignalSource.LOCAL_HEURISTIC, "Login form on an unverified site"))
            }
        )

        val result = orchestrator.scan("https://new-bank.test/login")

        assertEquals("https://new-bank.test/login", inspected) // inspector saw the scanned URL
        assertEquals(Verdict.THREAT, result.verdict.verdict) // 45 + 25 crosses the threat threshold
        assertEquals(70, result.verdict.finalScore)
        assertEquals(Confidence.HIGH, result.verdict.confidence)
        assertEquals(2, result.verdict.signals.size)
        assertEquals(null, result.resolvedUrl)
    }

    @Test
    fun `clean untrusted scan invokes the bounded page inspector`() = runTest {
        var inspected: String? = null
        val orchestrator = orchestrator(
            credential = FakeCredentialFormInspector {
                inspected = it
                emptyList()
            }
        )

        val result = orchestrator.scan("https://example.com")

        assertEquals("https://example.com", inspected)
        assertEquals(Verdict.SAFE, result.verdict.verdict)
        assertEquals(0, result.verdict.finalScore)
        assertEquals(Confidence.HIGH, result.verdict.confidence)
        assertTrue(result.verdict.signals.isEmpty())
        assertEquals(null, result.resolvedUrl)
    }

    @Test
    fun `trusted host is not inspected even when borderline`() = runTest {
        val suspicious = signal(45, SignalStrength.STRONG, title = "Some strong signal")
        var inspectCalls = 0
        val orchestrator = orchestrator(
            heuristic = FakeHeuristic { listOf(suspicious) },
            credential = FakeCredentialFormInspector(
                block = { inspectCalls++; emptyList() },
                eligible = { false }
            )
        )

        val result = orchestrator.scan("https://google.com/login")

        assertEquals(Verdict.SUSPICIOUS, result.verdict.verdict) // stays suspicious; inspector skipped
        assertEquals(0, inspectCalls)
    }

    @Test
    fun `provider threat retains historical flags when concurrent inspection finds evidence`() = runTest {
        val critical = signal(100, SignalStrength.CRITICAL, title = "Flagged by Google Safe Browsing")
        val pageSignal = signal(25, source = SignalSource.LOCAL_HEURISTIC, title = "Login form")
        var inspectCalls = 0
        val orchestrator = orchestrator(
            reputation = FakeProvider { listOf(critical) },
            credential = FakeCredentialFormInspector { inspectCalls++; listOf(pageSignal) }
        )

        val result = orchestrator.scan("https://evil.test/x")

        assertEquals(Verdict.THREAT, result.verdict.verdict)
        assertEquals(100, result.verdict.finalScore)
        assertEquals(Confidence.HIGH, result.verdict.confidence)
        assertEquals(listOf(critical.ruleId), result.verdict.signals.map { it.ruleId })
        assertEquals(1, inspectCalls) // bounded work may finish, but its flags remain excluded
    }

    @Test
    fun `local threat does not start page inspection`() = runTest {
        val critical = signal(100, SignalStrength.CRITICAL, SignalSource.LOCAL_HEURISTIC, "Local threat")
        var inspectCalls = 0
        val orchestrator = orchestrator(
            heuristic = FakeHeuristic { listOf(critical) },
            credential = FakeCredentialFormInspector { inspectCalls++; emptyList() }
        )

        val result = orchestrator.scan("https://evil.test/x")

        assertEquals(Verdict.THREAT, result.verdict.verdict)
        assertEquals(listOf(critical.ruleId), result.verdict.signals.map { it.ruleId })
        assertEquals(0, inspectCalls)
    }

    @Test
    fun `provider and inspection delays overlap without early safe`() = runTest {
        var providerCalls = 0
        var inspectCalls = 0
        val delayedProvider = FakeProvider {
            providerCalls++
            delay(7_999)
            emptyList()
        }
        val orchestrator = orchestrator(
            reputation = delayedProvider, domain = delayedProvider, enrichment = delayedProvider,
            hybrid = delayedProvider, domainAge = delayedProvider, urlhaus = delayedProvider,
            credential = FakeCredentialFormInspector {
                inspectCalls++
                delay(AppConfig.CONTENT_FETCH_TIMEOUT_MS)
                emptyList()
            }
        )

        var result: com.linkguard.app.domain.model.ScanResult? = null
        val scanJob = launch { result = orchestrator.scan("https://slow.test/login") }
        advanceTimeBy(AppConfig.CONTENT_FETCH_TIMEOUT_MS)
        assertFalse(scanJob.isCompleted) // inspection finished, but providers are still required
        advanceUntilIdle()

        assertEquals(7_999L, testScheduler.currentTime)
        assertEquals(6, providerCalls)
        assertEquals(1, inspectCalls)
        assertEquals(Verdict.SAFE, result?.verdict?.verdict)
    }

    @Test
    fun `inspection timeout does not discard provider findings`() = runTest {
        val providerSignal = signal(45, SignalStrength.STRONG, title = "Provider finding")
        val orchestrator = orchestrator(
            reputation = FakeProvider { listOf(providerSignal) },
            credential = FakeCredentialFormInspector { awaitCancellation() }
        )

        val result = orchestrator.scan("https://slow-page.test")

        assertEquals(Verdict.SUSPICIOUS, result.verdict.verdict)
        assertEquals(45, result.verdict.finalScore)
        assertEquals(listOf(providerSignal.ruleId), result.verdict.signals.map { it.ruleId })
        assertEquals(AppConfig.CONTENT_FETCH_TIMEOUT_MS, testScheduler.currentTime)
    }

    @Test
    fun `provider threat waits no longer than content budget and retains historical flags`() = runTest {
        val critical = signal(100, SignalStrength.CRITICAL, title = "Provider threat")
        val orchestrator = orchestrator(
            reputation = FakeProvider { listOf(critical) },
            credential = FakeCredentialFormInspector { awaitCancellation() }
        )

        val result = orchestrator.scan("https://slow-threat-page.test")

        assertEquals(AppConfig.CONTENT_FETCH_TIMEOUT_MS, testScheduler.currentTime)
        assertEquals(Verdict.THREAT, result.verdict.verdict)
        assertEquals(100, result.verdict.finalScore)
        assertEquals(listOf(critical.ruleId), result.verdict.signals.map { it.ruleId })
    }

    @Test
    fun `inspection exception does not discard provider findings`() = runTest {
        val providerSignal = signal(25, title = "Provider finding")
        val orchestrator = orchestrator(
            reputation = FakeProvider { listOf(providerSignal) },
            credential = FakeCredentialFormInspector { throw IOException("page offline") }
        )

        val result = orchestrator.scan("https://offline-page.test")

        assertEquals(Verdict.SUSPICIOUS, result.verdict.verdict)
        assertEquals(25, result.verdict.finalScore)
        assertEquals(listOf(providerSignal.ruleId), result.verdict.signals.map { it.ruleId })
    }

    @Test
    fun `provider timeout does not discard completed inspection findings`() = runTest {
        val pageSignal = signal(25, source = SignalSource.LOCAL_HEURISTIC, title = "Page finding")
        val orchestrator = orchestrator(
            reputation = FakeProvider { awaitCancellation() },
            credential = FakeCredentialFormInspector { listOf(pageSignal) }
        )

        val result = orchestrator.scan("https://page-evidence.test")

        assertEquals(Verdict.SUSPICIOUS, result.verdict.verdict)
        assertEquals(25, result.verdict.finalScore)
        assertEquals(listOf(pageSignal.ruleId), result.verdict.signals.map { it.ruleId })
        assertNotEquals(Confidence.LOW, result.verdict.confidence)
    }

    @Test
    fun `all provider failures retain page findings and remain unvetted`() = runTest {
        val pageSignal = signal(25, source = SignalSource.LOCAL_HEURISTIC, title = "Page finding")
        val failing = FakeProvider { throw IOException("offline") }
        val orchestrator = orchestrator(
            reputation = failing, domain = failing, enrichment = failing,
            hybrid = failing, domainAge = failing, urlhaus = failing,
            credential = FakeCredentialFormInspector { listOf(pageSignal) }
        )

        val result = orchestrator.scan("https://page-only.test")

        assertEquals(Verdict.SUSPICIOUS, result.verdict.verdict)
        assertEquals(25, result.verdict.finalScore)
        assertEquals(Confidence.MEDIUM, result.verdict.confidence)
        assertTrue(result.verdict.secondaryReasons.contains(ScoringEngine.EXTERNAL_CHECKS_UNAVAILABLE_REASON))
        assertEquals(listOf(pageSignal.ruleId), result.verdict.signals.map { it.ruleId })
    }

    @Test
    fun `ineligible private host never invokes inspector`() = runTest {
        var inspectCalls = 0
        val orchestrator = orchestrator(
            credential = FakeCredentialFormInspector(
                block = { inspectCalls++; emptyList() },
                eligible = { false }
            )
        )

        orchestrator.scan("http://127.0.0.1/login")

        assertEquals(0, inspectCalls)
    }

    @Test
    fun `redirected untrusted destination is inspected and preserved`() = runTest {
        var inspected: String? = null
        val pageSignal = signal(25, source = SignalSource.LOCAL_HEURISTIC, title = "Redirect page")
        val finalUrl = "https://landing.test/login"
        val orchestrator = orchestrator(
            redirect = FakeRedirectResolver {
                RedirectResolution(finalUrl, listOf(it, finalUrl), true, RedirectOutcome.RESOLVED)
            },
            credential = FakeCredentialFormInspector { inspected = it; listOf(pageSignal) }
        )

        val result = orchestrator.scan("https://sho.rt/a")

        assertEquals(finalUrl, inspected)
        assertEquals(finalUrl, result.resolvedUrl)
        assertTrue(result.verdict.signals.any { it.ruleId == pageSignal.ruleId })
    }

    @Test
    fun `redirected trusted destination is not inspected`() = runTest {
        var inspectCalls = 0
        val finalUrl = "https://google.com/login"
        val orchestrator = orchestrator(
            redirect = FakeRedirectResolver {
                RedirectResolution(finalUrl, listOf(it, finalUrl), true, RedirectOutcome.RESOLVED)
            },
            credential = FakeCredentialFormInspector(
                block = { inspectCalls++; emptyList() },
                eligible = { false }
            )
        )

        val result = orchestrator.scan("https://sho.rt/a")

        assertEquals(finalUrl, result.resolvedUrl)
        assertEquals(0, inspectCalls)
    }

    @Test
    fun `duplicate provider and page findings are counted once`() = runTest {
        val duplicate = signal(25, source = SignalSource.LOCAL_HEURISTIC, title = "Same finding")
        val orchestrator = orchestrator(
            reputation = FakeProvider { listOf(duplicate) },
            credential = FakeCredentialFormInspector { listOf(duplicate) }
        )

        val result = orchestrator.scan("https://duplicate.test")

        assertEquals(25, result.verdict.finalScore)
        assertEquals(1, result.verdict.signals.count { it.ruleId == duplicate.ruleId })
    }

    @Test
    fun `unvetted result with page evidence is not cached`() = runTest {
        var providerCalls = 0
        var inspectCalls = 0
        val failing = FakeProvider { providerCalls++; throw IOException("offline") }
        val orchestrator = orchestrator(
            reputation = failing, domain = failing, enrichment = failing,
            hybrid = failing, domainAge = failing, urlhaus = failing,
            credential = FakeCredentialFormInspector { inspectCalls++; emptyList() }
        )

        orchestrator.scan("https://uncached.test")
        orchestrator.scan("https://uncached.test")

        assertEquals(12, providerCalls)
        assertEquals(2, inspectCalls)
    }

    @Test
    fun `fully vetted result with page evidence is cached without rerunning work`() = runTest {
        var providerCalls = 0
        var inspectCalls = 0
        val provider = FakeProvider { providerCalls++; emptyList() }
        val pageSignal = signal(25, source = SignalSource.LOCAL_HEURISTIC, title = "Cached page finding")
        val orchestrator = orchestrator(
            reputation = provider, domain = provider, enrichment = provider,
            hybrid = provider, domainAge = provider, urlhaus = provider,
            credential = FakeCredentialFormInspector { inspectCalls++; listOf(pageSignal) }
        )

        val first = orchestrator.scan("https://cached-page.test")
        val second = orchestrator.scan("https://cached-page.test")

        assertEquals(first.copy(timestamp = second.timestamp), second)
        assertEquals(6, providerCalls)
        assertEquals(1, inspectCalls)
    }

    @Test
    fun `parent cancellation leaves no provider or inspection work running`() = runTest {
        var activeProviders = 0
        var activeInspections = 0
        val blockingProvider = FakeProvider {
            activeProviders++
            try { awaitCancellation() } finally { activeProviders-- }
        }
        val orchestrator = orchestrator(
            reputation = blockingProvider, domain = blockingProvider, enrichment = blockingProvider,
            hybrid = blockingProvider, domainAge = blockingProvider, urlhaus = blockingProvider,
            credential = FakeCredentialFormInspector {
                activeInspections++
                try { awaitCancellation() } finally { activeInspections-- }
            }
        )

        val scanJob = launch { orchestrator.scan("https://cancel.test") }
        runCurrent()
        assertEquals(6, activeProviders)
        assertEquals(1, activeInspections)

        scanJob.cancelAndJoin()

        assertEquals(0, activeProviders)
        assertEquals(0, activeInspections)
    }

    @Test
    fun `unsafe redirect DNS remains fail-soft and never reaches transport`() = kotlinx.coroutines.runBlocking {
        val connectStarts = AtomicInteger()
        val requestStarts = AtomicInteger()
        val unsafeDns = HostSafetyValidator(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                listOf(InetAddress.getByName("10.0.0.5"))
        })
        val client = OkHttpClient.Builder()
            .dns(unsafeDns)
            .eventListener(object : EventListener() {
                override fun connectStart(
                    call: Call,
                    inetSocketAddress: InetSocketAddress,
                    proxy: Proxy
                ) {
                    connectStarts.incrementAndGet()
                }

                override fun requestHeadersStart(call: Call) {
                    requestStarts.incrementAndGet()
                }
            })
            .build()
        val orchestrator = orchestrator(redirect = HttpRedirectResolver(client))

        val result = orchestrator.scan("http://public-looking.test/path")

        assertEquals(Verdict.SAFE, result.verdict.verdict)
        assertEquals(0, result.verdict.finalScore)
        assertEquals(null, result.resolvedUrl)
        assertEquals(0, connectStarts.get())
        assertEquals(0, requestStarts.get())
    }

    // ── Backlog sweep 2026-11-02: trusted link wrappers and APK downloads

    private fun noRedirect(url: String) = RedirectResolution(url, listOf(url), false, RedirectOutcome.NO_REDIRECT)

    @Test
    fun `trusted link wrappers are scanned at the address they carry`() = runTest {
        listOf(
            "https://www.google.com/url?q=https://evil-site.xyz/login&sa=D",
            "https://www.google.com/url?url=https://evil-site.xyz/login",
            "https://l.facebook.com/l.php?u=https%3A%2F%2Fevil-site.xyz%2Flogin&h=AT0",
            "https://lm.facebook.com/l.php?u=https%3A%2F%2Fevil-site.xyz%2Flogin",
            "https://www.facebook.com/l.php?u=https%3A%2F%2Fevil-site.xyz%2Flogin",
            "https://m.youtube.com/redirect?q=https%3A%2F%2Fevil-site.xyz%2Flogin&event=video_description",
            "https://www.youtube.com/redirect?q=https://evil-site.xyz/login"
        ).forEach { wrapper ->
            val resolved = mutableListOf<String>()
            val heuristicInputs = mutableListOf<String>()
            val orchestrator = orchestrator(
                heuristic = FakeHeuristic { heuristicInputs.add(it); emptyList() },
                redirect = FakeRedirectResolver { resolved.add(it); noRedirect(it) }
            )

            val result = orchestrator.scan(wrapper)

            assertEquals(wrapper, listOf("https://evil-site.xyz/login"), resolved)
            assertEquals(wrapper, listOf("https://evil-site.xyz/login"), heuristicInputs)
            assertEquals(wrapper, "https://evil-site.xyz/login", result.resolvedUrl)
            assertTrue(wrapper, result.verdict.signals.any { it.ruleId == "REDIRECT_CROSS_DOMAIN" })
        }
    }

    @Test
    fun `a wrapper is unwrapped even when network checks are off`() = runTest {
        val heuristicInputs = mutableListOf<String>()
        val orchestrator = orchestrator(
            heuristic = FakeHeuristic { heuristicInputs.add(it); emptyList() },
            redirect = FakeRedirectResolver { error("network checks are off") }
        )

        val result = orchestrator.scan(
            "https://www.google.com/url?q=https://evil-site.xyz/login",
            allowNetworkChecks = false
        )

        assertEquals(listOf("https://evil-site.xyz/login"), heuristicInputs)
        assertEquals("https://evil-site.xyz/login", result.resolvedUrl)
    }

    @Test
    fun `the carried address keeps its own redirect chain`() = runTest {
        val orchestrator = orchestrator(
            redirect = FakeRedirectResolver { target ->
                RedirectResolution(
                    finalUrl = "https://landing.test/x",
                    hops = listOf(target, "https://landing.test/x"),
                    crossedDomains = true,
                    outcome = RedirectOutcome.RESOLVED
                )
            }
        )

        val result = orchestrator.scan("https://www.google.com/url?q=https://bit.ly/abc")

        assertEquals("https://landing.test/x", result.resolvedUrl)
        // Opening still goes through the wrapper, so its own warning page stays in front.
        assertEquals(null, result.verifiedResolvedUrl)
    }

    @Test
    fun `a carried address that cannot be fetched is still scanned without an unresolved signal`() = runTest {
        val orchestrator = orchestrator(
            redirect = FakeRedirectResolver { RedirectResolution(it, listOf(it), false, RedirectOutcome.TIMEOUT) }
        )

        val result = orchestrator.scan("https://www.google.com/url?q=https://slow-site.test/")

        assertEquals("https://slow-site.test/", result.resolvedUrl)
        assertFalse(result.verdict.signals.any { it.ruleId == ScoringEngine.REDIRECT_UNRESOLVED_RULE_ID })
    }

    @Test
    fun `a wrapper pointing back into its own site is not a cross-site redirect`() = runTest {
        val orchestrator = orchestrator(redirect = FakeRedirectResolver { noRedirect(it) })

        val result = orchestrator.scan("https://www.google.com/url?q=https://maps.google.com/place/x")

        assertEquals("https://maps.google.com/place/x", result.resolvedUrl)
        assertFalse(result.verdict.signals.any { it.ruleId == "REDIRECT_CROSS_DOMAIN" })
    }

    @Test
    fun `wrapper paths and parameters that carry no web address are left alone`() = runTest {
        listOf(
            "https://www.google.com/search?q=https://evil-site.xyz/login",
            "https://www.google.com/url?q=javascript:alert(1)",
            "https://www.google.com/url?q=not-a-link",
            "https://www.facebook.com/profile.php?u=https%3A%2F%2Fevil-site.xyz",
            "https://evil-google.com/url?q=https://evil-site.xyz/login"
        ).forEach { url ->
            val heuristicInputs = mutableListOf<String>()
            val orchestrator = orchestrator(
                heuristic = FakeHeuristic { heuristicInputs.add(it); emptyList() },
                redirect = FakeRedirectResolver { noRedirect(it) }
            )

            val result = orchestrator.scan(url)

            assertEquals(url, listOf(url), heuristicInputs)
            assertEquals(url, null, result.resolvedUrl)
        }
    }

    @Test
    fun `a page served as an android app is flagged`() = runTest {
        val orchestrator = orchestrator(
            redirect = FakeRedirectResolver {
                RedirectResolution(
                    it, listOf(it), false, RedirectOutcome.NO_REDIRECT,
                    finalContentType = "application/vnd.android.package-archive; charset=utf-8"
                )
            }
        )

        val result = orchestrator.scan("https://promo.test/download?id=7")

        assertTrue(result.verdict.signals.any { it.ruleId == "DOWNLOADS_ANDROID_APP" })
        assertNotEquals(Verdict.SAFE, result.verdict.verdict)
    }

    @Test
    fun `an apk already named in the url is not counted twice`() = runTest {
        val orchestrator = orchestrator(
            heuristic = FakeHeuristic {
                listOf(
                    ScanSignal(
                        ruleId = "LOCAL_DANGEROUS_FILE_TYPE_APK", title = "apk", description = "apk",
                        strength = SignalStrength.MEDIUM, source = SignalSource.LOCAL_HEURISTIC, score = 25
                    )
                )
            },
            redirect = FakeRedirectResolver {
                RedirectResolution(
                    it, listOf(it), false, RedirectOutcome.NO_REDIRECT,
                    finalContentType = "application/vnd.android.package-archive"
                )
            }
        )

        val result = orchestrator.scan("https://promo.test/app.apk")

        assertFalse(result.verdict.signals.any { it.ruleId == "DOWNLOADS_ANDROID_APP" })
    }

    @Test
    fun `ordinary pages are not flagged as app downloads`() = runTest {
        listOf("text/html; charset=utf-8", null, "application/pdf").forEach { type ->
            val orchestrator = orchestrator(
                redirect = FakeRedirectResolver {
                    RedirectResolution(it, listOf(it), false, RedirectOutcome.NO_REDIRECT, finalContentType = type)
                }
            )

            val result = orchestrator.scan("https://promo.test/page")

            assertFalse("$type", result.verdict.signals.any { it.ruleId == "DOWNLOADS_ANDROID_APP" })
        }
    }

    // ─── Evidence about the tapped link survives a redirect ────────────────────────
    // A phishing server can answer LinkGuard's check with a redirect to a clean site while
    // real browsers get the phishing page. Only the destination used to be scored, so a link
    // that was on a blocklist read SAFE.

    private fun crossSiteRedirect(finalUrl: String) = FakeRedirectResolver {
        RedirectResolution(finalUrl, listOf(it, finalUrl), crossedDomains = true, outcome = RedirectOutcome.RESOLVED)
    }

    private fun vtSignal(vendors: Int, score: Int, strength: SignalStrength) = ScanSignal(
        ruleId = "VT_MALICIOUS",
        title = if (vendors == 1) "1 Vendor Flagged" else "$vendors Vendors Flagged",
        description = "test",
        strength = strength,
        source = SignalSource.ENRICHMENT,
        score = score
    )

    @Test
    fun `blocklisted link stays a threat when its server redirects the check to a trusted site`() = runTest {
        val tapped = "https://paypa1-secure-login.test/verify"
        val listed = FakeProvider { input ->
            if (input == tapped) listOf(signal(100, SignalStrength.CRITICAL, title = "Flagged by Google Safe Browsing"))
            else emptyList()
        }
        for (slot in listOf("reputation", "enrichment", "urlhaus")) {
            val orchestrator = orchestrator(
                reputation = if (slot == "reputation") listed else FakeProvider { emptyList() },
                enrichment = if (slot == "enrichment") listed else FakeProvider { emptyList() },
                urlhaus = if (slot == "urlhaus") listed else FakeProvider { emptyList() },
                redirect = crossSiteRedirect("https://www.google.com/")
            )

            val result = orchestrator.scan(tapped)

            assertEquals(slot, Verdict.THREAT, result.verdict.verdict)
            // Opening still goes to the destination that was actually checked.
            assertEquals(slot, "https://www.google.com/", result.verifiedResolvedUrl)
        }
    }

    @Test
    fun `newly registered tapped domain is still reported after a cross-site redirect`() = runTest {
        val orchestrator = orchestrator(
            domainAge = FakeProvider { domain ->
                if (domain == "paypa1-secure-login.test") listOf(
                    signal(45, SignalStrength.STRONG, SignalSource.DOMAIN_SIGNAL, "Newly registered domain")
                ) else emptyList()
            },
            redirect = crossSiteRedirect("https://www.google.com/")
        )

        val result = orchestrator.scan("https://paypa1-secure-login.test/verify")

        assertEquals(Verdict.SUSPICIOUS, result.verdict.verdict)
        assertTrue(result.verdict.secondaryReasons.contains("Newly registered domain"))
    }

    @Test
    fun `weak findings about the tapped link do not survive a redirect`() = runTest {
        // Host-level history and one-vendor hits are supporting evidence. Popular shorteners
        // and click-trackers carry them, so every resolved short link would turn suspicious.
        val tapped = "https://sho.rt/a"
        val weakForTapped = FakeProvider { input ->
            if (input == tapped || input == "sho.rt") listOf(signal(15, SignalStrength.WEAK, title = "Weak $input"))
            else emptyList()
        }
        val orchestrator = orchestrator(
            reputation = weakForTapped, enrichment = weakForTapped,
            urlhaus = weakForTapped, domainAge = weakForTapped,
            redirect = crossSiteRedirect("https://landing.example/page")
        )

        val result = orchestrator.scan(tapped)

        assertEquals(Verdict.SAFE, result.verdict.verdict)
        assertEquals(listOf("REDIRECT_CROSS_DOMAIN"), result.verdict.signals.map { it.ruleId })
    }

    @Test
    fun `only blocklist and domain-age checks are repeated for the tapped link`() = runTest {
        val calls = mutableMapOf<String, MutableList<String>>()
        fun recorder(name: String) = FakeProvider { calls.getOrPut(name) { mutableListOf() }.add(it); emptyList() }
        val orchestrator = orchestrator(
            reputation = recorder("reputation"), domain = recorder("domain"),
            enrichment = recorder("enrichment"), hybrid = recorder("hybrid"),
            domainAge = recorder("domainAge"), urlhaus = recorder("urlhaus"),
            redirect = crossSiteRedirect("https://landing.example/page")
        )

        orchestrator.scan("https://user:secret@sho.rt/a#frag")

        val bothUrls = setOf("https://landing.example/page", "https://sho.rt/a") // sanitized copy
        assertEquals(bothUrls, calls.getValue("reputation").toSet())
        assertEquals(bothUrls, calls.getValue("enrichment").toSet())
        assertEquals(bothUrls, calls.getValue("urlhaus").toSet())
        assertEquals(setOf("landing.example", "sho.rt"), calls.getValue("domainAge").toSet())
        // A DNS-policy block and a sandbox label are weak by design; not repeated.
        assertEquals(listOf("landing.example"), calls.getValue("domain"))
        assertEquals(listOf("https://landing.example/page"), calls.getValue("hybrid"))
    }

    @Test
    fun `tapped link is not looked up again when the redirect stays on its own site`() = runTest {
        val inputs = mutableListOf<String>()
        val recorder = FakeProvider { inputs.add(it); emptyList() }
        val orchestrator = orchestrator(
            reputation = recorder, enrichment = recorder, urlhaus = recorder, domainAge = recorder,
            redirect = FakeRedirectResolver {
                RedirectResolution(
                    "https://www.example.com/home", listOf(it, "https://www.example.com/home"),
                    crossedDomains = false, outcome = RedirectOutcome.RESOLVED
                )
            }
        )

        orchestrator.scan("https://example.com/")

        assertEquals(4, inputs.size) // one lookup per provider: quota is not doubled for www/https hops
        assertFalse(inputs.contains("https://example.com/"))
    }

    @Test
    fun `trusted wrapper is not looked up as a tapped link`() = runTest {
        val inputs = mutableListOf<String>()
        val recorder = FakeProvider { inputs.add(it); emptyList() }
        val orchestrator = orchestrator(
            reputation = recorder, enrichment = recorder, urlhaus = recorder, domainAge = recorder,
            redirect = FakeRedirectResolver {
                RedirectResolution(it, listOf(it), crossedDomains = false, outcome = RedirectOutcome.NO_REDIRECT)
            }
        )

        orchestrator.scan("https://www.google.com/url?q=https%3A%2F%2Flanding.example%2Fpage")

        assertEquals(4, inputs.size)
        assertFalse(inputs.any { it.contains("google.com") })
    }

    @Test
    fun `failed lookup of the tapped link reads as partial coverage and is not cached`() = runTest {
        val tapped = "https://sho.rt/a"
        var resolverCalls = 0
        val orchestrator = orchestrator(
            reputation = FakeProvider { input ->
                if (input == tapped) throw IOException("Safe Browsing HTTP 429") else emptyList()
            },
            redirect = FakeRedirectResolver {
                resolverCalls++
                RedirectResolution(
                    "https://landing.example/page", listOf(it, "https://landing.example/page"),
                    crossedDomains = true, outcome = RedirectOutcome.RESOLVED
                )
            }
        )

        val first = orchestrator.scan(tapped)
        orchestrator.scan(tapped)

        assertTrue(first.verdict.secondaryReasons.contains(ScoringEngine.EXTERNAL_CHECKS_PARTIAL_REASON))
        assertEquals(2, resolverCalls) // an unvetted verdict must be retried, not served from cache
    }

    @Test
    fun `the same finding on both ends of a redirect is counted once, the stronger one`() = runTest {
        val tapped = "https://sho.rt/a"
        val orchestrator = orchestrator(
            enrichment = FakeProvider { input ->
                if (input == tapped) listOf(vtSignal(6, 65, SignalStrength.CRITICAL))
                else listOf(vtSignal(1, 25, SignalStrength.WEAK))
            },
            redirect = crossSiteRedirect("https://landing.example/page")
        )

        val result = orchestrator.scan(tapped)

        val vt = result.verdict.signals.filter { it.ruleId == "VT_MALICIOUS" }
        assertEquals(listOf("6 Vendors Flagged"), vt.map { it.title })
        assertEquals(65 + 10, result.verdict.finalScore) // + the cross-site redirect itself
    }

    // ─── What is known about a forwarding host counts ──────────────────────────────
    // A host that sent the check somewhere else can send a browser somewhere else. Its
    // look-alike name and the scam wording of the message used to be dropped the moment it
    // answered with a redirect, and the blocklists were only asked about the tapped link,
    // so a public shortener in front of the scam host hid it completely.

    private fun chain(vararg hops: String) = FakeRedirectResolver { tapped ->
        if (tapped != hops.first()) noRedirect(tapped) else RedirectResolution(
            hops.last(), hops.toList(),
            crossedDomains = DomainExtractor.site(DomainExtractor.extract(hops.first())!!) !=
                DomainExtractor.site(DomainExtractor.extract(hops.last())!!),
            outcome = RedirectOutcome.RESOLVED
        )
    }

    private val parcelScam = "Your parcel is held at our warehouse. Pay the delivery fee today."
    private val promoWording = "Congratulations! Claim your P50 cashback reward now."

    private fun ruleIds(result: com.linkguard.app.domain.model.ScanResult) =
        result.verdict.signals.map { it.ruleId }

    @Test
    fun `a look-alike host that forwards the check to the real site is still reported`() = runTest {
        val orchestrator = orchestrator(
            heuristic = LegacyHeuristicEngine(),
            redirect = chain("https://gcash-verify.top/login", "https://www.gcash.com/")
        )

        val result = orchestrator.scan("https://gcash-verify.top/login")

        assertEquals(Verdict.THREAT, result.verdict.verdict)
        assertTrue(ruleIds(result).containsAll(listOf("LOCAL_BRAND_SPOOF_gcash", "LOCAL_ELEVATED_RISK_TLD")))
    }

    @Test
    fun `a misspelled brand and a raw IP address that forward elsewhere are still reported`() = runTest {
        val typo = orchestrator(
            heuristic = LegacyHeuristicEngine(),
            redirect = chain("https://gcassh.com/", "https://www.gcash.com/")
        ).scan("https://gcassh.com/")
        val ip = orchestrator(
            heuristic = LegacyHeuristicEngine(),
            redirect = chain("https://203.0.113.9/login", "https://www.google.com/")
        ).scan("https://203.0.113.9/login")

        assertTrue(ruleIds(typo).contains("LOCAL_TYPOSQUAT_gcash"))
        assertNotEquals(Verdict.SAFE, typo.verdict.verdict)
        assertTrue(ruleIds(ip).contains("LOCAL_IP_ADDRESS_HOST"))
        assertEquals(Verdict.SUSPICIOUS, ip.verdict.verdict)
    }

    @Test
    fun `scam wording counts when an unknown host forwards the check to a trusted site`() = runTest {
        val tapped = "https://parcel-redelivery.info/track"
        val orchestrator = orchestrator(
            heuristic = LegacyHeuristicEngine(),
            redirect = chain(tapped, "https://www.google.com/")
        )

        val withWording = orchestrator.scan(tapped, messageText = parcelScam)
        val withoutWording = orchestrator.scan(tapped)

        assertEquals(Verdict.SUSPICIOUS, withWording.verdict.verdict)
        assertEquals(25 + 10, withWording.verdict.finalScore)
        assertTrue(ruleIds(withWording).contains("LOCAL_SMISHING_MESSAGE_PATTERN"))
        // The host itself says nothing: without the message there is nothing to report.
        assertEquals(Verdict.SAFE, withoutWording.verdict.verdict)
    }

    @Test
    fun `a host hidden behind a public shortener is checked, the shortener is not`() = runTest {
        val calls = mutableMapOf<String, MutableList<String>>()
        fun recorder(name: String) = FakeProvider { calls.getOrPut(name) { mutableListOf() }.add(it); emptyList() }
        val orchestrator = orchestrator(
            heuristic = LegacyHeuristicEngine(),
            reputation = recorder("reputation"), enrichment = recorder("enrichment"),
            urlhaus = recorder("urlhaus"), domainAge = recorder("domainAge"),
            redirect = chain("https://bit.ly/3xAbCd", "https://gcash-verify.top/r", "https://www.gcash.com/")
        )

        val result = orchestrator.scan("https://bit.ly/3xAbCd")

        assertEquals(Verdict.THREAT, result.verdict.verdict)
        assertTrue(ruleIds(result).contains("LOCAL_BRAND_SPOOF_gcash"))
        val asked = setOf("https://www.gcash.com/", "https://gcash-verify.top/r")
        assertEquals(asked, calls.getValue("reputation").toSet())
        assertEquals(asked, calls.getValue("enrichment").toSet())
        assertEquals(asked, calls.getValue("urlhaus").toSet())
        assertEquals(setOf("gcash.com", "gcash-verify.top"), calls.getValue("domainAge").toSet())
    }

    @Test
    fun `a blocklisted host behind a public shortener stays a threat`() = runTest {
        val orchestrator = orchestrator(
            reputation = FakeProvider { input ->
                if (input == "https://paypa1-secure-login.test/r")
                    listOf(signal(100, SignalStrength.CRITICAL, title = "Flagged by Google Safe Browsing"))
                else emptyList()
            },
            redirect = chain("https://bit.ly/3xAbCd", "https://paypa1-secure-login.test/r", "https://www.google.com/")
        )

        assertEquals(Verdict.THREAT, orchestrator.scan("https://bit.ly/3xAbCd").verdict.verdict)
    }

    @Test
    fun `shorteners and brand-owned links that forward to a trusted site stay safe`() = runTest {
        val cases = listOf(
            listOf("https://bit.ly/3xAbCd", "https://www.gcash.com/promo"),
            listOf("https://shp.ee/abc123", "https://shopee.ph/product/1/2"),
            listOf("https://ph.shp.ee/abc123", "https://shopee.ph/"),
            listOf("https://youtu.be/dQw4w9WgXcQ", "https://www.youtube.com/watch?v=dQw4w9WgXcQ"),
            listOf("https://amzn.to/3abc", "https://www.amazon.com/dp/B000"),
            listOf("https://apple.net/", "https://www.apple.com/"),
            // A brand's app-install link carries the brand's name in front of the service's.
            listOf("https://grab.onelink.me/2695613898?pid=sms", "https://www.grab.com/ph/")
        )
        for (hops in cases) {
            val inputs = mutableListOf<String>()
            val recorder = FakeProvider { inputs.add(it); emptyList() }
            val orchestrator = orchestrator(
                heuristic = LegacyHeuristicEngine(),
                reputation = recorder, enrichment = recorder, urlhaus = recorder, domainAge = recorder,
                redirect = chain(*hops.toTypedArray())
            )

            val result = orchestrator.scan(hops.first(), messageText = promoWording)

            assertEquals(hops.first(), Verdict.SAFE, result.verdict.verdict)
            assertEquals(hops.first(), listOf("REDIRECT_CROSS_DOMAIN"), ruleIds(result))
            // One lookup per provider: these hosts forward everyone alike, nothing to ask about.
            assertEquals(hops.first(), 4, inputs.size)
        }
    }

    @Test
    fun `the shape of a forwarding host's address is not evidence`() = runTest {
        // Click-trackers have deep subdomains, random labels, long encoded paths and words such
        // as "click" or "account" in them. None of that says who they are.
        val tracker = "http://xkqzvbnm.links.click.e.account-mailer-update-example.com/ls/click?upn=" +
            "%2F".repeat(8) + "a".repeat(220)
        val orchestrator = orchestrator(
            heuristic = LegacyHeuristicEngine(),
            redirect = chain(tracker, "https://www.lazada.com.ph/")
        )

        val result = orchestrator.scan(tracker)

        assertEquals(Verdict.SAFE, result.verdict.verdict)
        assertEquals(listOf("REDIRECT_CROSS_DOMAIN"), ruleIds(result))
    }

    @Test
    fun `a finding on both the forwarding host and the destination is counted once`() = runTest {
        val orchestrator = orchestrator(
            heuristic = LegacyHeuristicEngine(),
            redirect = chain("https://parcel-one.xyz/a", "https://parcel-two.top/b")
        )

        val result = orchestrator.scan("https://parcel-one.xyz/a", messageText = parcelScam)

        assertEquals(1, ruleIds(result).count { it == "LOCAL_ELEVATED_RISK_TLD" })
        assertEquals(1, ruleIds(result).count { it == "LOCAL_SMISHING_MESSAGE_PATTERN" })
        assertEquals(15 + 25 + 10, result.verdict.finalScore)
    }

    @Test
    fun `at most two forwarding hosts are looked up`() = runTest {
        val asked = mutableListOf<String>()
        val orchestrator = orchestrator(
            domainAge = FakeProvider { asked.add(it); emptyList() },
            redirect = chain(
                "https://one.example/a", "https://two.example/b", "https://three.example/c",
                "https://four.example/d", "https://landing.example/page"
            )
        )

        orchestrator.scan("https://one.example/a")

        assertEquals(setOf("landing.example", "one.example", "two.example"), asked.toSet())
    }

    @Test
    fun `a trusted wrapper in front of a forwarding host does not hide it`() = runTest {
        val carried = "https://gcash-verify.top/r"
        val orchestrator = orchestrator(
            heuristic = LegacyHeuristicEngine(),
            redirect = FakeRedirectResolver {
                RedirectResolution(
                    "https://www.gcash.com/", listOf(carried, "https://www.gcash.com/"),
                    crossedDomains = true, outcome = RedirectOutcome.RESOLVED
                )
            }
        )

        val result = orchestrator.scan("https://www.google.com/url?q=https%3A%2F%2Fgcash-verify.top%2Fr")

        assertTrue(ruleIds(result).contains("LOCAL_BRAND_SPOOF_gcash"))
        assertNotEquals(Verdict.SAFE, result.verdict.verdict)
    }
}
