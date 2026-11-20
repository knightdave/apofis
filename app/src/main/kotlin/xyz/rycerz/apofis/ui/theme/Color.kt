package xyz.rycerz.apofis.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver

/** Tokens Material has no slot for. All opaque: the UI map's alpha recipes are resolved here, once. */
@Immutable
data class ApofisColors(
    val cardFill: Color,
    val hairline: Color,
    val hairlineStrong: Color,
    val success: Color,
    val warning: Color,
)

private val White = Color(0xFFFFFFFF)
private val Black = Color(0xFF000000)

internal val LightPage = Color(0xFFF7F7F7)
internal val DarkPage = Color(0xFF121213)

private fun Color.at(alpha: Float, over: Color) = copy(alpha = alpha).compositeOver(over)

/** The static palette of UI map section 1, with the surface ladder blended over [page]. */
internal fun staticScheme(dark: Boolean, page: Color): ColorScheme = if (dark) {
    val onSurface = Color(0xFFF9F9F9)
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
        background = page,
        onBackground = onSurface,
        surface = page,
        onSurface = onSurface,
        onSurfaceVariant = Color(0xFFCECECE),
        surfaceContainerLowest = page,
        surfaceContainerLow = White.at(0.03f, page),
        surfaceContainer = White.at(0.045f, page),
        surfaceContainerHigh = White.at(0.06f, page),
        surfaceContainerHighest = White.at(0.09f, page),
        // Field borders need 3:1 against the page; the pure white hairline base never goes into the scheme.
        outline = onSurface.at(0.5f, page),
        outlineVariant = White.at(0.26f, page),
    )
} else {
    val onSurface = Color(0xFF202020)
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
        background = page,
        onBackground = onSurface,
        surface = page,
        onSurface = onSurface,
        onSurfaceVariant = Color(0xFF646464),
        surfaceContainerLowest = White.at(0.85f, page),
        surfaceContainerLow = White.at(0.72f, page),
        surfaceContainer = White.at(0.55f, page),
        surfaceContainerHigh = White.at(0.35f, page),
        surfaceContainerHighest = onSurface.at(0.05f, page),
        outline = onSurface.at(0.5f, page),
        outlineVariant = Black.at(0.38f, page),
    )
}

/** Extra tokens over the page of any scheme, static or dynamic. */
internal fun apofisColors(dark: Boolean, page: Color) = if (dark) {
    ApofisColors(
        cardFill = White.at(0.10f, page),
        hairline = White.at(0.08f, page),
        hairlineStrong = White.at(0.26f, page),
        success = Color(0xFF81C784),
        warning = Color(0xFFFFB74D),
    )
} else {
    ApofisColors(
        cardFill = White.at(0.96f, page),
        hairline = Black.at(0.06f, page),
        hairlineStrong = Black.at(0.38f, page),
        success = Color(0xFF2E7D32),
        warning = Color(0xFFF57C00),
    )
}
