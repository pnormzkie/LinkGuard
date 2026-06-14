package com.linkguard.app.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationFilterTest {

    private val own = "com.linkguard.app"

    private fun shouldScan(
        pkg: String,
        ownPackage: String = own,
        ongoing: Boolean = false,
        groupSummary: Boolean = false,
    ) = NotificationFilter.shouldScan(pkg, ownPackage, ongoing, groupSummary)

    // --- Core new behaviour: any app is scanned by default -------------------

    @Test
    fun `unknown messaging app not in the old allowlist is scanned`() {
        // Slack / Teams were NOT in the old 19-package allowlist.
        assertTrue(shouldScan("com.Slack"))
        assertTrue(shouldScan("com.microsoft.teams"))
    }

    @Test
    fun `previously allowlisted app is still scanned`() {
        assertTrue(shouldScan("com.whatsapp"))
    }

    @Test
    fun `system-namespaced messaging app (SMS) is still scanned`() {
        // Guards against over-exclusion: a broad "com.android.*" denylist would
        // wrongly drop the stock SMS app.
        assertTrue(shouldScan("com.android.mms"))
    }

    // --- Exclusions ----------------------------------------------------------

    @Test
    fun `own package is never scanned (self-scan loop guard)`() {
        assertFalse(shouldScan(own))
    }

    @Test
    fun `system surfaces are excluded`() {
        assertFalse(shouldScan("android"))
        assertFalse(shouldScan("com.android.systemui"))
        assertFalse(shouldScan("com.android.vending"))
        assertFalse(shouldScan("com.google.android.gms"))
    }

    @Test
    fun `ongoing notifications are skipped`() {
        assertFalse(shouldScan("com.whatsapp", ongoing = true))
    }

    @Test
    fun `group summary notifications are skipped`() {
        assertFalse(shouldScan("com.whatsapp", groupSummary = true))
    }

    @Test
    fun `blank package is rejected`() {
        assertFalse(shouldScan(""))
    }

    // --- Breadth scope (Phase 2: seed-19 default vs opt-in scan-all) ----------

    @Test
    fun `seed messaging app is in scope even when scan-all is off`() {
        assertTrue(NotificationFilter.isWithinScope("com.whatsapp", scanAllApps = false))
    }

    @Test
    fun `non-seed app is out of scope when scan-all is off (default)`() {
        assertFalse(NotificationFilter.isWithinScope("com.Slack", scanAllApps = false))
        assertFalse(NotificationFilter.isWithinScope("com.microsoft.teams", scanAllApps = false))
    }

    @Test
    fun `non-seed app comes into scope when scan-all is on`() {
        assertTrue(NotificationFilter.isWithinScope("com.Slack", scanAllApps = true))
    }

    @Test
    fun `seed app stays in scope when scan-all is on`() {
        assertTrue(NotificationFilter.isWithinScope("com.whatsapp", scanAllApps = true))
    }
}
