package xyz.rycerz.apofis.chat

import java.util.concurrent.ConcurrentHashMap

/**
 * Unsent composer text per chat, for as long as the connection lives. Written only by a user edit or by a send that
 * clears or gives back text; removed only when the text was sent or the chat was deleted. Not kept across process death
 * in this milestone.
 */
class Drafts {
    private val texts = ConcurrentHashMap<String, String>()

    fun get(sessionKey: String): String = texts[sessionKey].orEmpty()

    fun set(sessionKey: String, text: String) {
        if (text.isEmpty()) texts.remove(sessionKey) else texts[sessionKey] = text
    }

    /** Atomically replaces the draft with [change] of the current one. */
    fun update(sessionKey: String, change: (String) -> String) {
        texts.compute(sessionKey) { _, old -> change(old.orEmpty()).ifEmpty { null } }
    }

    fun remove(sessionKey: String) {
        texts.remove(sessionKey)
    }
}
