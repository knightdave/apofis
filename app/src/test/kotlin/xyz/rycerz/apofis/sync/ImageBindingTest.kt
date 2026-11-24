package xyz.rycerz.apofis.sync

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.ChatItem
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.PendingSendEntity
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.ui.chat.actionable

private const val KEY = "apofis-s"
private const val RUN = "run_1"
private const val DB_FILE = "bindings.db"
private val D1 = "a1".repeat(32)
private val D2 = "b2".repeat(32)

/** Photos are shown only through a binding written at an unambiguous delivery of this phone's send and checked on every sync. */
@RunWith(RobolectricTestRunner::class)
class ImageBindingTest {
    private val app = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private val routes = ConcurrentHashMap<String, () -> MockResponse>()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                routes[request.target.substringBefore('?')]?.invoke() ?: MockResponse.Builder().code(500).build()
        }
        server.start()
        app.deleteDatabase(DB_FILE)
        open()
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    private fun open() {
        db = Room.databaseBuilder(app, AppDatabase::class.java, DB_FILE).allowMainThreadQueries().build()
        repo = ChatRepository(HermesApi(server.url("/").toString(), "secret-key"), db, now = { 1_000L })
    }

    /** A new process over the same database file. */
    private fun restart() {
        db.close()
        open()
    }

    private fun hint(digest: String) = "[Image attached at: /home/h/.hermes/cache/native-inputs/api_${digest.take(32)}.jpg]"

    private fun block(vararg digests: String) =
        "\n\n" + digests.joinToString("\n") { hint(it) } + "\n" + digests.joinToString("\n") { "[screenshot]" }

    private fun row(id: Long, content: String, role: String = "user") =
        buildJsonObject { put("id", id); put("role", role); put("content", content); put("timestamp", 1.0) }.toString()

    private fun serve(vararg rows: String) {
        val body = """{"object":"list","session_id":"$KEY","data":[${rows.joinToString(",")}]}"""
        routes["/api/sessions/$KEY/messages"] = { MockResponse.Builder().body(body).addHeader("Content-Type", "application/json").build() }
    }

    private suspend fun sync(vararg rows: String) {
        serve(*rows)
        assertTrue(repo.syncMessages(KEY))
    }

    /** The sync a run end makes: its settlement removes the delivered send inside the same transaction. */
    private suspend fun runEnd(vararg rows: String) {
        serve(*rows)
        assertTrue(repo.syncMessages(KEY, afterReplace = { _ -> db.pendingSendDao().rows(KEY).forEach { db.pendingSendDao().delete(it.idempotencyKey) } }))
    }

    private suspend fun seed(key: String, text: String, images: List<String>, createdAt: Long = 1_000L) {
        db.sessionDao().upsert(
            SessionEntity(
                key = KEY, currentId = KEY, lineageIds = "", title = "t", pinned = false, archived = false, startedAt = 1.0,
                lastActiveAt = 2.0, messageCount = 0, preview = null, stale = false, existsOnServer = true,
            ),
        )
        db.pendingSendDao().upsert(PendingSendEntity(key, KEY, text, "{}", createdAt, "sending", RUN, null, images = images.joinToString(",")))
    }

    private suspend fun shown() = repo.transcript(KEY).first()

    private suspend fun users() = shown().filterIsInstance<UserMessage>()

    private suspend fun bindings() = db.imageBindingDao().forSession(KEY).associate { it.messageId to it.digests }

    @Test
    fun bindingWrittenWithTheStoredRow() = runTest {
        seed("k", "cap", listOf(D1))
        serve(row(1, "cap" + block(D1)))
        lateinit var reader: Deferred<Pair<List<Long>, List<Long>>>
        assertTrue(
            repo.syncMessages(KEY, afterReplace = { _ ->
                // Inside the transaction, where the run follower settles: the row and its binding are both written.
                assertEquals(mapOf(1L to D1), bindings())
                assertEquals(listOf(1L), db.messageDao().rows(KEY).map { it.id })
                // A reader outside the transaction, started while it is open.
                reader = async(Dispatchers.IO) {
                    db.withTransaction { db.messageDao().rows(KEY).map { it.id } to db.imageBindingDao().forSession(KEY).map { it.messageId } }
                }
                db.pendingSendDao().delete("k") // the settlement removes the send after the binding was written
            }),
        )
        val (rows, bound) = withContext(Dispatchers.IO) { withTimeout(10_000) { reader.await() } }
        assertTrue("row $rows, bindings $bound", (rows.isEmpty() && bound.isEmpty()) || (rows == listOf(1L) && bound == listOf(1L)))
        assertEquals(mapOf(1L to D1), bindings())
        assertEquals(listOf(D1), users().single().images)
    }

    @Test
    fun photosStayThroughRunAndSettlement() = runTest {
        seed("k", "cap", listOf(D1, D2))
        val seen = CopyOnWriteArrayList<List<ChatItem>>()
        val latest = MutableStateFlow<List<ChatItem>?>(null)
        backgroundScope.launch(Dispatchers.IO) { repo.transcript(KEY).collect { seen += it; latest.value = it } }
        suspend fun awaitShown(condition: (List<ChatItem>) -> Boolean) =
            withContext(Dispatchers.IO) { withTimeout(10_000) { latest.filterNotNull().first(condition) } }

        awaitShown { items -> items.any { it.key == "pk" } } // the pending bubble
        sync(row(1, "cap" + block(D1, D2)), row(2, "Looking", role = "assistant")) // during the live run
        awaitShown { items -> items.any { it.key == "u1" } }
        runEnd(row(1, "cap" + block(D1, D2)), row(2, "Two cats.", role = "assistant"))
        awaitShown { items -> items.any { it is AssistantReply && it.text == "Two cats." } }

        assertTrue(seen.size >= 3)
        for (items in seen) {
            val bubble = items.filterIsInstance<UserMessage>().single() // never two bubbles
            assertEquals(listOf(D1, D2), bubble.images) // never a placeholder
            assertEquals("cap", bubble.text)
        }
    }

    @Test
    fun bindingWrittenAloneRemaps() = runTest {
        val latest = MutableStateFlow<List<ChatItem>?>(null)
        backgroundScope.launch(Dispatchers.IO) { repo.transcript(KEY).collect { latest.value = it } }
        suspend fun awaitShown(condition: (List<ChatItem>) -> Boolean) =
            withContext(Dispatchers.IO) { withTimeout(10_000) { latest.filterNotNull().first(condition) } }
        seed("other", "other", emptyList())
        sync(row(1, "cap" + block(D1)))
        awaitShown { items -> items.any { it.key == "u1" && (it as UserMessage).images == listOf(null) } }

        // The send is stored after the row arrived; the next sync returns the same rows and only writes the binding.
        seed("k", "cap", listOf(D1))
        sync(row(1, "cap" + block(D1)))
        assertEquals(mapOf(1L to D1), bindings())
        awaitShown { items -> items.any { it.key == "u1" && (it as UserMessage).images == listOf(D1) } }
    }

    @Test
    fun confirmedImageShownAfterRestart() = runTest {
        val content = "a caption" + block(D1)
        seed("k", "a caption", listOf(D1))
        runEnd(row(1, content), row(2, "Nice.", role = "assistant"))
        restart()

        val items = shown()
        val message = items.filterIsInstance<UserMessage>().single()
        assertEquals("a caption", message.text)
        assertEquals(listOf(D1), message.images)
        assertEquals(content, message.raw)
        assertEquals("a caption", actionable(items, "u1")) // Copy
        assertEquals("a caption", actionable(items, "u1", fullMessage = true)) // Select and the full reader
    }

    @Test
    fun unboundMarkerShowsPlaceholder() = runTest {
        seed("k", "other", emptyList())
        sync(row(1, "a caption" + block(D1)))

        val message = users().first()
        assertEquals("a caption", message.text)
        assertEquals(listOf(null), message.images)
        assertEquals(emptyMap<Long, String>(), bindings())
    }

    @Test
    fun bindingSurvivesRepeatedSync() = runTest {
        seed("k", "cap", listOf(D1))
        sync(row(1, "cap" + block(D1)))
        sync(row(1, "cap" + block(D1)), row(2, "Looking", role = "assistant"))
        runEnd(row(1, "cap" + block(D1)), row(2, "A cat.", role = "assistant"))
        sync(row(1, "cap" + block(D1)), row(2, "A cat.", role = "assistant"))

        assertEquals(mapOf(1L to D1), bindings())
        assertEquals(listOf(D1), users().single().images)
    }

    @Test
    fun foreignRowWithImagesShowsPlaceholders() = runTest {
        seed("k", "mine", listOf(D1))
        // Another client's rows: one with the same photo under another caption, one with another photo.
        sync(row(1, "theirs" + block(D1)), row(2, "mine" + block(D2)))

        assertEquals(listOf("u1", "u2", "pk"), shown().map { it.key })
        assertEquals(listOf(listOf(null), listOf(null), listOf(D1)), users().map { it.images })
        assertEquals(emptyMap<Long, String>(), bindings())
    }

    @Test
    fun foreignNullHintBeforeOwnRowBindsNothing() = runTest {
        seed("k", "cap", listOf(D1))
        val foreign = row(1, "cap\n\n[Image attached: https://example.org/x.png]\n[screenshot]")
        val own = row(2, "cap" + block(D1))
        sync(foreign, own)
        assertEquals(listOf("u1", "u2"), shown().map { it.key }) // confirmed once
        assertEquals(emptyMap<Long, String>(), bindings())
        sync(foreign, own)
        assertEquals(listOf("u1", "u2"), shown().map { it.key })
        assertEquals(emptyMap<Long, String>(), bindings())
        runEnd(foreign, own)
        restart()

        assertEquals(emptyMap<Long, String>(), bindings())
        assertEquals(listOf(listOf(null), listOf(null)), users().map { it.images })
    }

    @Test
    fun ambiguousMatchBindsNothing() = runTest {
        // Two rows match one send.
        seed("k", "cap", listOf(D1))
        sync(row(1, "cap" + block(D1)), row(2, "cap" + block(D1)))
        assertEquals(listOf("u1", "u2"), shown().map { it.key }) // confirmed once
        assertEquals(emptyMap<Long, String>(), bindings())
        runEnd(row(1, "cap" + block(D1)), row(2, "cap" + block(D1)))
        restart()
        assertEquals(emptyMap<Long, String>(), bindings())
        assertEquals(listOf(listOf(null), listOf(null)), users().map { it.images })

        // One row matches two sends (the same photo sent twice).
        seed("k1", "again", listOf(D2))
        seed("k2", "again", listOf(D2), createdAt = 2_000L)
        sync(row(1, "cap" + block(D1)), row(2, "cap" + block(D1)), row(3, "again" + block(D2)))
        assertEquals(listOf("u1", "u2", "u3", "pk2"), shown().map { it.key })
        assertEquals(emptyMap<Long, String>(), bindings())
    }

    @Test
    fun rewrittenSameIdDropsBinding() = runTest {
        seed("k1", "one", listOf(D1))
        seed("k2", "two", listOf(D2))
        runEnd(row(1, "one" + block(D1)), row(2, "two" + block(D2)))
        assertEquals(mapOf(1L to D1, 2L to D2), bindings())

        // Row 1 rewritten without its block, row 2 with another photo's hint, under the same ids.
        sync(row(1, "one"), row(2, "two" + block(D1)))
        assertEquals(emptyMap<Long, String>(), bindings())
        assertEquals(listOf(emptyList(), listOf(null)), users().map { it.images })
        restart()
        assertEquals(emptyMap<Long, String>(), bindings())
        assertEquals(listOf(emptyList(), listOf(null)), users().map { it.images })
    }

    @Test
    fun vanishedIdInSurvivingChatDropsBinding() = runTest {
        seed("k", "cap", listOf(D1))
        runEnd(row(1, "cap" + block(D1)), row(2, "A cat.", role = "assistant"))
        assertEquals(mapOf(1L to D1), bindings())

        sync(row(3, "cap" + block(D1)), row(4, "A cat.", role = "assistant")) // the same content under a new id
        assertEquals(emptyMap<Long, String>(), bindings())
        restart()
        assertEquals(emptyMap<Long, String>(), bindings())
        assertNotNull(db.sessionDao().get(KEY))
        assertEquals(listOf(listOf(null)), users().map { it.images })
    }

    @Test
    fun textOnlySendGetsNoBinding() = runTest {
        val text = "hi" + block(D1)
        seed("k", text, emptyList())
        sync(row(1, text))

        assertEquals(listOf("u1"), shown().map { it.key }) // confirmed
        assertEquals(listOf(listOf(null)), users().map { it.images })
        assertEquals(emptyMap<Long, String>(), bindings())
    }
}
