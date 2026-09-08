package com.linkguard.app.data.provider

import com.linkguard.app.domain.model.SignalStrength
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class UrlHausDomainProviderTest {

    private val key = "test-auth-key"

    @Test
    fun `listed exact path with an online url is critical`() = runBlocking {
        val body = """{"query_status":"ok","urls":[{"url":"http://evil.test/x","url_status":"online","threat":"malware_download"}]}"""
        val signals = UrlHausDomainProvider(clientReturning(200, body), key)
            .fetchSignals("http://evil.test/x")
        assertEquals(1, signals.size)
        assertEquals("URLHAUS_ACTIVE_MALWARE", signals[0].ruleId)
        assertEquals(SignalStrength.CRITICAL, signals[0].strength)
    }

    @Test
    fun `online malware on a different shared-host path is weak evidence`() = runBlocking {
        val body = """{"query_status":"ok","urls":[{"url":"http://shared.test/malware.exe","url_status":"online"}]}"""
        val signals = UrlHausDomainProvider(clientReturning(200, body), key)
            .fetchSignals("https://shared.test/legitimate-page")

        assertEquals(1, signals.size)
        assertEquals("URLHAUS_SHARED_HOST_ACTIVITY", signals[0].ruleId)
        assertEquals(SignalStrength.WEAK, signals[0].strength)
        assertEquals(15, signals[0].score)
    }

    @Test
    fun `matching path and query are required for a critical urlhaus result`() = runBlocking {
        val body = """{"query_status":"ok","urls":[{"url":"http://evil.test/download?id=bad","url_status":"online"}]}"""
        val signals = UrlHausDomainProvider(clientReturning(200, body), key)
            .fetchSignals("https://evil.test/download?id=good")

        assertEquals("URLHAUS_SHARED_HOST_ACTIVITY", signals.single().ruleId)
    }

    @Test
    fun `listed host with only offline urls is weak historical evidence`() = runBlocking {
        val body = """{"query_status":"ok","urls":[{"url_status":"offline"}]}"""
        val signals = UrlHausDomainProvider(clientReturning(200, body), key)
            .fetchSignals("http://evil.test/x")
        assertEquals(1, signals.size)
        assertEquals("URLHAUS_HISTORICAL_MALWARE", signals[0].ruleId)
        assertEquals(SignalStrength.WEAK, signals[0].strength)
        assertEquals(15, signals[0].score)
    }

    @Test
    fun `no_results is a clean empty result`() = runBlocking {
        val signals = UrlHausDomainProvider(clientReturning(200, """{"query_status":"no_results"}"""), key)
            .fetchSignals("http://clean.test/x")
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `blank key is a no-op and makes no request`() = runBlocking {
        // clientFailing would throw if a request were made; a blank key must short-circuit first.
        val signals = UrlHausDomainProvider(clientFailing(), "").fetchSignals("http://evil.test/x")
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `trusted domain is skipped without a request`() = runBlocking {
        val signals = UrlHausDomainProvider(clientFailing(), key).fetchSignals("https://google.com")
        assertTrue(signals.isEmpty())
    }

    @Test
    fun `non-2xx fails loud`() {
        val provider = UrlHausDomainProvider(clientReturning(429, ""), key)
        assertThrows(IOException::class.java) { runBlocking { provider.fetchSignals("http://evil.test/x") } }
    }

    @Test
    fun `auth error status fails loud`() {
        val provider = UrlHausDomainProvider(clientReturning(200, """{"query_status":"invalid_auth_key"}"""), key)
        assertThrows(IOException::class.java) { runBlocking { provider.fetchSignals("http://evil.test/x") } }
    }

    @Test
    fun `network error fails loud`() {
        val provider = UrlHausDomainProvider(clientFailing(), key)
        assertThrows(IOException::class.java) { runBlocking { provider.fetchSignals("http://evil.test/x") } }
    }
}
