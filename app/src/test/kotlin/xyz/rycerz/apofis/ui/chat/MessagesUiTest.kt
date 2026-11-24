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
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.longClick
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
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
import xyz.rycerz.apofis.chat.ReplyOutcome
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
import xyz.rycerz.apofis.ui.text.graphemeCut

/** Action rows, the user bubble collapse and the Select view. */
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
    private fun List(items: List<ChatItem>, onSelect: (String) -> Unit = {}, current: (() -> List<ChatItem>)? = null, live: LiveReply? = null) = MessageList(
        items, live, emptyMap(), answering = false, actions = object : ChatActions {}, onCancel = {}, onStep = {},
        listState = rememberLazyListState(), contentPadding = PaddingValues(), zone = ZoneOffset.UTC, today = FIXED_TODAY,
        onSelect = onSelect, current = current,
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
        val inputs = listOf(
            "w".repeat(100_000), "line\n".repeat(10_000), "😀".repeat(50_000), "start" + "\n".repeat(50_000) + "end",
            "title\n" + "w".repeat(10_000), // expanding must not rewind to the early line break
        )
        for ((n, text) in inputs.withIndex()) {
            // Unsent too: the full message is reachable from any bubble.
            message = UserMessage("u1", text, false, FIXED_NOW, if (n % 2 == 0) SendState.SENT else SendState.FAILED, idempotencyKey = "k")
            rule.waitForIdle()
            val shown = bubbleText()
            assertTrue("collapsed shows a bounded prefix: ${shown.length}", shown.length <= COLLAPSED_MAX_CHARS + GRAPHEME_SLACK)
            assertTrue(text.startsWith(shown))
            rule.onNodeWithText("Show more").performClick()
            rule.waitForIdle()
            val page = bubbleText()
            assertTrue("expanded shows one bounded piece: ${page.length}", page.length <= PAGE_CHARS + GRAPHEME_SLACK)
            assertTrue("expanding never shows less than before", page.startsWith(shown))
            assertTrue(text.startsWith(page) && page.length < text.length)
            assertEquals("no second Show more", 0, rule.onAllNodes(hasText("Show more")).fetchSemanticsNodes().size)
            rule.onNodeWithText("Show full message").performClick()
            assertEquals("the reader, not a row action", "full:u1", selected.removeLast())
            rule.onNodeWithText("Show less").performClick()
            rule.waitForIdle()
        }
    }

    @Test
    @Config(qualifiers = "pl-w360dp-h760dp-xhdpi")
    fun theFullMessageControlHasItsOwnLabelInPolish() {
        val message = UserMessage("u1", "w".repeat(20_000), false, FIXED_NOW)
        rule.setContent { Themed { List(listOf(message)) } }
        rule.onNodeWithText("Pokaż więcej").performClick()
        rule.onNodeWithText("Pokaż całą wiadomość").assertIsDisplayed()
        rule.onNodeWithText("Pokaż mniej").assertIsDisplayed()
    }

    @Test
    fun oddTextNeverThrowsAndCopiesAsStored() {
        val odd = listOf("", " ", "\uD800", "a\uDC00b", "‮evil‬", "a\r\nb\rc", "‏\n\n\n")
        for (text in odd) {
            assertTrue(text.startsWith(collapsedPrefix(text)))
            assertEquals(text.takeIf { it.isNotBlank() }, actionable(listOf(reply("r1", text)), "r1"))
            assertEquals(text, actionable(listOf(UserMessage("u1", text, false, FIXED_NOW)), "u1"))
            assertEquals(CopyPlan.Write(lf(text)), copyPlan(text, text))
            assertEquals(text, pieces(text).joinToString(""))
        }
        assertNull(actionable(listOf(AssistantReply("r1", emptyList(), "x", FIXED_NOW, live = true)), "r1"))
        assertNull(actionable(listOf(reply("r1", "x")), "r2"))
        assertEquals("pressed on another text", CopyPlan.Skip, copyPlan("new", "old"))
        assertEquals(CopyPlan.Skip, copyPlan(null, "old"))
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

    /** The pieces as the one cutting function makes them. */
    private fun pieces(text: CharSequence): List<String> {
        val out = ArrayList<String>()
        var start = 0
        do {
            val end = graphemeCut(text, start, start + PAGE_CHARS, up = true)
            out += text.substring(start, end)
            start = end
        } while (start < text.length)
        return out
    }

    @Test
    fun piecesCutOnlyAtGraphemeBoundariesAndConcatenateExactly() {
        val atoms = listOf(
            "a", "w", " ", "\n", "\r", "\r\n", "🇵🇱", "👨‍👩‍👧",
            "é", "́̂", "\uD800", "\uDC00", "😀",
        )
        val random = java.util.Random(5)
        val inputs = ArrayList<String>()
        repeat(40) { inputs += buildString { val n = 20_000 + random.nextInt(9000); while (length < n) append(atoms[random.nextInt(atoms.size)]) } }
        // The review's counterexamples: a flag pair shifted against the cap, combining marks across it, CRLF at it.
        for (shift in 0..3) inputs += "x".repeat(shift) + "🇵🇱".repeat(3000)
        inputs += "x".repeat(PAGE_CHARS - 50) + "e" + "́".repeat(200) + "tail"
        inputs += "x".repeat(PAGE_CHARS - 1) + "\r\n" + "y"
        inputs += "\n".repeat(50_000)
        inputs += "w".repeat(100_000)
        // A cluster longer than the slack whose last code point is a surrogate pair at the window's edge (the review's
        // case), swept across that edge, and long clusters starting well before the cap.
        for (marks in GRAPHEME_SLACK - 8..GRAPHEME_SLACK + 8) inputs += "x".repeat(PAGE_CHARS - 1) + "a" + "\u0301".repeat(marks) + "\uDB40\uDD00" + "tail"
        for (marks in listOf(100, 1500, 3000)) inputs += "x".repeat(PAGE_CHARS - 900) + "a" + "\u0301".repeat(marks) + "tail"
        for (shift in 0..2) inputs += "x".repeat(PAGE_CHARS + GRAPHEME_SLACK - shift - 1) + "\uD83D\uDE00".repeat(4) + "y"
        for (text in inputs) {
            val parts = pieces(text)
            assertEquals(text, parts.joinToString(""))
            val bi = java.text.BreakIterator.getCharacterInstance().apply { setText(text) }
            var end = 0
            for ((i, part) in parts.withIndex()) {
                val start = end
                end += part.length
                assertTrue("piece $i has ${part.length} chars", part.isNotEmpty() && part.length <= PAGE_CHARS + GRAPHEME_SLACK)
                if (end < text.length) {
                    assertTrue("cut $end splits a surrogate pair", !(text[end - 1].isHighSurrogate() && text[end].isLowSurrogate()))
                    val fallback = part.length in PAGE_CHARS..PAGE_CHARS + 1 && bi.following(start + PAGE_CHARS - 1) - start > PAGE_CHARS + GRAPHEME_SLACK - 2
                    assertTrue("cut $end is a grapheme boundary of the whole text (or the documented fallback)", bi.isBoundary(end) || fallback)
                    // At most the cap plus the one grapheme that straddles it.
                    assertTrue("piece $i overshoots", fallback || part.length == PAGE_CHARS || bi.preceding(end) < start + PAGE_CHARS)
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
        // As a String does: hashing or comparing by content reads it all.
        override fun hashCode() = s.hashCode().also { reads += s.length }
        override fun equals(other: Any?) = (other is Counting && other.s == s).also { reads += s.length }
    }

    @Test
    fun copyChecksTheSizeFirstAndNeverNormalizesPastTheLimit() {
        val body = " ".repeat(1_000_000) + "x"
        val huge = Counting(body)
        val pressed = Counting(body) // an equal text in another instance (a refresh)
        assertEquals(CopyPlan.TooLarge, copyPlan(huge, pressed))
        assertEquals("refused by length, unread", 0, huge.reads + pressed.reads)
        val limit = 10
        assertNull(lfWithin(Counting("\r\n".repeat(limit) + "x"), limit))
        assertEquals("\n".repeat(limit), lfWithin(Counting("\r\n".repeat(limit)), limit))
        val over = Counting("a\r\n".repeat(limit))
        assertNull(lfWithin(over, limit))
        assertTrue("reads ${over.reads}", over.reads <= 2 * limit)
        assertEquals(CopyPlan.Write("\n".repeat(MAX_CLIP_CHARS)), "\r\n".repeat(MAX_CLIP_CHARS).let { copyPlan(it, it) })
        assertEquals(CopyPlan.TooLarge, ("\r\n".repeat(MAX_CLIP_CHARS) + "\n").let { copyPlan(it, it) })
        // A huge blank-looking reply is found by its length, not by scanning it for text.
        val spaces = " ".repeat(1_000_000) + "x"
        assertEquals(spaces, actionable(listOf(reply("r1", spaces)), "r1"))
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
    fun theDoubleTapGuardIsPerMessageAndAction() {
        rule.mainClock.autoAdvance = false
        val clipboard = FakeClipboard()
        val items = listOf<ChatItem>(reply("a", "A"), UserMessage("u", "B?", false, FIXED_NOW), reply("b", "B"))
        rule.setContent { Themed(clipboard = clipboard) { List(items) } }
        rule.mainClock.advanceTimeByFrame()
        val copies = rule.onAllNodes(hasContentDescription("Copy"))
        val replies = copies.fetchSemanticsNodes().withIndex().filter { it.value.boundsInRoot.left < 100f }.map { it.index }
        copies[replies[0]].performClick()
        copies[replies[0]].performClick()
        copies[replies[1]].performClick() // another row at another place, within the interval
        rule.mainClock.advanceTimeBy(5 * frame)
        assertEquals(2, clipboard.writes.size)
        assertEquals(setOf<String?>("A", "B"), clipboard.writes.toSet())
    }

    @Test
    fun aRefusedTapStartsNoTimeout() {
        var t = 0L
        val guard = TapGuard(now = { t }, timeout = 300)
        assertTrue(guard.allows("copy:a"))
        t = 10 // refused for another reason: it never ran
        assertTrue(guard.allows("copy:a"))
        guard.ran("copy:a")
        t = 309
        assertTrue("just before the timeout", !guard.allows("copy:a"))
        assertTrue(guard.allows("copy:b"))
        assertTrue(guard.allows("select:a"))
        t = 310
        assertTrue("at the timeout", guard.allows("copy:a"))
    }

    @Test
    fun selectShowsAnyLengthInLazyBoundedPiecesThatConcatenateExactly() {
        var text by mutableStateOf("x".repeat(300_000))
        rule.setContent { Themed { SelectTextScreen(text) {} } }
        for (t in listOf("x".repeat(300_000), "a\n".repeat(30_000) + "tail", "line\r\n".repeat(20_000) + "end")) {
            text = t
            rule.waitForIdle()
            val list = rule.onNode(hasScrollToIndexAction())
            val known = list.fetchSemanticsNode().config[SemanticsProperties.CollectionInfo].rowCount
            assertTrue("opening finds one or two pieces, not all: $known", known in 1..2)
            val seen = ArrayList<String>()
            while (true) {
                if (runCatching { list.performScrollToIndex(seen.size) }.isFailure) break // past the last piece
                rule.waitForIdle()
                val piece = rule.onAllNodes(hasTestTag("select.piece:${seen.size}")).fetchSemanticsNodes().singleOrNull() ?: break
                seen += text(piece)
            }
            assertTrue(seen.size > 1 && seen.all { it.length <= PAGE_CHARS + GRAPHEME_SLACK })
            assertEquals("the selectable pieces are the stored text, separators included", lf(t), seen.joinToString(""))
        }
    }

    @Test
    fun aRecreatedSelectViewStartsAtTheTop() {
        val tester = StateRestorationTester(rule)
        val text = (0 until 12).joinToString("") { "piece$it ".padEnd(PAGE_CHARS, '.') }
        tester.setContent { Themed { SelectTextScreen(text) {} } }
        val list = rule.onNode(hasScrollToIndexAction())
        for (i in 0..7) {
            list.performScrollToIndex(i)
            rule.waitForIdle()
        }
        rule.onNode(hasText("piece7", substring = true)).assertIsDisplayed()
        tester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNode(hasText("piece0", substring = true)).assertIsDisplayed()
        val found = rule.onNode(hasScrollToIndexAction()).fetchSemanticsNode().config[SemanticsProperties.CollectionInfo].rowCount
        assertTrue("nothing restored: only the pieces at the top found ($found)", found in 1..2)
    }

    @Test
    fun aRestoredSelectViewOnAShortenedMessageShowsItWhole() {
        val tester = StateRestorationTester(rule)
        val long = (0 until 12).joinToString("") { "piece$it ".padEnd(PAGE_CHARS, '.') }
        var state by mutableStateOf(state(listOf(reply("r1", long))))
        tester.setContent { Themed { ChatScreen(state, object : ChatActions {}, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY) } }
        rule.onNodeWithContentDescription("Select text").performClick()
        val list = rule.onNode(hasScrollToIndexAction() and hasAnyAncestor(isDialog()))
        for (i in 0..7) {
            list.performScrollToIndex(i)
            rule.waitForIdle()
        }
        state = ChatUiState("s", "Chat", StatusLine.IDLE, emptyList(), ComposerMode.SEND_DISABLED, "", loaded = false)
        tester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        state = state(listOf(reply("r1", "word")))
        rule.waitForIdle()
        rule.onNode(hasText("word") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        val items = rule.onNode(hasScrollToIndexAction() and hasAnyAncestor(isDialog())).fetchSemanticsNode().config[SemanticsProperties.CollectionInfo].rowCount
        assertEquals(1, items)
    }

    @Test
    fun aSelectViewSavedDeepAndRestoredOntoAShorterBodyWithoutLoadingShowsItWhole() {
        val tester = StateRestorationTester(rule)
        val long = (0 until 12).joinToString("") { "piece$it ".padEnd(PAGE_CHARS, '.') }
        var state by mutableStateOf(state(listOf(reply("r1", long))))
        tester.setContent { Themed { ChatScreen(state, object : ChatActions {}, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY) } }
        rule.onNodeWithContentDescription("Select text").performClick()
        val list = rule.onNode(hasScrollToIndexAction() and hasAnyAncestor(isDialog()))
        for (i in 0..7) {
            list.performScrollToIndex(i)
            rule.waitForIdle()
        }
        // Saved while the deep view is still mounted; the retained owner already holds the shorter body on restore.
        state = state(listOf(reply("r1", "word")))
        tester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNode(hasText("word") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        assertEquals(1, rule.onNode(hasScrollToIndexAction() and hasAnyAncestor(isDialog())).fetchSemanticsNode().config[SemanticsProperties.CollectionInfo].rowCount)
    }

    @Test
    fun theReaderSavedDeepAndRecreatedOntoAShorterBodyShowsItWhole() {
        // The screen itself, outside a dialog window (whose content state is not restored), so saved state would reach it.
        val tester = StateRestorationTester(rule)
        var text by mutableStateOf((0 until 12).joinToString("") { "piece$it ".padEnd(PAGE_CHARS, '.') })
        tester.setContent { Themed { SelectTextScreen(text) {} } }
        val list = rule.onNode(hasScrollToIndexAction())
        for (i in 0..7) {
            list.performScrollToIndex(i)
            rule.waitForIdle()
        }
        text = "word" // saved while deep; the shorter body is there at the restored composition
        tester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        rule.onNode(hasText("word")).assertIsDisplayed()
        assertEquals(1, rule.onNode(hasScrollToIndexAction()).fetchSemanticsNode().config[SemanticsProperties.CollectionInfo].rowCount)
    }

    @Test
    fun openingSelectReadsOnePieceOfAFreshBody() {
        val body = Counting("y".repeat(1_000_000))
        rule.setContent { Themed { SelectTextScreen(body) {} } }
        rule.waitForIdle()
        rule.onNodeWithTag("select.piece:0").assertIsDisplayed()
        assertTrue("read ${body.reads} characters before the first piece", body.reads <= 2 * (PAGE_CHARS + GRAPHEME_SLACK + 1))
    }

    @Test
    fun aWordSelectedAtAPieceEdgeCopiesExactly() {
        val clipboard = FakeClipboard()
        val text = "x".repeat(PAGE_CHARS) + "\nomega tail"
        rule.setContent { Themed(clipboard = clipboard) { SelectTextScreen(text) {} } }
        rule.onNode(hasScrollToIndexAction()).performScrollToIndex(1)
        rule.waitForIdle()
        val piece = rule.onNodeWithTag("select.piece:1")
        assertEquals("the piece keeps its leading line break", "\nomega tail", text(piece.fetchSemanticsNode()))
        piece.performTouchInput { longClick(Offset(30f, bottom - 20f)) } // selects the word under the finger
        piece.performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.C) } }
        rule.waitForIdle()
        assertTrue(clipboard.writes.isNotEmpty())
        assertEquals("omega", clipboard.writes.last())
    }

    @Test
    fun aRestoredSelectViewWaitsForTheTranscript() {
        val tester = StateRestorationTester(rule)
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
    fun noReplyHasActionsWhileARunIsLiveOrUnsettled() {
        val r1 = reply("r1", "Earlier answer")
        val asked = UserMessage("u2", "Next?", false, FIXED_NOW, runId = "A") // its pending send is not settled yet
        val partial = reply("r3", "Half of the")
        val polling = LiveReply("A", "running", "", emptyList(), null, Connection.POLLING)
        var state by mutableStateOf(state(listOf(r1, asked, partial), polling))
        rule.setContent { Themed { ChatScreen(state, object : ChatActions {}, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY) } }
        rule.waitForIdle()
        assertEquals("live: no reply row", 0, replyRows().size)
        // The run's end reaches the screen before the transcript that settled it: still unsettled.
        state = state(listOf(r1, asked, partial))
        rule.waitForIdle()
        assertEquals(0, replyRows().size)
        // The final transcript: the send settled (no run id) and the final body stored.
        state = state(listOf(r1, asked.copy(runId = null), reply("r3", "Half of the answer, now whole.")))
        rule.waitForIdle()
        assertEquals(2, replyRows().size)
    }

    @Test
    fun theRuleCannotMisattributeAReplyToARun() {
        // Clock skew: the phone's send did not match its server row, so the marked bubble comes after the partial reply.
        val skew = listOf<ChatItem>(UserMessage("u1", "ask", false, FIXED_NOW - 70), reply("r2", "partial"), UserMessage("p123", "ask", false, FIXED_NOW, SendState.SENT, "k", runId = "A"))
        assertNull(actionable(skew, "r2"))
        assertNull(actionable(Joined(skew, listOf<ChatItem>(AssistantReply("live", emptyList(), "", FIXED_NOW, live = true))), "r2"))
        // A marker on an earlier turn: everything waits until that run settles, then nothing waits.
        val misplaced = listOf<ChatItem>(UserMessage("uA", "a", false, FIXED_NOW, runId = "A"), reply("rA", "A"), UserMessage("uB", "b", false, FIXED_NOW), reply("rB", "B"))
        assertNull(actionable(misplaced, "rB"))
        val settledA = misplaced.map { if (it is UserMessage) it.copy(runId = null) else it }
        assertEquals("B", actionable(settledA, "rB"))
        assertEquals("A", actionable(settledA, "rA"))
        // A steer inside the running turn carries no run id; the turn's first message does.
        val steered = listOf<ChatItem>(UserMessage("u1", "go", false, FIXED_NOW, runId = "A"), reply("r1", "part one"), UserMessage("s2", "also", true, FIXED_NOW), reply("r2", "part two"))
        assertNull(actionable(steered, "r1"))
        assertNull(actionable(steered, "r2"))
        // Stopped or failed runs settle like any other: their transcript has outcomes and no run id.
        val stopped = listOf<ChatItem>(UserMessage("u1", "go", false, FIXED_NOW), AssistantReply("r1", emptyList(), "half", FIXED_NOW, outcome = ReplyOutcome.STOPPED))
        assertEquals("half", actionable(stopped, "r1"))
        val failed = listOf<ChatItem>(UserMessage("u1", "go", false, FIXED_NOW), AssistantReply("r1", emptyList(), "err", FIXED_NOW, outcome = ReplyOutcome.FAILED))
        assertEquals("err", actionable(failed, "r1"))
        // A transcript loaded with no local runs: every stored reply is actionable.
        val history = listOf<ChatItem>(reply("r0", "leading"), UserMessage("u1", "q", false, FIXED_NOW), reply("r1", "a"), UserMessage("u2", "q2", false, FIXED_NOW), reply("r2", "b"))
        for (key in listOf("r0", "r1", "r2")) assertTrue(actionable(history, key) != null)
        assertNull("a live run blocks it too", actionable(history, "r2", running = true))
    }

    /** Counts every item read. */
    private class CountingList(private val items: List<ChatItem>) : AbstractList<ChatItem>() {
        var visits = 0
        override val size get() = items.size
        override fun get(index: Int): ChatItem = items[index].also { visits++ }
    }

    @Test
    fun aTapVisitsEachItemAtMostOnce() {
        val n = 1000
        val history = CountingList((0 until n).map { if (it % 2 == 0) reply("r$it", "answer $it") else UserMessage("u$it", "q $it", false, FIXED_NOW) })
        assertEquals("answer 0", actionable(history, "r0"))
        assertTrue("visited ${history.visits} of $n items", history.visits <= n)
        history.visits = 0
        assertNull(actionable(history, "missing"))
        assertTrue(history.visits <= n)
    }

    @Test
    fun aMismatchedSendKeepsTheFooterCopyAndSelectOff() {
        rule.mainClock.autoAdvance = false
        val clipboard = FakeClipboard()
        val selected = ArrayList<String>()
        val shown = listOf<ChatItem>(UserMessage("u1", "ask", false, FIXED_NOW - 70), reply("r2", "partial"))
        val skew = shown + UserMessage("p123", "ask", false, FIXED_NOW, SendState.SENT, "k", runId = "A")
        var now = shown
        rule.setContent { Themed(clipboard = clipboard) { List(shown, onSelect = { selected += it }, current = { now }) } }
        rule.mainClock.advanceTimeByFrame()
        now = skew // the marked bubble arrives before the next frame
        val replyCopy = rule.onAllNodes(hasContentDescription("Copy")).let { all -> all[all.fetchSemanticsNodes().withIndex().first { it.value.boundsInRoot.left < 100f }.index] }
        replyCopy.performClick()
        rule.mainClock.advanceTimeBy(5 * frame)
        assertTrue(clipboard.writes.isEmpty())
        assertNull(actionable(skew, "r2"))
    }

    /** The action button of the last message on screen. */
    private fun bottomMost(description: String) = rule.onAllNodes(hasContentDescription(description)).let { all ->
        all[all.fetchSemanticsNodes().withIndex().maxBy { it.value.positionInRoot.y }.index]
    }

    @Test
    fun eligibilityIsCheckedAgainAtActionTime() {
        rule.mainClock.autoAdvance = false
        val clipboard = FakeClipboard()
        val selected = ArrayList<String>()
        val r1 = reply("r1", "Earlier answer")
        val asked = UserMessage("u2", "Next?", false, FIXED_NOW)
        val r3 = reply("r3", "Stored so far")
        val drawn = listOf<ChatItem>(r1, asked, r3)
        var now = drawn
        rule.setContent { Themed(clipboard = clipboard) { List(drawn, onSelect = { selected += it }, current = { now }) } }
        rule.mainClock.advanceTimeByFrame()
        now = listOf(r1, asked.copy(runId = "A"), r3) // a run for that turn is pending again before the next frame
        bottomMost("Copy").performClick()
        bottomMost("Select text").performClick()
        rule.mainClock.advanceTimeBy(5 * frame)
        assertTrue(clipboard.writes.isEmpty())
        assertTrue(selected.isEmpty())
        assertNull("while a run is unsettled no reply qualifies", actionable(now, "r1"))
    }

    @Test
    fun aSentMessageThatFailedBeforeTheRedrawHasNoActions() {
        rule.mainClock.autoAdvance = false
        val clipboard = FakeClipboard()
        val selected = ArrayList<String>()
        val sent = UserMessage("p1", "Remember the milk", false, FIXED_NOW, SendState.SENT, idempotencyKey = "k")
        var now = listOf<ChatItem>(sent)
        rule.setContent { Themed(clipboard = clipboard) { List(listOf(sent), onSelect = { selected += it }, current = { now }) } }
        rule.mainClock.advanceTimeByFrame()
        now = listOf(sent.copy(state = SendState.FAILED)) // same key and body, an unconfirmed finish
        rule.onNodeWithContentDescription("Copy").performClick()
        rule.onNodeWithContentDescription("Select text").performClick()
        rule.mainClock.advanceTimeBy(5 * frame)
        assertTrue(clipboard.writes.isEmpty())
        assertTrue(selected.isEmpty())
        assertNull(actionable(now, "p1"))
        assertEquals("the full-message reader still opens it", "Remember the milk", actionable(now, "p1", fullMessage = true))
    }

    @Test
    fun theFullMessageReaderOpensAFailedMessage() {
        val failed = UserMessage("p1", "w".repeat(20_000), false, FIXED_NOW, SendState.FAILED, idempotencyKey = "k")
        rule.setContent { Themed { ChatScreen(state(listOf(failed)), object : ChatActions {}, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY) } }
        rule.onNodeWithText("Show more").performClick()
        rule.onNodeWithText("Show full message").performClick()
        rule.waitForIdle()
        assertEquals(1, rule.onAllNodes(isDialog()).fetchSemanticsNodes().size)
        rule.onNode(hasText("Select text") and hasAnyAncestor(isDialog())).assertIsDisplayed()
    }

    @Test
    fun anOpenSelectViewClosesWhenItsReplyBecomesPartOfAPendingTurn() {
        val r1 = reply("r1", "Earlier answer")
        val asked = UserMessage("u2", "Next?", false, FIXED_NOW)
        val r3 = reply("r3", "Stored so far")
        var state by mutableStateOf(state(listOf(r1, asked, r3)))
        rule.setContent { Themed { ChatScreen(state, object : ChatActions {}, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY) } }
        bottomMost("Select text").performClick()
        assertEquals(1, rule.onAllNodes(isDialog()).fetchSemanticsNodes().size)
        state = state(listOf(r1, asked.copy(runId = "A"), r3), LiveReply("A", "running", "", emptyList(), null, Connection.POLLING))
        rule.waitForIdle()
        assertEquals(0, rule.onAllNodes(isDialog()).fetchSemanticsNodes().size)
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

    /**
     * The footer draws Copy and Select by the tap check's own bounded rule: a long whitespace prefix with a text tail has
     * the row (without walking the prefix), and a body over the page cap counts as text unread, exactly as [actionable].
     */
    @Test
    fun theFooterDrawsActionsByTheTapChecksBoundedRule() {
        var reply by mutableStateOf(AssistantReply("r1", emptyList(), " ".repeat(1_000_000) + "x", 0.0))
        rule.setContent { Themed { ReplyFooter(reply, null, emptyMap(), answering = false, object : ChatActions {}, RowActions({ listOf(reply) }, {})) } }
        for (text in listOf(" ".repeat(1_000_000) + "x", " ".repeat(PAGE_CHARS + 1), "  ", "ok")) {
            reply = reply.copy(text = text)
            rule.waitForIdle()
            val drawn = rule.onAllNodes(hasContentDescription("Copy")).fetchSemanticsNodes().isNotEmpty()
            assertEquals("${text.length} characters", actionable(listOf(reply), "r1") != null, drawn)
        }
    }
}
