package xyz.rycerz.apofis.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import xyz.rycerz.apofis.ui.GreenSeed
import xyz.rycerz.apofis.ui.PurpleSeed

class ThemeTokensTest {
    private data class Theme(val name: String, val scheme: ColorScheme, val colors: ApofisColors)

    private fun theme(name: String, base: ColorScheme, dark: Boolean, pure: Boolean): Theme {
        val scheme = deriveScheme(base, dark, pageFor(base, dark, pure))
        return Theme("$name dark=$dark pure=$pure", scheme, apofisColors(scheme, dark))
    }

    /** Static and both seeds, light and dark, pure background off and on. */
    private val themes = listOf(null to "static", PurpleSeed to "purple", GreenSeed to "green").flatMap { (seed, name) ->
        listOf(false, true).flatMap { dark ->
            val base = seed?.let { if (dark) it.dark else it.light } ?: staticScheme(dark)
            listOf(false, true).map { pure -> theme(name, base, dark, pure) }
        }
    }

    private fun assertClose(expected: Color, actual: Color) {
        val e = listOf(expected.red, expected.green, expected.blue, expected.alpha)
        val a = listOf(actual.red, actual.green, actual.blue, actual.alpha)
        e.zip(a).forEach { (x, y) -> assertTrue("expected $expected, got $actual", abs(x - y) <= 1f / 255f + 1e-6f) }
    }

    private fun over(color: Color, alpha: Float, page: Color) = color.copy(alpha = alpha).compositeOver(page)

    @Test
    fun ladderOverDynamicPage() {
        val light = PurpleSeed.light
        val derivedLight = deriveScheme(light, dark = false, page = light.surface)
        assertEquals(light.surface, derivedLight.surface)
        assertEquals(light.surface, derivedLight.background)
        assertClose(over(light.onSurface, 0.05f, light.surface), derivedLight.surfaceContainerHighest)

        val dark = PurpleSeed.dark
        val derivedDark = deriveScheme(dark, dark = true, page = dark.surface)
        assertEquals(dark.surface, derivedDark.surface)
        assertClose(over(Color.White, 0.09f, dark.surface), derivedDark.surfaceContainerHighest)

        assertEquals(Color.White, pageFor(light, dark = false, pureBlack = true))
        assertEquals(Color.Black, pageFor(dark, dark = true, pureBlack = true))
        assertEquals(light.surface, pageFor(light, dark = false, pureBlack = false))
        val pureLight = deriveScheme(light, dark = false, page = Color.White)
        assertEquals(Color.White, pureLight.surface)
        assertClose(over(light.onSurface, 0.05f, Color.White), pureLight.surfaceContainerHighest)
        val pureDark = deriveScheme(dark, dark = true, page = Color.Black)
        assertEquals(Color.Black, pureDark.surface)
        assertEquals(Color.Black, pureDark.surfaceContainerLowest)
        assertClose(over(Color.White, 0.09f, Color.Black), pureDark.surfaceContainerHighest)
    }

