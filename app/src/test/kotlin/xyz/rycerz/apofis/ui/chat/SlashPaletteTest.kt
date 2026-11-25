package xyz.rycerz.apofis.ui.chat

import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.time.ZoneOffset
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.api.ChatModel
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.ChatItem
import xyz.rycerz.apofis.chat.Command
import xyz.rycerz.apofis.chat.DraftImage
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.run.Connection
import xyz.rycerz.apofis.run.LiveReply
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.FIXED_NOW
import xyz.rycerz.apofis.ui.FIXED_TODAY
import xyz.rycerz.apofis.ui.shot
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.Haptics
import xyz.rycerz.apofis.ui.theme.LocalHaptics
import xyz.rycerz.apofis.ui.theme.ViewHaptics
import xyz.rycerz.apofis.chat.Composer as DraftHolder
import android.view.KeyEvent as NativeKeyEvent

private val history: List<ChatItem> = (1..20).flatMap {
    listOf(UserMessage("u$it", "Question $it", steered = false, timestamp = FIXED_NOW), AssistantReply("a$it", emptyList(), "Answer $it", FIXED_NOW))
}

private fun state(draft: String, mode: ComposerMode = ComposerMode.SEND, live: LiveReply? = null, images: List<DraftImage> = emptyList()) =
    ChatUiState("k", "Chat", StatusLine.IDLE, withLive(history, live) { FIXED_NOW }, mode, draft, "hermes.example", live, draftRevision = 3, images = images)

/** A palette row by its visible word (a slash and the command word), merged into its clickable row. */
private fun row(word: String) = hasText("/$word")

