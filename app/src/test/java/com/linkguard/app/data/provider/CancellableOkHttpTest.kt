package com.linkguard.app.data.provider

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.withTimeout
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

@OptIn(ExperimentalCoroutinesApi::class)
class CancellableOkHttpTest {

    @Test
    fun `provider timeout cancels active call at 8000 milliseconds`() = runTest {
        val call = ControlledCall()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(8_000) { call.awaitResponse() }
        }

        advanceTimeBy(7_999)
        assertFalse(call.isCanceled())
        advanceTimeBy(1)
        runCurrent()

        assertTrue(call.isCanceled())
        assertTrue(result.isCancelled)
    }

    @Test
    fun `cancellation before callback cancels active call`() = runTest {
        val call = ControlledCall()
        val result = async(start = CoroutineStart.UNDISPATCHED) { call.awaitResponse() }

        result.cancelAndJoin()

        assertTrue(call.isCanceled())
        assertTrue(result.isCancelled)
    }

    @Test
    fun `response losing cancellation race is closed`() = runTest {
        val call = ControlledCall()
        val result = async(start = CoroutineStart.UNDISPATCHED) { call.awaitResponse() }
        val response = trackedResponse(call.request())

        result.cancelAndJoin()
        call.respond(response.response)

        assertTrue(response.closed.get())
    }

    @Test
    fun `onFailure before cancellation propagates IOException`() = runTest {
        val call = ControlledCall()
        val thrown = supervisorScope {
            val result = async(start = CoroutineStart.UNDISPATCHED) { call.awaitResponse() }
            call.fail(IOException("network down"))
            runCatching { result.await() }.exceptionOrNull()
        }

        assertTrue(thrown is IOException)
    }

    @Test
    fun `cancellation induced onFailure remains cancellation`() = runTest {
        val call = ControlledCall()
        val result = async(start = CoroutineStart.UNDISPATCHED) { call.awaitResponse() }

        result.cancel()
        call.fail(IOException("Canceled"))

        assertTrue(runCatching { result.await() }.exceptionOrNull() is CancellationException)
    }

    @Test
    fun `successful response remains open for caller ownership`() = runTest {
        val call = ControlledCall()
        val result = async(start = CoroutineStart.UNDISPATCHED) { call.awaitResponse() }
        val response = trackedResponse(call.request())

        call.respond(response.response)
        assertSame(response.response, result.await())
        assertFalse(response.closed.get())

        response.response.close()
        assertTrue(response.closed.get())
    }

    @Test
    fun `only first callback completes continuation`() = runTest {
        val call = ControlledCall()
        val result = async(start = CoroutineStart.UNDISPATCHED) { call.awaitResponse() }
        val first = trackedResponse(call.request())
        val late = trackedResponse(call.request())

        call.respond(first.response)
        call.respond(late.response)

        assertSame(first.response, result.await())
        assertFalse(first.closed.get())
        assertTrue(late.closed.get())
        first.response.close()
    }

    @Test
    fun `synchronous enqueue exception propagates once`() = runTest {
        val failure = IOException("enqueue failed")
        val call = ControlledCall(enqueueFailure = failure)

        val thrown = runCatching { call.awaitResponse() }.exceptionOrNull()

        assertTrue(thrown is IOException)
    }

    private class ControlledCall(
        private val enqueueFailure: Throwable? = null,
    ) : Call {
        private val cancelled = AtomicBoolean(false)
        private val executed = AtomicBoolean(false)
        private var callback: Callback? = null
        private val request = Request.Builder().url("https://example.com/").build()

        override fun request(): Request = request
        override fun execute(): Response = error("Not used")
        override fun enqueue(responseCallback: Callback) {
            enqueueFailure?.let { throw it }
            check(executed.compareAndSet(false, true))
            callback = responseCallback
        }
        override fun cancel() { cancelled.set(true) }
        override fun isExecuted(): Boolean = executed.get()
        override fun isCanceled(): Boolean = cancelled.get()
        override fun timeout(): Timeout = Timeout.NONE
        override fun clone(): Call = ControlledCall(enqueueFailure)

        fun respond(response: Response) = callback!!.onResponse(this, response)
        fun fail(exception: IOException) = callback!!.onFailure(this, exception)
    }

    private data class TrackedResponse(
        val response: Response,
        val closed: AtomicBoolean,
    )

    private fun trackedResponse(request: Request): TrackedResponse {
        val closed = AtomicBoolean(false)
        val source = object : ForwardingSource(Buffer().writeUtf8("{}")) {
            override fun close() {
                closed.set(true)
                super.close()
            }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType(): MediaType? = null
            override fun contentLength(): Long = 2
            override fun source(): BufferedSource = source
        }
        return TrackedResponse(
            Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(200)
                .message("OK")
                .body(body)
                .build(),
            closed,
        )
    }
}
