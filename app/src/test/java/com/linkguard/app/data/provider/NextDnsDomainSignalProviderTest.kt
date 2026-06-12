package com.linkguard.app.data.provider

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * Error-semantics and parsing contract for NextDnsDomainSignalProvider (RFC 8484
 * DoH wireformat — the JSON /resolve endpoint ignores profiles, so the provider
 * queries dns.nextdns.io/{profile} directly). Known trackers must be flagged even
 * offline; for everything else a failed lookup must fail loud, not read as
 * "not blocked".
 */
@OptIn(ExperimentalCoroutinesApi::class)
class NextDnsDomainSignalProviderTest {

    private fun provider(client: okhttp3.OkHttpClient) =
        NextDnsDomainSignalProvider(client, profileId = "testprofile")

    /** Canned RFC 1035 wireformat response: optional A answer and/or EDE option. */
    private fun dnsResponse(
        rcode: Int = 0,
        answerIp: String? = null,
        edeInfoCode: Int? = null
    ): ByteArray {
        val out = ByteArrayOutputStream()
        fun u16(v: Int) {
            out.write((v ushr 8) and 0xFF)
            out.write(v and 0xFF)
        }
        u16(0)               // ID
        u16(0x8180 or rcode) // QR + RD + RA + rcode
        u16(1)                                       // QDCOUNT
        u16(if (answerIp != null) 1 else 0)          // ANCOUNT
        u16(0)                                       // NSCOUNT
        u16(if (edeInfoCode != null) 1 else 0)       // ARCOUNT (OPT)
        // question: example.com A IN
        out.write(7); out.write("example".toByteArray())
        out.write(3); out.write("com".toByteArray())
        out.write(0)
        u16(1); u16(1)
        if (answerIp != null) {
            u16(0xC00C)     // name: pointer to question
            u16(1); u16(1)  // type A, class IN
            u16(0); u16(300) // TTL
            u16(4)
            answerIp.split('.').forEach { out.write(it.toInt()) }
        }
        if (edeInfoCode != null) {
            out.write(0)      // root name
            u16(41)           // type OPT
            u16(1232)         // class = udp payload size
            u16(0); u16(0)    // TTL
            u16(6)            // RDLENGTH: option header (4) + info-code (2)
            u16(15)           // option code: EDE
            u16(2)            // option length
            u16(edeInfoCode)
        }
        return out.toByteArray()
    }

    // ── Success path ──────────────────────────────────────────────────────────

    @Test
    fun `nxdomain with ede filtered option means blocked`() = runTest {
        val signals = provider(clientReturningBytes(200, dnsResponse(rcode = 3, edeInfoCode = 17)))
            .fetchSignals("blocked.example.com")

        assertEquals(1, signals.size)
        assertEquals("NEXTDNS_BLOCK", signals[0].ruleId)
        assertEquals(25, signals[0].score)
        assertEquals("blocked.example.com", signals[0].matchedValue)
    }

    @Test
    fun `bare nxdomain is a nonexistent domain not a block`() = runTest {
        // No EDE option, no sinkhole — the domain simply doesn't exist. Flagging it as
        // "Blocked by NextDNS" was a false positive.
        val signals = provider(clientReturningBytes(200, dnsResponse(rcode = 3)))
            .fetchSignals("no-such-domain.example.com")

        assertTrue(signals.isEmpty())
    }

    @Test
    fun `sinkhole answer means blocked`() = runTest {
        val signals = provider(clientReturningBytes(200, dnsResponse(answerIp = "0.0.0.0")))
            .fetchSignals("ads.example.com")

        assertEquals(1, signals.size)
        assertEquals("NEXTDNS_BLOCK", signals[0].ruleId)
    }

    @Test
    fun `ede filtered option means blocked even with a real answer ip`() = runTest {
        // NextDNS block-page style: real-looking A record + EDE 17 (Filtered).
        val body = dnsResponse(answerIp = "149.248.211.216", edeInfoCode = 17)
        val signals = provider(clientReturningBytes(200, body)).fetchSignals("blocked.example.com")

        assertEquals(1, signals.size)
        assertEquals("NEXTDNS_BLOCK", signals[0].ruleId)
    }

    @Test
    fun `normal resolution yields no signal`() = runTest {
        val body = dnsResponse(answerIp = "93.184.216.34")
        assertTrue(provider(clientReturningBytes(200, body)).fetchSignals("example.com").isEmpty())
    }

    @Test
    fun `known tracker is flagged even when dns resolves clean`() = runTest {
        val body = dnsResponse(answerIp = "142.250.0.1")
        val signals = provider(clientReturningBytes(200, body)).fetchSignals("doubleclick.net")

        assertEquals(1, signals.size)
        assertEquals("NEXTDNS_BLOCK", signals[0].ruleId)
    }

    @Test
    fun `unextractable input yields no signal without network`() = runTest {
        assertTrue(provider(clientFailing()).fetchSignals("   ").isEmpty())
    }

    // ── Wireformat helpers ────────────────────────────────────────────────────

    @Test
    fun `query builder emits valid wireformat`() {
        val query = NextDnsDomainSignalProvider.buildDnsQuery("example.com")
        // header(12) + 1+7 "example" + 1+3 "com" + root(1) + QTYPE/QCLASS(4)
        assertEquals(29, query.size)
        assertEquals(1, query[5].toInt())   // QDCOUNT = 1
        assertEquals(7, query[12].toInt())  // first label length
        assertEquals(1, query[28].toInt())  // QCLASS = IN
    }

    @Test
    fun `clean response parses as not blocked`() {
        assertFalse(NextDnsDomainSignalProvider.isBlockedResponse(dnsResponse(answerIp = "1.2.3.4")))
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
    fun `malformed payload propagates as failure`() = runTest {
        val result = runCatching {
            provider(clientReturningBytes(200, "garbage".toByteArray())).fetchSignals("example.com")
        }
        assertTrue("Garbage wireformat must fail loud", result.isFailure)
    }

    // ── Blank profile (no NEXTDNS_PROFILE_ID configured) ──────────────────────

    @Test
    fun `blank profile skips network and yields no signal for unknown domain`() = runTest {
        val provider = NextDnsDomainSignalProvider(clientFailing(), profileId = "")
        // clientFailing() would throw if the network were hit; a clean empty result proves it wasn't.
        assertTrue(provider.fetchSignals("example.com").isEmpty())
    }

    @Test
    fun `blank profile still flags known trackers offline`() = runTest {
        val provider = NextDnsDomainSignalProvider(clientFailing(), profileId = "")
        val signals = provider.fetchSignals("doubleclick.net")

        assertEquals(1, signals.size)
        assertEquals("NEXTDNS_OFFLINE_BLOCK", signals[0].ruleId)
    }

    @Test
    fun `http error status fails loud instead of returning clean`() = runTest {
        for (code in listOf(429, 500)) {
            val result = runCatching { provider(clientReturning(code, "server error")).fetchSignals("example.com") }
            assertTrue("HTTP $code must fail loud, not return a clean result", result.isFailure)
        }
    }
}
