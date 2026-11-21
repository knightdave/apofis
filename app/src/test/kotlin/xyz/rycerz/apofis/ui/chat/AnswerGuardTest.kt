package xyz.rycerz.apofis.ui.chat

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
}
