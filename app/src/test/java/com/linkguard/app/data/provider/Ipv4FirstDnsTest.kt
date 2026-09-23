package com.linkguard.app.data.provider

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class Ipv4FirstDnsTest {

    private val v6a = InetAddress.getByName("2606:4700::6810:84e5")
    private val v6b = InetAddress.getByName("2a06:98c1:3121::3")
    private val v4a = InetAddress.getByName("172.64.153.73")
    private val v4b = InetAddress.getByName("188.114.97.0")

    @Test
    fun `IPv4 answers are tried before IPv6, keeping each family's order`() {
        val dns = Ipv4FirstDns(dnsReturning(listOf(v6a, v4a, v6b, v4b)))

        assertEquals(listOf(v4a, v4b, v6a, v6b), dns.lookup("hybrid-analysis.com"))
    }

    @Test
    fun `IPv6-only answers are kept so NAT64 and IPv6-only networks still connect`() {
        val dns = Ipv4FirstDns(dnsReturning(listOf(v6a, v6b)))

        assertEquals(listOf(v6a, v6b), dns.lookup("ipv6-only.test"))
    }

    @Test
    fun `lookup failures propagate unchanged`() {
        val dns = Ipv4FirstDns(object : Dns {
            override fun lookup(hostname: String): List<InetAddress> = throw UnknownHostException("nx")
        })

        val e = assertThrows(UnknownHostException::class.java) { dns.lookup("nx.test") }
        assertEquals("nx", e.message)
    }

    private fun dnsReturning(addresses: List<InetAddress>) = object : Dns {
        override fun lookup(hostname: String) = addresses
    }
}
