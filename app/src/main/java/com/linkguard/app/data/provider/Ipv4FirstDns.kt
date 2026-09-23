package com.linkguard.app.data.provider

import okhttp3.Dns
import java.net.Inet4Address
import java.net.InetAddress

/**
 * Orders DNS answers IPv4-first. OkHttp 4 tries addresses one at a time in DNS order with no
 * happy-eyeballs racing, so on a network with a black-holed IPv6 path (seen on a home router:
 * hybrid-analysis.com and rdap.org hung over IPv6, instant over IPv4) every provider call spent
 * its whole budget on the dead route. Nothing is dropped, so IPv6-only networks still connect.
 */
class Ipv4FirstDns(
    private val delegate: Dns = Dns.SYSTEM
) : Dns {

    override fun lookup(hostname: String): List<InetAddress> {
        val (v4, v6) = delegate.lookup(hostname).partition { it is Inet4Address }
        return v4 + v6
    }
}
