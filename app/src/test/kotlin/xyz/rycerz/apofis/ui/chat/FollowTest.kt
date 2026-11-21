package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.gestures.scrollBy
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
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
import xyz.rycerz.apofis.run.Connection
import xyz.rycerz.apofis.run.LiveReply
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.FIXED_NOW
import xyz.rycerz.apofis.ui.FIXED_TODAY
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.Motion

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class FollowTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun rules() {
        val density = 2f // xhdpi
        val threshold = 56 * density
        val following = FollowState(following = true, holdUntilMs = 0)
        val released = onUserDrag(following)
        assertFalse(released.following)
        assertTrue(onScrollSettled(released, 55 * density, threshold).following)
        assertTrue(onScrollSettled(released, 56 * density, threshold).following) // the boundary is inside
        assertFalse(onScrollSettled(released, 57 * density, threshold).following)
        assertFalse(onScrollSettled(following, 57 * density, threshold).following)
        // A reader released outside the zone loses any hold: an external scroll cancels the handoff.
        assertEquals(FollowState(false, 0), onScrollSettled(onRunEnded(following, 1000), 57 * density, threshold))
        assertEquals(1000L + Motion.FOLLOW_HOLD, onScrollSettled(onRunEnded(following, 1000), 55 * density, threshold).holdUntilMs)

        assertFalse(shouldPin(released, contentGrew = true, atBottom = false, nowMs = 1000))
        assertTrue(shouldPin(following, contentGrew = true, atBottom = false, nowMs = 1000))
        assertFalse(shouldPin(following, contentGrew = true, atBottom = true, nowMs = 1000))
        assertFalse(shouldPin(following, contentGrew = false, atBottom = false, nowMs = 1000))

        val held = onRunEnded(following, nowMs = 1000)
        assertEquals(1000L + Motion.FOLLOW_HOLD, held.holdUntilMs)
        assertTrue(shouldPin(held, contentGrew = false, atBottom = false, nowMs = 1000 + Motion.FOLLOW_HOLD - 1L))
        assertFalse(shouldPin(held, contentGrew = false, atBottom = false, nowMs = 1000L + Motion.FOLLOW_HOLD))
        assertFalse(shouldPin(held.copy(following = false), contentGrew = true, atBottom = false, nowMs = 1000L + Motion.FOLLOW_HOLD))

        // A reader who scrolled away is not caught by the hold, and a drag inside the hold releases at once.
        assertEquals(released, onRunEnded(released, nowMs = 1000))
        assertFalse(shouldPin(onUserDrag(held), contentGrew = true, atBottom = false, nowMs = 1001))
    }

    // ---- on the real screen ----

    private val history: List<ChatItem> = (1..12).flatMap {
        listOf(
            UserMessage("u$it", "Question $it", steered = false, timestamp = FIXED_NOW),
            AssistantReply("r$it", emptyList(), (1..4).joinToString("\n\n") { p -> "Answer $it, paragraph $p, long enough to wrap onto a second line." }, FIXED_NOW),
        )
    } + UserMessage("u13", "Write a lot", steered = false, timestamp = FIXED_NOW, runId = "run_1")

    private fun state(live: LiveReply?, items: List<ChatItem> = history) =
        ChatUiState("apofis-1", "Follow", if (live != null) StatusLine.WORKING else StatusLine.IDLE, withLive(items, live) { FIXED_NOW }, ComposerMode.STOP, "", "hermes.example", live)

    private fun live(text: String) = LiveReply("run_1", "running", text, emptyList(), null, Connection.LIVE)

    /** Paragraphs of 100 characters, so growth adds new display rows below the anchored one. */
    private fun text(chars: Int) = buildString {
        var p = 0
        while (length < chars) append("Paragraph ${p++} ").append("x".repeat(80)).append("\n\n")
    }.take(chars)

    private fun frames(n: Int = 1) = repeat(n) {
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeByFrame()
    }

    private fun settle() {
        frames(600) // a fling ends and the scroll button hides; whole frames only
    }

    private lateinit var list: LazyListState

    private fun show(initial: ChatUiState, index: Int = 0, offset: Int = 0): (ChatUiState) -> Unit {
        rule.mainClock.autoAdvance = false
        var ui by mutableStateOf(initial)
        val actions = object : ChatActions {} // stable, as the view model is in the app
        rule.setContent {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                list = androidx.compose.foundation.lazy.rememberLazyListState(index, offset)
                val now = ui
                // As the app does: a new current-state reader with every state (it must not reach the composer or header).
                ChatScreen(now, actions, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY, listState = list, currentState = { now })
            }
        }
        frames(10)
        return { ui = it }
    }

    /** How far the bottom row is shifted from its resting place; large when it is off screen. */
    private fun distanceFromBottom() = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }?.let { -it.offset } ?: 100_000

    private fun atBottom() = list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0

    /** A real drag of [dy] px on the list (positive moves the content down, away from the bottom of the reversed list). */
    private fun drag(dy: Float) {
        rule.onNode(hasScrollToIndexAction()).performTouchInput {
            down(Offset(centerX, centerY - dy / 2))
            repeat(30) { moveBy(Offset(0f, dy / 30)) }
            advanceEventTime(200) // the finger rests before lifting: no fling
            up()
        }
        settle()
    }

    /** A scroll that is not a drag (as layout drift would be): following is not released by it. */
    private fun nudge(px: Float) {
        rule.runOnUiThread { runBlocking { list.scrollBy(px) } } // positive moves away from the bottom of the reversed list
        frames(2)
    }

    @Test
    fun growingTailStaysPinned() {
        val set = show(state(live(text(100))))
        assertTrue(atBottom())
        for (i in 1..20) {
            set(state(live(text(100 + i * 100))))
            frames()
            assertTrue("frame $i: ${list.firstVisibleItemIndex}/${list.firstVisibleItemScrollOffset}", atBottom())
        }
        // The reply's end row anchors the bottom by itself; a follower 30 dp up (inside the 56 dp zone) is pinned by the rules.
        nudge(60f)
        assertEquals(60, distanceFromBottom())
        for (i in 21..40) {
            set(state(live(text(100 + i * 100))))
            frames()
            assertTrue("frame $i: ${list.firstVisibleItemIndex}/${list.firstVisibleItemScrollOffset}", atBottom())
        }
    }

    @Test
    fun readerIsNotPulledBack() {
        val set = show(state(live(text(400))))
        drag(600f) // 300 dp at xhdpi
        val index = list.firstVisibleItemIndex
        val offset = list.firstVisibleItemScrollOffset
        val anchored = list.layoutInfo.visibleItemsInfo.first().key
        assertFalse(atBottom())
        for (i in 1..40) {
            set(state(live(text(400 + i * 100))))
            frames()
        }
        // New rows are added below the reader, so the anchored row keeps its place on screen, never moved to the bottom.
        assertFalse(atBottom())
        assertEquals(anchored, list.layoutInfo.visibleItemsInfo.first().key)
        assertTrue("index $index -> ${list.firstVisibleItemIndex}", list.firstVisibleItemIndex >= index)
        assertEquals(offset, list.firstVisibleItemScrollOffset)
    }

    @Test
    fun reacquireWithin56dp() {
        val set = show(state(live(text(400))))
        drag(400f)
        assertFalse(atBottom())
        assertTrue("${distanceFromBottom()}", distanceFromBottom() > 112)
        // Back to 40 dp (80 px) from the bottom; both drags lose the same touch slop, so it cancels.
        drag(-320f)
        assertTrue("distance ${distanceFromBottom()}", distanceFromBottom() in 1..112)
        for (i in 1..5) {
            set(state(live(text(400 + i * 100))))
            frames()
        }
        assertTrue("${list.firstVisibleItemIndex}/${list.firstVisibleItemScrollOffset}", atBottom())
    }

    @Test
    fun handoffStaysPinned() {
        val set = show(state(live(text(1200))))
        assertTrue(atBottom())
        set(state(null, history + AssistantReply("r99", emptyList(), text(1600), FIXED_NOW))) // the stored reply, longer
        frames()
        assertTrue("${list.firstVisibleItemIndex}/${list.firstVisibleItemScrollOffset}", atBottom())
        nudge(60f) // the stored rows settle during the hold and shift the list without any new content
        frames(Motion.FOLLOW_HOLD / 16)
        assertTrue("${list.firstVisibleItemIndex}/${list.firstVisibleItemScrollOffset}", atBottom())
        nudge(60f) // after the hold nothing pulls the list back without new content
        frames(10)
        assertEquals(60, distanceFromBottom())
    }

    /** The row on screen at the reader's position and its offset (the index shifts as rows are added below). */
    private fun anchor() = list.layoutInfo.visibleItemsInfo.first().key to list.firstVisibleItemScrollOffset

    /** I1: a position restored many rows up (recreation) is a reader, not a follower: growth and the run end leave it. */
    @Test
    fun restoredReaderIsNotPulled() {
        val set = show(state(live(text(400))), index = 12, offset = 40)
        assertFalse(atBottom())
        val key = list.layoutInfo.visibleItemsInfo.first().key
        val offset = list.firstVisibleItemScrollOffset
        for (i in 1..20) {
            set(state(live(text(400 + i * 100))))
            frames()
            assertEquals("frame $i", key, list.layoutInfo.visibleItemsInfo.first().key)
            assertEquals("frame $i", offset, list.firstVisibleItemScrollOffset)
        }
        set(state(null, history + AssistantReply("r99", emptyList(), text(2400), FIXED_NOW)))
        for (f in 0..(Motion.FOLLOW_HOLD / 16 + 4)) {
            frames()
            assertEquals("hold frame $f", key, list.layoutInfo.visibleItemsInfo.first().key)
            assertEquals("hold frame $f", offset, list.firstVisibleItemScrollOffset)
        }
    }

    /** I2: a scroll without a drag (TalkBack's scroll action) is not cancelled by streamed growth. */
    @Test
    fun semanticsScrollIsNotCancelledByGrowth() {
        val set = show(state(live(text(400))))
        assertTrue(atBottom())
        rule.onNode(hasScrollToIndexAction()).performSemanticsAction(SemanticsActions.ScrollBy) { it(0f, 800f) }
        for (i in 1..120) {
            if (i in 2..30) set(state(live(text(400 + i * 100))))
            frames()
        }
        assertTrue("distance ${distanceFromBottom()}", distanceFromBottom() > 112)
    }

    /** I2: the button's animation owns the scroll: growth during it requests no pin, even for a follower within 56 dp. */
    @Test
    fun noPinDuringTheButtonAnimation() {
        val set = show(state(live(text(400))))
        nudge(100f) // 50 dp up: still following, and the scroll shows the button
        assertTrue(list.canScrollBackward)
        rule.onNodeWithContentDescription("Scroll to the latest message").performClick()
        frames(2)
        assertTrue("the animation runs", list.isScrollInProgress)
        val pins = ChromeProbe.pins
        for (i in 1..5) {
            set(state(live(text(400 + i * 100))))
            frames()
        }
        assertEquals("a pin was requested during the button's animation", pins, ChromeProbe.pins)
        frames(120)
        assertTrue(atBottom())
    }

    /** I2: a handoff hold ends when the reader scrolls (without a drag) more than 56 dp away before it expires. */
    @Test
    fun externalScrollCancelsTheHandoffHold() {
        val set = show(state(live(text(1200))))
        assertTrue(atBottom())
        set(state(null, history + AssistantReply("r99", emptyList(), text(1600), FIXED_NOW)))
        frames(6) // the hold has started; still inside its 450 ms
        rule.runOnUiThread { runBlocking { list.scrollBy(600f) } } // a keyboard or wheel scroll: 300 dp away
        val held = anchor() // right after it: the next frames must not pull it back
        assertFalse(atBottom())
        for (f in 0..(Motion.FOLLOW_HOLD / 16 + 4)) {
            frames()
            assertEquals("frame $f: pulled back", held, anchor())
        }
    }

    /** I1: restored with a saved position while the transcript is still empty, the reader stays a reader once it loads. */
    @Test
    fun restoredOverAnEmptyFirstLayoutStaysAReader() {
        val set = show(ChatUiState("apofis-1", "Follow", StatusLine.IDLE, emptyList(), ComposerMode.SEND, "", "hermes.example"), index = 12, offset = 40)
        frames(5)
        set(state(live(text(400))))
        frames(5)
        assertFalse(atBottom())
        val held = anchor()
        for (i in 1..20) {
            set(state(live(text(400 + i * 100))))
            frames()
            assertEquals("growth frame $i", held, anchor())
        }
        val position = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        set(state(null, history + AssistantReply("r99", emptyList(), text(2400), FIXED_NOW)))
        for (f in 0..(Motion.FOLLOW_HOLD / 16 + 4)) {
            frames()
            assertTrue("hold frame $f: moved to the bottom", !atBottom())
        }
    }

    /** The drag itself releases following: with the finger still down, growth and the run end move nothing. */
    @Test
    fun heldDragReleasesFollowing() {
        val set = show(state(live(text(400))))
        nudge(40f) // following, 20 dp up: growth alone would pin this
        frames(4)
        val node = rule.onNode(hasScrollToIndexAction())
        node.performTouchInput {
            down(center)
            repeat(10) { moveBy(Offset(0f, 30f)) } // away, about 150 dp
        }
        frames(4)
        val held = anchor()
        for (i in 1..10) {
            set(state(live(text(400 + i * 100))))
            frames()
            assertEquals("growth frame $i", held, anchor())
        }
        val position = list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
        set(state(null, history + AssistantReply("r99", emptyList(), text(1600), FIXED_NOW)))
        for (f in 0..(Motion.FOLLOW_HOLD / 16 + 4)) { // the reply's keys change at the swap: the position must hold
            frames()
            assertEquals("hold frame $f", position, list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset)
        }
        node.performTouchInput {
            advanceEventTime(200)
            up()
        }
        settle()
    }

    /** Settling just outside the 56 dp zone leaves the reader; just inside resumes following (with [reacquireWithin56dp]). */
    @Test
    fun settleJustOutside56dpStaysReleased() {
        val set = show(state(live(text(400))))
        drag(400f)
        drag(-254f) // back to 146 px (73 dp) from the bottom: both drags lose the same touch slop
        val now = distanceFromBottom()
        assertTrue("distance $now", now in 113..170)
        for (i in 1..5) {
            set(state(live(text(400 + i * 100))))
            frames()
            assertTrue("frame $i: pulled to ${distanceFromBottom()}", distanceFromBottom() > 112)
        }
    }

    /** I11: a streamed token recomposes only the transcript: not the header, not the composer, and no root measure. */
    @Test
    fun streamedTokensLeaveTheChromeAlone() {
        val set = show(state(live(text(100))))
        frames(5)
        val bar = ChromeProbe.barCompositions
        val bottom = ChromeProbe.bottomCompositions
        val root = ChromeProbe.rootMeasures
        for (i in 1..100) {
            set(state(live(text(100 + i * 40))))
            frames()
        }
        assertEquals("header recomposed by tokens", bar, ChromeProbe.barCompositions)
        assertEquals("composer recomposed by tokens", bottom, ChromeProbe.bottomCompositions)
        assertEquals("root measured by tokens", root, ChromeProbe.rootMeasures)
        // Control: real chrome changes still reach them.
        set(state(live(text(4200))).copy(title = "Renamed", draft = "typed"))
        frames(2)
        assertTrue(ChromeProbe.barCompositions > bar)
        assertTrue(ChromeProbe.bottomCompositions > bottom)
    }
}
