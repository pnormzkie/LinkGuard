package com.linkguard.app.ui

import android.Manifest
import android.content.Intent
import android.os.SystemClock
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.android.material.R as MaterialR
import com.linkguard.app.R
import com.linkguard.app.data.ScanRepository
import com.linkguard.app.data.ThreatLevel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class PaymentQrFlowTest {

    private val instrumentation
        get() = InstrumentationRegistry.getInstrumentation()
    private val targetContext
        get() = instrumentation.targetContext
    private val repository by lazy { ScanRepository(targetContext) }

    @Before
    fun clearHistory() {
        instrumentation.uiAutomation.grantRuntimePermission(
            targetContext.packageName,
            Manifest.permission.CAMERA,
        )
        runBlocking { repository.clearHistory() }
    }

    @After
    fun cleanupHistory() = runBlocking { repository.clearHistory() }

    @Test
    fun validFormatIsDisplayedAndStoredAsPayeeUnverified() {
        val resultIntent = Intent().putExtra("QR_RESULT", validPaymentPayload())
        val scenario = ActivityScenario.launch(MainActivity::class.java)

        try {
            onView(withId(R.id.btnScanQR)).perform(scrollTo(), click())
            val qrActivity = waitForResumedQrActivity()
            instrumentation.runOnMainSync {
                qrActivity.setResult(android.app.Activity.RESULT_OK, resultIntent)
                qrActivity.finish()
            }

            val snackbarText = waitForSnackbarText()
            assertTrue(snackbarText.contains("Valid payment QR format"))
            assertTrue(snackbarText.contains("Always verify merchant details before paying"))

            val saved = runBlocking {
                withTimeout(5_000) {
                    var result = repository.getAllScans().firstOrNull()
                    while (result == null) {
                        delay(50)
                        result = repository.getAllScans().firstOrNull()
                    }
                    requireNotNull(result)
                }
            }
            assertEquals(ThreatLevel.SUSPICIOUS, saved.threatLevel)
            assertEquals(25, saved.riskScore)
            assertEquals("Payment QR — Payee unverified", saved.category)
            assertTrue(saved.flags.any { it.contains("recipient account was not verified") })
        } finally {
            scenario.close()
        }
    }

    private fun waitForResumedQrActivity(): QrScannerActivity {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var resumed: QrScannerActivity? = null
            instrumentation.runOnMainSync {
                resumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<QrScannerActivity>()
                    .firstOrNull()
            }
            if (resumed != null) return requireNotNull(resumed)
            SystemClock.sleep(50)
        }
        error("QrScannerActivity did not reach RESUMED")
    }

    private fun waitForSnackbarText(): String {
        val deadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < deadline) {
            var text: String? = null
            instrumentation.runOnMainSync {
                val activity = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .filterIsInstance<MainActivity>()
                    .firstOrNull()
                text = activity
                    ?.findViewById<TextView>(MaterialR.id.snackbar_text)
                    ?.text
                    ?.toString()
            }
            if (text != null) return requireNotNull(text)
            SystemClock.sleep(25)
        }
        error("Payment QR result Snackbar was not displayed")
    }

    private fun validPaymentPayload(): String {
        val body = "000201" + "5910TEST STORE" + "6006MANILA" + "6304"
        return body + crc16(body)
    }

    private fun crc16(data: String): String {
        var crc = 0xFFFF
        for (byte in data.toByteArray(Charsets.UTF_8)) {
            crc = crc xor ((byte.toInt() and 0xFF) shl 8)
            repeat(8) {
                crc = if ((crc and 0x8000) != 0) (crc shl 1) xor 0x1021 else crc shl 1
                crc = crc and 0xFFFF
            }
        }
        return crc.toString(16).uppercase().padStart(4, '0')
    }
}
