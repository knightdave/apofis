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
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.requestFocus
import org.junit.Before
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
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
 * answer. The test main clock (which also times injected input) drives it.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class AnswerGuardTest {
    @get:Rule
    val rule = createComposeRule()

    /**
     * The uptime clock: the test's main clock, which also times injected input events, plus [lag]: how far the main
     * thread is behind (the clock reads later than the events it is handling).
     */
    private var lag = 0L

    @Before
    fun manualClock() {
        rule.mainClock.autoAdvance = false
    }
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
            CompositionLocalProvider(LocalAnswerClock provides { rule.mainClock.currentTime + lag }, content = content)
        }

    private val approval = PendingControl("approval", "ctl_1", 1, "rm -rf ~/old", null)
    private val clarify = PendingControl("clarify", "ctl_q", 4, null, "Which folder?")
    private val reply = AssistantReply("live", emptyList(), "Checking.", 0.0, live = true)
    private fun live(pending: PendingControl?) = LiveReply("run_1", "running", "Checking.", emptyList(), pending, Connection.LIVE)

    private lateinit var inputMode: InputModeManager

    @Composable
    private fun Footer(pending: PendingControl?) {
        inputMode = LocalInputModeManager.current
        ReplyFooter(reply, live(pending), emptyMap(), answering = false, actions)
    }

    /** Key presses need keyboard input mode for the controls to take focus. */
    private fun keyboard() = rule.runOnUiThread { inputMode.requestInputMode(InputMode.Keyboard) }

    private fun tick(ms: Long) {
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeBy(ms)
    }

    private fun settle() = tick(16)

    @Test
    fun aClarifyAnswerTypedEarlyIsKeptAndSentOnceAfterTheInterval() {
        // Injected input moves the clock on by itself (16 ms per event), so "early" and "late" keep a margin.
        rule.setContent { Themed { Footer(clarify) } }
        rule.onNodeWithText("Your answer").performTextInput("Downloads")
        rule.onNodeWithContentDescription("Send answer").performClick() // too early: nothing, and nothing used up
        tick(150)
        rule.onNodeWithContentDescription("Send answer").performClick()
        assertTrue("$answers", answers.isEmpty())
        tick(200)
        rule.onNodeWithContentDescription("Send answer").performClick() // the typed answer is still there
        assertEquals(listOf(Triple("ctl_q", 4L, "Downloads")), answers)
    }

    @Test
    fun theSameRequestShownAgainWaitsAgain() {
        var pending by mutableStateOf<PendingControl?>(approval)
        rule.setContent { Themed { Footer(pending) } }
        tick(1_000)
        pending = null
        settle()
        pending = approval // the same id and generation, shown again
        settle()
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
        rule.mainClock.autoAdvance = true // recreating the screen needs frames
        tester.emulateSavedInstanceStateRestore()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithText("Allow once").performClick()
        assertTrue("$approvals", approvals.isEmpty())
        tick(300)
        rule.onNodeWithText("Allow once").performClick()
        assertEquals(1, approvals.size)
    }

    @Test
    fun aPressBegunBeforeTheIntervalEndsNeverAnswersEvenIfReleasedAfter() {
        rule.setContent { Themed { Footer(approval) } }
        tick(200)
        rule.onNodeWithText("Allow once").performTouchInput { down(center) } // begun early
        tick(200)
        rule.onNodeWithText("Allow once").performTouchInput { up() } // released late
        settle()
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
        settle()
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
        settle()
        rule.onNodeWithText("Always allow").performClick() // the menu opened at once: too early
        assertTrue("$approvals", approvals.isEmpty())
        tick(300)
        rule.onNodeWithContentDescription("More approval options").performClick()
        settle()
        rule.onNodeWithText("Allow for this chat").performClick()
        assertEquals(listOf(Triple("ctl_1", 1L, ApprovalChoice.SESSION)), approvals)
    }

    // ---- once the interval has passed, nothing else blocks an answer ----

    @Test
    @Config(qualifiers = "w640dp-h220dp-xhdpi")
    fun inAShortWindowTheClarifySendGoesThrough() {
        rule.setContent { Themed { Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { Footer(clarify) } } }
        rule.mainClock.autoAdvance = true // scrolling needs frames; the interval passes on the way
        rule.onNodeWithText("Your answer").performScrollTo().performTextInput("Downloads") // the keyboard would be open
        rule.onNodeWithContentDescription("Send answer").performScrollTo()
        rule.mainClock.autoAdvance = false
        tick(300)
        rule.onNodeWithContentDescription("Send answer").performClick()
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
        settle()
        rule.runOnIdle { scope.launch { drawer.close() } }
        settle()
        rule.onNodeWithText("Allow once").performClick() // at once
        assertEquals(1, approvals.size)
    }

    // ---- fix round 5: a press is timed by its own down event and belongs to the activation it produces ----

    @Test
    fun aSecondTapDelayedInDispatchNeverLooksLaterThanItWas() {
        rule.setContent { Themed { Footer(approval) } }
        settle()
        tick(84) // the tap physically starts about 100 ms after the request appeared
        lag = 210 // but the busy main thread handles it 210 ms later: the clock already reads past the interval
        rule.onNodeWithText("Allow once").performTouchInput { down(center); up() }
        settle()
        assertTrue("$approvals", approvals.isEmpty())
        lag = 0
        tick(300)
        rule.onNodeWithText("Allow once").performClick()
        assertEquals(listOf(Triple("ctl_1", 1L, ApprovalChoice.ONCE)), approvals)
    }

    @Test
    fun anAccessibilityClickDuringAnEarlyHoldIsJudgedOnItsOwnAndTheHoldStaysEarly() {
        rule.setContent { Themed { Footer(approval) } }
        tick(200)
        rule.onNodeWithText("Allow once").performTouchInput { down(center) } // held from about 200
        tick(150)
        rule.onNodeWithText("Allow once").performSemanticsAction(SemanticsActions.OnClick) // at about 370: its own decision
        assertEquals(listOf(Triple("ctl_1", 1L, ApprovalChoice.ONCE)), approvals)
        tick(10)
        rule.onNodeWithText("Allow once").performTouchInput { up() } // the early hold ends: nothing more
        settle()
        assertEquals(1, approvals.size)
    }

    @Test
    fun twoKeysHeldEarlyAnswerNothingWhenReleasedLate() {
        rule.setContent { Themed { Footer(approval) } }
        settle()
        val once = rule.onNodeWithText("Allow once")
        keyboard()
        once.requestFocus()
        settle()
        once.performKeyInput { keyDown(Key.Enter) }
        tick(50)
        once.performKeyInput { keyDown(Key.Spacebar) }
        tick(400)
        once.performKeyInput { keyUp(Key.Enter) }
        settle()
        once.performKeyInput { keyUp(Key.Spacebar) }
        settle()
        assertTrue("$approvals", approvals.isEmpty())
        once.performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) } // a fresh key press
        settle()
        assertEquals(1, approvals.size)
    }

    @Test
    fun aDownBeforeTheReplacementNeverValidatesAnActivationAfterIt() {
        var pending by mutableStateOf(approval)
        rule.setContent { Themed { Footer(pending) } }
        tick(1_000)
        rule.onNodeWithText("Allow once").performTouchInput { down(center) } // a press on the old request
        pending = approval.copy(requestId = "ctl_2")
        settle()
        rule.onNodeWithText("Allow once").performSemanticsAction(SemanticsActions.OnClick) // judged on arrival: early
        keyboard()
        rule.onNodeWithText("Allow once").requestFocus()
        settle()
        rule.onNodeWithText("Allow once").performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) } // its own key press: early
        settle()
        assertTrue("$approvals", approvals.isEmpty())
        tick(300)
        rule.onNodeWithText("Allow once").performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf(Triple("ctl_2", 1L, ApprovalChoice.ONCE)), approvals)
    }

    @Test
    fun aCancelledPressLeavesNothingBehind() {
        rule.setContent { Themed { Footer(approval) } }
        settle()
        tick(100)
        rule.onNodeWithText("Deny").performTouchInput { down(center); cancel() } // an early press, cancelled
        tick(300)
        rule.onNodeWithText("Deny").performSemanticsAction(SemanticsActions.OnClick) // judged on arrival, not by the old down
        assertEquals(1, approvals.size)
        rule.onNodeWithText("Deny").performClick() // a fresh pointer press, in time
        assertEquals(2, approvals.size)
    }
}
