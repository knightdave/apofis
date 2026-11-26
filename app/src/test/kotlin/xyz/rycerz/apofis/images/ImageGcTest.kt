package xyz.rycerz.apofis.images

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.rycerz.apofis.AppContainer
import xyz.rycerz.apofis.CACHE_PREFS
import xyz.rycerz.apofis.SWITCH_MARKER
import xyz.rycerz.apofis.db.AgentFileEntity
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.ImageBindingEntity
import xyz.rycerz.apofis.db.PendingSendEntity
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.settings.CredentialStore
import xyz.rycerz.apofis.settings.Credentials

private const val MINUTE = 60_000L

private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

/** The one image collection, the purge of a server switch and what keeps a file. */
@RunWith(RobolectricTestRunner::class)
class ImageGcTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val server = MockWebServer()
    private val routes = ConcurrentHashMap<String, Pair<Int, String>>() // "METHOD /path" to code and body
    private val requests = CopyOnWriteArrayList<Pair<String, String>>() // "METHOD /path" and body, as served
    private lateinit var a: Credentials
    private lateinit var c: AppContainer

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val target = "${request.method} ${request.target.substringBefore('?')}"
                requests += target to (request.body?.utf8() ?: "")
                val (code, body) = routes[target] ?: (500 to "")
                return MockResponse.Builder().code(code).body(body).addHeader("Content-Type", "application/json").build()
            }
        }
        server.start()
        a = Credentials(server.url("/").toString(), "key-a")
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        c = container()
        wait { c.connect(a) }
    }

    @After
    fun tearDown() {
        db.close()
        server.close()
    }

    private fun <T> wait(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    /** A container over the same database and files: a new process. */
    private fun container() = AppContainer(app, CredentialStore(app) { key }) { db }.also { wait { it.startup.join() } }

    private val dir get() = File(app.filesDir, "images")

    /** An image file [ageMs] old; returns its digest. */
    private fun file(seed: Int, ageMs: Long = 11 * MINUTE): String {
        val bytes = byteArrayOf(seed.toByte(), 7)
        val d = sha(bytes)
        dir.mkdirs()
        File(dir, "$d.jpg").apply { writeBytes(bytes) }.setLastModified(System.currentTimeMillis() - ageMs)
        return d
    }

    private fun age(d: String, ageMs: Long = 11 * MINUTE) = File(dir, "$d.jpg").setLastModified(System.currentTimeMillis() - ageMs)

    private fun exists(d: String) = c.imageStore.file(d) != null

    private fun session(key: String) = wait {
        db.sessionDao().upsert(
            SessionEntity(
                key = key, currentId = key, lineageIds = "", title = "t", pinned = false, archived = false, startedAt = 1.0,
                lastActiveAt = 2.0, messageCount = 0, preview = null, stale = false, existsOnServer = true,
            ),
        )
    }

    private fun bind(key: String, id: Long, vararg digests: String) =
        wait { db.imageBindingDao().upsert(ImageBindingEntity(key, id, digests.joinToString(","))) }

    private fun pending(key: String, idem: String, state: String, vararg images: String, runId: String? = null) = wait {
        db.pendingSendDao().upsert(PendingSendEntity(idem, key, "cap", "{}", 1_000L, state, runId, null, images = images.joinToString(",")))
    }

    private fun bindings(key: String) = wait { db.imageBindingDao().forSession(key) }.map { it.messageId to it.digests }

    private fun deleteAnswers(id: String) {
        routes["DELETE /api/sessions/$id"] = 200 to """{"object":"hermes.session.deleted","id":"$id","deleted":true}"""
    }

    private fun hint(digest: String) = "[Image attached at: /home/h/.hermes/cache/native-inputs/api_${digest.take(32)}.jpg]"

    private fun serveRows(key: String, vararg rows: Pair<Long, String>) {
        val data = rows.joinToString(",") { (id, content) ->
            """{"id":$id,"role":"user","display_kind":"internal_notification","content":${kotlinx.serialization.json.JsonPrimitive(content)},"timestamp":1.0}"""
        }
        routes["GET /api/sessions/$key/messages"] = 200 to """{"object":"list","session_id":"$key","data":[$data]}"""
    }

    /**
     * Deletes [key] and waits until the collection that Delete started has finished, without running one itself: the
     * pass signals from inside the store's lock, so taking the lock afterwards waits for that pass to end.
     */
    private fun deleteAndAwaitItsCollection(key: String) {
        val started = CompletableDeferred<Unit>()
        c.beforeImageRows = { c.beforeImageRows = {}; started.complete(Unit) }
        wait { c.connected()!!.repo.delete(key) }
        wait { started.await() } // fails on the watchdog when the Delete starts no collection
        wait { c.imageStore.lock.withLock { } }
    }

    private fun readyDraft(key: String, d: String) = c.composer.setImage(c.composer.addImages(key, 1).single(), d)

    @Test
    fun gcKeepsPendingAndBoundImages() {
        session("apofis-s")
        val p1 = file(1)
        val p2 = file(2)
        val b = file(3)
        val loose = file(4)
        pending("apofis-s", "k", "queued", p1, p2)
        bind("apofis-s", 1, b)
        wait { c.collectImages() }
        assertTrue(exists(p1) && exists(p2) && exists(b))
        assertFalse(exists(loose))
    }

    @Test
    fun gcKeepsUnsentImages() {
        val d = file(1)
        c.composer.edit("apofis-s", "cap", c.composer.current("apofis-s").revision)
        readyDraft("apofis-s", d)
        wait { c.composer.sendWithImages("apofis-s") { _, _ -> throw IllegalStateException("not stored") }!!.join() }
        wait { c.collectImages() }
        assertTrue(exists(d))
        c.composer.discard("apofis-s", c.composer.current("apofis-s").unsent!!.id)
        wait { c.collectImages() }
        assertFalse(exists(d))
    }

    @Test
    fun confirmationBeforeRowReadKeepsImage() {
        session("apofis-s")
        val d = file(1)
        pending("apofis-s", "k", "sending", d, runId = "run_1")
        serveRows("apofis-s", 1L to "cap\n\n${hint(d)}\n[screenshot]")
        val repo = c.connected()!!.repo
        // The confirmation runs between the composer's read and the database's: the run end's sync writes the binding
        // and removes the pending row in one transaction, and the collection then finds the digest in the binding.
        // This does not pin that the collection reads both tables in one transaction.
        c.beforeImageRows = {
            c.beforeImageRows = {}
            assertTrue(repo.syncMessages("apofis-s", afterReplace = { _ -> db.pendingSendDao().delete("k") }))
        }
        wait { c.collectImages() }
        assertEquals(listOf(1L to d), bindings("apofis-s"))
        assertNull(wait { db.pendingSendDao().get("k") })
        assertTrue(exists(d))
    }

    @Test
    fun gcDuringSendStoreKeepsImage() {
        session("apofis-s")
        val d = file(1) // an eleven minute old draft photo
        c.composer.edit("apofis-s", "look", c.composer.current("apofis-s").revision)
        readyDraft("apofis-s", d)
        val repo = c.connected()!!.repo
        c.beforeImageRows = {
            c.beforeImageRows = {}
            c.composer.sendWithImages("apofis-s") { t, i -> repo.send("apofis-s", t, i, onStored = { stored() }) }!!.join()
            assertEquals(emptyList<Any>(), c.composer.current("apofis-s").images) // the draft emptied after the row was stored
        }
        wait { c.collectImages() }
        assertEquals(listOf(d), wait { db.pendingSendDao().allImages() })
        assertTrue(exists(d))
    }

    @Test
    fun evictedChatPhotosCollected() {
        session("apofis-gone")
        session("apofis-kept")
        val gone = file(1)
        val kept = file(2)
        bind("apofis-gone", 1, gone)
        bind("apofis-kept", 1, kept)
        pending("apofis-kept", "k", "failed") // unsent work keeps the chat, hidden, once the server drops it
        routes["GET /api/sessions"] = 200 to """{"object":"list","data":[],"has_more":false}"""
        routes["GET /api/sessions/apofis-kept"] = 404 to """{"error":{"code":"session_not_found"}}"""
        wait { c.connected()!!.repo.refreshSessions() }
        assertNull(wait { db.sessionDao().get("apofis-gone") })
        assertTrue(wait { db.sessionDao().get("apofis-kept") }!!.hidden)

        wait { c.collectImages() }
        assertEquals(emptyList<Any>(), bindings("apofis-gone"))
        assertFalse(exists(gone))
        assertEquals(listOf(1L to kept), bindings("apofis-kept"))
        assertTrue(exists(kept))
    }

    @Test
    fun deletedChatOldPhotosCollected() {
        session("apofis-x")
        val old = file(1)
        val young = file(2, ageMs = MINUTE)
        bind("apofis-x", 1, old, young)
        deleteAnswers("apofis-x")
        deleteAndAwaitItsCollection("apofis-x")
        assertEquals(emptyList<Any>(), bindings("apofis-x"))
        assertFalse(exists(old))
        assertTrue(exists(young))
        age(young)
        wait { c.collectImages() } // a later pass, once the young file is old enough
        assertFalse(exists(young))
    }

    @Test
    fun sharedFileBetweenTwoChatsKept() {
        session("apofis-1")
        session("apofis-2")
        val d = file(1)
        bind("apofis-1", 1, d)
        bind("apofis-2", 5, d)
        deleteAnswers("apofis-1")
        deleteAnswers("apofis-2")
        deleteAndAwaitItsCollection("apofis-1")
        assertTrue(exists(d))
        deleteAndAwaitItsCollection("apofis-2")
        assertFalse(exists(d))
    }

    @Test
    fun droppedBindingPhotoCollected() {
        session("apofis-s")
        session("apofis-t")
        val rewritten = file(1, ageMs = 5 * MINUTE)
        val pendingToo = file(2, ageMs = 5 * MINUTE)
        val boundElsewhere = file(3, ageMs = 5 * MINUTE)
        bind("apofis-s", 1, rewritten)
        bind("apofis-s", 2, pendingToo)
        bind("apofis-s", 3, boundElsewhere)
        bind("apofis-t", 1, boundElsewhere)
        pending("apofis-s", "k", "failed", pendingToo)
        serveRows("apofis-s", 1L to "rewritten") // row 1 lost its hints; rows 2 and 3 vanished
        assertTrue(wait { c.connected()!!.repo.syncMessages("apofis-s") })
        assertEquals(emptyList<Any>(), bindings("apofis-s"))
        wait { c.collectImages() }
        assertTrue(exists(rewritten)) // younger than ten minutes

        listOf(rewritten, pendingToo, boundElsewhere).forEach { age(it) }
        c = container() // the first collection after a restart
        assertFalse(exists(rewritten))
        assertTrue(exists(pendingToo))
        assertTrue(exists(boundElsewhere))
    }

    @Test
    fun urlSwitchPurgesImages() {
        val drafted = file(1, ageMs = 0)
        val loose = file(2, ageMs = 0)
        readyDraft("apofis-s", drafted)
        wait { c.connect(Credentials("https://b.example/", "key-b")) }
        assertEquals(emptyList<String>(), dir.list().orEmpty().toList())
        assertEquals(emptySet<String>(), c.composer.keys)
        assertFalse(exists(loose))
    }

    @Test
    fun sameServerReconnectKeepsImages() {
        val drafted = file(1)
        val young = file(2, ageMs = MINUTE)
        val old = file(3)
        readyDraft("apofis-s", drafted)
        wait { c.connect(a.copy(apiKey = "key-2")) }
        assertTrue(exists(drafted))
        assertTrue(exists(young))
        assertFalse(exists(old)) // the collection after the switch ran
        assertEquals(listOf(drafted), c.composer.current("apofis-s").images.map { it.digest })
    }

    @Test
    fun interruptedSwitchPurgedAtNextConnect() {
        val d = file(1, ageMs = 0)
        // What a death right after the switch's first step leaves: the owner removed, the mark still set.
        assertTrue(app.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE).edit().remove("cache_base_url").putBoolean(SWITCH_MARKER, true).commit())
        c = container()
        assertNull(c.connected())
        assertTrue(exists(d)) // nothing is purged before a connect
        wait { c.connect(a) } // the same server again: the owner is unknown, so the cache goes
        assertFalse(exists(d))
    }

    @Test
    fun thumbCacheDroppedOnSwitch() {
        val first = c.connected()!!.thumbs
        assertSame(first, c.connection.value!!.thumbs)
        wait { c.connect(a.copy(apiKey = "key-2")) }
        val second = c.connected()!!.thumbs
        assertNotSame(first, second)
        assertSame(second, c.connection.value!!.thumbs)
        assertEquals(0, second.size())
    }

    @Test
    fun reattachedOldFileSurvivesGc() {
        val d = file(1)
        val bytes = File(dir, "$d.jpg").readBytes()
        c.convertImage = { Encoded.Ok(bytes) }
        wait { c.connected()!!.attachImages("apofis-s", listOf(Uri.parse("content://picker/1")))!!.join() }
        val image = c.composer.current("apofis-s").images.single()
        assertEquals(d, image.digest)
        c.composer.removeImage("apofis-s", image.id) // unreferenced again, but its put made it young
        wait { c.collectImages() }
        assertTrue(exists(d))
    }

    @Test
    fun removeDuringSendKeepsPhoto() {
        session("apofis-s")
        val d = file(1) // eleven minutes old
        c.composer.edit("apofis-s", "look", c.composer.current("apofis-s").revision)
        val id = c.composer.addImages("apofis-s", 1).single().also { c.composer.setImage(it, d) }
        val repo = c.connected()!!.repo
        val gate = CompletableDeferred<Unit>()
        val send = c.composer.sendWithImages("apofis-s") { t, i ->
            gate.await() // before its row commits
            repo.send("apofis-s", t, i, onStored = { stored() })
        }!!
        c.composer.removeImage("apofis-s", id)
        assertEquals(listOf(d), c.composer.current("apofis-s").images.map { it.digest })
        wait { c.collectImages() }
        assertTrue(exists(d))
        gate.complete(Unit)
        wait { send.join() }
        assertEquals(listOf(d), wait { db.pendingSendDao().allImages() })
        assertTrue(exists(d))
    }

    @Test
    fun deleteReturnsWhileCollectionHeld() {
        session("apofis-x")
        deleteAnswers("apofis-x")
        val entered = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        c.beforeImageRows = { entered.complete(Unit); gate.await() }
        var after = false
        val repo = c.connected()!!.repo
        runBlocking {
            val deleting = launch(Dispatchers.Default) {
                repo.delete("apofis-x")
                after = true // the drawer's cleanup after a Delete
            }
            try {
                // The watchdog fails the test if the Delete waits for the held collection, also uncancellably: the gate
                // opens in the finally, so the child always ends before this scope is left.
                withTimeout(10_000) {
                    entered.await()
                    deleting.join()
                }
            } finally {
                c.beforeImageRows = {}
                gate.complete(Unit)
            }
        }
        assertTrue(after)
        assertNull(wait { db.sessionDao().get("apofis-x") })
        wait { c.imageStore.lock.withLock { } } // the released pass has ended
    }

    @Test
    fun purgeIgnoresAgeOrdinaryCollectionDoesNot() {
        val young = file(1, ageMs = 0)
        val future = file(2, ageMs = -60 * MINUTE) // dated an hour ahead
        wait { c.collectImages() }
        assertTrue(exists(young))
        assertTrue(exists(future))
        wait { c.connect(Credentials("https://b.example/", "key-b")) }
        assertFalse(exists(young))
        assertFalse(exists(future))
        assertEquals(emptyList<String>(), dir.list().orEmpty().toList())
    }

    @Test
    fun picturesReachTheRunInPickOrder() {
        session("apofis-s")
        routes["POST /v1/runs"] = 202 to """{"run_id":"run_1","status":"started","replayed":false}"""
        val first = byteArrayOf(1, 2, 3)
        val second = byteArrayOf(4, 5, 6)
        c.convertImage = { Encoded.Ok(if (it.lastPathSegment == "1") first else second) }
        val conn = c.connected()!!
        wait { conn.attachImages("apofis-s", listOf(Uri.parse("content://picker/1"), Uri.parse("content://picker/2")))!!.join() }
        c.composer.edit("apofis-s", "two", c.composer.current("apofis-s").revision)
        wait { c.composer.sendWithImages("apofis-s") { t, i -> conn.repo.send("apofis-s", t, i, onStored = { stored() }) }!!.join() }
        val runs = requests.filter { it.first == "POST /v1/runs" }
        assertEquals(1, runs.size)
        val body = runs.single().second
        val a = body.indexOf(Base64.getEncoder().encodeToString(first))
        val b = body.indexOf(Base64.getEncoder().encodeToString(second))
        assertTrue("both pictures in the body", a >= 0 && b >= 0)
        assertTrue("in pick order", a < b)
    }

    @Test
    fun restartCollectsDraftOnlyFilesAndDecodeTemps() {
        session("apofis-s")
        val drafted = file(1)
        val queued = file(2)
        readyDraft("apofis-s", drafted)
        pending("apofis-s", "k", "failed", queued)
        val temp = File(app.cacheDir, "decode-x.tmp").apply { writeBytes(byteArrayOf(1)) }
        wait { c.collectImages() }
        assertTrue(exists(drafted)) // the draft still names it

        c = container() // a process death: the draft is lost, the pending row is not
        assertFalse(exists(drafted))
        assertTrue(exists(queued))
        assertFalse(temp.exists())
    }

    private fun agentFile(key: String, id: Long, digest: String) =
        wait { db.agentFileDao().insert(AgentFileEntity(key, id, "/srv/out/chart.png", digest)) }

    @Test
    fun gcKeepsAgentFileDigests() {
        session("apofis-s")
        val bound = file(1)
        val loose = file(2)
        agentFile("apofis-s", 1, bound)
        wait { c.collectImages() }
        assertTrue(exists(bound))
        assertFalse(exists(loose))
    }

    @Test
    fun deletedChatAgentFilesGo() {
        session("apofis-x")
        session("apofis-y") // a live chat: its binding and file must survive
        val gone = file(1)
        val kept = file(2)
        agentFile("apofis-x", 1, gone)
        agentFile("apofis-y", 1, kept)
        assertEquals(setOf(gone, kept), wait { db.agentFileDao().digests() }.toSet())
        deleteAnswers("apofis-x")
        deleteAndAwaitItsCollection("apofis-x")
        assertEquals(listOf(kept), wait { db.agentFileDao().digests() })
        assertFalse(exists(gone))
        assertTrue(exists(kept))
    }
}
