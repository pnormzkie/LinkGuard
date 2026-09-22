package com.linkguard.app.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Regression coverage for the update-URL allow-list (S1: APK update installed with no
 * integrity verification). Only HTTPS URLs whose host is an allowed GitHub release host
 * (exact or subdomain) may be downloaded as an update.
 */
class UpdateInstallerUrlTest {

    @Test
    fun `accepts github browser_download_url over https`() {
        assertTrue(
            UpdateInstaller.isTrustedUpdateUrl(
                "https://github.com/pnormzkie/LinkGuard/releases/download/v1.2/LinkGuard-v1.2.apk"
            )
        )
    }

    @Test
    fun `accepts githubusercontent release object host`() {
        assertTrue(
            UpdateInstaller.isTrustedUpdateUrl(
                "https://objects.githubusercontent.com/github-production-release-asset/abc/LinkGuard.apk"
            )
        )
    }

    @Test
    fun `accepts subdomain of allowed host`() {
        assertTrue(UpdateInstaller.isTrustedUpdateUrl("https://release-assets.github.com/x.apk"))
    }

    @Test
    fun `accepts the host github actually redirects release downloads to`() {
        // Measured 2026-09-23: browser_download_url on github.com 302s here. The old list
        // would have failed closed if GitHub ever returned this URL directly, which stops
        // updates silently rather than visibly.
        assertTrue(
            UpdateInstaller.isTrustedUpdateUrl(
                "https://release-assets.githubusercontent.com/github-production-release-asset/1/x?sig=abc"
            )
        )
    }

    @Test
    fun `the new host gets the same lookalike protection as the others`() {
        // Subdomain trick.
        assertFalse(
            UpdateInstaller.isTrustedUpdateUrl("https://release-assets.githubusercontent.com.evil.com/x.apk")
        )
        // Substring trick: a different host that merely starts the same way.
        assertFalse(
            UpdateInstaller.isTrustedUpdateUrl("https://notrelease-assets.githubusercontent.com/x.apk")
        )
        // Widening the host list must not have widened the scheme rule.
        assertFalse(
            UpdateInstaller.isTrustedUpdateUrl("http://release-assets.githubusercontent.com/x.apk")
        )
    }

    @Test
    fun `rejects plain http`() {
        assertFalse(
            UpdateInstaller.isTrustedUpdateUrl("http://github.com/x/releases/download/v1/x.apk")
        )
    }

    @Test
    fun `rejects unrelated host`() {
        assertFalse(UpdateInstaller.isTrustedUpdateUrl("https://example.com/LinkGuard.apk"))
    }

    @Test
    fun `rejects subdomain-trick lookalike host`() {
        assertFalse(UpdateInstaller.isTrustedUpdateUrl("https://github.com.evil.com/x.apk"))
    }

    @Test
    fun `rejects path-trick lookalike host`() {
        assertFalse(UpdateInstaller.isTrustedUpdateUrl("https://evil.com/github.com/x.apk"))
    }

    @Test
    fun `rejects host containing allowed host as substring`() {
        assertFalse(UpdateInstaller.isTrustedUpdateUrl("https://notgithub.com/x.apk"))
        assertFalse(UpdateInstaller.isTrustedUpdateUrl("https://mygithub.com/x.apk"))
    }

    @Test
    fun `rejects credentials embedded host trick`() {
        // Userinfo before @ must not be mistaken for the host.
        assertFalse(UpdateInstaller.isTrustedUpdateUrl("https://github.com@evil.com/x.apk"))
    }

    @Test
    fun `rejects null blank and malformed`() {
        assertFalse(UpdateInstaller.isTrustedUpdateUrl(null))
        assertFalse(UpdateInstaller.isTrustedUpdateUrl(""))
        assertFalse(UpdateInstaller.isTrustedUpdateUrl("   "))
        assertFalse(UpdateInstaller.isTrustedUpdateUrl("not a url"))
        assertFalse(UpdateInstaller.isTrustedUpdateUrl("ftp://github.com/x.apk"))
    }
}
