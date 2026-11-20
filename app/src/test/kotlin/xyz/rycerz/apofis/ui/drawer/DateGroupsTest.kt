package xyz.rycerz.apofis.ui.drawer

import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Test
import xyz.rycerz.apofis.db.SessionEntity

class DateGroupsTest {
    // A zone far from UTC, so a test that ignored the zone would put the late-evening chat on the wrong day.
    private val zone = ZoneId.of("America/Los_Angeles")
    private val today = LocalDate.of(2026, 3, 10)

    private fun at(date: LocalDate, hour: Int, minute: Int = 0) =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, minute)).atZone(zone).toEpochSecond().toDouble()

    private fun session(key: String, title: String?, active: Double?, pinned: Boolean = false, started: Double = 0.0) = SessionEntity(
        key = key, currentId = key, lineageIds = "", title = title, pinned = pinned, archived = false, startedAt = started,
        lastActiveAt = active, messageCount = 1, preview = null, stale = false, existsOnServer = true,
    )

    private fun chat(key: String, title: String, selected: Boolean = false) = DrawerRow.Chat(key, title, selected, running = false, needsInput = false)

    @Test
    fun pinnedFirst() {
        val rows = groupSessions(
            listOf(
                session("apofis-new", "Weekly groceries", at(today, 9)),
                session("apofis-old", "Tax return notes", at(today.minusDays(40), 12), pinned = true),
            ),
            query = "",
            selectedKey = "apofis-new",
            today = today,
            zone = zone,
        )
        assertEquals(
            listOf(
                DrawerRow.Header(GroupLabel.Pinned),
                chat("apofis-old", "Tax return notes"),
                DrawerRow.Header(GroupLabel.Today),
                chat("apofis-new", "Weekly groceries", selected = true),
            ),
            rows,
        )
    }

    @Test
    fun todayYesterdayOlder() {
        val older = today.minusDays(9)
        val rows = groupSessions(
            listOf(
                session("apofis-c", "Bike repair", at(older, 12)),
                session("apofis-b", "Late night idea", at(today.minusDays(1), 23, 59)),
                session("apofis-a", "Morning plan", at(today, 0, 1)),
                session("apofis-d", "Started only", active = null, started = at(today, 8)),
            ),
            query = "",
            selectedKey = null,
            today = today,
            zone = zone,
        )
        assertEquals(
            listOf(
                DrawerRow.Header(GroupLabel.Today),
                chat("apofis-d", "Started only"),
                chat("apofis-a", "Morning plan"),
                DrawerRow.Header(GroupLabel.Yesterday),
                chat("apofis-b", "Late night idea"),
                DrawerRow.Header(GroupLabel.Date(older.toEpochDay())),
                chat("apofis-c", "Bike repair"),
            ),
            rows,
        )
    }

    @Test
    fun searchKeepsGroups() {
        val rows = groupSessions(
            listOf(
                session("apofis-a", "Trip plans", at(today, 10)),
                session("apofis-b", "Recipes", at(today, 9)),
                session("apofis-c", "Plant care", at(today.minusDays(3), 9)),
                session("apofis-d", "Untitled", active = at(today, 8)).copy(title = null),
            ),
            query = "PLAN",
            selectedKey = null,
            today = today,
            zone = zone,
        )
        assertEquals(
            listOf(
                DrawerRow.Header(GroupLabel.Today),
                chat("apofis-a", "Trip plans"),
                DrawerRow.Header(GroupLabel.Date(today.minusDays(3).toEpochDay())),
                chat("apofis-c", "Plant care"),
            ),
            rows,
        )
    }

    @Test
    fun untitledUsesEmptyTitle() {
        val rows = groupSessions(listOf(session("apofis-a", null, at(today, 10))), "", null, today, zone)
        assertEquals(listOf(DrawerRow.Header(GroupLabel.Today), chat("apofis-a", "")), rows)
    }
}
