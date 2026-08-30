package com.linkguard.app.data.provider

import com.linkguard.app.domain.model.SignalStrength
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Error-semantics and parsing contract for HybridAnalysisProvider (Falcon Sandbox
 * /search/terms). 404 "URL unknown" is a legitimate no-report result; infrastructure
 * failures (network, 429/500, garbage payload) must fail loud so the orchestrator
 * can tell "couldn't check" apart from "no report found".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HybridAnalysisProviderTest {

    private val url = "https://evil.example/dropper.apk"

    private fun provider(client: okhttp3.OkHttpClient, apiKey: String = "test-key") =
        HybridAnalysisProvider(client, apiKey)

    // ── Success path ──────────────────────────────────────────────────────────

    @Test
    fun `malicious report yields critical signal with halved score`() = runTest {
        val body = """
            {"search_terms":[{"id":"url","value":"$url"}],"count":1,
             "result":[{"verdict":"malicious","threat_score":85,"sha256":"abc","analysis_start_time":"2026-01-01"}]}
        """.trimIndent()
        val signals = provider(clientReturning(200, body)).fetchSignals(url)

        assertEquals(1, signals.size)
        assertEquals("HYBRID_ANALYSIS_THREAT", signals[0].ruleId)
        assertEquals("Falcon Sandbox: Malicious", signals[0].title)
        assertEquals(SignalStrength.CRITICAL, signals[0].strength)
        assertEquals(42, signals[0].score) // 85 / 2, capped at 50
    }

    @Test
    fun `worst result wins when multiple reports exist`() = runTest {
        val body = """
            {"count":2,"result":[
              {"verdict":"no specific threat","threat_score":5},
              {"verdict":"suspicious","threat_score":60}]}
        """.trimIndent()
        val signals = provider(clientReturning(200, body)).fetchSignals(url)

        assertEquals(1, signals.size)
        assertEquals("Falcon Sandbox: Suspicious", signals[0].title)
        assertEquals(SignalStrength.STRONG, signals[0].strength) // 60 < 75
    }

    @Test
    fun `newer clean analysis supersedes an older malicious report`() = runTest {
        val body = """
            {"count":2,"result":[
              {"verdict":"malicious","threat_score":95,"analysis_start_time":"2025-01-01T00:00:00Z"},
              {"verdict":"no specific threat","threat_score":5,"analysis_start_time":"2026-01-01T00:00:00Z"}]}
        """.trimIndent()

        val signals = provider(clientReturning(200, body)).fetchSignals(url)

        assertTrue(signals.isEmpty())
    }

    @Test
    fun `missing analysis timestamp retains conservative worst-result behavior`() = runTest {
        val body = """
            {"count":2,"result":[
              {"verdict":"malicious","threat_score":80},
              {"verdict":"no specific threat","threat_score":5,"analysis_start_time":"2026-01-01"}]}
        """.trimIndent()

        val signals = provider(clientReturning(200, body)).fetchSignals(url)

        assertEquals(1, signals.size)
        assertEquals("HYBRID_ANALYSIS_THREAT", signals[0].ruleId)
    }

    @Test
    fun `benign report yields no signal`() = runTest {
        val body = """{"count":1,"result":[{"verdict":"no specific threat","threat_score":10}]}"""
        assertTrue(provider(clientReturning(200, body)).fetchSignals(url).isEmpty())
    }

    @Test
    fun `404 means no report and is a legitimate empty result`() = runTest {
        // Documented in-code: non-success such as 404 = URL unknown to the sandbox.
        val body = """{"message":"Not Found"}"""
        assertTrue(provider(clientReturning(404, body)).fetchSignals(url).isEmpty())
    }

    @Test
    fun `blank api key returns empty without any network call`() = runTest {
        assertTrue(provider(clientFailing(), apiKey = " ").fetchSignals(url).isEmpty())
    }

    @Test
    fun `trusted-domain url is checked but its report remains calibrated`() = runTest {
        val trustedUrl = "https://github.com/user/repo/releases/download/app.apk"
        val body = """{"count":1,"result":[{"verdict":"malicious","threat_score":80}]}"""

        val signals = provider(clientReturning(200, body)).fetchSignals(trustedUrl)

        assertEquals(1, signals.size)
        assertEquals(SignalStrength.WEAK, signals[0].strength)
        assertEquals(15, signals[0].score)
        assertEquals(trustedUrl, signals[0].matchedValue)
    }

    // ── Failure semantics ─────────────────────────────────────────────────────

    @Test
    fun `network failure propagates`() = runTest {
        val result = runCatching { provider(clientFailing()).fetchSignals(url) }
        assertTrue("IOException must propagate, not become a clean result", result.isFailure)
    }

    // EXPOSES BUG: performSearch treats ALL non-2xx as "no report" (HybridAnalysisProvider.kt:105-109).
    // 404 is a legitimate no-report, but 429 (quota) / 500 (server down) mean the check did not run;
    // returning empty makes an outage look like a clean URL. Expected per design intent: fail loud.
    @Test
    fun `server error status fails loud instead of returning clean`() = runTest {
        for (code in listOf(429, 500)) {
            val result = runCatching { provider(clientReturning(code, """{"message":"error"}""")).fetchSignals(url) }
            assertTrue("HTTP $code must fail loud, not return a clean result", result.isFailure)
        }
    }

    // EXPOSES BUG: the JSON parse error is swallowed (HybridAnalysisProvider.kt:112-117) and the
    // scan continues as if the sandbox had no report. A broken/garbage API payload means the check
    // did not run. Expected per design intent: fail loud.
    @Test
    fun `malformed json fails loud instead of returning clean`() = runTest {
        val result = runCatching { provider(clientReturning(200, """{"result": [{""")).fetchSignals(url) }
        assertTrue("Truncated JSON must fail loud", result.isFailure)
    }
}
