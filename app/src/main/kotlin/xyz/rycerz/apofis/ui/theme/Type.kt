package xyz.rycerz.apofis.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

// UI map section 1 with Android's weight flattening applied here, once: declared medium is Normal (400),
// declared semibold and bold are Medium (500).
private fun style(size: TextUnit, weight: FontWeight = FontWeight.Normal, lineHeight: TextUnit = 1.3.em) = TextStyle(
    fontSize = size,
    fontWeight = weight,
    lineHeight = lineHeight,
    lineHeightStyle = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None),
)

internal val ApofisTypography = Typography(
    headlineSmall = style(24.sp), // screen heading (Connect)
    titleLarge = style(18.sp, FontWeight.Medium), // app bar titles other than chat (semibold)
    titleMedium = style(16.sp), // chat title (medium)
    bodyLarge = style(15.sp), // settings row (medium), composer, drawer chat title
    bodyMedium = style(14.sp), // drawer search, secondary text
    bodySmall = style(12.5.sp, lineHeight = 1.32.em), // timeline body
    labelLarge = style(13.sp, FontWeight.Medium), // settings section header (semibold), buttons
    labelMedium = style(13.sp), // message name (medium)
    labelSmall = style(11.sp), // app bar subtitle (medium), timestamps
)

/** The chat text size setting; provided around the message list and the Select view only. */
val LocalChatFontScale = staticCompositionLocalOf { 1f }

/** Message list text styles the Material type table has no slot for. */
object ChatText {
    val assistant = style(15.7.sp, lineHeight = 1.5.em).copy(letterSpacing = 0.05.sp)
    val user = style(15.5.sp, lineHeight = 1.45.em)
    val code = TextStyle(fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 1.5.em)
    val dialogTitle = style(17.sp, FontWeight.Medium) // declared bold, flattened
}

/** [style] at the chat text size. */
@Composable
fun scaled(style: TextStyle): TextStyle {
    val scale = LocalChatFontScale.current
    return if (scale == 1f) style else style.copy(fontSize = style.fontSize * scale)
}
