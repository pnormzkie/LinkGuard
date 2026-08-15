package com.linkguard.app.data.provider

import okhttp3.Interceptor
import okhttp3.Dns
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.net.InetAddress

private val PUBLIC_TEST_ADDRESS: InetAddress =
    InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34))
private val PUBLIC_TEST_DNS = object : Dns {
    override fun lookup(hostname: String): List<InetAddress> = listOf(PUBLIC_TEST_ADDRESS)
}

/**
 * Test seam for the providers' injected [OkHttpClient]: an application interceptor
 * short-circuits every call with a canned response (or failure) before any DNS or
 * socket work happens, so provider tests are deterministic and never touch the network.
 */
internal fun clientReturning(code: Int, body: String): OkHttpClient =
    OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("stub")
                .body(body.toResponseBody("application/json".toMediaType()))
                .build()
        })
        .build()

/** Like [clientReturning] but tags the body as text/html (for page-content inspection). */
internal fun clientReturningHtml(code: Int, body: String, contentType: String = "text/html"): OkHttpClient =
    OkHttpClient.Builder()
        .dns(PUBLIC_TEST_DNS)
        .addInterceptor(Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("stub")
                .header("Content-Type", contentType)
                .body(body.toResponseBody(contentType.toMediaType()))
                .build()
        })
        .build()

/** Like [clientReturning] but with a raw byte body (e.g. DNS wireformat). */
internal fun clientReturningBytes(code: Int, body: ByteArray): OkHttpClient =
    OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            Response.Builder()
                .request(chain.request())
                .protocol(Protocol.HTTP_1_1)
                .code(code)
                .message("stub")
                .body(body.toResponseBody("application/dns-message".toMediaType()))
                .build()
        })
        .build()

/** A client whose every call fails as if the network were down. */
internal fun clientFailing(message: String = "network down"): OkHttpClient =
    OkHttpClient.Builder()
        .addInterceptor(Interceptor { throw IOException(message) })
        .build()
