package xyz.rycerz.apofis

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundTest {
    private val calls = mutableListOf<String>()

    @Test
    fun stopDuringASlowResumeEndsWithPause() = runTest {
        val slow = CompletableDeferred<Unit>()
        val fg = Foreground(backgroundScope, resume = { _ ->
            calls += "resume"
            slow.await()
            calls += "resumed"
        }, pause = { calls += "pause" }, onError = {})

        fg.start()
        runCurrent()
        fg.stop() // arrives while resumeAll is still running
        runCurrent()
        assertEquals(listOf("resume"), calls)
        slow.complete(Unit)
        runCurrent()

        assertEquals(listOf("resume", "resumed", "pause"), calls)
    }

    @Test
    fun resumeQueuedBehindAStopDoesNothing() = runTest {
        val fg = Foreground(backgroundScope, resume = { _ -> calls += "resume" }, pause = { calls += "pause" }, onError = {})
        fg.start()
        fg.stop()
        runCurrent()
        assertEquals(listOf("pause"), calls)
    }

    @Test
    fun lastEventWins() = runTest {
        val fg = Foreground(backgroundScope, resume = { _ -> calls += "resume" }, pause = { calls += "pause" }, onError = {})
        fg.start()
        fg.stop()
        fg.start()
        runCurrent()
        assertEquals("resume", calls.last())
    }

    @Test
    fun aFailingResumeDoesNotStopLaterEvents() = runTest {
        val errors = mutableListOf<Throwable>()
        val fg = Foreground(backgroundScope, resume = { _ -> throw IllegalStateException("boom") }, pause = { calls += "pause" }, onError = { errors += it })
        fg.start()
        runCurrent()
        fg.stop()
        runCurrent()
        assertEquals(1, errors.size)
        assertEquals(listOf("pause"), calls)
    }
}
