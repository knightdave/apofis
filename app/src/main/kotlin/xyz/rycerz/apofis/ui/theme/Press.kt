package xyz.rycerz.apofis.ui.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.clickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/** The keyboard focus ring color, provided by [ApofisTheme]. */
internal val LocalFocusRing = staticCompositionLocalOf { Color.Unspecified }

/**
 * An icon button without a ripple: a 48 dp touch target around a centred [visualSize] box whose content fades to
 * 0.45 while pressed (0.38 when disabled); keyboard focus draws a ring. [contentDescription] labels the button node,
 * so the content's own icons pass none.
 */
@Composable
fun ApofisIconButton(
    onClick: () -> Unit,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    visualSize: Dp = 44.dp,
    content: @Composable () -> Unit,
) {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val alpha by animateFloatAsState(
        when {
            !enabled -> 0.38f
            pressed -> 0.45f
            else -> 1f
        },
        tween(Motion.PRESS_FADE, easing = EaseOutCubic),
        label = "press",
    )
    Box(
        modifier
            .sizeIn(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(source, focusRing, enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { if (contentDescription != null) this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(visualSize).graphicsLayer { this.alpha = alpha }, contentAlignment = Alignment.Center) { content() }
    }
}

/** Rows and cards: pressed washes toward onSurface over [Motion.WASH] ms; focused draws a 2 dp primary ring. */
internal val WashIndication: IndicationNodeFactory = PressIndication(wash = true, RectangleShape, Color.Unspecified)

/** Focus ring only: [ApofisIconButton] shows its press by fading the content. */
private val focusRing = PressIndication(wash = false, RectangleShape, Color.Unspecified)

/**
 * The wash on a Material component that installs its own (disabled) ripple: pass it the same [interactionSource].
 * The focus ring follows [shape]; on a filled control pass a [ring] that contrasts with its fill (onPrimary on primary).
 */
fun Modifier.wash(interactionSource: InteractionSource, shape: Shape = RectangleShape, ring: Color = Color.Unspecified): Modifier =
    clip(shape).indication(interactionSource, PressIndication(wash = true, shape, ring))

/** [ring] Unspecified: the theme's primary. */
private data class PressIndication(val wash: Boolean, val shape: Shape, val ring: Color) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode = PressNode(interactionSource, this)
}

private class PressNode(private val source: InteractionSource, private val style: PressIndication) :
    Modifier.Node(), DrawModifierNode, CompositionLocalConsumerModifierNode {
    private val washAlpha = Animatable(0f)
    private var focused by mutableStateOf(false)

    override fun onAttach() {
        coroutineScope.launch {
            val presses = mutableListOf<PressInteraction.Press>()
            val focuses = mutableListOf<FocusInteraction.Focus>()
            var pressed = false // decided here, synchronously, so a release in the same frame as its press always wins
            var animation: Job? = null
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> presses += interaction
                    is PressInteraction.Release -> presses -= interaction.press
                    is PressInteraction.Cancel -> presses -= interaction.press
                    is FocusInteraction.Focus -> focuses += interaction
                    is FocusInteraction.Unfocus -> focuses -= interaction.focus
                }
                if (focused != focuses.isNotEmpty()) {
                    focused = focuses.isNotEmpty()
                    invalidateDraw()
                }
                if (style.wash && pressed != presses.isNotEmpty()) {
                    pressed = presses.isNotEmpty()
                    val target = if (pressed) 1f else 0f
                    animation?.cancel()
                    animation = launch { washAlpha.animateTo(target, tween(Motion.WASH, easing = EaseOutCubic)) }
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        val alpha = washAlpha.value
        if (alpha > 0f) drawRect(currentValueOf(LocalApofisColors).pressWash, alpha = alpha)
        if (focused) {
            val stroke = 2.dp.toPx()
            val color = style.ring.takeOrElse { currentValueOf(LocalFocusRing) }
            inset(1.dp.toPx() + stroke / 2) { drawOutline(style.shape.createOutline(size, layoutDirection, this), color, style = Stroke(stroke)) }
        }
    }
}
