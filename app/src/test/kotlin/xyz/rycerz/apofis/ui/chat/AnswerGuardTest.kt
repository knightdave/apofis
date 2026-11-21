package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
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
import xyz.rycerz.apofis.run.Connection
import xyz.rycerz.apofis.run.LiveReply
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.theme.ApofisTheme

/**
 * The answer guard: a request accepts an answer only from a press that began at least the double-tap interval
 * (300 ms) after the block first showed it or it changed, and only for that same request. Nothing else blocks an
 * answer. A manual clock ([now]) drives it.
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
    private val clarify = PendingControl("clarify", "ctl_q", 4, null, "Which folder?")
    private val reply = AssistantReply("live", emptyList(), "Checking.", 0.0, live = true)
    private fun live(pending: PendingControl?) = LiveReply("run_1", "running", "Checking.", emptyList(), pending, Connection.LIVE)

    @Composable
    private fun Footer(pending: PendingControl?) = ReplyFooter(reply, live(pending), emptyMap(), answering = false, actions)

    private fun tick(ms: Long) {
        now += ms
        rule.waitForIdle()
    }

    @Test
    fun aClarifyAnswerTypedEarlyIsKeptAndSentOnceAfterTheInterval() {
        rule.setContent { Themed { Footer(clarify) } }
        rule.onNodeWithText("Your answer").performTextInput("Downloads")
        rule.onNodeWithContentDescription("Send answer").performClick() // too early: nothing, and nothing used up
        tick(299)
        rule.onNodeWithContentDescription("Send answer").performClick()
        assertTrue("$answers", answers.isEmpty())
        tick(1)
        rule.onNodeWithContentDescription("Send answer").performClick() // the typed answer is still there
        assertEquals(listOf(Triple("ctl_q", 4L, "Downloads")), answers)
    }

    @Test
    fun theSameRequestShownAgainWaitsAgain() {
        var pending by mutableStateOf<PendingControl?>(approval)
        rule.setContent { Themed { Footer(pending) } }
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
        tester.setContent { Themed { Footer(approval) } }
        tick(1_000)
        tester.emulateSavedInstanceStateRestore()
        rule.onNodeWithText("Allow once").performClick()
        assertTrue("$approvals", approvals.isEmpty())
        tick(300)
        rule.onNodeWithText("Allow once").performClick()
        assertEquals(1, approvals.size)
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
    fun oneControlsEarlyPressNeverValidatesAnothersAnswer() {
        rule.setContent { Themed { Footer(approval) } }
        tick(100)
        rule.onNodeWithText("Deny").performTouchInput { down(center) } // early, still held
        tick(300)
        rule.onNodeWithText("Allow once").performSemanticsAction(SemanticsActions.OnClick) // its own activation, in time
        rule.onNodeWithText("Deny").performTouchInput { up() } // the early press ends: it answers nothing
        rule.waitForIdle()
        assertEquals(listOf(Triple("ctl_1", 1L, ApprovalChoice.ONCE)), approvals)
    }

    @Test
    fun anAccessibilityClickIsJudgedWhenItArrives() {
        rule.setContent { Themed { Footer(approval) } }
        rule.onNodeWithText("Deny").performSemanticsAction(SemanticsActions.OnClick)
        assertTrue("$approvals", approvals.isEmpty())
        tick(300)
        rule.onNodeWithText("Deny").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(1, approvals.size)
    }

    @Test
    fun menuItemsFollowTheSameRuleWithTheirOwnPress() {
        rule.setContent { Themed { Footer(approval) } }
        rule.onNodeWithContentDescription("More approval options").performClick()
        rule.onNodeWithText("Always allow").performClick() // the menu opened at once: too early
        assertTrue("$approvals", approvals.isEmpty())
        tick(300)
        rule.onNodeWithContentDescription("More approval options").performClick()
        rule.onNodeWithText("Allow for this chat").performClick()
        assertEquals(listOf(Triple("ctl_1", 1L, ApprovalChoice.SESSION)), approvals)
    }

    // ---- once the interval has passed, nothing else blocks an answer ----

    @Test
    @Config(qualifiers = "w640dp-h220dp-xhdpi")
    fun inAShortWindowTheClarifySendGoesThrough() {
        rule.setContent { Themed { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { Footer(clarify) } } }
        rule.onNodeWithText("Your answer").performScrollTo().performTextInput("Downloads") // the keyboard would be open
        tick(300)
        rule.onNodeWithContentDescription("Send answer").performScrollTo().performClick()
        assertEquals(listOf(Triple("ctl_q", 4L, "Downloads")), answers)
    }

    @Test
    @Config(qualifiers = "w320dp-h400dp-xhdpi")
    fun inASplitScreenPaneAnApprovalGoesThrough() {
        rule.setContent { Themed { Footer(approval) } }
        tick(300)
        rule.onNodeWithText("Allow once").performClick()
        assertEquals(1, approvals.size)
    }

    @Test
    fun rightAfterTheDrawerClosesAnApprovalGoesThrough() {
        val drawer = DrawerState(DrawerValue.Closed)
        lateinit var scope: CoroutineScope
        rule.setContent {
            Themed {
                scope = rememberCoroutineScope()
                ModalNavigationDrawer(drawerContent = { ModalDrawerSheet { Text("Drawer") } }, drawerState = drawer) { Footer(approval) }
            }
        }
        tick(1_000)
        rule.runOnIdle { scope.launch { drawer.open() } }
        rule.waitForIdle()
        rule.runOnIdle { scope.launch { drawer.close() } }
        rule.waitForIdle()
        rule.onNodeWithText("Allow once").performClick() // at once
        assertEquals(1, approvals.size)
    }
}

