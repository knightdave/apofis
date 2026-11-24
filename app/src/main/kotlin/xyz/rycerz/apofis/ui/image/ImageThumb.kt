package xyz.rycerz.apofis.ui.image

import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.images.Decoded
import xyz.rycerz.apofis.images.ImageFailure
import xyz.rycerz.apofis.images.ImageInput
import xyz.rycerz.apofis.images.ImageLimits
import xyz.rycerz.apofis.images.ImageStore
import xyz.rycerz.apofis.images.decodeBounded
import xyz.rycerz.apofis.ui.theme.ApofisTheme

/** What the viewer shows: a stored photo by its digest, or the bytes of a reply's `data:` image with its MIME label. */
sealed interface ViewerImage {
    data class Stored(val digest: String) : ViewerImage
    // The array is never changed after it is made: a detached decoder may still read it.
    class Data(val bytes: ByteArray, val label: String) : ViewerImage
}

/** The connection's image store; null until one is provided (Task 5a): every stored image is then unavailable. */
val LocalImageStore: ProvidableCompositionLocal<ImageStore?> = staticCompositionLocalOf { null }

/** The connection's thumbnail cache; null: nothing is memoised, each new composition decodes again. */
val LocalThumbCache: ProvidableCompositionLocal<ThumbCache?> = staticCompositionLocalOf { null }

/**
 * Decoded thumbnails and reply images, or their failures, by digest or URL. Each entry weighs its bitmap's byte count
 * (a failure 64 KiB), at least 64 KiB, plus twice its key's length; the weight is capped at 16 MiB, the oldest evicted
 * first. The floor makes the cap hold at most 256 entries, whatever their keys or failures. Bitmaps are never recycled
 * here: a tile may still draw an evicted one, so it is left to the garbage collector.
 */
class ThumbCache : LruCache<String, Decoded>(MAX_WEIGHT) {
    override fun sizeOf(key: String, value: Decoded): Int =
        maxOf(FLOOR, (value as? Decoded.Ok)?.bitmap?.allocationByteCount ?: FLOOR) + 2 * key.length

    companion object {
        const val MAX_WEIGHT = 16 * 1024 * 1024
        const val MAX_ENTRIES = 256
        private const val FLOOR = MAX_WEIGHT / MAX_ENTRIES // 64 KiB
    }
}

/**
 * The decode of [key], from [LocalThumbCache] when it holds it, else from [decode] in this composition's coroutine
 * (cancelled when the caller leaves, which hands an unclaimed bitmap back to the decoder); null until it is done.
 * A failure is shown, not retried, until the caller is composed anew; one cached stays while resident, except BUSY.
 */
@Composable
internal fun rememberDecoded(key: String, decode: suspend () -> Decoded): Decoded? {
    val cache = LocalThumbCache.current
    val result = remember(key, cache) { mutableStateOf(cache?.get(key)) }
    // BUSY says nothing about the image: it is shown but not cached, so the next composition tries again.
    if (result.value == null) LaunchedEffect(key, cache) { result.value = decode().also { if ((it as? Decoded.Failed)?.reason != ImageFailure.BUSY) cache?.put(key, it) } }
    return result.value
}

/**
 * A stored photo at [size], cover crop, small radius, decoded at [ImageLimits.THUMB_SIDE]. A null or missing digest or
 * a failed decode shows the placeholder, labelled unavailable and not clickable; [onClick] null: never clickable.
 */
@Composable
fun ImageThumb(digest: String?, size: Dp, label: String, onClick: (() -> Unit)?) {
    val shape = MaterialTheme.shapes.small
    val decoded = if (digest == null) {
        Decoded.Failed(ImageFailure.UNREADABLE)
    } else {
        val store = LocalImageStore.current
        val context = LocalContext.current
        rememberDecoded(digest) {
            val file = withContext(Dispatchers.IO) { store?.file(digest) }
            if (file == null) Decoded.Failed(ImageFailure.UNREADABLE) else decodeBounded(context, ImageInput.Stored(file), ImageLimits.THUMB_SIDE)
        }
    }
    val box = Modifier.size(size).clip(shape)
    if (decoded is Decoded.Ok) {
        Image(
            remember(decoded.bitmap) { decoded.bitmap.asImageBitmap() },
            label,
            box.then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier),
            contentScale = ContentScale.Crop,
        )
    } else {
        ImagePlaceholder(box, if (decoded == null) label else stringResource(R.string.chat_image_unavailable), glyph = decoded != null, glyphSize = 24.dp)
    }
}

/** The fill of an image that has no picture (yet); once it is known to have none, the image-off glyph and [failed]. */
@Composable
internal fun ImagePlaceholder(modifier: Modifier, label: String, glyph: Boolean, glyphSize: Dp, failed: @Composable () -> Unit = {}) {
    val colors = ApofisTheme.colors
    Column(
        modifier.background(colors.thumbPlaceholder).semantics(mergeDescendants = true) { contentDescription = label },
        verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        if (glyph) {
            Icon(painterResource(R.drawable.lucide_image_off), null, Modifier.size(glyphSize), tint = colors.muted)
            failed()
        }
    }
}
