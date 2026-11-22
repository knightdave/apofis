package xyz.rycerz.apofis.ui.drawer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.SoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.getOrNull
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotSame
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
import xyz.rycerz.apofis.chat.ChatItem
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.run.Connection
import xyz.rycerz.apofis.run.LiveReply
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.DrawerButton
import xyz.rycerz.apofis.ui.NewChatButton
import xyz.rycerz.apofis.ui.FIXED_NOW
import xyz.rycerz.apofis.ui.FIXED_TODAY
import xyz.rycerz.apofis.ui.closingOnSelect
import xyz.rycerz.apofis.ui.chat.ChatActions
import xyz.rycerz.apofis.ui.chat.ChatScreen
import xyz.rycerz.apofis.ui.chat.ChatUiState
import xyz.rycerz.apofis.ui.chat.ChromeProbe
import xyz.rycerz.apofis.ui.chat.ComposerMode
import xyz.rycerz.apofis.ui.chat.LocalAnswerClock
import xyz.rycerz.apofis.ui.chat.StatusLine
import xyz.rycerz.apofis.ui.chat.withLive
import xyz.rycerz.apofis.ui.theme.ApofisIconButton
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.Haptics
import xyz.rycerz.apofis.ui.theme.LocalHaptics
import xyz.rycerz.apofis.ui.theme.PulseProbe
import xyz.rycerz.apofis.ui.theme.sidewaysScroll

