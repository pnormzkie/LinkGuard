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

    @Test
    fun `trusted sites, shorteners, brand links and app links are known forwarders`() {
        for (domain in listOf(
            "google.com", "l.facebook.com",          // trusted
            "bit.ly", "maps.app.goo.gl",              // public shortener, and a subdomain of one
            "shp.ee", "youtu.be", "smrt.ph",          // a brand's own forwarding domain
            "grab.onelink.me", "app.appsflyer.com"    // app-install link service
        )) {
            assertTrue(domain, KnownDomains.isKnownForwarder(domain))
        }
    }

    @Test
    fun `an ordinary or look-alike host is not a known forwarder`() {
        for (domain in listOf(
            "gcash-verify.top", "parcel-redelivery.info", "example.com",
            "notbit.ly", "shp.ee.evil.xyz", "onelink.me.evil.xyz", "pldt.ph", "gcash.co",
            "gcsh.app", "glo.be" // ownership not confirmed
        )) {
            assertFalse(domain, KnownDomains.isKnownForwarder(domain))
        }
        assertFalse(KnownDomains.isKnownForwarder(null))
    }

    @Test
    fun `a brand forwarding domain is not trusted as a destination`() {
        // The list only says who does the forwarding. A page on these hosts is still scanned.
        for (domain in KnownDomains.BRAND_FORWARDING_DOMAINS + KnownDomains.APP_LINK_SERVICES) {
            assertFalse(domain, KnownDomains.isTrusted(domain))
        }
    }
}
