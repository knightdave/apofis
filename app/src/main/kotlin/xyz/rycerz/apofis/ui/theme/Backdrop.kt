package xyz.rycerz.apofis.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlurEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.inset
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.node.GlobalPositionAwareModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.node.requireGraphicsContext
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.ceil

/**
 * One [GraphicsLayer] holding the display list of the backdrop source's viewport (the message list with its fade), and
 * the source's position in the root. [layer] is null when blur is off: the source then draws directly and glass draws
 * its fill only.
 */
@Stable
class BackdropState internal constructor(internal val layer: GraphicsLayer?) {
    internal var origin by mutableStateOf(Offset.Unspecified)
    internal var samples = 0 // blurred samples recorded from this backdrop (tests read it)
}

/** A backdrop that blurs only while [LocalBlur] is on (hardware rendering, not turned off; never in goldens). */
@Composable
fun rememberBackdropState(): BackdropState {
    val layer = if (LocalBlur.current) rememberGraphicsLayer() else null
    return remember(layer) { BackdropState(layer) }
}

/**
 * Records this node's drawing (only what is on screen: lazy content outside the viewport is not composed) over an
 * opaque [background] (the page behind it) into the state's layer once per frame it draws, and draws that layer sharp.
 * The page is part of the recording so a blurred sample replaces the sharp ink under the glass instead of laying
 * translucent blurred ink over it. Glass belongs outside the source, so it is never sampled.
 */
fun Modifier.backdropSource(state: BackdropState, background: Color): Modifier {
    val layer = state.layer ?: return this
    return onGloballyPositioned { state.origin = it.positionInRoot() }
        .drawWithContent {
            layer.record {
                drawRect(background)
                this@drawWithContent.drawContent()
            }
            drawLayer(layer)
        }
}

/**
 * Glass: with a recorded backdrop, a blurred copy of only the backdrop under this element (its bounds plus [blur] on each
 * side) clipped to [shape]; then [fill] and a 1 dp [border]. Without one (blur off, software rendering, goldens) only
 * [fill] and [border]. The copy references the source layer's display list, so it follows the source's redraws without
 * being recorded again; it is recorded again only when this element or the source moves or resizes.
 */
fun Modifier.frostedGlass(state: BackdropState?, shape: Shape, fill: Color, border: Color, blur: Dp = 24.dp): Modifier =
    this then GlassElement(state, shape, fill, border, blur)

/** [this] with alpha 0, for gradients that fade a token out (interpolating toward transparent black would grey it). */
internal fun Color.transparent(): Color = copy(alpha = 0f)

private const val SAMPLE_SCALE = 0.5f

private data class GlassElement(val state: BackdropState?, val shape: Shape, val fill: Color, val border: Color, val blur: Dp) :
    ModifierNodeElement<GlassNode>() {
    override fun create() = GlassNode(state, shape, fill, border, blur)

    override fun update(node: GlassNode) {
        node.state = state
        node.shape = shape
        node.fill = fill
        node.border = border
        node.blur = blur
        node.invalidateDraw()
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "frostedGlass"
    }
}

private class GlassNode(var state: BackdropState?, var shape: Shape, var fill: Color, var border: Color, var blur: Dp) :
    Modifier.Node(), DrawModifierNode, GlobalPositionAwareModifierNode, CompositionLocalConsumerModifierNode {
    private var sample: GraphicsLayer? = null
    private var position = Offset.Unspecified

    override fun onGloballyPositioned(coordinates: LayoutCoordinates) {
        val now = coordinates.positionInRoot()
        if (now != position) {
            position = now
            invalidateDraw()
        }
    }

    override fun onDetach() {
        sample?.let { requireGraphicsContext().releaseGraphicsLayer(it) }
        sample = null
        position = Offset.Unspecified
    }

    override fun ContentDrawScope.draw() {
        val outline = shape.createOutline(size, layoutDirection, this)
        val source = state?.layer?.takeIf { currentValueOf(LocalBlur) } // blur off here wins over a recording source
        val origin = state?.origin ?: Offset.Unspecified // a state read: a moved source redraws this
        if (source != null && origin.isSpecified && position.isSpecified && size.width >= 1f && size.height >= 1f) {
            val margin = blur.toPx()
            val layer = sample ?: requireGraphicsContext().createGraphicsLayer().also { sample = it }
            layer.clip = true // the blur works on this sub-rectangle only, never on the whole viewport
            // Recorded and blurred at half resolution, drawn scaled up (a blur hides the lost detail): at full resolution
            // the emulator measured 13 ms more at p95, at half 6 ms (Task 7 report).
            layer.renderEffect = if (margin > 0f) BlurEffect(margin * SAMPLE_SCALE, margin * SAMPLE_SCALE, TileMode.Clamp) else null
            val at = position - origin
            val w = ceil((size.width + 2 * margin) * SAMPLE_SCALE).toInt()
            val h = ceil((size.height + 2 * margin) * SAMPLE_SCALE).toInt()
            state?.let { it.samples++ }
            layer.record(IntSize(w, h)) {
                scale(SAMPLE_SCALE, SAMPLE_SCALE, pivot = Offset.Zero) { translate(margin - at.x, margin - at.y) { drawLayer(source) } }
            }
            clipPath(Path().apply { addOutline(outline) }) {
                translate(-margin, -margin) { scale(1 / SAMPLE_SCALE, 1 / SAMPLE_SCALE, pivot = Offset.Zero) { drawLayer(layer) } }
            }
        }
        drawOutline(outline, fill)
        val stroke = 1.dp.toPx()
        inset(stroke / 2) { drawOutline(shape.createOutline(size, layoutDirection, this), border, style = Stroke(stroke)) }
        drawContent()
    }
}
