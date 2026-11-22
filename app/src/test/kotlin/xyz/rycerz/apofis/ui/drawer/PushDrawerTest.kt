package xyz.rycerz.apofis.ui.drawer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest

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

    /**
     * A drag held exactly at the open end (target still Closed) whose end could not settle (the drawer's scope was gone):
     * the idle rule settles the endpoint too, with its target and the opening callback.
     */
    @Test
    fun idleSettlesAnEndpointWhoseDragEndCouldNotSettle() = runTest {
        var openings = 0
        val state = PushDrawerState(PushDrawerValue.Closed).apply {
            width = 100f
            reduceMotion = true
            onOpening = { openings++ }
        }
        val owner = DragOwner(fromScrim = false)
        assertTrue(state.startDrag(owner))
        state.dragBy(owner, 150f)
        assertEquals(1f, state.fraction)
        assertFalse(state.isOpen)
        state.endDrag(owner, 0f, lost = false) // no scope: nothing settles, the owner lets go
        assertEquals(null, state.owner)
        assertFalse(state.isOpen)
        state.scope = this
        state.idle()
        advanceUntilIdle()
        assertTrue(state.isOpen)
        assertEquals(1, openings)
    }

    /** Only a drag's owner ends it: a superseded owner's late end moves nothing and clears nothing. */
    @Test
    fun aStaleOwnerEndsNothing() = runTest {
        val state = PushDrawerState(PushDrawerValue.Closed).apply {
            width = 100f
            reduceMotion = true
            scope = this@runTest
        }
        val first = DragOwner(fromScrim = true)
        val second = DragOwner(fromScrim = true)
        state.startDrag(first)
        state.dragBy(first, 30f)
        state.startDrag(second)
        state.endDrag(first, 1000f, lost = false) // the first one's late release
        advanceUntilIdle()
        assertTrue(state.owner === second)
        assertEquals(0.3f, state.fraction, 0.0001f)
        state.dragBy(first, 30f) // and its late moves
        assertEquals(0.3f, state.fraction, 0.0001f)
    }
}
