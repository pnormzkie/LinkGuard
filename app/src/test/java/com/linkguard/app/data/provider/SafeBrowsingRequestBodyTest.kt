package com.linkguard.app.data.provider

import com.google.gson.Gson
import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Regression coverage for S2: the Safe Browsing request body was built by string
 * interpolation, so a URL containing `"`, `\`, or a newline produced malformed JSON.
 * The API returned 400 and the strongest provider silently dropped out. The body must
 * now be valid JSON that round-trips the URL exactly.
 */
class SafeBrowsingRequestBodyTest {

    private val gson = Gson()

    private fun urlInBody(input: String): String {
        val body = SafeBrowsingReputationProvider.buildRequestBody(input)
        // Throws if the body is not valid JSON — that is the regression we guard against.
        val root = gson.fromJson(body, JsonObject::class.java)
        return root.getAsJsonObject("threatInfo")
            .getAsJsonArray("threatEntries")
            .get(0).asJsonObject
            .get("url").asString
    }

    @Test
    fun `plain url round-trips`() {
        assertEquals("https://example.com/path", urlInBody("https://example.com/path"))
    }

    @Test
    fun `url with double quote round-trips and stays valid json`() {
        val input = """https://evil.com/"}],"injected":true,"x":["""
        assertEquals(input, urlInBody(input))
    }

    @Test
    fun `url with backslash round-trips`() {
        val input = """https://evil.com/a\b\c"""
        assertEquals(input, urlInBody(input))
    }

    @Test
    fun `url with newline round-trips`() {
        val input = "https://evil.com/a\nb"
        assertEquals(input, urlInBody(input))
    }

    @Test
    fun `static request fields are present`() {
        val root = gson.fromJson(
            SafeBrowsingReputationProvider.buildRequestBody("https://x"),
            JsonObject::class.java
        )
        assertEquals("linkguard", root.getAsJsonObject("client").get("clientId").asString)
        val info = root.getAsJsonObject("threatInfo")
        assertEquals(4, info.getAsJsonArray("threatTypes").size())
        assertEquals("ANY_PLATFORM", info.getAsJsonArray("platformTypes").get(0).asString)
        assertEquals("URL", info.getAsJsonArray("threatEntryTypes").get(0).asString)
    }
}
