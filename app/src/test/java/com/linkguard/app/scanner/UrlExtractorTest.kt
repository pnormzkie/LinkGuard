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

    @Test
    fun `explicit scheme url is not subject to tld allowlist`() {
        assertEquals(listOf("https://PM.This"), UrlExtractor.extractUrls("Open https://PM.This"))
    }
}
