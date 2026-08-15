package com.linkguard.app.data.provider

import com.linkguard.app.domain.model.RedirectOutcome
import com.linkguard.app.util.AppConfig
import kotlinx.coroutines.runBlocking
import okhttp3.Call
import okhttp3.Dns
import okhttp3.EventListener
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.util.concurrent.atomic.AtomicInteger

class HttpRedirectResolverTest {

    /**
     * A client whose responses are keyed by request URL: each entry is (status, Location?).
     * Unlisted URLs return [default]. When [rejectHead] is set, HEAD requests return 405 so the
     * resolver's GET fallback can be exercised.
     */
    private fun routingClient(
        routes: Map<String, Pair<Int, String?>>,
        default: Pair<Int, String?> = 200 to null,
        rejectHead: Boolean = false
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val req = chain.request()
            if (rejectHead && req.method == "HEAD") return@Interceptor stub(req, 405, null)
            val (code, location) = routes[req.url.toString()] ?: default
            stub(req, code, location)
        })
        .build()

    private fun stub(req: Request, code: Int, location: String?): Response {
        val builder = Response.Builder()
            .request(req)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("stub")
            .body("".toResponseBody("text/plain".toMediaType()))
        if (location != null) builder.header("Location", location)
        return builder.build()
    }

    @Test
    fun `no redirect returns the url unchanged`() = runBlocking {
        val resolver = HttpRedirectResolver(routingClient(emptyMap()))
        val res = resolver.resolve("https://example.com/page")
        assertEquals(RedirectOutcome.NO_REDIRECT, res.outcome)
        assertEquals("https://example.com/page", res.finalUrl)
        assertFalse(res.redirected)
    }

    @Test
    fun `single redirect resolves to the destination`() = runBlocking {
        val resolver = HttpRedirectResolver(
            routingClient(mapOf("https://sho.rt/a" to (301 to "https://evil.example/landing")))
        )
        val res = resolver.resolve("https://sho.rt/a")
        assertEquals(RedirectOutcome.RESOLVED, res.outcome)
        assertEquals("https://evil.example/landing", res.finalUrl)
        assertTrue(res.redirected)
        assertTrue(res.crossedDomains)
    }

    @Test
    fun `multi-hop chain is followed to the end`() = runBlocking {
        val resolver = HttpRedirectResolver(
            routingClient(
                mapOf(
                    "https://a.test/1" to (302 to "https://b.test/2"),
                    "https://b.test/2" to (302 to "https://c.test/3")
                )
            )
        )
        val res = resolver.resolve("https://a.test/1")
        assertEquals(RedirectOutcome.RESOLVED, res.outcome)
        assertEquals("https://c.test/3", res.finalUrl)
        assertEquals(3, res.hops.size)
    }

    @Test
    fun `redirect loop is detected`() = runBlocking {
        val resolver = HttpRedirectResolver(
            routingClient(
                mapOf(
                    "https://a.test/x" to (302 to "https://b.test/y"),
                    "https://b.test/y" to (302 to "https://a.test/x")
                )
            )
        )
        val res = resolver.resolve("https://a.test/x")
        assertEquals(RedirectOutcome.LOOP, res.outcome)
    }

    @Test
    fun `chain longer than the hop limit stops at max hops`() = runBlocking {
        val resolver = HttpRedirectResolver(
            routingClient(
                mapOf(
                    "https://a.test/1" to (302 to "https://b.test/2"),
                    "https://b.test/2" to (302 to "https://c.test/3"),
                    "https://c.test/3" to (302 to "https://d.test/4")
                )
            ),
            maxHops = 2
        )
        val res = resolver.resolve("https://a.test/1")
        assertEquals(RedirectOutcome.MAX_HOPS, res.outcome)
    }

    @Test
    fun `redirect to a non-http scheme is blocked`() = runBlocking {
        val resolver = HttpRedirectResolver(
            routingClient(mapOf("https://a.test/x" to (302 to "javascript:alert(1)")))
        )
        val res = resolver.resolve("https://a.test/x")
        assertEquals(RedirectOutcome.BLOCKED_SCHEME, res.outcome)
    }

    @Test
    fun `redirect to a private host is blocked`() = runBlocking {
        val resolver = HttpRedirectResolver(
            routingClient(mapOf("https://a.test/x" to (302 to "http://192.168.0.1/admin")))
        )
        val res = resolver.resolve("https://a.test/x")
        assertEquals(RedirectOutcome.BLOCKED_PRIVATE_HOST, res.outcome)
    }

    @Test
    fun `head rejection falls back to get`() = runBlocking {
        val resolver = HttpRedirectResolver(
            routingClient(
                mapOf("https://a.test/1" to (301 to "https://b.test/2")),
                rejectHead = true
            )
        )
        val res = resolver.resolve("https://a.test/1")
        assertEquals(RedirectOutcome.RESOLVED, res.outcome)
        assertEquals("https://b.test/2", res.finalUrl)
    }

    @Test
    fun `total time budget yields a timeout`() = runBlocking {
        val clock = ArrayDeque(listOf(0L, AppConfig.REDIRECT_TOTAL_BUDGET_MS + 1))
        val resolver = HttpRedirectResolver(
            routingClient(mapOf("https://a.test/x" to (302 to "https://b.test/y"))),
            now = { clock.removeFirst() }
        )
        val res = resolver.resolve("https://a.test/x")
        assertEquals(RedirectOutcome.TIMEOUT, res.outcome)
        assertEquals("https://a.test/x", res.finalUrl)
    }

    @Test
    fun `same-domain redirect does not count as cross-domain`() = runBlocking {
        val resolver = HttpRedirectResolver(
            routingClient(mapOf("https://site.test/a" to (302 to "/b")))
        )
        val res = resolver.resolve("https://site.test/a")
        assertEquals(RedirectOutcome.RESOLVED, res.outcome)
        assertEquals("https://site.test/b", res.finalUrl)
        assertFalse(res.crossedDomains)
    }

    @Test
    fun `network error returns best-known url without throwing`() = runBlocking {
        val resolver = HttpRedirectResolver(clientFailing())
        val res = resolver.resolve("https://a.test/x")
        assertEquals(RedirectOutcome.ERROR, res.outcome)
        assertEquals("https://a.test/x", res.finalUrl)
    }

    @Test
    fun `unsafe DNS answer is blocked before socket connection or request transport`() = runBlocking {
        val connectStarts = AtomicInteger()
        val requestStarts = AtomicInteger()
        val unsafeDns = HostSafetyValidator(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                listOf(InetAddress.getByName("127.0.0.1"))
        })
        val client = OkHttpClient.Builder()
            .dns(unsafeDns)
            .eventListener(object : EventListener() {
                override fun connectStart(
                    call: Call,
                    inetSocketAddress: InetSocketAddress,
                    proxy: Proxy
                ) {
                    connectStarts.incrementAndGet()
                }

                override fun requestHeadersStart(call: Call) {
                    requestStarts.incrementAndGet()
                }
            })
            .build()

        val result = HttpRedirectResolver(client).resolve("http://public-looking.test/path")

        assertEquals(RedirectOutcome.ERROR, result.outcome)
        assertEquals(0, connectStarts.get())
        assertEquals(0, requestStarts.get())
    }
}
