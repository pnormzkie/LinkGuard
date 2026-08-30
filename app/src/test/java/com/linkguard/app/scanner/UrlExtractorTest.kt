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
}
