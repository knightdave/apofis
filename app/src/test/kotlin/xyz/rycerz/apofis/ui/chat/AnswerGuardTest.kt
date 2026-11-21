package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import xyz.rycerz.apofis.ui.drawerCovers
import xyz.rycerz.apofis.ui.onShownInWindow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.espresso.Espresso
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
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
import xyz.rycerz.apofis.chat.ToolStep
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.run.Connection
import xyz.rycerz.apofis.run.LiveReply
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.FIXED_NOW
import xyz.rycerz.apofis.ui.FIXED_TODAY
import xyz.rycerz.apofis.ui.theme.ApofisTheme

/**
 * The answer guard counts only time the request was answerable on screen: a request that waited behind a sheet, a
 * dialog or the drawer, or that was just shown again, needs the full interval from the moment it is uncovered.
 * A manual clock ([now]) drives the guard; the double-tap interval is 300 ms.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class AnswerGuardTest {
    @get:Rule
    val rule = createComposeRule()

    private var now = 0L
    private val approvals = ArrayList<Triple<String, Long, ApprovalChoice>>()
    private val answers = ArrayList<Triple<String, Long, String>>()
    private val actions = object : ChatActions {
        override fun onApproval(requestId: String, executionGeneration: Long, choice: ApprovalChoice) {
            approvals += Triple(requestId, executionGeneration, choice)
        }
        override fun onClarify(requestId: String, executionGeneration: Long, answer: String) {
            answers += Triple(requestId, executionGeneration, answer)
        }
    }

    @Composable
    private fun Themed(content: @Composable () -> Unit) =
        ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
            CompositionLocalProvider(LocalAnswerClock provides { now }, content = content)
        }

    private val approval = PendingControl("approval", "ctl_1", 1, "rm -rf ~/old", null)
    private val reply = AssistantReply("live", emptyList(), "Checking.", 0.0, live = true)
    private fun live(pending: PendingControl?) = LiveReply("run_1", "running", "Checking.", emptyList(), pending, Connection.LIVE)

    private fun tick(ms: Long) {
        now += ms
        rule.waitForIdle()
    }

    @Test
    fun aRequestThatWaitedBehindTheToolSheetWaitsAgainOnceUncovered() {
        val user = UserMessage("u1", "Clean up", steered = false, timestamp = FIXED_NOW, runId = "run_1")
        val step = ToolStep("c1", "terminal", "command=ls", "a.txt", false, error = false, running = false)
        fun state(pending: PendingControl?): ChatUiState {
            val l = LiveReply("run_1", "running", "", listOf(step), pending, Connection.LIVE)
            return ChatUiState("apofis-1", "Files", StatusLine.WORKING, withLive(listOf(user), l) { FIXED_NOW }, ComposerMode.STOP, "", "h", l)
        }
        var ui by mutableStateOf(state(null))
        rule.setContent { Themed { ChatScreen(ui, actions, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY) } }
        rule.onNodeWithTag("step:live:0:terminal").performClick() // the tool sheet covers the chat
        rule.onNodeWithText("Arguments").assertExists()
        ui = state(approval) // the request arrives behind the sheet
        rule.waitForIdle() // composed there, under the sheet
        tick(5_000) // and ages there, far beyond the interval
        Espresso.pressBack()
        rule.waitForIdle()
        rule.onNodeWithText("Allow once").performClick() // the moment it is uncovered: nothing
        tick(299)
        rule.onNodeWithText("Allow once").performClick()
        assertTrue("answered before it was seen for the interval: $approvals", approvals.isEmpty())
        tick(1)
        rule.onNodeWithText("Allow once").performClick() // seen long enough: exactly one answer
        assertEquals(listOf(Triple("ctl_1", 1L, ApprovalChoice.ONCE)), approvals)
    }

    @Test
    fun coveringAndUncoveringRestartsTheInterval() {
        var obscured by mutableStateOf(false)
        rule.setContent {
            Themed { CompositionLocalProvider(LocalObscured provides obscured) { ReplyFooter(reply, live(approval), emptyMap(), answering = false, actions) } }
        }
        tick(1_000)
        obscured = true // the drawer or a dialog covers it
        tick(1_000)
        rule.onNodeWithText("Deny").performClick()
        obscured = false
        rule.waitForIdle()
        rule.onNodeWithText("Deny").performClick()
        tick(299)
        rule.onNodeWithText("Deny").performClick()
        assertTrue("$approvals", approvals.isEmpty())
        tick(1)
        rule.onNodeWithText("Deny").performClick()
        assertEquals(listOf(Triple("ctl_1", 1L, ApprovalChoice.DENY)), approvals)
    }

    @Test
    fun aClarifyAnswerTypedEarlyIsSentOnceAfterTheInterval() {
        val clarify = PendingControl("clarify", "ctl_q", 4, null, "Which folder?")
        var obscured by mutableStateOf(true)
        rule.setContent {
            Themed { CompositionLocalProvider(LocalObscured provides obscured) { ReplyFooter(reply, live(clarify), emptyMap(), answering = false, actions) } }
        }
        rule.onNodeWithText("Your answer").performTextInput("Downloads")
        tick(1_000) // the question waited behind a dialog
        obscured = false
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Send answer").performClick() // too early: nothing, and nothing used up
        tick(299)
        rule.onNodeWithContentDescription("Send answer").performClick()
        assertTrue("$answers", answers.isEmpty())
        tick(1)
        rule.onNodeWithContentDescription("Send answer").performClick()
        assertEquals(listOf(Triple("ctl_q", 4L, "Downloads")), answers)
    }

    @Test
    fun theSameRequestShownAgainWaitsAgain() {
        var pending by mutableStateOf<PendingControl?>(approval)
        rule.setContent { Themed { ReplyFooter(reply, live(pending), emptyMap(), answering = false, actions) } }
        tick(1_000)
        pending = null
        rule.waitForIdle()
        pending = approval // the same id and generation, shown again
        rule.waitForIdle()
        rule.onNodeWithText("Allow once").performClick()
        assertTrue("$approvals", approvals.isEmpty())
        tick(300)
        rule.onNodeWithText("Allow once").performClick()
        assertEquals(1, approvals.size)
    }

    @Test
    fun aRestoredScreenWaitsAgain() {
        val tester = StateRestorationTester(rule)
        tester.setContent { Themed { ReplyFooter(reply, live(approval), emptyMap(), answering = false, actions) } }
        tick(1_000)
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Allow once").performClick()
        assertTrue("$approvals", approvals.isEmpty())
        tick(300)
        rule.onNodeWithText("Allow once").performClick()
        assertEquals(1, approvals.size)
    }

    // ---- fix round 3: a press counts only if it began on a settled request; the controls must be in view ----

    private var hostFocused by mutableStateOf(true)
    private var menuFocused by mutableStateOf(true)

    @Composable
    private fun Footer(pending: PendingControl?, obscured: Boolean = false) =
        CompositionLocalProvider(LocalWindowFocus provides { menu -> if (menu) menuFocused else hostFocused }, LocalObscured provides obscured) {
            ReplyFooter(reply, live(pending), emptyMap(), answering = false, actions)
        }

    @Test
    fun aPressBegunBeforeTheIntervalEndsNeverAnswersEvenIfReleasedAfter() {
        rule.setContent { Themed { Footer(approval) } }
        tick(299)
        rule.onNodeWithText("Allow once").performTouchInput { down(center) }
        tick(2)
        rule.onNodeWithText("Allow once").performTouchInput { up() }
        rule.waitForIdle()
        assertTrue("$approvals", approvals.isEmpty())
        rule.onNodeWithText("Allow once").performClick() // a fresh press after the interval
        assertEquals(listOf(Triple("ctl_1", 1L, ApprovalChoice.ONCE)), approvals)
    }

    @Test
    fun anAccessibilityClickIsGatedToo() {
        rule.setContent { Themed { Footer(approval) } }
        rule.onNodeWithText("Deny").performSemanticsAction(SemanticsActions.OnClick)
        assertTrue("$approvals", approvals.isEmpty())
        tick(300)
        rule.onNodeWithText("Deny").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, approvals.size)
    }

    @Test
    fun losingAndRegainingWindowFocusRestartsTheInterval() {
        rule.setContent { Themed { Footer(approval) } }
        tick(1_000)
        hostFocused = false // a system dialog or the other split-screen pane
        tick(1_000)
        hostFocused = true
        rule.waitForIdle()
        rule.onNodeWithText("Allow once").performClick()
        assertTrue("$approvals", approvals.isEmpty())
        tick(300)
        rule.onNodeWithText("Allow once").performClick()
        assertEquals(1, approvals.size)
    }

    @Test
    fun scrollingTheButtonsOutOfViewAndBackRestartsTheInterval() {
        val listState = LazyListState()
        val history = (1..20).flatMap { listOf(UserMessage("u$it", "Question $it", false, FIXED_NOW), AssistantReply("r$it", emptyList(), "Answer $it", FIXED_NOW)) }
        val l = live(approval)
        val items = withLive(history + UserMessage("u21", "Clean up", false, FIXED_NOW, runId = "run_1"), l) { FIXED_NOW }
        rule.setContent {
            Themed {
                CompositionLocalProvider(LocalWindowFocus provides { true }) {
                    MessageList(items, l, emptyMap(), answering = false, actions = actions, onCancel = {}, onStep = {}, listState = listState, contentPadding = PaddingValues(), zone = ZoneOffset.UTC, today = FIXED_TODAY)
                }
            }
        }
        tick(1_000)
        val row = rule.onNodeWithText("Allow once").fetchSemanticsNode().boundsInRoot.height
        rule.runOnIdle { listState.dispatchRawDelta(row * 1.5f) } // the heading stays, the buttons go below the clip
        tick(1_000)
        rule.onNodeWithText("Hermes wants to run a command").assertExists()
        rule.runOnIdle { listState.dispatchRawDelta(-row * 1.5f) }
        rule.waitForIdle()
        rule.onNodeWithText("Allow once").performClick()
        assertTrue("$approvals", approvals.isEmpty())
        tick(300)
        rule.onNodeWithText("Allow once").performClick()
        assertEquals(1, approvals.size)
    }

    @Test
    fun aCancelledDrawerDragRestartsTheInterval() {
        val drawer = DrawerState(DrawerValue.Closed)
        var sheetShown by mutableStateOf(false)
        val rest = floatArrayOf(Float.MAX_VALUE)
        rule.setContent {
            Themed {
                ModalNavigationDrawer(
                    drawerContent = { ModalDrawerSheet(Modifier.onShownInWindow(rest) { sheetShown = it }) { Text("Drawer") } },
                    drawerState = drawer,
                ) {
                    CompositionLocalProvider(LocalObscured provides drawerCovers(drawer, sheetShown, promptOpen = false)) {
                        Box(Modifier.fillMaxSize().testTag("chat")) { ReplyFooter(reply, live(approval), emptyMap(), answering = false, actions) }
                    }
                }
            }
        }
        tick(1_000)
        val resting = drawer.currentOffset
        rule.onNodeWithTag("chat").performTouchInput { // a short, slow pull
            down(Offset(5f, centerY))
            repeat(10) { moveBy(Offset(12f, 0f), delayMillis = 32) }
        }
        rule.waitForIdle()
        assertTrue("the drawer moved: ${drawer.currentOffset} vs $resting", drawer.currentOffset > resting)
        assertEquals("the anchors still say closed", DrawerValue.Closed, drawer.currentValue)
        tick(1_000)
        rule.onNodeWithTag("chat").performTouchInput { repeat(10) { moveBy(Offset(-12f, 0f), delayMillis = 32) }; up() } // back, let go
        rule.waitForIdle()
        assertEquals(DrawerValue.Closed, drawer.currentValue)
        rule.onNodeWithText("Allow once").performClick()
        assertTrue("$approvals", approvals.isEmpty())
        tick(300)
        rule.onNodeWithText("Allow once").performClick()
        assertEquals(1, approvals.size)
    }

    @Test
    fun theRequestsOwnMenuWorksWhileItHoldsTheFocus() {
        var pending by mutableStateOf(approval)
        rule.setContent { Themed { Footer(pending) } }
        tick(1_000)
        for ((label, choice) in listOf("Allow for this chat" to ApprovalChoice.SESSION, "Always allow" to ApprovalChoice.ALWAYS)) {
            hostFocused = true
            rule.onNodeWithContentDescription("More approval options").performClick()
            rule.waitForIdle() // the menu window is shown (and focused) before the chat loses the focus
            hostFocused = false // the menu's window takes the focus
            menuFocused = true
            rule.waitForIdle()
            rule.onNodeWithText(label).performClick()
            rule.waitForIdle()
            assertEquals(choice, approvals.last().third)
            pending = pending.copy(requestId = pending.requestId + "x") // the next request
            hostFocused = true
            rule.waitForIdle()
            tick(1_000)
        }
        assertEquals(2, approvals.size)
        // A request replaced while its menu is open: the new one waits its own interval.
        rule.onNodeWithContentDescription("More approval options").performClick()
        hostFocused = false
        rule.waitForIdle()
        pending = pending.copy(executionGeneration = 9)
        hostFocused = true
        rule.waitForIdle()
        rule.onNodeWithContentDescription("More approval options").performClick()
        hostFocused = false
        rule.waitForIdle()
        rule.onNodeWithText("Always allow").performClick()
        assertEquals(2, approvals.size)
    }
}
