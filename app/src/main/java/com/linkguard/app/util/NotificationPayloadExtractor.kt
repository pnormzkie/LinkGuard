package com.linkguard.app.util

import android.app.Notification
import android.os.Bundle

/**
 * Collects the text surfaces a notification can carry into one string for URL extraction.
 * Apps put message content in different extras: the classic text/big-text pair, InboxStyle
 * line lists, and MessagingStyle message bundles. Kept pure (plain values in, string out)
 * so the Bundles are unwired in the service and this logic is JVM-unit-testable.
 */
object NotificationPayloadExtractor {

    @Suppress("DEPRECATION")
    fun collectText(extras: Bundle): String = collectText(
        extras.getCharSequence(Notification.EXTRA_TITLE),
        extras.getCharSequence(Notification.EXTRA_TEXT),
        extras.getCharSequence(Notification.EXTRA_BIG_TEXT),
        extras.getCharSequenceArray(Notification.EXTRA_TEXT_LINES)?.toList(),
        extras.getParcelableArray(Notification.EXTRA_MESSAGES)
            ?.mapNotNull { (it as? Bundle)?.getCharSequence("text") }
    )

    /**
     * Joins every non-blank segment; segments that repeat (big text commonly mirrors the
     * short text) are collapsed so the same URL is not treated as multiple sightings.
     */
    fun collectText(
        title: CharSequence?,
        text: CharSequence?,
        bigText: CharSequence?,
        textLines: List<CharSequence>?,
        messages: List<CharSequence>?
    ): String = sequenceOf(title, text, bigText)
        .plus(textLines.orEmpty().asSequence())
        .plus(messages.orEmpty().asSequence())
        .map { it?.toString()?.trim().orEmpty() }
        .filter { it.isNotEmpty() }
        .distinct()
        .joinToString(" ")
}
