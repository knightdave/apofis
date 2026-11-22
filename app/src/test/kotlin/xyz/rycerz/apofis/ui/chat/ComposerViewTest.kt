package xyz.rycerz.apofis.ui.chat

import android.view.KeyEvent as NativeKeyEvent
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
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
import androidx.compose.ui.input.key.KeyEvent
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
import androidx.compose.ui.test.performKeyPress
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
    fun enterSubmitsRule() {
        assertTrue(enterSubmits(true, shift = false, repeatCount = 0, ComposerMode.SEND, busy = false))
        assertTrue(enterSubmits(true, shift = false, repeatCount = 0, ComposerMode.STEER, busy = false))
        assertFalse(enterSubmits(true, shift = true, repeatCount = 0, ComposerMode.SEND, busy = false))
        assertFalse(enterSubmits(true, shift = false, repeatCount = 1, ComposerMode.SEND, busy = false))
        assertFalse(enterSubmits(true, shift = false, repeatCount = 0, ComposerMode.STOP, busy = false))
        assertFalse(enterSubmits(true, shift = false, repeatCount = 0, ComposerMode.SEND_DISABLED, busy = false))
        assertFalse(enterSubmits(true, shift = false, repeatCount = 0, ComposerMode.SEND, busy = true))
        assertFalse(enterSubmits(true, shift = false, repeatCount = 0, ComposerMode.STEER, busy = true))
        assertFalse(enterSubmits(false, shift = false, repeatCount = 0, ComposerMode.SEND, busy = false))
        // Every other combination is false too.
        for (e in listOf(true, false)) for (s in listOf(true, false)) for (r in 0..2) for (m in ComposerMode.entries) for (b in listOf(true, false)) {
            val expected = e && !s && r == 0 && !b && (m == ComposerMode.SEND || m == ComposerMode.STEER)
            assertEquals("$e $s $r $m $b", expected, enterSubmits(e, s, r, m, b))
        }
        assertTrue(canSubmit(ComposerMode.SEND, false) && canSubmit(ComposerMode.STEER, false))
        assertFalse(canSubmit(ComposerMode.STOP, false) || canSubmit(ComposerMode.SEND_DISABLED, false) || canSubmit(ComposerMode.SEND, true))
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
    private var sends = 0
    private var stops = 0
    private val edits = ArrayList<String>()
    private var onSendHook: () -> Unit = { sends++ }

    private fun show(expandedHeight: Dp = Dp.Unspecified, writeBack: Boolean = true, fontScale: Float = 1f, rtl: Boolean = false, theme: ThemeMode = ThemeMode.LIGHT) {
        rule.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale), LocalLayoutDirection provides if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr) {
                ApofisTheme(theme, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                    Box(Modifier.fillMaxSize().testTag("root"), contentAlignment = Alignment.BottomCenter) {
                        Composer(
                            draft, mode,
                            onDraftChange = {
                                edits += it
                                if (writeBack) draft = it
                            },
                            onSend = { onSendHook() },
                            onStop = { stops++ },
                            busy = busy, enterToSend = enter, expanded = expanded, onExpandedChange = { expanded = it }, expandedHeight = expandedHeight,
                        )
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

    @Test
    fun imeSendOnlyWhenSubmittable() {
        enter = true
        draft = "hi"
        show()
        assertEquals(ImeAction.Send, field().fetchSemanticsNode().config[SemanticsProperties.ImeAction])
        for ((m, b, expected) in listOf(
            Triple(ComposerMode.SEND, false, 1), Triple(ComposerMode.STEER, false, 1), Triple(ComposerMode.STOP, false, 0),
            Triple(ComposerMode.SEND, true, 0), Triple(ComposerMode.STEER, true, 0), Triple(ComposerMode.SEND_DISABLED, false, 0),
        )) {
            mode = m
            busy = b
            rule.waitForIdle()
            sends = 0
            field().performImeAction()
            rule.waitForIdle()
            assertEquals("$m busy=$b", expected, sends)
            assertEquals(0, stops)
        }
        assertEquals("the IME action never edits the draft", emptyList<String>(), edits)
        // Off: Return is a newline key, no Send action.
        enter = false
        rule.waitForIdle()
        assertEquals(ImeAction.Default, field().fetchSemanticsNode().config[SemanticsProperties.ImeAction])
    }

    private fun focusAtEnd() {
        field().performClick()
        field().performTextInputSelection(TextRange(draft.length))
        rule.waitForIdle()
    }

    private fun enterDown(repeat: Int) = KeyEvent(NativeKeyEvent(0L, 0L, NativeKeyEvent.ACTION_DOWN, NativeKeyEvent.KEYCODE_ENTER, repeat))

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

        field().performKeyInput { withKeyDown(Key.ShiftLeft) { pressKey(Key.Enter) } }
        rule.waitForIdle()
        assertEquals(1, sends)
        assertEquals(listOf("hi\n"), edits)

        edits.clear()
        field().performKeyPress(enterDown(repeat = 3)) // a held key's repeats
        rule.waitForIdle()
        assertEquals(1, sends)
        assertEquals(emptyList<String>(), edits)

        mode = ComposerMode.STOP
        rule.waitForIdle()
        field().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals("STOP: Enter neither sends", 1, sends)
        assertEquals("nor stops", 0, stops)
        assertEquals("and with Enter to send on it is consumed", emptyList<String>(), edits)

        enter = false
        rule.waitForIdle()
        field().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals("off, in STOP: a newline", 1, edits.size)
        mode = ComposerMode.SEND
        rule.waitForIdle()
        field().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals("off: Enter never sends", 1, sends)
        assertEquals("hi\n\n\n", draft)
    }

    /** A word the IME is still composing is never sent by Enter: the first Enter finishes it, the next one sends it whole. */
    @Test
    fun composingTextIsFinishedBeforeEnterSends() {
        enter = true
        draft = ""
        show()
        field().performClick()
        rule.waitForIdle()
        val content = rule.activity.findViewById<ViewGroup>(android.R.id.content)
        val view = (content.getChildAt(0) as ViewGroup).getChildAt(0)
        lateinit var ic: android.view.inputmethod.InputConnection
        rule.runOnUiThread { ic = view.onCreateInputConnection(EditorInfo())!! }
        rule.runOnUiThread { ic.setComposingText("wor", 1) }
        rule.waitForIdle()
        assertEquals("wor", draft)
        field().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals("composing text was sent", 0, sends)
        assertEquals("composing text was lost or changed", "wor", draft)
        field().performKeyInput { pressKey(Key.Enter) }
        rule.waitForIdle()
        assertEquals(1, sends)
        assertEquals("wor", draft)
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

    @Test
    fun collapsesOnAcceptedSubmission() {
        draft = "x"
        expanded = true
        show()
        val collapsed = px(48f) + 1
        assertTrue(bounds(FIELD_TAG).height > px(300f))
        rule.mainClock.autoAdvance = false
        rule.onNodeWithTag(SEND_TAG).performClick() // onSend counts only: nothing clears the draft
        frames(1)
        assertFalse(expanded)
        assertEquals(1, sends)
        assertTrue("collapsed at once: ${bounds(FIELD_TAG).height}", bounds(FIELD_TAG).height <= collapsed)

        // The IME's send collapses too; a refused submission (nothing to send) does not.
        rule.mainClock.autoAdvance = true
        enter = true
        expanded = true
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        field().performImeAction()
        frames(1)
        assertFalse(expanded)
        assertTrue(bounds(FIELD_TAG).height <= collapsed)
        rule.mainClock.autoAdvance = true
        mode = ComposerMode.SEND_DISABLED
        expanded = true
        rule.waitForIdle()
        field().performImeAction()
        rule.waitForIdle()
        assertTrue("refused: still expanded", expanded)
        assertEquals(2, sends)
        assertEquals(emptyList<String>(), edits)
    }

    /** Two presses in one frame, before the composer redraws: the holder takes the draft once. */
    @Test
    fun doublePressSendsOnce() {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val holder = DraftHolder(scope)
        holder.edit("k", "hello", holder.current("k").revision)
        var stores = 0
        onSendHook = { holder.send("k") { stores++; awaitCancellation() } }
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
        draft = "x".repeat(100_000)
        show()
        val fiveLines = px(5 * 19.5f + 28f) + 2
        assertTrue("100k chars: ${bounds(FIELD_TAG).height}", bounds(FIELD_TAG).height <= fiveLines)
        rule.onNodeWithContentDescription("Expand the editor").assertExists()
        draft = (1..10_000).joinToString("\n") { "line $it" }
        rule.waitForIdle()
        assertTrue("10k lines: ${bounds(FIELD_TAG).height}", bounds(FIELD_TAG).height <= fiveLines)
        expanded = true
        rule.waitForIdle()
        val root = bounds("root")
        val f = bounds(FIELD_TAG)
        assertTrue("expanded stays inside the window: $f", f.top >= root.top && f.bottom <= root.bottom)
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
        assertFalse(layout.didOverflowWidth)
        val f = bounds(FIELD_TAG)
        assertEquals(3, layout.lineCount)
        assertTrue("all three lines at 2.0 show: ${f.height}", f.height >= layout.getLineBottom(2) + px(28f) - 1)
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
                    Composer("", ComposerMode.SEND_DISABLED, {}, {}, {})
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
    private val chatActions = object : ChatActions {
        override fun onDraftChange(text: String, basedOnRevision: Long) {
            recorded += text to basedOnRevision
        }
    }

    private fun showChat(restoration: StateRestorationTester? = null) {
        val content: @androidx.compose.runtime.Composable () -> Unit = {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                ChatScreen(ui, chatActions, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY)
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

    /** Expanded survives rotation and process recreation (saved state); another chat starts collapsed. The text is always the holder's. */
    @Test
    fun expandedSurvivesRecreationAndResetsInAnotherChat() {
        ui = ui.copy(draft = "one\ntwo\nthree")
        val tester = StateRestorationTester(rule)
        showChat(tester)
        rule.onNodeWithContentDescription("Expand the editor").performClick()
        rule.waitForIdle()
        ui = ui.copy(draft = "one\ntwo\nthree\nfour", draftRevision = 2) // the holder changed while the activity was away
        tester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Collapse the editor").assertExists()
        assertEquals("one\ntwo\nthree\nfour", shownText())
        ui = ui.copy(sessionKey = "apofis-2", draft = "other\nchat\ndraft")
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Expand the editor").assertExists()
        assertEquals("other\nchat\ndraft", shownText())
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
