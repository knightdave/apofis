package xyz.rycerz.apofis.ui.markdown

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.theme.ApofisTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class MarkdownUiTest {
    @get:Rule
    val rule = createComposeRule()

    private val frames = 20 * 16L

    @Composable
    private fun Themed(content: @Composable () -> Unit) =
        ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false, content = content)

    private fun same(a: ImageBitmap, b: ImageBitmap) = a.toPixelMap().buffer.contentEquals(b.toPixelMap().buffer)

    @Test
    fun linksShowPressAndKeyboardFocus() {
        rule.mainClock.autoAdvance = false
        val opened = ArrayList<String>()
        lateinit var inputMode: InputModeManager
        val chunk = DisplayChunk.Blocks(parseMarkdown("before [first](https://a.b) and [second](https://c.d) after"))
        rule.setContent {
            Themed {
                inputMode = LocalInputModeManager.current
                MarkdownChunk(chunk, { opened += it }, Modifier.testTag("md"))
            }
        }
        rule.mainClock.advanceTimeBy(frames)
        val md = rule.onNodeWithTag("md")
        val idle = md.captureToImage()
        val links = rule.onAllNodes(hasClickAction(), useUnmergedTree = true)
        assertEquals(2, links.fetchSemanticsNodes().size)

        links[0].performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(frames)
        val pressed = md.captureToImage()
        links[0].performTouchInput { up() }
        rule.mainClock.advanceTimeBy(frames)
        assertEquals(listOf("https://a.b"), opened)
        assertTrue("released link still looks pressed", same(idle, md.captureToImage()))

        rule.runOnUiThread { inputMode.requestInputMode(InputMode.Keyboard) }
        links[0].requestFocus()
        rule.mainClock.advanceTimeBy(frames)
        val firstFocused = md.captureToImage()
        links[1].requestFocus()
        rule.mainClock.advanceTimeBy(frames)
        val secondFocused = md.captureToImage()

        assertFalse("pressed link looks idle", same(idle, pressed))
        assertFalse("focused link looks idle", same(idle, firstFocused))
        assertFalse("which link has focus is not visible", same(firstFocused, secondFocused))
        assertEquals(listOf("https://a.b"), opened) // focus alone opens nothing
    }

    @Test
    fun taskItemsExposeReadOnlyCheckedState() {
        val chunk = DisplayChunk.Blocks(parseMarkdown("- [ ] open\n- [x] done"))
        rule.setContent { Themed { MarkdownChunk(chunk, {}) } }
        fun state(value: ToggleableState) = SemanticsMatcher.expectValue(SemanticsProperties.ToggleableState, value)
        assertEquals(1, rule.onAllNodes(state(ToggleableState.Off) and hasText("open")).fetchSemanticsNodes().size)
        assertEquals(1, rule.onAllNodes(state(ToggleableState.On) and hasText("done")).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().size)
    }
}
