package xyz.rycerz.apofis.chat

import java.util.concurrent.ConcurrentHashMap
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

/** What the composer of one chat shows. [error]: the last send stored nothing and its text is back in [draft]. */
data class ComposerState(val draft: String = "", val sending: Boolean = false, val error: ComposeError? = null)

enum class ComposeError { NOT_STORED }

private val IDLE = ComposerState()

/**
 * The composer of every chat of one server, by session key: the unsent text, the send being stored and its failure.
 * Outlives the chat view models, and a new key for the same server; dropped when the server changes. Sends run in
 * [scope]. Only entries with something to show are kept, so an idle chat takes no memory and text is never evicted.
 * Not kept across process death in this milestone.
 */
class Composer(private val scope: CoroutineScope) {
    private val entries = MutableStateFlow<Map<String, ComposerState>>(emptyMap())
    private val gone: MutableSet<String> = ConcurrentHashMap.newKeySet() // deleted chats: nothing may recreate them

    fun state(sessionKey: String): Flow<ComposerState> = entries.map { it[sessionKey] ?: IDLE }.distinctUntilChanged()

    fun current(sessionKey: String): ComposerState = entries.value[sessionKey] ?: IDLE

    /** Keys with an entry; for tests. */
    internal val keys: Set<String> get() = entries.value.keys

    /** A user edit: replaces the draft and clears the last failure. */
    fun edit(sessionKey: String, text: String) = change(sessionKey) { it.copy(draft = text, error = null) }

    /**
     * Sends the draft with [store] unless a send of this chat is in flight or the draft is blank (then null). [store]
     * must throw only when nothing was stored: the text then goes back before whatever was typed since. Cancellation
     * (the server changed) drops it.
     */
    fun send(sessionKey: String, store: suspend (String) -> Unit): Job? {
        var text: String? = null
        change(sessionKey) { s ->
            text = s.draft.takeUnless { s.sending || it.isBlank() }
            if (text == null) s else s.copy(draft = "", sending = true, error = null)
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
                change(sessionKey) { s ->
                    if (!failed) s.copy(sending = false)
                    else s.copy(draft = if (s.draft.isEmpty()) claimed else claimed + "\n" + s.draft, sending = false, error = ComposeError.NOT_STORED)
                }
            }
        }
    }

    /** The chat was deleted with the user's confirmation: its entry goes, and a send failing later brings nothing back. */
    fun remove(sessionKey: String) {
        gone += sessionKey
        entries.update { it - sessionKey }
    }

    /** Waits for the sends in flight now. */
    internal suspend fun settle() = scope.coroutineContext.job.children.toList().joinAll()

    internal fun close() = scope.cancel()

    private fun change(sessionKey: String, f: (ComposerState) -> ComposerState) = entries.update { m ->
        val next = if (sessionKey in gone) IDLE else f(m[sessionKey] ?: IDLE)
        if (next == IDLE) m - sessionKey else m + (sessionKey to next)
    }
}
