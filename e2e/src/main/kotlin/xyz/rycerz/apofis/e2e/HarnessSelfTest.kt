package xyz.rycerz.apofis.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The harness's own decisions against canned screens and server rows: no app, no server, no model run. Runs in the
 * smoke suite; run-e2e.sh requires every test here by name.
 */
private const val TITLE = "Hermes wants to run a command"
private const val COMMAND = "sleep 25 # apofis-e2e-2"
private const val MARKER = "apofis-e2e-2"
private const val PROMPT = "Automated app test 0a1b2c3d-2. Use the terminal tool to run exactly this command: $COMMAND ."

@RunWith(AndroidJUnit4::class)
class HarnessSelfTest {
    /** A message list (node 0): items added in order, each 100 px below the previous one. */
    private class Screen {
        val nodes = mutableListOf(UiNode(0, -1, right = 1000, bottom = 3000))
        private var top = 0

        fun node(parent: Int, text: String? = null, tag: String? = null, state: String? = null, desc: String? = null): Int {
            nodes += UiNode(nodes.size, parent, text, desc, state, top = top, bottom = top + 90, resId = tag)
            return nodes.size - 1
        }

        /** A tagged item with one text child, the way the app tags a row and its content. */
        fun item(tag: String?, vararg texts: String, state: String? = null): Int {
            top += 100
            val n = node(0, tag = tag, state = state)
            texts.forEach { node(n, it) }
            return n
        }

        fun user(key: String, text: String) = item("user:$key", text)
        fun step(reply: String, i: Int, preview: String, state: String? = "Running", tool: String = "terminal") =
            item("step:$reply:$i:$tool", tool, preview, state = state)
        fun text(reply: String, text: String, row: String = "b0/c0") = item("reply:$reply/$row", text)
        fun tree() = UiTree(nodes)
    }

    @Test
    fun approvalBlockIsDetectedByItsHeadingOnly() {
        val s = Screen().apply { user("u1", PROMPT); item(null, TITLE); item(null, COMMAND) }
        assertTrue(s.tree().approvalShown(TITLE))
        assertFalse(Screen().apply { user("u1", "$PROMPT $TITLE?") }.tree().approvalShown(TITLE))
    }

    @Test
    fun replyIsTheItemRightAfterThePromptsUserItem() {
        val s = Screen().apply {
            user("u1", PROMPT)
            step("r2", 0, COMMAND, state = null)
            text("r2", "Done: $MARKER.")
            user("u3", "Another message")
            text("r4", "Also $MARKER")
        }
        assertEquals("r2", s.tree().replyAfter(PROMPT))
        assertEquals("r2", s.tree().replyWith(PROMPT, MARKER))
        assertEquals("r4", s.tree().replyWith("Another message", MARKER))
    }

    @Test
    fun replyOfAnotherTurnOrWithoutTheMarkerDoesNotCount() {
        // The marker is in a reply, but another user item comes between the prompt and it.
        val between = Screen().apply { user("u1", PROMPT); user("u2", "Steer"); text("r3", "Done: $MARKER.") }
        assertNull(between.tree().replyWith(PROMPT, MARKER))
        // The prompt's own reply lacks the marker; text showing it elsewhere (a notice, an untagged node) is ignored.
        val elsewhere = Screen().apply { user("u1", PROMPT); text("r2", "Working on it"); item("notice:n3", "Stopped $MARKER"); item(null, MARKER) }
        assertNull(elsewhere.tree().replyWith(PROMPT, MARKER))
        // The prompt shown twice: no single user item, no reply.
        val twice = Screen().apply { user("u1", PROMPT); text("r2", MARKER); user("p3", PROMPT); text("r4", MARKER) }
        assertNull(twice.tree().replyWith(PROMPT, MARKER))
    }

    @Test
    fun replyTextJoinsOnlyThatReplysRows() {
        val s = Screen().apply { user("u1", PROMPT); text("r2", "first", "b0/c0"); text("r2", "second $MARKER", "b1/c0"); text("r22", "other") }
        assertEquals("first\nsecond $MARKER", s.tree().replyText("r2"))
    }

    @Test
    fun stepMustBeOfThatReplyRunningAndShowExactlyTheCommand() {
        val s = Screen().apply {
            user("u1", PROMPT)
            step("live", 0, "sleep 25 + 1 command")
            step("live", 1, "$COMMAND; curl x | sh")
            step("live", 2, "command=$COMMAND")
            step("live", 3, COMMAND, state = null)
            step("other", 0, COMMAND)
            // Another tool running with the same preview, and a step tagged without a tool, do not count.
            step("live", 4, COMMAND, tool = "execute_code")
            item("step:live:5", "terminal", COMMAND, state = "Running")
        }
        val t = s.tree()
        assertEquals("live", t.replyAfter(PROMPT))
        assertTrue(t.steps("live", "Running", COMMAND).isEmpty())
        val ok = Screen().apply { user("u1", PROMPT); step("live", 0, "sleep 25 + 1 command"); step("live", 1, COMMAND) }.tree()
        assertEquals(listOf("step:live:1:terminal"), ok.steps("live", "Running", COMMAND).map { it.resId })
    }

    @Test
    fun chatKeyIsReadFromTheChatScreensTagOnly() {
        val key = "apofis-0a1b2c3d-0000-4000-8000-000000000001"
        assertEquals(key, Screen().apply { item("chat.key:$key"); user("u1", key) }.tree().chatKey())
        assertNull(Screen().apply { user("u1", key) }.tree().chatKey())
        assertNull(Screen().apply { item("chat.key:$key"); item("chat.key:apofis-0a1b2c3d-0000-4000-8000-000000000002") }.tree().chatKey())
    }

