package com.linkguard.app.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Membership and matching contract for the shared domain lists. The two sets overlap on the
 * Google ad hosts by design; these tests pin that down so the duplication reads as intent
 * rather than as an oversight waiting to be deduplicated.
 */
class KnownDomainsTest {

    @Test
    fun `the google ad hosts are intentionally both trusted and trackers`() {
        val overlap = setOf("doubleclick.net", "googlesyndication.com", "googleadservices.com")

        for (domain in overlap) {
            assertTrue("$domain must stay trusted", KnownDomains.isTrusted(domain))
            assertTrue("$domain must stay a tracker", KnownDomains.isTracker(domain))
        }
        assertEquals(
            "Only the Google ad hosts may appear in both lists",
            overlap,
            KnownDomains.TRUSTED_DOMAINS intersect KnownDomains.TRACKER_DOMAINS
        )
    }

    @Test
    fun `subdomains inherit the parent classification`() {
        assertTrue(KnownDomains.isTrusted("accounts.google.com"))
        assertTrue(KnownDomains.isTracker("stats.crashlytics.com"))
    }

    @Test
    fun `a lookalike suffix does not match`() {
        // "notgoogle.com" ends with "google.com" as a raw string but is a different host.
        assertFalse(KnownDomains.isTrusted("notgoogle.com"))
        assertFalse(KnownDomains.isTracker("notadjust.com"))
    }

    @Test
    fun `a null domain is neither trusted nor a tracker`() {
        assertFalse(KnownDomains.isTrusted(null))
        assertFalse(KnownDomains.isTracker(null))
    }
}
