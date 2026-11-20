package xyz.rycerz.apofis.chat

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
 * [unsent] is text a send could not store, shown apart until the user puts it back or discards it.
 */
data class ComposerState(val draft: String = "", val sending: Boolean = false, val unsent: UnsentText? = null)

data class UnsentText(val text: String, val error: ComposeError)

enum class ComposeError { NOT_STORED }

private val IDLE = ComposerState()

/** [gone]: deleted chats whose send is still in flight; each is forgotten when that send ends. */
private data class Book(val entries: Map<String, ComposerState> = emptyMap(), val gone: Set<String> = emptySet())

/**
 * The composer of every chat of one server, by session key: the draft, the send being stored and text that could not
 * be stored. Lives as long as its server stays the saved one, across connections; sends run in [scope], which closing
 * the composer cancels. Only entries with something to show are kept, so an idle chat takes no memory and text is
 * never evicted. Not kept across process death in this milestone.
 */
class Composer(private val scope: CoroutineScope) {
    private val book = MutableStateFlow(Book())

    fun state(sessionKey: String): Flow<ComposerState> = book.map { it.entries[sessionKey] ?: IDLE }.distinctUntilChanged()

    fun current(sessionKey: String): ComposerState = book.value.entries[sessionKey] ?: IDLE

    /** Keys this composer remembers, deleted chats with a send in flight included; for tests. */
    internal val keys: Set<String> get() = book.value.let { it.entries.keys + it.gone }

    /** The scope's job; for tests. */
    internal val job: Job get() = scope.coroutineContext.job

    /** A user edit: replaces the draft only. */
    fun edit(sessionKey: String, text: String) = change(sessionKey) { it.copy(draft = text) }

    /**
     * Sends the draft with [store] unless a send of this chat is in flight or the draft is blank (then null). [store]
     * must throw only when nothing was stored: the text then becomes [ComposerState.unsent], joined after any earlier
     * unsent text with a blank line. Cancellation (the composer was closed) lets it go.
     */
    fun send(sessionKey: String, store: suspend (String) -> Unit): Job? {
        var text: String? = null
        change(sessionKey) { s ->
            text = s.draft.takeUnless { s.sending || it.isBlank() }
            if (text == null) s else s.copy(draft = "", sending = true)
        }
        val claimed = text ?: return null
        return scope.launch {
            var failed = false
            try {
                store(claimed)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                failed = true
            } finally {
                book.update { b ->
                    if (sessionKey in b.gone) return@update b.copy(gone = b.gone - sessionKey) // deleted meanwhile: dropped
                    b.with(sessionKey) { s ->
                        val unsent = if (!failed) s.unsent else UnsentText(s.unsent?.let { it.text + "\n\n" + claimed } ?: claimed, ComposeError.NOT_STORED)
                        s.copy(sending = false, unsent = unsent)
                    }
                }
            }
        }
    }

    /** Puts the unsent text back: the unsent text, then a newline and the draft when the draft is not blank. */
    fun putBack(sessionKey: String) = change(sessionKey) { s ->
        val unsent = s.unsent ?: return@change s
        s.copy(draft = if (s.draft.isBlank()) unsent.text else unsent.text + "\n" + s.draft, unsent = null)
    }

    /** Discards the unsent text, only if it is still exactly [text], the text the user agreed to discard. */
    fun discard(sessionKey: String, text: String) = change(sessionKey) { if (it.unsent?.text == text) it.copy(unsent = null) else it }

    /** The chat was deleted with the user's confirmation: its entry goes, and a send failing later brings nothing back. */
    fun remove(sessionKey: String) = book.update { b ->
        val inFlight = b.entries[sessionKey]?.sending == true
        Book(b.entries - sessionKey, if (inFlight) b.gone + sessionKey else b.gone)
    }

    /** Waits for the sends in flight now. */
    internal suspend fun settle() = job.children.toList().joinAll()

    internal fun close() = scope.cancel()

    private fun change(sessionKey: String, f: (ComposerState) -> ComposerState) = book.update { b ->
        if (sessionKey in b.gone) b else b.with(sessionKey, f)
    }

    private fun Book.with(sessionKey: String, f: (ComposerState) -> ComposerState): Book {
        val next = f(entries[sessionKey] ?: IDLE)
        return copy(entries = if (next == IDLE) entries - sessionKey else entries + (sessionKey to next))
    }
}
