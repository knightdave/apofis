package xyz.rycerz.apofis.e2e

import android.content.ClipData
import android.content.ContentProvider
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream

// The app's decode limits, copied from app/src/main/kotlin/xyz/rycerz/apofis/images/Decode.kt:34-35 (ImageLimits);
// tools/emulator/run-e2e-selftest.sh fails when the two copies differ.
const val MAX_SOURCE_BYTES = 25L * 1024 * 1024
const val MAX_PIXELS = 64_000_000L

const val FIXTURES = "xyz.rycerz.apofis.e2e.fixtures"
const val SMALL = "small" // 640 x 480, for the caption shares
const val OVER = "8001x8000" // 64,008,000 pixels: one row past the pixel cap
const val AT = "8000x8000" // 64,000,000 pixels: exactly the cap

/**
 * Serves three generated PNGs to the app, read only, by URI grant: non-exported (manifest), so only a granted reader can
 * open them. Each file is written once to this APK's cache dir. The app opens them with openAssetFileDescriptor, which
 * reaches [openFile]; everything else is a stub.
 */
class FixtureImageProvider : ContentProvider() {
    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("read only")
        val (w, h) = SIZES[uri.lastPathSegment] ?: throw FileNotFoundException("no such fixture")
        val dir = context?.cacheDir ?: throw FileNotFoundException("no cache dir")
        val file = File(dir, "fixture-${w}x$h.png")
        synchronized(this) {
            if (!file.exists()) {
                // Written beside it and renamed, so a half-written file is never served.
                val temp = File(dir, "${file.name}.tmp")
                temp.outputStream().use { writeSolidPng(it, w, h) }
                if (!temp.renameTo(file)) throw FileNotFoundException("fixture not written")
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/png"
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, args: Array<out String>?, sort: String?) = null
    override fun insert(uri: Uri, values: ContentValues?) = null
    override fun update(uri: Uri, values: ContentValues?, selection: String?, args: Array<out String>?) = 0
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0

    companion object {
        val SIZES = mapOf(SMALL to (640 to 480), OVER to (8001 to 8000), AT to (8000 to 8000))
    }
}

fun fixtureUri(name: String): Uri = Uri.parse("content://$FIXTURES/$name")

/**
 * A valid solid black [w] x [h] PNG: grayscale at bit depth 1, then the signature, IHDR, one IDAT and IEND, each chunk
 * with its CRC32. The rows (a 0 filter byte and ceil(w / 8) zero bytes each) are deflated as they are made into a small
 * buffer (the chunk length comes before the data; 8 MB of rows deflate to about 8 KB). No Bitmap and no pixel buffer.
 */
fun writeSolidPng(out: OutputStream, w: Int, h: Int) {
    val data = DataOutputStream(out)
    data.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10))
    // Width, height, bit depth 1, colour type 0 (grayscale), deflate, adaptive filtering, no interlace.
    chunk(data, "IHDR", ByteBuffer.allocate(13).putInt(w).putInt(h).put(1).put(0).put(0).put(0).put(0).array())
    val idat = ByteArrayOutputStream()
    DeflaterOutputStream(idat).use { z ->
        val row = ByteArray(1 + (w + 7) / 8)
        repeat(h) { z.write(row) }
    }
    chunk(data, "IDAT", idat.toByteArray())
    chunk(data, "IEND", ByteArray(0))
    data.flush()
}

private fun chunk(out: DataOutputStream, type: String, body: ByteArray) {
    val t = type.toByteArray(Charsets.US_ASCII)
    out.writeInt(body.size)
    out.write(t)
    out.write(body)
    out.writeInt(CRC32().apply { update(t); update(body) }.value.toInt())
}

/**
 * Fixture [name] as this process reads it through its own resolver: its byte size and the width and height its IHDR
 * states. Fails unless it is a PNG whose first chunk is IHDR.
 */
fun AppDriver.fixtureHeader(name: String): Triple<Long, Int, Int> =
    instrumentation.context.contentResolver.openFileDescriptor(fixtureUri(name), "r")!!.use { fd ->
        DataInputStream(ParcelFileDescriptor.AutoCloseInputStream(fd.dup())).use { input ->
            val head = ByteArray(16).also(input::readFully)
            if (head[1] != 'P'.code.toByte() || String(head, 12, 4, Charsets.US_ASCII) != "IHDR") fail("Fixture $name is not a PNG")
            Triple(fd.statSize, input.readInt(), input.readInt())
        }
    }

/**
 * Shares fixture [name] (and [caption], when given) with the app as another app's share sheet would: ACTION_SEND to the
 * app's package, EXTRA_STREAM and the same URI in ClipData with the read grant (the app takes only ClipData items of an
 * intent carrying the grant), started from this process.
 */
fun AppDriver.share(name: String, caption: String? = null) {
    val uri = fixtureUri(name)
    val intent = Intent(Intent.ACTION_SEND).setType("image/png").setPackage(APP).putExtra(Intent.EXTRA_STREAM, uri)
    if (caption != null) intent.putExtra(Intent.EXTRA_TEXT, caption)
    intent.clipData = ClipData.newRawUri("", uri)
    intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    instrumentation.context.startActivity(intent)
    log("shared fixture $name${if (caption != null) " with a caption" else ""}")
}

/**
 * Waits for the share sheet and checks what it shows: the caption (when given, as the preview; none otherwise), the
 * image count ([images], not shown for 0), the could-not-be-read note ([failed]) and Accept ([accept]); Cancel always.
 * By visible text: the sheet is a window of its own.
 */
fun AppDriver.awaitShareSheet(caption: String?, images: Int, failed: Boolean, accept: Boolean) {
    waitFor("the share sheet", 60_000) { has(str("share_title")) && has(str("share_cancel")) }
    val shown = mapOf(
        "the caption" to (caption?.let(::has) ?: true),
        "the image count" to (if (images > 0) has(str("share_images_count", images)) else !has(str("share_images_count", 1))),
        "the could-not-be-read note" to (has(str("share_images_failed")) == failed),
        "Accept" to (has(str("share_accept")) == accept),
    )
    shown.filterValues { !it }.keys.takeIf { it.isNotEmpty() }?.let { fail("The share sheet does not show as expected: $it. Screen: ${screen()}") }
    log("share sheet: images $images, note $failed, accept $accept")
}

/** Cancels the share sheet and waits until it is gone. */
fun AppDriver.cancelShare() {
    click(str("share_cancel"))
    waitFor("the share sheet gone", 10_000) { !has(str("share_title")) }
}
