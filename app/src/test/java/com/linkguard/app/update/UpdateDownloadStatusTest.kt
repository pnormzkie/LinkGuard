package com.linkguard.app.update

import android.app.DownloadManager
import com.linkguard.app.update.UpdateInstaller.DownloadStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Unit coverage for the pure download-progress mapping that drives the update dialog
 * ([UpdateInstaller.percentOf] / [UpdateInstaller.mapStatus]). The DownloadManager status
 * constants are real compile-time ints, so this maps without any framework objects.
 */
class UpdateDownloadStatusTest {

    // ── percentOf ───────────────────────────────────────────────────────────

    @Test fun `percent is zero when total unknown`() {
        assertEquals(0, UpdateInstaller.percentOf(soFar = 0, total = 0))
        assertEquals(0, UpdateInstaller.percentOf(soFar = 5_000, total = 0))
        assertEquals(0, UpdateInstaller.percentOf(soFar = 5_000, total = -1))
    }

    @Test fun `percent is integer floor of the ratio`() {
        assertEquals(0, UpdateInstaller.percentOf(soFar = 0, total = 100))
        assertEquals(50, UpdateInstaller.percentOf(soFar = 50, total = 100))
        assertEquals(100, UpdateInstaller.percentOf(soFar = 100, total = 100))
        assertEquals(33, UpdateInstaller.percentOf(soFar = 1, total = 3))
        assertEquals(47, UpdateInstaller.percentOf(soFar = 11_500_000, total = 24_400_000))
    }

    @Test fun `percent is clamped to 100 when bytes overshoot total`() {
        assertEquals(100, UpdateInstaller.percentOf(soFar = 150, total = 100))
    }

    // ── mapStatus ───────────────────────────────────────────────────────────

    @Test fun `successful maps to Succeeded`() {
        assertEquals(
            DownloadStatus.Succeeded,
            UpdateInstaller.mapStatus(DownloadManager.STATUS_SUCCESSFUL, 0, 24L, 24L)
        )
    }

    @Test fun `failed maps to Failed`() {
        assertEquals(
            DownloadStatus.Failed,
            UpdateInstaller.mapStatus(DownloadManager.STATUS_FAILED, DownloadManager.ERROR_UNKNOWN, 5L, 24L)
        )
    }

    @Test fun `pending maps to Pending`() {
        assertEquals(
            DownloadStatus.Pending,
            UpdateInstaller.mapStatus(DownloadManager.STATUS_PENDING, 0, 0L, 0L)
        )
    }

    @Test fun `running carries percent and bytes`() {
        val status = UpdateInstaller.mapStatus(DownloadManager.STATUS_RUNNING, 0, 12L, 24L)
        assertEquals(DownloadStatus.Running(percent = 50, soFar = 12L, total = 24L), status)
    }

    @Test fun `paused carries percent and bytes`() {
        val status = UpdateInstaller.mapStatus(
            DownloadManager.STATUS_PAUSED, DownloadManager.PAUSED_WAITING_FOR_NETWORK, 6L, 24L
        )
        assertEquals(DownloadStatus.Paused(percent = 25, soFar = 6L, total = 24L), status)
    }

    @Test fun `unknown status maps to Failed`() {
        assertEquals(DownloadStatus.Failed, UpdateInstaller.mapStatus(99999, 0, 0L, 0L))
    }
}
