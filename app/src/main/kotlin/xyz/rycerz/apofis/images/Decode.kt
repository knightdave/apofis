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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
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

/**
 * Treat an array passed to ImageInput.Bytes as immutable; after cancellation or timeout a detached decoder may
 * still read it, so do not reuse or mutate that array. A `Stored` file is a file of the app's private directories,
 * trusted to be a regular file that does not change while it is read.
 */
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

private typealias Decoder = (ImageDecoder.Source, ImageDecoder.OnHeaderDecodedListener) -> Bitmap

private const val ALLOCATION_LIMIT = 64L * 1024 * 1024
private const val SNIFF_BYTES = 64

/** Process-wide: one decode (with an upload's encode) at a time, at most [ImageLimits.MAX_COPIES] source copies. */
private val gate = Mutex()
private val copySlots = Semaphore(ImageLimits.MAX_COPIES)

/** Every request's worker runs here. Internal for the tests' observation only; never cancelled. */
internal val decodeWork = CoroutineScope(SupervisorJob() + Dispatchers.IO)

private class Fail(val reason: ImageFailure) : Exception(null, null, false, false)

/**
 * What one request shares between its waiting caller and its worker. The worker owns everything the request
 * takes (copy slot, temp file, descriptor, gate, bitmap) and gives it all back before it reports. An abandoning
 * caller sets [abandoned] first; the worker checks it between stages and reads.
 */
private class Request<T : Any>(private val dispose: (T) -> Unit) {
    val abandoned = AtomicBoolean()
    val copied = CompletableDeferred<Unit>()
    val admitted = CompletableDeferred<Unit>()
    val done = CompletableDeferred<Unit>()
    val signal = CancellationSignal()
    val descriptor = AtomicReference<AssetFileDescriptor?>()
    @Volatile var value: T? = null
    @Volatile var failure: ImageFailure? = null
    private val claimed = AtomicBoolean()

    fun checkWanted() {
        if (abandoned.get()) throw Fail(ImageFailure.UNREADABLE)
    }

    /** The value, for the caller that waited for it; at most one of [take] and [disposeUnclaimed] gets it. */
    fun take(): T? = value?.takeIf { claimed.compareAndSet(false, true) }

    fun disposeUnclaimed() {
        val v = value ?: return
        if (claimed.compareAndSet(false, true)) dispose(v)
    }

    /** Never on the caller's thread: signal, close and dispose run on [decodeWork]. */
    fun abandon(worker: Job?) {
        abandoned.set(true)
        worker?.cancel() // wakes a worker waiting for the gate; no effect on blocking code
        decodeWork.launch {
            runCatching { signal.cancel() }
            runCatching { descriptor.getAndSet(null)?.close() }
            runCatching { disposeUnclaimed() }
        }
    }
}

/**
 * Decodes [input] within [limits], the longest side at most [longestSide]. Only cancellation of the calling job
 * propagates; an independently cancelled codec operation is an unreadable image.
 */
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
    decode: Decoder,
): Decoded = mapFailures(Decoded::Failed) {
    Decoded.Ok(admit(context, input, longestSide, software, limits, decode, use = { it }, dispose = Bitmap::recycle))
}

/** Software decode at [ImageLimits.UPLOAD_SIDE], flattened on white, JPEG at quality 85; pixels only, no EXIF. */
suspend fun toUploadJpeg(context: Context, input: ImageInput, limits: DecodeLimits = DecodeLimits()): Encoded =
    toUploadJpeg(context, input, limits, ImageDecoder::decodeBitmap, ::flatJpeg)

/** The encode runs inside the gate, so at most one decoded bitmap and one white copy exist at a time. */
internal suspend fun toUploadJpeg(
    context: Context,
    input: ImageInput,
    limits: DecodeLimits,
    decode: Decoder,
    encode: (Bitmap) -> ByteArray,
): Encoded = mapFailures(Encoded::Failed) {
    Encoded.Ok(admit(context, input, ImageLimits.UPLOAD_SIDE, software = true, limits, decode, use = encode, dispose = {}))
}

private inline fun <R> mapFailures(failed: (ImageFailure) -> R, block: () -> R): R = try {
    block()
} catch (e: Fail) {
    failed(e.reason)
} catch (e: CancellationException) {
    throw e
} catch (e: Throwable) {
    failed(ImageFailure.UNREADABLE)
}

/**
 * Starts one worker for the request and waits for it: the source deadline until the copy is done, then
 * [ImageLimits.GATE_WAIT_MS] until the worker is in the gate, then no limit. On expiry or cancellation the
 * caller abandons and leaves at once.
 */
