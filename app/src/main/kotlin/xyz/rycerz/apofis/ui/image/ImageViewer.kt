package xyz.rycerz.apofis.ui.image

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.toSize
import kotlin.math.roundToInt
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.withContext
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.images.DecodeLimits
import xyz.rycerz.apofis.images.Decoded
import xyz.rycerz.apofis.images.ImageFailure
import xyz.rycerz.apofis.images.ImageInput
import xyz.rycerz.apofis.images.ImageLimits
import xyz.rycerz.apofis.images.REPLY_FORMATS
import xyz.rycerz.apofis.images.decodeBounded
import xyz.rycerz.apofis.ui.theme.ApofisIconButton
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.EaseOutCubic
import xyz.rycerz.apofis.ui.theme.Motion

/** Opens the viewer on an image; null (the default): image tiles are not clickable. */
val LocalImageOpen = staticCompositionLocalOf<((ViewerImage) -> Unit)?> { null }

/** The limits of a reply's `data:` image, in a tile and in the viewer. */
internal val ReplyLimits = DecodeLimits(maxPixels = ImageLimits.REPLY_PIXELS, formats = REPLY_FORMATS)

private const val MAX_SCALE = 5f
private const val DOUBLE_TAP_SCALE = 2.5f
private const val MAX_SAVED_BYTES = 16 * 1024

/**
 * The open image across recreation: a stored one by its digest, a data one by its bytes and label. Data above
 * 16 KiB is not saved (a reply's image passed the 20,000 character block bound, so it is at most 15,000 bytes); the
 * viewer is then closed after recreation.
 */
internal val ViewerImageSaver = Saver<ViewerImage?, Any>(
    save = { image ->
        when (image) {
            is ViewerImage.Stored -> image.digest
            is ViewerImage.Data -> if (image.bytes.size <= MAX_SAVED_BYTES) arrayListOf(image.bytes, image.label) else null
            null -> null
        }
    },
    restore = { saved ->
        when (saved) {
            is String -> ViewerImage.Stored(saved)
            is List<*> -> ViewerImage.Data(saved[0] as ByteArray, saved[1] as String)
            else -> null
        }
    },
)

/** [scale] within 1 to 5 and [offset] such that the scaled [image], centred in [viewport], never leaves an edge gap. */
internal fun clampTransform(scale: Float, offset: Offset, image: Size, viewport: Size): Pair<Float, Offset> {
    val s = scale.coerceIn(1f, MAX_SCALE)
    val x = maxOf(0f, (image.width * s - viewport.width) / 2)
    val y = maxOf(0f, (image.height * s - viewport.height) / 2)
    return s to Offset(offset.x.coerceIn(-x, x), offset.y.coerceIn(-y, y))
}

/**
 * A [width] x [height] picture fitted inside [box] (px), aspect kept and rounded as an image is drawn, each side at least
 * one physical pixel, so a thin image never vanishes.
 */
internal fun fitSize(width: Int, height: Int, box: Size): IntSize {
    val fit = minOf(box.width / width, box.height / height)
    return IntSize(maxOf(1, (width * fit).roundToInt()), maxOf(1, (height * fit).roundToInt()))
}

internal typealias ViewerDecode = suspend (Context, ImageInput, DecodeLimits) -> Decoded

internal val decodeForViewer: ViewerDecode = { context, input, limits -> decodeBounded(context, input, ImageLimits.VIEW_SIDE, limits = limits) }

/**
 * One image full screen on black, decoded at [ImageLimits.VIEW_SIDE] when it opens and recycled when it closes; a
 * data image within the reply limits and its own label. Pinch and pan within [clampTransform], double tap toggles 1
 * and 2.5, Back or the close button closes; fades in and out, at once under reduced motion.
 */
@Composable
fun ImageViewer(image: ViewerImage, onClose: () -> Unit) = ImageViewer(image, onClose, decodeForViewer)

