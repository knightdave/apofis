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
        assertEquals("apofis-marker", step.result)
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
        assertEquals("  plain text \n", unwrapSteer("  plain text \n"))
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

    private fun stepFor(result: String) =
        toolSteps(buildTranscript(listOf(row(1, "assistant", toolCallsJson = call("c1")), row(2, "tool", result, toolCallId = "c1")))).single()

    @Test
    fun toolErrorDetected() {
        for (result in listOf("""{"exit_code": 2}""", """{"error": "boom"}""", """{"error": "false"}""", """{"error": true}""", """{"error": 0}""", """{"error": 1}""", """{"error": {}}""", """{"error": []}""")) {
            val step = stepFor(result)
            assertTrue(result, step.error)
            assertFalse(step.running)
            assertEquals(result, step.result)
        }
        for (result in listOf("""{"error": null}""", """{"error": false}""", """{"error": ""}""", """{"exit_code": 0}""", """{"exit_code": "2"}""", """{"exit_code": 2.5}""", "plain text", "[1]", "")) {
            assertFalse(result, stepFor(result).error)
        }
    }

    @Test
    fun resultShowsOutputField() {
        val step = toolSteps(buildTranscript(rows("messages_tool_run.json"))).first()
        assertEquals("apofis-marker", step.result)
        assertFalse(step.resultTruncated)
        assertEquals("""{"output": 7}""", stepFor("""{"output": 7}""").result)
    }

    @Test
    fun hugeResultFailureDetected() {
        val big = "x".repeat(3_000_000)
        val failed = stepFor("""{"output": "$big", "exit_code": 2}""")
        assertTrue(failed.error)
        assertEquals(TOOL_RESULT_DISPLAY_LIMIT, failed.result!!.length)
        assertTrue(failed.resultTruncated)
        assertTrue(stepFor("""{"output": "$big", "exit_code": 2, "error": null}""").error)
        assertTrue(stepFor("""{"output": "$big", "error": "boom"}""").error)
        assertFalse(stepFor("""{"output": "$big", "exit_code": 0, "error": null}""").error)
        assertTrue(stepFor("""{"output": "$big", "exit_code": 0, "error": true}""").error)
        assertTrue(stepFor("""{"output": "$big", "exit_code": 0, "error": 0}""").error)
        assertTrue(stepFor("""{"output": "$big", "exit_code": 0, "error": 1}""").error)
        assertFalse(stepFor("""{"meta": {"exit_code": 2}, "output": "$big", "exit_code": 0}""").error)
        assertFalse(stepFor("""{"output": "$big", "meta": {"exit_code": 2}}""").error)
        assertTrue(stepFor("""{"output": "$big", "exit_code": 0, "error": "${"ab\\\"".repeat(950)}"}""").error) // escaped quotes
        assertTrue(stepFor("""{"output": "$big", "exit_code": 0, "error": "${"e".repeat(4093)}"}""").error)
        assertTrue(stepFor("""{"output": "$big", "exit_code": 0, "error": "${"e".repeat(5000)}"}""").error)
        assertTrue(stepFor("""{"output": "$big", "error": "${"e".repeat(4093)}"}""").error)
        assertFalse(stepFor("""{"output": "$big", "exit_code": 0, "error": ""}""").error)
        assertFalse(stepFor("""{"output": "$big", "error": null, "exit_code":"2"}""").error)
        assertTrue(stepFor("""{"output": "$big", "error": null, "exit_code":-1}""").error)
        assertTrue(stepFor("""{"output": "$big", "exit_code": 1${"0".repeat(5000)}}""").error) // long integer token
    }

    @Test
    fun manyTurnsStayLinear() {
        val calls = (0 until 100_000).joinToString(",", "[", "]") { """{"id":"p$it","function":{"name":"t","arguments":"{}"}}""" }
        val input = mutableListOf(row(0, "assistant", toolCallsJson = calls))
        for (i in 1..20_000) {
            input += row(2L * i, "user", "q")
            input += row(2L * i + 1, "assistant", toolCallsJson = call("c$i")) // left running, so each turn has a waiting call
        }
        val items = buildTranscript(input)
        assertEquals(40_001, items.size)
        assertEquals("r0", items.first().key)
        assertEquals("r40001", items.last().key)
    }

    @Test
    fun noticeBetweenCallAndResult() {
        val items = buildTranscript(
            listOf(
                row(1, "user", "hi"),
                row(2, "assistant", toolCallsJson = call("c1")),
                row(3, "system", "Switched model", displayKind = "model_switch"),
                row(4, "tool", """{"output": "ok", "exit_code": 0}""", toolCallId = "c1"),
                row(5, "assistant", "done"),
            ),
        )
        assertEquals(listOf("u1", "r2", "n3", "r5"), items.map { it.key })
        val step = (items[1] as AssistantReply).steps.single() as ToolStep
        assertFalse(step.running)
        assertEquals("ok", step.result)
        assertEquals("", (items[1] as AssistantReply).text)
        assertEquals(AssistantReply("r5", emptyList(), "done", 5.0), items[3])
    }

    @Test
    fun orphanAfterNoticeStaysWhereStored() {
        val items = buildTranscript(
            listOf(
                row(1, "assistant", toolCallsJson = call("c1")),
                row(2, "system", "Switched model", displayKind = "model_switch"),
                row(3, "tool", "lost", toolCallId = "c9"),
            ),
        )
        assertEquals(listOf("r1", "n2", "r3"), items.map { it.key })
        assertTrue(((items[0] as AssistantReply).steps.single() as ToolStep).running)
        assertEquals("lost", ((items[2] as AssistantReply).steps.single() as ToolStep).result)
    }

    @Test
    fun unwrapSteerExactBody() {
        val open = "[OUT-OF-BAND USER MESSAGE - x]"
        val close = "[/OUT-OF-BAND USER MESSAGE]"
        assertEquals("  a\r\n b ", unwrapSteer("$open\r\n  a\r\n b \r\n$close"))
        assertEquals(" body\n", unwrapSteer("\n  \n$open\n body\n\n$close  \n"))
        assertEquals("", unwrapSteer("$open\n$close"))
        assertEquals("", unwrapSteer("$open\r\n$close"))
    }

    @Test
    fun orphanToolRowShownOnce() {
        val items = buildTranscript(
            listOf(
                row(1, "assistant", toolCallsJson = call("c1")),
                row(2, "tool", "first", toolCallId = "c1"),
                row(3, "tool", "lost", toolCallId = "c9"),
            ),
        )
        val steps = toolSteps(items)
        assertEquals(listOf("c1" to "first", "c9" to "lost"), steps.map { it.callId to it.result })
        assertFalse(steps[1].running)
    }

    @Test
    fun textIsNotTrimmed() {
        val code = "    indented code\nline  \n"
        val items = buildTranscript(
            listOf(
                row(1, "user", "  hi\n"),
                row(2, "assistant", code, reasoning = "  think\n"),
                row(3, "assistant", "  \n"),
                row(4, "assistant", "tail\n"),
            ),
        )
        assertEquals("  hi\n", (items[0] as UserMessage).text)
        val reply = items[1] as AssistantReply
        assertEquals("$code\n\ntail\n", reply.text)
        assertEquals(ReasoningStep("  think\n"), reply.steps.single())
        assertEquals(
            "  indented\n\n",
            unwrapSteer("[OUT-OF-BAND USER MESSAGE - x]\n  indented\n\n\n[/OUT-OF-BAND USER MESSAGE]"),
        )
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
