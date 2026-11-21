package xyz.rycerz.apofis.ui.chat

import android.os.Looper
import android.view.ViewConfiguration
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.AnnotatedString
import java.time.Duration
import java.time.ZoneOffset
import org.robolectric.Shadows.shadowOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.ChatItem
import xyz.rycerz.apofis.chat.SendState
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.run.Connection
import xyz.rycerz.apofis.run.LiveReply
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.FIXED_NOW
import xyz.rycerz.apofis.ui.FIXED_TODAY
import xyz.rycerz.apofis.ui.markdown.COPY_NOTE_MS
import xyz.rycerz.apofis.ui.markdown.MAX_CLIP_CHARS
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.Motion

/** Action rows, the user bubble collapse, the Select view and the "just finished" entry. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class MessagesUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val frame = 16L

    private class FakeClipboard : Clipboard {
        val writes = ArrayList<String?>()
        override suspend fun getClipEntry(): ClipEntry? = null
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            writes += clipEntry?.clipData?.getItemAt(0)?.text?.toString()
        }
        override val nativeClipboard get() = throw UnsupportedOperationException()
    }

    @Composable
    private fun Themed(reduceMotion: Boolean = true, clipboard: Clipboard = FakeClipboard(), content: @Composable () -> Unit) =
        ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = reduceMotion, blur = false) {
            CompositionLocalProvider(LocalClipboard provides clipboard, content = content)
        }

    @Composable
    private fun List(items: List<ChatItem>, onSelect: (String) -> Unit = {}, justFinished: String? = null) = MessageList(
        items, null, emptyMap(), answering = false, actions = object : ChatActions {}, onCancel = {}, onStep = {},
        listState = rememberLazyListState(), contentPadding = PaddingValues(), zone = ZoneOffset.UTC, today = FIXED_TODAY,
        onSelect = onSelect, justFinishedKey = justFinished,
    )

    private fun state(items: List<ChatItem>, live: LiveReply? = null) =
        ChatUiState("s", "Chat", StatusLine.IDLE, withLive(items, live) { FIXED_NOW }, ComposerMode.SEND_DISABLED, "", live = live)

    private fun count(description: String) = rule.onAllNodes(hasContentDescription(description)).fetchSemanticsNodes().size

    private fun reply(key: String, text: String) = AssistantReply(key, emptyList(), text, FIXED_NOW)

    @Test
    fun actionRowOnlyWhenNotLive() {
        var items by mutableStateOf<List<ChatItem>>(Joined(emptyList(), listOf(AssistantReply("r1", emptyList(), "Hello there", FIXED_NOW, live = true))))
        rule.setContent { Themed { List(items) } }
        assertEquals(0, count("Copy"))
        assertEquals(0, count("Select text"))
        items = listOf(reply("r1", "Hello there"))
        rule.waitForIdle()
        assertEquals(1, count("Copy"))
        assertEquals(1, count("Select text"))
        items = listOf(reply("r1", " \n\t "))
        rule.waitForIdle()
        assertEquals("a blank reply has nothing to copy", 0, count("Copy"))
    }

    /** The action row of each reply on screen (start aligned, unlike a user bubble's): clipped height and full height. */
    private fun replyRows(): List<Pair<Int, Int>> = rule.onAllNodes(hasContentDescription("Copy")).fetchSemanticsNodes()
        .filter { it.boundsInRoot.left < 100f }
        .sortedBy { it.positionInRoot.y }
        .map { it.boundsInRoot.height.toInt() to it.size.height }

    @Test
    fun actionRowEntersOnlyForTheReplyThatJustFinished() {
        rule.mainClock.autoAdvance = false
        val history = listOf(reply("r1", "One"), reply("r2", "Two"), reply("r3", "Three"))
        val asked = UserMessage("u4", "Four?", false, FIXED_NOW, runId = "run")
        var state by mutableStateOf(state(history))
        rule.setContent { Themed(reduceMotion = false) { ChatScreen(state, object : ChatActions {}, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY) } }
        rule.mainClock.advanceTimeByFrame()
        val opened = replyRows()
        assertEquals(3, opened.size)
        assertTrue("historical rows are full on the first frame: $opened", opened.all { (shown, full) -> shown == full && full > 0 })

        // A state write reaches composition through the main looper (waitForIdle), then the next frame composes it.
        state = state(history + asked, LiveReply("run", "running", "Fo", emptyList(), null, Connection.LIVE))
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        assertEquals(1, rule.onAllNodes(hasText("Fo")).fetchSemanticsNodes().size)
        assertEquals(3, replyRows().size)
        state = state(history + asked + reply("r9", "Four."))
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame() // composes r9's row, hidden
        rule.mainClock.advanceTimeByFrame() // its animation takes this frame as its start
        assertEquals(0, replyRows().last().first)
        rule.mainClock.advanceTimeByFrame()
        val entering = replyRows()
        assertEquals(4, entering.size)
        assertEquals(opened, entering.take(3))
        val (shown, full) = entering.last()
        assertTrue("r9 is mid-animation: $shown of $full", shown in 1 until full)
        repeat(((Motion.ACTION_ROW + frame - 1) / frame).toInt()) {
            rule.mainClock.advanceTimeByFrame()
            assertEquals("no other row animates", opened, replyRows().take(3))
        }
        val (after, fullAfter) = replyRows().last()
        assertEquals(fullAfter, after)
    }

    @Test
    fun justFinishedFollowsTheRunThatWasLive() {
        val r1 = reply("r1", "One")
        val live = AssistantReply("live", emptyList(), "Tw", FIXED_NOW, live = true)
        val running = Joined(listOf<ChatItem>(r1), listOf<ChatItem>(live))
        val idle = FinishTracker()
        assertEquals("nothing before a run", idle, idle.next(false, listOf(r1)))
        val armed = idle.next(true, running)
        assertEquals(FinishTracker(before = "r1", armed = true), armed)
        assertEquals("same run, same state", armed, armed.next(true, running))
        assertEquals("the stored reply has not arrived yet", armed, armed.next(false, listOf(r1)))
        val done = armed.next(false, listOf(r1, reply("r2", "Two")))
        assertEquals(FinishTracker(key = "r2"), done)
        assertEquals("later updates keep it", done, done.next(false, listOf(r1, reply("r2", "Two."))))
        assertEquals("a new run clears it", FinishTracker(before = "r2", armed = true), done.next(true, Joined(listOf<ChatItem>(r1, reply("r2", "Two")), listOf<ChatItem>(live))))
        // Same key across the swap: the transcript already held the reply while it streamed.
        val sameKey = idle.next(true, Joined(listOf<ChatItem>(r1), listOf<ChatItem>(AssistantReply("r2", emptyList(), "T", FIXED_NOW, live = true))))
        assertEquals(FinishTracker(key = "r2"), sameKey.next(false, listOf(r1, reply("r2", "Two"))))
        assertEquals("first run in an empty chat", FinishTracker(key = "r1"), idle.next(true, Joined(emptyList(), listOf<ChatItem>(live))).next(false, listOf(r1)))
        assertEquals(FinishTracker(before = "r1", armed = true, key = null), FinishTracker.restore(FinishTracker.save(armed)))
        assertEquals(done, FinishTracker.restore(FinishTracker.save(done)))
    }

    @Test
    fun storedBubbleWithoutKeyHasActions() {
        val clipboard = FakeClipboard()
        var selected: String? = null
        val message = UserMessage("u1", "first\r\nsecond", false, FIXED_NOW, SendState.SENT, idempotencyKey = null)
        rule.setContent { Themed(clipboard = clipboard) { List(listOf(message), onSelect = { selected = it }) } }
        rule.onNodeWithContentDescription("Copy").performClick()
        rule.waitForIdle()
        assertEquals(listOf<String?>("first\nsecond"), clipboard.writes)
        rule.onNodeWithContentDescription("Select text").performClick()
        assertEquals("u1", selected)
    }

    @Test
    fun unsentBubblesKeepTheirControlsAndHaveNoActions() {
        val items = listOf(
            UserMessage("p1", "queued", false, FIXED_NOW, SendState.QUEUED, idempotencyKey = "k1"),
            UserMessage("p2", "sending", false, FIXED_NOW, SendState.SENDING, idempotencyKey = "k2"),
            UserMessage("p3", "failed", false, FIXED_NOW, SendState.FAILED, idempotencyKey = "k3"),
        )
        rule.setContent { Themed { List(items) } }
        assertEquals(0, count("Copy"))
        rule.onNodeWithText("Retry").assertExists()
    }

    /** Past the double-tap timeout on the uptime clock the action rows read. */
    private fun later() = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ViewConfiguration.getDoubleTapTimeout() + 1L))

    @Test
    fun aTapRunsUnlessItCompletesADoubleTap() {
        val timeout = ViewConfiguration.getDoubleTapTimeout().toLong()
        assertTrue(acceptsTap(Long.MIN_VALUE / 2, 0))
        assertTrue(!acceptsTap(1000, 1000 + timeout - 1))
        assertTrue(acceptsTap(1000, 1000 + timeout))
    }

    @Test
    fun actionsRunOnceAndReadTheTranscriptWhenTapped() {
        rule.mainClock.autoAdvance = false
        val clipboard = FakeClipboard()
        val selected = ArrayList<String>()
        var items by mutableStateOf<List<ChatItem>>(listOf(reply("r1", "Hello")))
        rule.setContent { Themed(clipboard = clipboard) { List(items, onSelect = { selected += it }) } }
        rule.mainClock.advanceTimeByFrame()
        val copy = rule.onNodeWithContentDescription("Copy")
        copy.performClick()
        copy.performClick()
        rule.mainClock.advanceTimeBy(5 * frame)
        assertEquals("a double tap copies once", listOf<String?>("Hello"), clipboard.writes)
        rule.onNodeWithText("Copied").assertExists()
        later()
        copy.performClick()
        rule.mainClock.advanceTimeBy(5 * frame)
        assertEquals(2, clipboard.writes.size)
        rule.mainClock.advanceTimeBy(COPY_NOTE_MS + 5 * frame)

        // The row reads the transcript when tapped, by key: the same row showing a refreshed text copies that text.
        items = listOf(reply("r1", "Hello, edited"))
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        later()
        copy.performClick()
        rule.mainClock.advanceTimeBy(5 * frame)
        assertEquals("Hello, edited", clipboard.writes.last())
        rule.onNodeWithContentDescription("Select text").performClick()
        rule.onNodeWithContentDescription("Select text").performClick()
        assertEquals("a double tap selects once", listOf("r1"), selected)
        later()
        rule.onNodeWithContentDescription("Select text").performClick()
        assertEquals(2, selected.size)
    }

    @Test
    fun copyRefusesTooLargeTextVisibly() {
        val clipboard = FakeClipboard()
        rule.setContent { Themed(clipboard = clipboard) { List(listOf(reply("r1", "x".repeat(MAX_CLIP_CHARS + 1)))) } }
        rule.onNodeWithContentDescription("Copy").performClick()
        rule.waitForIdle()
        assertTrue(clipboard.writes.isEmpty())
        rule.onNodeWithText("Too large to copy").assertExists()
    }

    private fun bubbleText(): String = rule.onAllNodes(hasAnyAncestor(hasTestTag("user:u1")) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Text), useUnmergedTree = true)
        .fetchSemanticsNodes().map(::text).maxBy { it.length }

    private fun text(node: SemanticsNode) = node.config[SemanticsProperties.Text].joinToString("", transform = AnnotatedString::text)

    @Test
    fun longUserMessageCollapses() {
        val text = (1..30).joinToString("\n") { "line $it" }
        rule.setContent { Themed { List(listOf(UserMessage("u1", text, false, FIXED_NOW))) } }
        rule.onNodeWithText("Show more").performClick()
        rule.onNodeWithText("Show less").assertExists()
        assertEquals(text, bubbleText())
        rule.onNodeWithText("Show less").performClick()
        rule.onNodeWithText("Show more").assertExists()
    }

    @Test
    fun shortUserMessageHasNoToggle() {
        val nine = (1..USER_COLLAPSED_LINES).joinToString("\n") { "line $it" }
        rule.setContent { Themed { List(listOf(UserMessage("u1", nine, false, FIXED_NOW))) } }
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodes(hasText("Show more")).fetchSemanticsNodes().size)
        assertEquals(nine, bubbleText())
    }

    @Test
    fun hugeUserMessageIsNotLaidOutWholeWhenCollapsed() {
        var message by mutableStateOf(UserMessage("u1", "", false, FIXED_NOW))
        rule.setContent { Themed { List(listOf(message)) } }
        for (text in listOf("w".repeat(100_000), "line\n".repeat(10_000), "😀".repeat(50_000))) {
            message = UserMessage("u1", text, false, FIXED_NOW)
            rule.waitForIdle()
            val shown = bubbleText()
            assertTrue("collapsed shows a bounded prefix: ${shown.length}", shown.length <= COLLAPSED_MAX_CHARS)
            assertTrue(shown.lines().size <= USER_COLLAPSED_LINES + 1)
            assertTrue(text.startsWith(shown))
            assertTrue("never a lone half of a surrogate pair", !shown.last().isHighSurrogate())
            rule.onNodeWithText("Show more").performClick()
            rule.waitForIdle()
            assertEquals("expanded shows it all", text, bubbleText())
            rule.onNodeWithText("Show less").performClick()
            rule.waitForIdle()
        }
    }

    @Test
    fun oddTextNeverThrowsAndCopiesAsStored() {
        val odd = listOf("", " ", "\uD800", "a\uDC00b", "‮evil‬", "a\r\nb\rc", "‏\n\n\n")
        for (text in odd) {
            val prefix = collapsedPrefix(text)
            assertTrue(text.startsWith(prefix))
            assertEquals(lf(text).takeIf { it.isNotBlank() }, actionText(listOf(reply("r1", text)), "r1"))
            assertEquals(lf(text), actionText(listOf(UserMessage("u1", text, false, FIXED_NOW)), "u1"))
        }
        assertNull(actionText(listOf(UserMessage("p1", "x", false, FIXED_NOW, SendState.QUEUED, "k")), "p1"))
        assertNull(actionText(listOf(AssistantReply("r1", emptyList(), "x", FIXED_NOW, live = true)), "r1"))
        assertNull(actionText(listOf(reply("r1", "x")), "r2"))
    }

    @Test
    fun selectOpensFullScreenText() {
        var state by mutableStateOf(state(listOf(reply("r1", "Pick some of these words"))))
        rule.setContent { Themed { ChatScreen(state, object : ChatActions {}, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY) } }
        fun inDialog(text: String) = rule.onAllNodes(hasText(text) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().size
        rule.onNodeWithContentDescription("Select text").performClick()
        rule.onNodeWithText("Select text").assertExists()
        assertEquals(1, inDialog("Pick some of these words"))
        rule.onNodeWithContentDescription("Back").performClick()
        assertEquals(0, rule.onAllNodes(isDialog()).fetchSemanticsNodes().size)

        // The view follows its key: a refresh that removes the message closes it.
        later()
        rule.onNodeWithContentDescription("Select text").performClick()
        assertEquals(1, rule.onAllNodes(isDialog()).fetchSemanticsNodes().size)
        state = state(listOf(reply("r2", "Something else")))
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodes(isDialog()).fetchSemanticsNodes().size)
        state = state(listOf(reply("r1", "Pick some of these words")))
        rule.waitForIdle()
        assertEquals("closed for good, not hidden until the key comes back", 0, rule.onAllNodes(isDialog()).fetchSemanticsNodes().size)
    }

    @Test
    fun selectRefusesTooLargeTextVisibly() {
        val big = "y".repeat(SELECT_MAX_CHARS + 1)
        rule.setContent { Themed { SelectTextScreen(big, onBack = {}) } }
        rule.onNodeWithText("Too long to select").assertExists()
        assertEquals(0, rule.onAllNodes(hasText(big)).fetchSemanticsNodes().size)
    }
}
