package xyz.rycerz.apofis.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.api.ApprovalChoice
import xyz.rycerz.apofis.api.PendingControl
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.run.Connection
import xyz.rycerz.apofis.run.LiveReply
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.theme.ApofisTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class ControlBlockTest {
    @get:Rule
    val rule = createComposeRule()

    private val reply = AssistantReply("live", emptyList(), "Checking.", 0.0, live = true)
    private val choices = ArrayList<Triple<String, Long, ApprovalChoice>>()
    private val actions = object : ChatActions {
        override fun onApproval(requestId: String, executionGeneration: Long, choice: ApprovalChoice) {
            choices += Triple(requestId, executionGeneration, choice)
        }
    }

    private fun live(pending: PendingControl?) = LiveReply("run_1", "running", "Checking.", emptyList(), pending, Connection.LIVE)

    @Composable
    private fun Themed(content: @Composable () -> Unit) =
        ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
            CompositionLocalProvider(LocalAnswerClock provides { rule.mainClock.currentTime }, content = content)
        }

    /** Past the answer guard: the request on screen has been seen. */
    private fun seen() = rule.mainClock.advanceTimeBy(400)

    private fun same(a: ImageBitmap, b: ImageBitmap) = a.toPixelMap().buffer.contentEquals(b.toPixelMap().buffer)

    @Test
    fun answersCarryTheShownRequestAndAReplacementResetsTheBlock() {
        var pending by mutableStateOf(PendingControl("approval", "ctl_1", 1, "rm -rf ~/old", null))
        rule.setContent { Themed { ReplyFooter(reply, live(pending), emptyMap(), answering = false, actions) } }
        rule.onNodeWithText("Hermes wants to run a command").assert(isHeading())
        seen()
        rule.onNodeWithText("Allow once").performClick()
        assertEquals(Triple("ctl_1", 1L, ApprovalChoice.ONCE), choices.last())

        rule.onNodeWithContentDescription("More approval options").performClick()
        rule.onNodeWithText("Always allow").assertExists()
        pending = pending.copy(executionGeneration = 2, command = "rm -rf ~/older")
        rule.waitForIdle()
        assertEquals("an open menu never carries over to a new request", 0, rule.onAllNodesWithTextCount("Always allow"))
        rule.onNodeWithText("rm -rf ~/older").assertExists()
        seen()
        rule.onNodeWithText("Deny").performClick()
        assertEquals(Triple("ctl_1", 2L, ApprovalChoice.DENY), choices.last())

        pending = PendingControl("approval", "ctl_2", 2, "ls", null)
        rule.waitForIdle()
        seen()
        rule.onNodeWithContentDescription("More approval options").performClick()
        rule.onNodeWithText("Allow for this chat").performClick()
        assertEquals(Triple("ctl_2", 2L, ApprovalChoice.SESSION), choices.last())
        assertEquals(3, choices.size)
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String) =
        onAllNodes(androidx.compose.ui.test.hasText(text)).fetchSemanticsNodes().size

    @Test
    fun withoutCommandOnlyDenyAndUnknownKindsNoButtons() {
        var pending by mutableStateOf(PendingControl("approval", "ctl_1", 1, "  ", null))
        rule.setContent { Themed { ReplyFooter(reply, live(pending), emptyMap(), answering = false, actions) } }
        for (absent in listOf("  ", null)) {
            pending = PendingControl("approval", "ctl_1", 1, absent, null)
            rule.waitForIdle()
            rule.onNodeWithText("Deny").assertExists()
            assertEquals(0, rule.onAllNodesWithTextCount("Allow once"))
            assertEquals(0, rule.onAllNodesWithTextCount("Show full command"))
            assertEquals(0, rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription) and hasClickAction()).fetchSemanticsNodes().size)
        }
        pending = PendingControl("payment", "ctl_9", 1, "pay 100", "?")
        rule.waitForIdle()
        rule.onNodeWithText("Hermes is waiting for an answer").assert(isHeading())
        assertEquals(0, rule.onAllNodes(hasClickAction()).fetchSemanticsNodes().size)
    }

    @Test
    fun pillsHaveTargetsPressAndFocus() {
        rule.mainClock.autoAdvance = false
        lateinit var inputMode: InputModeManager
        rule.setContent {
            Themed {
                inputMode = LocalInputModeManager.current
                ReplyFooter(reply, live(PendingControl("approval", "ctl_1", 1, "ls", null)), emptyMap(), answering = false, actions)
            }
        }
        rule.mainClock.advanceTimeBy(320)
        for (label in listOf("Deny", "Allow once")) {
            val pill = rule.onNodeWithText(label)
            pill.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button)).assertHeightIsAtLeast(48.dp)
            val idle = rule.onRoot().captureToImage()
            pill.performTouchInput { down(center) }
            rule.mainClock.advanceTimeBy(320)
            assertFalse("$label shows no press", same(idle, rule.onRoot().captureToImage()))
            pill.performTouchInput { cancel() }
            rule.mainClock.advanceTimeBy(320)
            assertTrue("$label still looks pressed", same(idle, rule.onRoot().captureToImage()))
            rule.runOnUiThread { inputMode.requestInputMode(InputMode.Keyboard) }
            pill.requestFocus()
            rule.mainClock.advanceTimeBy(320)
            assertFalse("$label shows no focus", same(idle, rule.onRoot().captureToImage()))
            rule.runOnUiThread { inputMode.requestInputMode(InputMode.Touch) }
            rule.onNodeWithContentDescription("More approval options").requestFocus() // move focus off before the next pill
            rule.mainClock.advanceTimeBy(320)
        }
        assertTrue(choices.isEmpty())
    }

    // ---- fix round 1: an answer needs a request the user has seen ----

    private fun guarded(pending: () -> PendingControl?, answering: () -> Boolean = { false }) = rule.setContent {
        Themed { ReplyFooter(reply, live(pending()), emptyMap(), answering(), actions) }
    }

    private fun advance(ms: Long) {
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(ms)
    }

    @Test
    fun aSuccessorDoesNotTakeTheSecondTapOfADoubleTap() {
        rule.mainClock.autoAdvance = false
        var pending by mutableStateOf(PendingControl("approval", "ctl_a", 1, "rm a", null))
        guarded({ pending })
        advance(1000)
        rule.onNodeWithText("Allow once").performClick()
        assertEquals(listOf(Triple("ctl_a", 1L, ApprovalChoice.ONCE)), choices)

        pending = PendingControl("approval", "ctl_b", 1, "rm b", null) // appears between the two taps
        advance(16)
        rule.onNodeWithText("rm b").assertExists()
        advance(100)
        rule.onNodeWithText("Allow once").performClick() // the trailing tap: answers nothing
        rule.onNodeWithText("Deny").performClick()
        rule.onNodeWithContentDescription("More approval options").performClick()
        advance(16)
        rule.onNodeWithText("Always allow").performClick()
        advance(16)
        assertEquals(1, choices.size)

        advance(400) // seen long enough: a deliberate tap answers the successor, once
        rule.onNodeWithText("Allow once").performClick()
        advance(16)
        assertEquals(listOf(Triple("ctl_a", 1L, ApprovalChoice.ONCE), Triple("ctl_b", 1L, ApprovalChoice.ONCE)), choices)
    }

    @Test
    fun aNewGenerationAlsoWaitsAndMenuOptionsAnswerAfterTheInterval() {
        rule.mainClock.autoAdvance = false
        var pending by mutableStateOf(PendingControl("approval", "ctl_a", 1, "rm a", null))
        guarded({ pending })
        advance(16)
        rule.onNodeWithText("Deny").performClick() // just shown: nothing
        advance(16)
        assertTrue(choices.isEmpty())
        advance(1000)
        pending = pending.copy(executionGeneration = 2)
        advance(48)
        rule.onNodeWithContentDescription("More approval options").performClick()
        advance(16)
        rule.onNodeWithText("Allow for this chat").performClick()
        advance(16)
        assertTrue("a replaced generation is a new request", choices.isEmpty())
        advance(1000)
        rule.onNodeWithContentDescription("More approval options").performClick()
        advance(16)
        advance(1) // the answer clock moves on before the tap: no real tap shares the popup's millisecond
        rule.onNodeWithText("Always allow").performClick()
        advance(16)
        assertEquals(listOf(Triple("ctl_a", 2L, ApprovalChoice.ALWAYS)), choices)
    }

    @Test
    fun aPressThatBeganBeforeTheSuccessorNeverAnswersIt() {
        rule.mainClock.autoAdvance = false
        var n = 0
        var pending by mutableStateOf(PendingControl("approval", "ctl_0", 1, "rm 0", null))
        guarded({ pending })
        advance(1000)
        for (label in listOf("Deny", "Allow once", "Allow for this chat", "Always allow")) {
            val inMenu = label.startsWith("Allow f") || label.startsWith("Always")
            if (inMenu) {
                rule.onNodeWithContentDescription("More approval options").performClick()
                advance(16)
            }
            rule.onNodeWithText(label).performTouchInput { down(center) }
            advance(16)
            pending = PendingControl("approval", "ctl_${++n}", 1, "rm $n", null)
            advance(1000) // even long after the successor appeared
            // A pill's finger lifts on the same window; a menu's window is gone with the old request.
            if (!inMenu) rule.onRoot().performTouchInput { up() }
            advance(16)
            assertTrue("$label answered the successor", choices.isEmpty())
            assertEquals("the old menu is closed", 0, rule.onAllNodesWithTextCount("Always allow"))
        }
    }

    @Test
    fun answeringDisablesEveryChoice() {
        var pending by mutableStateOf(PendingControl("approval", "ctl_a", 1, "rm a", null))
        guarded({ pending }, answering = { true })
        for (label in listOf("Deny", "Allow once")) rule.onNodeWithText(label).assertIsNotEnabled().performClick()
        rule.onNodeWithContentDescription("More approval options").assertIsNotEnabled()
        assertTrue(choices.isEmpty())
    }

    // ---- final fix wave: a long command is never laid out whole, and can always be read whole

    /** The length of every text layout input composed now (all windows). */
    private fun layoutInputs(): List<Int> =
        rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNodes().map { node ->
            val results = ArrayList<TextLayoutResult>()
            node.config[SemanticsActions.GetTextLayoutResult].action!!(results)
            results.single().layoutInput.text.length
        }

    private fun textOf(node: SemanticsNode) = node.config[SemanticsProperties.Text].joinToString("") { it.text }

    /** Opens the full command, reads the reader's pieces in order to the last one, and goes back. */
    private fun readWhole(): List<String> {
        rule.onNodeWithText("Show full command").performClick()
        rule.waitForIdle()
        return readOpen()
    }

    /** Reads the open reader's pieces in order to the last one, and goes back. */
    private fun readOpen(): List<String> {
        val list = rule.onNode(hasScrollToIndexAction())
        val pieces = ArrayList<String>()
        while (true) {
            if (runCatching { list.performScrollToIndex(pieces.size) }.isFailure) break // past the last piece
            rule.waitForIdle()
            pieces += textOf(rule.onAllNodes(hasTestTag("select.piece:${pieces.size}")).fetchSemanticsNodes().singleOrNull() ?: break)
        }
        rule.onNodeWithContentDescription("Back").performClick()
        rule.waitForIdle()
        return pieces
    }

    @Test
    fun anOrdinaryCommandIsShownWholeInItsRow() {
        val command = "x".repeat(ONE_LINE_MAX)
        rule.setContent { Themed { ReplyFooter(reply, live(PendingControl("approval", "ctl_1", 1, command, null)), emptyMap(), answering = false, actions) } }
        rule.onNodeWithText(command).assertExists()
        assertEquals(0, rule.onAllNodesWithTextCount("Show full command"))
    }

    @Test
    fun aLongCommandShowsItsHeadAndTheReaderShowsAllOfIt() {
        val a = "printf '%s' " + "y".repeat(20_000) + " > /tmp/a"
        val b = a.dropLast(1) + "b" // the same command but for its last character
        var pending by mutableStateOf(PendingControl("approval", "ctl_1", 1, a, null))
        rule.setContent { Themed { ReplyFooter(reply, live(pending), emptyMap(), answering = false, actions) } }
        for ((i, command) in listOf(a, b).withIndex()) {
            pending = PendingControl("approval", "ctl_1", i + 1L, command, null)
            rule.waitForIdle()
            assertTrue("a text laid out over the bound: ${layoutInputs().max()}", layoutInputs().all { it <= ONE_LINE_MAX })
            rule.onNodeWithText(command.take(ONE_LINE_MAX)).assertExists() // the head, in the command's row
            val pieces = readWhole()
            assertTrue(pieces.size > 1)
            assertTrue("the last piece shows the difference", pieces.last().endsWith(if (i == 0) "/tmp/a" else "/tmp/b"))
            assertEquals(command, pieces.joinToString(""))
            seen()
            rule.onNodeWithText("Allow once").performClick() // Allow stays where it was
            assertEquals(Triple("ctl_1", i + 1L, ApprovalChoice.ONCE), choices.last())
        }
    }

    @Test
    fun aMillionSpacesThenOneCharacterIsACommandReadableToItsEnd() {
        val command = " ".repeat(1_000_000) + "x"
        rule.setContent { Themed { ReplyFooter(reply, live(PendingControl("approval", "ctl_1", 1, command, null)), emptyMap(), answering = false, actions) } }
        assertTrue(layoutInputs().all { it <= ONE_LINE_MAX })
        rule.onNodeWithText("Allow once").assertExists()
        val pieces = readWhole()
        assertTrue(pieces.last().endsWith("x"))
        assertEquals(command.length, pieces.sumOf { it.length })
    }

    // ---- closing commit: the block follows the current command of its request ----

    private fun footer(pending: () -> PendingControl) = rule.setContent { Themed { ReplyFooter(reply, live(pending()), emptyMap(), answering = false, actions) } }

    @Test
    fun aShortCommandReplacedByALongOneUnderTheSameRequestShowsTheNewOne() {
        val long = "echo " + "z".repeat(20_000) + " > /tmp/new"
        var pending by mutableStateOf(PendingControl("approval", "ctl_1", 1, "ls", null))
        footer { pending }
        rule.onNodeWithText("ls").assertExists()
        assertEquals(0, rule.onAllNodesWithTextCount("Show full command"))
        pending = pending.copy(command = long) // the same id and generation
        rule.waitForIdle()
        rule.onNodeWithText(long.take(ONE_LINE_MAX)).assertExists()
        assertTrue("laid out whole", layoutInputs().all { it <= ONE_LINE_MAX })
        val pieces = readWhole()
        assertTrue("the reader shows the new tail", pieces.last().endsWith("/tmp/new"))
        assertEquals(long, pieces.joinToString(""))
    }

    @Test
    fun aLongCommandReplacedWhileTheReaderIsOpenShowsTheNewText() {
        val a = "echo " + "a".repeat(20_000) + " > /tmp/a"
        val b = "echo " + "b".repeat(20_000) + " > /tmp/b"
        var pending by mutableStateOf(PendingControl("approval", "ctl_1", 1, a, null))
        footer { pending }
        rule.onNodeWithText("Show full command").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("select.piece:0").assertExists()
        pending = pending.copy(command = b) // the reader stays open
        rule.waitForIdle()
        assertEquals(b, readOpen().joinToString(""))
    }

    @Test
    fun aCommandThatBecomesBlankLeavesDenyOnly() {
        var pending by mutableStateOf(PendingControl("approval", "ctl_1", 1, "ls", null))
        footer { pending }
        rule.onNodeWithText("Allow once").assertExists()
        pending = pending.copy(command = "  ")
        rule.waitForIdle()
        rule.onNodeWithText("Deny").assertExists()
        assertEquals(0, rule.onAllNodesWithTextCount("Allow once"))
        assertEquals(0, rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription) and hasClickAction()).fetchSemanticsNodes().size)
    }

    @Test
    fun aBlankCommandThatGetsTextOffersAllow() {
        var pending by mutableStateOf(PendingControl("approval", "ctl_1", 1, "  ", null))
        footer { pending }
        assertEquals(0, rule.onAllNodesWithTextCount("Allow once"))
        pending = pending.copy(command = "ls")
        rule.waitForIdle()
        rule.onNodeWithText("ls").assertExists()
        rule.onNodeWithText("Allow once").assertExists()
        rule.onNodeWithContentDescription("More approval options").assertExists()
    }
}
