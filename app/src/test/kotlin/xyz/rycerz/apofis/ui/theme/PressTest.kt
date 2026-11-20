package xyz.rycerz.apofis.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
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
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.SemanticsNodeInteraction
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
import androidx.compose.material3.MaterialTheme
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.connect.ConnectScreen
import xyz.rycerz.apofis.ui.connect.ConnectViewModel

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class PressTest {
    @get:Rule
    val rule = createComposeRule()

    private val frame = 16L
    private val settle = (Motion.WASH / frame + 3) * frame // the wash animation plus three frames, in whole frames

    @Composable
    private fun Themed(mode: ThemeMode = ThemeMode.LIGHT, content: @Composable () -> Unit) =
        ApofisTheme(mode, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false, content = content)

    private fun focusWithKeyboard(node: SemanticsNodeInteraction, inputMode: () -> InputModeManager) {
        rule.runOnUiThread { inputMode().requestInputMode(InputMode.Keyboard) }
        node.requestFocus()
        rule.mainClock.advanceTimeBy(settle)
    }

    @Test
    fun iconButtonHitAreaIs48() {
        rule.mainClock.autoAdvance = false
        rule.setContent { Themed { ApofisIconButton(onClick = {}, contentDescription = "Close", visualSize = 28.dp) { Box(Modifier.size(16.dp)) } } }
        rule.mainClock.advanceTimeByFrame()
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
        rule.mainClock.advanceTimeBy(settle)
        val idle = button.captureToImage()

        button.performTouchInput { down(center) }
        rule.mainClock.advanceTimeBy(settle)
        val pressed = button.captureToImage()
        button.performTouchInput { up() }
        rule.mainClock.advanceTimeBy(settle)

        focusWithKeyboard(button) { inputMode }
        val focused = button.captureToImage()

        assertFalse("pressed looks idle", same(idle, pressed))
        assertFalse("focused looks idle", same(idle, focused))
        assertFalse("focused looks pressed", same(pressed, focused))
        rule.onAllNodesWithTag("button", useUnmergedTree = true).assertCountEquals(1)
        assertEquals(1, rule.onAllNodes(hasClickAction(), useUnmergedTree = true).fetchSemanticsNodes().size)
    }

    @Test
    fun filledButtonFocusRingContrastsLight() = filledButtonFocusRingContrasts(ThemeMode.LIGHT)

    @Test
    fun filledButtonFocusRingContrastsDark() = filledButtonFocusRingContrasts(ThemeMode.DARK)

    /** The production Connect button: enough ring pixels must stand out 3:1 from the idle button under them. */
    private fun filledButtonFocusRingContrasts(mode: ThemeMode) {
        rule.mainClock.autoAdvance = false
        lateinit var inputMode: InputModeManager
        val vm = ConnectViewModel(current = null, willClearCache = { false }, save = {})
        rule.setContent {
            Themed(mode) {
                inputMode = LocalInputModeManager.current
                ConnectScreen(vm, onBack = null)
            }
        }
        rule.mainClock.advanceTimeBy(settle)
        val button = rule.onNode(hasText("Connect") and hasClickAction())
        val idle = button.captureToImage().toPixelMap()
        focusWithKeyboard(button) { inputMode }
        val focused = button.captureToImage().toPixelMap()
        var strong = 0
        for (x in 0 until idle.width) for (y in 0 until idle.height) {
            if (contrastRatio(focused[x, y], idle[x, y]) >= 3.0) strong++
        }
        assertTrue("$mode: only $strong ring pixels reach 3:1", strong >= 500)
    }

    @Test
    fun releaseInTheSameFrameAsPressLeavesNoWash() {
        rule.mainClock.autoAdvance = false
        val source = MutableInteractionSource()
        rule.setContent {
            Themed { Box(Modifier.size(100.dp).testTag("row").background(MaterialTheme.colorScheme.surface).indication(source, WashIndication)) }
        }
        rule.mainClock.advanceTimeBy(settle)
        val row = rule.onNodeWithTag("row")
        val idle = row.captureToImage()
        for (end in listOf<(PressInteraction.Press) -> PressInteraction>({ PressInteraction.Release(it) }, { PressInteraction.Cancel(it) })) {
            rule.runOnUiThread {
                val press = PressInteraction.Press(Offset.Zero)
                source.tryEmit(press)
                source.tryEmit(end(press)) // no frame in between
            }
            rule.mainClock.advanceTimeBy(settle)
            assertTrue("a press that ended in the same frame left a wash", same(idle, row.captureToImage()))
        }
        rule.runOnUiThread { source.tryEmit(PressInteraction.Press(Offset.Zero)) }
        rule.mainClock.advanceTimeBy(settle)
        assertFalse("a held press shows no wash", same(idle, row.captureToImage()))
    }

    private fun same(a: ImageBitmap, b: ImageBitmap): Boolean {
        val x = a.toPixelMap()
        val y = b.toPixelMap()
        return x.width == y.width && x.height == y.height && x.buffer.contentEquals(y.buffer)
    }
}
