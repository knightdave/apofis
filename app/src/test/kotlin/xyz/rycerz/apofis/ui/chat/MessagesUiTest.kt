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
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
    private fun List(items: List<ChatItem>, onSelect: (String) -> Unit = {}, justFinished: String? = null, current: (() -> List<ChatItem>)? = null) = MessageList(
        items, null, emptyMap(), answering = false, actions = object : ChatActions {}, onCancel = {}, onStep = {},
        listState = rememberLazyListState(), contentPadding = PaddingValues(), zone = ZoneOffset.UTC, today = FIXED_TODAY,
        onSelect = onSelect, justFinishedKey = justFinished, currentItems = current,
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
        val asked = UserMessage("u2", "Two?", false, FIXED_NOW, runId = "A")
        val live = AssistantReply("live", emptyList(), "Tw", FIXED_NOW, live = true)
        val running = Joined(listOf<ChatItem>(r1, asked), listOf<ChatItem>(live))
        val idle = FinishTracker()
        assertEquals("nothing before a run", idle, idle.next(null, listOf(r1)))
        val armed = idle.next("A", running)
        assertEquals(FinishTracker(runId = "A", before = "r1"), armed)
        assertEquals("same run, same state", armed, armed.next("A", running))
        val waiting = armed.next(null, listOf(r1, asked))
        assertEquals("the stored reply may come one update late", FinishTracker(runId = "A", before = "r1", ended = true), waiting)
        val done = waiting.next(null, listOf(r1, asked, reply("r3", "Two.")))
        assertEquals(FinishTracker(key = "r3"), done)
        assertEquals("the next update consumes the handoff", FinishTracker(), done.next(null, listOf(r1, asked, reply("r3", "Two!"))))
        // Same key across the swap: the transcript already held the reply while it streamed (or polling stored it).
        val polling = idle.next("A", Joined(listOf<ChatItem>(r1, asked, reply("r3", "T")), listOf<ChatItem>(live)))
        assertEquals("parts of the running turn are not history", "r1", polling.before)
        assertEquals(FinishTracker(key = "r3"), polling.next(null, listOf(r1, asked, reply("r3", "Two"))))
        assertEquals("first run in an empty chat", FinishTracker(key = "r1"), idle.next("A", Joined(emptyList(), listOf<ChatItem>(live))).next(null, listOf(r1)))
        assertEquals(armed, FinishTracker.restore(FinishTracker.save(armed)))
        assertEquals("a pending handoff is not saved", FinishTracker(), FinishTracker.restore(FinishTracker.save(done)))
    }

    @Test
    fun aRunWithoutAReplyNeverFinishesLater() {
        val r1 = reply("r1", "One")
        val armed = FinishTracker().next("A", Joined(listOf<ChatItem>(r1), listOf<ChatItem>(AssistantReply("live", emptyList(), "", FIXED_NOW, live = true))))
        val cleared = armed.next(null, listOf(r1)).next(null, listOf(r1, UserMessage("u5", "x", false, FIXED_NOW)))
        assertEquals("no reply after one more update: cleared", FinishTracker(), cleared)
        assertEquals("a later reply from elsewhere is not this run's", FinishTracker(), cleared.next(null, listOf(r1, reply("r9", "other device"))))
        assertEquals("deleting the last reply reveals older history, not a finish", FinishTracker(), cleared.next(null, emptyList()))
        // A new run while an earlier one still waits starts over with the new run.
        val waiting = armed.next(null, listOf(r1))
        val next = waiting.next("B", Joined(listOf<ChatItem>(r1, UserMessage("u6", "y", false, FIXED_NOW, runId = "B")), listOf<ChatItem>(AssistantReply("live", emptyList(), "", FIXED_NOW, live = true))))
        assertEquals(FinishTracker(runId = "B", before = "r1"), next)
    }

    @Test
    fun holderAdvancesOnlyForNewTranscriptsAndConsumesTheHandoff() {
        val r1 = reply("r1", "One")
        val holder = FinishHolder()
        val running = Joined(listOf<ChatItem>(r1), listOf<ChatItem>(AssistantReply("live", emptyList(), "", FIXED_NOW, live = true)))
        assertNull(holder.update("A", running))
        val after = listOf(r1, reply("r2", "Two"))
        assertEquals("r2", holder.update(null, after))
        assertNull("a recomposition with the same transcript consumes it", holder.update(null, after))
        assertEquals(FinishTracker(), holder.tracker)
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
    fun hugeUserMessageIsNotLaidOutWholeWhenCollapsedOrExpanded() {
        var message by mutableStateOf(UserMessage("u1", "", false, FIXED_NOW))
        val selected = ArrayList<String>()
        rule.setContent { Themed { List(listOf(message), onSelect = { selected += it }) } }
        for (text in listOf("w".repeat(100_000), "line\n".repeat(10_000), "\uD83D\uDE00".repeat(50_000), "start" + "\n".repeat(50_000) + "end")) {
            message = UserMessage("u1", text, false, FIXED_NOW)
            rule.waitForIdle()
            val shown = bubbleText()
            assertTrue("collapsed shows a bounded prefix: ${shown.length}", shown.length <= COLLAPSED_MAX_CHARS)
            assertTrue(shown.lines().size <= USER_COLLAPSED_LINES + 1)
            assertTrue(text.startsWith(shown))
            assertTrue("never a lone half of a surrogate pair", shown.isEmpty() || !shown.last().isHighSurrogate())
            rule.onNodeWithText("Show more").performClick()
            rule.waitForIdle()
            val page = bubbleText()
            assertTrue("expanded shows one bounded piece: ${page.length}", page.length <= PAGE_CHARS + 1)
            assertTrue(page.count { it == '\n' } <= PAGE_LINES)
            assertTrue(text.startsWith(page) && page.length < text.length)
            rule.onNodeWithText("Show more").performClick() // the rest: the Select view
            assertEquals("u1", selected.removeLast())
            rule.onNodeWithText("Show less").performClick()
            rule.waitForIdle()
        }
    }

    @Test
    fun oddTextNeverThrowsAndCopiesAsStored() {
        val odd = listOf("", " ", "\uD800", "a\uDC00b", "\u202Eevil\u202C", "a\r\nb\rc", "\u200F\n\n\n")
        for (text in odd) {
            val prefix = collapsedPrefix(text)
            assertTrue(text.startsWith(prefix))
            val r = reply("r1", text)
            assertEquals(text.takeIf { it.isNotBlank() }, stillShown(listOf(r), r))
            val u = UserMessage("u1", text, false, FIXED_NOW)
            assertEquals(text, stillShown(listOf(u), u))
            assertEquals(lf(text), lfWithin(text, MAX_CLIP_CHARS))
            assertEquals(text, textPages(text).let { ends -> ends.indices.joinToString("") { text.substring(if (it == 0) 0 else ends[it - 1], ends[it]) } })
        }
        val queued = UserMessage("p1", "x", false, FIXED_NOW, SendState.QUEUED, "k")
        assertNull(stillShown(listOf(queued), queued))
        val live = AssistantReply("r1", emptyList(), "x", FIXED_NOW, live = true)
        assertNull(stillShown(listOf(live), live))
        assertNull(stillShown(listOf(reply("r1", "x")), reply("r2", "x")))
    }

    @Test
    fun copyGivesTheStoredTextWithLfEndings() {
        val clipboard = FakeClipboard()
        val items = listOf(UserMessage("u1", "a\r\nb\rc\u202E\uD800", false, FIXED_NOW))
        rule.setContent { Themed(clipboard = clipboard) { List(items) } }
        rule.onNodeWithContentDescription("Copy").performClick()
        rule.waitForIdle()
        assertEquals(listOf<String?>("a\nb\nc\u202E\uD800"), clipboard.writes)
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

    // ---- fix round 1

    private fun pieces(text: String, ends: IntArray = textPages(text)) = ends.indices.map { text.substring(if (it == 0) 0 else ends[it - 1], ends[it]) }

    private fun breaks(s: String): Int {
        var n = 0
        var i = 0
        while (i < s.length) {
            if (s[i] == '\n' || s[i] == '\r') {
                if (s[i] == '\r' && i + 1 < s.length && s[i + 1] == '\n') i++
                n++
            }
            i++
        }
        return n
    }

    @Test
    fun piecesAreBoundedAndConcatenateExactly() {
        val family = "\uD83D\uDC68\u200D\uD83D\uDC69\u200D\uD83D\uDC67" // one grapheme, 8 chars
        val inputs = listOf(
            "w".repeat(100_000), "\n".repeat(50_000), "start" + "\n".repeat(400_000) + "end", "a\r\n".repeat(30_000), "\r".repeat(9_000),
            family.repeat(5_000), "\uD83C\uDDF5\uD83C\uDDF1".repeat(10_000), "x".repeat(PAGE_CHARS - 1) + "\r\n" + "y".repeat(10),
        )
        for (text in inputs) {
            val ends = textPages(text)
            val parts = pieces(text, ends)
            assertEquals(text, parts.joinToString(""))
            val bi = java.text.BreakIterator.getCharacterInstance().apply { setText(text) }
            for ((i, part) in parts.withIndex()) {
                assertTrue("piece $i has ${part.length} chars", part.length in 1..PAGE_CHARS + 1)
                assertTrue("piece $i has ${breaks(part)} breaks", breaks(part) <= PAGE_LINES)
                val end = ends[i]
                if (end < text.length) {
                    assertTrue("never inside CRLF", !(text[end - 1] == '\r' && text[end] == '\n'))
                    assertTrue("a grapheme boundary at $end", bi.isBoundary(end))
                }
            }
        }
        assertEquals(listOf(""), pieces(""))
    }

    /** Counts every character read. */
    private class Counting(val s: String) : CharSequence {
        var reads = 0
        override val length get() = s.length
        override fun get(index: Int): Char = s[index].also { reads++ }
        override fun subSequence(startIndex: Int, endIndex: Int) = s.subSequence(startIndex, endIndex).also { reads += it.length }
        override fun toString() = s.also { reads += it.length }
    }

    @Test
    fun copyChecksTheSizeBeforeNormalizingAndNeverNormalizesPastTheLimit() {
        val limit = 10
        val tooLong = Counting("\r\n".repeat(limit) + "x")
        assertNull(lfWithin(tooLong, limit))
        assertEquals("over twice the limit: refused unread", 0, tooLong.reads)
        val exact = Counting("\r\n".repeat(limit))
        assertEquals("\n".repeat(limit), lfWithin(exact, limit))
        val over = Counting("a\r\n".repeat(limit))
        assertNull(lfWithin(over, limit))
        assertTrue("reads ${over.reads}", over.reads <= 2 * limit)
        assertEquals("\n".repeat(MAX_CLIP_CHARS), lfWithin("\r\n".repeat(MAX_CLIP_CHARS), MAX_CLIP_CHARS))
        assertNull(lfWithin("\r\n".repeat(MAX_CLIP_CHARS) + "\n", MAX_CLIP_CHARS))
    }

    @Test
    fun anActionDoesNothingUnlessTheTranscriptStillHoldsExactlyThatMessage() {
        rule.mainClock.autoAdvance = false
        val clipboard = FakeClipboard()
        val drawn = listOf<ChatItem>(reply("r1", "old"))
        var now: List<ChatItem> = drawn // the owner's state, changed without a new frame
        rule.setContent { Themed(clipboard = clipboard) { List(drawn, current = { now }) } }
        rule.mainClock.advanceTimeByFrame()
        val copy = rule.onNodeWithContentDescription("Copy")
        for (changed in listOf(emptyList(), listOf(reply("r1", "new")), listOf(AssistantReply("r1", emptyList(), "old", FIXED_NOW, live = true)))) {
            now = changed
            copy.performClick()
            rule.mainClock.advanceTimeBy(5 * frame)
            later()
        }
        assertTrue("removed, edited or live again: nothing copied ${clipboard.writes}", clipboard.writes.isEmpty())
        now = listOf(reply("r1", "old"))
        copy.performClick()
        rule.mainClock.advanceTimeBy(5 * frame)
        assertEquals(listOf<String?>("old"), clipboard.writes)
    }

    @Test
    fun aPressIsBoundToTheMessageItBeganOn() {
        rule.mainClock.autoAdvance = false
        val clipboard = FakeClipboard()
        var items by mutableStateOf<List<ChatItem>>(listOf(reply("r1", "first")))
        rule.setContent { Themed(clipboard = clipboard) { List(items) } }
        rule.mainClock.advanceTimeByFrame()
        val copy = rule.onNodeWithContentDescription("Copy")
        copy.performTouchInput { down(center) }
        items = listOf(reply("r1", "edited under the finger"))
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        copy.performTouchInput { up() }
        rule.mainClock.advanceTimeBy(5 * frame)
        assertTrue("the press began on 'first', which is gone: ${clipboard.writes}", clipboard.writes.isEmpty())
        later()
        copy.performClick()
        rule.mainClock.advanceTimeBy(5 * frame)
        assertEquals(listOf<String?>("edited under the finger"), clipboard.writes)
    }

    @Test
    fun aDoubleTapCannotCopyTheMessageThatReplacedTheFirst() {
        rule.mainClock.autoAdvance = false
        val clipboard = FakeClipboard()
        var items by mutableStateOf<List<ChatItem>>(listOf(reply("a", "A")))
        rule.setContent { Themed(clipboard = clipboard) { List(items) } }
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithContentDescription("Copy").performClick()
        rule.mainClock.advanceTimeBy(5 * frame)
        items = listOf(reply("b", "B")) // a new button at the same place
        rule.waitForIdle()
        rule.mainClock.advanceTimeByFrame()
        rule.onNodeWithContentDescription("Copy").performClick()
        rule.mainClock.advanceTimeBy(5 * frame)
        assertEquals(listOf<String?>("A"), clipboard.writes)
    }

    @Test
    fun tapGuardBoundary() {
        var t = 1000L
        val guard = TapGuard(now = { t }, timeout = 300)
        assertTrue(guard.accept())
        t = 1299
        assertTrue("just before the timeout", !guard.accept())
        t = 1300
        assertTrue("at the timeout", guard.accept())
        t = 1599
        assertTrue(!guard.accept())
    }

    @Test
    fun selectShowsAnyLengthInBoundedPieces() {
        var text by mutableStateOf("x".repeat(100_001))
        rule.setContent { Themed { SelectTextScreen(text) {} } }
        for (t in listOf("x".repeat(100_001), "line\n".repeat(50_000) + "the end")) {
            text = t
            rule.waitForIdle()
            val shown = rule.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.Text)).fetchSemanticsNodes().map(::text)
            assertTrue("no piece over the bound", shown.all { it.length <= PAGE_CHARS + 1 && it.count { c -> c == '\n' } <= PAGE_LINES })
            val ends = textPages(t)
            assertTrue(ends.size > 1)
            rule.onNode(androidx.compose.ui.test.hasScrollToIndexAction()).performScrollToIndex(ends.size - 1)
            rule.waitForIdle()
            val last = selectPiece(t, ends, ends.size - 1)
            rule.onNode(hasText(last)).assertIsDisplayed()
            assertEquals(lf(t), ends.indices.joinToString("") { lf(pieces(t, ends)[it]) })
        }
    }

    @Test
    fun aRestoredSelectViewWaitsForTheTranscript() {
        val tester = androidx.compose.ui.test.junit4.StateRestorationTester(rule)
        var state by mutableStateOf(state(listOf(reply("r1", "Pick these words"))))
        tester.setContent { Themed { ChatScreen(state, object : ChatActions {}, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY) } }
        rule.onNodeWithContentDescription("Select text").performClick()
        assertEquals(1, rule.onAllNodes(isDialog()).fetchSemanticsNodes().size)
        state = ChatUiState("s", "Chat", StatusLine.IDLE, emptyList(), ComposerMode.SEND_DISABLED, "", loaded = false)
        tester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals("loading: nothing shown yet", 0, rule.onAllNodes(isDialog()).fetchSemanticsNodes().size)
        state = state(listOf(reply("r1", "Edited\r\nwords")))
        rule.waitForIdle()
        assertEquals(1, rule.onAllNodes(hasText("Edited\nwords") and hasAnyAncestor(isDialog())).fetchSemanticsNodes().size)
    }

    @Test
    fun aPolledRunningTurnHasNoActionsUntilTheRunEnds() {
        val r1 = reply("r1", "Earlier answer")
        val asked = UserMessage("u2", "Next?", false, FIXED_NOW, runId = "A")
        val partial = reply("r3", "Half of the")
        val polling = LiveReply("A", "running", "", emptyList(), null, Connection.POLLING)
        var state by mutableStateOf(state(listOf(r1, asked, partial), polling))
        rule.setContent { Themed { ChatScreen(state, object : ChatActions {}, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY) } }
        rule.waitForIdle()
        assertEquals("only the history reply r1", 1, replyRows().size)
        state = state(listOf(r1, asked, reply("r3", "Half of the answer, now whole.")))
        rule.waitForIdle()
        assertEquals(2, replyRows().size)
    }

    @Test
    @Config(qualifiers = "pl-w320dp-h760dp-xhdpi")
    fun theCopyNoteNeverSqueezesTheActions() {
        val message = UserMessage("u1", "z".repeat(MAX_CLIP_CHARS + 1), false, FIXED_NOW)
        rule.setContent {
            Themed {
                val d = androidx.compose.ui.platform.LocalDensity.current
                CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(d.density, 2f)) { List(listOf(message)) }
            }
        }
        fun check() {
            val copy = rule.onNodeWithContentDescription("Kopiuj").fetchSemanticsNode().boundsInRoot
            val select = rule.onNodeWithContentDescription("Zaznacz tekst").fetchSemanticsNode().boundsInRoot
            val min = 48 * 2f // 48 dp at xhdpi
            for (b in listOf(copy, select)) assertTrue("target $b", b.width >= min && b.height >= min)
            assertTrue("Copy before Select, apart: $copy $select", copy.right <= select.left)
        }
        check()
        rule.onNodeWithContentDescription("Kopiuj").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Za duże, by skopiować").assertIsDisplayed()
        check()
    }

    @Test
    fun showMoreFollowsTheLayoutBothWays() {
        var width by mutableStateOf(100.dp)
        val message = UserMessage("u1", "word ".repeat(30).trim(), false, FIXED_NOW)
        rule.setContent {
            Themed {
                Box(Modifier.width(width)) { UserBubble(message, onRetry = {}, onCancel = {}, actions = RowActions({ listOf(message) }, {})) }
            }
        }
        rule.onNodeWithText("Show more").assertExists()
        width = 1500.dp
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodes(hasText("Show more")).fetchSemanticsNodes().size)
    }
}
