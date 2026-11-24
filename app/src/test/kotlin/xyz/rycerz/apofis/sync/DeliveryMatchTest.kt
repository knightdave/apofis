package xyz.rycerz.apofis.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.db.ActiveRunEntity
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.PendingSendEntity
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.run.RunFollower

private const val KEY = "apofis-s"
private const val RUN = "run_1"
private val D1 = "a1".repeat(32)
private val D2 = "b2".repeat(32)

/** The delivery match: when a server row shows a started send. It hides the bubble and settles the run; it never grants a photo. */
@RunWith(RobolectricTestRunner::class)
class DeliveryMatchTest {
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
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ChatRepository(HermesApi(server.url("/").toString(), "secret-key"), db, now = { 1_000L })
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    private fun hint(digest: String) = "[Image attached at: /home/h/.hermes/cache/native-inputs/api_${digest.take(32)}.jpg]"

    private fun row(id: Long, content: String, role: String = "user") =
        buildJsonObject { put("id", id); put("role", role); put("content", content); put("timestamp", 1.0) }.toString()

    private fun serve(vararg rows: String) {
        val body = """{"object":"list","session_id":"$KEY","data":[${rows.joinToString(",")}]}"""
        routes["/api/sessions/$KEY/messages"] = { MockResponse.Builder().body(body).addHeader("Content-Type", "application/json").build() }
    }

    private suspend fun sync(vararg rows: String) {
        serve(*rows)
        repo.syncMessages(KEY)
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

    private suspend fun bindings() = db.imageBindingDao().forSession(KEY).associate { it.messageId to it.digests }

    @Test
    fun imageMessageConfirmed() = runTest {
        seed("k", "look", listOf(D1, D2))
        sync(row(1, "look\n\n${hint(D1)}\n${hint(D2)}\n[screenshot]\n[screenshot]"))

        val item = shown().single() as UserMessage
        assertEquals("u1", item.key)
        assertEquals("look", item.text)
        assertEquals(RUN, item.runId) // the row showing the send carries its run
    }

    @Test
    fun imageCountMismatch() = runTest {
        seed("k", "look", listOf(D1, D2))
        sync(row(1, "look\n\n${hint(D1)}\n[screenshot]"))

        assertEquals(listOf("u1", "pk"), shown().map { it.key })
        assertEquals(emptyMap<Long, String>(), bindings())
    }

    @Test
    fun prefixMismatch() = runTest {
        seed("k", "look", listOf(D1))
        sync(row(1, "look\n\n${hint(D2)}\n[screenshot]"))

        assertEquals(listOf("u1", "pk"), shown().map { it.key })
        assertEquals(emptyMap<Long, String>(), bindings())
    }

    @Test
    fun nullHintMatchesAnyDigest() = runTest {
        seed("k", "look", listOf(D1))
        sync(row(1, "look\n\n[Image attached: https://example.org/x.png]\n[screenshot]"))

        val item = shown().single() as UserMessage // delivered: the bubble is gone
        assertEquals(listOf(null), item.images) // and no photo was granted
        assertEquals(emptyMap<Long, String>(), bindings())
    }

    @Test
    fun identicalCaptionsDifferentImages() = runTest {
        seed("k1", "same", listOf(D1))
        seed("k2", "same", listOf(D2), createdAt = 2_000L)
        // The server rows in the other order: each send is matched by its own digest, not by position.
        sync(row(1, "same\n\n${hint(D2)}\n[screenshot]"), row(2, "same\n\n${hint(D1)}\n[screenshot]"))

        assertEquals(listOf("u1", "u2"), shown().map { it.key })
        assertEquals(mapOf(1L to D2, 2L to D1), bindings())
    }

    @Test
    fun textOnlyWithScreenshotLineConfirmed() = runTest {
        seed("k", "see this\n[screenshot]", emptyList())
        sync(row(1, "see this\n[screenshot]"))

        val item = shown().single() as UserMessage
        assertEquals("see this\n[screenshot]", item.text) // a bare marker is text
        assertEquals(emptyList<String?>(), item.images)
    }

    @Test
    fun syntheticMarkerSuffixInTextOnlySend() = runTest {
        val text = "hi\n\n${hint(D1)}\n[screenshot]"
        seed("k", text, emptyList())
        sync(row(1, text))
        sync(row(1, text), row(2, "working", role = "assistant")) // a later sync during the run

        val users = shown().filterIsInstance<UserMessage>()
        assertEquals(listOf("u1"), users.map { it.key }) // confirmed once, never doubled
        assertEquals("hi", users.single().text)
        assertEquals(listOf(null), users.single().images) // it looks like the server's block: a placeholder (known limit)
        assertEquals(emptyMap<Long, String>(), bindings())
    }

    /** A real run follower follows the run of send "k" to its end; the server then returns [rows]. */
    private suspend fun TestScope.runEndsWith(text: String, images: List<String>, vararg rows: String) {
        serve(*rows)
        routes["/v1/runs/$RUN/events"] = {
            MockResponse.Builder().body(": open\n\nid: 0\ndata: {\"event\":\"run.completed\",\"seq\":0}\n\n")
                .addHeader("Content-Type", "text/event-stream").build()
        }
        val done = CompletableDeferred<Unit>()
        val follower = RunFollower(HermesApi(server.url("/").toString(), "secret-key"), db, repo, backgroundScope, onRunFinished = { done.complete(Unit) })
        follower.resumeAll() // before the run is stored, so only the start below follows it (as RunFollowerTest's started)
        seed("k", text, images)
        db.activeRunDao().upsert(ActiveRunEntity(RUN, KEY, null, "running", 1L))
        follower.onRunStarted(KEY, RUN)
        done.await()
    }

    @Test
    fun settlementUsesRaw() = runTest {
        runEndsWith("caption", listOf(D1), row(10, "caption\n\n${hint(D1)}\n[screenshot]"), row(11, "A cat.", role = "assistant"))

        assertNull(db.pendingSendDao().get("k")) // confirmed and removed, not marked unconfirmed
        assertEquals(listOf("u10", "r11"), shown().map { it.key })
        assertEquals(mapOf(10L to D1), bindings()) // bound by the sync before the settlement removed the send
    }

    @Test
    fun settlementRefusesTheSameCaptionWithAnotherPhoto() = runTest {
        runEndsWith("caption", listOf(D1), row(10, "caption\n\n${hint(D2)}\n[screenshot]"), row(11, "A dog.", role = "assistant"))

        val left = db.pendingSendDao().get("k")!!
        assertEquals("failed", left.state)
        assertEquals("unconfirmed", left.error)
        assertEquals(emptyMap<Long, String>(), bindings())
    }

    @Test
    fun settlementOfATextOnlySendComparesItsWholeText() = runTest {
        val text = "hi\n\n${hint(D1)}\n[screenshot]"
        runEndsWith(text, emptyList(), row(10, text), row(11, "Hello.", role = "assistant"))

        assertNull(db.pendingSendDao().get("k")) // removed on raw equality
        assertEquals("hi", (shown().first() as UserMessage).text) // although the shown caption differs from the text
        assertEquals(emptyMap<Long, String>(), bindings())
    }
}
