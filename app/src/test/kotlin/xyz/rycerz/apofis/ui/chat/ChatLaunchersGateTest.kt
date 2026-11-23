package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.api.PendingControl
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.ChatItem
import xyz.rycerz.apofis.chat.ToolStep
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.run.Connection
import xyz.rycerz.apofis.run.LiveReply
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.FIXED_NOW
import xyz.rycerz.apofis.ui.FIXED_TODAY
import xyz.rycerz.apofis.ui.theme.ApofisTheme

/**
 * The chat screen's own launchers under the drawer gate ([ChatGateTest] covers the view model's actions): opening a
 * tool step's sheet, Select, a link, the scroll button, Retry and Connection from a failed load or rejected key, and a
 * long approval command's reader. While the drawer covers the chat ([ChatScreen]'s live `coveredNow`), each tap
 * opens, scrolls or fires nothing, also a press that began before the drawer came out; uncovered, each works once.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class ChatLaunchersGateTest {
    @get:Rule
    val rule = createComposeRule()

    private var covered = false
    private val opened = ArrayList<String>()
    private var retries = 0
    private var connections = 0
    private lateinit var list: LazyListState

    private val history: List<ChatItem> = (1..20).flatMap {
        listOf(UserMessage("u$it", "Question $it", steered = false, timestamp = FIXED_NOW), AssistantReply("a$it", emptyList(), "Answer $it", FIXED_NOW))
    }
    private val reply = AssistantReply(
        "r1",
        listOf(ToolStep("c1", "terminal", "command=ls", "ok", resultTruncated = false, error = false, running = false)),
        "See [the docs](https://example.com/docs).",
        FIXED_NOW,
    )
    private val stored = ChatUiState("k", "Chat", StatusLine.IDLE, history + reply, ComposerMode.SEND, "", "hermes.example")
    private var ui by mutableStateOf(stored)

    private fun frames(n: Int = 1) = repeat(n) {
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeByFrame()
    }

    private fun host() {
        rule.mainClock.autoAdvance = false
        val handler = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        rule.setContent {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                CompositionLocalProvider(LocalUriHandler provides handler, LocalAnswerClock provides { rule.mainClock.currentTime }) {
                    list = rememberLazyListState()
                    val now = ui
                    ChatScreen(
                        now, object : ChatActions {}, onOpenConnection = { connections++ }, onRetryLoad = { retries++ },
                        zone = ZoneOffset.UTC, today = FIXED_TODAY, listState = list, currentState = { now }, coveredNow = { covered },
                    )
                }
            }
        }
        frames(10)
    }

    private fun windows() = rule.onAllNodes(isDialog()).fetchSemanticsNodes().size

    private fun shown(m: SemanticsMatcher) = rule.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private val step = hasText("terminal") and hasClickAction()
    private val link = hasClickAction() and hasAnyAncestor(SemanticsMatcher("in r1's text") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("reply:r1/") == true })
    private val button = hasContentDescription("Scroll to the latest message")

    /** r1's Select: the lowest one on screen (the newest row of the reversed list). */
    private fun tapSelect() {
        val id = rule.onAllNodes(hasContentDescription("Select text") and hasClickAction()).fetchSemanticsNodes().maxBy { it.boundsInRoot.top }.id
        rule.onNode(SemanticsMatcher("node $id") { it.id == id }).performClick()
        frames(5)
    }

    private fun tap(m: SemanticsMatcher, unmerged: Boolean = false) {
        rule.onNode(m, useUnmergedTree = unmerged).performClick()
        frames(5)
    }

    private fun scrollAway(): Int {
        rule.onNode(hasScrollToIndexAction()).performTouchInput { // a drag away from the bottom: the button shows
            down(center)
            repeat(10) { moveBy(Offset(0f, 30f)) }
            advanceEventTime(200) // the finger rests before lifting: no fling
            up()
        }
        frames(10)
        assertTrue("the button is shown", shown(button))
        return list.firstVisibleItemScrollOffset
    }

    @Test
    fun theScreensLaunchersDoNothingWhileCoveredAndWorkOnceUncovered() {
        host()
        // Covered: nothing opens, scrolls or fires.
        covered = true
        tap(step)
        tapSelect()
        tap(link, unmerged = true)
        assertEquals("a window opened under the drawer", 0, windows())
        assertTrue(opened.isEmpty())
        val offset = scrollAway()
        tap(button)
        frames(60)
        assertEquals("the scroll button moved the list under the drawer", offset, list.firstVisibleItemScrollOffset)

        // A press that began uncovered and ends after the drawer came out fires nothing either.
        covered = false
        rule.onNode(step).performTouchInput { down(center) }
        frames(2)
        covered = true
        rule.onNode(step).performTouchInput { up() }
        frames(5)
        assertEquals("a held press opened the sheet under the drawer", 0, windows())

        // Uncovered: each works once.
        covered = false
        scrollAway()
        tap(button)
        frames(120)
        assertEquals(0, list.firstVisibleItemIndex)
        assertEquals(0, list.firstVisibleItemScrollOffset)
        tap(link, unmerged = true)
        assertEquals(listOf("https://example.com/docs"), opened)
        tapSelect()
        assertTrue("Select opened", shown(hasTestTag("select.piece:0")))
        tap(hasContentDescription("Back"))
        assertEquals(0, windows())
        tap(step)
        assertEquals("the tool sheet opened", 1, windows())
        assertTrue(shown(hasTestTag("sheet.result") or hasAnyAncestor(hasTestTag("sheet.result"))))
    }

    @Test
    fun aFailedLoadAndARejectedKeyFireNothingWhileCovered() {
        host()
        ui = ChatUiState("", "", StatusLine.LOAD_FAILED, emptyList(), ComposerMode.SEND_DISABLED, "")
        frames(5)
        val retry = hasText("Try again") and hasClickAction()
        val title = hasTestTag("chat.title")
        covered = true
        tap(retry)
        tap(title, unmerged = true)
        assertEquals(0, retries)
        covered = false
        tap(retry)
        assertEquals(1, retries)
        tap(title, unmerged = true)
        assertEquals(2, retries)

        ui = stored.copy(status = StatusLine.KEY_REJECTED)
        frames(5)
        covered = true
        tap(title, unmerged = true)
        assertEquals(0, connections)
        covered = false
        tap(title, unmerged = true)
        assertEquals(1, connections)
    }

    @Test
    fun aLongCommandsReaderOpensOnlyUncovered() {
        host()
        val live = LiveReply("run_1", "running", "Checking.", emptyList(), PendingControl("approval", "ctl_1", 1, "x".repeat(5000), null), Connection.LIVE)
        ui = ChatUiState("k", "Chat", StatusLine.WORKING, withLive(history, live) { FIXED_NOW }, ComposerMode.STOP, "", "hermes.example", live)
        frames(60) // revealed: the list moves to the request
        val full = hasText("Show full command") and hasClickAction()
        covered = true
        tap(full)
        assertEquals(0, windows())
        covered = false
        tap(full)
        assertTrue("the reader opened", shown(hasTestTag("select.piece:0")))
    }
}
