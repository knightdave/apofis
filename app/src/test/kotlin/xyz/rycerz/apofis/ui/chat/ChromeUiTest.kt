package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.layout.Box
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
        rule.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown() } // away from the bottom of the reversed list
        frames()
        assertTrue(list.canScrollBackward)
        assertTrue(buttonShown())
        var guard = 0
        while (list.isScrollInProgress) frames().also { check(guard++ < 600) }
        // Scrolling ended during the last frame: whole frames up to just under 2000 ms keep the button.
        val keep = (Motion.SCROLL_BUTTON_HIDE - 1) / 16 - 1
        frames(keep)
        assertTrue("after ${keep * 16} ms idle", buttonShown())
        frames(Motion.SCROLL_BUTTON_SLIDE / 16 + 6) // the remaining idle frames, the exit animation and its removal
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
        val tops = rule.onAllNodes(row, useUnmergedTree = true).fetchSemanticsNodes().map { it.boundsInRoot.top }
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
}
