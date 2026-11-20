package xyz.rycerz.apofis.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.api.MessageDto
import xyz.rycerz.apofis.api.MessagePage
import xyz.rycerz.apofis.api.fixture

class TranscriptTest {
    private fun rows(name: String) = HermesApi.json.decodeFromString<MessagePage>(fixture(name)).messages

    private fun row(
        id: Long,
        role: String,
        content: String = "",
        displayKind: String? = null,
        toolCallsJson: String? = null,
        toolCallId: String? = null,
        reasoning: String? = null,
    ) = MessageDto(id, role, displayKind, content, reasoning, toolCallsJson, toolCallId, null, id.toDouble(), null)

    private fun call(id: String, args: String = """{\"command\":\"ls\"}""") =
        """[{"id":"$id","call_id":"$id","type":"function","function":{"name":"terminal","arguments":"$args"}}]"""

    private fun toolSteps(items: List<ChatItem>) = items.filterIsInstance<AssistantReply>().flatMap { it.steps }.filterIsInstance<ToolStep>()

    @Test
    fun toolRunFixture() {
        val items = buildTranscript(rows("messages_tool_run.json"))
        assertEquals(
            listOf(UserMessage::class, AssistantReply::class, UserMessage::class, AssistantReply::class),
            items.map { it::class },
        )
        // The first turn alone is [UserMessage, AssistantReply].
        val reply = items[1] as AssistantReply
        val step = reply.steps.single() as ToolStep
        assertEquals("terminal", step.name)
        assertEquals("call_bcfa62f8a89f42adb149a971df2bb7cf", step.callId)
        assertFalse(step.error)
        assertFalse(step.running)
        assertEquals("command=sleep 20; echo apofis-marker, timeout=30", step.argsPreview)
        assertEquals("The command completed successfully: apofis-marker.", reply.text)
        assertEquals(listOf("u16393", "r16394", "u16398", "r16399"), items.map { it.key })
    }

    @Test
    fun apiUserRowsAreBubbles() {
        val items = buildTranscript(listOf(row(1, "user", "hi", displayKind = "internal_notification")))
        assertEquals(UserMessage("u1", "hi", steered = false, timestamp = 1.0), items.single())
    }

    @Test
    fun steerFixture() {
        val items = buildTranscript(rows("messages_steer.json"))
        assertEquals(
            listOf(UserMessage::class, AssistantReply::class, UserMessage::class, AssistantReply::class),
            items.map { it::class },
        )
        val steer = items[2] as UserMessage
        assertTrue(steer.steered)
        assertFalse(steer.text.contains("OUT-OF-BAND"))
        assertFalse(steer.text.contains("["))
        assertEquals("Probe steer one: also mention the word alpha.\nProbe steer two: also mention the word beta.", steer.text)
    }

    @Test
    fun unwrapSteerKeepsMalformedContent() {
        assertEquals("plain text", unwrapSteer("  plain text \n"))
        val noClose = "[OUT-OF-BAND USER MESSAGE - x]\nhello"
        assertEquals(noClose, unwrapSteer(noClose))
        assertEquals("[OUT-OF-BAND USER MESSAGE - x][/OUT-OF-BAND USER MESSAGE]", unwrapSteer("[OUT-OF-BAND USER MESSAGE - x][/OUT-OF-BAND USER MESSAGE]"))
        assertEquals("a\n\nb", unwrapSteer("[OUT-OF-BAND USER MESSAGE - x]\na\n\nb\n[/OUT-OF-BAND USER MESSAGE]"))
    }

    @Test
    fun hiddenAndMetaDropped() {
        val items = buildTranscript(
            listOf(
                row(1, "user", "hi"),
                row(2, "assistant", "a"),
                row(3, "session_meta"),
                row(4, "assistant", "", displayKind = "hidden"),
                row(5, "assistant", "b"),
            ),
        )
        assertEquals(listOf("u1", "r2"), items.map { it.key })
        assertEquals("a\n\nb", (items[1] as AssistantReply).text)
    }

    @Test
    fun unknownKindIsNotice() {
        val items = buildTranscript(listOf(row(7, "system", "Switched model", displayKind = "model_switch")))
        assertEquals(Notice("n7", "model_switch", "Switched model"), items.single())
    }

    @Test
    fun runningTool() {
        val step = toolSteps(buildTranscript(listOf(row(1, "assistant", toolCallsJson = call("c1"))))).single()
        assertTrue(step.running)
        assertEquals(null, step.result)
        assertEquals("command=ls", step.argsPreview)
    }

    @Test
    fun toolErrorDetected() {
        for (result in listOf("""{"exit_code": 2}""", """{"error": "boom"}""")) {
            val items = buildTranscript(listOf(row(1, "assistant", toolCallsJson = call("c1")), row(2, "tool", result, toolCallId = "c1")))
            val step = toolSteps(items).single()
            assertTrue(result, step.error)
            assertFalse(step.running)
            assertEquals(result, step.result)
        }
    }

    @Test
    fun reasoningBeforeToolSteps() {
        val reply = buildTranscript(rows("messages_after_stop.json")).filterIsInstance<AssistantReply>().single()
        assertEquals(ReasoningStep("I’m starting the probe."), reply.steps[0])
        assertTrue((reply.steps[1] as ToolStep).error) // exit_code 130
        assertEquals("", reply.text)
    }

    @Test
    fun hugeToolResult() {
        val huge = """{"output": "${"x".repeat(3_000_000)}", "exit_code": 0}"""
        val items = buildTranscript(listOf(row(1, "assistant", toolCallsJson = call("c1")), row(2, "tool", huge, toolCallId = "c1")))
        val step = toolSteps(items).single()
        assertEquals(TOOL_RESULT_DISPLAY_LIMIT, step.result!!.length)
        assertTrue(step.resultTruncated)
        assertFalse(step.error)
    }

    @Test
    fun keysAreStable() {
        val input = rows("messages_steer.json") + rows("messages_tool_run.json")
        assertEquals(buildTranscript(input).map { it.key }, buildTranscript(input).map { it.key })
    }
}
