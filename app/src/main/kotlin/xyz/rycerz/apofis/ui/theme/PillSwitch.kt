package xyz.rycerz.apofis.ui.theme

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp

/**
 * The UI map's switch: a 44 x 26 dp pill with a 1 dp border and a 20 dp thumb inset 3 dp, at the start when off and at
 * the end when on, moving over [Motion.SWITCH] ms. Visual only: the row carries the toggleable semantics and the press.
 * The off border is the field outline (3:1 on the card), not the map's faint hairline.
 */
@Composable
fun PillSwitch(checked: Boolean, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val colors = ApofisTheme.colors
    val on by animateFloatAsState(if (checked) 1f else 0f, tween(Motion.SWITCH, easing = EaseOutCubic), label = "switch")
    Canvas(modifier.size(44.dp, 26.dp)) {
        val radius = CornerRadius(size.height / 2)
        drawRoundRect(lerp(colors.switchOffTrack, scheme.primary, on), cornerRadius = radius)
        val stroke = 1.dp.toPx()
        drawRoundRect(
            lerp(scheme.outline, scheme.primary, on),
            topLeft = Offset(stroke / 2, stroke / 2),
            size = Size(size.width - stroke, size.height - stroke),
            cornerRadius = CornerRadius(size.height / 2 - stroke / 2),
            style = Stroke(stroke),
        )
        val thumb = 10.dp.toPx()
        val travel = size.width - 2 * (3.dp.toPx() + thumb)
        val fromStart = 3.dp.toPx() + thumb + travel * on
        val x = if (layoutDirection == LayoutDirection.Rtl) size.width - fromStart else fromStart
        drawCircle(lerp(colors.switchOffThumb, scheme.onPrimary, on), thumb, Offset(x, size.height / 2))
    }
}
