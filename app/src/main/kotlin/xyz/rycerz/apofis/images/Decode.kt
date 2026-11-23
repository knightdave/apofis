package xyz.rycerz.apofis.images

import android.content.ContentResolver
import android.content.Context
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.CancellationSignal
import androidx.core.graphics.createBitmap
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

object ImageLimits {
    const val MAX_SOURCE_BYTES = 25L * 1024 * 1024
    const val MAX_PIXELS = 64_000_000L
    const val REPLY_PIXELS = 4_000_000L
    const val UPLOAD_SIDE = 1568
    const val VIEW_SIDE = 2560
    const val THUMB_SIDE = 320
    const val JPEG_QUALITY = 85
    const val MAX_IMAGES = 10
    const val MAX_COPIES = 2
    const val MAX_SHARE_BYTES = 24L * 1024 * 1024
    const val GATE_WAIT_MS = 20_000L
}

enum class ImageFailure { UNREADABLE, TOO_LARGE, NOT_AN_IMAGE, BUSY }

sealed interface Decoded {
    data class Ok(val bitmap: Bitmap) : Decoded
    data class Failed(val reason: ImageFailure) : Decoded
}

sealed interface Encoded {
    data class Ok(val jpeg: ByteArray) : Encoded
    data class Failed(val reason: ImageFailure) : Encoded
}

sealed interface ImageInput {
    data class Content(val uri: Uri) : ImageInput
    data class Bytes(val bytes: ByteArray, val label: String? = null) : ImageInput
    data class Stored(val file: File) : ImageInput
}

enum class ImageFormat { JPEG, PNG, GIF, WEBP, HEIF, AVIF }

val UPLOAD_FORMATS: Set<ImageFormat> = ImageFormat.entries.toSet()
val REPLY_FORMATS: Set<ImageFormat> = setOf(ImageFormat.JPEG, ImageFormat.PNG, ImageFormat.GIF, ImageFormat.WEBP)

data class DecodeLimits(
    val maxSourceBytes: Long = ImageLimits.MAX_SOURCE_BYTES,
    val maxPixels: Long = ImageLimits.MAX_PIXELS,
    val formats: Set<ImageFormat> = UPLOAD_FORMATS,
    val sourceDeadlineMs: Long = 20_000,
)

private const val ALLOCATION_LIMIT = 64L * 1024 * 1024
private const val SNIFF_BYTES = 64

/** Process-wide: one decode at a time, at most [ImageLimits.MAX_COPIES] source copies (each with its temp file). */
private val gate = Mutex()
private val copySlots = Semaphore(ImageLimits.MAX_COPIES)

/** Blocking work that may outlive its caller (a stuck provider read, a decode whose caller was cancelled). */
internal val decodeWork = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private class Fail(val reason: ImageFailure) : Exception(null, null, false, false)

/** A copy slot and, once created, its temp file: given back together exactly once. */
private class Copy {
    @Volatile var file: File? = null
    private val done = AtomicBoolean()

    fun release() {
        if (done.compareAndSet(false, true)) {
            file?.delete()
            copySlots.release()
        }
    }
}

suspend fun decodeBounded(
    context: Context,
    input: ImageInput,
    longestSide: Int,
    software: Boolean = false,
    limits: DecodeLimits = DecodeLimits(),
): Decoded = decodeBounded(context, input, longestSide, software, limits, ImageDecoder::decodeBitmap)

internal suspend fun decodeBounded(
    context: Context,
    input: ImageInput,
    longestSide: Int,
    software: Boolean,
    limits: DecodeLimits,
    decode: (ImageDecoder.Source, ImageDecoder.OnHeaderDecodedListener) -> Bitmap,
): Decoded = try {
    var copy: Copy? = null
    val source: ImageInput = when (input) {
        is ImageInput.Bytes -> if (input.bytes.size > limits.maxSourceBytes) throw Fail(ImageFailure.TOO_LARGE) else input
        is ImageInput.Stored -> input
        is ImageInput.Content -> {
            if (!copySlots.tryAcquire()) throw Fail(ImageFailure.BUSY)
            copy = copyIn(context, input.uri, limits)
            ImageInput.Stored(copy.file!!)
        }
    }
    var handedOver = false
    try {
        if (withTimeoutOrNull(ImageLimits.GATE_WAIT_MS) { gate.lock() } == null) throw Fail(ImageFailure.UNREADABLE)
        handedOver = true
        // The decode cannot be interrupted: a cancelled caller leaves at once, the gate and the copy wait for it.
        decodeWork.async {
            try {
                Decoded.Ok(decodeGated(source, longestSide, software, limits, decode))
            } finally {
                gate.unlock()
                copy?.release()
            }
        }.await()
    } finally {
        if (!handedOver) copy?.release()
    }
} catch (e: Fail) {
    Decoded.Failed(e.reason)
} catch (e: CancellationException) {
    currentCoroutineContext().ensureActive()
    Decoded.Failed(ImageFailure.UNREADABLE)
} catch (e: Throwable) {
    Decoded.Failed(ImageFailure.UNREADABLE)
}

