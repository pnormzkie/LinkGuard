package com.linkguard.app.data.provider

import okhttp3.Dns
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class HostSafetyValidatorTest {

    @Test
    fun `hostname resolving to loopback IPv4 is blocked`() = assertBlocked("127.0.0.1")

    @Test
    fun `hostname resolving to RFC1918 IPv4 is blocked`() = assertBlocked("10.20.30.40")

    @Test
    fun `hostname resolving to link-local IPv4 is blocked`() = assertBlocked("169.254.10.20")

    @Test
    fun `hostname resolving to IPv6 loopback is blocked`() = assertBlocked("::1")

    @Test
    fun `hostname resolving to IPv6 unique-local is blocked`() = assertBlocked("fd00::1234")

    @Test
    fun `hostname resolving only to a public IP is allowed`() {
        val publicAddress = InetAddress.getByName("93.184.216.34")
        val validator = HostSafetyValidator(dnsReturning { listOf(publicAddress) })

        assertEquals(listOf(publicAddress), validator.lookup("public-looking.test"))
    }

    @Test
    fun `mixed public and private DNS answers are blocked`() {
        val publicAddress = InetAddress.getByName("93.184.216.34")
        val privateAddress = InetAddress.getByName("192.168.1.10")
        val validator = HostSafetyValidator(dnsReturning { listOf(publicAddress, privateAddress) })

        assertThrows(UnknownHostException::class.java) {
            validator.lookup("mixed-answer.test")
        }
    }

    @Test
    fun `empty DNS resolution is blocked fail closed`() {
        val validator = HostSafetyValidator(dnsReturning { emptyList() })

        assertThrows(UnknownHostException::class.java) {
            validator.lookup("empty-answer.test")
        }
    }

    @Test
    fun `public IPv6 destination is allowed`() {
        val publicAddress = InetAddress.getByName("2606:4700:4700::1111")
        val validator = HostSafetyValidator(dnsReturning { listOf(publicAddress) })

        assertEquals(listOf(publicAddress), validator.lookup("public-v6.test"))
    }

    @Test
    fun `DNS failure is blocked fail closed`() {
        val validator = HostSafetyValidator(dnsReturning { throw UnknownHostException("unavailable") })

        assertThrows(UnknownHostException::class.java) {
            validator.lookup("unvalidated.test")
        }
    }

    private fun assertBlocked(address: String) {
        val validator = HostSafetyValidator(dnsReturning { listOf(InetAddress.getByName(address)) })

        assertThrows(UnknownHostException::class.java) {
            validator.lookup("public-looking.test")
        }
    }

    private fun dnsReturning(block: () -> List<InetAddress>) = object : Dns {
        override fun lookup(hostname: String): List<InetAddress> = block()
    }
}
