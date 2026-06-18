package com.linkguard.app.util

import com.linkguard.app.util.AlertCapabilities.Gap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AlertCapabilitiesTest {

    @Test
    fun `pre-Tiramisu reports no gaps even when capabilities look off`() {
        // Below API 33 there is no runtime notification permission and no FSI gating.
        val gaps = AlertCapabilities.missing(
            sdkInt = 31,
            notificationsEnabled = false,
            canUseFullScreenIntent = false,
        )
        assertTrue(gaps.isEmpty())
    }

    @Test
    fun `Tiramisu with notifications off reports only the notifications gap`() {
        // API 33 gates POST_NOTIFICATIONS but not full-screen intent.
        val gaps = AlertCapabilities.missing(
            sdkInt = 33,
            notificationsEnabled = false,
            canUseFullScreenIntent = false,
        )
        assertEquals(listOf(Gap.NOTIFICATIONS_DISABLED), gaps)
    }

    @Test
    fun `Tiramisu with notifications on reports no gaps`() {
        val gaps = AlertCapabilities.missing(
            sdkInt = 33,
            notificationsEnabled = true,
            canUseFullScreenIntent = false,
        )
        assertTrue(gaps.isEmpty())
    }

    @Test
    fun `UpsideDownCake reports both gaps when both capabilities are missing`() {
        val gaps = AlertCapabilities.missing(
            sdkInt = 34,
            notificationsEnabled = false,
            canUseFullScreenIntent = false,
        )
        // Notifications first (fix-priority order).
        assertEquals(listOf(Gap.NOTIFICATIONS_DISABLED, Gap.FULL_SCREEN_INTENT_BLOCKED), gaps)
    }

    @Test
    fun `UpsideDownCake with notifications on but FSI blocked reports only the FSI gap`() {
        val gaps = AlertCapabilities.missing(
            sdkInt = 34,
            notificationsEnabled = true,
            canUseFullScreenIntent = false,
        )
        assertEquals(listOf(Gap.FULL_SCREEN_INTENT_BLOCKED), gaps)
    }

    @Test
    fun `UpsideDownCake with everything granted reports no gaps`() {
        val gaps = AlertCapabilities.missing(
            sdkInt = 34,
            notificationsEnabled = true,
            canUseFullScreenIntent = true,
        )
        assertTrue(gaps.isEmpty())
    }
}
