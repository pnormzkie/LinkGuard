package com.linkguard.app.data.provider

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * Contract for DomainAgeProvider (RDAP domain-age signal). A newly registered domain is a
 * strong phishing indicator; an old domain yields no signal. Failures must fail loud (so the
 * orchestrator doesn't cache an unvetted verdict), except a 404 which is a legitimate "no
 * RDAP record". Trusted majors skip the network entirely.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DomainAgeProviderTest {

    // Fixed "now" so age math is deterministic.
    private val now = Instant.parse("2024-01-10T00:00:00Z").toEpochMilli()

    private fun provider(client: okhttp3.OkHttpClient) =
        DomainAgeProvider(client, now = { now })

    /** Minimal RDAP body with a registration event (plus an unrelated event). */
    private fun rdapBody(registrationDate: String): String =
        """{"events":[{"eventAction":"last changed","eventDate":"2024-01-09T00:00:00Z"},""" +
            """{"eventAction":"registration","eventDate":"$registrationDate"}]}"""

    @Test
    fun `domain registered two days ago is a strong signal`() = runTest {
        val signals = provider(clientReturning(200, rdapBody("2024-01-08T00:00:00Z")))
            .fetchSignals("phishy-example-site.net")

        assertEquals(1, signals.size)
        assertEquals("DOMAIN_AGE_VERY_NEW", signals[0].ruleId)
        assertEquals(45, signals[0].score)
    }

    @Test
    fun `domain registered 15 days ago is a weak signal`() = runTest {
        val signals = provider(clientReturning(200, rdapBody("2023-12-26T00:00:00Z")))
            .fetchSignals("phishy-example-site.net")

        assertEquals(1, signals.size)
        assertEquals("DOMAIN_AGE_RECENT", signals[0].ruleId)
        assertEquals(20, signals[0].score)
    }

    @Test
    fun `old domain yields no signal`() = runTest {
        val signals = provider(clientReturning(200, rdapBody("2010-01-01T00:00:00Z")))
            .fetchSignals("phishy-example-site.net")

        assertTrue(signals.isEmpty())
    }

    @Test
    fun `body without a registration event yields no signal`() = runTest {
        val body = """{"events":[{"eventAction":"expiration","eventDate":"2025-01-01T00:00:00Z"}]}"""
        val signals = provider(clientReturning(200, body)).fetchSignals("phishy-example-site.net")

        assertTrue(signals.isEmpty())
    }

    @Test
    fun `404 means no rdap record and is not a failure`() = runTest {
        val signals = provider(clientReturning(404, "not found")).fetchSignals("phishy-example-site.net")
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `server error fails loud instead of returning clean`() = runTest {
        for (code in listOf(429, 500, 503)) {
            val result = runCatching {
                provider(clientReturning(code, "err")).fetchSignals("phishy-example-site.net")
            }
            assertTrue("HTTP $code must fail loud", result.isFailure)
        }
    }

    @Test
    fun `malformed json fails loud`() = runTest {
        val result = runCatching {
            provider(clientReturning(200, "not json at all")).fetchSignals("phishy-example-site.net")
        }
        assertTrue("Garbage RDAP body must fail loud", result.isFailure)
    }

    @Test
    fun `network failure propagates`() = runTest {
        val result = runCatching { provider(clientFailing()).fetchSignals("phishy-example-site.net") }
        assertTrue("IOException must propagate, not become a clean result", result.isFailure)
    }

    @Test
    fun `trusted domain skips the network`() = runTest {
        // clientFailing() would throw if the network were hit; a clean empty result proves it wasn't.
        val signals = provider(clientFailing()).fetchSignals("google.com")
        assertTrue(signals.isEmpty())
    }
}
