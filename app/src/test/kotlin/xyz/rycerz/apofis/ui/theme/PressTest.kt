package xyz.rycerz.apofis.ui.theme

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.input.InputModeManager
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.unit.dp
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

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class PressTest {
    @get:Rule
    val rule = createComposeRule()

    @Composable
    private fun Themed(content: @Composable () -> Unit) =
        ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false, content = content)

    @Test
    fun iconButtonHitAreaIs48() {
        rule.setContent { Themed { ApofisIconButton(onClick = {}, contentDescription = "Close", visualSize = 28.dp) { Box(Modifier.size(16.dp)) } } }
        val node = rule.onNodeWithContentDescription("Close")
        node.assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
        val bounds = node.fetchSemanticsNode().touchBoundsInRoot
        val min = with(rule.density) { 48.dp.toPx() }
        assertTrue("touch bounds $bounds", bounds.width >= min && bounds.height >= min)
    }

    @Test
    fun pressAndFocusAreVisible() {
        rule.mainClock.autoAdvance = false
        lateinit var inputMode: InputModeManager
        rule.setContent {
            Themed {
                inputMode = LocalInputModeManager.current
                val source = remember { MutableInteractionSource() }
                TextButton(onClick = {}, Modifier.testTag("button").wash(source, ButtonDefaults.textShape), interactionSource = source) { Text("Retry") }
            }
        }
        val button = rule.onNodeWithTag("button")
        val settle = Motion.WASH + 48L // the wash animation plus a few frames
        rule.mainClock.advanceTimeBy(settle)
        val idle = button.captureToImage()

        button.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(settle)
        val pressed = button.captureToImage()
        button.performTouchInput { up() }
        rule.mainClock.advanceTimeBy(settle)

        rule.runOnUiThread { inputMode.requestInputMode(InputMode.Keyboard) }
        button.requestFocus()
        rule.mainClock.advanceTimeBy(settle)
        val focused = button.captureToImage()

        assertFalse("pressed looks idle", same(idle, pressed))
        assertFalse("focused looks idle", same(idle, focused))
        assertFalse("focused looks pressed", same(pressed, focused))
        rule.onAllNodesWithTag("button", useUnmergedTree = true).assertCountEquals(1)
        assertEquals(1, rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    private fun same(a: ImageBitmap, b: ImageBitmap): Boolean {
        val x = a.toPixelMap()
        val y = b.toPixelMap()
        return x.width == y.width && x.height == y.height && x.buffer.contentEquals(y.buffer)
    }
}
