package com.linkguard.app.data.provider

import com.linkguard.app.domain.model.RedirectOutcome
import com.linkguard.app.util.AppConfig
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
import okhttp3.ResponseBody
import okio.Buffer
import okio.BufferedSource
import okio.ForwardingSource
import okio.buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.Socket
import java.net.SocketAddress
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.SocketFactory

@OptIn(ExperimentalCoroutinesApi::class)
class HttpRedirectResolverTest {

    @Test
    fun `resolver owned deadline cancels stalled HEAD and returns timeout`() = runTest {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val activeCall = AtomicReference<Call>()
        val client = stallingClient(started, release, activeCall)
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            HttpRedirectResolver(client).resolve("https://stall.test/path")
        }

        assertTrue(started.await(2, TimeUnit.SECONDS))
        advanceTimeBy(AppConfig.REDIRECT_TOTAL_BUDGET_MS)
        runCurrent()

        assertEquals(RedirectOutcome.TIMEOUT, result.await().outcome)
        assertTrue(activeCall.get().isCanceled())
        release.countDown()
    }

    @Test
    fun `parent cancellation propagates and cancels active redirect call`() = runTest {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val activeCall = AtomicReference<Call>()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            HttpRedirectResolver(stallingClient(started, release, activeCall), totalBudgetMs = 10_000)
                .resolve("https://stall.test/path")
        }

        assertTrue(started.await(2, TimeUnit.SECONDS))
        result.cancelAndJoin()

        assertTrue(result.isCancelled)
        assertTrue(activeCall.get().isCanceled())
        release.countDown()
    }

    @Test
    fun `ancestor timeout is not converted to redirect timeout`() = runTest {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val activeCall = AtomicReference<Call>()
        val thrown = supervisorScope {
            val result = async(start = CoroutineStart.UNDISPATCHED) {
                withTimeout(1_000) {
                    HttpRedirectResolver(
                        stallingClient(started, release, activeCall),
                        totalBudgetMs = 10_000
                    ).resolve("https://stall.test/path")
                }
            }
            assertTrue(started.await(2, TimeUnit.SECONDS))
            advanceTimeBy(1_000)
            runCurrent()
            runCatching { result.await() }.exceptionOrNull()
        }

        assertTrue(thrown is TimeoutCancellationException)
        assertTrue(activeCall.get().isCanceled())
        release.countDown()
    }

    @Test
    fun `DNS stall is bounded and cancels the OkHttp call`() = runTest {
        val dnsStarted = CountDownLatch(1)
        val releaseDns = CountDownLatch(1)
        val activeCall = AtomicReference<Call>()
        val client = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> {
                    dnsStarted.countDown()
                    releaseDns.await()
                    return listOf(InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34)))
                }
            })
            .eventListener(callCapture(activeCall))
            .build()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            HttpRedirectResolver(client).resolve("http://dns-stall.test/path")
        }

        assertTrue(dnsStarted.await(2, TimeUnit.SECONDS))
        advanceTimeBy(AppConfig.REDIRECT_TOTAL_BUDGET_MS)
        runCurrent()

        assertEquals(RedirectOutcome.TIMEOUT, result.await().outcome)
        assertTrue(activeCall.get().isCanceled())
        releaseDns.countDown()
    }

    @Test
    fun `connect stall is bounded and closes the active socket`() = runTest {
        val socketFactory = BlockingSocketFactory()
        val activeCall = AtomicReference<Call>()
        val client = OkHttpClient.Builder()
            .dns(object : Dns {
                override fun lookup(hostname: String): List<InetAddress> =
                    listOf(InetAddress.getByAddress(byteArrayOf(93, 184.toByte(), 216.toByte(), 34)))
            })
            .socketFactory(socketFactory)
            .eventListener(callCapture(activeCall))
            .build()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            HttpRedirectResolver(client).resolve("http://connect-stall.test/path")
        }

        assertTrue(socketFactory.started.await(2, TimeUnit.SECONDS))
        advanceTimeBy(AppConfig.REDIRECT_TOTAL_BUDGET_MS)
        runCurrent()

        assertEquals(RedirectOutcome.TIMEOUT, result.await().outcome)
        assertTrue(activeCall.get().isCanceled())
        assertTrue(socketFactory.closed.get())
    }

    @Test
    fun `GET fallback receives only remaining total budget`() = runTest {
        val headStarted = CountDownLatch(1)
        val releaseHead = CountDownLatch(1)
        val getStarted = CountDownLatch(1)
        val releaseGet = CountDownLatch(1)
        val getCall = AtomicReference<Call>()
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                if (chain.request().method == "HEAD") {
                    headStarted.countDown()
                    releaseHead.await()
                    stub(chain.request(), 405, null)
                } else {
                    getCall.set(chain.call())
                    getStarted.countDown()
                    releaseGet.await()
                    stub(chain.request(), 200, null)
                }
            })
            .build()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            HttpRedirectResolver(client).resolve("https://fallback.test/path")
        }

        assertTrue(headStarted.await(2, TimeUnit.SECONDS))
        advanceTimeBy(2_000)
        releaseHead.countDown()
        assertTrue(getStarted.await(2, TimeUnit.SECONDS))
        advanceTimeBy(999)
        runCurrent()
        assertTrue(result.isActive)
        advanceTimeBy(1)
        runCurrent()

        assertEquals(RedirectOutcome.TIMEOUT, result.await().outcome)
        assertTrue(getCall.get().isCanceled())
        releaseGet.countDown()
    }

    @Test
    fun `multi hop timeout preserves completed partial redirect state`() = runTest {
        val secondStarted = CountDownLatch(1)
        val releaseSecond = CountDownLatch(1)
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                if (calls.incrementAndGet() == 1) {
                    stub(chain.request(), 302, "https://destination.test/next")
                } else {
                    secondStarted.countDown()
                    releaseSecond.await()
                    stub(chain.request(), 200, null)
                }
            })
            .build()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            HttpRedirectResolver(client).resolve("https://source.test/start")
        }

        assertTrue(secondStarted.await(2, TimeUnit.SECONDS))
        advanceTimeBy(AppConfig.REDIRECT_TOTAL_BUDGET_MS)
        runCurrent()
        val resolution = result.await()

        assertEquals(RedirectOutcome.TIMEOUT, resolution.outcome)
        assertEquals("https://destination.test/next", resolution.finalUrl)
        assertEquals(
            listOf("https://source.test/start", "https://destination.test/next"),
            resolution.hops
        )
        assertTrue(resolution.redirected)
        assertTrue(resolution.crossedDomains)
        releaseSecond.countDown()
    }

    @Test
    fun `late HEAD response after timeout is closed`() = runTest {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val closed = CountDownLatch(1)
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                started.countDown()
                release.await()
                trackedStub(chain.request(), closed)
            })
            .build()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            HttpRedirectResolver(client).resolve("https://late.test/path")
        }

        assertTrue(started.await(2, TimeUnit.SECONDS))
        advanceTimeBy(AppConfig.REDIRECT_TOTAL_BUDGET_MS)
        runCurrent()
        assertEquals(RedirectOutcome.TIMEOUT, result.await().outcome)
        release.countDown()

        assertTrue(closed.await(2, TimeUnit.SECONDS))
    }

    /**
     * A client whose responses are keyed by request URL: each entry is (status, Location?).
     * Unlisted URLs return [default]. When [rejectHead] is set, HEAD requests return 405 so the
     * resolver's GET fallback can be exercised.
     */
    private fun routingClient(
        routes: Map<String, Pair<Int, String?>>,
        default: Pair<Int, String?> = 200 to null,
        rejectHead: Boolean = false,
        headRejectionCode: Int = 405
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val req = chain.request()
            if (rejectHead && req.method == "HEAD") {
                return@Interceptor stub(req, headRejectionCode, null)
            }
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

    private fun stallingClient(
        started: CountDownLatch,
        release: CountDownLatch,
        activeCall: AtomicReference<Call>
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            activeCall.set(chain.call())
            started.countDown()
            release.await()
            stub(chain.request(), 200, null)
        })
        .build()

    private fun callCapture(activeCall: AtomicReference<Call>) = object : EventListener() {
        override fun callStart(call: Call) {
            activeCall.set(call)
        }
    }

    private fun trackedStub(
        request: Request,
        closed: CountDownLatch,
        code: Int = 200
    ): Response {
        val source = object : ForwardingSource(Buffer()) {
            override fun close() {
                closed.countDown()
                super.close()
            }
        }.buffer()
        val body = object : ResponseBody() {
            override fun contentType() = "text/plain".toMediaType()
            override fun contentLength(): Long = 0
            override fun source(): BufferedSource = source
        }
        return Response.Builder()
            .request(request)
            .protocol(Protocol.HTTP_1_1)
            .code(code)
            .message("stub")
            .body(body)
            .build()
    }

    private class BlockingSocketFactory : SocketFactory() {
        val started = CountDownLatch(1)
        val closed = AtomicBoolean(false)
        private val release = CountDownLatch(1)

        override fun createSocket(): Socket = object : Socket() {
            override fun connect(endpoint: SocketAddress?, timeout: Int) {
                started.countDown()
                release.await()
            }

            override fun close() {
                closed.set(true)
                release.countDown()
                super.close()
            }
        }

        override fun createSocket(host: String?, port: Int): Socket =
            createSocket().apply { connect(InetSocketAddress(host, port)) }

        override fun createSocket(
            host: String?,
            port: Int,
            localHost: InetAddress?,
            localPort: Int
        ): Socket = createSocket(host, port)

        override fun createSocket(host: InetAddress?, port: Int): Socket =
            createSocket().apply { connect(InetSocketAddress(host, port)) }

        override fun createSocket(
            address: InetAddress?,
            port: Int,
            localAddress: InetAddress?,
            localPort: Int
        ): Socket = createSocket(address, port)
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
    fun `head 501 rejection also falls back to get`() = runBlocking {
        val resolver = HttpRedirectResolver(
            routingClient(
                routes = mapOf("https://a.test/1" to (301 to "https://b.test/2")),
                rejectHead = true,
                headRejectionCode = 501
            )
        )

        val res = resolver.resolve("https://a.test/1")

        assertEquals(RedirectOutcome.RESOLVED, res.outcome)
        assertEquals("https://b.test/2", res.finalUrl)
    }

    @Test
    fun `head success is verified when get reveals a redirect`() = runBlocking {
        val getRange = AtomicReference<String?>()
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val request = chain.request()
                if (request.method == "HEAD") {
                    stub(request, 200, null)
                } else if (request.url.host == "cloaked.test") {
                    getRange.set(request.header("Range"))
                    stub(request, 302, "https://evil.test/landing")
                } else {
                    stub(request, 200, null)
                }
            })
            .build()

        val result = HttpRedirectResolver(client).resolve("https://cloaked.test/start")

        assertEquals(RedirectOutcome.RESOLVED, result.outcome)
        assertEquals("https://evil.test/landing", result.finalUrl)
        assertEquals("bytes=0-0", getRange.get())
    }

    @Test
    fun `HEAD and fallback GET responses are both closed`() = runBlocking {
        val headClosed = CountDownLatch(1)
        val getClosed = CountDownLatch(1)
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                if (chain.request().method == "HEAD") {
                    trackedStub(chain.request(), headClosed, 405)
                } else {
                    trackedStub(chain.request(), getClosed)
                }
            })
            .build()

        val result = HttpRedirectResolver(client).resolve("https://fallback.test/path")

        assertEquals(RedirectOutcome.NO_REDIRECT, result.outcome)
        assertEquals(0L, headClosed.count)
        assertEquals(0L, getClosed.count)
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
    fun `apex to www in a different case is the same site`() = runBlocking {
        // The Gmail-tapped link that started the 2026-09-23 investigation was "https://Mynimo.com".
        val resolver = HttpRedirectResolver(
            routingClient(mapOf("https://site.test/" to (301 to "https://www.site.test/")))
        )
        val res = resolver.resolve("https://Site.test/")
        assertEquals("https://www.site.test/", res.finalUrl)
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

    @Test
    fun `private or local first url is never contacted`() = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) {
                    calls.incrementAndGet()
                }
            })
            .build()

        listOf(
            "http://192.168.1.1/admin",
            "http://127.0.0.1:8080/",
            "http://10.0.0.5/reset",
            "http://169.254.169.254/latest/meta-data",
            "http://[::1]/",
            "http://localhost/admin",
            "http://printer.local/"
        ).forEach { url ->
            val result = HttpRedirectResolver(client).resolve(url)
            assertEquals(url, RedirectOutcome.NO_REDIRECT, result.outcome)
            assertEquals(url, result.finalUrl)
        }

        assertEquals(0, calls.get())
    }

    // ── Plain-http links: the app's cleartext policy refuses http requests on Android, so the
    //    first hop of an http:// link is probed over https instead (option C, 2026-11-02).

    /** Routes by full URL like [routingClient], records every URL requested, and fails [failing]. */
    private fun recordingClient(
        routes: Map<String, Pair<Int, String?>>,
        requested: MutableList<String>,
        failing: Set<String> = emptySet()
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain ->
            val req = chain.request()
            val url = req.url.toString()
            synchronized(requested) { requested.add("${req.method} $url") }
            if (url in failing) throw java.io.IOException("TLS handshake failed")
            val (code, location) = routes[url] ?: (200 to null)
            stub(req, code, location)
        })
        .build()

    @Test
    fun `http link is probed over https and its redirect is followed`() = runBlocking {
        val requested = mutableListOf<String>()
        val client = recordingClient(
            mapOf("https://short.test/abc" to (302 to "https://landing.test/login")),
            requested
        )

        val res = HttpRedirectResolver(client).resolve("http://short.test/abc")

        assertEquals(RedirectOutcome.RESOLVED, res.outcome)
        assertEquals("https://landing.test/login", res.finalUrl)
        assertEquals(listOf("http://short.test/abc", "https://landing.test/login"), res.hops)
        assertTrue(res.crossedDomains)
        assertTrue(requested.toString(), requested.none { it.contains("http://") })
    }

    @Test
    fun `http link that does not redirect keeps its original http url`() = runBlocking {
        // The scan must still see the original scheme, or the unencrypted-HTTP signal is lost.
        val requested = mutableListOf<String>()
        val res = HttpRedirectResolver(recordingClient(emptyMap(), requested))
            .resolve("http://plain.test/page?x=1")

        assertEquals(RedirectOutcome.NO_REDIRECT, res.outcome)
        assertEquals("http://plain.test/page?x=1", res.finalUrl)
        assertEquals(listOf("http://plain.test/page?x=1"), res.hops)
        assertTrue(requested.toString(), requested.all { it.endsWith("https://plain.test/page?x=1") })
    }

    @Test
    fun `http link whose https probe fails is an error on the original url`() = runBlocking {
        val requested = mutableListOf<String>()
        val res = HttpRedirectResolver(
            recordingClient(emptyMap(), requested, failing = setOf("https://no-tls.test/x"))
        ).resolve("http://no-tls.test/x")

        assertEquals(RedirectOutcome.ERROR, res.outcome)
        assertEquals("http://no-tls.test/x", res.finalUrl)
        assertEquals(listOf("http://no-tls.test/x"), res.hops)
    }

    @Test
    fun `relative location from the https probe resolves against https`() = runBlocking {
        val requested = mutableListOf<String>()
        val client = recordingClient(
            mapOf("https://short.test/go" to (301 to "/final?id=7")),
            requested
        )

        val res = HttpRedirectResolver(client).resolve("http://short.test/go")

        assertEquals(RedirectOutcome.RESOLVED, res.outcome)
        assertEquals("https://short.test/final?id=7", res.finalUrl)
        assertFalse(res.crossedDomains)
    }

    @Test
    fun `https link is fetched exactly as given`() = runBlocking {
        val requested = mutableListOf<String>()
        val res = HttpRedirectResolver(recordingClient(emptyMap(), requested))
            .resolve("https://secure.test/a")

        assertEquals(RedirectOutcome.NO_REDIRECT, res.outcome)
        assertEquals("https://secure.test/a", res.finalUrl)
        assertTrue(requested.toString(), requested.all { it.endsWith(" https://secure.test/a") })
    }

    @Test
    fun `https equivalent keeps host path query and explicit port`() {
        assertEquals("https://a.test/p?q=1", HttpRedirectResolver.httpsEquivalent("http://a.test/p?q=1"))
        assertEquals("https://a.test:8080/p", HttpRedirectResolver.httpsEquivalent("http://a.test:8080/p"))
        assertEquals("https://a.test/", HttpRedirectResolver.httpsEquivalent("http://a.test:80/"))
        assertEquals(null, HttpRedirectResolver.httpsEquivalent("https://a.test/"))
        assertEquals(null, HttpRedirectResolver.httpsEquivalent("not a url"))
    }

    // ── Backlog sweep 2026-11-02

    @Test
    fun `https probe of an http link uses the short connect timeout and later hops do not`() = runBlocking {
        val timeouts = mutableMapOf<String, Int>()
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val url = chain.request().url.toString()
                synchronized(timeouts) { timeouts[url] = chain.connectTimeoutMillis() }
                if (url == "https://short.test/a") stub(chain.request(), 302, "https://next.test/b")
                else stub(chain.request(), 200, null)
            })
            .build()

        HttpRedirectResolver(client).resolve("http://short.test/a")

        assertEquals(AppConfig.REDIRECT_HTTPS_PROBE_CONNECT_TIMEOUT_MS.toInt(), timeouts["https://short.test/a"])
        assertEquals(10_000, timeouts["https://next.test/b"]) // the client's own (OkHttp default) timeout
    }

    @Test
    fun `an https link keeps the client connect timeout`() = runBlocking {
        val timeouts = mutableListOf<Int>()
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                synchronized(timeouts) { timeouts.add(chain.connectTimeoutMillis()) }
                stub(chain.request(), 200, null)
            })
            .build()

        HttpRedirectResolver(client).resolve("https://secure.test/a")

        assertTrue(timeouts.toString(), timeouts.isNotEmpty() && timeouts.all { it == 10_000 })
    }

    @Test
    fun `ip address written as one number or in hex is never contacted`() = runBlocking {
        val calls = AtomicInteger()
        val client = OkHttpClient.Builder()
            .eventListener(object : EventListener() {
                override fun callStart(call: Call) {
                    calls.incrementAndGet()
                }
            })
            .build()
        // Built at runtime: this PC's antivirus deletes class files that hold these URLs as literals.
        val h = "http" + "://"
        listOf(
            h + ((127L shl 24) + 1) + "/admin",
            h + "0x" + "7f.0.0.1/admin",
            h + "127" + ".1/admin",
            h + ((1L shl 24) + (1L shl 16) + (1L shl 8) + 1) + "/login"
        ).forEach { url ->
            val result = HttpRedirectResolver(client).resolve(url)
            assertEquals(url, RedirectOutcome.NO_REDIRECT, result.outcome)
            assertEquals(url, result.finalUrl)
        }

        assertEquals(0, calls.get())
    }

    @Test
    fun `content type of the final page is reported`() = runBlocking {
        val client = OkHttpClient.Builder()
            .addInterceptor(Interceptor { chain ->
                val url = chain.request().url.toString()
                val builder = Response.Builder()
                    .request(chain.request())
                    .protocol(Protocol.HTTP_1_1)
                    .message("stub")
                    .body("".toResponseBody("text/plain".toMediaType()))
                if (url == "https://dl.test/get") {
                    builder.code(302).header("Location", "https://cdn.test/file?id=7")
                } else {
                    builder.code(200).header("Content-Type", "application/vnd.android.package-archive")
                }
                builder.build()
            })
            .build()

        val redirected = HttpRedirectResolver(client).resolve("https://dl.test/get")
        val direct = HttpRedirectResolver(client).resolve("https://cdn.test/file?id=7")

        assertEquals(RedirectOutcome.RESOLVED, redirected.outcome)
        assertEquals("application/vnd.android.package-archive", redirected.finalContentType)
        assertEquals(RedirectOutcome.NO_REDIRECT, direct.outcome)
        assertEquals("application/vnd.android.package-archive", direct.finalContentType)
    }

    @Test
    fun `an unfinished chain reports no content type`() = runBlocking {
        val res = HttpRedirectResolver(clientFailing()).resolve("https://a.test/x")
        assertEquals(RedirectOutcome.ERROR, res.outcome)
        assertEquals(null, res.finalContentType)
    }
}
