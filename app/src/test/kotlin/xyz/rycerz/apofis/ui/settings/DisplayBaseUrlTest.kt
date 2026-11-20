package xyz.rycerz.apofis.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class DisplayBaseUrlTest {
    @Test
    fun showsOnlySchemeHostPortAndPath() {
        assertEquals("https://hermes.example:8642/api/", displayBaseUrl("https://user:sk-1@hermes.example:8642/api/?key=sk-2#sk-3"))
        assertEquals("http://[200:1234::1]:8642/", displayBaseUrl("http://[200:1234::1]:8642/"))
        assertEquals("", displayBaseUrl("not a url sk-1"))
    }
}