/** Scheme content only, a provider of another package, no user info in the authority. */
private fun contentAllowed(context: Context, uri: Uri): Boolean {
    if (uri.scheme != ContentResolver.SCHEME_CONTENT) return false
    val authority = uri.authority ?: return false
    if ('@' in authority) return false
    @Suppress("DEPRECATION") // the flags overload needs API 33; 0 means the same on every level
    val provider = context.packageManager.resolveContentProvider(authority, 0) ?: return false
    return provider.packageName != context.packageName
}

/**
 * Copies [uri] into a new temp file; the caller has taken a copy slot, which this call owns from here. A watchdog
 * in the caller's context, armed before the copy starts, bounds the check, the open and the read; on expiry or
 * cancellation it cancels the signal and closes the descriptor, and a blocked call keeps the slot until it returns.
 */
private suspend fun copyIn(context: Context, uri: Uri, limits: DecodeLimits): Copy {
    val copy = Copy()
    val signal = CancellationSignal()
    val descriptor = AtomicReference<AssetFileDescriptor?>()
    val copied = CompletableDeferred<Copy>()
    var started = false
    fun giveUp() {
        signal.cancel()
        runCatching { descriptor.get()?.close() }
        // Not started, or already finished (the copy job has returned): release now; otherwise the job does it.
        if (!started || !copied.completeExceptionally(CancellationException("gave up"))) copy.release()
    }
    return try {
        withTimeoutOrNull(limits.sourceDeadlineMs) {
            started = true
            decodeWork.launch {
                val handed = try {
                    if (!contentAllowed(context, uri)) throw Fail(ImageFailure.NOT_AN_IMAGE)
                    val file = File.createTempFile("decode-", ".tmp", context.cacheDir).also { copy.file = it }
                    val afd = context.contentResolver.openAssetFileDescriptor(uri, "r", signal) ?: throw Fail(ImageFailure.UNREADABLE)
                    descriptor.set(afd)
                    afd.use { copyCapped(it.createInputStream(), file, limits.maxSourceBytes) }
                    copied.complete(copy)
                } catch (e: Throwable) {
                    copied.completeExceptionally(e)
                    false
                }
                // Failed, or the caller gave up first: nobody else will release it.
                if (!handed) copy.release()
            }
            copied.await()
        } ?: run {
            giveUp()
            throw Fail(ImageFailure.UNREADABLE)
        }
    } catch (e: CancellationException) {
        giveUp()
        throw e
    }
}

/** Bytes read are counted; past [max] the copy stops with TOO_LARGE. */
private fun copyCapped(input: InputStream, file: File, max: Long) {
    file.outputStream().use { out ->
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = input.read(buffer)
            if (n < 0) break
            total += n
            if (total > max) throw Fail(ImageFailure.TOO_LARGE)
            out.write(buffer, 0, n)
        }
    }
}

/** Runs on a worker thread while this call holds the gate. */
private fun decodeGated(
    input: ImageInput,
    longestSide: Int,
    software: Boolean,
    limits: DecodeLimits,
    decode: (ImageDecoder.Source, ImageDecoder.OnHeaderDecodedListener) -> Bitmap,
): Bitmap {
    val head = when (input) {
        is ImageInput.Bytes -> input.bytes.copyOf(minOf(SNIFF_BYTES, input.bytes.size))
        is ImageInput.Stored -> {
            if (input.file.length() > limits.maxSourceBytes) throw Fail(ImageFailure.TOO_LARGE)
            val buffer = ByteArray(SNIFF_BYTES)
            buffer.copyOf(maxOf(0, input.file.inputStream().use { it.read(buffer) }))
        }
        is ImageInput.Content -> error("copied before the gate")
    }
    val format = sniff(head)
    if (format == null || format !in limits.formats) throw Fail(ImageFailure.NOT_AN_IMAGE)
    val label = (input as? ImageInput.Bytes)?.label
    if (label != null && LABELS[label.lowercase()] != format) throw Fail(ImageFailure.NOT_AN_IMAGE)
    val source = when (input) {
        is ImageInput.Bytes -> ImageDecoder.createSource(input.bytes)
        else -> ImageDecoder.createSource((input as ImageInput.Stored).file)
    }
    return decode(source) { decoder, info, _ ->
        val width = info.size.width.toLong()
        val height = info.size.height.toLong()
        if (width * height > limits.maxPixels) throw Fail(ImageFailure.TOO_LARGE)
        val (targetWidth, targetHeight) = targetSize(width, height, longestSide)
        decoder.setTargetSize(targetWidth, targetHeight)
        if (software) decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        if (Build.VERSION.SDK_INT >= 36 && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.CINNAMON_BUN_2) {
            decoder.setAllocationLimit(ALLOCATION_LIMIT)
        }
    }
}

