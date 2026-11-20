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

/**
 * What the composer of one chat shows. [draft] is the text field: only user edits and a send taking it change it.
 * While [sending], the field keeps the text being sent and takes no edits; it empties in the step that records the
 * text as stored.
 * [revision] changes with every change to [draft] that is not a user edit; an edit made on an older revision is
 * dropped. [unsent] is text a send could not store, shown apart until the user puts it back or discards it.
 */
data class ComposerState(val draft: String = "", val sending: Boolean = false, val unsent: UnsentText? = null, val revision: Long = 0)

/** [id] is new for every entry, a joined one included, so a confirmation for one entry never applies to another. */
data class UnsentText(val id: Long, val text: String, val error: ComposeError)

enum class ComposeError { NOT_STORED }

private fun ComposerState.idle() = draft.isEmpty() && !sending && unsent == null

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
     * Sends the draft with [store] unless a send of this chat is in flight or the draft is blank (then null). The field
     * keeps showing the text until it is stored: [store] calls [Stored.stored] once the text is durably accepted (or
     * simply returns), and only then the field empties. [store] must throw only when nothing was stored: the text then
     * leaves the field for [ComposerState.unsent], joined after any earlier unsent text with a blank line. Cancellation
     * ([clear]) lets it go.
     */
    fun send(sessionKey: String, store: suspend Stored.(String) -> Unit): Job? {
        var text: String? = null
        var generation = 0L
        book.update { b ->
            generation = b.generation
            text = null
            if (sessionKey in b.gone) return@update b
            b.with(sessionKey) { s, tick ->
                text = s.draft.takeUnless { s.sending || it.isBlank() }
                if (text == null) s else s.copy(sending = true, revision = tick) // the field drawn before takes no edit
            }
        }
        val claimed = text ?: return null
        var settled = false // guarded by the book's atomic updates below; set once
        fun settle(failed: Boolean) = book.update { b ->
            when {
                settled -> b
                b.generation != generation -> b // cleared meanwhile: nothing of it survives
                sessionKey in b.gone -> b.copy(gone = b.gone - sessionKey) // deleted meanwhile: dropped
                else -> b.with(sessionKey) { s, tick ->
                    val unsent = if (!failed) s.unsent else UnsentText(tick, s.unsent?.let { it.text + "\n\n" + claimed } ?: claimed, ComposeError.NOT_STORED)
                    s.copy(draft = "", sending = false, unsent = unsent, revision = tick)
                }
            }
        }.also { settled = true }
        return scope.launch {
            var failed = false
            try {
                Stored { settle(failed = false) }.store(claimed)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            } finally {
                settle(failed)
            }
        }
    }

    /** Puts the unsent text back: the unsent text, then a newline and the draft when the draft is not blank. */
    fun putBack(sessionKey: String) = change(sessionKey) { s, tick ->
        val unsent = s.unsent?.takeIf { !s.sending } ?: return@change s // the field holds a send in flight
        s.copy(draft = if (s.draft.isBlank()) unsent.text else unsent.text + "\n" + s.draft, unsent = null, revision = tick)
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
