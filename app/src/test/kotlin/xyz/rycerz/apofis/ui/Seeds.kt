package xyz.rycerz.apofis.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color
import xyz.rycerz.apofis.ui.theme.SeedSchemes

/** Two fixed dynamic schemes, injected through the real derivation path instead of the system palette. */
val PurpleSeed = SeedSchemes(
    light = lightColorScheme(
        surface = Color(0xFFFEF7FF),
        onSurface = Color(0xFF1D1B20),
        primary = Color(0xFF65558F),
        onPrimary = Color(0xFFFFFFFF),
        secondary = Color(0xFF625B71),
        primaryContainer = Color(0xFFE9DDFF),
        onSurfaceVariant = Color(0xFF49454F),
    ),
    dark = darkColorScheme(
        surface = Color(0xFF141218),
        onSurface = Color(0xFFE6E0E9),
        primary = Color(0xFFCFBCFF),
        onPrimary = Color(0xFF381E72),
        secondary = Color(0xFFCBC2DB),
        primaryContainer = Color(0xFF4F378B),
        onSurfaceVariant = Color(0xFFCAC4D0),
    ),
)

val GreenSeed = SeedSchemes(
    light = lightColorScheme(
        surface = Color(0xFFF7FBF1),
        onSurface = Color(0xFF181D17),
        primary = Color(0xFF3B6939),
        onPrimary = Color(0xFFFFFFFF),
        secondary = Color(0xFF52634F),
        primaryContainer = Color(0xFFBCF0B4),
        onSurfaceVariant = Color(0xFF424940),
    ),
    dark = darkColorScheme(
        surface = Color(0xFF10140F),
        onSurface = Color(0xFFE0E4DB),
        primary = Color(0xFFA1D39A),
        onPrimary = Color(0xFF0A390F),
        secondary = Color(0xFFB9CCB4),
        primaryContainer = Color(0xFF235023),
        onSurfaceVariant = Color(0xFFC2C9BD),
    ),
)
