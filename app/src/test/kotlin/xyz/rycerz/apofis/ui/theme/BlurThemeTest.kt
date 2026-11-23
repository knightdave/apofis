package xyz.rycerz.apofis.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.GreenSeed
import xyz.rycerz.apofis.ui.PurpleSeed

/** What [ApofisTheme] provides with the frosted glass switch on: raised glass with blur, or the blur-off tokens without. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class BlurThemeTest {
    @get:Rule
    val rule = createComposeRule()

    private data class Case(val mode: ThemeMode, val seed: SeedSchemes?, val pure: Boolean)
    private data class Seen(val colors: ApofisColors, val scheme: ColorScheme, val blur: Boolean)

    private var case by mutableStateOf(Case(ThemeMode.LIGHT, null, false))
    private var seen: Seen? = null

    private fun show() = rule.setContent {
        val c = case
        ApofisTheme(c.mode, dynamicColor = c.seed != null, pureBlack = c.pure, reduceMotion = true, blur = true, seed = c.seed) {
            seen = Seen(ApofisTheme.colors, MaterialTheme.colorScheme, ApofisTheme.blur)
        }
    }

    private fun at(c: Case): Seen {
        case = c
        rule.waitForIdle()
        return seen!!
    }

    /**
     * The fixed palettes keep blur, and with it the raised glass (never the blur-off fill with blur drawn), except purple
     * dark, where no translucent glass passes: there exactly the blur-off tokens and no blur.
     */
    @Test
    fun theFixedPalettesKeepBlurWithTheRaisedGlass() {
        show()
        for (seed in listOf(null, PurpleSeed, GreenSeed)) for (mode in listOf(ThemeMode.LIGHT, ThemeMode.DARK)) for (pure in listOf(false, true)) {
            val s = at(Case(mode, seed, pure))
            val name = "${if (seed == PurpleSeed) "purple" else if (seed == GreenSeed) "green" else "static"} $mode pure=$pure"
            val off = apofisColors(s.scheme, mode == ThemeMode.DARK)
            if (seed == PurpleSeed && mode == ThemeMode.DARK && !pure) {
                // No translucent glass keeps selected text at 4.5:1 here (ThemeTokensTest): the blur-off tokens, no blur.
                assertEquals(name, off, s.colors)
                assertEquals(name, false, s.blur)
                continue
            }
            assertTrue("$name: blur drawn", s.blur)
            assertEquals(name, blurredColors(s.scheme, mode == ThemeMode.DARK), s.colors)
            assertTrue(name, s.colors.glassFill.alpha > off.glassFill.alpha)
        }
    }

    /** The static light page and primary with a softer ink: the placeholder passes only on the opaque glass tint, or nowhere. */
    private fun softInk(ink: Long) = SeedSchemes(light = staticScheme(false).copy(onSurface = Color(ink)), dark = staticScheme(true))

    @Test
    fun aPaletteWhoseGlassPassesOnlyOpaqueGetsNoBlur() {
        val seed = softInk(0xFF363636)
        val s = run { show(); at(Case(ThemeMode.LIGHT, seed, false)) }
        val off = apofisColors(s.scheme, dark = false)
        // The case is the one named: the opaque tint keeps the placeholder at 4.5:1, no translucent fill does.
        assertTrue(contrastRatio(off.muted, off.glassFill.copy(alpha = 1f)) >= 4.5)
        assertNull(blurredColors(s.scheme, dark = false))
        assertEquals(off, s.colors)
        assertEquals(false, s.blur)
    }

    @Test
    fun aPaletteWhoseGlassNeverPassesGetsNoBlur() {
        val seed = softInk(0xFF383838)
        val s = run { show(); at(Case(ThemeMode.LIGHT, seed, false)) }
        val off = apofisColors(s.scheme, dark = false)
        assertTrue("not even opaque", contrastRatio(off.muted, off.glassFill.copy(alpha = 1f)) < 4.5)
        assertEquals(off, s.colors)
        assertEquals(false, s.blur)
    }
}
