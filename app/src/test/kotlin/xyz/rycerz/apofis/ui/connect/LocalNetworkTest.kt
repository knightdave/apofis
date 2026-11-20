package xyz.rycerz.apofis.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalNetworkTest {
    @Test
    fun asksFirstOnlyForLiteralPrivateOrLinkLocalIpv4WithoutTheGrant() {
        assertEquals(true, asksForLocalNetworkFirst("192.168.1.10", granted = false)) // private IPv4
        assertEquals(true, asksForLocalNetworkFirst("169.254.3.4", granted = false)) // link-local IPv4
        assertEquals(false, asksForLocalNetworkFirst("8.8.8.8", granted = false)) // public IPv4
        assertEquals(false, asksForLocalNetworkFirst("hermes.example", granted = false)) // host name: tried first
        assertEquals(false, asksForLocalNetworkFirst("fd12:3456::1", granted = false)) // IPv6 unique-local: tried first
        assertEquals(false, asksForLocalNetworkFirst("2001:db8::1", granted = false)) // IPv6 global: tried first
        assertEquals(false, asksForLocalNetworkFirst("10.8.0.1", granted = true)) // a VPN-style 10.x, already granted
    }

    @Test
    fun rangeEdges() {
        listOf("10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.0.1", "169.254.255.255")
            .forEach { assertEquals(it, true, asksForLocalNetworkFirst(it, granted = false)) }
        listOf("172.15.0.1", "172.32.0.1", "192.169.0.1", "169.253.0.1", "100.64.0.1", "127.0.0.1", "999.1.1.1", "hermes.local")
            .forEach { assertEquals(it, false, asksForLocalNetworkFirst(it, granted = false)) }
    }
}
