package xyz.rycerz.apofis.ui.chat

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.api.ApprovalChoice
import xyz.rycerz.apofis.api.PendingControl
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.ChatItem
import xyz.rycerz.apofis.chat.ToolStep
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.run.Connection
import xyz.rycerz.apofis.run.LiveReply
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.FIXED_NOW
import xyz.rycerz.apofis.ui.FIXED_TODAY
import xyz.rycerz.apofis.ui.theme.ApofisTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class PendingRevealTest {
    @get:Rule
    val rule = createComposeRule()

    private val history: List<ChatItem> = (1..20).flatMap {
        listOf(
            UserMessage("u$it", "Question $it", steered = false, timestamp = FIXED_NOW),
            AssistantReply("r$it", emptyList(), (1..6).joinToString("\n\n") { p -> "Answer $it, paragraph $p." }, FIXED_NOW),
        )
    } + UserMessage("u21", "Clean up old images", steered = false, timestamp = FIXED_NOW, runId = "run_1")

    private val steps = List(5) { ToolStep("c$it", "terminal", "command=ls $it", "ok", false, error = false, running = false) }

    private fun state(pending: PendingControl?): ChatUiState {
        val live = LiveReply("run_1", "running", "", steps, pending, Connection.LIVE)
        return ChatUiState("apofis-1", "Images", StatusLine.WORKING, withLive(history, live) { FIXED_NOW }, ComposerMode.STOP, "", "hermes.example", live)
    }

    private fun approval(generation: Long) = PendingControl("approval", "ctl_1", generation, "rm -rf ~/old", null)

    private fun frames(n: Int = 120) = repeat(n) {
        Snapshot.sendApplyNotifications() // state written by the test reaches the composition in the next frame
        rule.mainClock.advanceTimeByFrame()
    }

    private fun scrollAway() {
        repeat(4) {
            rule.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown() }
            frames(60)
        }
        frames(600) // the last fling settles: a reveal never fights the user's own scroll
    }

    @Test
    fun newGenerationIsRevealedWhileScrolledAway() {
        rule.mainClock.autoAdvance = false
        val choices = ArrayList<Triple<String, Long, ApprovalChoice>>()
        val actions = object : ChatActions {
            override fun onApproval(requestId: String, executionGeneration: Long, choice: ApprovalChoice) {
                choices += Triple(requestId, executionGeneration, choice)
            }
        }
        var ui by mutableStateOf(state(null))
        rule.setContent {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                CompositionLocalProvider(LocalAnswerClock provides { rule.mainClock.currentTime }) {
                    ChatScreen(ui, actions, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY)
                }
            }
        }
        frames()
        rule.onNodeWithText("Show 3 more steps").assertIsDisplayed() // the timeline is collapsed
        scrollAway()
        rule.onNodeWithText("Show 3 more steps").assertIsNotDisplayed()

        ui = state(approval(1))
        frames()
        rule.onNodeWithText("Hermes wants to run a command").assertIsDisplayed()

        scrollAway()
        rule.onNodeWithText("Hermes wants to run a command").assertIsNotDisplayed()

        ui = state(approval(2)) // same request id, new generation: a new request
        frames()
        rule.onNodeWithText("Hermes wants to run a command").assertIsDisplayed()
        frames(30) // past the answer guard
        rule.onNodeWithText("Deny").performClick()
        frames(2)
        assertEquals(listOf(Triple("ctl_1", 2L, ApprovalChoice.DENY)), choices)
    }
}