/**
 * The push drawer driven by touch, keys, Back and accessibility on a manual clock stepped in whole frames. Pointers
 * that must stay down across frames are held across separate injections. The window is 360 dp at 2x, so the panel is
 * 270 dp, 540 px. No blur is drawn (a blurred draw can blank later captures).
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class DrawerUiTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    private val panel = 540f
    private lateinit var state: PushDrawerState
    private lateinit var scope: CoroutineScope
    private lateinit var inputMode: InputModeManager
    private var openings = 0
    private val button = FocusRequester()

    private class RecordingHaptics : Haptics {
        var selections = 0
        var lights = 0
        override fun light() {
            lights++
        }
        override fun selection() {
            selections++
        }
    }

    private val haptics = RecordingHaptics()
    private val list = LazyListState()

    private val items: List<ChatItem> = (1..30).flatMap {
        listOf(
            UserMessage("u$it", "Question $it", steered = false, timestamp = FIXED_NOW),
            AssistantReply("r$it", emptyList(), "Answer $it, long enough to wrap onto a second line of the chat screen.", FIXED_NOW),
        )
    }
    private val chat = ChatUiState("apofis-1", "Drawer", StatusLine.IDLE, items, ComposerMode.SEND, "Some typed text in the composer", "hermes.example")

    private fun withPending(pending: PendingControl): ChatUiState {
        val live = LiveReply("run_1", "running", "Checking.", emptyList(), pending, Connection.LIVE)
        return chat.copy(items = withLive(items.take(4), live) { FIXED_NOW }, live = live)
    }

    private val approval = PendingControl("approval", "ctl_1", 1, "rm -rf ~/old", null)
    private val clarify = PendingControl("clarify", "ctl_q", 4, null, "Which folder?")

    private val approvals = ArrayList<ApprovalChoice>()
    private var sends = 0
    private val recording = object : ChatActions {
        override fun onApproval(requestId: String, executionGeneration: Long, choice: ApprovalChoice) {
            approvals += choice
        }
        override fun onSendClick() {
            sends++
        }
    }
    private var shownChat by mutableStateOf(chat)
    private var newChats = 0

    private val rows = listOf(
        DrawerRow.Header(GroupLabel.Today),
        DrawerRow.Chat("apofis-1", "Largest files", selected = true, running = false, needsInput = false),
        DrawerRow.Chat("apofis-2", "Garden beds", selected = false, running = false, needsInput = false),
    )

    private fun drawerOf(rows: List<DrawerRow>, actions: DrawerActions = object : DrawerActions {}, onSettings: () -> Unit = {}): @Composable () -> Unit = {
        DrawerContent(rows, "", false, hasArchived = false, actions = actions, onQuery = {}, onNewChat = {}, onArchived = {}, onSettings = onSettings)
    }

    /** The chat as the app wires it: the drag area, and `covered` from the drawer not being fully closed. */
    private val chatContent: @Composable (Modifier) -> Unit = { drag ->
        ChatScreen(
            shownChat, recording, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY, listState = list, dragArea = drag,
            covered = state.covered,
            coveredNow = { state.covered },
            navigationIcon = {
                Row {
                    ApofisIconButton({}, contentDescription = "Before") {} // first in focus order: automatic focus lands here, not on the button
                    DrawerButton(Modifier.focusRequester(button)) { scope.launch { state.open() } }
                }
            },
            appBarActions = { NewChatButton { newChats++ } },
        )
    }

    private fun show(
        initial: PushDrawerValue = PushDrawerValue.Closed,
        reduceMotion: Boolean = false,
        direction: LayoutDirection = LayoutDirection.Ltr,
        fontScale: Float = 1f,
        drawer: @Composable () -> Unit = drawerOf(rows),
        content: @Composable (Modifier) -> Unit = chatContent,
    ) {
        rule.mainClock.autoAdvance = false
        rule.setContent { Drawer(initial, reduceMotion, direction, fontScale, drawer, content) }
        frames(2)
    }

    @Composable
    private fun Drawer(initial: PushDrawerValue, reduceMotion: Boolean, direction: LayoutDirection, fontScale: Float, drawer: @Composable () -> Unit, content: @Composable (Modifier) -> Unit) {
        val density = LocalDensity.current
        ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = reduceMotion, blur = false) {
            CompositionLocalProvider(
                LocalHaptics provides haptics,
                LocalLayoutDirection provides direction,
                LocalDensity provides Density(density.density, fontScale),
                LocalAnswerClock provides { rule.mainClock.currentTime },
                LocalSoftwareKeyboardController provides keyboard,
            ) {
                state = rememberPushDrawerState(initial)
                scope = rememberCoroutineScope()
                inputMode = LocalInputModeManager.current
                PushDrawer(state, drawer, onOpening = { openings++ }, restoreFocus = button, content = content)
            }
        }
    }

    private var hides = 0
    private val keyboard = object : SoftwareKeyboardController {
        override fun show() = Unit
        override fun hide() {
            hides++
        }
    }

    /** [n] whole frames; test-thread state writes are announced first, as the manual clock does not. */
    private fun frames(n: Int = 1) = repeat(n) {
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeByFrame()
    }

    /** Longer than a settle (250 ms) plus the frame the settle rule waits. */
    private fun settled() = frames(30)

    /** Down at [from] (default the middle), [moves] moves of [dx] px in all, each a 16 ms event; held unless [release]. */
    private fun drag(dx: Float, moves: Int, node: SemanticsNodeInteraction = rule.onRoot(), from: (TouchInjectionScope.() -> Offset)? = null, release: Boolean = true) {
        node.performTouchInput {
            down(from?.invoke(this) ?: center)
            repeat(moves) { moveBy(Offset(dx / moves, 0f)) }
            if (release) up()
        }
    }

    private fun up(node: SemanticsNodeInteraction = rule.onRoot()) = node.performTouchInput { up() }

    private fun code(): (TouchInjectionScope.() -> Offset) = { Offset(width - 40f, centerY) }

    private fun runCode(block: suspend () -> Unit) = rule.runOnIdle { scope.launch { block() } }

    private fun assertClosed() {
        assertFalse(state.isOpen)
        assertEquals(0f, state.fraction)
    }

    private fun assertOpen() {
        assertTrue(state.isOpen)
        assertEquals(1f, state.fraction)
    }

    /** [n] frames, asserting the drawer stays shut on each. */
    private fun shutFor(n: Int) = repeat(n) {
        frames()
        assertEquals("the drawer moved on frame $it", 0f, state.fraction)
    }

    private fun backEnabled(): Boolean {
        var enabled = false
        rule.runOnUiThread { enabled = rule.activity.onBackPressedDispatcher.hasEnabledCallbacks() }
        return enabled
    }

    // ---- settle rules ----

    @Test
    fun dragPastHalfOpens() {
        show()
        drag(0.6f * panel, 40) // about 265 dp/s: slow
        settled()
        assertOpen()
        assertEquals(panel, state.width)
        assertEquals(270.dp, rule.onNodeWithTag("drawer.scrim").getBoundsInRoot().left) // the content is pushed by the panel width
        assertEquals(0.dp, rule.onNodeWithTag("drawer.panel").getBoundsInRoot().left)
    }

    @Test
    fun shortSlowDragSnapsBack() {
        show()
        drag(0.3f * panel, 40)
        settled()
        assertClosed()
        assertEquals(0, openings)
    }

    @Test
    fun flingOpensAndCloses() {
        show()
        drag(0.2f * panel, 4) // about 1,700 dp/s
        settled()
        assertOpen()
        drag(-0.2f * panel, 4, rule.onNodeWithTag("drawer.scrim"), from = code())
        settled()
        assertClosed()
    }

    @Test
    fun rightToLeftComesFromTheStartEdge() {
        show(direction = LayoutDirection.Rtl)
        drag(-0.2f * panel, 4) // a fling toward the start edge's opposite side opens
        settled()
        assertOpen()
        assertEquals(90.dp, rule.onNodeWithTag("drawer.scrim").getBoundsInRoot().right) // pushed toward the left
        assertEquals(360.dp, rule.onNodeWithTag("drawer.panel").getBoundsInRoot().right)
        drag(0.2f * panel, 4, rule.onNodeWithTag("drawer.scrim"), from = { Offset(40f, centerY) }) // a fling back to the start closes
        settled()
        assertClosed()
        drag(-0.6f * panel, 40)
        settled()
        assertOpen()
        drag(0.6f * panel, 40, rule.onNodeWithTag("drawer.panel"))
        settled()
        assertClosed()
    }

    @Test
    fun reversedPartialSwipe() {
        show()
        rule.onRoot().performTouchInput {
            down(center)
            repeat(40) { moveBy(Offset(0.7f * panel / 40, 0f)) }
            repeat(40) { moveBy(Offset(-0.5f * panel / 40, 0f)) }
            up()
        }
        settled()
        assertClosed()
    }

    // ---- Ruling A: only a direct drag or code moves the drawer ----

    private fun codeRow(code: ScrollState, contentWidth: Int): @Composable (Modifier) -> Unit = { drag ->
        Box(Modifier.fillMaxSize().then(drag)) {
            Row(Modifier.align(Alignment.Center).fillMaxWidth().sidewaysScroll(code).testTag("code")) { Box(Modifier.width(contentWidth.dp).height(100.dp)) }
        }
    }

    /** A code row that scrolls keeps every drag, release and fling, also at its start edge; the drawer never moves. */
    @Test
    fun scrollingCodeBlockKeepsItsGestures() {
        val code = ScrollState(0)
        show(drawer = drawerOf(emptyList()), content = codeRow(code, 450))
        assertEquals(180, code.maxValue)
        val row = rule.onNodeWithTag("code")
        drag(0.6f * panel, 30, row, release = false) // at its start, outward: slow, held
        shutFor(5)
        up(row)
        shutFor(30)
        drag(0.3f * panel, 3, row) // a fast release at its start
        shutFor(30)
        runCode { code.scrollTo(code.maxValue) }
        frames(2)
        drag(0.3f * panel, 3, row) // a fling back to its start, the leftover going nowhere
        shutFor(40)
        assertEquals("the fling did not return the row to its start", 0, code.value)
        assertEquals(0, openings)
        assertClosed()
    }

    /** A code row whose content fits takes no drag: the drawer moves and settles by the 365 dp/s and half rules. */
    @Test
    fun fittingCodeBlockLetsTheDrawerMove() {
        val code = ScrollState(0)
        show(drawer = drawerOf(emptyList()), content = codeRow(code, 300))
        assertEquals(0, code.maxValue)
        val row = rule.onNodeWithTag("code")
        drag(0.3f * panel, 40, row, release = false)
        frames()
        assertTrue("the drawer did not follow the drag", state.fraction > 0.2f)
        up()
        settled()
        assertClosed() // slow, under half
        drag(0.2f * panel, 4, row) // fast
        settled()
        assertOpen()
    }

    /** An accessibility scroll of a code row (one viewport, more than it can scroll) never reaches the drawer. */
    @Test
    fun accessibilityScrollOfACodeBlockLeavesTheDrawerShut() {
        val code = ScrollState(0)
        show(drawer = drawerOf(emptyList()), content = codeRow(code, 450))
        runCode { code.scrollTo(code.maxValue) }
        frames(2)
        rule.onNodeWithTag("code").performSemanticsAction(SemanticsActions.ScrollBy) { it(-2000f, 0f) }
        shutFor(30)
        assertEquals(0, code.value)
        assertClosed()
    }

    /** After a code-driven close under a finger, that finger's further moves and release do nothing; a fresh drag works. */
    @Test
    fun codeCloseUnderAFingerWins() {
        show()
        drag(0.4f * panel, 20, release = false)
        frames()
        assertTrue(state.fraction > 0f)
        runCode { state.close() }
        settled()
        assertClosed()
        rule.onRoot().performTouchInput { repeat(20) { moveBy(Offset(0.04f * panel, 0f)) } }
        shutFor(5)
        rule.onRoot().performTouchInput { repeat(3) { moveBy(Offset(0.1f * panel, 0f)) }; up() } // a fast release too
        shutFor(30)
        assertClosed()
        drag(0.6f * panel, 30)
        settled()
        assertOpen()
    }

    /** A drag that starts on an editable field (the clarify answer in the chat, the search in the drawer) never moves the drawer. */
    @Test
    fun editableFieldsKeepTheirDrags() {
        shownChat = withPending(clarify)
        show()
        val answer = rule.onNode(hasSetTextAction() and !hasTestTag("composer.field"))
        answer.performTextInput("Downloads")
        frames(2)
        drag(0.6f * panel, 6, answer) // quick
        shutFor(30)
        answer.performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100) // a selection drag
            repeat(20) { moveBy(Offset(10f, 0f)) }
        }
        shutFor(3)
        up(answer)
        shutFor(30)
        assertEquals("Downloads", answer.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
        runCode { state.open() }
        settled()
        val search = rule.onNodeWithContentDescription("Search chats")
        drag(-0.6f * panel, 6, search) // quick, toward closing
        repeat(30) {
            frames()
            assertEquals(1f, state.fraction)
        }
        search.performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            repeat(20) { moveBy(Offset(-10f, 0f)) }
            up()
        }
        settled()
        assertOpen()
        assertEquals("", search.fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
    }

    private val longLine = "val answer = listOf(" + (1..40).joinToString { "\"item$it\"" } + ")"
    private val wideTable = "| Name of the column | Second column here | Third column of it | Fourth column wide |\n|---|---|---|---|\n" +
        "| a fairly long cell | another long cell | yet another cell | the last long cell |"

    private fun replyWith(text: String) = chat.copy(items = items.take(2) + AssistantReply("md", emptyList(), text, FIXED_NOW))

    /** A swipe 35 degrees off horizontal, 10 moves held, starting at [from] on [node]; asserts the drawer per frame. */
    private fun diagonal(node: SemanticsNodeInteraction, toward: Float = 1f, stays: Boolean) {
        node.performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(toward * 17f, 11.9f)) }
        }
        repeat(3) {
            frames()
            if (stays) assertEquals("the drawer moved", if (state.isOpen) 1f else 0f, state.fraction)
        }
        if (!stays) assertTrue("the drawer did not move", state.fraction > 0f)
        up()
        settled()
    }

    /** Ownership is decided at the down: a 35 degree swipe on a scrolling code block, a scrolling table or either field never moves the drawer. */
    @Test
    fun diagonalSwipesOnSidewaysOwnersLeaveTheDrawer() {
        shownChat = replyWith("Code:\n\n```\n$longLine\n```\n\n$wideTable")
        show()
        diagonal(rule.onNodeWithText(longLine, substring = true), stays = true)
        diagonal(rule.onNodeWithText("a fairly long cell"), stays = true)
        assertClosed()
        shownChat = withPending(clarify)
        frames(3)
        diagonal(rule.onNode(hasSetTextAction() and !hasTestTag("composer.field")), stays = true)
        runCode { state.open() }
        settled()
        diagonal(rule.onNodeWithContentDescription("Search chats"), toward = -1f, stays = true)
        assertOpen()
    }

    /** The same diagonal swipe on plain transcript text moves the drawer. */
    @Test
    fun diagonalSwipeOnPlainTextMovesTheDrawer() {
        show()
        diagonal(rule.onNodeWithText("Answer 29, long enough to wrap onto a second line of the chat screen."), stays = false)
    }

    /**
     * A table that fits (two short columns, at 2.625x where each cell and the divider round on their own) is no
     * sideways scroller: a swipe that starts on it moves the drawer.
     */
    @Test
    @Config(qualifiers = "w411dp-h860dp-420dpi")
    fun fittedTableLetsTheDrawerMove() {
        shownChat = replyWith("| abcde | fghij |\n|---|---|\n| klmno | pqrst |")
        show()
        drag(0.6f * state.width, 40, rule.onNodeWithText("klmno"), release = false)
        frames()
        assertTrue("the fitted table kept the swipe", state.fraction > 0.2f)
        up()
        settled()
        assertOpen()
    }

    /** The element under the finger goes away mid-drag (a row re-keyed): the drawer neither picks the gesture up nor jumps. */
    @Test
    fun ownerRemovedMidDragNoJump() {
        val code = ScrollState(0)
        var shown by mutableStateOf(true)
        show(drawer = drawerOf(emptyList())) { drag ->
            Box(Modifier.fillMaxSize().then(drag)) {
                if (shown) Row(Modifier.align(Alignment.Center).fillMaxWidth().sidewaysScroll(code).testTag("code")) { Box(Modifier.width(900.dp).height(100.dp)) }
            }
        }
        runCode { code.scrollTo(code.maxValue) }
        frames(2)
        drag(-300f, 20, rule.onNodeWithTag("code"), release = false) // scrolls the row back, held
        frames()
        shown = false
        frames(2)
        rule.onRoot().performTouchInput { repeat(10) { moveBy(Offset(20f, 0f)) } }
        shutFor(5)
        up()
        shutFor(30)
        assertClosed()
    }

    @Test
    fun verticalScrollIsNotStolen() {
        show()
        rule.onRoot().performTouchInput {
            down(center)
            repeat(20) { moveBy(Offset(2f, 20f)) } // dx 20 dp, dy 200 dp
        }
        frames()
        assertEquals(0f, state.fraction)
        assertTrue("the list did not scroll", list.canScrollBackward)
        up()
        settled()
        assertClosed()
    }

    @Test
    fun composerDoesNotOpenDrawer() {
        show()
        val field = rule.onNodeWithTag("composer.field")
        drag(0.6f * panel, 40, field, release = false)
        frames()
        assertEquals(0f, state.fraction)
        up(field)
        settled()
        assertClosed()
        field.performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100) // a text selection
            repeat(20) { moveBy(Offset(10f, 0f)) }
        }
        frames()
        assertEquals(0f, state.fraction)
        up(field)
        settled()
        assertClosed()
    }

    // ---- Ruling B: one settle rule ----

    /** A scrim drag held at the closed end through a frame, then released: Closed everywhere, and the next open is an opening. */
    @Test
    fun scrimDragHeldAtTheClosedEndSettlesClosed() {
        val tester = StateRestorationTester(rule)
        rule.mainClock.autoAdvance = false
        tester.setContent { Drawer(PushDrawerValue.Open, false, LayoutDirection.Ltr, 1f, drawerOf(rows), chatContent) }
        frames(2)
        drag(-1.1f * panel, 40, rule.onNodeWithTag("drawer.scrim"), from = code(), release = false)
        frames(3) // a composition frame at fraction 0 with the finger still down
        assertEquals(0f, state.fraction)
        up()
        settled()
        assertClosed()
        assertFalse("Back is still taken", backEnabled())
        restore(tester)
        assertClosed() // the saver stored Closed
        rule.onNodeWithContentDescription("Open chat list").performClick()
        settled()
        assertOpen()
        assertEquals(1, openings)
    }

    /** A drag whose node disappears mid-drag (the row re-keyed, the chat replaced) still ends at an end. */
    @Test
    fun dragWhoseNodeDisappearsStillSettles() {
        var shown by mutableStateOf(true)
        show(drawer = drawerOf(emptyList())) { drag -> if (shown) Box(Modifier.fillMaxSize().then(drag)) }
        drag(0.3f * panel, 20, release = false)
        frames()
        assertTrue(state.fraction > 0.2f)
        shown = false
        frames(3)
        up()
        settled()
        assertClosed()
    }

    /** A settle cancelled half way (the drawer leaves the screen, as when the archive opens) finishes when it is back. */
    @Test
    fun interruptedSettleNeverStaysHalfOpen() {
        var drawerShown by mutableStateOf(true)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = false, blur = false) {
                state = rememberPushDrawerState()
                scope = rememberCoroutineScope()
                if (drawerShown) PushDrawer(state, drawerOf(rows), onOpening = { openings++ }, content = chatContent)
            }
        }
        frames(2)
        drag(0.6f * panel, 30)
        frames(3)
        assertTrue(state.fraction > 0f && state.fraction < 1f)
        drawerShown = false
        frames(3)
        val stopped = state.fraction
        assertTrue("the settle was not interrupted", stopped > 0f && stopped < 1f)
        drawerShown = true
        settled()
        assertTrue("left at ${state.fraction} (target ${state.target}, owner ${state.owner}, settling ${state.settling})", state.fraction == 0f || state.fraction == 1f)
        assertEquals(state.isOpen, state.fraction == 1f)
    }

    /**
     * A drag held exactly at the open end (target still Closed) whose drawer leaves the screen before the finger lifts:
     * on return the idle rule settles the endpoint too, with its target and the opening refresh.
     */
    @Test
    fun aLostDragAtTheOpenEndIsSettledOnReturn() {
        var drawerShown by mutableStateOf(true)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = false, blur = false) {
                state = rememberPushDrawerState()
                scope = rememberCoroutineScope()
                if (drawerShown) PushDrawer(state, drawerOf(rows), onOpening = { openings++ }, content = chatContent)
            }
        }
        frames(2)
        drag(1.1f * panel, 40, release = false)
        frames()
        assertEquals(1f, state.fraction)
        assertFalse(state.isOpen)
        drawerShown = false
        frames(3)
        up()
        drawerShown = true
        settled()
        assertOpen()
        assertEquals(1, openings)
    }

    /** The drag's own node goes away while another finger stays down: the drag ends at once, not when that finger lifts. */
    @Test
    fun aDragWhoseNodeGoesAwayEndsWithAnotherFingerDown() {
        var shown by mutableStateOf(true)
        show(drawer = drawerOf(emptyList())) { drag ->
            androidx.compose.foundation.layout.Column(Modifier.fillMaxSize()) {
                Box(Modifier.fillMaxWidth().height(500.dp).testTag("area").then(if (shown) drag else Modifier))
                Box(Modifier.fillMaxWidth().height(100.dp).testTag("plain"))
            }
        }
        rule.onNodeWithTag("area").performTouchInput {
            down(0, center)
            repeat(20) { moveBy(0, Offset(0.3f * panel / 20, 0f)) }
        }
        frames()
        assertTrue(state.fraction > 0.2f)
        rule.onRoot().performTouchInput { down(1, Offset(100f, 1100f)) } // on the plain box, held
        shown = false
        settled()
        assertClosed() // both fingers still down
        rule.onRoot().performTouchInput {
            up(0)
            up(1)
        }
        settled()
        assertClosed()
    }

    /** The drag's handler is reset mid-drag (the layout direction flips): the drag ends at once, by position. */
    @Test
    fun aDragWhoseHandlerIsResetStillSettles() {
        var direction by mutableStateOf(LayoutDirection.Ltr)
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = false, blur = false) {
                CompositionLocalProvider(LocalLayoutDirection provides direction) {
                    state = rememberPushDrawerState()
                    scope = rememberCoroutineScope()
                    PushDrawer(state, drawerOf(emptyList())) { drag -> Box(Modifier.fillMaxSize().then(drag)) }
                }
            }
        }
        frames(2)
        drag(0.3f * panel, 20, release = false)
        frames()
        assertTrue(state.fraction > 0.2f)
        direction = LayoutDirection.Rtl
        settled()
        assertClosed() // the finger is still down
        up()
        settled()
        assertClosed()
    }

    /**
     * A second scrim drag begun while the first one's release is settling owns the scrim: held at the closed end through
     * frames it keeps its finger, and moving back out moves the drawer.
     */
    @Test
    fun aScrimDragDuringASettleKeepsItsFinger() {
        show(PushDrawerValue.Open)
        drag(-0.3f * panel, 20, rule.onNodeWithTag("drawer.scrim"), from = code()) // released: settles back open
        frames(3)
        assertTrue("not settling", state.fraction > 0.5f && state.fraction < 1f)
        drag(-1.2f * panel, 30, rule.onNodeWithTag("drawer.scrim"), from = code(), release = false)
        frames(3)
        assertEquals(0f, state.fraction)
        rule.onRoot().performTouchInput { repeat(10) { moveBy(Offset(0.03f * panel, 0f)) } } // back out, same finger
        frames()
        assertTrue("the held scrim drag lost its finger at the closed end", state.fraction > 0.2f)
        rule.onRoot().performTouchInput { repeat(10) { moveBy(Offset(-0.03f * panel, 0f)) } }
        frames()
        up()
        settled()
        assertClosed()
        assertFalse(backEnabled())
    }

    // ---- Ruling C: a covered chat does not act ----

    /** One finger holds a real answer control, a second drags the drawer out on the top bar, the first lifts: no answer. */
    @Test
    fun aHeldAnswerDoesNotFireUnderTheDrawer() {
        shownChat = withPending(approval)
        show()
        frames(40) // the request is mature for the answer guard
        val allow = rule.onNodeWithText("Allow once")
        allow.performTouchInput { down(0, center) }
        rule.onNodeWithTag("chat.topbar").performTouchInput {
            down(1, center)
            repeat(8) { moveBy(1, Offset(6f, 0f)) } // past slop, a few dp out
        }
        frames()
        assertTrue(state.fraction > 0f)
        rule.onRoot().performTouchInput { up(0) } // the covered button is out of the tree; the pointer is not
        frames()
        assertEquals("an answer fired while covered", emptyList<ApprovalChoice>(), approvals)
        rule.onRoot().performTouchInput { up(1) }
        settled()
        assertClosed()
        frames(40)
        rule.onNodeWithText("Allow once").performClick()
        frames()
        assertEquals(listOf(ApprovalChoice.ONCE), approvals)
    }

    @Test
    fun aHeldSendDoesNotFireUnderTheDrawer() {
        show()
        val send = rule.onNodeWithTag("composer.send")
        send.performTouchInput { down(0, center) }
        rule.onNodeWithTag("chat.topbar").performTouchInput {
            down(1, center)
            repeat(8) { moveBy(1, Offset(6f, 0f)) }
        }
        frames()
        assertTrue(state.fraction > 0f)
        rule.onRoot().performTouchInput { up(0) }
        frames()
        assertEquals(0, sends)
        rule.onRoot().performTouchInput { up(1) }
        settled()
        rule.onNodeWithTag("composer.send").performClick()
        frames()
        assertEquals(1, sends)
    }

    /**
     * The approval is released in the same input batch in which the other finger moved the drawer off zero, before any
     * composition: the gate asks the drawer then, so the answer is dropped.
     */
    @Test
    fun anApprovalReleasedBeforeTheNextCompositionIsDropped() {
        shownChat = withPending(approval)
        show()
        frames(40)
        val allow = rule.onNodeWithText("Allow once").getBoundsInRoot()
        val bar = rule.onNodeWithTag("chat.topbar").getBoundsInRoot()
        val density = rule.density.density
        rule.onRoot().performTouchInput {
            down(0, Offset((allow.left + allow.right).value / 2 * density, (allow.top + allow.bottom).value / 2 * density))
            down(1, Offset(width / 2f, (bar.top + bar.bottom).value / 2 * density))
            repeat(8) { moveBy(1, Offset(6f, 0f)) }
            up(0)
        }
        assertTrue(state.fraction > 0f)
        frames()
        assertEquals("an answer fired while covered", emptyList<ApprovalChoice>(), approvals)
        rule.onRoot().performTouchInput { up(1) }
        settled()
        assertClosed()
        frames(40)
        rule.onNodeWithText("Allow once").performClick()
        frames()
        assertEquals(listOf(ApprovalChoice.ONCE), approvals)
    }

    /** The bar's real New chat button, held while another finger drags the transcript out, does nothing on release. */
    @Test
    fun aHeldNewChatDoesNothingUnderTheDrawer() {
        show()
        val newChat = rule.onNodeWithContentDescription("New chat")
        newChat.performTouchInput { down(0, center) }
        rule.onNodeWithText("Answer 29, long enough to wrap onto a second line of the chat screen.").performTouchInput {
            down(1, center)
            repeat(8) { moveBy(1, Offset(6f, 0f)) }
        }
        frames()
        assertTrue(state.fraction > 0f)
        rule.onRoot().performTouchInput { up(0) }
        frames()
        assertEquals(0, newChats)
        rule.onRoot().performTouchInput { up(1) }
        settled()
        assertClosed()
        rule.onNodeWithContentDescription("New chat").performClick()
        frames()
        assertEquals(1, newChats)
    }

    /** A one-finger drag that starts on a button moves the drawer and presses nothing. */
    @Test
    fun aDragThatStartsOnAButtonActsNot() {
        shownChat = withPending(approval)
        show()
        frames(40)
        drag(0.6f * panel, 30, rule.onNodeWithText("Allow once"))
        settled()
        assertOpen()
        assertTrue("$approvals", approvals.isEmpty())
    }

    // ---- closing ----

    @Test
    fun scrimTapCloses() {
        show(PushDrawerValue.Open)
        val scrim = rule.onNodeWithTag("drawer.scrim")
        assertEquals(listOf("Close the chat list"), scrim.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ContentDescription))
        scrim.performTouchInput { click(Offset(width - 40f, centerY)) }
        settled()
        assertClosed()
    }

    @Test
    fun backCloses() {
        show(PushDrawerValue.Open)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        settled()
        assertClosed()
        assertFalse("Back is still taken by a closed drawer", backEnabled())
        drag(0.4f * panel, 20, release = false) // part way, finger down: Back closes, not leaves
        frames()
        assertTrue(backEnabled())
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        settled()
        assertClosed()
        assertFalse(rule.activity.isFinishing)
        up()
        settled()
        assertClosed()
    }

    @Test
    fun dismissActionCloses() {
        show(PushDrawerValue.Open)
        val panelNode = rule.onNodeWithTag("drawer.panel")
        assertEquals("Chat list", panelNode.fetchSemanticsNode().config[SemanticsProperties.PaneTitle])
        panelNode.performSemanticsAction(SemanticsActions.Dismiss)
        settled()
        assertClosed()
    }

    @Test
    fun onOpeningOncePerOpening() {
        show()
        drag(0.3f * panel, 30) // snaps back: no opening
        settled()
        assertEquals(0, openings)
        drag(0.6f * panel, 30)
        settled()
        assertOpen()
        assertEquals(1, openings)
        runCode { state.close() }
        settled()
        runCode { state.open() }
        settled()
        runCode { state.open() } // already open: not another opening
        settled()
        assertEquals(2, openings)
    }

    @Test
    fun hapticAtBothEnds() {
        show()
        drag(0.6f * panel, 30)
        settled()
        assertOpen()
        assertEquals(1, haptics.selections)
        drag(-0.6f * panel, 30, rule.onNodeWithTag("drawer.scrim"), from = code())
        settled()
        assertClosed()
        assertEquals(2, haptics.selections)
        assertEquals(0, haptics.lights)
    }

    // ---- focus, keyboard, accessibility ----

    @Test
    fun hiddenSideHasNoFocusInputOrSemantics() {
        var drawerFocus = 0
        var chatFocus = 0
        show(
            drawer = { Box(Modifier.onFocusEvent { if (it.hasFocus) drawerFocus++ }) { drawerOf(rows)() } },
            content = { drag -> Box(Modifier.onFocusEvent { if (it.hasFocus) chatFocus++ }) { chatContent(drag) } },
        )
        // Closed: nothing of the panel in the tree.
        rule.onNodeWithText("Largest files").assertDoesNotExist()
        rule.onNodeWithContentDescription("Search chats").assertDoesNotExist()
        // Touch mode (nothing moves focus by itself there): opening moves focus into the panel.
        runCode { state.open() }
        settled()
        rule.onNodeWithTag("drawer.panel").assertIsFocused()
        runCode { state.close() }
        settled()
        // Keyboard: Tab from the chat never enters the closed panel.
        rule.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        drawerFocus = 0
        rule.runOnIdle { button.requestFocus() }
        repeat(12) {
            rule.onRoot().performKeyInput { pressKey(Key.Tab) }
            frames()
        }
        assertEquals("Tab focused something in the closed panel", 0, drawerFocus)
        assertTrue(rule.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag("drawer.panel"))).fetchSemanticsNodes().isEmpty())
        // Open: nothing of the chat in the tree or reachable by Tab.
        runCode { state.open() }
        settled()
        rule.onNodeWithTag("chat.title").assertDoesNotExist()
        rule.onNodeWithTag("composer.field").assertDoesNotExist()
        rule.onNodeWithText("Largest files").assertExists()
        chatFocus = 0
        repeat(12) {
            rule.onRoot().performKeyInput { pressKey(Key.Tab) }
            frames()
        }
        assertEquals("Tab focused something in the pushed chat", 0, chatFocus)
        // Closed again: focus is back on the drawer button.
        runCode { state.close() }
        settled()
        rule.onNodeWithContentDescription("Open chat list").assertIsFocused()
    }

    /** With focus inside the panel (the search field), a part-way drag that snaps back open leaves it there. */
    @Test
    fun snappingBackOpenKeepsFocusInThePanel() {
        show()
        rule.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        runCode { state.open() }
        settled()
        rule.onNodeWithTag("drawer.panel").assertIsFocused()
        rule.onRoot().performKeyInput { pressKey(Key.Tab) }
        frames()
        val search = rule.onNodeWithContentDescription("Search chats")
        search.assertIsFocused()
        drag(-0.2f * panel, 20, rule.onNodeWithTag("drawer.panel"), from = { Offset(width - 10f, bottom - 40f) })
        settled()
        assertOpen()
        search.assertIsFocused()
    }

    private fun pixel(x: Int, y: Int): Color = rule.onRoot().captureToImage().toPixelMap()[x, y]

    /** Keyboard focus is visible on the panel and on the scrim, reached both ways; the scrim washes when pressed. */
    @Test
    fun focusAndPressAreVisible() {
        show()
        rule.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        runCode { state.open() }
        settled()
        rule.onNodeWithTag("drawer.panel").assertIsFocused()
        val panelRing = pixel(4, 760) // the panel's start edge, inside the 2 dp ring 1 dp in
        val scrimEdge = pixel(544, 760) // the scrim's start edge
        rule.onRoot().performKeyInput { pressKey(Key.Tab) } // into the panel's content
        frames()
        assertNotEquals("no focus ring on the panel", panelRing, pixel(4, 760))
        var steps = 0
        while (rule.onAllNodes(isFocused() and hasTestTag("drawer.scrim")).fetchSemanticsNodes().isEmpty() && steps++ < 30) {
            rule.onRoot().performKeyInput { pressKey(Key.Tab) } // forward
            frames()
        }
        rule.onNodeWithTag("drawer.scrim").assertIsFocused()
        assertNotEquals("no focus ring on the scrim", scrimEdge, pixel(544, 760))
        rule.onRoot().performKeyInput { pressKey(Key.Tab) }
        frames()
        steps = 0
        while (rule.onAllNodes(isFocused() and hasTestTag("drawer.scrim")).fetchSemanticsNodes().isEmpty() && steps++ < 30) {
            rule.onRoot().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Tab) } } // backward
            frames()
        }
        rule.onNodeWithTag("drawer.scrim").assertIsFocused()
        // Pressed: the theme's wash over the scrim.
        rule.runOnIdle { inputMode.requestInputMode(InputMode.Touch) }
        rule.onRoot().performKeyInput { pressKey(Key.Tab) }
        frames(20)
        val before = pixel(700, 760)
        rule.onNodeWithTag("drawer.scrim").performTouchInput { down(Offset(width - 20f, centerY)) }
        frames(20)
        assertNotEquals("no press feedback on the scrim", before, pixel(700, 760))
        up()
        settled()
    }

    @Test
    fun focusRingRightToLeft() {
        show(direction = LayoutDirection.Rtl)
        rule.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        runCode { state.open() }
        settled()
        rule.onNodeWithTag("drawer.panel").assertIsFocused()
        val start = pixel(715, 760) // the panel's start edge (right)
        val end = pixel(184, 760) // its end edge
        rule.onRoot().performKeyInput { pressKey(Key.Tab) }
        frames()
        assertNotEquals("no focus ring on the panel's start edge", start, pixel(715, 760))
        assertNotEquals("no focus ring on the panel's end edge", end, pixel(184, 760))
    }

    /** The keyboard goes and the chat's focus is cleared once, when the drawer leaves closed. */
    @Test
    fun leavingClosedHidesTheKeyboardOnce() {
        show()
        rule.onNodeWithTag("composer.field").performSemanticsAction(SemanticsActions.RequestFocus)
        frames(2)
        rule.onNodeWithTag("composer.field").assertIsFocused()
        drag(0.1f * panel, 10, release = false)
        frames()
        assertEquals(1, hides)
        rule.onRoot().performTouchInput { repeat(20) { moveBy(Offset(5f, 0f)) } }
        frames()
        assertEquals(1, hides)
        up()
        settled()
        runCode { state.close() }
        settled()
        rule.onNodeWithTag("composer.field").assertIsNotFocused()
    }

    @Test
    fun emptyAndPopulated() {
        var shown by mutableStateOf(emptyList<DrawerRow>())
        show(drawer = { drawerOf(shown)() })
        for (r in listOf(emptyList(), rows)) {
            shown = r
            rule.onNodeWithContentDescription("Open chat list").performClick()
            settled()
            assertOpen()
            rule.onNodeWithText("New chat").assertExists()
            rule.onNodeWithText("Settings").assertExists()
            if (r.isEmpty()) rule.onNodeWithText("Largest files").assertDoesNotExist() else rule.onNodeWithText("Largest files").assertExists()
            rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
            settled()
            assertClosed()
            rule.onNodeWithText("New chat").assertDoesNotExist()
        }
    }

    // ---- the production selection adapter (ChatRoute's) ----

    /** Selecting another row or the open one forwards the key to the drawer's own select and closes; the gesture never selects. */
    @Test
    fun selectionForwardsAndCloses() {
        val selected = ArrayList<String>()
        var also = 0
        val delegate = object : DrawerActions {
            override fun onSelect(key: String) {
                selected += key
            }
        }
        show(drawer = { drawerOf(rows, closingOnSelect(delegate, state, scope) { also++ })() })
        drag(0.6f * panel, 30)
        settled()
        assertOpen()
        assertTrue("a drag selected a chat", selected.isEmpty())
        rule.onNodeWithText("Garden beds").performClick()
        settled()
        assertEquals(listOf("apofis-2"), selected)
        assertClosed()
        runCode { state.open() }
        settled()
        rule.onNodeWithText("Largest files").performClick() // the open chat
        settled()
        assertEquals(listOf("apofis-2", "apofis-1"), selected)
        assertClosed()
        assertEquals(2, also)
    }

    // ---- composition and layout ----

    /** The chat stays composed (and so reported as visible) while the drawer moves; while covered it is out of the tree. */
    @Test
    fun chatStaysComposedWhileTheDrawerMoves() {
        var starts = 0
        var disposals = 0
        show { drag ->
            DisposableEffect(Unit) {
                starts++
                onDispose { disposals++ }
            }
            chatContent(drag)
        }
        drag(0.3f * panel, 20, release = false)
        frames()
        assertTrue(state.fraction > 0f && state.fraction < 1f)
        rule.onNodeWithTag("chat.title").assertDoesNotExist()
        rule.onNodeWithTag("composer.field").assertDoesNotExist()
        rule.onNodeWithTag("drawer.scrim").assertExists()
        rule.onNodeWithText("Largest files").assertExists()
        up()
        drag(0.6f * panel, 20)
        settled()
        assertOpen()
        runCode { state.close() }
        settled()
        assertEquals(1, starts)
        assertEquals(0, disposals)
    }

    /** A drag moves layers only: no composition of the header, transcript or composer, no root measure, per frame. */
    @Test
    fun dragLeavesTheChatAlone() {
        show()
        val bar = ChromeProbe.barCompositions
        val bottom = ChromeProbe.bottomCompositions
        val body = ChromeProbe.bodyCompositions
        val root = ChromeProbe.rootMeasures
        drag(0.8f * panel, 40)
        settled()
        assertOpen()
        drag(-0.8f * panel, 40, rule.onNodeWithTag("drawer.scrim"), from = code())
        settled()
        assertClosed()
        assertEquals("header composed by the drag", bar, ChromeProbe.barCompositions)
        assertEquals("composer composed by the drag", bottom, ChromeProbe.bottomCompositions)
        assertEquals("transcript composed by the drag", body, ChromeProbe.bodyCompositions)
        assertEquals("chat measured by the drag", root, ChromeProbe.rootMeasures)
    }

    @Test
    fun reduceMotionSettlesAtOnce() {
        show(reduceMotion = true)
        runCode { state.open() }
        frames()
        assertOpen()
        runCode { state.close() }
        frames()
        assertClosed()
    }

    /** A programmatic close (a notification tap, a selection) during a held direct drag wins; the release changes nothing. */
    @Test
    fun programmaticCloseDuringADragWins() {
        show()
        drag(0.4f * panel, 20, release = false)
        frames()
        assertTrue(state.fraction > 0f)
        runCode { state.close() }
        settled()
        assertClosed()
        up()
        settled()
        assertClosed()
    }

    /** Recreates the drawer from its saved state; recreating needs frames, so the clock runs for it. */
    private fun restore(tester: StateRestorationTester) {
        val old = state
        rule.mainClock.autoAdvance = true
        tester.emulateSavedInstanceStateRestore()
        rule.mainClock.autoAdvance = false
        frames(2)
        assertNotSame("the drawer was not recreated", old, state)
    }

    @Test
    fun restoredAtItsRestingValue() {
        val tester = StateRestorationTester(rule)
        rule.mainClock.autoAdvance = false
        tester.setContent { Drawer(PushDrawerValue.Closed, false, LayoutDirection.Ltr, 1f, drawerOf(rows), chatContent) }
        frames(2)
        drag(0.6f * panel, 30)
        settled()
        restore(tester)
        assertOpen()
        drag(-0.3f * panel, 20, rule.onNodeWithTag("drawer.panel"), release = false) // part way back, finger down
        frames()
        assertTrue(state.fraction < 1f)
        restore(tester)
        assertOpen() // restored where it rested, never part way
        assertEquals(0, rule.onAllNodes(hasTestTag("chat.title")).fetchSemanticsNodes().size)
        up()
        settled()
        assertOpen()
    }

    /** The running chat's pulsing dot schedules no frame while the panel is closed and moves once it is shown. */
    @Test
    fun runningDotRunsOnlyWhileShown() {
        show(drawer = drawerOf(listOf(DrawerRow.Chat("apofis-2", "Garden beds", selected = false, running = true, needsInput = false))))
        frames(5)
        val closed = PulseProbe.frames
        frames(30)
        assertEquals("the closed panel's dot scheduled frames", closed, PulseProbe.frames)
        runCode { state.open() }
        frames(30)
        assertTrue("the shown dot does not move", PulseProbe.frames - closed >= 20)
        rule.onNodeWithContentDescription("working").assertExists()
    }

    // ---- insets and short windows ----

    /** Insets as the system would dispatch them, in px. */
    private fun insets(top: Int = 0, bottom: Int = 0, cutoutLeft: Int = 0, cutoutRight: Int = 0, ime: Int = 0) {
        rule.runOnUiThread {
            WindowCompat.setDecorFitsSystemWindows(rule.activity.window, false)
            val i = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, top, 0, 0))
                .setInsets(WindowInsetsCompat.Type.navigationBars(), Insets.of(0, 0, 0, bottom))
                .setInsets(WindowInsetsCompat.Type.displayCutout(), Insets.of(cutoutLeft, 0, cutoutRight, 0))
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime)).setVisible(WindowInsetsCompat.Type.ime(), ime > 0)
                .build()
            ViewCompat.dispatchApplyWindowInsets(rule.activity.window.decorView, i)
        }
        frames(2)
    }

    /** The panel keeps its content clear of the status bar, the navigation bar and a start-edge cutout, mirrored in RTL. */
    @Test
    fun panelInsets() {
        show(PushDrawerValue.Open)
        insets(top = 60, bottom = 80, cutoutLeft = 50)
        assertTrue(rule.onNodeWithContentDescription("Search chats").getBoundsInRoot().top >= 30.dp)
        assertTrue(rule.onNodeWithText("Settings").getBoundsInRoot().bottom <= 760.dp - 40.dp)
        assertTrue(rule.onNodeWithText("New chat").getBoundsInRoot().left >= 25.dp)
    }

    @Test
    fun panelInsetsRightToLeft() {
        show(PushDrawerValue.Open, direction = LayoutDirection.Rtl)
        insets(top = 60, bottom = 80, cutoutRight = 50)
        assertTrue(rule.onNodeWithContentDescription("Search chats").getBoundsInRoot().top >= 30.dp)
        assertTrue(rule.onNodeWithText("Settings").getBoundsInRoot().bottom <= 760.dp - 40.dp)
        assertTrue(rule.onNodeWithText("New chat").getBoundsInRoot().right <= 360.dp - 25.dp)
    }

    /** With the keyboard up and large text the fixed header and footer leave no row: the panel scrolls as one, by touch and by accessibility. */
    @Test
    fun shortWindowKeepsResultsAndSettingsReachable() {
        val selected = ArrayList<String>()
        var settings = 0
        rule.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(rule.activity.window, false) } // before composing, so insets arrive
        val many = listOf(DrawerRow.Header(GroupLabel.Today)) + (1..12).map { DrawerRow.Chat("apofis-$it", "Chat number $it", selected = false, running = false, needsInput = false) }
        show(
            PushDrawerValue.Open,
            fontScale = 2f,
            drawer = drawerOf(
                many,
                object : DrawerActions {
                    override fun onSelect(key: String) {
                        selected += key
                    }
                },
                onSettings = { settings++ },
            ),
        )
        insets(ime = 1220) // 610 dp of the 760 dp window: 150 dp left, less than the large header and footer need
        frames(3)
        val search = rule.onNodeWithContentDescription("Search chats")
        val top = search.fetchSemanticsNode().positionInRoot.y // unclipped: the header scrolls out above the panel
        assertTrue("the keyboard inset did not reach the panel", rule.onNodeWithText("New chat").getBoundsInRoot().top < 150.dp)
        // Touch: a swipe up that starts on a visible result moves the header with it.
        rule.onNodeWithText("Chat number 1").performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, -12f)) }
            up()
        }
        frames(5)
        val lazyLists = rule.onAllNodes(hasScrollToIndexAction() and hasAnyAncestor(hasTestTag("drawer.panel"))).fetchSemanticsNodes().size
        val moved = top - search.fetchSemanticsNode().positionInRoot.y
        assertTrue("the header did not move with the rows: $moved px, lazy lists $lazyLists", moved > 80f)
        // Accessibility: the panel has one vertical scroll, and scrolling it reaches a later result and Settings.
        val vertical = androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange)
        assertEquals("not one vertical scroll owner", 1, rule.onAllNodes(vertical and hasAnyAncestor(hasTestTag("drawer.panel"))).fetchSemanticsNodes().size)
        val scroller = rule.onNode(hasScrollAction() and vertical and hasAnyAncestor(hasTestTag("drawer.panel")))
        repeat(20) {
            if (!isShown("Chat number 9")) {
                scroller.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100f) }
                frames(30) // an accessibility scroll animates
            }
        }
        rule.onNodeWithText("Chat number 9").assertIsDisplayed().performClick()
        frames(2)
        assertEquals(listOf("apofis-9"), selected)
        repeat(30) {
            if (!isShown("Settings")) {
                scroller.performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 100f) }
                frames(30) // an accessibility scroll animates
            }
        }
        rule.onNodeWithText("Settings").assertIsDisplayed().performClick()
        frames(2)
        assertEquals(1, settings)
    }

    private fun isShown(text: String): Boolean = runCatching { rule.onNodeWithText(text).assertIsDisplayed() }.isSuccess
}