private suspend fun <T : Any> admit(
    context: Context,
    input: ImageInput,
    longestSide: Int,
    software: Boolean,
    limits: DecodeLimits,
    decode: Decoder,
    use: (Bitmap) -> T,
    dispose: (T) -> Unit,
): T {
    if (longestSide <= 0) throw Fail(ImageFailure.UNREADABLE)
    if (input is ImageInput.Bytes && input.bytes.size > limits.maxSourceBytes) throw Fail(ImageFailure.TOO_LARGE)
    val content = input is ImageInput.Content
    // Everything that allocates comes before the slot, so only the launch sits between taking it and the worker.
    val request = Request(dispose)
    var worker: Job? = null
    var delivered = false
    var slotHeld = false // by this caller from tryAcquire until the worker starts
    if (content && !copySlots.tryAcquire()) throw Fail(ImageFailure.BUSY)
    slotHeld = content
    fun start() {
        slotHeld = false
        worker = try {
            // ATOMIC: the body starts even when cancelled first, so its finally always gives the slot back.
            @OptIn(DelicateCoroutinesApi::class)
            decodeWork.launch(start = CoroutineStart.ATOMIC) {
                request.work(context, input, longestSide, software, limits, decode, use)
            }
        } catch (e: Throwable) {
            if (content) copySlots.release()
            throw e
        }
    }
    try {
        val copied = if (content) withTimeoutOrNull(limits.sourceDeadlineMs) { start(); request.copied.await() } else start()
        if (copied == null || withTimeoutOrNull(ImageLimits.GATE_WAIT_MS) { request.admitted.await() } == null) {
            throw Fail(ImageFailure.UNREADABLE)
        }
        request.done.await()
        request.failure?.let { throw Fail(it) }
        val value = request.take() ?: throw Fail(ImageFailure.UNREADABLE)
        delivered = true
        return value
    } finally {
        // Every exit that did not deliver abandons: timeout, cancellation, failure or any other throw.
        if (!delivered) request.abandon(worker)
        if (slotHeld) copySlots.release()
    }
}

/** The worker: copy, gate, decode, use; then everything given back, then the result reported. */
private suspend fun <T : Any> Request<T>.work(
    context: Context,
    input: ImageInput,
    longestSide: Int,
    software: Boolean,
    limits: DecodeLimits,
    decode: Decoder,
    use: (Bitmap) -> T,
) {
    var temp: File? = null
    var produced: T? = null
    var failed: ImageFailure? = null
    try {
        checkWanted() // a caller that left before this worker's first step gets no provider call
        val source = if (input is ImageInput.Content) {
            if (!contentAllowed(context, input.uri)) throw Fail(ImageFailure.NOT_AN_IMAGE)
            checkWanted()
            val file = File.createTempFile("decode-", ".tmp", context.cacheDir).also { temp = it }
            checkWanted()
            val afd = context.contentResolver.openAssetFileDescriptor(input.uri, "r", signal) ?: throw Fail(ImageFailure.UNREADABLE)
            descriptor.set(afd)
            checkWanted() // published first, then checked: an abandoning caller closes it, or the finally does
            afd.createInputStream().use { copyCapped(it, file, limits.maxSourceBytes, abandoned) }
            ImageInput.Stored(file)
        } else {
            input
        }
        copied.complete(Unit)
        produced = gate.withLock {
            checkWanted() // an abandoned request starts no decode
            admitted.complete(Unit)
            val bitmap = decodeGated(source, longestSide, software, limits, decode)
            // The use step borrows the bitmap; it is recycled here unless it is the result itself (a plain decode).
            var result: T? = null
            try {
                checkWanted() // a caller that left during the decode gets no encode
                result = use(bitmap)
            } finally {
                if (result !== bitmap) bitmap.recycle()
            }
            result
        }
    } catch (e: Fail) {
        failed = e.reason
    } catch (e: Throwable) {
        failed = ImageFailure.UNREADABLE
    } finally {
        // Each step runs even when an earlier one throws; the slot and the report never depend on close or delete.
        runCatching { descriptor.getAndSet(null)?.close() }
        runCatching { temp?.delete() } // shortcut: a failed delete still returns the slot; clearDecodeTemps removes the file at the next start
        try {
            if (input is ImageInput.Content) copySlots.release()
        } finally {
            value = produced
            failure = failed
            done.complete(Unit)
            copied.complete(Unit)
            admitted.complete(Unit)
            if (abandoned.get()) disposeUnclaimed() // value published first, then checked: see Request.abandon
        }
    }
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

/** Bytes read are counted; past [max] the copy stops with TOO_LARGE. Only -1 ends the stream. */
private fun copyCapped(input: InputStream, file: File, max: Long, abandoned: AtomicBoolean) {
    file.outputStream().use { out ->
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            if (abandoned.get()) throw Fail(ImageFailure.UNREADABLE)
            val n = input.read(buffer)
            if (n == -1) break
            if (n <= 0) throw Fail(ImageFailure.UNREADABLE)
            total += n
            if (total > max) throw Fail(ImageFailure.TOO_LARGE)
            out.write(buffer, 0, n)
        }
    }
}

