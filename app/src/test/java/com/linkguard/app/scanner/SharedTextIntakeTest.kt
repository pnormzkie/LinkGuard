package com.linkguard.app.scanner

import com.linkguard.app.scanner.SharedTextIntake.Result
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedTextIntakeTest {

    @Test
    fun `a bare shared link scans without message context, like a typed link`() {
        assertEquals(
            Result.Links(listOf("https://evil.xyz/login"), totalFound = 1, messageText = null),
            SharedTextIntake.parse("  https://evil.xyz/login\n"),
        )
    }

    @Test
    fun `words shared with the link become its message context`() {
        val text = "Your parcel is held. Pay the delivery fee: https://evil.xyz/pay"
        assertEquals(
            Result.Links(listOf("https://evil.xyz/pay"), totalFound = 1, messageText = text),
            SharedTextIntake.parse(text),
        )
    }

    @Test
    fun `a scheme-less link inside a selected sentence is found`() {
        val result = SharedTextIntake.parse("Mag-register na dito: evil.xyz/ayuda") as Result.Links
        assertEquals(listOf("https://evil.xyz/ayuda"), result.urls)
    }

    @Test
    fun `text without a link is reported as such, never as a scan`() {
        val text = "Na-hold po ang inyong bank account. Tawagan agad ang 0917 555 0142."
        assertEquals(Result.NoLink(text), SharedTextIntake.parse(text))
    }

    @Test
    fun `nothing shared is empty`() {
        assertEquals(Result.Empty, SharedTextIntake.parse(null))
        assertEquals(Result.Empty, SharedTextIntake.parse(""))
        assertEquals(Result.Empty, SharedTextIntake.parse(" \n\t "))
    }

    @Test
    fun `every link up to the limit is checked, so a clean first link cannot hide a later one`() {
        val result = SharedTextIntake.parse(
            "From https://bdo.com.ph: verify at https://evil.xyz/login"
        ) as Result.Links
        assertEquals(listOf("https://bdo.com.ph", "https://evil.xyz/login"), result.urls)
        assertEquals(2, result.totalFound)
    }

    @Test
    fun `links past the limit are counted but not checked`() {
        val text = (1..5).joinToString(" ") { "https://site$it.example/a" }
        val result = SharedTextIntake.parse(text) as Result.Links
        assertEquals(SharedTextIntake.MAX_LINKS, result.urls.size)
        assertEquals("https://site1.example/a", result.urls.first())
        assertEquals(5, result.totalFound)
    }

    @Test
    fun `text is cut to the cap before anything reads it`() {
        val padding = "x ".repeat(SharedTextIntake.MAX_TEXT_CHARS)
        // A link beyond the cap is not seen: the card then says no link was found, which is true
        // of what was read and makes no claim about the rest.
        val beyond = SharedTextIntake.parse(padding + "https://evil.xyz/login")
        assertTrue(beyond is Result.NoLink)
        assertTrue((beyond as Result.NoLink).text.length <= SharedTextIntake.MAX_TEXT_CHARS)

        val within = SharedTextIntake.parse("https://evil.xyz/login $padding") as Result.Links
        assertEquals(listOf("https://evil.xyz/login"), within.urls)
        assertTrue(within.messageText!!.length <= SharedTextIntake.MAX_TEXT_CHARS)
    }

    // Shared text is written by the sender and delivered by any app, and it is parsed on the
    // main thread before a card is shown. Each of these almost matches a link pattern.
    @Test(timeout = 5_000)
    fun `near-miss text much larger than the cap is handled quickly`() {
        val size = 200_000
        listOf(
            "1".repeat(size),
            "a".repeat(size),
            "a.".repeat(size / 2),
            "a-".repeat(size / 2),
            "ab.cd-".repeat(size / 6),
            "http://".repeat(size / 7),
            "https://a.b/" + "(".repeat(size),
            "xn--".repeat(size / 4),
        ).forEach { SharedTextIntake.parse(it) }
    }
}
