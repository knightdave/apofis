package xyz.rycerz.apofis.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.LayoutDirection
import org.junit.Assert.assertEquals
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
class PillSwitchTest {
    @get:Rule
    val rule = createComposeRule()

    private var checked by mutableStateOf(false)
    private var direction by mutableStateOf(LayoutDirection.Ltr)

    private fun assertNear(what: String, expected: Color, actual: Color) =
        listOf(expected.red to actual.red, expected.green to actual.green, expected.blue to actual.blue)
            .forEach { (e, a) -> assertEquals("$what: expected $expected, got $actual", e, a, 2f / 255f) }

    /** The thumb sits at the start when off and at the end when on, mirrored right to left; both states settle. */
    @Test
    fun thumbAtTheStartWhenOffAtTheEndWhenOn() {
        lateinit var colors: ApofisColors
        var primary = Color.Unspecified
        var onPrimary = Color.Unspecified
        var outline = Color.Unspecified
        rule.setContent {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                colors = ApofisTheme.colors
                primary = MaterialTheme.colorScheme.primary
                onPrimary = MaterialTheme.colorScheme.onPrimary
                outline = MaterialTheme.colorScheme.outline
                CompositionLocalProvider(LocalLayoutDirection provides direction) { PillSwitch(checked, Modifier.testTag("switch")) }
            }
        }
        // xhdpi: the 44 x 26 dp pill is 88 x 52 px; thumb centres 13 dp from either end.
        for (rtl in listOf(false, true)) {
            direction = if (rtl) LayoutDirection.Rtl else LayoutDirection.Ltr
            for (on in listOf(false, true)) {
                checked = on
                rule.waitForIdle()
                val pixels = rule.onNodeWithTag("switch").captureToImage().toPixelMap()
                assertEquals(88, pixels.width)
                assertEquals(52, pixels.height)
                val (thumbX, trackX) = if (on != rtl) 62 to 26 else 26 to 62
                val case = "rtl=$rtl on=$on"
                assertNear("$case thumb", if (on) onPrimary else colors.switchOffThumb, pixels[thumbX, 26])
                assertNear("$case track", if (on) primary else colors.switchOffTrack, pixels[trackX, 26])
                // The 1 dp border at the pill's far end: the field outline when off (3:1 on the card), primary when on.
                assertNear("$case border", if (on) primary else outline, pixels[if (on != rtl) 1 else 86, 26])
            }
        }
    }
}
