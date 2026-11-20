package xyz.rycerz.apofis.ui.theme

import android.provider.Settings
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import xyz.rycerz.apofis.settings.ThemeMode

internal val LocalApofisColors = staticCompositionLocalOf { apofisColors(deriveScheme(staticScheme(false), false, LightPage), dark = false) }

val LocalHaptics: ProvidableCompositionLocal<Haptics> = staticCompositionLocalOf {
    object : Haptics {
        override fun light() = Unit
        override fun selection() = Unit
    }
}

/** "Remove animations" is on: infinite animations show their static version. */
val LocalReduceMotion: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/** Backdrop blur may be drawn (hardware accelerated and not turned off). */
val LocalBlur: ProvidableCompositionLocal<Boolean> = staticCompositionLocalOf { false }

/** Fixed light and dark schemes that replace the system dynamic scheme; tests only. */
data class SeedSchemes(val light: ColorScheme, val dark: ColorScheme)

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

/** The system's "Remove animations" (animator duration scale 0), read when the theme is first composed. */
@Composable
fun systemReduceMotion(): Boolean {
    val resolver = LocalContext.current.contentResolver
    return remember(resolver) { Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }
}

/**
 * System dynamic color (minSdk 31 always has it), or [seed] in tests, or the static palette, all through [deriveScheme];
 * [pureBlack] makes the page #000000 or #FFFFFF. Ripples are off: presses wash and keyboard focus draws a ring.
 */
@Composable
fun ApofisTheme(
    mode: ThemeMode,
    dynamicColor: Boolean,
    pureBlack: Boolean,
    hapticsEnabled: () -> Boolean = { true },
    reduceMotion: Boolean = systemReduceMotion(),
    blur: Boolean = true,
    seed: SeedSchemes? = null,
    content: @Composable () -> Unit,
) {
    val dark = isDarkTheme(mode)
    val context = LocalContext.current
    val base = when {
        !dynamicColor -> staticScheme(dark)
        seed != null -> if (dark) seed.dark else seed.light
        dark -> dynamicDarkColorScheme(context)
        else -> dynamicLightColorScheme(context)
    }
    val scheme = remember(base, dark, pureBlack) { deriveScheme(base, dark, pageFor(base, dark, pureBlack)) }
    val colors = remember(scheme, dark) { apofisColors(scheme, dark) }
    val view = LocalView.current
    val enabled by rememberUpdatedState(hapticsEnabled)
    val haptics = remember(view) { ViewHaptics(view::performHapticFeedback) { enabled() } }
    MaterialTheme(colorScheme = scheme, typography = ApofisTypography, shapes = ApofisShapes) {
        CompositionLocalProvider(
            LocalApofisColors provides colors,
            LocalFocusRing provides scheme.primary,
            LocalRippleConfiguration provides null,
            LocalIndication provides WashIndication,
            LocalHaptics provides haptics,
            LocalReduceMotion provides reduceMotion,
            LocalBlur provides (blur && view.isHardwareAccelerated),
            content = content,
        )
    }
}

object ApofisTheme {
    val colors: ApofisColors
        @Composable @ReadOnlyComposable get() = LocalApofisColors.current
    val reduceMotion: Boolean
        @Composable @ReadOnlyComposable get() = LocalReduceMotion.current
    val blur: Boolean
        @Composable @ReadOnlyComposable get() = LocalBlur.current
    val haptics: Haptics
        @Composable @ReadOnlyComposable get() = LocalHaptics.current
}
