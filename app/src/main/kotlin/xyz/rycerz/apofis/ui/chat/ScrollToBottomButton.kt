package xyz.rycerz.apofis.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.ui.theme.ApofisIconButton
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.BackdropState
import xyz.rycerz.apofis.ui.theme.EaseOutCubic
import xyz.rycerz.apofis.ui.theme.Motion
import xyz.rycerz.apofis.ui.theme.frostedGlass

/** The button shows while the list is away from the bottom and scrolling, and for [Motion.SCROLL_BUTTON_HIDE] ms after. */
internal fun scrollButtonVisible(away: Boolean, scrolling: Boolean, idleMs: Long): Boolean =
    away && (scrolling || idleMs < Motion.SCROLL_BUTTON_HIDE)

/** A 28 dp glass circle with a 16 dp arrow in a 48 dp target; slides in from the end and fades. */
@Composable
internal fun ScrollToBottomButton(visible: Boolean, backdrop: BackdropState?, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val sign = if (LocalLayoutDirection.current == LayoutDirection.Rtl) -1 else 1
    val spec = tween<androidx.compose.ui.unit.IntOffset>(Motion.SCROLL_BUTTON_SLIDE, easing = EaseOutCubic)
    val fade = tween<Float>(Motion.SCROLL_BUTTON_SLIDE, easing = EaseOutCubic)
    AnimatedVisibility(
        visible,
        modifier,
        enter = slideInHorizontally(spec) { sign * it } + fadeIn(fade),
        exit = slideOutHorizontally(spec) { sign * it } + fadeOut(fade),
    ) {
        val colors = ApofisTheme.colors
        ApofisIconButton(onClick, stringResource(R.string.chat_scroll_to_bottom), visualSize = 28.dp) {
            // Without blur the glass shows the content under it, which can be as light as the arrow (dark theme over a light
            // patch): a page backplate keeps the arrow at its contrast against the fill whatever lies under the button.
            val fillOnly = backdrop?.layer == null || !ApofisTheme.blur
            val backplate = if (fillOnly) Modifier.background(MaterialTheme.colorScheme.surface, CircleShape) else Modifier
            Box(Modifier.fillMaxSize().then(backplate).frostedGlass(backdrop, CircleShape, colors.glassButtonFill, colors.glassButtonBorder), contentAlignment = Alignment.Center) {
                Icon(painterResource(R.drawable.lucide_arrow_down), null, Modifier.size(16.dp), tint = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
