package xyz.rycerz.apofis.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import kotlin.math.max
import kotlin.math.min

/**
 * Tokens Material has no slot for. The UI map's alpha recipes are resolved here, once per theme, to opaque colors;
 * only the glass, scrim, press wash and handle tokens stay translucent, because they must show what is behind them.
 */
@Immutable
data class ApofisColors(
    val cardFill: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val success: Color,
    val warning: Color,
    val muted: Color, // the faintest text tone on the page
    val subtitle: Color, // app bar status line when idle
    val sectionTitle: Color,
    val selectedRow: Color,
    val bubbleFill: Color,
    val timelineFill: Color,
    val timelineStrong: Color,
    val timelineBody: Color,
    val timelineMuted: Color,
    val timelineConnector: Color,
    val codeFill: Color,
    val codeHeaderFill: Color,
    val codeLabel: Color,
    val inlineCodeFill: Color,
    val quoteBar: Color,
    val quoteText: Color,
    val tableBorder: Color,
    val tableDivider: Color,
    val tableHeaderFill: Color,
    val tableBody: Color,
    val actionIcon: Color,
    val expandIcon: Color, // non-text: 3:1
    val switchOffTrack: Color,
    val glassFill: Color,
    val glassBorder: Color,
    val glassButtonFill: Color,
    val glassButtonBorder: Color,
    val scrim: Color,
    val pressWash: Color,
    val handle: Color,
)

private val White = Color(0xFFFFFFFF)
private val Black = Color(0xFF000000)

internal val LightPage = Color(0xFFF7F7F7)
internal val DarkPage = Color(0xFF121213)

private fun Color.at(alpha: Float, over: Color) = copy(alpha = alpha).compositeOver(over)

/** The static palette of UI map section 1 over its neutral page; [deriveScheme] adds the ladder. */
internal fun staticScheme(dark: Boolean): ColorScheme = if (dark) {
    darkColorScheme(
        primary = Color(0xFFB6C4FF),
        onPrimary = Color(0xFF1D2D61),
        primaryContainer = Color(0xFF354479),
        onPrimaryContainer = Color(0xFFDCE1FF),
        secondary = Color(0xFFC2C5DD),
        secondaryContainer = Color(0xFF424659),
        tertiary = Color(0xFFE3BADA),
        tertiaryContainer = Color(0xFF5B3D57),
        error = Color(0xFFFCB4BD),
        errorContainer = Color(0xFF910034),
        surface = DarkPage,
        onSurface = Color(0xFFF9F9F9),
        onBackground = Color(0xFFF9F9F9),
        onSurfaceVariant = Color(0xFFCECECE),
    )
} else {
    lightColorScheme(
        primary = Color(0xFF4D5C92),
        onPrimary = White,
        primaryContainer = Color(0xFFDCE1FF),
        onPrimaryContainer = Color(0xFF03174B),
        secondary = Color(0xFF595D72),
        secondaryContainer = Color(0xFFDEE1F9),
        tertiary = Color(0xFF75546F),
        tertiaryContainer = Color(0xFFFFD7F6),
        error = Color(0xFFBB0947),
        errorContainer = Color(0xFFFDDADE),
        surface = LightPage,
        onSurface = Color(0xFF202020),
        onBackground = Color(0xFF202020),
        onSurfaceVariant = Color(0xFF646464),
    )
}

/** The page of a theme: the scheme's own surface, or #000000 / #FFFFFF with pure background. */
internal fun pageFor(base: ColorScheme, dark: Boolean, pureBlack: Boolean): Color = when {
    !pureBlack -> base.surface
    dark -> Black
    else -> White
}

/** Puts [page] under any scheme (static, system dynamic or an injected seed) and blends the UI map's surface ladder over it. */
internal fun deriveScheme(base: ColorScheme, dark: Boolean, page: Color): ColorScheme {
    val on = base.onSurface
    // Field borders need 3:1 against the page; the pure black or white hairline base never goes into the scheme.
    val outline = on.at(0.5f, page)
    return if (dark) {
        base.copy(
            background = page,
            surface = page,
            surfaceContainerLowest = page,
            surfaceContainerLow = White.at(0.03f, page),
            surfaceContainer = White.at(0.045f, page),
            surfaceContainerHigh = White.at(0.06f, page),
            surfaceContainerHighest = White.at(0.09f, page),
            outline = outline,
            outlineVariant = White.at(0.26f, page),
        )
    } else {
        base.copy(
            background = page,
            surface = page,
            surfaceContainerLowest = White.at(0.85f, page),
            surfaceContainerLow = White.at(0.72f, page),
            surfaceContainer = White.at(0.55f, page),
            surfaceContainerHigh = White.at(0.35f, page),
            surfaceContainerHighest = on.at(0.05f, page),
            outline = outline,
            outlineVariant = Black.at(0.38f, page),
        )
    }
}