/** Runs on the worker while it holds the gate. The bitmap is checked against the target the header set. */
private fun Request<*>.decodeGated(
    input: ImageInput,
    longestSide: Int,
    software: Boolean,
    limits: DecodeLimits,
    decode: Decoder,
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
    if (label != null && LABELS.entries.none { it.value == format && it.key.equals(label, ignoreCase = true) }) {
        throw Fail(ImageFailure.NOT_AN_IMAGE)
    }
    val source = when (input) {
        is ImageInput.Bytes -> ImageDecoder.createSource(input.bytes)
        else -> ImageDecoder.createSource((input as ImageInput.Stored).file)
    }
    var target: Pair<Int, Int>? = null
    checkWanted() // a caller that left during the sniff gets no codec
    val bitmap = decode(source) { decoder, info, _ ->
        val width = info.size.width.toLong()
        val height = info.size.height.toLong()
        if (width <= 0 || height <= 0) throw Fail(ImageFailure.UNREADABLE)
        if (width * height > limits.maxPixels) throw Fail(ImageFailure.TOO_LARGE)
        val size = targetSize(width, height, longestSide) ?: throw Fail(ImageFailure.UNREADABLE)
        decoder.setTargetSize(size.first, size.second)
        if (software) decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        if (Build.VERSION.SDK_INT >= 36 && Build.VERSION.SDK_INT_FULL >= Build.VERSION_CODES_FULL.CINNAMON_BUN_2) {
            decoder.setAllocationLimit(ALLOCATION_LIMIT)
        }
        target = size
    }
    val size = target
    if (size == null || bitmap.width > size.first || bitmap.height > size.second ||
        (software && bitmap.config == Bitmap.Config.HARDWARE)
    ) {
        bitmap.recycle()
        throw Fail(ImageFailure.UNREADABLE)
    }
    return bitmap
}

/**
 * Longest side min(source, [side]), aspect kept, each side at least 1; Long arithmetic throughout. Null when a
 * side or [side] is 0 or less.
 */
internal fun targetSize(width: Long, height: Long, side: Int): Pair<Int, Int>? {
    if (width <= 0 || height <= 0 || side <= 0) return null
    val longest = maxOf(width, height)
    if (longest <= side) return width.toInt() to height.toInt()
    return maxOf(1L, width * side / longest).toInt() to maxOf(1L, height * side / longest).toInt()
}

/** White canvas, JPEG at quality 85. Borrows [bitmap]; recycles only its own white copy. */
internal fun flatJpeg(bitmap: Bitmap): ByteArray {
    val flat = createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
    try {
        Canvas(flat).apply {
            drawColor(Color.WHITE)
            drawBitmap(bitmap, 0f, 0f, null)
        }
        val out = ByteArrayOutputStream()
        if (!flat.compress(Bitmap.CompressFormat.JPEG, ImageLimits.JPEG_QUALITY, out)) throw Fail(ImageFailure.UNREADABLE)
        return out.toByteArray()
    } finally {
        flat.recycle()
    }
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

/**
 * The format named by the first bytes, or null. An `ftyp` box is read only when its 32-bit size is at least 16
 * and at least 16 bytes are present; its brands only within min(box size, the bytes given).
 */
internal fun sniff(head: ByteArray): ImageFormat? {
    fun at(offset: Int, vararg bytes: Int) =
        head.size >= offset + bytes.size && bytes.indices.all { head[offset + it] == bytes[it].toByte() }
    fun text(offset: Int, s: String) = at(offset, *s.map { it.code }.toIntArray())
    return when {
        at(0, 0xFF, 0xD8, 0xFF) -> ImageFormat.JPEG
        at(0, 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A) -> ImageFormat.PNG
        text(0, "GIF87a") || text(0, "GIF89a") -> ImageFormat.GIF
        text(0, "RIFF") && text(8, "WEBP") -> ImageFormat.WEBP
        head.size >= 16 && text(4, "ftyp") -> {
            val box = (0..3).fold(0L) { acc, i -> (acc shl 8) or (head[i].toLong() and 0xFF) }
            if (box < 16) return null
            val end = minOf(box, head.size.toLong()).toInt()
            // The major brand at 8, the compatible brands from 16; the minor version at 12 is skipped.
            val brands = (listOf(8) + (16..end - 4 step 4)).map { String(head, it, 4, Charsets.US_ASCII) }
            when {
                brands.any { it == "avif" || it == "avis" } -> ImageFormat.AVIF
                brands.any { it in HEIF_BRANDS } -> ImageFormat.HEIF
                else -> null
            }
        }
        else -> null
    }
}

/** Deletes temp files a killed process left behind; Task 5a calls it at container start. */
fun clearDecodeTemps(cacheDir: File) {
    cacheDir.listFiles { file -> file.name.startsWith("decode-") && file.name.endsWith(".tmp") }?.forEach { it.delete() }
}
