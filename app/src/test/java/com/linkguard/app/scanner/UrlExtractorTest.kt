package com.linkguard.app.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlExtractorTest {

    @Test
    fun `scheme-less suspicious tld reaches the scanner`() {
        assertEquals(listOf("https://evil.xyz"), UrlExtractor.extractUrls("Please visit evil.xyz now"))
    }

    @Test
    fun `trusted bare host path is extracted instead of pre-whitelisted`() {
        assertEquals(
            listOf("https://github.com/attacker/malware.apk"),
            UrlExtractor.extractUrls("Download github.com/attacker/malware.apk")
        )
    }

    @Test
    fun `sentence punctuation and unmatched wrappers are removed`() {
        assertEquals(
            listOf("https://evil.example/login"),
            UrlExtractor.extractUrls("See (https://evil.example/login).")
        )
    }

    @Test
    fun `balanced url parentheses are preserved`() {
        assertEquals(
            listOf("https://example.com/wiki/Test_(example)"),
            UrlExtractor.extractUrls("Open https://example.com/wiki/Test_(example).")
        )
    }

    @Test
    fun `unicode lookalike host is extracted intact for homoglyph analysis`() {
        assertEquals(listOf("https://pаypal.com"), UrlExtractor.extractUrls("Open pаypal.com"))
    }

    @Test
    fun `bare domain inside an explicit url is not duplicated`() {
        val urls = UrlExtractor.extractUrls("Open https://evil.xyz/path")
        assertEquals(listOf("https://evil.xyz/path"), urls)
        assertTrue(urls.distinct().size == urls.size)
    }

    @Test
    fun `prose run-on with non-tld word is not extracted`() {
        assertEquals(emptyList<String>(), UrlExtractor.extractUrls("Your table is booked for 3 PM.This message is automated"))
        assertEquals(emptyList<String>(), UrlExtractor.extractUrls("Thanks.Please reply soon"))
    }

    @Test
    fun `prose run-on with real cctld but title case is not extracted`() {
        assertEquals(emptyList<String>(), UrlExtractor.extractUrls("See you on Mon.In the morning"))
    }

    @Test
    fun `less common real tlds are still extracted`() {
        assertEquals(listOf("https://evil.zip"), UrlExtractor.extractUrls("Open evil.zip now"))
        assertEquals(listOf("https://evil.mov"), UrlExtractor.extractUrls("Open evil.mov now"))
    }

    @Test
    fun `lowercase or pathed bare hosts with word-like cctld are still extracted`() {
        assertEquals(listOf("https://evil.in"), UrlExtractor.extractUrls("Open evil.in now"))
        assertEquals(listOf("https://Evil.In/login"), UrlExtractor.extractUrls("Open Evil.In/login now"))
    }

    // Found 2026-11-11 while adding Share to LinkGuard: the bare-domain pattern repeated its
    // label group without a limit, and 4,000 characters of "a." ended in StackOverflowError.
    @Test(timeout = 5_000)
    fun `a long run of dotted labels does not overflow the stack`() {
        listOf(1_000, 4_000, 50_000).forEach { size ->
            UrlExtractor.extractUrls("a.".repeat(size / 2))
            UrlExtractor.extractUrls("ab-cd.".repeat(size / 6) + "xyz")
        }
    }

    @Test
    fun `a host with many labels is still extracted whole`() {
        val host = (1..20).joinToString(".") { "l$it" } + ".example.com"
        assertEquals(listOf("https://$host/login"), UrlExtractor.extractUrls("Open $host/login now"))
    }

    @Test
    fun `explicit scheme url is not subject to tld allowlist`() {
        assertEquals(listOf("https://PM.This"), UrlExtractor.extractUrls("Open https://PM.This"))
    }
}