/** Longest side min(source, [side]), aspect kept, each side at least 1; Long arithmetic throughout. */
internal fun targetSize(width: Long, height: Long, side: Int): Pair<Int, Int> {
    val longest = maxOf(width, height)
    if (longest <= side) return width.toInt() to height.toInt()
    return maxOf(1L, width * side / longest).toInt() to maxOf(1L, height * side / longest).toInt()
}

private val LABELS = mapOf(
    "image/jpeg" to ImageFormat.JPEG,
    "image/png" to ImageFormat.PNG,
    "image/gif" to ImageFormat.GIF,
    "image/webp" to ImageFormat.WEBP,
    "image/heif" to ImageFormat.HEIF,
    "image/heic" to ImageFormat.HEIF,
    "image/avif" to ImageFormat.AVIF,
)

private val HEIF_BRANDS = setOf("heic", "heix", "heim", "heis", "hevc", "hevx", "hevm", "hevs", "mif1", "msf1")

/** The format named by the first bytes, or null. */
internal fun sniff(head: ByteArray): ImageFormat? {
    fun at(offset: Int, vararg bytes: Int) =
        head.size >= offset + bytes.size && bytes.indices.all { head[offset + it] == bytes[it].toByte() }
    fun text(offset: Int, s: String) = at(offset, *s.map { it.code }.toIntArray())
    return when {
        at(0, 0xFF, 0xD8, 0xFF) -> ImageFormat.JPEG
        at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> ImageFormat.PNG
        text(0, "GIF87a") || text(0, "GIF89a") -> ImageFormat.GIF
        text(0, "RIFF") && text(8, "WEBP") -> ImageFormat.WEBP
        text(4, "ftyp") -> {
            val box = ((head[0].toInt() and 0xFF) shl 24) or ((head[1].toInt() and 0xFF) shl 16) or
                ((head[2].toInt() and 0xFF) shl 8) or (head[3].toInt() and 0xFF)
            val end = minOf(head.size, if (box in 16..SNIFF_BYTES) box else head.size)
            // The major brand at 8, the compatible brands from 16; the minor version at 12 is skipped.
            val brands = (listOf(8) + (16..end - 4 step 4)).filter { it + 4 <= end }
                .map { String(head, it, 4, Charsets.US_ASCII) }
            when {
                brands.any { it == "avif" || it == "avis" } -> ImageFormat.AVIF
                brands.any { it in HEIF_BRANDS } -> ImageFormat.HEIF
                else -> null
            }
        }
        else -> null
    }
}

/** Software decode at [ImageLimits.UPLOAD_SIDE], flattened on white, JPEG at quality 85; pixels only, no EXIF. */
suspend fun toUploadJpeg(context: Context, input: ImageInput, limits: DecodeLimits = DecodeLimits()): Encoded {
    val decoded = decodeBounded(context, input, ImageLimits.UPLOAD_SIDE, software = true, limits)
    if (decoded is Decoded.Failed) return Encoded.Failed(decoded.reason)
    val bitmap = (decoded as Decoded.Ok).bitmap
    return try {
        withContext(Dispatchers.IO) {
            val flat = createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
            try {
                Canvas(flat).apply {
                    drawColor(Color.WHITE)
                    drawBitmap(bitmap, 0f, 0f, null)
                }
                val out = ByteArrayOutputStream()
                if (!flat.compress(Bitmap.CompressFormat.JPEG, ImageLimits.JPEG_QUALITY, out)) throw Fail(ImageFailure.UNREADABLE)
                Encoded.Ok(out.toByteArray())
            } finally {
                flat.recycle()
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Throwable) {
        Encoded.Failed(ImageFailure.UNREADABLE)
    } finally {
        bitmap.recycle()
    }
}

/** Deletes temp files a killed process left behind; Task 5a calls it at container start. */
fun clearDecodeTemps(cacheDir: File) {
    cacheDir.listFiles { file -> file.name.startsWith("decode-") && file.name.endsWith(".tmp") }?.forEach { it.delete() }
}
