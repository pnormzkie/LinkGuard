package com.linkguard.app.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class UpdateInstallerPruneTest {

    @get:Rule
    val dir = TemporaryFolder()

    @Test
    fun `removes earlier release apks and keeps unrelated files`() {
        val old = listOf("LinkGuard-v1.41.apk", "LinkGuard-v1.44.apk", "LinkGuard-v1.44-1.apk")
            .map { dir.newFile(it) }
        val other = dir.newFile("notes.txt")
        val foreign = dir.newFile("other-app.apk")

        val removed = UpdateInstaller.pruneOldReleaseApks(dir.root)

        assertEquals(3, removed)
        old.forEach { assertFalse(it.exists()) }
        assertTrue(other.exists())
        assertTrue(foreign.exists())
    }

    @Test
    fun `missing or null directory is a no-op`() {
        assertEquals(0, UpdateInstaller.pruneOldReleaseApks(null))
        assertEquals(0, UpdateInstaller.pruneOldReleaseApks(dir.root.resolve("absent")))
    }
}
