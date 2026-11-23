package xyz.rycerz.apofis.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.clippedTexts
import xyz.rycerz.apofis.chat.ComposeError
import xyz.rycerz.apofis.chat.SendState
import xyz.rycerz.apofis.chat.UnsentText
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.ui.chat.ChatActions
import xyz.rycerz.apofis.ui.chat.ChatScreen
import xyz.rycerz.apofis.ui.chat.ChatUiState
import xyz.rycerz.apofis.ui.chat.ComposerMode
import xyz.rycerz.apofis.ui.chat.StatusLine
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class DialogTest {
    @get:Rule
    val rule = createComposeRule()

    private var destructive by mutableStateOf(true)
    private var enabled by mutableStateOf(true)
    private val calls = mutableListOf<Int>()
    private val events = mutableListOf<String>()
    private var error = Color.Unspecified

    private fun show(text: String = "Body text") = rule.setContent {
        ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
            error = MaterialTheme.colorScheme.error
            CompositionLocalProvider(LocalHaptics provides ViewHaptics({ calls += it; true }, { true })) {
                ApofisDialog("Delete chat?", "Delete", { events += "confirm" }, "Cancel", { events += "dismiss" }, destructive, enabled) { Text(text) }
            }
        }
    }

    private fun labelColor(label: String): Color {
        val node = rule.onNode(hasText(label) and hasAnyAncestor(isDialog()) and androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsActions.GetTextLayoutResult), useUnmergedTree = true).fetchSemanticsNode()
        val results = ArrayList<TextLayoutResult>()
        node.config[SemanticsActions.GetTextLayoutResult].action!!(results)
        return results.single().layoutInput.style.color
    }

    @Test
    fun buttonsAnswerWithoutFeedbackAndDestructiveIsInError() {
        show()
        assertEquals(error, labelColor("Delete"))
        assertNotEquals(error, labelColor("Cancel"))
        rule.onNode(hasText("Delete") and hasClickAction()).assertHeightIsAtLeast(48.dp).performClick()
        rule.onNode(hasText("Cancel") and hasClickAction()).assertHeightIsAtLeast(48.dp).performClick()
        assertEquals(listOf("confirm", "dismiss"), events)
        assertEquals("a dialog answer is not a gesture of its own", emptyList<Int>(), calls)
    }

    @Test
    fun aDisabledConfirmIsNotInErrorAndDoesNothing() {
        enabled = false
        show()
        rule.onNode(hasText("Delete") and hasClickAction()).assertIsNotEnabled().performClick()
        assertEquals(emptyList<String>(), events)
        assertNotEquals(error, labelColor("Delete"))
        enabled = true
        rule.waitForIdle()
        rule.onNode(hasText("Delete") and hasClickAction()).assertIsEnabled()
        assertEquals(error, labelColor("Delete"))
        destructive = false
        rule.waitForIdle()
        assertNotEquals(error, labelColor("Delete"))
    }

    /** At double size in a narrow window a long text scrolls inside the dialog; it is never cut and the buttons stay. */
    @Test
    @Config(qualifiers = "pl-w320dp-h480dp-xhdpi", fontScale = 2f)
    fun longPolishTextScrollsAndIsNotClipped() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        show(listOf(R.string.chat_cancel_text_attempted, R.string.drawer_delete_text, R.string.drawer_delete_running, R.string.server_change_text).joinToString("\n\n") { context.getString(it) })
        rule.waitForIdle()
        assertEquals(emptyList<String>(), rule.clippedTexts())
        rule.onNode(hasText("Delete") and hasClickAction()).assertIsDisplayed()
        rule.onNode(hasText("Cancel") and hasClickAction()).assertIsDisplayed()
        rule.onNode(androidx.compose.ui.test.SemanticsMatcher.keyIsDefined(SemanticsActions.ScrollBy) and androidx.compose.ui.test.hasAnyDescendant(hasText("Ta wiadomość", substring = true)))
            .assertExists()
    }

    /**
     * The chat's three confirmations: removing a message or discarding text is destructive, a retry is not; their six
     * answers stay direct (exempt from the drawer gate), so they act even while the drawer covers the chat.
     */
    @Test
    fun chatConfirmationsAreDestructiveWhereTheyRemoveAndAnswerWhileCovered() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        fun str(id: Int) = context.getString(id)
        val queued = UserMessage("u1", "Queued text", steered = false, timestamp = 0.0, state = SendState.QUEUED, idempotencyKey = "k1")
        val failed = UserMessage("u2", "Failed text", steered = false, timestamp = 0.0, state = SendState.FAILED, idempotencyKey = "k2")
        val unsent = UnsentText(7, "Unsent text", ComposeError.NOT_STORED)
        val base = ChatUiState("s", "Chat", StatusLine.IDLE, listOf(queued, failed), ComposerMode.SEND, "", unsent = unsent)
        var ui by mutableStateOf(base.copy(confirmCancel = "k1"))
        val actions = object : ChatActions {
            override fun onConfirmCancel(idempotencyKey: String, mayHaveSentShown: Boolean) { events += "cancel $idempotencyKey" }
            override fun onDismissCancel(idempotencyKey: String) { events += "keep $idempotencyKey" }
            override fun onConfirmDiscard(id: Long) { events += "discard $id" }
            override fun onDismissDiscard() { events += "keep unsent" }
            override fun onConfirmRetry(idempotencyKey: String) { events += "retry $idempotencyKey" }
            override fun onDismissRetry(idempotencyKey: String) { events += "no retry $idempotencyKey" }
        }
        rule.setContent {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                error = MaterialTheme.colorScheme.error
                ChatScreen(ui, actions, onOpenConnection = {}, covered = true, coveredNow = { true })
            }
        }
        fun answer(label: Int) = rule.onNode(hasText(str(label)) and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        assertEquals(error, labelColor(str(R.string.chat_cancel_confirm)))
        answer(R.string.chat_cancel_confirm)
        answer(R.string.chat_keep)
        ui = base.copy(confirmDiscard = unsent)
        rule.waitForIdle()
        assertEquals(error, labelColor(str(R.string.chat_unsent_discard)))
        answer(R.string.chat_unsent_discard)
        answer(R.string.chat_keep)
        ui = base.copy(confirmRetry = failed)
        rule.waitForIdle()
        assertNotEquals(error, labelColor(str(R.string.chat_retry_confirm)))
        answer(R.string.chat_retry_confirm)
        answer(R.string.cancel)
        assertEquals(listOf("cancel k1", "keep k1", "discard 7", "keep unsent", "retry k2", "no retry k2"), events)
    }
}
