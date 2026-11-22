package xyz.rycerz.apofis.ui.drawer

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.TouchInjectionScope
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
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
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.ChatItem
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.DrawerButton
import xyz.rycerz.apofis.ui.FIXED_NOW
import xyz.rycerz.apofis.ui.FIXED_TODAY
import xyz.rycerz.apofis.ui.chat.ChatActions
import xyz.rycerz.apofis.ui.chat.ChatScreen
import xyz.rycerz.apofis.ui.chat.ChatUiState
import xyz.rycerz.apofis.ui.chat.ChromeProbe
import xyz.rycerz.apofis.ui.chat.ComposerMode
import xyz.rycerz.apofis.ui.chat.StatusLine
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.ApofisIconButton
import xyz.rycerz.apofis.ui.theme.Haptics
import xyz.rycerz.apofis.ui.theme.LocalHaptics
import xyz.rycerz.apofis.ui.theme.PulseProbe

/**
 * The push drawer driven by touch, keys, Back and accessibility on the virtual clock (input moves it in 16 ms events).
 * The window is 360 dp at 2x, so the panel is 270 dp, 540 px. No blur is drawn (a blurred draw can blank later captures).
 */
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

    private val rows = listOf(
        DrawerRow.Header(GroupLabel.Today),
        DrawerRow.Chat("apofis-1", "Largest files", selected = true, running = false, needsInput = false),
        DrawerRow.Chat("apofis-2", "Garden beds", selected = false, running = false, needsInput = false),
    )

    private fun drawerOf(rows: List<DrawerRow>, actions: DrawerActions = object : DrawerActions {}): @Composable () -> Unit = {
        DrawerContent(rows, "", false, hasArchived = false, actions = actions, onQuery = {}, onNewChat = {}, onArchived = {}, onSettings = {})
    }

    private val chatContent: @Composable (Modifier) -> Unit = { drag ->
        ChatScreen(
            chat, object : ChatActions {}, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY, listState = list, dragArea = drag,
            navigationIcon = {
                Row {
                    ApofisIconButton({}, contentDescription = "Before") {} // first in focus order: automatic focus lands here, not on the button
                    DrawerButton(Modifier.focusRequester(button)) { scope.launch { state.open() } }
                }
            },
        )
    }

    private fun show(
        initial: PushDrawerValue = PushDrawerValue.Closed,
        reduceMotion: Boolean = false,
        direction: LayoutDirection = LayoutDirection.Ltr,
        drawer: @Composable () -> Unit = drawerOf(rows),
        content: @Composable (Modifier) -> Unit = chatContent,
    ) {
        rule.setContent { Drawer(initial, reduceMotion, direction, drawer, content) }
        rule.waitForIdle()
    }

    @Composable
    private fun Drawer(initial: PushDrawerValue, reduceMotion: Boolean, direction: LayoutDirection, drawer: @Composable () -> Unit, content: @Composable (Modifier) -> Unit) {
        ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = reduceMotion, blur = false) {
            CompositionLocalProvider(LocalHaptics provides haptics, LocalLayoutDirection provides direction) {
                state = rememberPushDrawerState(initial)
                scope = rememberCoroutineScope()
                inputMode = LocalInputModeManager.current
                PushDrawer(state, drawer, onOpening = { openings++ }, restoreFocus = button, content = content)
            }
        }
    }

    /** A drag of [dx] px in [moves] 16 ms steps from [from] (default: the middle of the screen). */
    private fun drag(dx: Float, moves: Int, node: SemanticsNodeInteraction = rule.onRoot(), from: (TouchInjectionScope.() -> Offset)? = null, release: Boolean = true) {
        node.performTouchInput {
            down(from?.invoke(this) ?: center)
            repeat(moves) { moveBy(Offset(dx / moves, 0f)) }
            if (release) up()
        }
    }

    private fun settled() = rule.waitForIdle()

    private fun assertClosed() {
        assertFalse(state.isOpen)
        assertEquals(0f, state.fraction)
    }

    private fun assertOpen() {
        assertTrue(state.isOpen)
        assertEquals(1f, state.fraction)
    }

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
    }

    @Test
    fun flingOpens() {
        show()
        drag(0.2f * panel, 4) // about 1,700 dp/s
        settled()
        assertOpen()
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

    @Test
    fun rightToLeftComesFromTheStartEdge() {
        show(direction = LayoutDirection.Rtl)
        drag(-0.6f * panel, 40)
        settled()
        assertOpen()
        assertEquals(90.dp, rule.onNodeWithTag("drawer.scrim").getBoundsInRoot().right) // pushed toward the left
        assertEquals(360.dp, rule.onNodeWithTag("drawer.panel").getBoundsInRoot().right)
        drag(0.6f * panel, 40, rule.onNodeWithTag("drawer.panel"))
        settled()
        assertClosed()
    }

    @Test
    fun scrollableChildScrollsFirst() {
        val code = ScrollState(0)
        show(drawer = drawerOf(emptyList())) { drag ->
            Box(Modifier.fillMaxSize().then(drag)) {
                Row(Modifier.align(Alignment.Center).fillMaxWidth().horizontalScroll(code).testTag("code")) { Box(Modifier.width(450.dp).height(100.dp)) }
            }
        }
        rule.runOnIdle { scope.launch { code.scrollTo(code.maxValue) } }
        settled()
        assertEquals(180, code.value)
        drag(196f, 30, rule.onNodeWithTag("code"))
        settled()
        assertEquals("the code block did not scroll back to its start", 0, code.value)
        assertClosed()
        drag(0.6f * panel, 40, rule.onNodeWithTag("code"))
        settled()
        assertOpen()
    }

    @Test
    fun verticalScrollIsNotStolen() {
        show()
        rule.onRoot().performTouchInput {
            down(center)
            repeat(20) { moveBy(Offset(2f, 20f)) } // dx 20 dp, dy 200 dp
        }
        assertEquals(0f, state.fraction)
        assertTrue("the list did not scroll", list.canScrollBackward)
        rule.onRoot().performTouchInput { up() }
        settled()
        assertClosed()
    }

    @Test
    fun composerDoesNotOpenDrawer() {
        show()
        val field = rule.onNodeWithTag("composer.field")
        drag(0.6f * panel, 40, field, release = false)
        assertEquals(0f, state.fraction)
        field.performTouchInput { up() }
        settled()
        assertClosed()
        field.performTouchInput {
            down(center)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100) // a text selection
            repeat(20) { moveBy(Offset(10f, 0f)) }
        }
        assertEquals(0f, state.fraction)
        field.performTouchInput { up() }
        settled()
        assertClosed()
    }

    @Test
    fun scrimTapCloses() {
        show(PushDrawerValue.Open)
        rule.onNodeWithTag("drawer.scrim").performTouchInput { click(Offset(width - 40f, centerY)) }
        settled()
        assertClosed()
    }

    @Test
    fun backCloses() {
        show(PushDrawerValue.Open)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        settled()
        assertClosed()
        rule.runOnUiThread { assertFalse("Back is still taken by a closed drawer", rule.activity.onBackPressedDispatcher.hasEnabledCallbacks()) }
    }

    @Test
    fun dismissActionCloses() {
        show(PushDrawerValue.Open)
        rule.onNodeWithTag("drawer.panel").performSemanticsAction(SemanticsActions.Dismiss)
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
        rule.runOnIdle { scope.launch { state.close() } }
        settled()
        rule.runOnIdle { scope.launch { state.open() } }
        settled()
        rule.runOnIdle { scope.launch { state.open() } } // already open: not another opening
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
        drag(-0.6f * panel, 30, rule.onNodeWithTag("drawer.scrim"), from = { Offset(width - 40f, centerY) })
        settled()
        assertClosed()
        assertEquals(2, haptics.selections)
        assertEquals(0, haptics.lights)
    }

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
        rule.runOnIdle { scope.launch { state.open() } }
        settled()
        rule.onNodeWithTag("drawer.panel").assertIsFocused()
        rule.runOnIdle { scope.launch { state.close() } }
        settled()
        // Keyboard: Tab from the chat never enters the closed panel.
        rule.runOnIdle { inputMode.requestInputMode(InputMode.Keyboard) }
        drawerFocus = 0
        rule.runOnIdle { button.requestFocus() }
        repeat(12) {
            rule.onRoot().performKeyInput { pressKey(Key.Tab) }
            rule.waitForIdle()
        }
        assertEquals("Tab focused something in the closed panel", 0, drawerFocus)
        assertTrue(rule.onAllNodes(isFocused() and hasAnyAncestor(hasTestTag("drawer.panel"))).fetchSemanticsNodes().isEmpty())
        // Open: focus moves into the panel; nothing of the chat in the tree or reachable by Tab.
        rule.runOnIdle { scope.launch { state.open() } }
        settled()
        rule.onNodeWithTag("chat.title").assertDoesNotExist()
        rule.onNodeWithTag("composer.field").assertDoesNotExist()
        rule.onNodeWithText("Largest files").assertExists()
        chatFocus = 0
        repeat(12) {
            rule.onRoot().performKeyInput { pressKey(Key.Tab) }
            rule.waitForIdle()
        }
        assertEquals("Tab focused something in the pushed chat", 0, chatFocus)
        // Closed again: focus is back on the drawer button.
        rule.runOnIdle { scope.launch { state.close() } }
        settled()
        rule.onNodeWithContentDescription("Open chat list").assertIsFocused()
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

    /** Selecting a row calls the drawer's own select (as ChatRoute's wrapper does) and then closes; the gesture never selects. */
    @Test
    fun selectingARowSelectsAndCloses() {
        val selected = ArrayList<String>()
        val actions = object : DrawerActions {
            override fun onSelect(key: String) {
                selected += key
                scope.launch { state.close() }
            }
        }
        show(drawer = drawerOf(rows, actions))
        drag(0.6f * panel, 30)
        settled()
        assertTrue("a drag selected a chat", selected.isEmpty())
        rule.onNodeWithText("Garden beds").performClick()
        settled()
        assertEquals(listOf("apofis-2"), selected)
        assertClosed()
    }

    /** The chat stays composed (and so reported as visible) while the drawer moves, opens and closes. */
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
        assertTrue(state.fraction > 0f)
        rule.onRoot().performTouchInput { up() }
        drag(0.6f * panel, 20)
        settled()
        assertOpen()
        rule.runOnIdle { scope.launch { state.close() } }
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
        drag(-0.8f * panel, 40, rule.onNodeWithTag("drawer.scrim"), from = { Offset(width - 40f, centerY) })
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
        rule.mainClock.autoAdvance = false
        rule.runOnIdle { scope.launch { state.open() } }
        rule.mainClock.advanceTimeByFrame()
        assertOpen()
        rule.runOnIdle { scope.launch { state.close() } }
        rule.mainClock.advanceTimeByFrame()
        assertClosed()
    }

    /**
     * A settle that is cancelled half way (the drawer leaves the screen mid-settle, as when the archive opens over it)
     * finishes when the drawer is back: never left part way.
     */
    @Test
    fun interruptedSettleNeverStaysHalfOpen() {
        var shown by mutableStateOf(true)
        rule.setContent {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = false, blur = false) {
                state = rememberPushDrawerState()
                if (shown) PushDrawer(state, drawerOf(rows), content = chatContent)
            }
        }
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        drag(0.6f * panel, 30)
        repeat(3) { rule.mainClock.advanceTimeByFrame() }
        assertTrue(state.fraction > 0f && state.fraction < 1f)
        shown = false
        repeat(3) { rule.mainClock.advanceTimeByFrame() }
        val stopped = state.fraction
        assertTrue("the settle was not interrupted", stopped > 0f && stopped < 1f)
        shown = true
        rule.mainClock.autoAdvance = true
        settled()
        assertTrue("left at ${state.fraction}", state.fraction == 0f || state.fraction == 1f)
        assertEquals(state.isOpen, state.fraction == 1f)
    }

    /** A programmatic close (a notification tap, a selection) wins over a finger holding the drawer part way. */
    @Test
    fun programmaticCloseDuringADragWins() {
        show()
        drag(0.4f * panel, 20, release = false)
        assertTrue(state.fraction > 0f)
        rule.runOnIdle { scope.launch { state.close() } }
        settled()
        assertClosed()
        rule.onRoot().performTouchInput { up() }
        settled()
        assertClosed()
    }

    @Test
    fun restoredAtItsRestingValue() {
        val tester = StateRestorationTester(rule)
        tester.setContent { Drawer(PushDrawerValue.Closed, false, LayoutDirection.Ltr, drawerOf(rows), chatContent) }
        rule.waitForIdle()
        drag(0.6f * panel, 30)
        settled()
        tester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertOpen()
        drag(-0.3f * panel, 20, rule.onNodeWithTag("drawer.panel"), release = false) // part way back, finger down
        tester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertOpen() // restored where it rested, never part way
        assertEquals(0, rule.onAllNodes(hasTestTag("chat.title")).fetchSemanticsNodes().size)
    }

    /** The running chat's pulsing dot schedules no frame while the panel is closed and moves once it is shown. */
    @Test
    fun runningDotRunsOnlyWhileShown() {
        rule.mainClock.autoAdvance = false
        show(drawer = drawerOf(listOf(DrawerRow.Chat("apofis-2", "Garden beds", selected = false, running = true, needsInput = false))))
        repeat(5) { rule.mainClock.advanceTimeByFrame() }
        val closed = PulseProbe.frames
        repeat(30) { rule.mainClock.advanceTimeByFrame() }
        assertEquals("the closed panel's dot scheduled frames", closed, PulseProbe.frames)
        rule.runOnIdle { scope.launch { state.open() } }
        repeat(30) { rule.mainClock.advanceTimeByFrame() }
        assertTrue("the shown dot does not move", PulseProbe.frames - closed >= 20)
        rule.onNodeWithContentDescription("working").assertExists()
    }
}
