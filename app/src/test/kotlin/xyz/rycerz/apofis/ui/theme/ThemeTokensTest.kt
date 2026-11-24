package xyz.rycerz.apofis.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
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
            // The composer card without blur: its glass fill over its own page backplate, whatever content lies under it.
            val card = c.glassFill.compositeOver(page)
            floor("composer text on its fill-only card", scheme.onSurface, card, 4.5)
            floor("composer placeholder on its fill-only card", c.muted, card, 4.5)
            floor("composer expand icon on its fill-only card", c.expandIcon, card, 3.0)
            floor("composer send fill and stop outline on its fill-only card", scheme.primary, card, 3.0)
            floor("send glyph on its fill", scheme.onPrimary, scheme.primary, 3.0)
            floor("warning on timelineFill", c.warning, c.timelineFill, 3.0) // the approval card
            floor("handle over the sheet", c.handle.compositeOver(page), page, 3.0)
            floor("error on timelineFill", scheme.error, c.timelineFill, 4.5) // a failed step's title
            floor("onSurfaceVariant on timelineFill", scheme.onSurfaceVariant, c.timelineFill, 4.5) // control card text
            floor("slider unfilled track (outline) on cardFill", scheme.outline, c.cardFill, 3.0) // the chat text size slider
            floor("clarify field idle border on timelineFill", c.timelineOutline, c.timelineFill, 3.0)
            floor("image placeholder glyph (muted) on thumbPlaceholder", c.muted, c.thumbPlaceholder, 3.0)
            floor("thumbChipGlyph on thumbChip", c.thumbChipGlyph, c.thumbChip, 3.0)
            floor("onViewer on viewerBackdrop", c.onViewer, c.viewerBackdrop, 4.5)
        }
        assertEquals(emptyList<String>(), failures)
    }

    /**
     * With blur on (the Settings switch), the glass shows blurred content of any color: every foreground on it keeps its
     * floor over black, white, the text color, primary and every grey between, and the glass stays translucent, in all 12
     * palettes. Selected text is not one of the floors (ruling): its worst case is pinned as a known limit instead.
     */
    @Test
    fun blurredGlassKeepsItsFloorsOverAnyContent() {
        val failures = mutableListOf<String>()
        val table = StringBuilder("theme | composer alpha | text | placeholder | expand | primary | button alpha | arrow | selected text | selected, blur off\n")
        var worstSelected = Double.MAX_VALUE to ""
        var worstSelectedOff = Double.MAX_VALUE
        for ((name, scheme, _) in themes) {
            val dark = scheme.surface.luminance() < 0.5f
            val contents = listOf(Color.Black, Color.White, scheme.onSurface, scheme.primary) + (0..255).map { Color(it, it, it) }
            fun worst(fg: Color, glass: Color, over: Color = Color.Transparent) =
                contents.minOf { content -> contrastRatio(fg, over.compositeOver(glass.compositeOver(content))) }
            val c = blurredColors(scheme, dark) ?: run { failures += "$name: no blur"; null } ?: continue
            val pairs = listOf(
                Triple("text", scheme.onSurface to c.glassFill, 4.5),
                Triple("placeholder", c.muted to c.glassFill, 4.5),
                Triple("expand icon", c.expandIcon to c.glassFill, 3.0),
                Triple("send fill and stop outline", scheme.primary to c.glassFill, 3.0),
                Triple("scroll arrow", scheme.onSurface to c.glassButtonFill, 3.0),
            )
            val ratios = pairs.map { (what, colors, min) ->
                val ratio = worst(colors.first, colors.second)
                if (ratio < min) failures += "$name: $what is %.2f:1 over some content, needs $min".format(ratio)
                ratio
            }
            for ((what, glass) in listOf("composer" to c.glassFill, "scroll button" to c.glassButtonFill)) {
                if (glass.alpha >= 1f) failures += "$name: the $what glass is opaque"
            }
            // Selected text: on Material's selection background (primary at 0.40) over the raised glass; and with blur off,
            // over the fill-only glass on its page backplate.
            val selection = scheme.primary.copy(alpha = 0.4f)
            val selected = worst(scheme.onSurface, c.glassFill, selection)
            val off = apofisColors(scheme, dark)
            val selectedOff = contrastRatio(scheme.onSurface, selection.compositeOver(off.glassFill.compositeOver(scheme.surface)))
            if (selected < worstSelected.first) worstSelected = selected to name
            worstSelectedOff = minOf(worstSelectedOff, selectedOff)
            table.append("$name | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f | %.2f\n".format(c.glassFill.alpha, ratios[0], ratios[1], ratios[2], ratios[3], c.glassButtonFill.alpha, ratios[4], selected, selectedOff))
        }
        table.append("worst selected text with blur: %.3f in %s; with blur off: %.3f\n".format(worstSelected.first, worstSelected.second, worstSelectedOff))
        println(table)
        // Known limit (ruling): with blur on, selected text can fall below 4.5:1 over bright content. Pinned, so worse fails.
        assertTrue("selected text with blur: ${worstSelected.first} in ${worstSelected.second}", worstSelected.first >= SELECTED_WITH_BLUR)
        assertEquals(emptyList<String>(), failures)
        // Without blur nothing changes: the glass keeps the UI map's alpha over its page backplate.
        for ((_, scheme, c) in themes) {
            val dark = scheme.surface.luminance() < 0.5f
            assertEquals(if (dark) 0.74f else 0.82f, c.glassFill.alpha, 0.002f)
            assertEquals(if (dark) 0.40f else 0.85f, c.glassButtonFill.alpha, 0.002f)
        }
    }

    /** The switch: its outline when off and its fill when on reach 3:1 on the card; the thumb when on reaches 3:1 on its track. */
    @Test
    fun switchReaches3to1OnTheCard() {
        val failures = mutableListOf<String>()
        for ((name, scheme, c) in themes) {
            fun floor(what: String, fg: Color, bg: Color) {
                val ratio = contrastRatio(fg, bg)
                if (ratio < 3.0) failures += "$name: $what is %.2f:1".format(ratio)
            }
            floor("off outline on the card", scheme.outline, c.cardFill)
            floor("on track on the card", scheme.primary, c.cardFill)
            floor("on thumb on its track", scheme.onPrimary, scheme.primary)
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

    private companion object {
        /** The measured worst case of selected text in the composer with frosted glass on, over any content (known limit). */
        const val SELECTED_WITH_BLUR = 3.729 // green dark, pure off
    }
}
