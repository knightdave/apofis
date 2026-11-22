package xyz.rycerz.apofis.ui.chat

import android.view.KeyEvent as NativeKeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import android.view.View
import android.view.inputmethod.InputConnection
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.performTouchInput
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
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
import xyz.rycerz.apofis.ui.FIXED_NOW
import xyz.rycerz.apofis.ui.FIXED_TODAY
import xyz.rycerz.apofis.ui.GreenSeed
import xyz.rycerz.apofis.ui.PurpleSeed
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.Motion
import xyz.rycerz.apofis.chat.Composer as DraftHolder

@OptIn(ExperimentalTestApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class ComposerViewTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    // ---- pure rules ----

    @Test
    fun enterKeyRule() {
        // Off, or another key: the field gets it.
        for (down in listOf(true, false)) for (r in 0..2) for (shift in listOf(true, false)) {
            assertEquals(EnterKey.FIELD, enterKey(enterToSend = false, enter = true, down = down, repeatCount = r, shift = shift))
            assertEquals(EnterKey.FIELD, enterKey(enterToSend = true, enter = false, down = down, repeatCount = r, shift = shift))
        }
        assertEquals(EnterKey.SEND, enterKey(true, enter = true, down = true, repeatCount = 0, shift = false))
        assertEquals(EnterKey.FIELD, enterKey(true, enter = true, down = true, repeatCount = 0, shift = true)) // Shift+Enter: a newline
        for (shift in listOf(true, false)) {
            assertEquals(EnterKey.CONSUME, enterKey(true, enter = true, down = true, repeatCount = 1, shift = shift)) // repeats
            assertEquals(EnterKey.CONSUME, enterKey(true, enter = true, down = false, repeatCount = 0, shift = shift)) // key-ups
        }
    }

    @Test
    fun showsExpandRule() {
        assertFalse(showsExpand(1, expanded = false))
        assertFalse(showsExpand(2, expanded = false))
        assertTrue(showsExpand(3, expanded = false))
        assertTrue(showsExpand(400, expanded = false))
        assertTrue(showsExpand(1, expanded = true))
        assertTrue(showsExpand(0, expanded = true))
    }

    // ---- the composer alone ----

    private var draft by mutableStateOf("")
    private var mode by mutableStateOf(ComposerMode.SEND)
    private var busy by mutableStateOf(false)
    private var enter by mutableStateOf(false)
    private var expanded by mutableStateOf(false)
    private var subject by mutableStateOf<Any?>("run-A") // what the primary acts on: the run, or the chat for Send
    private var accept = true // what the test's holder answers
    private var sends = 0 // send attempts
    private var stops = 0
    private var now = 0L
    private var stopsHook: () -> Unit = {}
    private val sentTexts = ArrayList<String>()
    private val edits = ArrayList<String>()
    private lateinit var inputMode: androidx.compose.ui.input.InputModeManager
    private var ancestorEnters = 0 // Enter events that reached a handler above the composer
    private var onSendHook: () -> Boolean = {
        sends++
        sentTexts += draft
        accept
    }

    private fun show(expandedHeight: Dp = Dp.Unspecified, writeBack: Boolean = true, fontScale: Float = 1f, rtl: Boolean = false, theme: ThemeMode = ThemeMode.LIGHT, height: Dp? = null) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                ApofisTheme(theme, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                    inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
                    Box(Modifier.fillMaxSize().testTag("root"), contentAlignment = Alignment.BottomCenter) {
                        Box(
                            (if (height != null) Modifier.fillMaxWidth().height(height) else Modifier.fillMaxWidth()).testTag("slot").onKeyEvent {
                                if (it.key == Key.Enter || it.key == Key.NumPadEnter) ancestorEnters++
                                false
                            },
                            contentAlignment = Alignment.BottomCenter,
                        ) {
                            Composer(
                                draft, mode,
                                onDraftChange = {
                                    edits += it
                                    if (writeBack) draft = it
                                },
                                onSend = { onSendHook() },
                                onStop = { stops++; stopsHook() },
                                busy = busy, enterToSend = enter, expanded = expanded, onExpandedChange = { expanded = it }, expandedHeight = expandedHeight, subject = subject,
                                clock = { ++now }, // a manual clock: every reading is one tick later
                            )
                        }
                    }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun field() = rule.onNodeWithTag(FIELD_TAG)

    private fun nodes(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes()

    private fun SemanticsNode.descs(): List<String> = config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + children.flatMap { it.descs() }

    private fun bounds(tag: String): Rect = rule.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    private fun described(desc: String): Rect = rule.onNodeWithContentDescription(desc).fetchSemanticsNode().boundsInRoot

    private fun px(dp: Float) = with(rule.density) { dp.dp.toPx() }

    private fun frames(n: Int) = repeat(n) {
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeByFrame()
    }

    private fun composeView(): View = (rule.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup).getChildAt(0)

    /** The platform input connection of the focused field, as a keyboard sees it. */
    private fun connection(): InputConnection {
        lateinit var ic: InputConnection
        rule.runOnUiThread { ic = composeView().onCreateInputConnection(EditorInfo())!! }
        return ic
    }

    private fun key(action: Int, code: Int = NativeKeyEvent.KEYCODE_ENTER, repeat: Int = 0, meta: Int = 0) = NativeKeyEvent(0L, 0L, action, code, repeat, meta)

    /** Real key events through the view, in one UI turn: nothing is composed or drawn in between. */
    private fun dispatch(vararg events: NativeKeyEvent) = rule.runOnUiThread { for (e in events) composeView().dispatchKeyEvent(e) }

    private fun focusAtEnd() {
        field().performClick()
        field().performTextInputSelection(TextRange(draft.length))
        rule.waitForIdle()
    }

    private fun lineHeights(n: Int) = px(n * 19.5f + 28f)

    @Test
    fun labelsFollowMode() {
        draft = "hi"
        show()
        fun check(sendLabel: String?, sendEnabled: Boolean, stopEnabled: Boolean?) {
            rule.waitForIdle()
            val send = nodes(SEND_TAG)
            val stop = nodes(STOP_TAG)
            assertEquals(if (sendLabel == null) 0 else 1, send.size)
            assertEquals(if (stopEnabled == null) 0 else 1, stop.size)
            if (sendLabel != null) {
                assertEquals(listOf(sendLabel), send.single().descs())
                if (sendEnabled) rule.onNodeWithTag(SEND_TAG).assertIsEnabled() else rule.onNodeWithTag(SEND_TAG).assertIsNotEnabled()
            }
            if (stopEnabled != null) {
                assertEquals(listOf("Stop"), stop.single().descs())
                if (stopEnabled) rule.onNodeWithTag(STOP_TAG).assertIsEnabled() else rule.onNodeWithTag(STOP_TAG).assertIsNotEnabled()
            }
        }
        mode = ComposerMode.SEND_DISABLED; check("Send", sendEnabled = false, stopEnabled = null)
        mode = ComposerMode.SEND; check("Send", sendEnabled = true, stopEnabled = null)
        mode = ComposerMode.STEER; check("Send to the running reply", sendEnabled = true, stopEnabled = true)
        mode = ComposerMode.STOP; check(null, sendEnabled = false, stopEnabled = true)
        busy = true
        mode = ComposerMode.SEND; check("Send", sendEnabled = false, stopEnabled = null)
        mode = ComposerMode.STEER; check("Send to the running reply", sendEnabled = false, stopEnabled = true)
        mode = ComposerMode.STOP; check(null, sendEnabled = false, stopEnabled = true)
        busy = false

        // Mid glyph swap (arrow to square), each button node carries exactly one label.
        mode = ComposerMode.SEND
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        mode = ComposerMode.STOP
        frames(Motion.GLYPH_SWAP / 2 / 16)
        for (tag in listOf(SEND_TAG, STOP_TAG)) for (node in nodes(tag)) assertEquals("$tag mid swap", 1, node.descs().size)
        assertEquals(1, nodes(STOP_TAG).size)
        mode = ComposerMode.STEER
        frames(Motion.GLYPH_SWAP / 2 / 16)
        for (tag in listOf(SEND_TAG, STOP_TAG)) for (node in nodes(tag)) assertEquals("$tag mid swap", 1, node.descs().size)
    }


    /** The filled button at the end: the primary control, whatever its action. */
    private fun primaryNode() = rule.onAllNodes(hasTestTag(SEND_TAG) or hasTestTag(STOP_TAG), useUnmergedTree = true).fetchSemanticsNodes().maxBy { it.boundsInRoot.right }

    /** A press held while the action changes fires nothing, for every change; a fresh press then works. */
    @Test
    fun aPressHeldAcrossAnActionChangeFiresNothing() {
        fun hold(from: ComposerMode, fromDraft: String, to: ComposerMode, toDraft: String) {
            mode = from
            draft = fromDraft
            rule.waitForIdle()
            val at = primaryNode().boundsInRoot.center
            val (s0, t0) = sends to stops
            rule.onRoot().performTouchInput { down(at) }
            mode = to
            draft = toDraft
            rule.waitForIdle()
            rule.onRoot().performTouchInput { up() }
            rule.waitForIdle()
            assertEquals("$from to $to: sent", s0, sends)
            assertEquals("$from to $to: stopped", t0, stops)
            rule.onRoot().performTouchInput { click(primaryNode().boundsInRoot.center) } // a fresh press on the new action
            rule.waitForIdle()
            if (to == ComposerMode.STOP) assertEquals("$to: a fresh tap", t0 + 1, stops) else assertEquals("$to: a fresh tap", s0 + 1, sends)
        }
        show()
        hold(ComposerMode.STOP, "", ComposerMode.STEER, "dictated")
        hold(ComposerMode.STEER, "dictated", ComposerMode.SEND, "dictated")
        hold(ComposerMode.SEND, "typed", ComposerMode.STOP, "")
        rule.onNodeWithTag(STOP_TAG).performSemanticsAction(SemanticsActions.OnClick) // the accessibility click: no press of its own
        rule.waitForIdle()
        assertEquals("a direct accessibility activation", 2, stops)
    }

    /** Held through Stop, Steer and back to Stop: the shown action is Stop again, but a new generation; nothing fires. */
    @Test
    fun aPressHeldThroughStopSteerStopFiresNothing() {
        mode = ComposerMode.STOP
        draft = ""
        show()
        val at = primaryNode().boundsInRoot.center
        rule.onRoot().performTouchInput { down(at) }
        mode = ComposerMode.STEER
        draft = "dictated"
        rule.waitForIdle()
        mode = ComposerMode.STOP
        draft = ""
        rule.waitForIdle()
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()
        assertEquals("a press begun on an earlier Stop stopped", 0, stops)
        rule.onRoot().performTouchInput { click(at) }
        rule.waitForIdle()
        assertEquals("a fresh tap stops once", 1, stops)
    }

    /** Run A ends and run B starts while the press is held: the release does not stop B. */
    @Test
    fun aPressHeldAcrossARunReplacementDoesNotStopTheNewRun() {
        mode = ComposerMode.STOP
        draft = ""
        subject = "run-A"
        val stopped = ArrayList<Any?>()
        show()
        stopsHook = { stopped += subject }
        val at = primaryNode().boundsInRoot.center
        rule.onRoot().performTouchInput { down(at) }
        subject = "run-B" // still Stop, another run
        rule.waitForIdle()
        rule.onRoot().performTouchInput { moveBy(androidx.compose.ui.geometry.Offset(1f, 0f)) } // a move after the change is no press-down
        rule.onRoot().performTouchInput { up() }
        rule.waitForIdle()
        assertEquals("run B was stopped by a press begun on run A", emptyList<Any?>(), stopped)
        rule.onNodeWithTag(STOP_TAG).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitForIdle()
        assertEquals(listOf<Any?>("run-B"), stopped)
    }

    /** An indirect pointer (a touch navigation device) on the focused button: a fresh press fires once, a held one across a change nothing. */
    @Test
    fun anIndirectPressIsGuardedToo() {
        mode = ComposerMode.SEND
        draft = "typed"
        subject = "chat-1"
        show()
        val node = primaryNode()
        rule.runOnUiThread { inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
        rule.onNode(androidx.compose.ui.test.SemanticsMatcher("primary") { it.id == node.id }, useUnmergedTree = true).performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        fun indirect(action: Int) = android.view.MotionEvent.obtain(0L, 0L, action, 10f, 10f, 0).apply { source = android.view.InputDevice.SOURCE_TOUCH_NAVIGATION }
        rule.runOnUiThread {
            composeView().dispatchGenericMotionEvent(indirect(android.view.MotionEvent.ACTION_DOWN))
            composeView().dispatchGenericMotionEvent(indirect(android.view.MotionEvent.ACTION_UP))
        }
        rule.waitForIdle()
        assertEquals("a fresh indirect press", 1, sends)
        rule.runOnUiThread { composeView().dispatchGenericMotionEvent(indirect(android.view.MotionEvent.ACTION_DOWN)) }
        subject = "chat-2"
        rule.waitForIdle()
        rule.runOnUiThread { composeView().dispatchGenericMotionEvent(indirect(android.view.MotionEvent.ACTION_UP)) }
        rule.waitForIdle()
        assertEquals("an indirect press held across a change", 1, sends)
    }

    /** A lost key-up (Enter down, no release ever arrives) blocks nothing: a fresh Space press fires once. */
    @Test
    fun aLostReleaseBlocksNoLaterPress() {
        mode = ComposerMode.SEND
        draft = "typed"
        subject = "chat-1"
        show()
        val node = primaryNode()
        rule.runOnUiThread { inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
        rule.onNode(androidx.compose.ui.test.SemanticsMatcher("primary") { it.id == node.id }, useUnmergedTree = true).performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        dispatch(key(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_ENTER)) // its release is lost
        rule.waitForIdle()
        dispatch(key(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_SPACE), key(NativeKeyEvent.ACTION_UP, NativeKeyEvent.KEYCODE_SPACE))
        rule.waitForIdle()
        assertEquals(1, sends)
    }

    /** Two fingers down and up with no change of action: the holder takes the draft once; Stop goes to the shown run only. */
    @Test
    fun twoFingersWithoutAChange() {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val holder = DraftHolder(scope)
        holder.edit("k", "hello", holder.current("k").revision)
        var stores = 0
        onSendHook = { holder.send("k") { stores++; awaitCancellation() } != null }
        draft = "hello"
        subject = "chat-1"
        show()
        fun twoFingers() {
            val c = primaryNode().boundsInRoot.center
            rule.onRoot().performTouchInput {
                down(0, c)
                down(1, c + androidx.compose.ui.geometry.Offset(4f, 4f))
                up(0)
                up(1)
            }
            rule.waitForIdle()
        }
        twoFingers()
        assertEquals("the send went out once", 1, stores)
        mode = ComposerMode.STOP
        draft = ""
        subject = "run-A"
        val stopped = ArrayList<Any?>()
        stopsHook = { stopped += subject }
        rule.waitForIdle()
        twoFingers()
        assertTrue("Stop requested for run A only: $stopped", stopped.isNotEmpty() && stopped.all { it == "run-A" })
        scope.cancel()
    }

    /** Each activation key: a fresh press fires once; held across a change of generation it fires nothing. */
    @Test
    fun everyActivationKeyIsGuarded() {
        mode = ComposerMode.SEND
        draft = "typed"
        subject = "chat-1"
        show()
        val node = primaryNode()
        rule.runOnUiThread { inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) }
        rule.onNode(androidx.compose.ui.test.SemanticsMatcher("primary") { it.id == node.id }, useUnmergedTree = true).performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        var expected = 0
        for (code in listOf(NativeKeyEvent.KEYCODE_SPACE, NativeKeyEvent.KEYCODE_NUMPAD_ENTER, NativeKeyEvent.KEYCODE_DPAD_CENTER, NativeKeyEvent.KEYCODE_ENTER)) {
            // Fresh, with a held repeat in between: once.
            dispatch(key(NativeKeyEvent.ACTION_DOWN, code), key(NativeKeyEvent.ACTION_DOWN, code, repeat = 1), key(NativeKeyEvent.ACTION_UP, code))
            rule.waitForIdle()
            expected++
            assertEquals("key $code: a fresh press", expected, sends)
            // Held while the chat changes: nothing.
            dispatch(key(NativeKeyEvent.ACTION_DOWN, code))
            subject = "chat-$code"
            rule.waitForIdle()
            dispatch(key(NativeKeyEvent.ACTION_DOWN, code, repeat = 1)) // a repeat after the change is no press-down
            dispatch(key(NativeKeyEvent.ACTION_UP, code))
            rule.waitForIdle()
            assertEquals("key $code: held across a change", expected, sends)
        }
        assertEquals(node.id, primaryNode().id)
    }

    /**
     * One stable control: focused on Steer, the run finishing turns it into Send without losing keyboard focus or its
     * accessibility node; one fresh keyboard activation sends once. A key press held across a change fires nothing.
     */
    @Test
    fun thePrimaryKeepsFocusAndIdentityAcrossActions() {
        mode = ComposerMode.STEER
        draft = "dictated"
        show()
        val node = primaryNode()
        rule.runOnUiThread { inputMode.requestInputMode(androidx.compose.ui.input.InputMode.Keyboard) } // as a Tab would
        rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.SemanticsMatcher("primary") { it.id == node.id }, useUnmergedTree = true).performSemanticsAction(SemanticsActions.RequestFocus)
        rule.waitForIdle()
        fun focusedPrimary() = primaryNode().also { assertTrue("the primary lost focus", it.config[SemanticsProperties.Focused]) }
        assertTrue("the primary took focus on Steer", primaryNode().config[SemanticsProperties.Focused])
        mode = ComposerMode.SEND // the run finished
        rule.waitForIdle()
        assertEquals("the same accessibility node", node.id, focusedPrimary().id)
        assertEquals(listOf("Send"), primaryNode().descs())
        dispatch(key(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_SPACE), key(NativeKeyEvent.ACTION_UP, NativeKeyEvent.KEYCODE_SPACE))
        rule.waitForIdle()
        assertEquals("one fresh activation sends once", 1, sends)
        // A key held across Send to Stop, then Stop to Steer: nothing fires.
        dispatch(key(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_ENTER))
        mode = ComposerMode.STOP
        draft = ""
        rule.waitForIdle()
        dispatch(key(NativeKeyEvent.ACTION_UP, NativeKeyEvent.KEYCODE_ENTER))
        rule.waitForIdle()
        assertEquals(1, sends)
        assertEquals(0, stops)
        assertEquals(node.id, focusedPrimary().id)
        dispatch(key(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_ENTER))
        mode = ComposerMode.STEER
        draft = "again"
        rule.waitForIdle()
        dispatch(key(NativeKeyEvent.ACTION_UP, NativeKeyEvent.KEYCODE_ENTER))
        rule.waitForIdle()
        assertEquals(1, sends)
        assertEquals(0, stops)
        dispatch(key(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_ENTER), key(NativeKeyEvent.ACTION_UP, NativeKeyEvent.KEYCODE_ENTER))
        rule.waitForIdle()
        assertEquals("a fresh Enter on Steer", 2, sends)
    }

    /** The keyboard's Send action sends only with Enter to send on, whatever action the keyboard reports. */
    @Test
    fun imeActionSendsOnlyWithEnterToSend() {
        draft = "hi"
        show()
        focusAtEnd()
        assertEquals(ImeAction.Default, field().fetchSemanticsNode().config[SemanticsProperties.ImeAction])
        val off = connection()
        rule.runOnUiThread { off.performEditorAction(EditorInfo.IME_ACTION_SEND) }
        rule.waitForIdle()
        assertEquals("Send from the keyboard with Enter to send off", 0, sends)
        enter = true
        rule.waitForIdle()
        assertEquals(ImeAction.Send, field().fetchSemanticsNode().config[SemanticsProperties.ImeAction])
        val on = connection()
        rule.runOnUiThread { on.performEditorAction(EditorInfo.IME_ACTION_SEND) }
        rule.waitForIdle()
        assertEquals(1, sends)
        field().performImeAction()
        rule.waitForIdle()
        assertEquals(2, sends)
        assertEquals("the IME action never edits the draft", emptyList<String>(), edits)
    }

    @Test
    fun hardwareEnter() {
        enter = true
        draft = "hi"
        show()
        focusAtEnd()
        field().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(1, sends)
        assertEquals("Enter that sends inserts nothing", emptyList<String>(), edits)
        assertEquals("the down and the up of Enter stay with the composer", 0, ancestorEnters)

        field().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
        rule.waitForIdle()
        assertEquals(1, sends)
        assertEquals("Shift+Enter inserts one line", listOf("hi\n"), edits)
        assertEquals("two lines show no Expand", 0, rule.onAllNodes(hasContentDescription("Expand the editor")).fetchSemanticsNodes().size)

        field().performKeyInput { pressKey(Key.NumPadEnter) } // keypad Enter is the same key
        rule.waitForIdle()
        assertEquals(2, sends)

        dispatch(key(NativeKeyEvent.ACTION_DOWN, repeat = 3)) // a held key's repeats
        rule.waitForIdle()
        assertEquals(2, sends)
        assertEquals(1, edits.size)
        assertEquals(0, ancestorEnters)

        enter = false
        rule.waitForIdle()
        field().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals("off: Enter never sends", 2, sends)
        assertEquals("off: Return is a newline", "hi\n\n", draft)
    }

    /** Held Enter with Shift pressed mid-hold and another key in between: no newline and no second send. */
    @Test
    fun heldEnterAcrossShiftAndOtherKeys() {
        enter = true
        draft = "hi"
        show()
        focusAtEnd()
        val shift = NativeKeyEvent.META_SHIFT_ON or NativeKeyEvent.META_SHIFT_LEFT_ON
        dispatch(
            key(NativeKeyEvent.ACTION_DOWN),
            key(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_SHIFT_LEFT, meta = shift),
            key(NativeKeyEvent.ACTION_DOWN, repeat = 1, meta = shift),
            key(NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_NUMPAD_ENTER, repeat = 1, meta = shift),
            key(NativeKeyEvent.ACTION_DOWN, repeat = 2, meta = shift),
            key(NativeKeyEvent.ACTION_UP, meta = shift),
            key(NativeKeyEvent.ACTION_UP, NativeKeyEvent.KEYCODE_NUMPAD_ENTER, meta = shift),
            key(NativeKeyEvent.ACTION_UP, NativeKeyEvent.KEYCODE_SHIFT_LEFT),
        )
        rule.waitForIdle()
        assertEquals(1, sends)
        assertEquals("no newline from a held Enter", emptyList<String>(), edits)
        assertEquals("no Enter event leaked to the parent", 0, ancestorEnters)
    }

    /** Text the keyboard is still composing is part of the draft: Enter sends it, once. */
    @Test
    fun composingTextPlusEnterSendsItOnce() {
        enter = true
        draft = ""
        show()
        field().performClick()
        rule.waitForIdle()
        val ic = connection()
        rule.runOnUiThread { ic.setComposingText("wor", 1) }
        rule.waitForIdle()
        assertEquals("wor", draft)
        field().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(listOf("wor"), sentTexts)
        assertTrue("no edit added a newline", edits.none { '\n' in it })
    }

    @Test
    fun expandDoesNotWriteDraft() {
        draft = "one\ntwo\nthree"
        mode = ComposerMode.SEND
        show()
        assertEquals(0, rule.onAllNodes(hasContentDescription("Collapse the editor")).fetchSemanticsNodes().size)
        rule.onNodeWithContentDescription("Expand the editor").performClick()
        rule.waitForIdle()
        assertTrue(expanded)
        val expand = described("Collapse the editor")
        val send = bounds(SEND_TAG)
        val text = bounds(FIELD_TAG)
        assertFalse("expand $expand overlaps send $send", expand.overlaps(send))
        assertFalse("expand $expand overlaps the field $text", expand.overlaps(text))
        assertTrue("expanded field is tall: $text", text.height > px(300f))
        rule.onNodeWithContentDescription("Collapse the editor").performClick()
        rule.waitForIdle()
        assertFalse(expanded)
        assertTrue(bounds(FIELD_TAG).height < px(150f))
        assertFalse(described("Expand the editor").overlaps(bounds(SEND_TAG)))
        assertEquals(emptyList<String>(), edits)
        assertEquals("one\ntwo\nthree", draft)
    }

    @Test
    fun expandingKeepsFocusInTheField() {
        draft = "one\ntwo\nthree"
        show()
        field().performClick()
        rule.waitForIdle()
        assertTrue(field().fetchSemanticsNode().config[SemanticsProperties.Focused])
        rule.onNodeWithContentDescription("Expand the editor").performClick()
        rule.waitForIdle()
        assertTrue("focus left the field", field().fetchSemanticsNode().config[SemanticsProperties.Focused])
    }

    /** Collapse happens only when the holder takes the draft; a refused attempt changes nothing. */
    @Test
    fun collapsesOnlyWhenTheHolderAccepts() {
        draft = "x"
        expanded = true
        enter = true
        show()
        assertTrue(bounds(FIELD_TAG).height > px(300f))
        accept = false
        rule.onNodeWithTag(SEND_TAG).performClick()
        field().performImeAction()
        rule.waitForIdle()
        assertEquals(2, sends)
        assertTrue("a refused send collapsed the editor", expanded)
        assertTrue(bounds(FIELD_TAG).height > px(300f))
        accept = true
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(SEND_TAG).performClick()
        frames(1)
        assertFalse(expanded)
        assertEquals("collapsed at once: ${bounds(FIELD_TAG).height}", lineHeights(1), bounds(FIELD_TAG).height, 2f)
        rule.mainClock.autoAdvance = true
        expanded = true
        rule.waitForIdle()
        field().performImeAction()
        rule.waitForIdle()
        assertFalse(expanded)
        assertEquals(emptyList<String>(), edits)
    }

    /** Expanding grows from the current collapsed height and never dips below it; collapsing ends at the current text's height. */
    @Test
    fun expandAndCollapseUseTheCurrentHeights() {
        draft = (1..5).joinToString("\n") { "line $it" }
        show()
        val five = bounds(FIELD_TAG).height
        assertEquals(lineHeights(5), five, 2f)
        rule.mainClock.autoAdvance = false
        expanded = true
        var last = 0f
        repeat(Motion.COMPOSER_EXPAND / 16 + 2) {
            frames(1)
            val h = bounds(FIELD_TAG).height
            assertTrue("frame $it: $h below the collapsed $five", h >= five - 2)
            assertTrue("frame $it: $h shrank from $last", h >= last - 1)
            last = h
        }
        rule.mainClock.autoAdvance = true
        draft = (1..8).joinToString("\n") { "line $it" } // more text while expanded
        rule.waitForIdle()
        expanded = false
        rule.waitForIdle()
        assertEquals(lineHeights(5), bounds(FIELD_TAG).height, 2f)
        expanded = true
        rule.waitForIdle()
        draft = "one" // less text while expanded
        rule.waitForIdle()
        expanded = false
        rule.waitForIdle()
        assertEquals(lineHeights(1), bounds(FIELD_TAG).height, 2f)
    }

    /** With room for one 48 dp target only, Expand is dropped and Send keeps its full target. */
    @Test
    fun expandIsDroppedBeforeSend() {
        draft = (1..4).joinToString("\n") { "line $it" }
        show(height = 90.dp) // the bare row gets all 90 dp before decoration: less than two 48 dp targets
        val placedExpand = rule.onAllNodes(hasContentDescription("Expand the editor"), useUnmergedTree = true).fetchSemanticsNodes().filter { it.layoutInfo.isPlaced }
        assertEquals("Expand shown without room for two targets", 0, placedExpand.size)
        val send = bounds(SEND_TAG)
        val slot = bounds("slot")
        assertTrue("send $send", send.height >= px(48f) - 0.5f && send.top >= slot.top && send.bottom <= slot.bottom)
        val f = bounds(FIELD_TAG)
        assertTrue("field $f inside its slot $slot", f.top >= slot.top && f.bottom <= slot.bottom)
    }

    /** Two presses in one frame, before the composer redraws: the holder takes the draft once. */
    @Test
    fun doublePressSendsOnce() {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val holder = DraftHolder(scope)
        holder.edit("k", "hello", holder.current("k").revision)
        var stores = 0
        onSendHook = { holder.send("k") { stores++; awaitCancellation() } != null }
        draft = "hello"
        enter = true
        show()
        focusAtEnd()
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(SEND_TAG).performClick()
        rule.onNodeWithTag(SEND_TAG).performClick()
        field().performImeAction()
        field().performKeyInput { pressKey(Key.Enter) }
        frames(2)
        assertEquals(1, stores)
        scope.cancel()
    }

    @Test
    fun draftsOfAnySizeStayBounded() {
        draft = (1..6).joinToString("\n") { "line $it" }
        show()
        assertEquals("six lines show five", lineHeights(5), bounds(FIELD_TAG).height, 2f)
        draft = "x".repeat(100_000)
        rule.waitForIdle()
        assertEquals("100k chars", lineHeights(5), bounds(FIELD_TAG).height, 2f)
        rule.onNodeWithContentDescription("Expand the editor").assertExists()
        draft = (1..10_000).joinToString("\n") { "line $it" }
        rule.waitForIdle()
        assertEquals("10k lines", lineHeights(5), bounds(FIELD_TAG).height, 2f)
        expanded = true
        rule.waitForIdle()
        val slot = bounds("slot")
        val f = bounds(FIELD_TAG)
        assertTrue("expanded stays inside its room: $f", f.top >= slot.top && f.bottom <= slot.bottom)
        val before = draft
        field().performTextInputSelection(TextRange(before.length))
        field().performTextInput("!")
        rule.waitForIdle()
        assertEquals(before + "!", edits.last())
    }

    /** Whatever the draft holds is shown and edited as is: nothing normalizes it, and showing it writes nothing. */
    @Test
    fun anyTextRoundTripsExactly() {
        val tricky = "a\rb\r\nc\uD800d‮e⁦f‏g\uDC00"
        for (text in listOf(tricky, "", "   \n\t ", "\r\n\r\n")) {
            edits.clear()
            draft = text
            if (text == tricky) show() else rule.waitForIdle()
            assertEquals(emptyList<String>(), edits)
            assertEquals(text, field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text)
            field().performTextInputSelection(TextRange(text.length))
            field().performTextInput("!")
            rule.waitForIdle()
            assertEquals(listOf(text + "!"), edits)
            draft = text
            rule.waitForIdle()
        }
    }

    @Test
    fun controlsAreLabelledAndLargeEnough() {
        draft = "one\ntwo\nthree"
        mode = ComposerMode.STEER
        show()
        for (desc in listOf("Expand the editor", "Stop", "Send to the running reply")) {
            val b = rule.onNode(hasContentDescription(desc), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("$desc target $b", b.width >= px(48f) - 0.5f && b.height >= px(48f) - 0.5f)
            assertTrue(rule.onNode(hasContentDescription(desc), useUnmergedTree = true).fetchSemanticsNode().config.contains(SemanticsActions.OnClick))
        }
        assertFalse(bounds(STOP_TAG).overlaps(bounds(SEND_TAG)))
        // TalkBack: inside the card (a traversal group), the field first, then the buttons.
        assertEquals(-1f, field().fetchSemanticsNode().config[SemanticsProperties.TraversalIndex])
        var n: SemanticsNode? = rule.onNodeWithTag(FIELD_TAG, useUnmergedTree = true).fetchSemanticsNode().parent
        var group = false
        while (n != null && !group) {
            group = n.config.getOrNull(SemanticsProperties.IsTraversalGroup) == true
            n = n.parent
        }
        assertTrue("the card is a traversal group", group)
    }

    private fun layoutOf(tag: String): TextLayoutResult {
        val results = ArrayList<TextLayoutResult>()
        rule.onNodeWithTag(tag).fetchSemanticsNode().config[SemanticsActions.GetTextLayoutResult].action!!(results)
        return results.single()
    }

    @Test
    @Config(qualifiers = "pl-w360dp-h760dp-xhdpi")
    fun polishAtDoubleFontScaleIsNotClipped() {
        draft = "Zażółć gęślą jaźń\ndrugi wiersz\ntrzeci wiersz"
        mode = ComposerMode.STEER
        show(fontScale = 2f)
        for (desc in listOf("Rozwiń edytor", "Zatrzymaj", "Wyślij do trwającej odpowiedzi")) rule.onNodeWithContentDescription(desc).assertExists()
        val layout = layoutOf(FIELD_TAG)
        val f = bounds(FIELD_TAG)
        assertEquals(3, layout.lineCount)
        assertTrue("all three lines at 2.0 show: ${f.height}", f.height >= layout.getLineBottom(2) + px(8f) - 1) // at 2.0 the padding shrinks to about 4 dp
        // The buttons stay inside the card's column, below and beside each other, never over the field.
        assertFalse(described("Rozwiń edytor").overlaps(bounds(SEND_TAG)))
        assertFalse(described("Rozwiń edytor").overlaps(f))
        assertFalse(bounds(SEND_TAG).overlaps(f))
    }

    @Test
    fun rightToLeftPutsTheButtonsAtTheStart() {
        draft = "one\ntwo\nthree"
        show(rtl = true)
        val f = bounds(FIELD_TAG)
        assertTrue(bounds(SEND_TAG).right <= f.left + 1)
        assertTrue(described("Expand the editor").right <= f.left + 1)
    }

    /**
     * Without blur the glass shows what is under it; the card's page backplate keeps the placeholder (the faintest text)
     * at 4.5:1 over the worst content: a solid block of the text color, in light, dark and both seeds.
     */
    @Test
    fun placeholderKeepsItsContrastOverAnyContent() {
        var case by mutableStateOf(0)
        val cases = listOf(ThemeMode.LIGHT to null, ThemeMode.DARK to null, ThemeMode.LIGHT to PurpleSeed, ThemeMode.DARK to PurpleSeed, ThemeMode.LIGHT to GreenSeed, ThemeMode.DARK to GreenSeed)
        rule.setContent {
            val (theme, seed) = cases[case]
            ApofisTheme(theme, dynamicColor = seed != null, pureBlack = false, reduceMotion = true, blur = false, seed = seed) {
                Box(Modifier.testTag("probe").background(MaterialTheme.colorScheme.onSurface).padding(vertical = 24.dp)) {
                    Composer("", ComposerMode.SEND_DISABLED, {}, { false }, {})
                }
            }
        }
        for (i in cases.indices) {
            case = i
            rule.waitForIdle()
            val image = rule.onNodeWithTag("probe").captureToImage().toPixelMap()
            val probe = bounds("probe")
            val f = bounds(FIELD_TAG)
            var lo = 1f
            var hi = 0f
            // The placeholder's line: from the field's start padding, the first 60 dp, its middle 12 dp.
            val midY = (f.top + f.bottom) / 2 - probe.top
            for (y in (midY - px(6f)).toInt() until (midY + px(6f)).toInt()) for (x in (f.left + px(6f)).toInt() until (f.left + px(66f)).toInt()) {
                val l = image[x, y].luminance()
                lo = minOf(lo, l)
                hi = maxOf(hi, l)
            }
            val ratio = (hi + 0.05f) / (lo + 0.05f)
            assertTrue("case ${cases[i]}: placeholder contrast %.2f".format(ratio), ratio >= 4.5f)
        }
    }

    // ---- inside the chat screen ----

    private val history: List<ChatItem> = (1..6).flatMap {
        listOf(
            UserMessage("u$it", "Question $it", steered = false, timestamp = FIXED_NOW),
            AssistantReply("r$it", emptyList(), "Answer $it, long enough to wrap onto a second line of the chat screen.", FIXED_NOW),
        )
    }

    private var ui by mutableStateOf(ChatUiState("apofis-1", "Composer", StatusLine.IDLE, history, ComposerMode.SEND, "a", "hermes.example", draftRevision = 1))
    private val recorded = ArrayList<Pair<String, Long>>()
    private var chatSends = 0
    private val chatActions = object : ChatActions {
        override fun onDraftChange(text: String, basedOnRevision: Long) {
            recorded += text to basedOnRevision
        }

        override fun onSendClick(): Boolean {
            chatSends++
            return true
        }
    }
    private lateinit var list: LazyListState

    private fun showChat(restoration: StateRestorationTester? = null, actions: ChatActions = chatActions, enterToSend: Boolean = false) {
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                list = androidx.compose.foundation.lazy.rememberLazyListState()
                ChatScreen(ui, actions, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY, listState = list, enterToSend = enterToSend)
            }
        }
        if (restoration != null) restoration.setContent(content) else rule.setContent(content)
        rule.waitForIdle()
    }

    private fun shownText() = field().fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    @Test
    fun staleRevisionIsDropped() {
        showChat()
        field().performTextInputSelection(TextRange(1))
        field().performTextInput("b")
        rule.waitForIdle()
        assertEquals(listOf("ab" to 1L), recorded)
        assertEquals("the field shows the holder's draft, not the unconfirmed edit", "a", shownText())
        ui = ui.copy(draft = "", draftRevision = 2) // sent
        rule.waitForIdle()
        assertEquals("", shownText())
        ui = ui.copy(draft = "put back", draftRevision = 3) // Put back
        rule.waitForIdle()
        assertEquals("put back", shownText())
        assertEquals("a state change is never written back as an edit", 1, recorded.size)
        field().performTextInputSelection(TextRange(8))
        field().performTextInput("!")
        rule.waitForIdle()
        assertEquals("put back!" to 3L, recorded.last())
    }

    private val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
    private val holder = DraftHolder(scope)
    private val stores = ArrayList<String>()

    /** The real draft holder behind the screen; [ui] is set from it once, so the screen draws the last frame's snapshot. */
    private fun showWithHolder(initial: String) {
        val k = ui.sessionKey
        holder.edit(k, initial, holder.current(k).revision)
        val s = holder.current(k)
        ui = ui.copy(draft = s.draft, draftRevision = s.revision, composer = if (s.draft.isBlank()) ComposerMode.SEND_DISABLED else ComposerMode.SEND)
        val actions = object : ChatActions {
            override fun onDraftChange(text: String, basedOnRevision: Long) = holder.edit(k, text, basedOnRevision)
            override fun onSendClick(): Boolean = holder.send(k) { stores += it; awaitCancellation() } != null
        }
        showChat(actions = actions, enterToSend = true)
    }

    /** The first character and Enter in one UI turn, before the screen redraws: the holder sends it, once. */
    @Test
    fun firstCharacterThenEnterInOneTurnSends() {
        showWithHolder("")
        field().performClick()
        rule.waitForIdle()
        val ic = connection()
        rule.runOnUiThread {
            ic.commitText("x", 1)
            composeView().dispatchKeyEvent(key(NativeKeyEvent.ACTION_DOWN))
            composeView().dispatchKeyEvent(key(NativeKeyEvent.ACTION_UP))
            ic.performEditorAction(EditorInfo.IME_ACTION_SEND) // the keyboard's Send too: the draft is already taken
        }
        rule.waitForIdle()
        assertEquals(listOf("x"), stores)
        scope.cancel()
    }

    /** The last character deleted and Enter or Send in one UI turn: nothing is sent, the editor stays, the reader stays. */
    @Test
    fun lastCharacterDeletedThenEnterInOneTurnChangesNothing() {
        showWithHolder("\n\nx") // three lines, so it can expand; blank once the x goes
        rule.onNodeWithContentDescription("Expand the editor").performClick()
        rule.waitForIdle()
        rule.runOnIdle { runBlocking { list.scrollToItem(6) } } // a reader scrolled up
        rule.waitForIdle()
        field().performClick()
        field().performTextInputSelection(TextRange(3))
        rule.waitForIdle()
        val ic = connection()
        rule.runOnUiThread {
            ic.deleteSurroundingText(1, 0)
            composeView().dispatchKeyEvent(key(NativeKeyEvent.ACTION_DOWN))
            composeView().dispatchKeyEvent(key(NativeKeyEvent.ACTION_UP))
            ic.performEditorAction(EditorInfo.IME_ACTION_SEND)
        }
        rule.waitForIdle()
        assertEquals("\n\n", holder.current(ui.sessionKey).draft)
        assertEquals("a blank draft was sent", emptyList<String>(), stores)
        rule.onNodeWithContentDescription("Collapse the editor").assertExists()
        assertEquals("the reader was moved", 6, list.firstVisibleItemIndex)
        scope.cancel()
    }

    @Test
    fun backCollapsesBeforeLeaving() {
        ui = ui.copy(draft = "one\ntwo\nthree")
        showChat()
        rule.onNodeWithContentDescription("Expand the editor").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Collapse the editor").assertExists()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Expand the editor").assertExists()
        assertFalse("Back left the chat instead of collapsing", rule.activity.isFinishing)
        assertEquals(emptyList<Pair<String, Long>>(), recorded)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        assertTrue("collapsed, Back leaves", rule.activity.isFinishing)
    }

    /** With the drawer over the chat, Back closes the drawer first; the editor stays expanded with its draft, the next Back collapses it. */
    @OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
    @Test
    fun backClosesTheDrawerBeforeCollapsing() {
        ui = ui.copy(draft = "one\ntwo\nthree")
        lateinit var drawer: DrawerState
        lateinit var drawerScope: CoroutineScope
        rule.setContent {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                drawer = rememberDrawerState(DrawerValue.Closed)
                drawerScope = rememberCoroutineScope()
                // As AppNav does: the drawer's Back first in composition, the chat told when it is covered.
                BackHandler(drawer.isOpen) { drawerScope.launch { drawer.close() } }
                ModalNavigationDrawer(drawerContent = { ModalDrawerSheet { Text("Chats") } }, drawerState = drawer) {
                    ChatScreen(ui, chatActions, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY, covered = drawer.isOpen)
                }
            }
        }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Expand the editor").performClick()
        rule.waitForIdle()
        rule.runOnIdle { drawerScope.launch { drawer.open() } }
        rule.waitForIdle()
        assertTrue(drawer.isOpen)
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        assertTrue("Back did not close the drawer", drawer.isClosed)
        rule.onNodeWithContentDescription("Collapse the editor").assertExists()
        assertEquals("one\ntwo\nthree", shownText())
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Expand the editor").assertExists()
        assertFalse(rule.activity.isFinishing)
        assertEquals(emptyList<Pair<String, Long>>(), recorded)
    }

    /**
     * Expanded is not saved: recreation starts collapsed. In another chat on the same screen it starts collapsed too, and
     * Expand, Back and an accepted send all work there (the remembered slot writes the screen's one state).
     */
    @Test
    fun expandedResetsOnRecreationAndWorksInAnotherChat() {
        ui = ui.copy(draft = "one\ntwo\nthree")
        val tester = StateRestorationTester(rule)
        showChat(tester)
        rule.onNodeWithContentDescription("Expand the editor").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Collapse the editor").assertExists()
        ui = ui.copy(draft = "one\ntwo\nthree\nfour", draftRevision = 2) // the holder changed while the activity was away
        tester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Expand the editor").assertExists()
        assertEquals("one\ntwo\nthree\nfour", shownText())

        rule.onNodeWithContentDescription("Expand the editor").performClick()
        rule.waitForIdle()
        ui = ui.copy(sessionKey = "apofis-2", draft = "other\nchat\ndraft") // the same screen, another chat
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Expand the editor").assertExists()
        assertEquals("other\nchat\ndraft", shownText())
        rule.onNodeWithContentDescription("Expand the editor").performClick()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Collapse the editor").assertExists()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Expand the editor").assertExists()
        rule.onNodeWithContentDescription("Expand the editor").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag(SEND_TAG).performClick()
        rule.waitForIdle()
        assertEquals(1, chatSends)
        rule.onNodeWithContentDescription("Expand the editor").assertExists()
        assertEquals(emptyList<Pair<String, Long>>(), recorded)
    }

    /** Typing changes only the composer: the header and the transcript are not recomposed and the root is not measured. */
    @Test
    fun typingLeavesTranscriptAndHeaderAlone() {
        ui = ui.copy(draft = "", composer = ComposerMode.SEND_DISABLED)
        showChat()
        rule.mainClock.autoAdvance = false
        frames(5)
        val bar = ChromeProbe.barCompositions
        val body = ChromeProbe.bodyCompositions
        val bottom = ChromeProbe.bottomCompositions
        val root = ChromeProbe.rootMeasures
        for (i in 1..30) { // one line: the bottom area keeps its height
            ui = ui.copy(draft = "x".repeat(i), composer = ComposerMode.SEND)
            frames(1)
        }
        assertEquals("header recomposed by typing", bar, ChromeProbe.barCompositions)
        assertEquals("transcript recomposed by typing", body, ChromeProbe.bodyCompositions)
        assertEquals("root measured by typing", root, ChromeProbe.rootMeasures)
        assertTrue("control: the composer did see the typing", ChromeProbe.bottomCompositions > bottom)
    }
}
