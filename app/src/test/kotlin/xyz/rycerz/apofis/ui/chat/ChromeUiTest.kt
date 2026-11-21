package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.test.assertIsDisplayed
import xyz.rycerz.apofis.ui.theme.backdropSource
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import java.time.ZoneOffset
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
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.LocalBlur
import xyz.rycerz.apofis.ui.theme.Motion
import xyz.rycerz.apofis.ui.theme.frostedGlass
import xyz.rycerz.apofis.ui.theme.rememberBackdropState

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class ChromeUiTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun scrollButtonVisibilityRules() {
        assertFalse(scrollButtonVisible(away = false, scrolling = true, idleMs = 0))
        assertTrue(scrollButtonVisible(away = true, scrolling = true, idleMs = 5000))
        assertTrue(scrollButtonVisible(away = true, scrolling = false, idleMs = 1999))
        assertFalse(scrollButtonVisible(away = true, scrolling = false, idleMs = 2000))
    }

    private val items: List<ChatItem> = (1..30).flatMap {
        listOf(
            UserMessage("u$it", "Question $it", steered = false, timestamp = FIXED_NOW),
            AssistantReply("r$it", emptyList(), "Answer $it, long enough to wrap onto a second line of the chat screen.", FIXED_NOW),
        )
    } // 60 items

    private fun frames(n: Int = 1) = repeat(n) {
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeByFrame()
    }

    private lateinit var list: LazyListState

    private fun show(initial: ChatUiState): (ChatUiState) -> Unit {
        rule.mainClock.autoAdvance = false
        var ui by mutableStateOf(initial)
        rule.setContent {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                list = rememberLazyListState()
                ChatScreen(ui, object : ChatActions {}, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY, listState = list)
            }
        }
        frames(10)
        return { ui = it }
    }

    private fun chat(title: String = "Chrome") = ChatUiState("apofis-1", title, StatusLine.IDLE, items, ComposerMode.SEND_DISABLED, "", "hermes.example")

    private val button = hasContentDescription("Scroll to the latest message")

    private fun buttonShown() = rule.onAllNodes(button).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun scrollButtonHidesAfterIdle() {
        show(chat())
        assertFalse(buttonShown()) // at the bottom, not scrolling
        rule.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown(startY = top + height / 4, endY = bottom - height / 4) } // away from the bottom; starts below the bar
        frames()
        assertTrue(list.canScrollBackward)
        assertTrue(buttonShown())
        var guard = 0
        while (list.isScrollInProgress) frames().also { check(guard++ < 600) }
        // Scrolling ended during the last frame: the last frame before 2000 ms keeps the button on screen.
        frames(Motion.SCROLL_BUTTON_HIDE / 16 - 1)
        rule.onNode(button).assertIsDisplayed()
        // The first frame past it starts the exit; after the exit animation the button is gone.
        frames(2 + Motion.SCROLL_BUTTON_SLIDE / 16 + 2)
        assertFalse(buttonShown())
    }

    @Test
    fun oneTitleNodeDuringCrossfade() {
        val set = show(chat("Old title"))
        set(chat("New title"))
        frames(Motion.TITLE_FADE / 2 / 16)
        val titles = rule.onAllNodes(hasTestTag("chat.title"), useUnmergedTree = true).fetchSemanticsNodes()
        assertEquals(1, titles.size)
        assertEquals(1, rule.onAllNodes(hasTestTag("chat.title") and hasText("New title"), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodes(hasText("Old title"), useUnmergedTree = true).fetchSemanticsNodes().size) // hidden from TalkBack
    }

    @Test
    fun listDrawsUnderTopBar() {
        show(chat())
        val bar = rule.onNodeWithTag("chat.topbar", useUnmergedTree = true).getBoundsInRoot()
        val row = SemanticsMatcher("a chat row") { node ->
            node.config.getOrNull(SemanticsProperties.TestTag)?.let { it.startsWith("user:") || it.startsWith("reply:") } == true
        }
        // Layout positions, not the clipped semantic bounds: rows under the bar are drawn there but hidden from touch and TalkBack.
        val tops = rule.onAllNodes(row, useUnmergedTree = true).fetchSemanticsNodes().map { it.layoutInfo.coordinates.positionInRoot().y }
        assertTrue(tops.isNotEmpty())
        with(rule.density) { assertTrue("topmost row ${tops.min()} px, bar bottom ${bar.bottom.toPx()} px", tops.min() < bar.bottom.toPx()) }
    }

    @Test
    fun glassWithoutBlurDrawsFill() {
        rule.setContent {
            Box(Modifier.testTag("plain").size(40.dp).frostedGlass(null, CircleShape, Color.Red, Color.Blue))
        }
        assertEquals(Color.Red, rule.onNodeWithTag("plain").captureToImage().toPixelMap()[40, 40])
    }

    @Test
    fun glassWithStateButBlurOffDrawsFill() {
        rule.setContent {
            CompositionLocalProvider(LocalBlur provides false) {
                val state = rememberBackdropState()
                Box(Modifier.testTag("glass").size(40.dp).frostedGlass(state, CircleShape, Color.Red, Color.Blue))
            }
        }
        assertEquals(Color.Red, rule.onNodeWithTag("glass").captureToImage().toPixelMap()[40, 40])
    }

    /**
     * The node's pixels. Robolectric sometimes captures a frame with nothing drawn (the window background, #FAFAFA),
     * also in unrelated goldens; such a capture is taken again after a frame, a few times at most.
     */
    private fun pixels(tag: String): androidx.compose.ui.graphics.PixelMap {
        repeat(5) {
            val px = rule.onNodeWithTag(tag).captureToImage().toPixelMap()
            if (px[px.width / 2, px.height / 2] != Color(0xFFFAFAFA)) return px // these tests never draw the window color there
            rule.mainClock.advanceTimeByFrame()
            rule.waitForIdle()
        }
        return rule.onNodeWithTag(tag).captureToImage().toPixelMap()
    }

    /** I8: inside the glass the backdrop's sharp ink is replaced by its blur; just outside it is unchanged. */
    @Test
    fun glassReplacesTheSharpBackdrop() {
        rule.setContent {
            CompositionLocalProvider(LocalBlur provides true) {
                val state = rememberBackdropState()
                Box(Modifier.testTag("page").size(120.dp).background(Color.White)) {
                    // The source: a black stroke on the page (the page itself belongs to the parent, as in the chat).
                    Box(Modifier.fillMaxSize().backdropSource(state, Color.White)) {
                        Box(Modifier.align(Alignment.Center).size(2.dp, 120.dp).background(Color.Black))
                    }
                    Box(Modifier.align(Alignment.Center).size(60.dp).frostedGlass(state, RectangleShape, Color.Transparent, Color.Transparent, blur = 12.dp))
                }
            }
        }
        val px = pixels("page")
        val inside = px[120, 120].luminance()
        val outside = px[120, 10].luminance()
        assertTrue("outside the glass the stroke is sharp: $outside", outside < 0.1f)
        assertTrue("inside the glass the stroke is blurred away: $inside", inside > 0.6f)
    }

    /** M2: a consumer under a local blur-off draws fill only (records no sample), even when handed a recorded backdrop. */
    @Test
    fun localBlurOffAtTheConsumerWins() {
        var consumerBlur by mutableStateOf(true)
        var fill by mutableStateOf(Color.Transparent)
        lateinit var backdrop: xyz.rycerz.apofis.ui.theme.BackdropState
        rule.setContent {
            CompositionLocalProvider(LocalBlur provides true) {
                backdrop = rememberBackdropState()
                Box(Modifier.size(80.dp)) {
                    Box(Modifier.fillMaxSize().backdropSource(backdrop, Color.Blue))
                    CompositionLocalProvider(LocalBlur provides consumerBlur) {
                        Box(Modifier.align(Alignment.Center).size(40.dp).frostedGlass(backdrop, RectangleShape, fill, Color.Transparent))
                    }
                }
            }
        }
        rule.waitForIdle()
        assertTrue("control: a consumer with blur on samples the backdrop", backdrop.samples > 0)
        consumerBlur = false
        rule.waitForIdle()
        val before = backdrop.samples
        fill = Color.Red // forces the consumer to draw again, now with blur off
        rule.waitForIdle()
        assertEquals("no sample once blur is off at the consumer", before, backdrop.samples)
    }
}
