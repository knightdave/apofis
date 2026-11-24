package xyz.rycerz.apofis.ui.image

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.run.Connection
import xyz.rycerz.apofis.run.LiveReply
import xyz.rycerz.apofis.ui.FIXED_NOW
import xyz.rycerz.apofis.ui.FIXED_TODAY
import xyz.rycerz.apofis.ui.chat.ChatActions
import xyz.rycerz.apofis.ui.chat.ChatScreen
import xyz.rycerz.apofis.ui.chat.ChatUiState
import xyz.rycerz.apofis.ui.chat.ChromeProbe
import xyz.rycerz.apofis.ui.chat.ComposerMode
import xyz.rycerz.apofis.ui.chat.StatusLine
import xyz.rycerz.apofis.ui.chat.withLive
import xyz.rycerz.apofis.ui.markdown.Themed
import xyz.rycerz.apofis.ui.markdown.dataUrl
import xyz.rycerz.apofis.ui.markdown.pngBytes

/** A reply image on screen, and the viewer opened from it and closed, leave the chrome alone while tokens stream. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class ReplyImageChromeTest {
    @get:Rule
    val rule = createComposeRule()

    private val items = listOf(
        UserMessage("u1", "Draw a dot", steered = false, timestamp = FIXED_NOW),
        AssistantReply("r0", emptyList(), "Here:\n\n![A dot](${dataUrl(pngBytes(4, 3))})", FIXED_NOW),
        UserMessage("u2", "Thanks", steered = false, timestamp = FIXED_NOW),
    )

    /** The live reply at [chars] characters: one more per token, so the image stays on screen. */
    private fun state(chars: Int): ChatUiState {
        val live = LiveReply("run_1", "running", "lorem ipsum ".repeat(30).take(chars), emptyList(), null, Connection.LIVE)
        return ChatUiState("k", "Chat", StatusLine.WORKING, withLive(items, live) { FIXED_NOW }, ComposerMode.STOP, "", "hermes.example", live)
    }

    private fun frames(n: Int = 1) = repeat(n) {
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeByFrame()
    }

    private val tile = hasContentDescription("A dot") and hasClickAction()

    private fun exists(m: SemanticsMatcher) = rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun chromeStableWithReplyImageAndViewer() {
        var ui by mutableStateOf(state(1))
        rule.setContent { Themed { val now = ui; ChatScreen(now, object : ChatActions {}, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY, currentState = { now }) } }
        rule.waitUntil(10_000) { exists(tile) }
        rule.mainClock.autoAdvance = false
        frames(5)
        val bar = ChromeProbe.barCompositions
        val bottom = ChromeProbe.bottomCompositions
        val root = ChromeProbe.rootMeasures
        fun stream(from: Int) = (1..100).forEach { ui = state(from + it); frames() }
        fun assertChromeAlone(phase: String) {
            assertEquals("header recomposed $phase", bar, ChromeProbe.barCompositions)
            assertEquals("composer recomposed $phase", bottom, ChromeProbe.bottomCompositions)
            assertEquals("root measured $phase", root, ChromeProbe.rootMeasures)
        }
        stream(1)
        assertTrue("the image is on screen", exists(tile))
        assertChromeAlone("by tokens beside a reply image")
        rule.onNode(tile).performSemanticsAction(SemanticsActions.OnClick)
        rule.waitUntil(10_000) { frames(); exists(hasContentDescription("Close image")) }
        rule.onNodeWithContentDescription("Close image").performSemanticsAction(SemanticsActions.OnClick)
        frames(5)
        assertTrue("the viewer closed", !exists(hasContentDescription("Close image")))
        assertChromeAlone("by opening and closing the viewer")
        stream(101)
        assertTrue(exists(tile))
        assertChromeAlone("by tokens after the viewer closed")
    }
}
