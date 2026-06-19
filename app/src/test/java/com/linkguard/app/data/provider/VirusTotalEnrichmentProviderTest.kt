package com.linkguard.app.data.provider

import com.linkguard.app.domain.model.SignalStrength
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Error-semantics and parsing contract for VirusTotalEnrichmentProvider (urls report
 * endpoint). 404 "URL not yet in database" is a legitimate no-report result; infrastructure
 * failures (network, 401/429/5xx) must fail loud so the orchestrator can tell "couldn't
 * check" apart from "checked clean".
 *
 * The provider encodes the URL with java.util.Base64 (API 26+ = minSdk), so the network
 * path is exercisable under JVM unit tests via the OkHttp interceptor seam in FakeHttp.kt.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VirusTotalEnrichmentProviderTest {

    private val url = "https://evil.example/x"

    private fun provider(client: okhttp3.OkHttpClient, apiKey: String = "test-key") =
        VirusTotalEnrichmentProvider(client, apiKey)

    // ── Pre-network guards ─────────────────────────────────────────────────────

    @Test
    fun `blank api key returns empty without any network call`() = runTest {
        assertTrue(provider(clientFailing(), apiKey = "  ").fetchSignals(url).isEmpty())
    }

    @Test
    fun `trusted domain is skipped without any network call`() = runTest {
        assertTrue(provider(clientFailing()).fetchSignals("https://google.com/search?q=x").isEmpty())
    }

    @Test
    fun `trusted subdomain is skipped without any network call`() = runTest {
        assertTrue(provider(clientFailing()).fetchSignals("https://accounts.google.com/signin").isEmpty())
    }

    // ── Success path ──────────────────────────────────────────────────────────

    @Test
    fun `multiple malicious vendors yield critical signal`() = runTest {
        val body = """
            {"data":{"attributes":{"last_analysis_stats":
            {"malicious":7,"suspicious":1,"harmless":60,"undetected":10}}}}
        """.trimIndent()
        val signals = provider(clientReturning(200, body)).fetchSignals(url)

        assertEquals(1, signals.size)
        assertEquals("VT_MALICIOUS", signals[0].ruleId)
        assertEquals(SignalStrength.CRITICAL, signals[0].strength) // malicious >= 5
        assertEquals(65, signals[0].score)
        assertEquals(url, signals[0].matchedValue)
    }

    @Test
    fun `single malicious vendor yields weak signal`() = runTest {
        val body = """{"data":{"attributes":{"last_analysis_stats":{"malicious":1}}}}"""
        val signals = provider(clientReturning(200, body)).fetchSignals(url)

        assertEquals(1, signals.size)
        assertEquals(SignalStrength.WEAK, signals[0].strength)
        assertEquals(25, signals[0].score)
    }

    @Test
    fun `engine names from last_analysis_results are captured (malicious only)`() = runTest {
        val body = """
            {"data":{"attributes":{
              "last_analysis_stats":{"malicious":2,"harmless":60},
              "last_analysis_results":{
                "Google Safebrowsing":{"category":"malicious","result":"phishing"},
                "Phishtank":{"category":"malicious","result":"phishing"},
                "Kaspersky":{"category":"harmless","result":"clean"}
              }}}}
        """.trimIndent()
        val signals = provider(clientReturning(200, body)).fetchSignals(url)
        assertEquals(1, signals.size)
        // Only the malicious engines, in response order; harmless ones excluded.
        assertEquals("Google Safebrowsing||Phishtank", signals[0].metadata["vendor_names"])
    }

    @Test
    fun `missing last_analysis_results leaves vendor_names absent`() = runTest {
        val body = """{"data":{"attributes":{"last_analysis_stats":{"malicious":1}}}}"""
        val signals = provider(clientReturning(200, body)).fetchSignals(url)
        assertEquals(null, signals[0].metadata["vendor_names"])
        assertEquals("1", signals[0].metadata["malicious_count"])
    }

    @Test
    fun `zero malicious vendors yields no signal`() = runTest {
        val body = """{"data":{"attributes":{"last_analysis_stats":{"malicious":0,"harmless":70}}}}"""
        assertTrue(provider(clientReturning(200, body)).fetchSignals(url).isEmpty())
    }

    @Test
    fun `404 means no report and is a legitimate empty result`() = runTest {
        // VirusTotal returns 404 when the URL has not yet been submitted/analyzed.
        assertTrue(provider(clientReturning(404, """{"error":{"code":"NotFoundError"}}""")).fetchSignals(url).isEmpty())
    }

    // ── Failure semantics ─────────────────────────────────────────────────────

    @Test
    fun `network failure propagates`() = runTest {
        val result = runCatching { provider(clientFailing()).fetchSignals(url) }
        assertTrue("IOException must propagate, not become a clean result", result.isFailure)
    }

    @Test
    fun `http error status fails loud instead of returning clean`() = runTest {
        // 401 bad key, 429 quota, 500 server down: the check did not run.
        for (code in listOf(401, 429, 500)) {
            val result = runCatching { provider(clientReturning(code, """{"error":{"code":$code}}""")).fetchSignals(url) }
            assertTrue("HTTP $code must fail loud, not return a clean result", result.isFailure)
        }
    }

    @Test
    fun `malformed json fails loud instead of returning clean`() = runTest {
        val result = runCatching { provider(clientReturning(200, """{"data":{""")).fetchSignals(url) }
        assertTrue("Truncated JSON must fail loud", result.isFailure)
    }
}
