package com.linkguard.app.data.provider

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/**
 * RetryInterceptor must retry transient failures once (5xx, connection IOException) but never
 * retry a 4xx — especially 429 (quota), where a retry would waste the budget. The backoff
 * sleeper is stubbed to a no-op so the tests don't actually wait.
 */
class RetryInterceptorTest {

    /** Counts how many times the request reached the (stub) network layer. */
    private fun clientWith(calls: AtomicInteger, stub: Interceptor): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor(sleeper = {})) // outermost: drives the retries
            .addInterceptor(Interceptor { chain -> calls.incrementAndGet(); stub.intercept(chain) })
            .build()

    private fun stubResponding(code: Int) = Interceptor { chain ->
        Response.Builder()
            .request(chain.request())
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("stub")
            .body("{}".toResponseBody("application/json".toMediaType()))
            .build()
    }

    private fun execute(client: OkHttpClient): Int =
        client.newCall(Request.Builder().url("https://example.com/").build()).execute()
            .use { it.code }

    @Test
    fun `5xx is retried once then the second response is returned`() {
        val calls = AtomicInteger(0)
        val code = execute(clientWith(calls, stubResponding(500)))
        assertEquals(2, calls.get()) // initial + one retry
        assertEquals(500, code)
    }

    @Test
    fun `429 is never retried`() {
        val calls = AtomicInteger(0)
        val code = execute(clientWith(calls, stubResponding(429)))
        assertEquals(1, calls.get())
        assertEquals(429, code)
    }

    @Test
    fun `other 4xx is never retried`() {
        val calls = AtomicInteger(0)
        val code = execute(clientWith(calls, stubResponding(403)))
        assertEquals(1, calls.get())
        assertEquals(403, code)
    }

    @Test
    fun `success is not retried`() {
        val calls = AtomicInteger(0)
        val code = execute(clientWith(calls, stubResponding(200)))
        assertEquals(1, calls.get())
        assertEquals(200, code)
    }

    @Test
    fun `connection IOException is retried once then propagates`() {
        val calls = AtomicInteger(0)
        val failing = Interceptor { throw IOException("connection reset") }
        val client = clientWith(calls, failing)

        val result = runCatching { execute(client) }
        assertTrue("IOException must propagate after the retry", result.isFailure)
        assertEquals(2, calls.get()) // initial + one retry
    }

    @Test
    fun `cancelled call performs no retry`() {
        val calls = AtomicInteger(0)
        val client = OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor(sleeper = {}))
            .addInterceptor(Interceptor { chain ->
                calls.incrementAndGet()
                chain.call().cancel()
                throw IOException("Canceled")
            })
            .build()

        runCatching { execute(client) }

        assertEquals(1, calls.get())
    }

    @Test
    fun `cancellation during backoff prevents next proceed`() {
        val calls = AtomicInteger(0)
        val activeCall = AtomicReference<okhttp3.Call>()
        val client = OkHttpClient.Builder()
            .addInterceptor(RetryInterceptor(sleeper = { activeCall.get().cancel() }))
            .addInterceptor(Interceptor { chain ->
                calls.incrementAndGet()
                stubResponding(500).intercept(chain)
            })
            .build()
        val call = client.newCall(Request.Builder().url("https://example.com/").build())
        activeCall.set(call)

        runCatching { call.execute().close() }

        assertEquals(1, calls.get())
    }
}
