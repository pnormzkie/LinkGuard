package com.linkguard.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UrlPrivacyTest {

    @Test
    fun `userinfo and fragment are stripped for external lookup`() {
        val sanitized = UrlPrivacy.sanitizeForExternalLookup(
            "https://user:hunter2@example.com/login?next=/home#refresh-token"
        )

        assertEquals("https://example.com/login?next=/home", sanitized)
        assertFalse(sanitized!!.contains("hunter2"))
        assertFalse(sanitized.contains("refresh-token"))
    }

    @Test
    fun `path and query are preserved for reputation matching`() {
        val sanitized = UrlPrivacy.sanitizeForExternalLookup(
            "https://example.com/a/b/c?q=phish&id=42"
        )

        assertEquals("https://example.com/a/b/c?q=phish&id=42", sanitized)
        assertFalse(sanitized!!.endsWith("#"))
    }

    @Test
    fun `plain url passes through unchanged`() {
        val url = "https://example.com/path"
        assertEquals(url, UrlPrivacy.sanitizeForExternalLookup(url))
    }

    @Test
    fun `unparseable input returns null`() {
        assertNull(UrlPrivacy.sanitizeForExternalLookup("not a url"))
        assertNull(UrlPrivacy.sanitizeForExternalLookup(""))
    }
}
