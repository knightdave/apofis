package xyz.rycerz.apofis.share

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.IntentCompat
import xyz.rycerz.apofis.BuildConfig
import xyz.rycerz.apofis.chat.NoteKind
import xyz.rycerz.apofis.images.Encoded
import xyz.rycerz.apofis.images.ImageFailure
import xyz.rycerz.apofis.images.ImageInput
import xyz.rycerz.apofis.images.ImageLimits
import xyz.rycerz.apofis.images.toUploadJpeg
import xyz.rycerz.apofis.ui.text.graphemeCut

/** The longest shared text kept; the rest is cut at a character boundary ([NoteKind.SHARE_TEXT_CUT]). */
const val MAX_SHARE_TEXT = 100_000

/**
 * A share read into memory and waiting for Accept: [images] are re-encoded JPEGs, [notes] only the three SHARE_ kinds.
 * [version] is the container's slot version (0 until it is offered).
 */
data class Share(val version: Long, val text: String, val images: List<ByteArray>, val notes: Set<NoteKind>)

/** The one share note shown, sheet and draft alike: the first of these the share has. */
val SHARE_NOTE_ORDER = listOf(NoteKind.SHARE_TOO_MANY, NoteKind.SHARE_IMAGES_FAILED, NoteKind.SHARE_TEXT_CUT)

sealed interface ShareIntent {
    data class Text(val text: String) : ShareIntent

    /** [uris]: only URIs that carry their read grant; [refused]: the others, never opened. */
    data class Images(val uris: List<Uri>, val refused: Int, val caption: String) : ShareIntent
}

/**
 * One of the declared combinations, or null: `SEND text/plain` (EXTRA_TEXT only), `SEND image/...` (one stream and an
 * optional caption), `SEND_MULTIPLE image/...` (a stream list and an optional caption). The stream is EXTRA_STREAM, or
 * the ClipData items when it is absent; a present stream that is no URI (or no list of URIs) is no share. A URI is taken
 * only with FLAG_GRANT_READ_URI_PERMISSION and only when it is an item of the ClipData, where the sender's grant travels;
 * any other is refused. Only those two extras are read, and none for an action and type that are no share.
 */
fun validateShare(intent: Intent): ShareIntent? {
    val action = intent.action
    val type = intent.type ?: return null
    // The pair is decided first: nothing of an intent that is no share is read.
    val text = action == Intent.ACTION_SEND && type == "text/plain"
    val images = (action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE) && type.startsWith("image/") && type.length > "image/".length
    if (!text && !images) return null
    return try {
        val words = intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString().orEmpty()
        if (text) return ShareIntent.Text(words).takeIf { words.isNotEmpty() }
        val clipped = intent.clipData?.let { clip -> (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri } }.orEmpty()
        // The ClipData is the stream only when EXTRA_STREAM is absent; a present one that is no URI (or no list of
        // URIs) makes the intent no share.
        val stream = when {
            !intent.hasExtra(Intent.EXTRA_STREAM) -> if (action == Intent.ACTION_SEND) clipped.take(1) else clipped
            action == Intent.ACTION_SEND -> listOf(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return null)
            else -> {
                val list: List<Any?> = IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java) ?: return null
                if (list.any { it != null && it !is Uri }) return null // the element type is not checked by every getter
                list.filterIsInstance<Uri>()
            }
        }
        if (stream.isEmpty()) return null
        val granted = intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0
        val clipSet = clipped.toHashSet() // one lookup per stream URI
        val taken = stream.filter { granted && it in clipSet }
        ShareIntent.Images(taken, stream.size - taken.size, words)
    } catch (_: RuntimeException) {
        null // an extra that cannot be unpacked
    }
}

/**
 * Reads [share] into memory: the text cut at [MAX_SHARE_TEXT], at most [ImageLimits.MAX_IMAGES] images (the rest are
 * [NoteKind.SHARE_TOO_MANY]), one at a time, each re-encoded at once and kept only while all kept images stay within
 * [maxBytes]. Nothing is written but each decode's own temp file, which is gone when it ends.
 */
suspend fun readShare(context: Context, share: ShareIntent, maxBytes: Long = ImageLimits.MAX_SHARE_BYTES): Share {
    val notes = HashSet<NoteKind>()
    val images = ArrayList<ByteArray>()
    val text = when (share) {
        is ShareIntent.Text -> share.text
        is ShareIntent.Images -> {
            repeat(share.refused) { failed(notes, ImageFailure.UNREADABLE) }
            if (share.uris.size > ImageLimits.MAX_IMAGES) notes += NoteKind.SHARE_TOO_MANY
            var kept = 0L
            for (uri in share.uris.take(ImageLimits.MAX_IMAGES)) {
                when (val r = toUploadJpeg(context, ImageInput.Content(uri))) {
                    is Encoded.Failed -> failed(notes, r.reason) // BUSY included: no retry, as for picked photos
                    is Encoded.Ok -> if (r.jpeg.size <= maxBytes - kept) {
                        images += r.jpeg
                        kept += r.jpeg.size
                    } else {
                        failed(notes, ImageFailure.TOO_LARGE)
                    }
                }
            }
            share.caption
        }
    }
    val cut = text.substring(0, graphemeCut(text, 0, MAX_SHARE_TEXT, up = false))
    if (cut.length != text.length) notes += NoteKind.SHARE_TEXT_CUT
    return Share(0, cut, images, notes)
}

private fun failed(notes: MutableSet<NoteKind>, reason: ImageFailure) {
    notes += NoteKind.SHARE_IMAGES_FAILED
    if (BuildConfig.DEBUG) Log.i("ApofisShare", "image ${reason.name}") // the reason only: no URI, size or digest
}
