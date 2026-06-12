package com.linkguard.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    // ── isNewerVersion ────────────────────────────────────────────────────────

    @Test
    fun `newer minor version triggers update`() {
        assertTrue(UpdateChecker.isNewerVersion("v1.2", "1.1"))
    }

    @Test
    fun `same version does not trigger update`() {
        assertFalse(UpdateChecker.isNewerVersion("1.1", "1.1"))
        assertFalse(UpdateChecker.isNewerVersion("v1.1", "1.1"))
    }

    @Test
    fun `older version does not trigger update`() {
        assertFalse(UpdateChecker.isNewerVersion("1.0", "1.1"))
    }

    @Test
    fun `extra patch component counts as newer`() {
        assertTrue(UpdateChecker.isNewerVersion("1.1.1", "1.1"))
    }

    @Test
    fun `numeric compare beats string compare`() {
        assertTrue(UpdateChecker.isNewerVersion("1.10", "1.9"))
    }

    @Test
    fun `major version bump without minor counts as newer`() {
        assertTrue(UpdateChecker.isNewerVersion("v2", "1.9"))
    }

    @Test
    fun `pre-release suffix is ignored for comparison`() {
        assertTrue(UpdateChecker.isNewerVersion("2.0-beta1", "1.9"))
    }

    @Test
    fun `unparseable remote version never triggers update`() {
        assertFalse(UpdateChecker.isNewerVersion("latest", "1.1"))
        assertFalse(UpdateChecker.isNewerVersion("", "1.1"))
    }

    // ── parseRelease ──────────────────────────────────────────────────────────

    private val fullRelease = """
        {
          "tag_name": "v1.2",
          "html_url": "https://github.com/pnormzkie/LinkGuard/releases/tag/v1.2",
          "body": "Bug fixes and improvements",
          "assets": [
            { "name": "mapping.txt", "browser_download_url": "https://example.com/mapping.txt" },
            { "name": "LinkGuard-v1.2.apk", "browser_download_url": "https://example.com/LinkGuard-v1.2.apk" }
          ]
        }
    """.trimIndent()

    @Test
    fun `parses full release with apk asset`() {
        val info = UpdateChecker.parseRelease(fullRelease)!!
        assertEquals("1.2", info.versionName)
        assertEquals("Bug fixes and improvements", info.releaseNotes)
        assertEquals("https://example.com/LinkGuard-v1.2.apk", info.apkUrl)
        assertEquals("LinkGuard-v1.2.apk", info.apkName)
        assertEquals("https://github.com/pnormzkie/LinkGuard/releases/tag/v1.2", info.htmlUrl)
    }

    @Test
    fun `release without apk asset still parses with null apkUrl`() {
        val info = UpdateChecker.parseRelease("""{ "tag_name": "v1.3", "html_url": "https://x", "assets": [] }""")!!
        assertEquals("1.3", info.versionName)
        assertNull(info.apkUrl)
    }

    @Test
    fun `release without tag is rejected`() {
        assertNull(UpdateChecker.parseRelease("""{ "html_url": "https://x" }"""))
    }

    @Test
    fun `null body yields empty release notes`() {
        val info = UpdateChecker.parseRelease("""{ "tag_name": "v1.4", "body": null }""")!!
        assertEquals("", info.releaseNotes)
    }

    @Test
    fun `garbage json is rejected`() {
        assertNull(UpdateChecker.parseRelease("not json at all"))
    }
}
