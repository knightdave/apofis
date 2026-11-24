package xyz.rycerz.apofis.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import xyz.rycerz.apofis.images.ImageLimits
import xyz.rycerz.apofis.sync.SendTooLarge

/**
 * What the composer of one chat shows. [draft] is the text field: only user edits and a send taking it change it.
 * While [sending], the field keeps the text being sent and takes no edits; it empties in the step that records the
 * text as stored.
 * [revision] changes with every change to [draft] that is not a user edit; an edit made on an older revision is
 * dropped. [unsent] is text a send could not store, shown apart until the user puts it back or discards it.
 * [images] are the draft's photos in pick order; [note] is the one note of this chat, the newest set.
 */
data class ComposerState(
    val draft: String = "",
    val sending: Boolean = false,
    val unsent: UnsentText? = null,
    val revision: Long = 0,
    val images: List<DraftImage> = emptyList(),
    val note: ComposerNote? = null,
)

/** A draft photo: [digest] null while it is processing, then its file in the image store, or [failed]. */
data class DraftImage(val id: Long, val digest: String?, val failed: Boolean = false) {
    val processing: Boolean get() = digest == null && !failed
}

/**
 * [id] is new for every entry, a joined one included, so a confirmation for one entry never applies to another.
 * [images] are the digests the send could not store with the text.
 */
data class UnsentText(val id: Long, val text: String, val error: ComposeError, val images: List<String> = emptyList())

enum class ComposeError { NOT_STORED }

data class ComposerNote(val kind: NoteKind, val arg: String? = null)

enum class NoteKind {
    REMOVED_MAY_HAVE_SENT, CANCEL_TOO_LATE, IMAGE_FAILED, IMAGES_LIMIT, IMAGES_TOO_LARGE, SHARE_TOO_MANY, SHARE_IMAGES_FAILED,
    SHARE_TEXT_CUT, CMD_UNKNOWN, CMD_NO_TEXT, CMD_NOTHING_RUNNING, CMD_NEEDS_TEXT, CMD_STEER_NO_IMAGES, CMD_TITLE_REFUSED, FAST_ON,
    FAST_OFF, TEMPORARY_NOT_DELETED,
}

private fun ComposerState.idle() = draft.isEmpty() && !sending && unsent == null && images.isEmpty() && note == null

/** Given to a send's store: [stored] says the text is durably accepted, so the field may empty now. */
fun interface Stored {
    fun stored()
}

/** A deleted chat whose send is still in flight: no edit applies to it. */
private val GONE = ComposerState(revision = -1)

/**
 * [rested]: the revision of an idle chat, kept so the empty field the UI drew stays valid. A chat in neither map gets
 * a fresh revision when it is first observed or changed, so no revision of an earlier life of its key applies again.
 * [gone]: deleted chats whose send is still in flight; each is forgotten when that send ends. [counter] issues
 * revisions and entry ids, only ever increasing. [generation] changes when everything is cleared: a send of an older
 * generation changes nothing when it ends.
 */
private data class Book(
    val entries: Map<String, ComposerState> = emptyMap(),
    val rested: Map<String, Long> = emptyMap(),
    val gone: Set<String> = emptySet(),
    val counter: Long = 0,
    val generation: Long = 0,
) {
    fun of(sessionKey: String): ComposerState? = entries[sessionKey] ?: rested[sessionKey]?.let { ComposerState(revision = it) }
}

/**
 * The composer of every chat of the cached server, by session key: the draft, the send being stored and text that
 * could not be stored. One per app, like the cache: emptied only together with the cached tables ([clear]). Sends run
 * in [scope]. Only entries with something to show are kept, so an idle chat takes only its revision (dropped by
 * [retain] once the chat is gone) and text is never evicted. Not kept across process death in this milestone.
 */
class Composer(private val scope: CoroutineScope) {
    private val book = MutableStateFlow(Book())

    /** A chat without a record (new, removed, pruned or cleared) is given a fresh revision as soon as it is observed. */
    fun state(sessionKey: String): Flow<ComposerState> = book.map { it.of(sessionKey) ?: current(sessionKey) }.distinctUntilChanged()

    fun current(sessionKey: String): ComposerState {
        book.update { b ->
            if (b.of(sessionKey) != null || sessionKey in b.gone) b else b.copy(rested = b.rested + (sessionKey to b.counter + 1), counter = b.counter + 1)
        }
        return book.value.of(sessionKey) ?: GONE
    }

    /** Keys this composer remembers, deleted chats with a send in flight included; for tests. */
    internal val keys: Set<String> get() = book.value.let { it.entries.keys + it.gone }

    /** Chats held only for their revision; for tests. */
    internal val idleKeys: Set<String> get() = book.value.rested.keys

