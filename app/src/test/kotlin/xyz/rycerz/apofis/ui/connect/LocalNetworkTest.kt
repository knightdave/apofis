package xyz.rycerz.apofis.ui.connect

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalNetworkTest {
    @Test
    fun literalHosts() {
        val local = listOf(
            "10.0.2.2", "10.255.255.255", "172.16.0.1", "172.31.255.254", "192.168.1.10", "169.254.3.4",
            "100.64.0.1", "100.127.255.254", "224.0.0.251", "255.255.255.255",
            "fe80::1", "fd12:3456::1", "fc00::1", "ff02::fb", "::ffff:192.168.1.1",
        )
        val notLocal = listOf(
            "127.0.0.1", "8.8.8.8", "172.32.0.1", "172.15.255.255", "100.63.255.255", "100.128.0.1", "11.0.0.1",
            "::1", "2001:db8::1", "fbff::1",
        )
        assertEquals(emptyList<String>(), local.filterNot(::hostNeedsLocalNetwork))
        assertEquals(emptyList<String>(), notLocal.filter(::hostNeedsLocalNetwork))
    }

    @Test
    fun hostNames() {
        assertEquals(true, hostNeedsLocalNetwork("hermes.local"))
        assertEquals(true, hostNeedsLocalNetwork("Hermes.LOCAL"))
        // Other names are decided after a failed connection, by resolving them: never by DNS up front.
        assertEquals(false, hostNeedsLocalNetwork("hermes.example"))
        assertEquals(false, hostNeedsLocalNetwork("localhost"))
    }
}