@Composable
internal fun ImageViewer(image: ViewerImage, onClose: () -> Unit, decode: ViewerDecode) {
    val reduce = ApofisTheme.reduceMotion
    val closeNow by rememberUpdatedState(onClose)
    val fade = remember { Animatable(if (reduce) 1f else 0f) }
    var closing by remember { mutableStateOf(false) }
    LaunchedEffect(closing) {
        if (!closing) {
            fade.animateTo(1f, tween(Motion.VIEWER_IN, easing = EaseOutCubic))
        } else {
            fade.animateTo(0f, tween(Motion.VIEWER_OUT, easing = EaseOutCubic))
            closeNow()
        }
    }
    val close = { if (reduce) closeNow() else closing = true }
    Dialog(onDismissRequest = close, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val colors = ApofisTheme.colors
        val context = LocalContext.current
        val store = LocalImageStore.current
        var decoded by remember(image) { mutableStateOf<Decoded?>(null) }
        // The one owner of the bitmap: it is recycled when the viewer leaves or shows another image.
        LaunchedEffect(image) {
            var bitmap: android.graphics.Bitmap? = null
            try {
                val result = when (image) {
                    is ViewerImage.Stored -> withContext(Dispatchers.IO) { store?.file(image.digest) }
                        ?.let { decode(context, ImageInput.Stored(it), DecodeLimits()) }
                    is ViewerImage.Data -> decode(context, ImageInput.Bytes(image.bytes, image.label), ReplyLimits)
                } ?: Decoded.Failed(ImageFailure.UNREADABLE)
                bitmap = (result as? Decoded.Ok)?.bitmap
                decoded = result
                awaitCancellation()
            } finally {
                decoded = null
                bitmap?.recycle()
            }
        }
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = fade.value }.background(colors.viewerBackdrop),
            contentAlignment = Alignment.Center,
        ) {
            when (val d = decoded) {
                is Decoded.Ok -> Zoomable(d.bitmap)
                is Decoded.Failed -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Icon(painterResource(R.drawable.lucide_image_off), null, Modifier.size(26.dp), tint = colors.onViewer)
                    Text(stringResource(R.string.md_image_failed), style = MaterialTheme.typography.bodyMedium, color = colors.onViewer)
                }
                null -> Unit
            }
            ApofisIconButton(
                onClick = close,
                contentDescription = stringResource(R.string.viewer_close),
                modifier = Modifier.align(Alignment.TopStart).windowInsetsPadding(WindowInsets.safeDrawing).padding(start = 12.dp, top = 6.dp),
                visualSize = 40.dp,
            ) {
                Box(Modifier.fillMaxSize().background(colors.thumbChip, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.lucide_x), null, Modifier.size(20.dp), tint = colors.thumbChipGlyph)
                }
            }
        }
    }
}

/** The bitmap fitted in the viewport, zoomed and panned by pinch, drag and double tap; never past [clampTransform]. */
@Composable
private fun Zoomable(bitmap: android.graphics.Bitmap) {
    var viewport by remember { mutableStateOf(Size.Zero) }
    var scale by remember(bitmap) { mutableStateOf(1f) }
    var offset by remember(bitmap) { mutableStateOf(Offset.Zero) }
    val fitted = remember(bitmap, viewport) { fitSize(bitmap.width, bitmap.height, viewport) }
    fun apply(s: Float, o: Offset) = clampTransform(s, o, fitted.toSize(), viewport).let { (cs, co) -> scale = cs; offset = co }
    val transform = rememberTransformableState { zoom, pan, _ -> apply(scale * zoom, offset + pan) }
    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { viewport = it.toSize() }
            .pointerInput(bitmap) {
                detectTapGestures(onDoubleTap = { at ->
                    // Zoom in at the tapped point, or back to the whole image.
                    if (scale > 1f) apply(1f, Offset.Zero) else apply(DOUBLE_TAP_SCALE, (Offset(size.width / 2f, size.height / 2f) - at) * (DOUBLE_TAP_SCALE - 1f))
                })
            }
            .transformable(transform),
        contentAlignment = Alignment.Center,
    ) {
        Image(
            remember(bitmap) { bitmap.asImageBitmap() },
            stringResource(R.string.md_image),
            Modifier
                .size(with(LocalDensity.current) { DpSize(fitted.width.toDp(), fitted.height.toDp()) })
                .graphicsLayer { scaleX = scale; scaleY = scale; translationX = offset.x; translationY = offset.y },
            contentScale = ContentScale.FillBounds,
        )
    }
}
