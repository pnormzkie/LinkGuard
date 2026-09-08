package com.linkguard.app.scanner

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UrlInputNormalizerTest {

    @Test
    fun `standalone apk filename is rejected`() {
        assertNull(UrlInputNormalizer.normalize("LinkGuard-v1.28.apk"))
    }

    @Test
    fun `standalone executable filename with a scheme is rejected`() {
        assertNull(UrlInputNormalizer.normalize("https://setup.exe"))
    }

    @Test
    fun `real apk download url is accepted`() {
        val url = "https://github.com/pnormzkie/LinkGuard/releases/download/v1.28/LinkGuard-v1.28.apk"
        assertEquals(url, UrlInputNormalizer.normalize(url))
    }

    @Test
    fun `public zip domain is accepted`() {
        assertEquals("https://example.zip", UrlInputNormalizer.normalize("example.zip"))
    }

    @Test
    fun `bare domain path is normalized`() {
        assertEquals("https://example.com/download", UrlInputNormalizer.normalize("example.com/download"))
    }

    @Test
    fun `ipv4 url is accepted`() {
        assertEquals("http://192.168.0.1/app.apk", UrlInputNormalizer.normalize("http://192.168.0.1/app.apk"))
    }

    @Test
    fun `single label and malformed inputs are rejected`() {
        assertNull(UrlInputNormalizer.normalize("localhost"))
        assertNull(UrlInputNormalizer.normalize("not a url"))
        assertNull(UrlInputNormalizer.normalize("https://"))
    }

    @Test
    fun `email address is not converted into a url with user info`() {
        assertNull(UrlInputNormalizer.normalize("user@example.com"))
    }

    @Test
    fun `unicode host spelling is preserved for homoglyph analysis`() {
        val url = "https://pаypal.com/login"
        assertEquals(url, UrlInputNormalizer.normalize(url))
    }
}