    /** Forgets the revision of every idle chat not in [sessionKeys]; chats with text or a send in flight stay. */
    fun retain(sessionKeys: Set<String>) = book.update { b -> b.copy(rested = b.rested.filterKeys { it in sessionKeys }) }

    /** The scope's job; for tests. */
    internal val job: Job get() = scope.coroutineContext.job

    /** A user edit on the field drawn from [basedOnRevision]; dropped when the draft was changed otherwise since. */
    fun edit(sessionKey: String, text: String, basedOnRevision: Long) =
        change(sessionKey) { s, _ -> if (s.revision == basedOnRevision && !s.sending) s.copy(draft = text) else s }

    /**
     * Sends the draft and its ready images with [store] unless a send of this chat is in flight (then null): [store] gets
     * the text and their digests (failed photos are left out). Nothing starts while a photo is processing, or when the
     * text is blank and no photo is ready. The field keeps showing the text until it is stored: [store] calls
     * [Stored.stored] once the text is durably accepted (or simply returns), and only then the field empties. [store]
     * must throw only when nothing was stored: the text then leaves the field for [ComposerState.unsent], joined after any
     * earlier unsent text with a blank line. Cancellation ([clear]) lets it go. [SendTooLarge] keeps the draft as it is
     * and sets the note [NoteKind.IMAGES_TOO_LARGE]. The photos leave the draft with the text: into
     * [ComposerState.unsent] with it when the store fails. Starting a send clears the note.
     */
    fun sendWithImages(sessionKey: String, store: suspend Stored.(String, List<String>) -> Unit): Job? =
        start(sessionKey, { s -> s.images.none { it.processing } }, store)

    private fun start(sessionKey: String, allowed: (ComposerState) -> Boolean, store: suspend Stored.(String, List<String>) -> Unit): Job? {
        var text: String? = null
        var claimed: List<DraftImage> = emptyList() // every photo of the draft now, failed ones included: they leave with the send
        var generation = 0L
        book.update { b ->
            generation = b.generation
            text = null
            if (sessionKey in b.gone) return@update b
            b.with(sessionKey) { s, tick ->
                claimed = s.images
                // More photos than one message may carry (put back onto a draft): refused until some are removed.
                if (!s.sending && allowed(s) && s.images.count { it.digest != null } > ImageLimits.MAX_IMAGES) {
                    return@with s.copy(note = ComposerNote(NoteKind.IMAGES_LIMIT))
                }
                text = s.draft.takeIf { !s.sending && allowed(s) && (it.isNotBlank() || s.images.any { i -> i.digest != null }) }
                if (text == null) s else s.copy(sending = true, revision = tick, note = null) // the field drawn before takes no edit
            }
        }
        val claimedText = text ?: return null
        val digests = claimed.mapNotNull { it.digest }
        val ids = claimed.mapTo(HashSet()) { it.id }
        var settled = false // guarded by the book's atomic updates below; set once
        fun settle(failed: Boolean, tooLarge: Boolean = false) = book.update { b ->
            when {
                settled -> b
                b.generation != generation -> b // cleared meanwhile: nothing of it survives
                sessionKey in b.gone -> b.copy(gone = b.gone - sessionKey) // deleted meanwhile: dropped
                else -> b.with(sessionKey) { s, tick ->
                    if (tooLarge) return@with s.copy(sending = false, revision = tick, note = ComposerNote(NoteKind.IMAGES_TOO_LARGE))
                    val unsent = if (!failed) s.unsent else UnsentText(
                        tick, listOfNotNull(s.unsent?.text, claimedText).filter { it.isNotBlank() }.joinToString("\n\n"),
                        ComposeError.NOT_STORED, s.unsent?.images.orEmpty() + digests,
                    )
                    // Photos added while it was stored stay in the draft.
                    s.copy(draft = "", sending = false, unsent = unsent, revision = tick, images = s.images.filterNot { it.id in ids })
                }
            }
        }.also { settled = true }
        return scope.launch {
            var failed = false
            var tooLarge = false
            try {
                Stored { settle(failed = false) }.store(claimedText, digests)
            } catch (e: CancellationException) {
                throw e
            } catch (e: SendTooLarge) {
                tooLarge = true
            } catch (_: Exception) {
                failed = true
            } finally {
                settle(failed, tooLarge)
            }
        }
    }