/** Extra tokens over the page of a derived [scheme]. */
internal fun apofisColors(scheme: ColorScheme, dark: Boolean): ApofisColors {
    val page = scheme.surface
    val on = scheme.onSurface
    val muted = on.at(0.7f, page)
    val timelineFill = scheme.primaryContainer.at(if (dark) 0.25f else 0.30f, page)
    val timelineBody = scheme.secondary.at(if (dark) 0.7f else 0.9f, timelineFill)
    val codeHeaderFill = scheme.surfaceContainerHighest.at(0.8f, page)
    return ApofisColors(
        cardFill = White.at(if (dark) 0.10f else 0.96f, page),
        hairline = if (dark) White.at(0.08f, page) else Black.at(0.06f, page),
        hairlineStrong = if (dark) White.at(0.26f, page) else Black.at(0.38f, page),
        success = if (dark) Color(0xFF81C784) else Color(0xFF2E7D32),
        warning = if (dark) Color(0xFFFFB74D) else Color(0xFFF57C00),
        muted = muted,
        subtitle = muted,
        sectionTitle = on.at(0.8f, page),
        selectedRow = scheme.primary.at(0.12f, page),
        bubbleFill = scheme.primary.at(if (dark) 0.15f else 0.08f, page),
        timelineFill = timelineFill,
        timelineStrong = scheme.secondary.at(0.9f, timelineFill),
        timelineBody = timelineBody,
        timelineMuted = timelineBody,
        timelineConnector = scheme.secondary.at(0.35f, timelineFill),
        codeFill = scheme.surfaceContainer.at(0.8f, page),
        codeHeaderFill = codeHeaderFill,
        codeLabel = on.at(0.7f, codeHeaderFill),
        inlineCodeFill = if (dark) White.at(0.10f, page) else on.at(0.06f, page),
        quoteBar = on.at(if (dark) 0.20f else 0.14f, page),
        quoteText = on.at(0.72f, page),
        tableBorder = on.at(0.16f, page),
        tableDivider = on.at(0.10f, page),
        tableHeaderFill = on.at(0.05f, page),
        tableBody = on.at(0.88f, page),
        actionIcon = on.at(0.9f, page),
        expandIcon = on.at(if (dark) 0.45f else 0.49f, page), // light 0.49: the smallest alpha that reaches 3:1
        switchOffTrack = on.at(0.08f, page),
        glassFill = (if (dark) on.at(0.07f, page) else scheme.primary.at(0.02f, page)).copy(alpha = if (dark) 0.74f else 0.82f),
        glassBorder = if (dark) on.copy(alpha = 0.10f) else scheme.outline.copy(alpha = 0.20f),
        glassButtonFill = page.copy(alpha = if (dark) 0.40f else 0.85f),
        glassButtonBorder = if (dark) on.copy(alpha = 0.12f) else scheme.outline.copy(alpha = 0.20f),
        scrim = on.copy(alpha = 0.12f),
        pressWash = on.copy(alpha = if (dark) 0.14f else 0.12f),
        handle = on.copy(alpha = 0.2f),
    )
}

/** WCAG 2.x contrast ratio of two opaque colors. */
internal fun contrastRatio(foreground: Color, background: Color): Double {
    val a = foreground.luminance().toDouble()
    val b = background.luminance().toDouble()
    return (max(a, b) + 0.05) / (min(a, b) + 0.05)
}

/** [color] if it already reaches [min] on [background], else blended toward [toward] in 5 % steps until it does. */
internal fun readableOn(color: Color, background: Color, toward: Color, min: Double = 4.5): Color {
    for (step in 0..20) {
        val candidate = if (step == 0) color else lerp(color, toward, step * 0.05f)
        if (contrastRatio(candidate, background) >= min) return candidate
    }
    return toward
}
