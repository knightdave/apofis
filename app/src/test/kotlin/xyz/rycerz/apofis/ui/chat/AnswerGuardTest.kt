package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.geometry.Offset
import xyz.rycerz.apofis.ui.drawer.PushDrawer
import xyz.rycerz.apofis.ui.drawer.PushDrawerState
import xyz.rycerz.apofis.ui.drawer.rememberPushDrawerState
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
import androidx.compose.runtime.remember
import xyz.rycerz.apofis.ui.PromptOverChat
import xyz.rycerz.apofis.ui.drawer.Prompt
import xyz.rycerz.apofis.ui.drawer.PromptActions
import xyz.rycerz.apofis.ui.drawer.PromptKind
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.click
import androidx.test.espresso.Espresso
import java.time.ZoneOffset
import xyz.rycerz.apofis.chat.SendState
import xyz.rycerz.apofis.chat.ToolStep
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.ui.FIXED_NOW
import xyz.rycerz.apofis.ui.FIXED_TODAY
import xyz.rycerz.apofis.ui.markdown.dataUrl
import xyz.rycerz.apofis.ui.markdown.pngBytes

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
        tick(1) // the answer clock moves on before the tap: no real tap shares the menu's millisecond
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
        lateinit var drawer: PushDrawerState
        lateinit var scope: CoroutineScope
        rule.setContent {
            Themed {
                drawer = rememberPushDrawerState()
                scope = rememberCoroutineScope()
                PushDrawer(drawer, drawerContent = { Text("Drawer") }) { drag -> Box(drag) { Footer(approval) } }
            }
        }
        tick(1_000)
        rule.runOnIdle { scope.launch { drawer.open() } }
        tick(48) // reduced motion: open in a frame
        rule.runOnIdle { scope.launch { drawer.close() } }
        tick(48) // closed; well inside the 300 ms answer interval, which counts from when the request showed, not from the close
        rule.onNodeWithText("Allow once").performClick() // at once
        assertEquals(1, approvals.size)
    }

    /** A drawer drag that starts on an answer control answers nothing; a fresh tap afterwards answers once. */
    @Test
    fun aDrawerDragOnAnAnswerControlAnswersNothing() {
        rule.setContent {
            Themed {
                PushDrawer(rememberPushDrawerState(), drawerContent = { Text("Drawer") }) { drag -> Box(drag) { Footer(approval) } }
            }
        }
        tick(1_000)
        rule.onNodeWithText("Allow once").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(8f, 0f)) }
            repeat(10) { moveBy(Offset(-8f, 0f)) }
            up()
        }
        tick(400)
        assertTrue("$approvals", approvals.isEmpty())
        rule.onNodeWithText("Allow once").performClick()
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

    // ---- a press begun before a window opened over the chat answers nothing (Task 3; nothing restarts on close) ----

    private var ui by mutableStateOf<ChatUiState?>(null)
    private val tile = hasContentDescription("A dot") and hasClickAction()
    private val step = hasText("terminal") and hasClickAction()

    /** The chat screen with a stored reply (a tool step and a data image) above the live approval. */
    private fun hostChat() {
        val items = listOf(
            UserMessage("u1", "Look", steered = false, timestamp = FIXED_NOW, state = SendState.QUEUED, idempotencyKey = "q1"),
            AssistantReply("r0", listOf(ToolStep("c1", "terminal", "command=ls", "ok", resultTruncated = false, error = false, running = false)), "![A dot](${dataUrl(pngBytes(4, 3))})", FIXED_NOW),
        )
        val live = live(approval)
        ui = ChatUiState("k", "Chat", StatusLine.WORKING, withLive(items, live) { FIXED_NOW }, ComposerMode.STOP, "", "hermes.example", live)
        rule.setContent { Themed { ui?.let { now -> ChatScreen(now, actions, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY, currentState = { now }) } } }
        rule.waitUntil(10_000) { tick(16); exists(tile) } // the image decodes off the main thread; the interval passes meanwhile
        tick(1_000)
    }

    private fun exists(m: SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()

    private fun windows() = rule.onAllNodes(isDialog()).fetchSemanticsNodes().size

    private fun openViewer() {
        rule.onNode(tile).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(10_000) { tick(16); exists(hasContentDescription("Close image")) }
    }

    private fun closeViewer() {
        rule.onNodeWithContentDescription("Close image").performSemanticsAction(SemanticsActions.OnClick)
        tick(48)
        assertEquals(0, windows())
    }

    @Test
    fun viewerBlocksApprovalGuard() {
        hostChat()
        rule.onNodeWithText("Allow once").performTouchInput { down(center) } // well after the interval
        tick(50)
        openViewer()
        closeViewer()
        rule.onNodeWithText("Allow once").performTouchInput { up() }
        settle()
        assertTrue("$approvals", approvals.isEmpty())
    }

    @Test
    fun answerRightAfterViewerClosesGoesThrough() {
        hostChat()
        openViewer()
        closeViewer()
        rule.onNodeWithText("Allow once").performClick() // at once: closing restarts nothing
        assertEquals(listOf(Triple("ctl_1", 1L, ApprovalChoice.ONCE)), approvals)
    }

    @Test
    fun aConfirmDialogBlocksAPressBegunBeforeIt() {
        hostChat()
        rule.onNodeWithText("Allow once").performTouchInput { down(center) }
        tick(50)
        ui = ui!!.copy(confirmCancel = "q1")
        tick(48)
        assertEquals(1, windows())
        ui = ui!!.copy(confirmCancel = null)
        tick(48)
        assertEquals(0, windows())
        rule.onNodeWithText("Allow once").performTouchInput { up() }
        settle()
        assertTrue("$approvals", approvals.isEmpty())
        rule.onNodeWithText("Allow once").performClick()
        assertEquals(1, approvals.size)
    }

    @Test
    fun theToolSheetBlocksAPressBegunBeforeIt() {
        hostChat()
        rule.onNodeWithText("Allow once").performTouchInput { down(center) }
        tick(50)
        rule.onNode(step).performSemanticsAction(SemanticsActions.OnClick)
        tick(1_000)
        assertEquals(1, windows())
        rule.mainClock.autoAdvance = true // the sheet's own hide animation needs frames
        val roots = rule.onAllNodes(isRoot())
        roots[roots.fetchSemanticsNodes().size - 1].performTouchInput { click(Offset(centerX, 10f)) } // a tap on its scrim
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        assertEquals(0, windows())
        rule.onNodeWithText("Allow once").performTouchInput { up() }
        settle()
        assertTrue("$approvals", approvals.isEmpty())
        rule.onNodeWithText("Allow once").performClick()
        assertEquals(1, approvals.size)
    }

    @Test
    fun theBlocksOwnReaderBlocksAPressBegunBeforeIt() {
        rule.setContent { Themed { Footer(approval.copy(command = "x".repeat(ONE_LINE_MAX + 1))) } }
        tick(1_000)
        rule.onNodeWithText("Allow once").performTouchInput { down(center) }
        tick(50)
        rule.onNodeWithText("Show full command").performSemanticsAction(SemanticsActions.OnClick)
        tick(48)
        assertEquals(1, windows())
        rule.onNodeWithContentDescription("Back").performSemanticsAction(SemanticsActions.OnClick)
        tick(48)
        assertEquals(0, windows())
        rule.onNodeWithText("Allow once").performTouchInput { up() }
        settle()
        assertTrue("$approvals", approvals.isEmpty())
        rule.onNodeWithText("Allow once").performClick()
        assertEquals(1, approvals.size)
    }

    // ---- fix round 1: the mark is the answer clock when a window opened; a press dated before it answers nothing ----

    private val longCommand get() = approval.copy(command = "x".repeat(ONE_LINE_MAX + 1))

    private fun openAndCloseTheReader() {
        rule.onNodeWithText("Show full command").performSemanticsAction(SemanticsActions.OnClick)
        tick(48)
        assertEquals(1, windows())
        rule.onNodeWithContentDescription("Back").performSemanticsAction(SemanticsActions.OnClick)
        tick(48)
        assertEquals(0, windows())
    }

    @Test
    fun aPointerDownDatedBeforeTheWindowIsRefusedThoughHandledAfter() {
        rule.setContent { Themed { Footer(longCommand) } }
        tick(1_000)
        lag = 200 // the main thread runs 200 ms behind the events it handles
        openAndCloseTheReader() // marked at the clock: 200 ms past the events of that moment
        rule.onNodeWithText("Allow once").performTouchInput { down(center); up() } // dated before the mark, handled after it
        settle()
        assertTrue("$approvals", approvals.isEmpty())
        lag = 0
        tick(300)
        rule.onNodeWithText("Allow once").performClick()
        assertEquals(1, approvals.size)
    }

    @Test
    fun aKeyDownDatedBeforeTheWindowIsRefusedThoughHandledAfter() {
        rule.setContent { Themed { Footer(longCommand) } }
        tick(1_000)
        lag = 200
        openAndCloseTheReader()
        keyboard()
        rule.onNodeWithText("Allow once").requestFocus()
        settle()
        rule.onNodeWithText("Allow once").performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        settle()
        assertTrue("$approvals", approvals.isEmpty())
        lag = 0
        tick(300)
        rule.onNodeWithText("Allow once").performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        settle()
        assertEquals(1, approvals.size)
    }

    @Test
    fun aKeyHeldWhileAWindowOpensAnswersNothing() {
        rule.setContent { Themed { Footer(longCommand) } }
        tick(1_000)
        keyboard()
        rule.onNodeWithText("Allow once").requestFocus()
        settle()
        rule.onNodeWithText("Allow once").performKeyInput { keyDown(Key.Enter) }
        tick(50)
        openAndCloseTheReader()
        rule.onNodeWithText("Allow once").performKeyInput { keyUp(Key.Enter) }
        settle()
        assertTrue("$approvals", approvals.isEmpty())
        rule.onNodeWithText("Allow once").performKeyInput { keyDown(Key.Enter); keyUp(Key.Enter) }
        settle()
        assertEquals(1, approvals.size)
    }

    @Test
    fun aPressHeldWhileTheBlocksMenuOpensAnswersNothing() {
        rule.setContent { Themed { Footer(approval) } }
        tick(1_000)
        rule.onNodeWithText("Allow once").performTouchInput { down(center) }
        tick(50)
        rule.onNodeWithContentDescription("More approval options").performSemanticsAction(SemanticsActions.OnClick)
        tick(48)
        assertTrue(exists(hasText("Always allow")))
        rule.onNodeWithText("Allow once").performTouchInput { up() }
        settle()
        assertTrue("$approvals", approvals.isEmpty())
        rule.onNodeWithText("Allow once").performClick()
        assertEquals(1, approvals.size)
    }

    /** The drawer's session actions sheet over the chat (AppNav's prompt host); another kind of prompt is another showing. */
    @Test
    fun aPressHeldAcrossTheSessionActionsSheetAnswersNothing() {
        lateinit var drawer: PushDrawerState
        lateinit var scope: CoroutineScope
        var prompt by mutableStateOf<Prompt?>(null)
        rule.setContent {
            Themed {
                CompositionLocalProvider(LocalOverlays provides remember { Overlays() }) {
                    drawer = rememberPushDrawerState()
                    scope = rememberCoroutineScope()
                    PushDrawer(drawer, drawerContent = { Text("Drawer") }) { drag -> Box(drag) { Footer(approval) } }
                    prompt?.let { PromptOverChat(it, object : PromptActions {}) }
                }
            }
        }
        val actions = Prompt(PromptKind.ACTIONS, "k", "Chat", pinned = false, archived = false, running = false, token = 1)
        tick(1_000)
        rule.onNodeWithText("Allow once").performTouchInput { down(center) }
        tick(50)
        rule.runOnIdle { scope.launch { drawer.open() } }
        tick(48)
        prompt = actions
        tick(48)
        prompt = null
        tick(48)
        rule.runOnIdle { scope.launch { drawer.close() } }
        tick(48)
        rule.onNodeWithText("Allow once").performTouchInput { up() }
        settle()
        assertTrue("$approvals", approvals.isEmpty())
        rule.onNodeWithText("Allow once").performClick() // at once
        assertEquals(1, approvals.size)
        // A press begun while the sheet shows, released after the prompt turned into the Rename dialog: another showing.
        prompt = actions
        tick(48)
        rule.onNodeWithText("Allow once").performTouchInput { down(center) }
        tick(50)
        prompt = actions.copy(kind = PromptKind.RENAME)
        tick(48)
        prompt = null
        tick(48)
        rule.onNodeWithText("Allow once").performTouchInput { up() }
        settle()
        assertEquals(1, approvals.size)
    }

    // ---- closing commit: the menu is marked when it shows; a prompt by its kind ----

    /** More asked at t, another answer's down at t + 5, the menu shows at t + 16: that press answers nothing. */
    @Test
    fun aDownBetweenMoreAndTheMenuShowingAnswersNothing() {
        val overlays = Overlays()
        rule.setContent { Themed { CompositionLocalProvider(LocalOverlays provides overlays) { Footer(approval) } } }
        tick(1_000)
        rule.onNodeWithContentDescription("More approval options").performSemanticsAction(SemanticsActions.OnClick) // asked
        rule.mainClock.advanceTimeBy(5)
        val down = rule.mainClock.currentTime
        rule.onNodeWithText("Allow once").performTouchInput { down(center) }
        tick(16)
        assertTrue(exists(hasText("Always allow")))
        assertTrue("shown at ${overlays.openedAt}, after the down at $down", overlays.openedAt > down)
        rule.mainClock.autoAdvance = true // the popup's own dismissal needs frames
        Espresso.pressBack()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        assertTrue("the menu closed", !exists(hasText("Always allow")))
        rule.onNodeWithText("Allow once").performTouchInput { up() }
        settle()
        assertTrue("$approvals", approvals.isEmpty())
    }

    @Test
    fun aMenuItemPressedAfterTheMenuShowedAnswers() {
        rule.setContent { Themed { Footer(approval) } }
        tick(1_000)
        rule.onNodeWithContentDescription("More approval options").performSemanticsAction(SemanticsActions.OnClick)
        tick(16) // shows
        tick(16)
        rule.onNodeWithText("Always allow").performClick()
        assertEquals(listOf(Triple("ctl_1", 1L, ApprovalChoice.ALWAYS)), approvals)
    }

    /** A drawer prompt marks when it shows and when its kind changes; a new token for the same dialog does not. */
    @Test
    fun aPromptIsMarkedByItsKindNotItsToken() {
        val overlays = Overlays()
        val actions = Prompt(PromptKind.ACTIONS, "k", "Chat", pinned = false, archived = false, running = false, token = 1)
        var prompt by mutableStateOf<Prompt?>(actions)
        rule.setContent { Themed { CompositionLocalProvider(LocalOverlays provides overlays) { prompt?.let { PromptOverChat(it, object : PromptActions {}) } } } }
        tick(48)
        val shown = overlays.openedAt
        assertTrue(shown > Long.MIN_VALUE)
        tick(100)
        prompt = actions.copy(kind = PromptKind.DELETE, token = 2)
        tick(48)
        val delete = overlays.openedAt
        assertTrue("a kind change moves it", delete > shown)
        tick(100)
        prompt = actions.copy(kind = PromptKind.DELETE, token = 3, running = true) // the reworded confirmation
        tick(48)
        prompt = prompt!!.copy(busy = true) // a plain update
        tick(48)
        assertEquals("the same dialog: no move", delete, overlays.openedAt)
        prompt = null
        tick(48)
        prompt = actions.copy(kind = PromptKind.DELETE, token = 4) // shown again after it was gone
        tick(48)
        assertTrue(overlays.openedAt > delete)
    }
}
