package xyz.rycerz.apofis.ui.drawer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import androidx.compose.foundation.ScrollState
import androidx.compose.runtime.BroadcastFrameClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import org.junit.Assert.assertNull
import xyz.rycerz.apofis.ui.theme.scrollsSideways
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

    /** A paused child scope with a frame clock that is never ticked: a settle that animates stays suspended. */
    private fun TestScope.paused() = CoroutineScope(StandardTestDispatcher(testScheduler) + BroadcastFrameClock() + Job())

    private fun fresh(onOpening: () -> Unit = {}) = PushDrawerState(PushDrawerValue.Closed).apply {
        width = 100f
        this.onOpening = onOpening
    }

    /** The host scope is cancelled right after a release, before anything ran: the owner is let go, and idle finishes the settle. */
    @Test
    fun aReleaseWhoseScopeIsCancelledLetsGo() = runTest {
        var openings = 0
        val state = fresh { openings++ }
        val host = paused()
        state.scope = host
        val owner = DragOwner(fromScrim = false)
        state.startDrag(owner)
        state.dragBy(owner, 70f)
        state.endDrag(owner, 0f, lost = false)
        host.cancel()
        testScheduler.runCurrent() // the cancellation is processed; nothing else ran
        assertNull("the owner stayed after its scope went", state.owner)
        assertEquals(1, openings) // the release settle had started (undispatched): target Open, its refresh asked once
        state.reduceMotion = true
        state.scope = this
        state.idle()
        advanceUntilIdle()
        assertTrue(state.isOpen)
        assertEquals(1f, state.fraction)
        assertTrue(state.covered) // Back and the gate agree with the open drawer
        assertEquals(1, openings)
    }

    /** A fast opening release below half, then a code close before the scheduler runs: the release never reopens it. */
    @Test
    fun aCodeCloseAfterAReleaseWins() = runTest {
        val state = fresh().apply { reduceMotion = true }
        state.scope = paused()
        val owner = DragOwner(fromScrim = false)
        state.startDrag(owner)
        state.dragBy(owner, 30f)
        state.endDrag(owner, 1000f, lost = false)
        state.close()
        advanceUntilIdle()
        assertFalse(state.isOpen)
        assertEquals(0f, state.fraction)
    }

    /** A settle that has started is superseded by a new drag; once its cancellation is processed the new drag still owns the drawer. */
    @Test
    fun aSupersededSettleLeavesTheNewOwner() = runTest {
        val state = fresh()
        state.scope = paused()
        val first = DragOwner(fromScrim = true)
        state.startDrag(first)
        state.dragBy(first, 30f)
        state.endDrag(first, 0f, lost = false) // settles toward Closed, suspended on the frame clock
        assertTrue(state.settling)
        val second = DragOwner(fromScrim = true)
        assertTrue(state.startDrag(second))
        advanceUntilIdle() // the cancelled settle's cleanup runs now
        assertTrue("the old settle cleared the new owner", state.owner === second)
    }

    /** A scroller not yet measured (range Int.MAX_VALUE) neither scrolls nor claims. */
    @Test
    fun anUnmeasuredScrollerDoesNotScrollSideways() {
        assertFalse(ScrollState(0).scrollsSideways(4f))
    }
}
