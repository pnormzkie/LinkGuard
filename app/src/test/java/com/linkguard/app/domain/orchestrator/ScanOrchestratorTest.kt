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
import com.linkguard.app.domain.scanner.RedirectResolver
import com.linkguard.app.domain.scanner.SignalProvider
import com.linkguard.app.domain.scoring.ScoringEngine
import com.linkguard.app.util.AppConfig
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

        val tapped = "https://google.com/url?redirect_url=https%3A%2F%2Fcredential-harvest.test%2Flogin"
        orchestrator.scan(tapped)

        assertEquals(tapped, resolvedInput)
        assertEquals("https://credential-harvest.test/login", heuristicInput)
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
    fun `redirect timeout partial destination keeps historical score resolved url and cache behavior`() = runTest {
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
        val second = orchestrator.scan("https://source.test/start")

        assertEquals(Verdict.SAFE, first.verdict.verdict)
        assertEquals(15, first.verdict.finalScore)
        assertEquals(setOf("REDIRECT_UNRESOLVED"), first.verdict.signals.map { it.ruleId }.toSet())
        assertEquals(destination, first.resolvedUrl)
        assertEquals(null, first.verifiedResolvedUrl)
        assertEquals(first.verdict, second.verdict)
        assertEquals(1, redirectCalls)
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
}
