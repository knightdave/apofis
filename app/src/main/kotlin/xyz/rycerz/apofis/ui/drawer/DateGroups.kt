package xyz.rycerz.apofis.ui.drawer

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.sync.isTemporaryKey

sealed interface DrawerRow {
    data class Header(val label: GroupLabel) : DrawerRow

    /** [title] is empty for an untitled chat, shown as "New chat". */
    data class Chat(val key: String, val title: String, val selected: Boolean, val running: Boolean, val needsInput: Boolean) : DrawerRow
}

sealed interface GroupLabel {
    data object Pinned : GroupLabel
    data object Today : GroupLabel
    data object Yesterday : GroupLabel
    data class Date(val epochDay: Long) : GroupLabel
}

/**
 * The drawer list: a Pinned group, then one group per local date of the last activity, newest first. [sessions] is the
 * non-archived list; [query] keeps the chats whose title contains it (case-insensitive) and the headers of their groups.
 */
fun groupSessions(sessions: List<SessionEntity>, query: String, selectedKey: String?, today: LocalDate, zone: ZoneId): List<DrawerRow> {
    val q = query.trim()
    val shown = sessions
        .filter { (q.isEmpty() || it.title.orEmpty().contains(q, ignoreCase = true)) && !(it.key == selectedKey && isTemporaryKey(it.key)) }
        .sortedByDescending { it.lastActiveAt ?: it.startedAt }
    val groups = shown.groupBy { s ->
        if (s.pinned) {
            GroupLabel.Pinned
        } else {
            val day = Instant.ofEpochMilli(((s.lastActiveAt ?: s.startedAt) * 1000).toLong()).atZone(zone).toLocalDate()
            when (day) {
                today -> GroupLabel.Today
                today.minusDays(1) -> GroupLabel.Yesterday
                else -> GroupLabel.Date(day.toEpochDay())
            }
        }
    }
    // groupBy keeps first-seen order, which is newest first; only Pinned has to move to the top.
    val ordered = groups.entries.sortedBy { if (it.key == GroupLabel.Pinned) 0 else 1 }
    return ordered.flatMap { (label, chats) ->
        listOf(DrawerRow.Header(label)) + chats.map { DrawerRow.Chat(it.key, it.title.orEmpty(), it.key == selectedKey, running = false, needsInput = false) }
    }
}
