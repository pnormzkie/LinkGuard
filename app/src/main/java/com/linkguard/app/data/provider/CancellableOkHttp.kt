package com.linkguard.app.data.provider

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean

internal suspend fun OkHttpClient.executeCancellable(request: Request): Response {
    val call = newCall(request)
    return call.awaitResponse()
}

@OptIn(ExperimentalCoroutinesApi::class)
internal suspend fun Call.awaitResponse(): Response = suspendCancellableCoroutine { continuation ->
    val completed = AtomicBoolean(false)
    continuation.invokeOnCancellation {
        completed.compareAndSet(false, true)
        cancel()
    }

    val callback = object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            if (completed.compareAndSet(false, true)) {
                continuation.resumeWith(Result.failure(e))
            }
        }

        override fun onResponse(call: Call, response: Response) {
            if (!completed.compareAndSet(false, true)) {
                response.close()
                return
            }
            continuation.resume(response) { response.close() }
        }
    }

    try {
        enqueue(callback)
    } catch (e: Throwable) {
        if (completed.compareAndSet(false, true)) {
            continuation.resumeWith(Result.failure(e))
        }
    }
}