    /**
     * Adds [count] processing photos to the draft, as many as fit under [ImageLimits.MAX_IMAGES]; returns their ids in
     * order. When some did not fit, the note [NoteKind.IMAGES_LIMIT] is set. Nothing is added once [live] is false.
     */
    fun addImages(sessionKey: String, count: Int, live: () -> Boolean = { true }): List<Long> {
        var ids: List<Long> = emptyList()
        book.update { b ->
            ids = emptyList()
            // [live] is read inside the update: a clear that lands first makes it retry and see the closed connection.
            if (sessionKey in b.gone || count <= 0 || !live()) return@update b
            val fits = (ImageLimits.MAX_IMAGES - (b.of(sessionKey)?.images?.size ?: 0)).coerceIn(0, count)
            ids = List(fits) { b.counter + 1 + it }
            b.copy(counter = b.counter + fits).with(sessionKey) { s, _ ->
                s.copy(images = s.images + ids.map { DraftImage(it, null) }, note = if (fits < count) ComposerNote(NoteKind.IMAGES_LIMIT) else s.note)
            }
        }
        return ids
    }

    /**
     * Sets the processing photo [id], under whichever chat holds it: ready with [digest], or failed when null (with the
     * note [NoteKind.IMAGE_FAILED]). A photo no longer processing, removed or cleared is left alone.
     */
    fun setImage(id: Long, digest: String?) = book.update { b ->
        val key = b.entries.entries.firstOrNull { (_, s) -> s.images.any { it.id == id && it.processing } }?.key ?: return@update b
        b.with(key) { s, _ ->
            s.copy(
                images = s.images.map { if (it.id == id) it.copy(digest = digest, failed = digest == null) else it },
                note = if (digest == null) ComposerNote(NoteKind.IMAGE_FAILED) else s.note,
            )
        }
    }

    /** Ignored while a send is in flight: its photos stay referenced until it is stored or fails, like the field. */
    fun removeImage(sessionKey: String, id: Long) =
        change(sessionKey) { s, _ -> if (s.sending) s else s.copy(images = s.images.filterNot { it.id == id }) }

    fun setNote(sessionKey: String, note: ComposerNote?) = change(sessionKey) { s, _ -> s.copy(note = note) }

    /** Every digest the composer still needs: ready draft photos and unsent photos of every chat. */
    fun referencedImages(): Set<String> = book.value.entries.values.flatMapTo(HashSet()) { s ->
        s.images.mapNotNull { it.digest } + s.unsent?.images.orEmpty()
    }

    /**
     * Puts the unsent text back: the unsent text, then a newline and the draft when neither is blank; its photos go
     * before the draft's.
     */
    fun putBack(sessionKey: String) = book.update { b ->
        val s = b.entries[sessionKey]
        val unsent = s?.unsent?.takeIf { !s.sending && sessionKey !in b.gone } ?: return@update b // the field holds a send in flight
        val images = unsent.images.mapIndexed { i, d -> DraftImage(b.counter + 1 + i, d) }
        b.copy(counter = b.counter + images.size).with(sessionKey) { _, tick ->
            val draft = when {
                unsent.text.isBlank() -> s.draft
                s.draft.isBlank() -> unsent.text
                else -> unsent.text + "\n" + s.draft
            }
            s.copy(draft = draft, unsent = null, revision = tick, images = images + s.images)
        }
    }

    /** Discards the unsent entry [id], the one the user agreed to discard; any other entry stays. */
    fun discard(sessionKey: String, id: Long) = change(sessionKey) { s, _ -> if (s.unsent?.id == id) s.copy(unsent = null) else s }

    /** The chat was deleted with the user's confirmation: its entry goes, and a send failing later brings nothing back. */
    fun remove(sessionKey: String) = book.update { b ->
        val inFlight = b.entries[sessionKey]?.sending == true
        b.copy(entries = b.entries - sessionKey, rested = b.rested - sessionKey, gone = if (inFlight) b.gone + sessionKey else b.gone)
    }

    /** Waits for the sends in flight now. */
    internal suspend fun settle() = job.children.toList().joinAll()

    /** The cached tables are being cleared: every entry goes and every send still in flight is cancelled. */
    internal fun clear() {
        book.update { Book(counter = it.counter, generation = it.generation + 1) }
        job.children.forEach { it.cancel() }
    }

    private fun change(sessionKey: String, f: (ComposerState, Long) -> ComposerState) = book.update { b ->
        if (sessionKey in b.gone) b else b.with(sessionKey, f)
    }

    /** [f] gets the entry and a fresh number for a revision or id. */
    private fun Book.with(sessionKey: String, f: (ComposerState, Long) -> ComposerState): Book {
        val tick = counter + 1
        val next = f(of(sessionKey) ?: ComposerState(revision = tick), tick) // no record: a revision no edit was based on
        if (!next.idle()) return copy(entries = entries + (sessionKey to next), rested = rested - sessionKey, counter = tick)
        // Idle: dropped, but on the revision it had, so an edit of the field drawn from it still applies.
        return copy(entries = entries - sessionKey, rested = rested + (sessionKey to next.revision), counter = tick)
    }
}
