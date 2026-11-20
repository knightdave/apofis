package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performTouchInput
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

    private fun show(initial: ChatUiState): (ChatUiState) -> Unit {
        rule.mainClock.autoAdvance = false
        var ui by mutableStateOf(initial)
        rule.setContent {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                list = androidx.compose.foundation.lazy.rememberLazyListState()
                ChatScreen(ui, object : ChatActions {}, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY, listState = list)
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
        set(state(null, history + AssistantReply("r9", emptyList(), text(1600), FIXED_NOW))) // the stored reply, longer
        frames()
        assertTrue("${list.firstVisibleItemIndex}/${list.firstVisibleItemScrollOffset}", atBottom())
        nudge(60f) // the stored rows settle during the hold and shift the list without any new content
        frames(Motion.FOLLOW_HOLD / 16)
        assertTrue("${list.firstVisibleItemIndex}/${list.firstVisibleItemScrollOffset}", atBottom())
        nudge(60f) // after the hold nothing pulls the list back without new content
        frames(10)
        assertEquals(60, distanceFromBottom())
    }
}
