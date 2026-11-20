package xyz.rycerz.apofis.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import xyz.rycerz.apofis.settings.ThemeMode

private val LocalApofisColors = staticCompositionLocalOf { apofisColors(dark = false, page = LightPage) }

private val ApofisShapes = Shapes(
    small = RoundedCornerShape(10.dp), // attachment thumbnails
    medium = RoundedCornerShape(12.dp), // settings cards
    large = RoundedCornerShape(16.dp), // bubbles, cards, drawer rows
    extraLarge = RoundedCornerShape(20.dp), // composer, sheets
)

@Composable
@ReadOnlyComposable
fun isDarkTheme(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

/** System dynamic color (minSdk 31 always has it) or the static palette; [pureBlack] makes the page #000000 or #FFFFFF. */
@Composable
fun ApofisTheme(mode: ThemeMode, dynamicColor: Boolean, pureBlack: Boolean, content: @Composable () -> Unit) {
    val dark = isDarkTheme(mode)
    val pure = if (dark) Color.Black else Color.White
    val scheme = if (dynamicColor) {
        val context = LocalContext.current
        val base = if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        if (pureBlack) base.copy(background = pure, surface = pure) else base
    } else {
        staticScheme(dark, if (pureBlack) pure else if (dark) DarkPage else LightPage)
    }
    CompositionLocalProvider(LocalApofisColors provides apofisColors(dark, scheme.surface)) {
        MaterialTheme(colorScheme = scheme, typography = ApofisTypography, shapes = ApofisShapes, content = content)
    }
}

object ApofisTheme {
    val colors: ApofisColors
        @Composable @ReadOnlyComposable get() = LocalApofisColors.current
}
