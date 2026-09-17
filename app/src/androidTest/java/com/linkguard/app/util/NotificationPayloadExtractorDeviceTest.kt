package com.linkguard.app.util

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the same Bundle-reading path LinkNotificationService uses, against real
 * framework extras produced by Notification.MessagingStyle (the contract the plain
 * JVM tests cannot cover: android.jar stubs can't build real Bundles on the JVM).
 */
@RunWith(AndroidJUnit4::class)
class NotificationPayloadExtractorDeviceTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private fun extract(notification: Notification): String =
        NotificationPayloadExtractor.collectText(notification.extras)

    @Test
    fun messagingStyleBundleCarriesTheMessageText() {
        val contentIntent = PendingIntent.getActivity(
            context, 0, Intent("android.intent.action.MAIN"), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(contentIntent)
            .setStyle(
                Notification.MessagingStyle("Me")
                    .addMessage(
                        Notification.MessagingStyle.Message(
                            "wire the fee to safe-pay.example/transfer", System.currentTimeMillis(), "Anna"
                        )
                    )
            )
            .build()

        notification.extras.remove(Notification.EXTRA_TEXT)
        notification.extras.remove(Notification.EXTRA_BIG_TEXT)
        notification.extras.remove(Notification.EXTRA_TITLE)
        val combined = extract(notification)

        assertTrue("MessagingStyle text missing from: $combined", combined.contains("safe-pay.example/transfer"))
    }

    @Test
    fun inboxStyleLinesAreCollected() {
        val contentIntent = PendingIntent.getActivity(
            context, 1, Intent("android.intent.action.MAIN"), PendingIntent.FLAG_IMMUTABLE
        )
        val notification = Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(contentIntent)
            .setStyle(
                Notification.InboxStyle()
                    .addLine("Anna: pay at https://pay.example/inv-7")
                    .addLine("Bob: thanks")
            )
            .build()

        val combined = extract(notification)

        assertTrue("InboxStyle line missing from: $combined", combined.contains("https://pay.example/inv-7"))
        assertTrue("Second line missing from: $combined", combined.contains("Bob: thanks"))
    }

    @Test
    fun urlInBothTextAndBigTextIsNotDuplicated() {
        val contentIntent = PendingIntent.getActivity(
            context, 2, Intent("android.intent.action.MAIN"), PendingIntent.FLAG_IMMUTABLE
        )
        val shared = "Check http://phish.example/login now"
        val notification = Notification.Builder(context, "test")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentIntent(contentIntent)
            .setContentText(shared)
            .setStyle(Notification.BigTextStyle().bigText(shared))
            .build()

        val combined = extract(notification)

        assertEquals(1, Regex("http://phish\\.example/login").findAll(combined).count())
    }

    @Test
    fun caseDifferingSegmentsAreBothKept() {
        val combined = NotificationPayloadExtractor.collectText(
            "Anna", "https://Pay.example/INV-7", "https://pay.example/inv-7", null, null
        )

        assertFalse(combined.isEmpty())
        assertTrue(combined.contains("https://Pay.example/INV-7"))
        assertTrue(combined.contains("https://pay.example/inv-7"))
    }
}
