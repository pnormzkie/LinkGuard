package com.linkguard.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DomainExtractorTest {

    @Test
    fun `preserves unicode host for homoglyph analysis`() {
        assertEquals("pаypal.com", DomainExtractor.extract("https://pаypal.com/login"))
    }

    @Test
    fun `extracts host from https url with path and query`() {
        assertEquals("example.com", DomainExtractor.extract("https://example.com/path?q=1"))
    }

    @Test
    fun `extracts host from http url`() {
        assertEquals("example.com", DomainExtractor.extract("http://example.com"))
    }

    @Test
    fun `strips www prefix`() {
        assertEquals("example.com", DomainExtractor.extract("https://www.example.com"))
    }

    @Test
    fun `lowercases the host`() {
        assertEquals("example.com", DomainExtractor.extract("https://EXAMPLE.COM/Path"))
    }

    @Test
    fun `handles url without scheme`() {
        assertEquals("example.com", DomainExtractor.extract("example.com/some/path"))
    }

    @Test
    fun `strips port`() {
        assertEquals("sub.example.com", DomainExtractor.extract("https://sub.example.com:8080/x"))
    }

    @Test
    fun `keeps non-www subdomains`() {
        assertEquals("login.bank.example.com", DomainExtractor.extract("https://login.bank.example.com"))
    }

    @Test
    fun `extracts ip literal`() {
        assertEquals("192.168.0.1", DomainExtractor.extract("http://192.168.0.1/login"))
    }

    @Test
    fun `credentials trick resolves to real host`() {
        // http://evil.com@good.com actually navigates to good.com
        assertEquals("good.com", DomainExtractor.extract("http://evil.com@good.com/"))
    }

    @Test
    fun `null input returns null`() {
        assertNull(DomainExtractor.extract(null))
    }

    @Test
    fun `blank input returns null`() {
        assertNull(DomainExtractor.extract("   "))
    }

    @Test
    fun `unparseable url falls back to string splitting`() {
        // Space makes URI() throw — fallback should still isolate the host part
        assertEquals("exam ple.com", DomainExtractor.extract("https://exam ple.com/path"))
    }

    // ── Embedded credentials (userinfo) ──────────────────────────────────────

    @Test
    fun `https credentials trick resolves to real host`() {
        assertEquals("good.com", DomainExtractor.extract("https://evil.com@good.com/"))
    }

    @Test
    fun `username-only userinfo resolves to real host`() {
        assertEquals("evil.com", DomainExtractor.extract("https://user@evil.com/"))
    }

    // EXPOSES BUG: the bad escape sequence makes URI() throw, and the fallback path
    // (DomainExtractor.kt:24-25) does not strip userinfo — it currently returns
    // "evil.com@good.com", a non-host string handed to trusted-domain endsWith checks.
    // The real navigation target is good.com.
    @Test
    fun `fallback strips embedded credentials`() {
        assertEquals("good.com", DomainExtractor.extract("https://evil.com@good.com/%%%"))
    }

    // EXPOSES BUG: with a password in the userinfo, the fallback's substringBefore(":")
    // truncates at the credential separator and currently returns "user" — not a host
    // at all. The real navigation target is evil.com.
    @Test
    fun `fallback strips credentials with password and port`() {
        assertEquals("evil.com", DomainExtractor.extract("https://user:hunter2@evil.com:8080/%%%"))
    }

    // ── Malformed scheme / fallback robustness ───────────────────────────────

    @Test
    fun `invalid scheme falls back to host extraction`() {
        // '!' is illegal in a URI scheme, so URI() throws and the fallback runs
        assertEquals("example.com", DomainExtractor.extract("ht!tp://example.com/path"))
    }

    @Test
    fun `strips port when scheme is missing`() {
        assertEquals("example.com", DomainExtractor.extract("example.com:8080/path"))
    }

    @Test
    fun `fallback lowercases and strips port`() {
        // Space forces the fallback; it must still strip the port and lowercase
        assertEquals("exam ple.com", DomainExtractor.extract("HTTPS://EXAM PLE.COM:8080/Path"))
    }
}
