package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
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
import xyz.rycerz.apofis.chat.SendState
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.FIXED_NOW
import xyz.rycerz.apofis.ui.FIXED_TODAY
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.LocalBlur

/** I13: every input the remembered slots use can be replaced on the same screen, and the new one is the one used. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class ChromeInputsTest {
    @get:Rule
    val rule = createComposeRule()

    private val items: List<ChatItem> = (1..30).flatMap {
        listOf(
            UserMessage("u$it", "Question $it", steered = false, timestamp = FIXED_NOW),
            AssistantReply("r$it", emptyList(), "Answer $it, long enough to wrap onto a second line of the chat screen.", FIXED_NOW),
        )
    }

    private fun chat(items: List<ChatItem> = this.items) = ChatUiState("apofis-1", "Inputs", StatusLine.IDLE, items, ComposerMode.SEND_DISABLED, "", "hermes.example")

    private var ui by mutableStateOf(chat())
    private var list by mutableStateOf(LazyListState())
    private var actions by mutableStateOf<ChatActions>(object : ChatActions {})
    private var blur by mutableStateOf(false)
    private var uri by mutableStateOf<UriHandler>(object : UriHandler {
        override fun openUri(uri: String) = Unit
    })
    private var currentState by mutableStateOf<(() -> ChatUiState)?>(null)

    private class FakeClipboard : Clipboard {
        val writes = ArrayList<String?>()
        override suspend fun getClipEntry(): ClipEntry? = null
        override suspend fun setClipEntry(clipEntry: ClipEntry?) {
            writes += clipEntry?.clipData?.getItemAt(0)?.text?.toString()
        }
        override val nativeClipboard get() = throw UnsupportedOperationException()
    }

    private val clipboard = FakeClipboard()

    private fun show() {
        rule.setContent {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                CompositionLocalProvider(LocalBlur provides blur, LocalUriHandler provides uri, LocalClipboard provides clipboard) {
                    ChatScreen(ui, actions, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY, listState = list, currentState = currentState)
                }
            }
        }
        rule.waitForIdle()
    }

    @Test
    fun replacedListStateDrivesTheList() {
        show()
        val a = list
        assertTrue(a.layoutInfo.totalItemsCount > 0)
        val b = LazyListState(firstVisibleItemIndex = 10)
        list = b
        rule.waitForIdle()
        assertTrue("the rendered list does not use the new state", b.layoutInfo.visibleItemsInfo.isNotEmpty())
        assertEquals(10, b.firstVisibleItemIndex)
        rule.runOnIdle { runBlocking { b.scrollToItem(20) } }
        rule.waitForIdle()
        assertEquals("the new state's scroll did not move the rendered rows", 20, b.firstVisibleItemIndex)
        assertTrue(b.layoutInfo.visibleItemsInfo.any { it.index == 20 })
    }

    @Test
    fun replacedListStateBringsItsFollowerAndButton() {
        show()
        list = LazyListState()
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        rule.onNode(hasScrollToIndexAction()).performTouchInput { swipeDown(startY = top + height / 4, endY = bottom - height / 4) }
        repeat(10) { rule.mainClock.advanceTimeByFrame() }
        assertTrue("the swipe scrolled the new list", list.canScrollBackward)
        // The button shows through the follower of the list on screen: the new one.
        assertEquals(1, rule.onAllNodes(androidx.compose.ui.test.hasContentDescription("Scroll to the latest message")).fetchSemanticsNodes().size)
    }

    /**
     * The blur setting changes the backdrop; the transcript (its recording source) and the button (its glass) use the new
     * one, on and off again. Checked by identity: drawing blur in Robolectric can leave later captures blank in the JVM.
     */
    @Test
    fun blurSwitchReachesTheBackdrop() {
        show()
        val off = ChromeProbe.bodyBackdropId
        assertTrue(!ChromeProbe.bodyBackdropHasLayer)
        blur = true
        rule.waitForIdle()
        val on = ChromeProbe.bodyBackdropId
        assertTrue("the transcript still uses the backdrop without a layer", on != off && ChromeProbe.bodyBackdropHasLayer)
        blur = false
        rule.waitForIdle()
        assertTrue("the transcript keeps the recording backdrop after blur was switched off", ChromeProbe.bodyBackdropId != on && !ChromeProbe.bodyBackdropHasLayer)
    }

    @Test
    fun replacedActionsReceiveTheTaps() {
        ui = chat(listOf(UserMessage("u1", "Failed one", false, FIXED_NOW, SendState.FAILED, "k1", error = "unconfirmed", attempted = true)))
        show()
        val got = ArrayList<String>()
        actions = object : ChatActions {
            override fun onRetry(idempotencyKey: String) {
                got += idempotencyKey
            }
        }
        rule.waitForIdle()
        rule.onNodeWithText("Retry").performClick()
        rule.waitForIdle()
        assertEquals(listOf("k1"), got)
    }

    @Test
    fun replacedUriHandlerOpensTheLinks() {
        ui = chat(listOf(AssistantReply("r1", emptyList(), "[docs](https://example.com/docs)", FIXED_NOW)))
        show()
        val opened = ArrayList<String>()
        uri = object : UriHandler {
            override fun openUri(uri: String) {
                opened += uri
            }
        }
        rule.waitForIdle()
        rule.onNode(androidx.compose.ui.test.hasClickAction() and androidx.compose.ui.test.hasAnyAncestor(androidx.compose.ui.test.hasTestTag("reply:r1/b0/c0")), useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(listOf("https://example.com/docs"), opened)
    }

    @Test
    fun replacedItemsAreShown() {
        show()
        ui = chat(listOf(UserMessage("n1", "A brand new transcript", false, FIXED_NOW)))
        rule.waitForIdle()
        rule.onNodeWithText("A brand new transcript").assertExists()
    }

    /**
     * Task 5's current-state reader is an input too: Copy checks the drawn text against the reader's transcript at tap
     * time and writes only when they agree. The first reader disagrees (Copy would skip); the replacement agrees.
     */
    @Test
    fun replacedCurrentStateIsReadByCopy() {
        ui = chat(listOf(AssistantReply("r1", emptyList(), "Hello", FIXED_NOW)))
        currentState = { chat(listOf(AssistantReply("r1", emptyList(), "Hello, since edited", FIXED_NOW))) }
        show()
        currentState = { chat(listOf(AssistantReply("r1", emptyList(), "Hello", FIXED_NOW))) }
        rule.waitForIdle()
        rule.onNodeWithContentDescription("Copy").performClick()
        rule.waitForIdle()
        assertEquals("Copy did not read the replacement reader", listOf<String?>("Hello"), clipboard.writes)
    }
}
