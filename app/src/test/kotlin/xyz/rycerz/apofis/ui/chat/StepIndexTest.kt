package xyz.rycerz.apofis.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.ChatItem
import xyz.rycerz.apofis.chat.ReasoningStep
import xyz.rycerz.apofis.chat.Step
import xyz.rycerz.apofis.chat.ToolStep
import xyz.rycerz.apofis.chat.UserMessage

class StepIndexTest {
    private fun tool(id: String, name: String = "terminal", result: String? = null) =
        ToolStep(id, name, "command=$id", result, resultTruncated = false, error = false, running = result == null)

    private val user = UserMessage("u1", "hi", steered = false, timestamp = 0.0)
    private val subject = StepSubject("r2", 1, "c2", "terminal")

    private fun live(steps: List<Step>, text: String) = Joined(listOf<ChatItem>(user), listOf(AssistantReply("r2", steps, text, 0.0, live = true)))

    @Test
    fun sameStepsListIsNotRescanned() {
        val steps = listOf(tool("c1"), tool("c2"))
        val index = StepIndex()
        val a = index.find(live(steps, "a"), subject)
        val b = index.find(live(steps, "ab"), subject)
        assertSame(steps[1], a?.step)
        assertSame(steps[1], b?.step)
        assertEquals(1, index.scans)
    }

    @Test
    fun liveToStoredSwapUpdatesLiveFlag() {
        val steps = listOf(tool("c1"), tool("c2", result = "done"))
        val index = StepIndex()
        assertTrue(index.find(live(steps, "a"), subject)!!.live)
        val stored = index.find(listOf(user, AssistantReply("r2", steps, "a and b", 0.0)), subject)
        assertNotNull(stored)
        assertFalse("Found.live comes from the current reply, never from the cache", stored!!.live)
        assertSame(steps[1], stored.step)
    }

    @Test
    fun removedOrReplacedSubjectIsGone() {
        val steps = listOf(tool("c1"), tool("c2"))
        val index = StepIndex()
        assertNotNull(index.find(live(steps, "a"), subject))
        assertNull("reply gone", index.find(listOf(user), subject))
        assertNotNull(index.find(live(steps, "a"), subject))
        assertNull("call id replaced", index.find(live(listOf(tool("c1"), tool("c3")), "a"), subject))
        assertNull("step changed kind", index.find(live(listOf(tool("c1"), ReasoningStep("x")), "a"), StepSubject("r2", 1, null, "terminal")))
        assertNull("duplicate call id is ambiguous", index.find(live(listOf(tool("c2"), tool("c2")), "a"), subject))
        assertNull("other reply key", index.find(live(steps, "a"), subject.copy(replyKey = "r9")))
    }

    @Test
    fun newStepsOrSubjectScanAgainOnce() {
        val index = StepIndex()
        val steps = listOf(tool("c1"), tool("c2"))
        index.find(live(steps, "a"), subject)
        val updated = listOf(tool("c1"), tool("c2", result = "done"))
        assertEquals("done", index.find(live(updated, "a"), subject)!!.step.result)
        assertEquals(2, index.scans)
        index.find(live(updated, "ab"), subject)
        assertEquals(2, index.scans)
        assertEquals("c1", index.find(live(updated, "ab"), StepSubject("r2", 0, "c1", "terminal"))!!.step.callId)
        assertEquals(3, index.scans)
        // A miss is cached too: a token does not rescan for a step that is not there.
        val gone = StepSubject("r2", 5, "c9", "terminal")
        assertNull(index.find(live(updated, "abc"), gone))
        assertNull(index.find(live(updated, "abcd"), gone))
        assertEquals(4, index.scans)
    }

    @Test
    fun withoutCallIdTheIndexAndNameDecide() {
        val index = StepIndex()
        val steps = listOf(tool(""), tool("", name = "read"))
        assertEquals("read", index.find(live(steps, ""), StepSubject("r2", 1, null, "read"))!!.step.name)
        assertNull(index.find(live(steps, ""), StepSubject("r2", 1, null, "terminal")))
        assertNull(index.find(live(steps, ""), StepSubject("r2", 7, null, "read")))
    }
}