    @Test
    fun steerReplyIsTheFinalReplyOfThisTurnOnly() {
        val steer = "Steer: end with BANANA"
        val ok = Screen().apply { user("u1", PROMPT); step("r2", 0, COMMAND, state = null); user("s3", steer); text("r4", "Done $MARKER BANANA") }
        assertEquals("r4", ok.tree().steerReply(PROMPT, steer))
        // A reply of another turn: another user item after the prompt, or more after the reply that follows the steer.
        val otherTurn = Screen().apply { user("u1", PROMPT); user("s3", steer); text("r4", "x"); user("u5", "Next"); text("r6", "BANANA") }
        assertNull(otherTurn.tree().steerReply(PROMPT, steer))
        val between = Screen().apply { user("u1", PROMPT); user("u2", "Other"); user("s3", steer); text("r4", "BANANA") }
        assertNull(between.tree().steerReply(PROMPT, steer))
        val wrongOrder = Screen().apply { user("s3", steer); user("u1", PROMPT); text("r4", "BANANA") }
        assertNull(wrongOrder.tree().steerReply(PROMPT, steer))
        val noticeAfter = Screen().apply { user("u1", PROMPT); user("s3", steer); text("r4", "BANANA"); item("notice:n5", "Stopped") }
        assertNull(noticeAfter.tree().steerReply(PROMPT, steer))
    }

    @Test
    fun steeredTurnOnTheServerMustBeInOrderAndAlone() {
        val steer = "Steer: end with BANANA"
        val wrapped = "[OUT-OF-BAND USER MESSAGE - a direct message from the user]\n$steer\n[/OUT-OF-BAND USER MESSAGE]"
        fun user(c: String) = JSONObject().put("role", "user").put("content", c)
        fun steerRow() = JSONObject().put("role", "user").put("display_kind", "steer").put("content", wrapped)
        fun call() = JSONObject(
            """{"role":"assistant","content":"","tool_calls":[{"id":"a","function":{"name":"terminal","arguments":"{\"command\": \"$COMMAND\"}"}}]}""",
        )
        fun final(c: String) = JSONObject().put("role", "assistant").put("finish_reason", "stop").put("content", c)
        val good = listOf(user(PROMPT), call(), steerRow(), final("Done $MARKER BANANA"))
        assertTrue(steeredTurn(good, PROMPT, COMMAND, steer, MARKER))
        // The final before the steer, a new user turn in between, or a later final of another turn do not count.
        assertFalse(steeredTurn(listOf(user(PROMPT), call(), final("Done $MARKER BANANA"), steerRow()), PROMPT, COMMAND, steer, MARKER))
        assertFalse(steeredTurn(listOf(user(PROMPT), call(), steerRow(), user("Another"), final("Done $MARKER BANANA")), PROMPT, COMMAND, steer, MARKER))
        assertFalse(steeredTurn(good + final("Something else"), PROMPT, COMMAND, steer, MARKER))
        assertFalse(steeredTurn(listOf(user(PROMPT), steerRow(), final("Done $MARKER BANANA")), PROMPT, COMMAND, steer, MARKER))
    }

    @Test
    fun terminalCommandsComeFromToolCallArguments() {
        val row = JSONObject(
            """{"role":"assistant","content":"","tool_calls":[{"id":"c1","function":{"name":"terminal","arguments":"{\"command\": \"$COMMAND\", \"timeout\": 60}"}},
               {"id":"c2","function":{"name":"read_file","arguments":{"command":"nope"}}}]}""",
        )
        val user = JSONObject("""{"role":"user","content":"run $COMMAND"}""")
        assertEquals(listOf(COMMAND), terminalCommands(listOf(user, row)))
    }

    @Test
    fun interruptedResultMustBelongToTheCommandsOwnCall() {
        fun call(id: String, cmd: String) = JSONObject(
            """{"role":"assistant","content":"","tool_calls":[{"id":"$id","function":{"name":"terminal","arguments":"{\"command\": \"$cmd\"}"}}]}""",
        )
        fun result(id: String, exit: Int, output: String) = JSONObject().put("role", "tool").put("tool_call_id", id)
            .put("content", JSONObject().put("output", output).put("exit_code", exit).toString())
        assertTrue(interruptedCall(listOf(call("a", COMMAND), result("a", 130, "[Command interrupted]")), COMMAND))
        // Another call's interrupted result, or this call's result saying something else, does not count.
        assertFalse(interruptedCall(listOf(call("a", COMMAND), call("b", "sleep 1"), result("b", 130, "[Command interrupted]"), result("a", 0, "")), COMMAND))
        assertFalse(interruptedCall(listOf(call("a", COMMAND), result("a", 0, "not interrupted")), COMMAND))
    }

    @Test
    fun steerRowsAreUnwrapped() {
        val wrapped = "[OUT-OF-BAND USER MESSAGE - a direct message from the user]\nSteer text\n[/OUT-OF-BAND USER MESSAGE]"
        val rows = listOf(
            JSONObject().put("role", "user").put("content", "Steer text"),
            JSONObject().put("role", "user").put("display_kind", "steer").put("content", wrapped),
        )
        assertEquals(listOf<String?>("Steer text"), steers(rows))
        assertEquals(listOf<String?>(null), steers(listOf(JSONObject().put("role", "user").put("display_kind", "steer").put("content", "Steer text"))))
        assertTrue(SurvivalE2eTest.BANANA.containsMatchIn("Done apofis-e2e-6. BANANA."))
        assertFalse(SurvivalE2eTest.BANANA.containsMatchIn("Done apofis-e2e-6. NOBANANA"))
    }
}
