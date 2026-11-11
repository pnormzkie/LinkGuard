package com.linkguard.app.scanner

/**
 * Turns text handed to LinkGuard by another app (Share sheet, text-selection menu) into
 * something to scan.
 *
 * The text is written by whoever sent the message, and any app can deliver it, so it is cut to
 * [MAX_TEXT_CHARS] before any pattern runs on it: the size cap, not a coroutine timeout, is
 * what bounds the work.
 */
internal object SharedTextIntake {

    /** A few long chat messages; far above a notification's text and far below an intent's limit. */
    const val MAX_TEXT_CHARS = 4_000

    /** Each link costs a full provider round, so only the first few are checked. */
    const val MAX_LINKS = 3

    sealed interface Result {
        /**
         * @param urls the links to check, at most [MAX_LINKS]
         * @param totalFound how many links the text held, so the screen can say when some were
         *   left unchecked
         * @param messageText the text the links arrived in, or null when the share was a bare
         *   link (which then scans exactly like the same link typed in or tapped)
         */
        data class Links(val urls: List<String>, val totalFound: Int, val messageText: String?) : Result

        /** Text with no link in it; [text] is what was shared, already cut to the cap. */
        data class NoLink(val text: String) : Result

        /** Nothing readable was shared. */
        object Empty : Result
    }

    fun parse(raw: CharSequence?): Result {
        val text = raw?.toString().orEmpty().take(MAX_TEXT_CHARS).trim()
        if (text.isEmpty()) return Result.Empty

        val found = UrlExtractor.extractUrls(text)
        if (found.isEmpty()) return Result.NoLink(text)

        return Result.Links(
            urls = found.take(MAX_LINKS),
            totalFound = found.size,
            // One unbroken token is a link and nothing else; words around it are a message.
            messageText = text.takeIf { it.any(Char::isWhitespace) },
        )
    }
}
