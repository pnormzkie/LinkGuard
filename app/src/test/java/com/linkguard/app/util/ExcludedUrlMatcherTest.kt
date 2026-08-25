package com.linkguard.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExcludedUrlMatcherTest {

    private val exact = "https://web-family-ai.vercel.app/?k=secret"

    @Test
    fun `normalizes scheme host default port and root path`() {
        assertEquals(
            exact,
            ExcludedUrlMatcher.normalize("HTTPS://WEB-FAMILY-AI.VERCEL.APP:443?k=secret"),
        )
    }

    @Test
    fun `ignores fragment because it is not sent to server`() {
        assertTrue(ExcludedUrlMatcher.isExcluded("$exact#section", setOf(exact)))
    }

    @Test
    fun `requires exact query`() {
        assertFalse(
            ExcludedUrlMatcher.isExcluded(
                "https://web-family-ai.vercel.app/?k=different",
                setOf(exact),
            )
        )
    }

    @Test
    fun `requires exact path`() {
        assertFalse(
            ExcludedUrlMatcher.isExcluded(
                "https://web-family-ai.vercel.app/account?k=secret",
                setOf(exact),
            )
        )
    }

    @Test
    fun `requires exact host`() {
        assertFalse(
            ExcludedUrlMatcher.isExcluded(
                "https://test.web-family-ai.vercel.app/?k=secret",
                setOf(exact),
            )
        )
    }

    @Test
    fun `rejects domain without scheme`() {
        assertNull(ExcludedUrlMatcher.normalize("web-family-ai.vercel.app/?k=secret"))
    }
}
