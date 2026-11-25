package xyz.rycerz.apofis.sync

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import java.util.concurrent.TimeUnit
import java.util.concurrent.CopyOnWriteArrayList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.test.runTest
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.rycerz.apofis.api.ApiError
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.api.HoldingQueue
import xyz.rycerz.apofis.api.fixture
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.ChatItem
import xyz.rycerz.apofis.chat.ReplyOutcome
import xyz.rycerz.apofis.chat.SendState
import xyz.rycerz.apofis.chat.TOOL_RESULT_DISPLAY_LIMIT
import xyz.rycerz.apofis.chat.ToolStep
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.chat.buildTranscript
import xyz.rycerz.apofis.chat.storedToolCalls
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.ImageBindingEntity
import xyz.rycerz.apofis.db.MessageEntity
import xyz.rycerz.apofis.db.PendingSendEntity
import xyz.rycerz.apofis.db.ReplyOutcomeEntity
import xyz.rycerz.apofis.db.SessionEntity

@RunWith(RobolectricTestRunner::class)
class ChatRepositorySyncTest {
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var repo: ChatRepository
    private val responses = HoldingQueue()

    @Before
    fun setUp() {
        server = MockWebServer()
        server.dispatcher = responses
        server.start()
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = ChatRepository(HermesApi(server.url("/").toString(), "secret-key"), db, now = { 1_000L })
    }

    @After
    fun tearDown() {
        db.close()
        responses.closeAndVerify(server)
    }

    private fun respond(body: String, code: Int = 200) =
        server.enqueue(MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build())

