package xyz.rycerz.apofis.ui.chat

import android.content.Context
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.theme.ApofisTheme

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class ChatLinkTest {
    @get:Rule
    val rule = createComposeRule()

    private class Recording : UriHandler {
        var fail = false
        val opened = ArrayList<String>()
        override fun openUri(uri: String) {
            if (fail) throw IllegalArgumentException("no activity for $uri")
            opened += uri
        }
    }

    @Test
    fun aTappedLinkOpensNormalizedAndAFailureShowsTheNoteUntilSuccessOrSend() {
        val handler = Recording()
        val reply = AssistantReply("r1", emptyList(), "[open](HTTPS://Example.com/Path)", 0.0)
        val state = ChatUiState("k", "Chat", StatusLine.IDLE, listOf(reply), ComposerMode.SEND, "draft")
        rule.setContent {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                CompositionLocalProvider(LocalUriHandler provides handler) {
                    ChatScreen(state, object : ChatActions {}, onOpenConnection = {})
                }
            }
        }
        val note = ApplicationProvider.getApplicationContext<Context>().getString(R.string.settings_link_no_app)
        fun noteShown() = rule.onAllNodes(hasText(note)).fetchSemanticsNodes().size
        val link = rule.onNode(hasClickAction() and hasAnyAncestor(hasTestTag("reply:r1/b0/c0")), useUnmergedTree = true)
        assertEquals(0, handler.opened.size) // nothing opens without a tap

        link.performClick()
        assertEquals(listOf("https://Example.com/Path"), handler.opened)
        assertEquals(0, noteShown())

        handler.fail = true
        link.performClick()
        assertEquals(1, noteShown())
        handler.fail = false
        link.performClick()
        assertEquals(0, noteShown()) // a link that opens clears it

        handler.fail = true
        link.performClick()
        assertEquals(1, noteShown())
        rule.onNodeWithTag("composer.send").performClick()
        assertEquals(0, noteShown()) // so does a send
    }
}