    @Test
    fun contrastFloorsHoldInEveryTheme() {
        assertEquals(12, themes.size)
        val failures = mutableListOf<String>()
        for ((name, scheme, c) in themes) {
            fun floor(token: String, fg: Color, bg: Color, min: Double) {
                val ratio = contrastRatio(fg, bg)
                if (ratio < min) failures += "$name: $token is %.2f:1, needs $min".format(ratio)
            }
            val page = scheme.surface
            floor("muted", c.muted, page, 4.5)
            floor("sectionTitle", c.sectionTitle, page, 4.5)
            floor("quoteText", c.quoteText, page, 4.5)
            floor("tableBody", c.tableBody, page, 4.5)
            floor("primary", scheme.primary, page, 4.5)
            floor("onSurface on bubbleFill", scheme.onSurface, c.bubbleFill, 4.5)
            floor("onSurface on selectedRow", scheme.onSurface, c.selectedRow, 4.5)
            floor("timelineStrong", c.timelineStrong, c.timelineFill, 4.5)
            floor("timelineBody", c.timelineBody, c.timelineFill, 4.5)
            floor("timelineMuted", c.timelineMuted, c.timelineFill, 4.5)
            floor("codeLabel", c.codeLabel, c.codeHeaderFill, 4.5)
            floor("expandIcon", c.expandIcon, page, 3.0)
            floor("actionIcon", c.actionIcon, page, 3.0)
            floor("warning", c.warning, page, 3.0)
            floor("warning on cardFill", c.warning, c.cardFill, 3.0)
            // The scroll button without blur: its glass fill over its own page backplate, whatever content lies under it.
            floor("scroll arrow on its fill-only button", scheme.onSurface, c.glassButtonFill.compositeOver(page), 3.0)
            floor("warning on timelineFill", c.warning, c.timelineFill, 3.0) // the approval card
            floor("handle over the sheet", c.handle.compositeOver(page), page, 3.0)
            floor("error on timelineFill", scheme.error, c.timelineFill, 4.5) // a failed step's title
            floor("onSurfaceVariant on timelineFill", scheme.onSurfaceVariant, c.timelineFill, 4.5) // control card text
        }
        assertEquals(emptyList<String>(), failures)
    }

    /** The wave's brightest phase (reply dots on the page, mini dots on the timeline card) reaches 3:1; dimmer phases are decorative. */
    @Test
    fun theWavePeakReaches3to1() {
        val peak = (0..1100).maxOf { dotWave(it.toLong(), 0).alpha }
        val failures = mutableListOf<String>()
        for ((name, scheme, c) in themes) {
            for ((where, bg) in listOf("page" to scheme.surface, "timelineFill" to c.timelineFill)) {
                val ratio = contrastRatio(scheme.primary.copy(alpha = peak).compositeOver(bg), bg)
                if (ratio < 3.0) failures += "$name on $where: %.2f:1".format(ratio)
            }
        }
        assertEquals(emptyList<String>(), failures)
    }

    @Test
    fun noOpaqueBlackOrWhiteInScheme() {
        for ((name, scheme) in themes) {
            for (color in listOf(scheme.outline, scheme.outlineVariant)) {
                assertNotEquals(name, Color.Black, color)
                assertNotEquals(name, Color.White, color)
            }
        }
    }

    @Test
    fun onlyAllowlistedTokensAreTranslucent() {
        val translucent = setOf("glassFill", "glassBorder", "glassButtonFill", "glassButtonBorder", "scrim", "pressWash", "handle")
        // Color is a value class, so every token is a long field named after its property.
        val fields = ApofisColors::class.java.declaredFields.filter { it.type == Long::class.javaPrimitiveType }
        assertTrue(fields.map { it.name }.containsAll(translucent + "muted" + "cardFill"))
        for ((name, _, colors) in themes) {
            for (field in fields) {
                field.isAccessible = true
                val color = Color(field.getLong(colors).toULong())
                if (field.name in translucent) {
                    assertTrue("$name: ${field.name} should be translucent", color.alpha < 1f)
                } else {
                    assertEquals("$name: ${field.name} should be opaque", 1f, color.alpha)
                }
            }
        }
    }

    @Test
    fun readableOnBlendsUntilItPasses() {
        val darkOnSurface = Color(0xFFF9F9F9)
        val darkCodeFill = theme("static", staticScheme(true), dark = true, pure = false).colors.codeFill
        val grey = Color(0xFF5C6370)
        val shifted = readableOn(grey, darkCodeFill, toward = darkOnSurface)
        assertTrue(contrastRatio(shifted, darkCodeFill) >= 4.5)
        assertNotEquals(grey, shifted)
        assertEquals(darkOnSurface, readableOn(darkOnSurface, darkCodeFill, toward = Color.White))
    }
}