    private fun sessionJson(
        id: String,
        source: String = "api_server",
        hidden: Boolean = false,
        archived: Boolean = false,
        pinned: Boolean = false,
        root: String? = null,
        lastActive: Double? = 2.0,
        lineage: List<String> = emptyList(),
    ) = """{"id":"$id","source":"$source","title":"t $id","pinned":$pinned,"archived":$archived,"hidden":$hidden,""" +
        """"started_at":1.0,"last_active":$lastActive,"message_count":3,"preview":${if (lastActive == null) "null" else "\"p\""}""" +
        (if (root != null) ""","_lineage_root_id":"$root"""" else "") +
        ""","_lineage_ids":[${lineage.joinToString(",") { "\"$it\"" }}]}"""

    private fun page(vararg rows: String, hasMore: Boolean = false) =
        """{"object":"list","data":[${rows.joinToString(",")}],"has_more":$hasMore}"""

    private fun messagesJson(sessionId: String, ids: List<Long>, role: (Long) -> String = { "user" }) =
        rawMessages(sessionId, ids.map { """{"id":$it,"role":"${role(it)}","content":"m$it","timestamp":$it.0}""" })

    private fun rawMessages(sessionId: String, rows: List<String>) =
        """{"object":"list","session_id":"$sessionId","data":[${rows.joinToString(",")}]}"""

    private suspend fun expectError(block: suspend () -> Unit): Throwable {
        try {
            block()
        } catch (e: Exception) {
            return e
        }
        fail("expected an error")
        throw AssertionError()
    }

    private fun session(
        key: String,
        currentId: String = key,
        lineageIds: String = "",
        pinned: Boolean = false,
        archived: Boolean = false,
        existsOnServer: Boolean = true,
    ) = SessionEntity(
        key = key, currentId = currentId, lineageIds = lineageIds, title = "t", pinned = pinned, archived = archived,
        startedAt = 1.0, lastActiveAt = 2.0, messageCount = 3, preview = null, stale = false, existsOnServer = existsOnServer,
    )

    private fun pending(sessionKey: String, key: String, createdAt: Long = 5L) = PendingSendEntity(
        idempotencyKey = key, sessionKey = sessionKey, text = "unsent", requestBody = "{}", createdAt = createdAt,
        state = "queued", runId = null, error = null,
    )

    @Test
    fun multiMegabyteToolArgumentSyncsAndReadsBackFromAFreshDatabase() = runTest {
        val app = ApplicationProvider.getApplicationContext<Context>()
        app.deleteDatabase("args.db")
        fun open() = Room.databaseBuilder(app, AppDatabase::class.java, "args.db").allowMainThreadQueries().build()
        val first = open()
        first.sessionDao().upsert(session("apofis-a"))
        val args = buildJsonObject {
            put("path", "/tmp/big")
            put("content", "x".repeat(3_000_000)) // a large file write: only the argument is large
        }.toString()
        val calls = buildJsonArray {
            addJsonObject {
                put("id", "c1")
                put("type", "function")
                putJsonObject("function") {
                    put("name", "write_file")
                    put("arguments", args)
                }
            }
        }.toString()
        val assistant = buildJsonObject {
            put("id", 2)
            put("role", "assistant")
            put("content", "")
            put("tool_calls", calls)
            put("timestamp", 2.0)
        }.toString()
        respond(
            rawMessages(
                "apofis-a",
                listOf(
                    """{"id":1,"role":"user","content":"write it","timestamp":1.0}""",
                    assistant,
                    """{"id":3,"role":"tool","content":"{\"output\":\"ok\"}","tool_call_id":"c1","timestamp":3.0}""",
                ),
            ),
        )
        assertTrue(ChatRepository(HermesApi(server.url("/").toString(), "secret-key"), first).syncMessages("apofis-a"))
        first.close()

        val reopened = open() // the chat opened again later: a new reader of the stored rows
        try {
            val reply = ChatRepository(HermesApi(server.url("/").toString(), "secret-key"), reopened).transcript("apofis-a").first()
                .filterIsInstance<AssistantReply>().single()
            val step = reply.steps.single() as ToolStep
            assertEquals("write_file", step.name)
            assertEquals(TOOL_RESULT_DISPLAY_LIMIT, step.args.length)
            assertTrue(step.argsTruncated)
            assertTrue(step.argsPreview.startsWith("path=/tmp/big, content=xxx"))
            assertEquals("ok", step.result)
        } finally {
            reopened.close()
            app.deleteDatabase("args.db")
        }
    }

    @Test
    fun multiMegabyteToolResultSyncsAndReadsBackThroughTheDatabase() = runTest {
        db.sessionDao().upsert(session("apofis-a"))
        val call = """[{"id":"c1","type":"function","function":{"name":"terminal","arguments":"{}"}}]""".replace("\"", "\\\"")
        val huge = """{\"output\": \"${"x".repeat(3_000_000)}\", \"exit_code\": 1}"""
        respond(
            rawMessages(
                "apofis-a",
                listOf(
                    """{"id":1,"role":"user","content":"run it","timestamp":1.0}""",
                    """{"id":2,"role":"assistant","content":"","tool_calls":"$call","timestamp":2.0}""",
                    """{"id":3,"role":"tool","content":"$huge","tool_call_id":"c1","timestamp":3.0}""",
                ),
            ),
        )
        assertTrue(repo.syncMessages("apofis-a"))

        // Read back through a Room query, as the chat screen does when the chat is opened again.
        val step = repo.transcript("apofis-a").first().filterIsInstance<AssistantReply>().single().steps.single() as ToolStep
        assertEquals(TOOL_RESULT_DISPLAY_LIMIT, step.result!!.length)
        assertTrue(step.resultTruncated)
        assertTrue(step.error) // exit_code 1, decided before the cut
        assertTrue(db.messageDao().rows("apofis-a").all { it.content.length <= TOOL_RESULT_DISPLAY_LIMIT + 100 })
    }

    @Test
    fun filtersToAppSessions() = runTest {
        respond(
            page(
                sessionJson("apofis-m", source = "matrix"),
                sessionJson("apofis-c", source = "cron"),
                sessionJson("api_1_x"),
                sessionJson("apofis-push-1"),
                sessionJson("apofis-h", hidden = true),
                sessionJson("apofis-a"),
            ),
        )
        repo.refreshSessions()
        assertEquals(listOf("apofis-a"), repo.sessions().first().map { it.key })
        assertTrue(isAppSession(HermesApi.json.decodeFromString(sessionJson("apofis-a"))))
    }

    @Test
    fun compressedSessionKeepsItsRootKey() = runTest {
        db.sessionDao().upsert(session("apofis-r"))
        respond(page(sessionJson("tip-9", root = "apofis-r")))
        repo.refreshSessions()
        val row = repo.sessions().first().single()
        assertEquals("apofis-r", row.key)
        assertEquals("tip-9", row.currentId)
        assertTrue(row.stale)
        assertEquals("apofis-r", repo.resolveKey("tip-9"))
    }

    @Test
    fun pagesUntilHasMoreFalse() = runTest {
        val first = (0 until 200).map { sessionJson("apofis-a$it") }
        respond(page(*first.toTypedArray(), hasMore = true))
        respond(page(sessionJson("apofis-b"), hasMore = false))
        repo.refreshSessions()
        assertEquals((0 until 200).map { "apofis-a$it" }.toSet() + "apofis-b", repo.sessions().first().map { it.key }.toSet())
        assertEquals(2, server.requestCount)
        server.takeRequest()
        assertEquals("200", server.takeRequest().url.queryParameter("offset")) // the next recency window
    }

    private suspend fun cachedWithHistory(key: String) {
        db.sessionDao().upsert(session(key))
        respond(messagesJson(key, listOf(1L)))
        repo.syncMessages(key)
    }

    @Test
    fun backFilledPinDoesNotSkipTheNextWindowsFirstRow() = runTest {
        cachedWithHistory("apofis-u200")
        val pin = sessionJson("apofis-pin", pinned = true, archived = true)
        // 200 recency rows plus the pin Hermes appends past the limit; has_more counts only the 200.
        respond(page(*((0 until 200).map { sessionJson("apofis-u$it") } + pin).toTypedArray(), hasMore = true))
        respond(page(sessionJson("apofis-u200"), pin, hasMore = false))
        repo.refreshSessions()

        server.takeRequest()
        server.takeRequest()
        assertEquals("200", server.takeRequest().url.queryParameter("offset"))
        assertNotNull(db.sessionDao().get("apofis-u200"))
        assertTrue(db.messageDao().hasRows("apofis-u200"))
        assertEquals(1, db.sessionDao().all().count { it.key == "apofis-pin" })
    }

    @Test
    fun pinInsideAFullWindowDoesNotEndTheListEarly() = runTest {
        cachedWithHistory("apofis-last")
        val pin = sessionJson("apofis-pin", pinned = true)
        // A full window with the pin inside it: Hermes counts 199 unpinned rows and says has_more=false.
        respond(page(*((0 until 199).map { sessionJson("apofis-u$it") } + pin).toTypedArray(), hasMore = false))
        respond(page(sessionJson("apofis-last"), pin, hasMore = false))
        repo.refreshSessions()

        assertEquals(3, server.requestCount)
        assertNotNull(db.sessionDao().get("apofis-last"))
        assertTrue(db.messageDao().hasRows("apofis-last"))
    }

    @Test
    fun twoHundredArchivedPinsCommit() = runTest {
        responses.setFailFast(true) // a further list read gets a 404 instead of waiting
        val pins = (0 until 200).map { sessionJson("apofis-pin$it", pinned = true, archived = true) }
        // Hermes appends every pin outside the recency window to every page: with 200 pins every page is full.
        respond(page(*pins.toTypedArray()))
        respond(page(*pins.toTypedArray()))
        repo.refreshSessions()
        assertEquals(2, server.requestCount)
        assertEquals(pins.indices.map { "apofis-pin$it" }.toSet(), repo.sessions(archived = true).first().map { it.key }.toSet())
        assertEquals(1L, repo.refreshed.value)
    }

    @Test
    fun allPinsFinalPageEvictsNothing() = runTest {
        cachedWithHistory("apofis-gone") // deleted on another client; only an evicting refresh would remove it
        responses.setFailFast(true)
        val recent = (0 until 150).map { sessionJson("apofis-u$it") }
        val pins = (0 until 200).map { sessionJson("apofis-pin$it", pinned = true) }
        // The newest window: 150 unpinned rows and 50 pins, the other 150 pins appended. The next window: those 150
        // pins with the first 50 appended, a full last page of rows already seen.
        respond(page(*(recent + pins).toTypedArray()))
        respond(page(*(pins.drop(50) + pins.take(50)).toTypedArray()))
        repo.refreshSessions()
        assertEquals(3, server.requestCount) // the history sync and two list reads
        val keys = repo.sessions().first().map { it.key }.toSet()
        assertTrue(keys.containsAll(recent.indices.map { "apofis-u$it" } + pins.indices.map { "apofis-pin$it" }))
        assertTrue("apofis-gone" in keys)
        assertTrue(db.messageDao().hasRows("apofis-gone"))
    }

    @Test
    fun repeatedPinnedWindowThenMoreRowsKeepsUnseen() = runTest {
        cachedWithHistory("apofis-u400") // older than both windows: listed only on a later page
        responses.setFailFast(true)
        val unpinned = (0 until 200).map { sessionJson("apofis-u$it") }
        val pins = (0 until 200).map { sessionJson("apofis-pin$it", pinned = true) }
        // The newest window holds 200 unpinned rows, the 200 older pins appended; the next window is exactly those
        // pins, a full page with nothing new, though older unpinned rows follow.
        respond(page(*(unpinned + pins).toTypedArray(), hasMore = true))
        respond(page(*pins.toTypedArray(), hasMore = true))
        repo.refreshSessions()
        assertEquals(3, server.requestCount) // the history sync and two list reads: paging stopped at the repeated page
        assertNotNull(db.sessionDao().get("apofis-u400"))
        assertTrue(db.messageDao().hasRows("apofis-u400"))
        assertEquals(401, repo.sessions().first().size)
    }

    @Test
    fun archivedChatsSurviveRefresh() = runTest {
        val dao = db.sessionDao()
        dao.upsert(session("apofis-arch", archived = true))

        respond(page())
        respond("""{"object":"hermes.session","session":${sessionJson("apofis-arch", archived = true)}}""")
        repo.refreshSessions()
        assertEquals(listOf("apofis-arch"), repo.sessions(archived = true).first().map { it.key })
        server.takeRequest()
        assertEquals("/api/sessions/apofis-arch", server.takeRequest().url.encodedPath)

        respond(page())
        respond("""{"object":"hermes.session","session":${sessionJson("apofis-arch", archived = false)}}""")
        repo.refreshSessions()
        assertEquals(listOf("apofis-arch"), repo.sessions(archived = false).first().map { it.key })
        assertTrue(repo.sessions(archived = true).first().isEmpty())

        dao.upsert(dao.get("apofis-arch")!!.copy(archived = true))
        respond(page())
        respond(fixture("error_404.json"), code = 404)
        repo.refreshSessions()
        assertNull(dao.get("apofis-arch"))
    }

    @Test
    fun setArchivedKeepsPinState() = runTest {
        db.sessionDao().upsert(session("apofis-s", pinned = true))
        respond(fixture("session_patch_archived.json"))
        repo.setArchived("apofis-s", true)
        assertEquals("""{"archived":true}""", server.takeRequest().body!!.utf8())
        val row = db.sessionDao().get("apofis-s")!!
        assertTrue(row.archived)
        assertTrue(row.pinned)
    }

    @Test
    fun pendingRowsCarryStateErrorAndAttempted() = runTest {
        db.sessionDao().upsert(session("apofis-s"))
        val base = pending("apofis-s", "a")
        db.pendingSendDao().upsert(base.copy(state = "failed", error = "unconfirmed", attempted = true, createdAt = 1L))
        db.pendingSendDao().upsert(base.copy(idempotencyKey = "b", state = "steering", createdAt = 2L))
        db.pendingSendDao().upsert(base.copy(idempotencyKey = "c", state = "steered", createdAt = 3L))
        db.pendingSendDao().upsert(base.copy(idempotencyKey = "d", attempted = true, createdAt = 4L))

        val (failed, steering, steered, queued) = repo.transcript("apofis-s").first().map { it as UserMessage }
        assertEquals(SendState.FAILED, failed.state)
        assertEquals("unconfirmed", failed.error)
        assertTrue(failed.attempted)
        assertEquals(SendState.SENDING, steering.state)
        assertTrue(steering.steered)
        assertEquals(SendState.SENT, steered.state)
        assertTrue(steered.steered)
        assertEquals(SendState.QUEUED, queued.state)
        assertNull(queued.error)
        assertTrue(queued.attempted)
    }

    @Test
    fun admittedRunSendShowsAsSentAndKeepsItsTurnAnchor() = runTest {
        db.sessionDao().upsert(session("apofis-s"))
        db.pendingSendDao().upsert(pending("apofis-s", "a").copy(state = "sending", runId = "run_1", attempted = true))

        val item = repo.transcript("apofis-s").first().single() as UserMessage
        assertEquals(SendState.SENT, item.state) // the server admitted its run: no longer "Sending"
        assertEquals("run_1", item.runId)
        assertEquals("a", item.idempotencyKey)
    }

    @Test
    fun deliveredPendingRowIsHiddenOnceTheServerShowsIt() = runTest {
        db.sessionDao().upsert(session("apofis-s"))
        // The repository clock is 1 s; rows are confirmed by the follower's rule: text, kind and the last minute.
        respond(rawMessages("apofis-s", listOf("""{"id":1,"role":"user","content":"hello ","timestamp":1.0}""")))
        repo.syncMessages("apofis-s")
        val base = pending("apofis-s", "a", createdAt = 1_000L)
        db.pendingSendDao().upsert(base.copy(text = "hello", state = "sending", runId = "run_1"))
        db.pendingSendDao().upsert(base.copy(idempotencyKey = "b", text = "hello", createdAt = 2_000L)) // queued: always shown
        db.pendingSendDao().upsert(base.copy(idempotencyKey = "c", text = "other", state = "sending", runId = "run_1", createdAt = 3_000L))

        assertEquals(listOf("u1", "pb", "pc"), repo.transcript("apofis-s").first().map { it.key })
    }

    @Test
    fun runStartIsMarkedOnTheBubbleThatShowsIt() = runTest {
        db.sessionDao().upsert(session("apofis-s"))
        respond(rawMessages("apofis-s", listOf("""{"id":1,"role":"user","content":"hello","timestamp":1.0}""")))
        repo.syncMessages("apofis-s")
        val base = pending("apofis-s", "a", createdAt = 1_000L)
        db.pendingSendDao().upsert(base.copy(text = "hello", state = "sending", runId = "run_1")) // shown by u1
        db.pendingSendDao().upsert(base.copy(idempotencyKey = "c", text = "other", state = "sending", runId = "run_2", createdAt = 3_000L))
        db.pendingSendDao().upsert(base.copy(idempotencyKey = "d", text = "steer", state = "steering", runId = "run_2", createdAt = 4_000L))

        val items = repo.transcript("apofis-s").first().map { it as UserMessage }
        assertEquals(listOf("u1" to "run_1", "pc" to "run_2", "pd" to null), items.map { it.key to it.runId })
    }

    @Test
    fun cachedMessagesAreKnown() = runTest {
        db.sessionDao().upsert(session("apofis-s"))
        assertFalse(repo.hasCachedMessages("apofis-s"))
        respond(messagesJson("apofis-s", listOf(1L)))
        repo.syncMessages("apofis-s")
        assertTrue(repo.hasCachedMessages("apofis-s"))
    }

    @Test
    fun sessionFlowFollowsRow() = runTest {
        assertNull(repo.session("apofis-s").first())
        db.sessionDao().upsert(session("apofis-s"))
        assertEquals("t", repo.session("apofis-s").first()!!.title)
    }

    @Test
    fun transcriptShapeChange() = runTest {
        db.sessionDao().upsert(session("apofis-s").copy(stale = true))
        respond(messagesJson("apofis-s", (1L..6L).toList()))
        repo.syncMessages("apofis-s")
        assertEquals(6, db.messageDao().observe("apofis-s").first().size)

        db.pendingSendDao().upsert(pending("apofis-s", "idem-1"))
        respond(messagesJson("tip-2", listOf(10L, 11L, 12L)))
        repo.syncMessages("apofis-s")

        assertEquals(listOf(10L, 11L, 12L), db.messageDao().observe("apofis-s").first().map { it.id })
        val row = db.sessionDao().get("apofis-s")!!
        assertEquals("tip-2", row.currentId)
        assertTrue("apofis-s" in row.lineageIds.split(","))
        assertFalse(row.stale)
        val last = repo.transcript("apofis-s").first().last() as UserMessage
        assertEquals("pidem-1", last.key)
        assertEquals(SendState.QUEUED, last.state)
        assertEquals("idem-1", last.idempotencyKey)
        server.takeRequest()
        assertEquals("/api/sessions/apofis-s/messages", server.takeRequest().url.encodedPath)
    }

    @Test
    fun outcomeAttachesToContainingReply() = runTest {
        db.sessionDao().upsert(session("apofis-s"))
        respond(messagesJson("apofis-s", listOf(1L, 2L, 3L, 4L)) { if (it % 2L == 0L) "assistant" else "user" })
        repo.syncMessages("apofis-s")
        db.replyOutcomeDao().upsert(ReplyOutcomeEntity("apofis-s", 2L, "stopped", null))
        db.replyOutcomeDao().upsert(ReplyOutcomeEntity("apofis-s", 3L, "failed", null)) // a user row: goes to reply r2
        val replies = repo.transcript("apofis-s").first().filterIsInstance<AssistantReply>()
        assertEquals(listOf(ReplyOutcome.FAILED, null), replies.map { it.outcome })
    }

    @Test
    fun resolveKeyFindsOldLineageId() = runTest {
        db.sessionDao().upsert(session("apofis-k", currentId = "tip-1", lineageIds = "apofis-k"))
        respond(messagesJson("tip-2", listOf(1L)))
        repo.syncMessages("apofis-k")
        assertEquals("apofis-k", repo.resolveKey("tip-1"))
        assertEquals("apofis-k", repo.resolveKey("tip-2"))
        assertNull(repo.resolveKey("unknown"))
    }

    @Test
    fun deleteFailsOffline() = runTest {
        db.sessionDao().upsert(session("apofis-s"))
        server.close()
        try {
            repo.delete("apofis-s")
            fail("expected ApiError.Network")
        } catch (e: ApiError.Network) {
            // expected
        }
        assertNotNull(db.sessionDao().get("apofis-s"))
    }

    @Test
    fun localOnlySessionRenameMakesNoRequest() = runTest {
        val key = repo.createLocalSession()
        assertTrue(key.startsWith("apofis-"))
        repo.rename(key, "hello")
        repo.setPinned(key, true)
        repo.setArchived(key, true)
        val row = db.sessionDao().get(key)!!
        assertEquals("hello", row.title)
        assertTrue(row.pinned && row.archived)
        assertFalse(row.existsOnServer)
        assertEquals(0, server.requestCount)
    }

    @Test
    fun reservedIdsAreNeverAppSessions() = runTest {
        respond(
            page(
                sessionJson("apofis-push-9", root = "apofis-a"),
                sessionJson("tip-1", root = "apofis-b", lineage = listOf("apofis-b", "apofis-probe-1")),
                sessionJson("apofis-c"),
            ),
        )
        repo.refreshSessions()
        assertEquals(listOf("apofis-c"), repo.sessions().first().map { it.key })
        assertTrue(expectError { repo.rename("apofis-push-1", "x") } is IllegalArgumentException)
        assertTrue(expectError { repo.syncMessages("apofis-probe-1") } is IllegalArgumentException)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun syncRefusesReservedTip() = runTest {
        db.sessionDao().upsert(session("apofis-s").copy(stale = true))
        respond(messagesJson("apofis-push-2", listOf(1L)))
        assertEquals(ApiError.Protocol, expectError { repo.syncMessages("apofis-s") })
        val row = db.sessionDao().get("apofis-s")!!
        assertEquals("apofis-s", row.currentId)
        assertTrue(row.stale)
        assertTrue(db.messageDao().observe("apofis-s").first().isEmpty())
    }

    @Test
    fun hiddenSessionKeepsUnsentWorkButIsNotShown() = runTest {
        db.sessionDao().upsert(session("apofis-h"))
        respond(messagesJson("apofis-h", listOf(1L, 2L)))
        repo.syncMessages("apofis-h")
        db.pendingSendDao().upsert(pending("apofis-h", "idem-h"))

        respond(page(sessionJson("apofis-h", hidden = true)))
        repo.refreshSessions()

        assertTrue(repo.sessions().first().isEmpty())
        assertTrue(db.sessionDao().get("apofis-h")!!.hidden)
        assertTrue(db.messageDao().observe("apofis-h").first().isEmpty())
        assertEquals(listOf("idem-h"), db.pendingSendDao().observe("apofis-h").first().map { it.idempotencyKey })
        repo.syncMessages("apofis-h")
        assertEquals(2, server.requestCount)
    }

    @Test
    fun emptyPageWithHasMoreFailsWithoutWriting() = runTest {
        db.sessionDao().upsert(session("apofis-old"))
        respond(page(sessionJson("apofis-a"), hasMore = true))
        respond(page(hasMore = true))
        assertEquals(ApiError.Protocol, expectError { repo.refreshSessions() })
        assertEquals(listOf("apofis-old"), repo.sessions().first().map { it.key })
    }

    @Test
    fun endlessPagingFails() = runTest {
        db.sessionDao().upsert(session("apofis-old"))
        repeat(100) { respond(page(sessionJson("apofis-p$it"), hasMore = true)) }
        assertEquals(ApiError.Protocol, expectError { repo.refreshSessions() })
        assertEquals(listOf("apofis-old"), repo.sessions().first().map { it.key })
    }

    @Test
    fun tipChangeBetweenPagesRestartsOnce() = runTest {
        db.sessionDao().upsert(session("apofis-s"))
        val full = (1L..500L).toList()
        respond(messagesJson("apofis-s", full))
        respond(messagesJson("tip-2", listOf(501L)))
        respond(messagesJson("tip-2", full))
        respond(messagesJson("tip-2", listOf(600L)))
        repo.syncMessages("apofis-s")
        assertEquals(501, db.messageDao().observe("apofis-s").first().size)
        assertEquals(600L, db.messageDao().observe("apofis-s").first().last().id)
        assertEquals("tip-2", db.sessionDao().get("apofis-s")!!.currentId)
        assertEquals("0", (1..3).map { server.takeRequest() }.last().url.queryParameter("offset"))
    }

    @Test
    fun tipChangingTwiceFailsWithoutWriting() = runTest {
        db.sessionDao().upsert(session("apofis-s"))
        val full = (1L..500L).toList()
        respond(messagesJson("apofis-s", full))
        respond(messagesJson("tip-2", listOf(501L)))
        respond(messagesJson("tip-2", full))
        respond(messagesJson("tip-3", listOf(600L)))
        assertEquals(ApiError.Protocol, expectError { repo.syncMessages("apofis-s") })
        assertTrue(db.messageDao().observe("apofis-s").first().isEmpty())
        assertEquals("apofis-s", db.sessionDao().get("apofis-s")!!.currentId)
    }

    @Test
    fun slowRefreshDoesNotResurrectDeletedChat() = runTest {
        db.sessionDao().upsert(session("apofis-s"))
        val listed = responses.hold(
            MockResponse.Builder().code(200).body(page(sessionJson("apofis-s"))).addHeader("Content-Type", "application/json").build(),
        )
        respond("""{"object":"hermes.session.deleted","id":"apofis-s","deleted":true}""")
        val refresh = launch(Dispatchers.Default) { repo.refreshSessions() }
        withContext(Dispatchers.IO) { server.takeRequest() } // the refresh is fetching
        // A second instance over the same database: the lock must be process-wide, not per repository.
        val other = ChatRepository(HermesApi(server.url("/").toString(), "secret-key"), db)
        // Undispatched: it runs to its first suspension, the lock the refresh holds, before launch returns.
        val delete = launch(Dispatchers.Default, CoroutineStart.UNDISPATCHED) { other.delete("apofis-s") }
        listed.countDown()
        refresh.join()
        delete.join()
        assertNull(db.sessionDao().get("apofis-s"))
    }

    @Test
    fun outcomeFollowsRowOwnership() = runTest {
        db.sessionDao().upsert(session("apofis-s"))
        val call = """[{"id":"c1","type":"function","function":{"name":"t","arguments":"{}"}}]"""
        respond(
            rawMessages(
                "apofis-s",
                listOf(
                    """{"id":1,"role":"user","content":"hi","timestamp":1.0}""",
                    """{"id":2,"role":"assistant","content":"","tool_calls":$call,"timestamp":2.0}""",
                    """{"id":3,"role":"system","display_kind":"model_switch","content":"switch","timestamp":3.0}""",
                    """{"id":4,"role":"assistant","content":"newer","timestamp":4.0}""",
                    """{"id":5,"role":"tool","tool_call_id":"c1","content":"late","timestamp":5.0}""",
                ),
            ),
        )
        repo.syncMessages("apofis-s")
        db.replyOutcomeDao().upsert(ReplyOutcomeEntity("apofis-s", 5L, "interrupted", null))
        val replies = repo.transcript("apofis-s").first().filterIsInstance<AssistantReply>()
        assertEquals(listOf("r2", "r4"), replies.map { it.key })
        assertEquals(listOf(ReplyOutcome.INTERRUPTED, null), replies.map { it.outcome })
    }

    @Test
    fun serverNullsOverwriteCacheAndMarkStale() = runTest {
        db.sessionDao().upsert(session("apofis-s").copy(preview = "old"))
        respond(page(sessionJson("apofis-s", lastActive = null)))
        repo.refreshSessions()
        val row = db.sessionDao().get("apofis-s")!!
        assertNull(row.lastActiveAt)
        assertNull(row.preview)
        assertTrue(row.stale)
    }

    @Test
    fun listedArchivedChatNeedsNoDetailRead() = runTest {
        db.sessionDao().upsert(session("apofis-arch", archived = true, pinned = true))
        respond(page(sessionJson("apofis-arch", archived = true, pinned = true)))
        repo.refreshSessions()
        assertEquals(listOf("apofis-arch"), repo.sessions(archived = true).first().map { it.key })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun reservedTipOnLaterPageFailsAtOnce() = runTest {
        db.sessionDao().upsert(session("apofis-s"))
        respond(messagesJson("apofis-s", (1L..500L).toList()))
        respond(messagesJson("apofis-push-1", listOf(501L)))
        respond(messagesJson("apofis-s", listOf(1L))) // would let a retry succeed
        assertEquals(ApiError.Protocol, expectError { repo.syncMessages("apofis-s") })
        assertEquals(2, server.requestCount)
        assertTrue(db.messageDao().observe("apofis-s").first().isEmpty())
    }

    private suspend fun unlistedWithPendingSend(detail: () -> Unit) {
        db.sessionDao().upsert(session("apofis-h"))
        respond(messagesJson("apofis-h", listOf(1L, 2L)))
        repo.syncMessages("apofis-h")
        db.pendingSendDao().upsert(pending("apofis-h", "idem-h"))
        respond(page())
        detail()
        repo.refreshSessions()
        assertEquals(3, server.requestCount)
        assertEquals("/api/sessions/apofis-h", (1..3).map { server.takeRequest() }.last().url.encodedPath)
        assertTrue(repo.sessions().first().isEmpty())
        assertTrue(repo.sessions(archived = true).first().isEmpty())
        assertTrue(db.messageDao().observe("apofis-h").first().isEmpty())
        assertEquals(listOf("idem-h"), db.pendingSendDao().observe("apofis-h").first().map { it.idempotencyKey })
    }

    @Test
    fun hiddenUnlistedChatWithPendingSendDisappears() = runTest {
        unlistedWithPendingSend { respond("""{"object":"hermes.session","session":${sessionJson("apofis-h", hidden = true)}}""") }
    }

    @Test
    fun goneUnlistedChatWithPendingSendDisappears() = runTest {
        unlistedWithPendingSend { respond(fixture("error_404.json"), code = 404) }
    }

    @Test
    fun outcomeOnNoticeFollowsPositionNotId() = runTest {
        db.sessionDao().upsert(session("apofis-s"))
        respond(
            rawMessages(
                "apofis-s",
                listOf(
                    """{"id":10,"role":"user","content":"hi","timestamp":1.0}""",
                    """{"id":20,"role":"assistant","content":"a","timestamp":2.0}""",
                    """{"id":5,"role":"system","display_kind":"model_switch","content":"switch","timestamp":3.0}""",
                    """{"id":30,"role":"user","content":"again","timestamp":4.0}""",
                    """{"id":40,"role":"assistant","content":"b","timestamp":5.0}""",
                ),
            ),
        )
        repo.syncMessages("apofis-s")
        db.replyOutcomeDao().upsert(ReplyOutcomeEntity("apofis-s", 5L, "stopped", null))
        db.replyOutcomeDao().upsert(ReplyOutcomeEntity("apofis-s", 999L, "failed", null)) // unknown row: last reply
        val replies = repo.transcript("apofis-s").first().filterIsInstance<AssistantReply>()
        assertEquals(listOf(ReplyOutcome.STOPPED, ReplyOutcome.FAILED), replies.map { it.outcome })
    }

    @Test
    fun everyUnlistedRetainedChatIsChecked() = runTest {
        val keys = (1..60).map { "apofis-r$it" }
        keys.forEach {
            db.sessionDao().upsert(session(it))
            db.pendingSendDao().upsert(pending(it, "idem-$it"))
        }
        respond(page())
        keys.forEach { respond("""{"object":"hermes.session","session":${sessionJson(it, hidden = true)}}""") }
        repo.refreshSessions()
        assertEquals(61, server.requestCount)
        assertTrue(repo.sessions().first().isEmpty())
        assertTrue(keys.all { db.sessionDao().get(it)!!.hidden })
    }

    @Test
    fun lateToolResultKeepsReplyEligibleForNoticeOutcome() = runTest {
        db.sessionDao().upsert(session("apofis-s"))
        val call = """[{"id":"c1","type":"function","function":{"name":"t","arguments":"{}"}}]"""
        respond(
            rawMessages(
                "apofis-s",
                listOf(
                    """{"id":1,"role":"user","content":"hi","timestamp":1.0}""",
                    """{"id":2,"role":"assistant","content":"","tool_calls":$call,"timestamp":2.0}""",
                    """{"id":3,"role":"system","display_kind":"model_switch","content":"switch","timestamp":3.0}""",
                    """{"id":4,"role":"tool","tool_call_id":"c1","content":"late","timestamp":4.0}""",
                ),
            ),
        )
        repo.syncMessages("apofis-s")
        db.replyOutcomeDao().upsert(ReplyOutcomeEntity("apofis-s", 3L, "stopped", null))
        val replies = repo.transcript("apofis-s").first().filterIsInstance<AssistantReply>()
        assertEquals(listOf(listOf(2L, 4L)), replies.map { it.rowIds })
        assertEquals(listOf(ReplyOutcome.STOPPED), replies.map { it.outcome })
    }

    @Test
    fun titleFromCases() {
        assertEquals("first line", titleFrom("first line\nsecond"))
        assertEquals("real", titleFrom("\n   \n  real  \nmore"))
        assertEquals("a".repeat(60), titleFrom("a".repeat(200)))
        assertEquals("", titleFrom(""))
    }

    @Test
    fun syncWithBlockRunsInsideTheReplace() = runTest {
        db.sessionDao().upsert(session("apofis-s"))
        db.pendingSendDao().upsert(pending("apofis-s", "k"))
        respond(messagesJson("apofis-s", listOf(1, 2)))

        val seen = mutableListOf<Long>()
        var committed = false
        assertTrue(
            repo.syncMessages("apofis-s", afterCommit = { committed = true }) { rows ->
                seen += rows.map { it.id }
                assertEquals(2L, db.messageDao().maxId("apofis-s")) // the new rows are already written
                db.pendingSendDao().delete("k")
            },
        )
        assertEquals(listOf(1L, 2L), seen)
        assertNull(db.pendingSendDao().get("k"))
        assertTrue(committed)

        // A failing block rolls the replace back too.
        respond(messagesJson("apofis-s", listOf(1, 2, 3)))
        assertTrue(expectError { repo.syncMessages("apofis-s", afterCommit = { fail("rolled back") }) { error("boom") } } is IllegalStateException)
        assertEquals(2L, db.messageDao().maxId("apofis-s"))

        // No server copy to read: no request, the block does not run.
        db.sessionDao().upsert(session("apofis-l", existsOnServer = false))
        assertFalse(repo.syncMessages("apofis-l", afterReplace = { fail("must not run") }))
        assertFalse(repo.syncMessages("apofis-l"))
    }

    @Test
    fun ordinarySyncIsCancellableDuringItsWrite() = runTest {
        db.sessionDao().upsert(session("apofis-s"))
        val response = responses.hold(
            MockResponse.Builder().body(messagesJson("apofis-s", listOf(1, 2))).addHeader("Content-Type", "application/json").build(),
        )
        val writing = CompletableDeferred<Unit>()
        repo.beforeSyncWrite = { writing.complete(Unit) }
        val sync = async(Dispatchers.Default) { repo.syncMessages("apofis-s") }
        assertNotNull(withContext(Dispatchers.IO) { server.takeRequest(5, TimeUnit.SECONDS) })
        // Holds the database before the response is let through, so the sync's write has to wait after its fetch.
        val holding = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val holder = launch(Dispatchers.Default) {
            db.withTransaction {
                holding.complete(Unit)
                release.await()
            }
        }
        holding.await()
        response.countDown()
        writing.await() // fetched: its write now waits for the held database
        sync.cancel()
        release.complete(Unit)
        holder.join()

        assertTrue(runCatching { sync.await() }.exceptionOrNull() is CancellationException)
        assertNull(db.messageDao().maxId("apofis-s"))
    }

    @Test
    fun syncReportsWhetherARequestSucceeded() = runTest {
        db.sessionDao().upsert(session("apofis-s"))
        respond(messagesJson("apofis-s", listOf(1)))
        assertTrue(repo.syncMessages("apofis-s"))
    }

    @Test
    fun closedRepositoryTouchesNothing() = runTest {
        var open = true
        val old = ChatRepository(HermesApi(server.url("/").toString(), "secret-key"), db, now = { 1_000L }, active = { open })
        val local = old.createLocalSession()
        val before = db.sessionDao().all()
        open = false

        for (call in listOf<suspend () -> Unit>(
            { old.createLocalSession() },
            { old.delete(local) },
            { old.rename(local, "phantom") },
            { old.setPinned(local, true) },
            { old.setArchived(local, true) },
        )) {
            val e = expectError(call)
            assertTrue(e is IllegalStateException && e.message == "closed")
        }
        old.refreshSessions()
        assertFalse(old.syncMessages(local))

        assertEquals(before, db.sessionDao().all())
        assertEquals(0, server.requestCount)
    }

    // Issue 16 row 4: a stored row is bounded, the cut is shown, and the cut never decides delivery.

    private val cutD1 = "a1".repeat(32)
    private val cutD2 = "b2".repeat(32)

    private fun cutRow(id: Long, role: String, content: String, reasoning: String? = null, calls: String? = null, kind: String? = null) = buildJsonObject {
        put("id", id); put("role", role); put("content", content); put("timestamp", 1.0)
        if (kind != null) put("display_kind", kind)
        if (reasoning != null) put("reasoning", reasoning)
        if (calls != null) put("tool_calls", calls)
    }.toString()

    /** Calls as (id, name, arguments). */
    private fun cutCalls(vararg calls: Triple<String, String, String>) = buildJsonArray {
        for ((id, name, args) in calls) addJsonObject {
            put("id", id)
            put("type", "function")
            putJsonObject("function") {
                put("name", name)
                put("arguments", args)
            }
        }
    }.toString()

    private fun cutHint(digest: String) = "[Image attached at: /home/h/.hermes/cache/native-inputs/api_${digest.take(32)}.jpg]"

    private fun cutBlock(vararg digests: String) =
        "\n\n" + digests.joinToString("\n") { cutHint(it) } + "\n" + digests.joinToString("\n") { "[screenshot]" }

    private fun storedChars(r: MessageEntity) = r.content.length.toLong() + (r.reasoning?.length ?: 0) + (r.toolCallsJson?.length ?: 0)

    private suspend fun assertWithinBound(db: AppDatabase) {
        val rows = db.messageDao().rows("apofis-a")
        assertTrue(rows.isNotEmpty())
        for (r in rows) assertTrue("row ${r.id} stores ${storedChars(r)}", storedChars(r) <= 400_000)
    }

    private fun cutFile(name: String) =
        Room.databaseBuilder(ApplicationProvider.getApplicationContext<Context>(), AppDatabase::class.java, name).allowMainThreadQueries().build()

    /** Syncs [rows] into a fresh file database [name] with [sync], then gives [check] that file opened again (a new process). */
    private suspend fun syncAndReopen(
        name: String,
        rows: List<String>,
        seed: suspend (AppDatabase) -> Unit = {},
        sync: suspend ChatRepository.(AppDatabase) -> Boolean = { syncMessages("apofis-a") },
        check: suspend (AppDatabase, ChatRepository) -> Unit,
    ) {
        val app = ApplicationProvider.getApplicationContext<Context>()
        app.deleteDatabase(name)
        try {
            val first = cutFile(name)
            try {
                first.sessionDao().upsert(session("apofis-a"))
                seed(first)
                respond(rawMessages("apofis-a", rows))
                assertTrue(ChatRepository(HermesApi(server.url("/").toString(), "secret-key"), first, now = { 1_000L }).sync(first))
            } finally {
                first.close()
            }
            val reopened = cutFile(name)
            try {
                check(reopened, ChatRepository(HermesApi(server.url("/").toString(), "secret-key"), reopened, now = { 1_000L }))
            } finally {
                reopened.close()
            }
        } finally {
            app.deleteDatabase(name) // also after a failure in the first half
        }
    }

    @Test
    fun manyLargeToolCallsReadBack() = runTest {
        val calls = cutCalls(*Array(106) { Triple("c$it", "write_file", "y".repeat(25_000)) })
        syncAndReopen("cut-calls.db", listOf(cutRow(1, "user", "go"), cutRow(2, "assistant", "done", calls = calls))) { db, repo ->
            assertWithinBound(db)
            val row = db.messageDao().rows("apofis-a").single { it.role == "assistant" }
            val reply = repo.transcript("apofis-a").first().filterIsInstance<AssistantReply>().single()
            assertTrue(row.droppedToolCalls > 0)
            assertEquals(106, reply.steps.size + row.droppedToolCalls)
            assertEquals(row.droppedToolCalls, reply.droppedToolCalls)
            // The kept calls are the first ones, in order, and as many as fit: the next one would pass the bound.
            val kept = reply.steps.map { (it as ToolStep).callId }
            assertTrue(kept.isNotEmpty())
            assertEquals(List(kept.size) { "c$it" }, kept)
            val next = (Json.parseToJsonElement(storedToolCalls(calls)!!) as JsonArray)[kept.size].toString()
            assertTrue(storedChars(row) + next.length + 1 > 400_000)
            assertEquals("done", reply.text)
            assertFalse(reply.shortened)
        }
    }

    @Test
    fun independentlyLargeFieldsCut() = runTest {
        db.sessionDao().upsert(session("apofis-a"))
        val small = cutCalls(Triple("c1", "terminal", "{}"))
        respond(
            rawMessages(
                "apofis-a",
                listOf(
                    cutRow(1, "user", "go"),
                    cutRow(2, "assistant", "a".repeat(300_000), reasoning = "r".repeat(300_000), calls = small),
                    cutRow(3, "assistant", "b".repeat(399_999) + "😀" + "tail", reasoning = "lost"), // the cut falls inside a pair
                    cutRow(4, "assistant", "c".repeat(100_000), reasoning = "s".repeat(100_000), calls = small), // within the bound
                ),
            ),
        )
        assertTrue(repo.syncMessages("apofis-a"))
        assertWithinBound(db)
        val rows = db.messageDao().rows("apofis-a")
        // Content first, then reasoning, then whole calls.
        assertEquals("a".repeat(300_000), rows[1].content)
        assertEquals("r".repeat(100_000), rows[1].reasoning)
        assertNull(rows[1].toolCallsJson)
        assertEquals(1, rows[1].droppedToolCalls)
        assertTrue(rows[1].shortened)
        // Never a lone high surrogate: the pair is dropped whole and the one character left goes to the reasoning.
        assertEquals("b".repeat(399_999), rows[2].content)
        assertEquals("l", rows[2].reasoning)
        assertEquals(0, rows[2].droppedToolCalls)
        assertTrue(rows[2].shortened)
        // A row within the bound is stored as fetched.
        assertEquals("c".repeat(100_000), rows[3].content)
        assertEquals("s".repeat(100_000), rows[3].reasoning)
        assertEquals(small, rows[3].toolCallsJson)
        assertEquals(0, rows[3].droppedToolCalls)
        assertFalse(rows[3].shortened)
        // One reply folds the three rows: the counts add up and the flag is set by any of them.
        val reply = repo.transcript("apofis-a").first().filterIsInstance<AssistantReply>().single()
        assertEquals(1, reply.droppedToolCalls)
        assertTrue(reply.shortened)
    }

    @Test
    fun singleOversizedCallDropped() = runTest {
        db.sessionDao().upsert(session("apofis-a"))
        // Arguments are cut before storage; only an unbounded name (or id) can carry one call past the bound.
        // The list ends at the first call that does not fit: the small call after it is dropped too.
        val calls = cutCalls(Triple("c1", "terminal", "{}"), Triple("c2", "n".repeat(450_000), "{}"), Triple("c3", "terminal", "{}"))
        respond(rawMessages("apofis-a", listOf(cutRow(1, "user", "go"), cutRow(2, "assistant", "ran", calls = calls))))
        assertTrue(repo.syncMessages("apofis-a"))
        assertWithinBound(db)
        val row = db.messageDao().rows("apofis-a")[1]
        assertEquals("ran", row.content)
        assertEquals(2, row.droppedToolCalls)
        assertFalse(row.shortened)
        val reply = repo.transcript("apofis-a").first().filterIsInstance<AssistantReply>().single()
        assertEquals("c1", (reply.steps.single() as ToolStep).callId)
        assertEquals(2, reply.droppedToolCalls)
        assertFalse(reply.shortened)
    }

    @Test
    fun cutMarkersShownAfterReopen() = runTest {
        val literal = "b".repeat(399_990) + cutBlock(cutD1) // typed text that ends like a marker block, with no binding
        syncAndReopen(
            "cut-markers.db",
            listOf(
                cutRow(1, "user", "a".repeat(450_000)),
                cutRow(2, "assistant", "d".repeat(450_000), calls = cutCalls(Triple("c1", "terminal", "{}"))),
                cutRow(3, "user", literal),
                cutRow(4, "user", "[OUT-OF-BAND USER MESSAGE]\n" + "s".repeat(450_000) + "\n[/OUT-OF-BAND USER MESSAGE]", kind = "steer"),
            ),
        ) { db, repo ->
            assertWithinBound(db)
            val items = repo.transcript("apofis-a").first()
            val users = items.filterIsInstance<UserMessage>()
            assertEquals("a".repeat(400_000), users[0].text)
            assertTrue(users[0].shortened)
            // An unbound row is cut from its tail like any text: shown as stored, never parsed, no placeholder.
            assertEquals(literal.take(400_000), users[1].text)
            assertEquals(literal.take(400_000), users[1].raw)
            assertEquals(emptyList<String?>(), users[1].images)
            assertTrue(users[1].shortened)
            // A cut steer row is cut from its tail too and keeps the mark (its wrapper line shows: a recorded limit).
            assertTrue(users[2].steered)
            assertTrue(users[2].shortened)
            assertEquals(400_000, users[2].raw.length)
            val reply = items.filterIsInstance<AssistantReply>().single()
            assertEquals("d".repeat(400_000), reply.text)
            assertEquals(1, reply.droppedToolCalls)
            assertTrue(reply.shortened)
        }
    }

    @Test
    fun storedBytesWithinWindow() = runTest {
        val euro = "€" // three bytes in UTF-8
        syncAndReopen(
            "cut-bytes.db",
            listOf(
                cutRow(1, "user", euro.repeat(450_000)),
                cutRow(2, "assistant", euro.repeat(300_000), reasoning = euro.repeat(300_000), calls = cutCalls(Triple("c1", euro, euro))),
            ),
        ) { db, repo ->
            assertWithinBound(db)
            for (r in db.messageDao().rows("apofis-a")) {
                val bytes = (r.content + (r.reasoning ?: "") + (r.toolCallsJson ?: "")).toByteArray(Charsets.UTF_8).size
                assertTrue("row ${r.id} stores $bytes bytes", bytes <= 1_200_000) // below the 2 MB cursor window
            }
            assertEquals(euro.repeat(400_000), repo.transcript("apofis-a").first().filterIsInstance<UserMessage>().single().text)
        }
    }

    @Test
    fun oversizedUserRowStillConfirmed() = runTest {
        val text = "t".repeat(450_000)
        val bigCaption = "p".repeat(450_000) // the caption alone passes the bound
        val caption = "q".repeat(399_990) // the caption fits; its marker block pushes the row past the bound
        val confirmed = mutableSetOf<String>()
        syncAndReopen(
            "cut-users.db",
            listOf(cutRow(1, "user", text), cutRow(2, "user", bigCaption + cutBlock(cutD1)), cutRow(3, "user", caption + cutBlock(cutD2))),
            seed = { db ->
                val dao = db.pendingSendDao()
                dao.upsert(PendingSendEntity("k1", "apofis-a", text, "{}", 1_000L, "sending", "run_1", null))
                dao.upsert(PendingSendEntity("k2", "apofis-a", bigCaption, "{}", 1_000L, "sending", "run_1", null, images = cutD1))
                dao.upsert(PendingSendEntity("k3", "apofis-a", caption, "{}", 1_000L, "sending", "run_1", null, images = cutD2))
            },
            // Settlement's own decision (RunFollower.write) on what the transaction hands it.
            sync = { db ->
                syncMessages("apofis-a") { rows ->
                    val shown = buildTranscript(rows).filterIsInstance<UserMessage>()
                    val used = HashSet<String>()
                    for (p in db.pendingSendDao().rows("apofis-a")) if (deliveredIn(p, shown, used)) confirmed += p.idempotencyKey
                }
            },
        ) { db, repo ->
            assertEquals(setOf("k1", "k2", "k3"), confirmed) // none would be marked unconfirmed
            assertWithinBound(db)
            val stored = db.messageDao().rows("apofis-a")
            assertTrue(stored.all { it.shortened })
            assertEquals(text.take(400_000), stored[0].content)
            assertEquals(bigCaption.take(400_000), stored[1].content) // the block dropped whole, never split
            assertEquals(caption, stored[2].content)
            assertFalse(stored[2].content.contains("[Image attached"))
            // The photos stay bound through the cut and the restart, and are shown through the binding.
            assertEquals(mapOf(2L to cutD1, 3L to cutD2), db.imageBindingDao().forSession("apofis-a").associate { it.messageId to it.digests })
            val users = repo.transcript("apofis-a").first().filterIsInstance<UserMessage>()
            assertEquals(listOf(emptyList(), listOf(cutD1), listOf(cutD2)), users.map { it.images })
            assertTrue(users.all { it.shortened })
        }
    }

    @Test
    fun oversizedCaptionLiveSyncShowsOneBubble() = runTest {
        db.sessionDao().upsert(session("apofis-a"))
        val caption = "p".repeat(450_000)
        db.pendingSendDao().upsert(PendingSendEntity("k", "apofis-a", caption, "{}", 1_000L, "sending", "run_1", null, images = cutD1))
        val seen = CopyOnWriteArrayList<List<ChatItem>>()
        val latest = MutableStateFlow<List<ChatItem>?>(null)
        backgroundScope.launch(Dispatchers.IO) { repo.transcript("apofis-a").collect { seen += it; latest.value = it } }
        suspend fun awaitShown(condition: (List<ChatItem>) -> Boolean) =
            withContext(Dispatchers.IO) { withTimeout(10_000) { latest.filterNotNull().first(condition) } }

        awaitShown { items -> items.any { it.key == "pk" } }
        // The live sync during the run: no settlement, so the pending row stays sending beside its cut stored row.
        respond(rawMessages("apofis-a", listOf(cutRow(1, "user", caption + cutBlock(cutD1)), cutRow(2, "assistant", "Looking"))))
        assertTrue(repo.syncMessages("apofis-a"))
        val last = awaitShown { items -> items.any { it is AssistantReply } }

        assertEquals("sending", db.pendingSendDao().get("k")?.state)
        for (items in seen) items.filterIsInstance<UserMessage>().single() // never two bubbles
        val bubble = last.filterIsInstance<UserMessage>().single()
        assertEquals("u1", bubble.key)
        assertEquals(listOf(cutD1), bubble.images)
        assertTrue(bubble.shortened)
        assertEquals("run_1", bubble.runId)
    }

    @Test
    fun blankCutRowMatchesNothing() = runTest {
        db.sessionDao().upsert(session("apofis-a"))
        db.pendingSendDao().upsert(PendingSendEntity("k", "apofis-a", "hello", "{}", 1_000L, "sending", "run_1", null))
        // Kept text all blank: the stored prefix of anything, so it must confirm nothing.
        respond(rawMessages("apofis-a", listOf(cutRow(1, "user", " ".repeat(400_000) + "elsewhere"))))
        assertTrue(repo.syncMessages("apofis-a"))
        val users = repo.transcript("apofis-a").first().filterIsInstance<UserMessage>()
        assertTrue(users[0].shortened)
        assertEquals(listOf("u1", "pk"), users.map { it.key }) // the pending bubble stays
    }

    @Test
    fun droppedCountCountsOnlyCalls() = runTest {
        db.sessionDao().upsert(session("apofis-a"))
        // Under the per-field limit the array is stored as sent, with elements the transcript never shows as calls.
        val calls = """[1,"x",{"name":"no id"},{"id":"c1","function":{"name":"a","arguments":"{}"}},{"call_id":"c2","name":"b"}]"""
        respond(rawMessages("apofis-a", listOf(cutRow(1, "user", "go"), cutRow(2, "assistant", "a".repeat(399_990), calls = calls))))
        assertTrue(repo.syncMessages("apofis-a"))
        assertWithinBound(db)
        val row = db.messageDao().rows("apofis-a")[1]
        assertNull(row.toolCallsJson)
        assertEquals(2, row.droppedToolCalls)
    }

    @Test
    fun cutChecksTheRowsOwnHints() = runTest {
        db.sessionDao().upsert(session("apofis-a"))
        db.imageBindingDao().upsert(ImageBindingEntity("apofis-a", 2, cutD1))
        // Two rows with one id: the short one supports the binding, the long one names another photo.
        val long = "p".repeat(399_990) + cutBlock(cutD2)
        respond(rawMessages("apofis-a", listOf(cutRow(2, "user", "x" + cutBlock(cutD1)), cutRow(2, "user", long))))
        assertTrue(repo.syncMessages("apofis-a"))
        assertEquals(long.take(400_000), db.messageDao().rows("apofis-a")[1].content) // cut from its tail, not parsed
        assertEquals(mapOf(2L to cutD1), db.imageBindingDao().forSession("apofis-a").associate { it.messageId to it.digests })
    }

    @Test
    fun rowRecutWhenItsBindingAppears() = runTest {
        db.sessionDao().upsert(session("apofis-a"))
        val caption = "q".repeat(399_990)
        val raw = caption + cutBlock(cutD1)
        respond(rawMessages("apofis-a", listOf(cutRow(1, "user", raw))))
        assertTrue(repo.syncMessages("apofis-a"))
        assertEquals(raw.take(400_000), db.messageDao().rows("apofis-a").single().content) // no binding yet: the tail cut

        db.imageBindingDao().upsert(ImageBindingEntity("apofis-a", 1, cutD1))
        respond(rawMessages("apofis-a", listOf(cutRow(1, "user", raw))))
        assertTrue(repo.syncMessages("apofis-a"))
        assertEquals(caption, db.messageDao().rows("apofis-a").single().content) // the next sync stores the caption
        assertEquals(listOf(cutD1), repo.transcript("apofis-a").first().filterIsInstance<UserMessage>().single().images)
    }

    @Test
    fun callThatFitsExactlyIsKept() = runTest {
        db.sessionDao().upsert(session("apofis-a"))
        val calls = """[{"id":"a"},{"id":"b"},{"id":"c"}]""" // 34 units; each call costs 10 plus a comma or bracket
        respond(
            rawMessages(
                "apofis-a",
                listOf(
                    cutRow(1, "assistant", "a".repeat(400_000 - 23), calls = calls), // 23 left: exactly two calls
                    cutRow(2, "assistant", "a".repeat(400_000 - 22), calls = calls), // one unit less: one call
                ),
            ),
        )
        assertTrue(repo.syncMessages("apofis-a"))
        assertWithinBound(db)
        val rows = db.messageDao().rows("apofis-a")
        assertEquals("""[{"id":"a"},{"id":"b"}]""", rows[0].toolCallsJson)
        assertEquals(1, rows[0].droppedToolCalls)
        assertEquals("""[{"id":"a"}]""", rows[1].toolCallsJson)
        assertEquals(2, rows[1].droppedToolCalls)
    }
}
