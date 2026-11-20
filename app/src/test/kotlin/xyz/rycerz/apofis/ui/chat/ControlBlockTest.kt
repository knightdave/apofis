package xyz.rycerz.apofis.ui.chat

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.Role
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
        ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false, content = content)

    private fun same(a: ImageBitmap, b: ImageBitmap) = a.toPixelMap().buffer.contentEquals(b.toPixelMap().buffer)

    @Test
    fun answersCarryTheShownRequestAndAReplacementResetsTheBlock() {
        var pending by mutableStateOf(PendingControl("approval", "ctl_1", 1, "rm -rf ~/old", null))
        rule.setContent { Themed { ReplyFooter(reply, live(pending), emptyMap(), answering = false, actions) } }
        rule.onNodeWithText("Hermes wants to run a command").assert(isHeading())
        rule.onNodeWithText("Allow once").performClick()
        assertEquals(Triple("ctl_1", 1L, ApprovalChoice.ONCE), choices.last())

        rule.onNodeWithContentDescription("More approval options").performClick()
        rule.onNodeWithText("Always allow").assertExists()
        pending = pending.copy(executionGeneration = 2, command = "rm -rf ~/older")
        rule.waitForIdle()
        assertEquals("an open menu never carries over to a new request", 0, rule.onAllNodesWithTextCount("Always allow"))
        rule.onNodeWithText("rm -rf ~/older").assertExists()
        rule.onNodeWithText("Deny").performClick()
        assertEquals(Triple("ctl_1", 2L, ApprovalChoice.DENY), choices.last())

        pending = PendingControl("approval", "ctl_2", 2, "ls", null)
        rule.waitForIdle()
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
        rule.onNodeWithText("Deny").assertExists()
        assertEquals(0, rule.onAllNodesWithTextCount("Allow once"))
        assertEquals(0, rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ContentDescription) and hasClickAction()).fetchSemanticsNodes().size)
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
}
