package com.linkguard.app.ui

import android.content.Intent
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.linkguard.app.R
import com.linkguard.app.data.ScanResult
import com.linkguard.app.data.ThreatLevel
import org.hamcrest.CoreMatchers.containsString
import org.hamcrest.CoreMatchers.not
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@MediumTest
class ThreatAlertActivityIntentTest {

    private val targetContext
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun newIntentReplacesTheVisibleThreatReport() {
        val firstUrl = "https://first-alert.example/phishing"
        val secondUrl = "https://second-alert.example/malware"
        val scenario = ActivityScenario.launch<ThreatAlertActivity>(
            ThreatAlertActivity.newIntent(targetContext, scanResult(firstUrl))
        )

        try {
            onView(withId(R.id.alertMessage))
                .check(matches(withText(containsString(firstUrl))))

            targetContext.startActivity(
                ThreatAlertActivity.newIntent(targetContext, scanResult(secondUrl))
            )

            onView(withId(R.id.alertMessage))
                .check(matches(withText(containsString(secondUrl))))
            onView(withId(R.id.alertMessage))
                .check(matches(not(withText(containsString(firstUrl)))))
        } finally {
            scenario.close()
        }
    }

    @Test
    fun missingScanResultFinishesWithoutShowingAnEmptyAlert() {
        val scenario = ActivityScenario.launch<ThreatAlertActivity>(
            Intent(targetContext, ThreatAlertActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )

        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
        assertEquals(Lifecycle.State.DESTROYED, scenario.state)
    }

    private fun scanResult(url: String) = ScanResult(
        url = url,
        threatLevel = ThreatLevel.DANGER,
        riskScore = 95,
        category = "Known threat",
        flags = listOf("Test signal"),
        sourceApp = "Test source",
        senderInfo = "Test sender",
    )
}