/**
 * The slash palette above the composer on the hosted chat screen, with fake actions: a tap runs or fills only while the
 * draft is still the slash word the row was drawn for, a press never moves to another row, and the palette is the
 * first piece of the bottom area to give way in a short window.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class SlashPaletteTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun edgeToEdge() = rule.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(rule.activity.window, false) }

    private fun insets(ime: Int = 0, top: Int = 0) {
        rule.runOnUiThread {
            val i = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime)).setVisible(WindowInsetsCompat.Type.ime(), ime > 0)
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, top, 0, 0))
                .build()
            ViewCompat.dispatchApplyWindowInsets(rule.activity.window.decorView, i)
        }
        frames(5)
    }

    private class Recorder : ChatActions {
        val commands = ArrayList<Command>()
        val edits = ArrayList<Pair<String, Long>>()
        var sends = 0

        override fun onCommand(command: Command): Boolean {
            commands += command
            return true
        }

        override fun onDraftChange(text: String, basedOnRevision: Long) {
            edits += text to basedOnRevision
        }

        override fun onSendClick(): Boolean {
            sends++
            return true
        }
    }

    private var ui by mutableStateOf(state(""))
    private var actions: ChatActions = Recorder()
    private val recorder get() = actions as Recorder
    private var current by mutableStateOf<(() -> ChatUiState)?>(null)
    private var covered = false
    private var haptics: Haptics? = null
    private lateinit var inputMode: InputModeManager

    private fun frames(n: Int = 1) = repeat(n) {
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeByFrame()
    }

    private fun host(start: ChatUiState) {
        ui = start
        rule.mainClock.autoAdvance = false
        rule.setContent {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                inputMode = LocalInputModeManager.current
                val screen = @Composable {
                    ChatScreen(ui, actions, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY, currentState = current, coveredNow = { covered })
                }
                val h = haptics
                if (h == null) screen() else CompositionLocalProvider(LocalHaptics provides h) { screen() }
            }
        }
        frames(10)
    }

    private fun tap(m: SemanticsMatcher) {
        rule.onNode(m).performClick()
        frames(5)
    }

    private fun count(m: SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().size

    private fun bounds(m: SemanticsMatcher): Rect = rule.onNode(m).fetchSemanticsNode().boundsInRoot // a row: the whole clickable row

    private val palette = hasTestTag("chat.palette")
    private val field get() = rule.onNode(hasTestTag(FIELD_TAG))

    private fun composeView(): View = (rule.activity.findViewById<ViewGroup>(android.R.id.content).getChildAt(0) as ViewGroup).getChildAt(0)

    /** A hardware key pressed and released through the view, in one UI turn: it goes to the focused node. */
    private fun press(code: Int) = rule.runOnUiThread {
        composeView().dispatchKeyEvent(NativeKeyEvent(0L, 0L, NativeKeyEvent.ACTION_DOWN, code, 0, 0))
        composeView().dispatchKeyEvent(NativeKeyEvent(0L, 0L, NativeKeyEvent.ACTION_UP, code, 0, 0))
    }

    /** The real draft holder behind the screen, copied into [ui] after every edit as the view model's state does. */
    private fun holderActions(start: String, commands: MutableList<Command> = ArrayList()): Pair<DraftHolder, CoroutineScope> {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val holder = DraftHolder(scope)
        holder.edit("k", start, holder.current("k").revision)
        fun synced() = holder.current("k").let { ui.copy(draft = it.draft, draftRevision = it.revision) }
        actions = object : ChatActions {
            override fun onDraftChange(text: String, basedOnRevision: Long) {
                holder.edit("k", text, basedOnRevision)
                ui = synced()
            }

            override fun onCommand(command: Command): Boolean {
                commands += command
                holder.edit("k", "", holder.current("k").revision) // the dispatch clears the claimed text
                ui = synced()
                return true
            }
        }
        ui = synced()
        return holder to scope
    }

    @Test
    fun noArgTapRuns() {
        host(state("/st"))
        assertEquals("the commands that start with the word", 2, count(row("stop")) + count(row("steer")))
        tap(row("stop"))
        assertEquals(listOf(Command.STOP), recorder.commands)
        assertEquals("the draft is not sent", 0, recorder.sends)
        assertTrue("nor edited", recorder.edits.isEmpty())

        // The draft at tap time has text after the word, though the screen still draws "/st": the tap does nothing.
        current = { ui.copy(draft = "/stop now") }
        frames(3)
        tap(row("stop"))
        assertEquals(listOf(Command.STOP), recorder.commands)
        assertTrue(recorder.edits.isEmpty())

        // The draft at tap time matches another command only: nothing; one that still matches this one: it runs.
        current = { ui.copy(draft = "/n") }
        frames(3)
        tap(row("stop"))
        assertEquals(listOf(Command.STOP), recorder.commands)
        current = { ui.copy(draft = "/s") }
        frames(3)
        tap(row("stop"))
        assertEquals(listOf(Command.STOP, Command.STOP), recorder.commands)
    }

    @Test
    fun argTapFills() {
        host(state("/qu"))
        field.performClick()
        frames(5)
        tap(row("queue"))
        assertEquals(listOf("/queue " to 3L), recorder.edits)
        assertTrue("a text command never runs from the palette", recorder.commands.isEmpty())
        field.assertIsFocused()

        // The draft at tap time has text after the word, though the screen still draws "/qu": the fill would replace it.
        current = { ui.copy(draft = "/queue now") }
        frames(3)
        tap(row("queue"))
        assertEquals(1, recorder.edits.size)
        current = null
        frames(3)

        covered = true // the drawer covers the chat: the fill is dropped
        tap(row("queue"))
        assertEquals(1, recorder.edits.size)
    }

    @Test
    fun argTapThenTypingAppends() {
        val scope = CoroutineScope(Job() + Dispatchers.Unconfined)
        val holder = DraftHolder(scope)
        holder.edit("k", "/q", holder.current("k").revision)
        fun synced() = holder.current("k").let { ui.copy(draft = it.draft, draftRevision = it.revision) }
        actions = object : ChatActions {
            override fun onDraftChange(text: String, basedOnRevision: Long) {
                holder.edit("k", text, basedOnRevision)
                ui = synced() // the screen draws the holder after every edit, as the view model's state does
            }
        }
        host(state("").let { s -> holder.current("k").let { s.copy(draft = it.draft, draftRevision = it.revision) } })
        field.performClick()
        frames(5)
        tap(row("queue"))
        assertEquals("/queue ", holder.current("k").draft)
        assertEquals("a filled command has no matches", 0, count(palette))
        field.performTextInput("x") // no selection set: the cursor is after the space
        frames(5)
        assertEquals("/queue x", holder.current("k").draft)
        scope.cancel()
    }

    @Test
    fun pressBegunOnOneRowNeverRunsAnother() {
        host(state("/n"))
        val under = bounds(row("new"))
        rule.onNode(row("new")).performTouchInput { down(center) }
        frames(2)
        ui = ui.copy(draft = "/q")
        frames(5)
        assertEquals("the row under the finger is another command now", under, bounds(row("queue")))
        rule.onRoot().performTouchInput { up() }
        frames(5)
        assertTrue(recorder.commands.isEmpty())
        assertTrue(recorder.edits.isEmpty())
    }

    @Test
    fun paletteBoundedAndScrolls() {
        host(state("/"))
        val rowHeight = bounds(row("new")).height
        val height = bounds(palette).height
        val order = rule.onNode(palette, useUnmergedTree = true).fetchSemanticsNode().config[SemanticsProperties.TraversalIndex]
        assertEquals("read with the notes and the strip, before the field", -2f, order)
        assertEquals("four and a half rows and the two 1 dp borders", 4.5f * rowHeight + 4f, height, 1f)
        rule.onNode(row("fast")).assertIsNotDisplayed()
        rule.mainClock.autoAdvance = true // the scroll action runs on clock frames
        rule.onNode(row("fast")).performScrollTo()
        rule.mainClock.autoAdvance = false
        frames(3)
        rule.onNode(row("fast")).assertIsDisplayed()
        val p = bounds(palette)
        val fast = bounds(row("fast"))
        assertTrue("$fast inside $p", fast.top >= p.top - 0.5f && fast.bottom <= p.bottom + 0.5f)

        ui = ui.copy(draft = "/m")
        frames(5)
        assertEquals("no model chosen", 1, count(hasText("/model") and hasText("Server default")))
        ui = ui.copy(choice = ChatModel("hermes-large"))
        frames(5)
        assertEquals("the chat's model", 1, count(hasText("/model") and hasText("hermes-large")))

        ui = ui.copy(draft = "/ hello")
        frames(5)
        assertEquals("not a slash word: no palette", 0, count(palette))
        ui = ui.copy(draft = "/", sending = true)
        frames(5)
        assertEquals("a draft of just a slash being stored: no palette", 0, count(palette))
        ui = ui.copy(draft = "/st", sending = false, status = StatusLine.LOAD_FAILED)
        frames(5)
        assertEquals("a chat that failed to load: no palette", 0, count(palette))
    }

    /** A 480 dp window: the palette takes at most half the band, gives way to the rest, and comes back with the room. */
    @Test
    @Config(qualifiers = "w360dp-h480dp-xhdpi")
    fun paletteFitsShortWindowWithIme() {
        host(state("/"))
        insets(top = 48)
        val natural = bounds(palette).height
        val oneRow = bounds(hasTestTag(FIELD_TAG)).height
        insets(top = 48, ime = 80) // a 40 dp keyboard: 416 dp of band, 216 dp left after the rest, 218 dp natural
        assertEquals("half the band", 416f, bounds(palette).height, 0.5f)
        insets(top = 48, ime = 400) // 24 dp status, 200 dp keyboard: 256 dp of safe band
        val bar = rule.onNode(hasTestTag("chat.topbar.row"), useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("the bar is placed whole: ${bar.boundsInRoot}", bar.layoutInfo.isPlaced && bar.boundsInRoot.top >= 48f)
        assertEquals("the field keeps its row", oneRow, bounds(hasTestTag(FIELD_TAG)).height, 0.5f)
        assertTrue("Send keeps 48 dp", bounds(hasTestTag(SEND_TAG)).height >= 96f - 0.5f)
        val p = bounds(palette)
        assertTrue("the palette shrank: $p of $natural", p.height in 1f..natural - 1f)
        assertTrue("above the field", p.bottom <= bounds(hasTestTag(FIELD_TAG)).top)
        assertTrue("above the keyboard", bounds(hasTestTag(SEND_TAG)).bottom <= 960f - 400f + 0.5f)

        insets(top = 48, ime = 512) // 200 dp band: the bare row, the bar, the transcript and the card take it all
        assertEquals("no room: the palette is gone", 0, count(palette))
        assertEquals(oneRow, bounds(hasTestTag(FIELD_TAG)).height, 0.5f)
        insets(top = 48, ime = 0)
        assertEquals("back whole with the room", natural, bounds(palette).height, 0.5f)
    }

    /**
     * 480 dp with a 120 dp keyboard, two draft photos and the draft "/qu": the bar is whole, the field and Send keep 48
     * dp, the strip stays whole and the palette is the piece that shrank.
     */
    @Test
    @Config(qualifiers = "w360dp-h480dp-xhdpi")
    fun paletteAndDraftStripShareTheBudget() {
        host(state("/qu", images = listOf(DraftImage(1, "%064x".format(1)), DraftImage(2, "%064x".format(2)))))
        insets(top = 48)
        val natural = bounds(palette).height
        val oneRow = bounds(hasTestTag(FIELD_TAG)).height
        insets(top = 48, ime = 240)
        val bar = rule.onNode(hasTestTag("chat.topbar.row"), useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("the bar is placed whole: ${bar.boundsInRoot}", bar.layoutInfo.isPlaced && bar.boundsInRoot.top >= 48f)
        assertEquals("the field keeps its row", oneRow, bounds(hasTestTag(FIELD_TAG)).height, 0.5f)
        assertTrue("the field's row is the bare 48 dp row", bounds(hasTestTag(FIELD_TAG)).height >= 95f - 0.5f) // one 19.5 dp line and its padding
        assertTrue("Send keeps 48 dp", bounds(hasTestTag(SEND_TAG)).height >= 96f - 0.5f)
        assertEquals("the strip is whole", 2, rule.onAllNodes(hasContentDescription("Remove image"), useUnmergedTree = true).fetchSemanticsNodes().size)
        val p = bounds(palette)
        assertTrue("the palette is the piece that shrank: $p of $natural", p.height in 1f..natural - 1f)
    }

    @Test
    fun oneHapticPerTap() {
        val calls = ArrayList<Int>()
        haptics = ViewHaptics({ calls += it; true }, { true })
        host(state("/st"))
        tap(row("stop"))
        assertEquals("one selection feedback", listOf(HapticFeedbackConstants.CLOCK_TICK), calls)
        ui = ui.copy(draft = "/q")
        frames(5)
        tap(row("queue"))
        assertEquals(listOf(HapticFeedbackConstants.CLOCK_TICK, HapticFeedbackConstants.CLOCK_TICK), calls)
    }

    /** The field keeps its own cursor across its own edits: "ab", cursor at 1, then "x" and "y" give "axyb". */
    @Test
    fun ownCursorKeptAcrossOwnEdits() {
        val (holder, scope) = holderActions("ab")
        host(ui)
        field.performClick()
        frames(5)
        field.performTextInputSelection(TextRange(1))
        frames(2)
        field.performTextInput("x")
        frames(5)
        field.performTextInput("y") // no new selection
        frames(5)
        assertEquals("axyb", holder.current("k").draft)
        scope.cancel()
    }

    /** Moving the cursor recomposes the field only: neither the rest of the bottom area nor the transcript. */
    @Test
    fun selectionChangeRecomposesOnlyTheField() {
        host(state("hello world"))
        field.performClick()
        frames(5)
        val bottom = ChromeProbe.bottomCompositions
        val body = ChromeProbe.bodyCompositions
        for (i in 0..5) {
            field.performTextInputSelection(TextRange(i))
            frames(2)
        }
        assertEquals(TextRange(5), field.fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        assertEquals("the bottom area", bottom, ChromeProbe.bottomCompositions)
        assertEquals("the transcript", body, ChromeProbe.bodyCompositions)
        assertTrue("a selection is not an edit", recorder.edits.isEmpty())
    }

    /** A row run or filled from a hardware keyboard gives focus back to the field, so the next keys type there. */
    @Test
    fun keyActivatedRowsReturnFocusToTheField() {
        val commands = ArrayList<Command>()
        val (holder, scope) = holderActions("/s", commands)
        host(ui)
        field.performClick()
        frames(5)
        rule.runOnUiThread { inputMode.requestInputMode(InputMode.Keyboard) }
        frames(2)
        rule.onNode(row("stop")).requestFocus()
        frames(2)
        rule.onNode(row("stop")).assertIsFocused()
        press(NativeKeyEvent.KEYCODE_ENTER)
        frames(5)
        assertEquals(listOf(Command.STOP), commands)
        assertEquals(0, count(palette))
        field.assertIsFocused()

        holder.edit("k", "/s", holder.current("k").revision)
        ui = holder.current("k").let { ui.copy(draft = it.draft, draftRevision = it.revision) }
        frames(5)
        rule.onNode(row("steer")).requestFocus()
        frames(2)
        rule.onNode(row("steer")).assertIsFocused()
        press(NativeKeyEvent.KEYCODE_ENTER)
        frames(5)
        assertEquals("/steer ", holder.current("k").draft)
        assertEquals(0, count(palette))
        field.assertIsFocused()
        press(NativeKeyEvent.KEYCODE_X)
        frames(5)
        assertEquals("the next key types after the fill", "/steer x", holder.current("k").draft)
        scope.cancel()
    }

    /** An expanded editor shows no palette, even for a draft that is a slash word. */
    @Test
    fun noPaletteWhileExpanded() {
        host(state("one\ntwo\nthree"))
        tap(hasContentDescription("Expand the editor"))
        frames(10)
        ui = ui.copy(draft = "/")
        frames(5)
        assertEquals(1, count(hasContentDescription("Collapse the editor")))
        assertEquals(0, count(palette))
    }

    /**
     * A key on a focused row in the frame its share becomes zero, before the palette is removed, runs nothing; when the
     * room comes back the row does not get focus back.
     */
    @Test
    @Config(qualifiers = "w360dp-h480dp-xhdpi")
    fun keyOnARowWithoutRoomRunsNothing() {
        host(state("/st"))
        insets(top = 48)
        rule.runOnUiThread { inputMode.requestInputMode(InputMode.Keyboard) }
        frames(2)
        rule.onNode(row("stop")).requestFocus()
        frames(2)
        rule.onNode(row("stop")).assertIsFocused()
        rule.runOnUiThread { // a 256 dp keyboard: a 200 dp band, no room left for the palette
            val i = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, 512)).setVisible(WindowInsetsCompat.Type.ime(), true)
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, 48, 0, 0))
                .build()
            ViewCompat.dispatchApplyWindowInsets(rule.activity.window.decorView, i)
        }
        var unplaced = false
        for (f in 1..5) {
            frames(1)
            val nodes = rule.onAllNodes(palette, useUnmergedTree = true).fetchSemanticsNodes()
            if (nodes.size == 1 && !nodes[0].layoutInfo.isPlaced) {
                unplaced = true
                break
            }
        }
        assertTrue("a frame with the palette measured to nothing but still composed", unplaced)
        press(NativeKeyEvent.KEYCODE_ENTER)
        frames(5)
        assertTrue(recorder.commands.isEmpty())
        assertEquals(0, count(palette))
        insets(top = 48, ime = 0)
        assertEquals(1, count(row("stop")))
        rule.onNode(row("stop")).assertIsNotFocused()
    }

    /** A draft is matched against the commands once when it changes, never per streamed update, and never when long. */
    @Test
    fun paletteScannedOncePerDraftAndNeverWhenLong() {
        val live = LiveReply("run_1", "running", "Work", emptyList(), null, Connection.LIVE)
        fun stream(from: Int) {
            for (t in from until from + 5) {
                val more = live.copy(text = "Work" + " more".repeat(t))
                ui = ui.copy(live = more, items = withLive(history, more) { FIXED_NOW })
                frames(2)
            }
        }
        val start = ChromeProbe.paletteScans
        host(state("/" + "A".repeat(99_999), ComposerMode.STOP, live))
        stream(1)
        assertEquals("a draft longer than any slash word is never matched", start, ChromeProbe.paletteScans)
        ui = ui.copy(draft = "/")
        frames(5)
        assertEquals(1, count(row("new")))
        val once = ChromeProbe.paletteScans
        stream(6)
        assertEquals("not per streamed update", once, ChromeProbe.paletteScans)
    }

    /** Streamed tokens never recompose the composer while the palette shows. */
    @Test
    fun chromeStableWithPalette() {
        val live = LiveReply("run_1", "running", "Work", emptyList(), null, Connection.LIVE)
        host(state("/", ComposerMode.STOP, live))
        frames(5)
        assertEquals(1, count(row("new")))
        val bottom = ChromeProbe.bottomCompositions
        for (t in 1..5) {
            val more = live.copy(text = "Work" + " more".repeat(t))
            ui = ui.copy(live = more, items = withLive(history, more) { FIXED_NOW })
            frames(2)
        }
        assertEquals("the composer with its palette", bottom, ChromeProbe.bottomCompositions)
        assertEquals(1, count(row("new")))
    }
}

/** The palette's golden: draft "/", the first four commands and the half row of /title (the 4.5-row cap); /model is below it. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class SlashPaletteScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun slash_palette() = rule.shot("slash_palette") {
        ChatScreen(state("/"), object : ChatActions {}, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY)
    }
}
