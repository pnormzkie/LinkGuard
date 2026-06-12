package com.linkguard.app.data.provider

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Error-semantics and parsing contract for NextDnsDomainSignalProvider (DNS-JSON
 * resolve endpoint). Known trackers must be flagged even offline; for everything
 * else a failed lookup must fail loud, not read as "not blocked".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NextDnsDomainSignalProviderTest {

    private fun provider(client: okhttp3.OkHttpClient) = NextDnsDomainSignalProvider(client)

    // ── Success path ──────────────────────────────────────────────────────────

    @Test
    fun `nxdomain status means blocked`() = runTest {
        val body = """{"Status":3,"TC":false,"RD":true,"RA":true,"AD":false,"CD":false}"""
        val signals = provider(clientReturning(200, body)).fetchSignals("blocked.example.com")

        assertEquals(1, signals.size)
        assertEquals("NEXTDNS_BLOCK", signals[0].ruleId)
        assertEquals(25, signals[0].score)
        assertEquals("blocked.example.com", signals[0].matchedValue)
    }

    @Test
    fun `sinkhole answer means blocked`() = runTest {
        val body = """{"Status":0,"Answer":[{"name":"ads.example.com","type":1,"TTL":300,"data":"0.0.0.0"}]}"""
        val signals = provider(clientReturning(200, body)).fetchSignals("ads.example.com")

        assertEquals(1, signals.size)
        assertEquals("NEXTDNS_BLOCK", signals[0].ruleId)
    }

    @Test
    fun `normal resolution yields no signal`() = runTest {
        val body = """{"Status":0,"Answer":[{"name":"example.com","type":1,"TTL":300,"data":"93.184.216.34"}]}"""
        assertTrue(provider(clientReturning(200, body)).fetchSignals("example.com").isEmpty())
    }

    @Test
    fun `known tracker is flagged even when dns resolves clean`() = runTest {
        val body = """{"Status":0,"Answer":[{"name":"doubleclick.net","type":1,"TTL":300,"data":"142.250.0.1"}]}"""
        val signals = provider(clientReturning(200, body)).fetchSignals("doubleclick.net")

        assertEquals(1, signals.size)
        assertEquals("NEXTDNS_BLOCK", signals[0].ruleId)
    }

    @Test
    fun `unextractable input yields no signal without network`() = runTest {
        assertTrue(provider(clientFailing()).fetchSignals("   ").isEmpty())
    }

    // ── Failure semantics ─────────────────────────────────────────────────────

    @Test
    fun `network failure on known tracker falls back to offline signal`() = runTest {
        val signals = provider(clientFailing()).fetchSignals("doubleclick.net")

        assertEquals(1, signals.size)
        assertEquals("NEXTDNS_OFFLINE_BLOCK", signals[0].ruleId)
    }

    @Test
    fun `network failure on unknown domain propagates`() = runTest {
        val result = runCatching { provider(clientFailing()).fetchSignals("example.com") }
        assertTrue("IOException must propagate, not become a clean result", result.isFailure)
    }

    @Test
    fun `malformed json propagates as failure`() = runTest {
        val result = runCatching { provider(clientReturning(200, """{"Status":""")).fetchSignals("example.com") }
        assertTrue("Truncated JSON must fail loud", result.isFailure)
    }

    // EXPOSES BUG: a non-2xx resolve response sets isBlockedByApi=false
    // (NextDnsDomainSignalProvider.kt:48-58) and falls through to emptyList(), so an
    // endpoint outage reads as "domain not blocked". Expected per design intent: fail loud.
    @Test
    fun `http error status fails loud instead of returning clean`() = runTest {
        for (code in listOf(429, 500)) {
            val result = runCatching { provider(clientReturning(code, "server error")).fetchSignals("example.com") }
            assertTrue("HTTP $code must fail loud, not return a clean result", result.isFailure)
        }
    }
}
