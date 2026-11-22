package xyz.rycerz.apofis.ui.drawer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The push drawer's settle and haptic rules, exactly at their thresholds. */
class PushDrawerTest {
    @Test
    fun settleRules() {
        assertEquals(PushDrawerValue.Closed, settleTo(0.49f, 0f))
        assertEquals(PushDrawerValue.Open, settleTo(0.5f, 0f))
        assertEquals(PushDrawerValue.Open, settleTo(0.2f, 400f))
        assertEquals(PushDrawerValue.Closed, settleTo(0.8f, -400f))
        assertEquals(PushDrawerValue.Open, settleTo(0.8f, 300f))
        assertEquals(PushDrawerValue.Closed, settleTo(0.3f, 365f))
        assertEquals(PushDrawerValue.Open, settleTo(0.3f, 366f))
        assertEquals(PushDrawerValue.Open, settleTo(0.7f, -365f))
        assertEquals(PushDrawerValue.Closed, settleTo(0.7f, -366f))
    }

    @Test
    fun edgeRules() {
        assertTrue(crossedEdge(0.9f, 0.96f))
        assertFalse(crossedEdge(0.96f, 0.99f))
        assertTrue(crossedEdge(0.1f, 0.04f))
        assertFalse(crossedEdge(0.04f, 0.01f))
        assertTrue(crossedEdge(0.94f, 0.95f))
        assertTrue(crossedEdge(0.06f, 0.05f))
        assertTrue(crossedEdge(0f, 1f))
        assertTrue(crossedEdge(1f, 0f))
    }
}
