package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import xyz.rycerz.apofis.chat.ToolStep
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.FIXED_TODAY
import xyz.rycerz.apofis.ui.theme.ApofisTheme

/** The tags and states the end-to-end suite (e2e/src) reads, on the unmerged tree as the accessibility service sees them. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class E2eTagsTest {
    @get:Rule
    val rule = createComposeRule()

    private fun count(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().size

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun stepTagsAndStates() {
        val steps = List(5) { ToolStep("c$it", "terminal", "ls -la /srv/$it", null, false, error = false, running = it == 4) }
        val items = Joined(emptyList(), listOf(AssistantReply("live", steps, "", 0.0, live = true)))
        rule.setContent {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                Box(Modifier.semantics { testTagsAsResourceId = true }) {
                    MessageList(
                        items, null, emptyMap(), answering = false, actions = object : ChatActions {}, onCancel = {}, onStep = {},
                        listState = rememberLazyListState(), contentPadding = PaddingValues(), zone = ZoneOffset.UTC, today = FIXED_TODAY,
                    )
                }
            }
        }
        assertEquals(1, count("step:live:3:terminal"))
        val running = rule.onAllNodes(hasTestTag("step:live:4:terminal"), useUnmergedTree = true).fetchSemanticsNodes().single()
        assertEquals("Running", running.config.getOrElseNullable(SemanticsProperties.StateDescription) { null })
        val ownTexts = rule.onAllNodes(hasText("ls -la /srv/4") and hasAnyAncestor(hasTestTag("step:live:4:terminal")), useUnmergedTree = true)
        assertEquals(1, ownTexts.fetchSemanticsNodes().size)
        assertTrue(rule.onAllNodes(hasText("ls -la /srv/3") and hasAnyAncestor(hasTestTag("step:live:3:terminal")), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty())
        assertEquals(0, count("step:live:0:terminal"))

        rule.onNodeWithText("Show 3 more steps").performClick()
        for (i in 0..4) assertEquals(1, count("step:live:$i:terminal"))
    }
}
