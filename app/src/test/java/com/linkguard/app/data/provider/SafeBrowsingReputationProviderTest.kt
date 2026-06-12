package com.linkguard.app.data.provider

import com.linkguard.app.domain.model.SignalStrength
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Error-semantics contract for SafeBrowsingReputationProvider: a failed check must
 * fail loud (throw) so ScanOrchestrator can tell "couldn't check" apart from
 * "checked clean" — a silent emptyList() here is indistinguishable from a clean URL.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SafeBrowsingReputationProviderTest {

    private val url = "https://evil.example/phish"

    private fun provider(client: okhttp3.OkHttpClient, apiKey: String = "test-key") =
        SafeBrowsingReputationProvider(client, apiKey)

    // ── Success path ──────────────────────────────────────────────────────────

    @Test
    fun `match response yields critical signal`() = runTest {
        val body = """
            {"matches":[{"threatType":"SOCIAL_ENGINEERING","platformType":"ANY_PLATFORM",
            "threatEntryType":"URL","threat":{"url":"$url"},"cacheDuration":"300s"}]}
        """.trimIndent()
        val signals = provider(clientReturning(200, body)).fetchSignals(url)

        assertEquals(1, signals.size)
        assertEquals("SAFE_BROWSING_MATCH", signals[0].ruleId)
        assertEquals(SignalStrength.CRITICAL, signals[0].strength)
        assertEquals(100, signals[0].score)
        assertEquals(url, signals[0].matchedValue)
    }

    @Test
    fun `empty object response means checked clean`() = runTest {
        // Safe Browsing returns {} when the URL is not on any threat list.
        assertTrue(provider(clientReturning(200, "{}")).fetchSignals(url).isEmpty())
    }

    @Test
    fun `empty matches array means checked clean`() = runTest {
        assertTrue(provider(clientReturning(200, """{"matches":[]}""")).fetchSignals(url).isEmpty())
    }

    @Test
    fun `blank api key returns empty without any network call`() = runTest {
        // clientFailing proves no request is attempted.
        assertTrue(provider(clientFailing(), apiKey = "").fetchSignals(url).isEmpty())
    }

    // ── Failure semantics ─────────────────────────────────────────────────────

    @Test
    fun `network failure propagates`() = runTest {
        val result = runCatching { provider(clientFailing()).fetchSignals(url) }
        assertTrue("IOException must propagate, not become a clean result", result.isFailure)
    }

    @Test
    fun `malformed json propagates as failure`() = runTest {
        val result = runCatching { provider(clientReturning(200, """{"matches": [""")).fetchSignals(url) }
        assertTrue("Truncated JSON must fail loud", result.isFailure)
    }

    @Test
    fun `unexpected json shape propagates as failure`() = runTest {
        val result = runCatching { provider(clientReturning(200, """{"matches": 5}""")).fetchSignals(url) }
        assertTrue("Non-array matches must fail loud", result.isFailure)
    }

    // EXPOSES BUG: provider returns emptyList() on non-2xx (SafeBrowsingReputationProvider.kt:39),
    // so an API rejection (e.g. bad key = 403, quota = 429) silently looks like "checked clean".
    // This is exactly the failure mode the request-body fix documented ("silently dropped this
    // provider from the scan"). Expected behavior per design intent: fail loud.
    @Test
    fun `http error status fails loud instead of returning clean`() = runTest {
        for (code in listOf(400, 403, 429, 500)) {
            val result = runCatching { provider(clientReturning(code, """{"error":{"code":$code}}""")).fetchSignals(url) }
            assertTrue("HTTP $code must fail loud, not return a clean result", result.isFailure)
        }
    }
}
