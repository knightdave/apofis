package xyz.rycerz.apofis.ui.chat

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
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

/** The tool detail sheet follows its step: it updates across the live-to-stored swap and closes when the step is gone. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class ToolSheetTest {
    @get:Rule
    val rule = createComposeRule()

    private val user = UserMessage("u1", "List files", steered = false, timestamp = FIXED_NOW, runId = "run_1")
    private val stored = AssistantReply("r2", emptyList(), "", FIXED_NOW)
    private val liveStep = ToolStep("c1", "terminal", "command=ls", null, false, error = false, running = true)
    private val storedStep = liveStep.copy(result = "a.txt", running = false, args = "{\n  \"command\": \"ls\"\n}")

    private fun liveState(): ChatUiState {
        val live = LiveReply("run_1", "running", "", listOf(liveStep), null, Connection.LIVE)
        return ChatUiState("apofis-1", "Files", StatusLine.WORKING, withLive(listOf(user, stored), live) { FIXED_NOW }, ComposerMode.STOP, "", "h", live)
    }

    private fun storedState(items: List<ChatItem>) = ChatUiState("apofis-1", "Files", StatusLine.IDLE, items, ComposerMode.SEND_DISABLED, "", "h")

    private val args = "Arguments"
    private fun sheetShown() = rule.onAllNodes(hasText(args)).fetchSemanticsNodes().size

    @Test
    fun sheetFollowsTheSwapAndClosesWhenTheStepIsGone() {
        var ui by mutableStateOf(liveState())
        rule.setContent {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                ChatScreen(ui, object : ChatActions {}, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY)
            }
        }
        rule.onNodeWithTag("step:r2:0:terminal").performClick()
        rule.waitForIdle()
        assertEquals(1, sheetShown())
        rule.onNodeWithText("Shortened. The full arguments are available after the reply finishes.").assertExists()

        ui = storedState(listOf(user, stored.copy(steps = listOf(storedStep), text = "Done.")))
        rule.waitForIdle()
        assertEquals("the stored step keeps the sheet open", 1, sheetShown())
        rule.onNodeWithText("a.txt").assertExists()
        assertEquals(0, rule.onAllNodes(hasText("Shortened. The full arguments are available after the reply finishes.")).fetchSemanticsNodes().size)

        ui = storedState(listOf(user, stored.copy(steps = listOf(storedStep.copy(callId = "c9")), text = "Done.")))
        rule.waitForIdle()
        assertEquals("a replaced step closes its sheet", 0, sheetShown())
    }

    @Test
    fun configurationChangeClosesTheSheetCleanly() {
        val tester = StateRestorationTester(rule)
        val ui = storedState(listOf(user, stored.copy(steps = listOf(storedStep), text = "Done.")))
        tester.setContent {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                ChatScreen(ui, object : ChatActions {}, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY)
            }
        }
        rule.onNodeWithTag("step:r2:0:terminal").performClick()
        rule.waitForIdle()
        assertEquals(1, sheetShown())
        tester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals(0, sheetShown())
        rule.onNodeWithTag("step:r2:0:terminal").performClick() // and it opens again
        rule.waitForIdle()
        assertEquals(1, sheetShown())
    }
}
