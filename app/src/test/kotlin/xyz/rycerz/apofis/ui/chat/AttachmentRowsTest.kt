package xyz.rycerz.apofis.ui.chat

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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.runtime.ProvidedValue
import kotlin.math.roundToInt
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import java.time.ZoneId
import java.time.ZoneOffset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.onCompletion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.ChatItem
import xyz.rycerz.apofis.chat.Step
import xyz.rycerz.apofis.chat.ToolStep
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.media.Attachment
import xyz.rycerz.apofis.media.AttachmentKind.FILE
import xyz.rycerz.apofis.media.AttachmentKind.PICTURE
import xyz.rycerz.apofis.media.AttachmentState
import xyz.rycerz.apofis.media.Attachments
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.FIXED_NOW
import xyz.rycerz.apofis.ui.FIXED_TODAY
import xyz.rycerz.apofis.ui.drawnPixels
import xyz.rycerz.apofis.ui.theme.ApofisTheme

/** Attachment rows of stored replies (spec 6.2) and their tiles before the decode exists (T8 owns the decode). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class AttachmentRowsTest {
    @get:Rule
    val rule = createComposeRule()

    private val chart = "/srv/out/chart.png"
    private val pdf = "/srv/out/report.pdf"
    private val step = ToolStep("c1", "image_generate", "{}", "ok", resultTruncated = false, error = false, running = false)

    private fun reply(key: String, text: String, steps: List<Step> = emptyList(), live: Boolean = false, ts: Double = 0.0) =
        AssistantReply(key, steps, text, ts, live = live)

    private fun rows(items: List<ChatItem>) = ChatRows(ZoneId.of("UTC")).rows(items)

    private fun local(items: List<ChatItem>) = rows(items).filter { it !is DateRow }

    // ---- rows ----

    @Test
    fun rowsInOrderStepsTextAttachmentsFooter() {
        val text = "Here it is.\nMEDIA:$chart\nMEDIA:$pdf"
        val r = reply("r1", text, steps = listOf(step), ts = 2 * 86_400.0)
        val user = UserMessage("u1", "Draw it", false, 0.0)
        val got = rows(listOf(user, r))
        val textRows = replyRows("r1", text, closeFences = true)
        assertTrue(textRows.isNotEmpty())
        val expected = listOf(StepsRow(r)) + textRows +
            listOf(AttachmentRow(r, 0, Attachment(chart, PICTURE)), AttachmentRow(r, 1, Attachment(pdf, FILE)), FooterRow(r))
        assertEquals(expected, got.filter { it !is DateRow && it !is UserRow })
        // The date rows are those the same items give without any path, each right before the first row of its item.
        fun dates(list: List<Row>) = list.zipWithNext().filter { it.first is DateRow }.map { it.first to it.second.key }
        val plain = rows(listOf(user, reply("r1", "Here it is.", steps = listOf(step), ts = 2 * 86_400.0)))
        assertEquals(dates(plain), dates(got))
        assertEquals(2, dates(got).size)
    }

    @Test
    fun keysAreUniqueAndIndexed() {
        val got = rows(listOf(reply("r1", "MEDIA:$chart\nMEDIA:/srv/out/b.png"), reply("r2", "See $chart")))
        val keys = got.map { it.key }
        assertTrue(keys.toString(), "r1/a0" in keys && "r1/a1" in keys && "r2/a0" in keys)
        assertEquals(keys.size, keys.toSet().size)
    }

    @Test
    fun liveItemGetsNoAttachmentRows() {
        val text = "MEDIA:$chart"
        // (a) A live item in a plain list is never scanned and keeps its text rows.
        val live = local(listOf(reply("r5", text, live = true)))
        assertTrue(live.none { it is AttachmentRow })
        assertEquals(replyRows("r5", text), live.filterIsInstance<TextRow>())
        // (b) A live tail item that borrowed a stored reply's key.
        val joined = rows(Joined(listOf<ChatItem>(UserMessage("u1", "Draw it", false, 0.0)), listOf<ChatItem>(reply("r7", text, live = true))))
        assertTrue(joined.none { it is AttachmentRow })
        // (c) The same reply stored.
        assertEquals(1, local(listOf(reply("r5", text))).count { it is AttachmentRow })
    }

    @Test
    fun tagOnlyReplyHasNoTextRows() {
        val p = reply("r1", "MEDIA:$chart")
        assertEquals(listOf(AttachmentRow(p, 0, Attachment(chart, PICTURE)), FooterRow(p)), local(listOf(p)))
        val f = reply("r1", "MEDIA:$pdf")
        assertEquals(listOf(AttachmentRow(f, 0, Attachment(pdf, FILE)), FooterRow(f)), local(listOf(f)))
    }

    @Test
    fun mixedReplyKeepsItsTagLine() {
        val text = "Here it is.\nMEDIA:$chart"
        val r = reply("r1", text)
        val textRows = replyRows("r1", text, closeFences = true)
        assertTrue(textRows.isNotEmpty())
        assertEquals(textRows + listOf(AttachmentRow(r, 0, Attachment(chart, PICTURE)), FooterRow(r)), local(listOf(r)))
    }

    @Test
    fun repliesWithoutPathsKeepTodaysRows() {
        val replies = listOf(
            reply("r1", "Just some prose, no file here."),
            reply("r2", "```kotlin\nval x = 1\n```"),
            reply("r3", "| a | b |\n|---|---|\n| 1 | 2 |"),
            reply("r4", "- one\n- two\n- three"),
            reply("r5", "", steps = listOf(step)),
        )
        for (r in replies) {
            val expected = (if (r.steps.isEmpty()) emptyList<Row>() else listOf(StepsRow(r))) +
                replyRows(r.key, r.text, closeFences = true) + FooterRow(r)
            assertEquals(r.key, expected, local(listOf(r)))
        }
    }

    // ---- tiles ----

    private class FakeClipboard : Clipboard {
        val writes = ArrayList<String?>()
        override suspend fun getClipEntry(): ClipEntry? = null
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            writes += clipEntry?.clipData?.getItemAt(0)?.text?.toString()
        }
        override val nativeClipboard get() = throw UnsupportedOperationException()
    }

    /**
     * One [MutableStateFlow] per (reply key, path); a null value holds the flow before its first emission. Records every
     * call, and in [ended] every collection that ended (a state flow never completes, so only by cancellation).
     */
    private class FakeAttachments : Attachments {
        val flows = HashMap<Pair<String, String>, MutableStateFlow<AttachmentState?>>()
        val calls = ArrayList<String>()
        val ended = ArrayList<String>()
        fun flow(replyKey: String, path: String) = flows.getOrPut(replyKey to path) { MutableStateFlow(AttachmentState.Loading) }
        override fun state(replyKey: String, path: String): Flow<AttachmentState> {
            calls += "state $replyKey $path"
            return flow(replyKey, path).filterNotNull().onCompletion { ended += "$replyKey $path" }
        }
        override fun retry(replyKey: String, path: String) {
            calls += "retry $replyKey $path"
        }
        override fun generation(replyKey: String, path: String): Long {
            calls += "generation $replyKey $path"
            return 0
        }
        override fun repair(replyKey: String, path: String, digest: String, generation: Long) {
            calls += "repair4 $replyKey $path $digest $generation"
        }
        override fun repair(replyKey: String, path: String, digest: String) {
            calls += "repair3 $replyKey $path $digest"
        }
        fun of(kind: String) = calls.filter { it.startsWith("$kind ") }
    }

    /** The real list. A null [attachments] provides no `LocalAttachments` at all, so its default is what shows. */
    @Composable
    private fun Tiles(
        items: List<ChatItem>,
        attachments: Attachments? = null,
        clipboard: Clipboard = FakeClipboard(),
        gate: (() -> Unit) -> Unit = { it() },
        fontScale: Float? = null,
    ) = ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
        val provided = listOfNotNull<ProvidedValue<*>>(
            LocalClipboard provides clipboard,
            LocalChatGate provides gate,
            attachments?.let { LocalAttachments provides it },
            fontScale?.let { LocalDensity provides Density(LocalDensity.current.density, it) },
        )
        CompositionLocalProvider(*provided.toTypedArray()) {
            MessageList(
                items, null, emptyMap(), answering = false, actions = object : ChatActions {}, onCancel = {}, onStep = {},
                listState = rememberLazyListState(), contentPadding = PaddingValues(), zone = ZoneOffset.UTC, today = FIXED_TODAY,
                modifier = Modifier.background(MaterialTheme.colorScheme.surface), // as ChatScreen; the bare window can match the tile fill
            )
        }
    }

    private val statusLines = mapOf(
        AttachmentState.Off to "On the server, not shown here",
        AttachmentState.Loading to "Loading picture",
        AttachmentState.Unavailable to "Cannot fetch from the picture folders",
        AttachmentState.Failed to "Could not load",
        AttachmentState.TooLarge to "Too large to load",
        AttachmentState.NotPicture to "Not a supported picture",
    )

    private fun count(matcher: SemanticsMatcher) = rule.onAllNodes(matcher, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun inTile(key: String = "r1/a0") = hasAnyAncestor(hasTestTag("attachment:$key"))

    /** The tile of [key] shows exactly [status] (null: no status line) and exactly the buttons [buttons]. */
    private fun assertTile(status: String?, buttons: List<String>, name: String = "chart.png", key: String = "r1/a0") {
        rule.waitForIdle()
        for (line in statusLines.values) {
            assertEquals("$line for $status", if (line == status) 1 else 0, count(hasText(line) and inTile(key)))
        }
        assertEquals(1, count(hasContentDescription("File: $name")))
        assertEquals("buttons for $status", buttons.size, count(hasClickAction() and inTile(key)))
        for (b in buttons) assertEquals(b, 1, count(hasText(b) and inTile(key)))
        assertEquals(0, count(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image) and inTile(key)))
    }

    @Test
    fun nullAttachmentsShowOff() {
        rule.setContent { Tiles(listOf(reply("r1", "MEDIA:$chart"))) }
        assertTile("On the server, not shown here", listOf("Copy file path"))
    }

    @Test
    fun tilesForEachState() {
        val fake = FakeAttachments()
        rule.setContent { Tiles(listOf(reply("r1", "MEDIA:$chart")), fake) }
        for ((state, line) in statusLines) {
            fake.flow("r1", chart).value = state
            val retry = state == AttachmentState.Unavailable || state == AttachmentState.Failed
            assertTile(line, if (retry) listOf("Copy file path", "Retry") else listOf("Copy file path"))
            assertEquals(if (retry) 1 else 0, count(hasText("Retry")))
        }
        assertEquals(listOf("state r1 $chart"), fake.calls) // one subscription, never asked again
    }

    @Test
    fun boundIsDrawnAsLoadingUntilDecode() {
        val fake = FakeAttachments()
        fake.flow("r1", chart).value = AttachmentState.Bound("d1")
        rule.setContent { Tiles(listOf(reply("r1", "MEDIA:$chart")), fake) }
        assertTile("Loading picture", listOf("Copy file path"))
    }

    @Test
    fun retryCallsTheCoordinator() {
        val fake = FakeAttachments()
        rule.setContent { Tiles(listOf(reply("r1", "MEDIA:$chart")), fake) }
        rule.waitForIdle()
        val subscribed = fake.of("state")
        assertEquals(listOf("state r1 $chart"), subscribed)
        for (state in listOf(AttachmentState.Unavailable, AttachmentState.Failed)) {
            fake.calls.clear()
            fake.flow("r1", chart).value = state
            rule.waitForIdle()
            rule.onNodeWithText("Retry").performClick()
            rule.waitForIdle()
            assertEquals("$state", listOf("retry r1 $chart"), fake.of("retry"))
            assertEquals("$state", emptyList<String>(), fake.of("state")) // the subscription is unchanged by the tap
            assertEquals("$state", emptyList<String>(), fake.of("generation") + fake.of("repair4") + fake.of("repair3"))
        }
    }

    @Test
    fun copyFilePathPutsThePathOnTheClipboard() {
        val clipboard = FakeClipboard()
        val path = "/srv/out/deep/folder/chart.png"
        rule.setContent { Tiles(listOf(reply("r1", "MEDIA:$path")), clipboard = clipboard) }
        rule.onNodeWithText("Copy file path").performClick()
        rule.waitForIdle()
        assertEquals(listOf<String?>(path), clipboard.writes)
    }

    @Test
    fun fileAttachmentNeverAsksForState() {
        val fake = FakeAttachments()
        rule.setContent { Tiles(listOf(reply("r1", "MEDIA:$pdf")), fake) }
        assertTile(null, listOf("Copy file path"), name = "report.pdf")
        assertEquals(emptyList<String>(), fake.calls)
    }

    @Test
    fun coveredChatDropsTileActions() {
        val fake = FakeAttachments()
        fake.flow("r1", chart).value = AttachmentState.Unavailable
        val clipboard = FakeClipboard()
        var covered by mutableStateOf(true)
        val drop: (() -> Unit) -> Unit = { _ -> }
        val pass: (() -> Unit) -> Unit = { it() }
        rule.setContent { Tiles(listOf(reply("r1", "MEDIA:$chart")), fake, clipboard, gate = if (covered) drop else pass) }
        rule.onNodeWithText("Copy file path").performClick()
        rule.onNodeWithText("Retry").performClick()
        rule.waitForIdle()
        assertEquals(emptyList<String?>(), clipboard.writes)
        assertEquals(emptyList<String>(), fake.of("retry") + fake.of("generation") + fake.of("repair4") + fake.of("repair3"))
        // The same taps through the default gate act once each.
        covered = false
        rule.waitForIdle()
        rule.onNodeWithText("Copy file path").performClick()
        rule.onNodeWithText("Retry").performClick()
        rule.waitForIdle()
        assertEquals(listOf<String?>(chart), clipboard.writes)
        assertEquals(listOf("retry r1 $chart"), fake.of("retry"))
    }

    private val frame = 16L

    @Test
    fun refreshedSlotShowsOnlyTheNewAttachment() {
        val other = "/srv/out/other.png"
        val fake = FakeAttachments()
        fake.flow("r1", chart).value = AttachmentState.Unavailable
        fake.flow("r1", other).value = null // held before its first emission
        var items by mutableStateOf(listOf<ChatItem>(reply("r1", "MEDIA:$chart")))
        rule.mainClock.autoAdvance = false
        rule.setContent { Tiles(items, fake) }
        rule.mainClock.advanceTimeBy(5 * frame)
        assertTile("Cannot fetch from the picture folders", listOf("Copy file path", "Retry"))
        rule.onNodeWithText("Copy file path").performClick()
        rule.mainClock.advanceTimeBy(5 * frame)
        assertEquals(1, count(hasText("Copied") and inTile()))
        // A stored refresh puts another path into the same indexed slot.
        items = listOf(reply("r1", "MEDIA:$other"))
        rule.mainClock.advanceTimeBy(5 * frame)
        assertEquals(1, count(hasTestTag("attachment:r1/a0")))
        assertTile("Loading picture", listOf("Copy file path"), name = "other.png")
        assertEquals(0, count(hasText("Copied")))
        assertEquals(listOf("r1 $chart"), fake.ended)
        assertEquals(listOf("state r1 $chart", "state r1 $other"), fake.of("state"))
        // Then the new flow's state arrives.
        fake.flow("r1", other).value = AttachmentState.Failed
        rule.mainClock.advanceTimeBy(5 * frame)
        assertTile("Could not load", listOf("Copy file path", "Retry"), name = "other.png")
    }

    @Test
    @Config(qualifiers = "pl-w240dp-h760dp-xhdpi")
    fun narrowLargeTextTileKeepsBothButtons() {
        val copy = "Kopiuj ścieżkę pliku"
        val retry = "Ponów"
        val fake = FakeAttachments()
        fake.flow("r1", chart).value = AttachmentState.Failed
        val clipboard = FakeClipboard()
        rule.mainClock.autoAdvance = false
        rule.setContent { Tiles(listOf(reply("r1", "MEDIA:$chart")), fake, clipboard, fontScale = 2f) }
        rule.mainClock.advanceTimeBy(5 * frame)
        rule.onNodeWithText("Nie udało się wczytać").assertIsDisplayed()
        for (label in listOf(copy, retry)) {
            val b = rule.onNode(hasText(label) and hasClickAction()).getUnclippedBoundsInRoot()
            assertTrue("$label: $b", b.right - b.left >= 48.dp && b.bottom - b.top >= 48.dp)
        }
        rule.onNode(hasText(retry) and hasClickAction()).performClick()
        rule.mainClock.advanceTimeBy(5 * frame)
        assertEquals(listOf("retry r1 $chart"), fake.of("retry"))
        rule.onNode(hasText(copy) and hasClickAction()).performClick()
        rule.mainClock.advanceTimeBy(5 * frame)
        assertEquals(listOf<String?>(chart), clipboard.writes)
        rule.onNodeWithText("Skopiowano").assertIsDisplayed()
    }

    @Test
    fun tileIsAtTheReplyTextWidth() {
        val prose = "This reply has a sentence long enough to wrap over more than one line of the reply column."
        rule.setContent { Tiles(listOf(reply("r1", "$prose\nMEDIA:$chart\nMEDIA:$pdf"))) }
        rule.waitForIdle()
        val px = rule.onRoot().drawnPixels()
        val text = rule.onNode(hasText(prose, substring = true)).fetchSemanticsNode().boundsInWindow
        val unit = rule.density.density // px per dp
        val fills = listOf("r1/a0", "r1/a1").map { key ->
            val copy = rule.onNode(hasText("Copy file path") and inTile(key)).fetchSemanticsNode().boundsInWindow
            val y = copy.center.y.roundToInt()
            val fill = px[(copy.left - 1).roundToInt(), y] // the tile's fill, left of the first button
            var left = (copy.left - 1).roundToInt()
            while (left > 0 && px[left - 1, y] == fill) left--
            var right = (copy.right + 1).roundToInt()
            while (right < px.width - 1 && px[right + 1, y] == fill) right++
            // Fill rows at a column past the corner radius, within this row.
            val x = left + (12 * unit).roundToInt() + 1
            val box = rule.onNodeWithTag("attachment:$key", useUnmergedTree = true).fetchSemanticsNode().boundsInWindow
            val rows = (box.top.roundToInt() until box.bottom.roundToInt()).filter { px[x, it] == fill }
            listOf(left, right + 1, rows.first(), rows.last())
        }
        for ((left, right) in fills) {
            assertEquals("left $fills text $text", text.left, left.toFloat(), 1f)
            assertEquals("right $fills text $text", text.right, right.toFloat(), 1f)
        }
        val (_, _, _, firstBottom) = fills[0]
        val (_, _, secondTop, _) = fills[1]
        assertTrue("stacked tiles touch: $fills", secondTop - firstBottom > 1)
    }

    @Test
    fun attachmentRowsAreTagged() {
        rule.setContent { Tiles(listOf(UserMessage("u1", "Draw it", false, FIXED_NOW), reply("r1", "Here it is.\nMEDIA:$chart", ts = FIXED_NOW))) }
        rule.waitForIdle()
        assertEquals(1, count(hasTestTag("attachment:r1/a0")))
        assertEquals(1, count(hasTestTag("user:u1")))
        assertEquals(1, count(hasTestTag("reply:r1/b0/c0")))
    }

    @Test
    fun tagOnlyCopyAndSelectGiveTheRawText() {
        val text = "MEDIA:$chart"
        val clipboard = FakeClipboard()
        val items = listOf<ChatItem>(reply("r1", text, ts = FIXED_NOW))
        val state = ChatUiState("s", "Chat", StatusLine.IDLE, withLive(items, null) { FIXED_NOW }, ComposerMode.SEND_DISABLED, "")
        rule.setContent {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                CompositionLocalProvider(LocalClipboard provides clipboard) {
                    ChatScreen(state, object : ChatActions {}, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY)
                }
            }
        }
        assertEquals(0, count(hasText(text))) // the tag line itself is not drawn
        rule.onNodeWithContentDescription("Copy").performClick()
        rule.waitForIdle()
        assertEquals(listOf<String?>(text), clipboard.writes)
        rule.onNodeWithContentDescription("Select text").performClick()
        rule.waitForIdle()
        assertEquals(1, rule.onAllNodes(hasText(text) and hasAnyAncestor(isDialog())).fetchSemanticsNodes().size)
    }
}
