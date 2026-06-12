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
