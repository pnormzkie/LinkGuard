package com.linkguard.app

import com.linkguard.app.util.AppConfig
import org.junit.Assert.assertEquals
import org.junit.Test

class ScannerProviderTest {
    @Test
    fun `provider client bounds the entire call including response body`() {
        assertEquals(AppConfig.PROVIDER_TIMEOUT_MS.toInt(), ScannerProvider.okHttpClient.callTimeoutMillis)
    }
}
