package com.linkguard.app.data.provider

import okhttp3.Interceptor
import okhttp3.Response
import java.io.IOException

/**
 * Retries a request at most once on TRANSIENT failures only: a connection-level [IOException]
 * or an HTTP 5xx response.
 *
 * It deliberately NEVER retries a 4xx — especially 429 (quota): retrying a quota rejection
 * would burn the very budget the daily cap protects, and other 4xx are permanent client errors
 * that a retry can't fix. OkHttp's own `retryOnConnectionFailure` (on by default) already covers
 * some socket-level retries; this adds one application-level 5xx retry with a short jittered
 * backoff. The sleeper is injected so tests don't actually wait.
 */
class RetryInterceptor(
    private val maxRetries: Int = 1,
    private val baseBackoffMs: Long = 300,
    private val sleeper: (Long) -> Unit = { Thread.sleep(it) },
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        var attempt = 0
        while (true) {
            throwIfCancelled(chain)
            if (attempt > 0) {
                throwIfCancelled(chain)
                sleeper(backoffFor(attempt))
                throwIfCancelled(chain)
            }
            throwIfCancelled(chain)
            try {
                val response = chain.proceed(request)
                if (response.code in 500..599 && attempt < maxRetries) {
                    if (chain.call().isCanceled()) {
                        response.close()
                        throw IOException("Canceled")
                    }
                    response.close() // must release the body before re-issuing
                    attempt++
                    continue
                }
                return response
            } catch (e: IOException) {
                if (chain.call().isCanceled()) throw e
                if (attempt >= maxRetries) throw e
                attempt++
            }
        }
    }

    private fun backoffFor(attempt: Int): Long =
        baseBackoffMs * attempt + (Math.random() * baseBackoffMs).toLong()

    private fun throwIfCancelled(chain: Interceptor.Chain) {
        if (chain.call().isCanceled()) throw IOException("Canceled")
    }
}
