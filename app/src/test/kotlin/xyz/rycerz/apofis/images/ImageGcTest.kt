package xyz.rycerz.apofis.images

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.KeyGenerator
import kotlinx.coroutines.runBlocking
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
    private lateinit var a: Credentials
    private lateinit var c: AppContainer

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val (code, body) = routes["${request.method} ${request.target.substringBefore('?')}"] ?: (500 to "")
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
            """{"id":$id,"role":"user","content":${kotlinx.serialization.json.JsonPrimitive(content)},"timestamp":1.0}"""
        }
        routes["GET /api/sessions/$key/messages"] = 200 to """{"object":"list","session_id":"$key","data":[$data]}"""
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
    fun gcDuringConfirmationKeepsImage() {
        session("apofis-s")
        val d = file(1)
        pending("apofis-s", "k", "sending", d, runId = "run_1")
        serveRows("apofis-s", 1L to "cap\n\n${hint(d)}\n[screenshot]")
        val repo = c.connected()!!.repo
        // The confirmation runs between the composer's read and the database's: the run end's sync writes the binding
        // and removes the pending row in one transaction.
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
        wait { c.connected()!!.repo.delete("apofis-x") }
        assertEquals(emptyList<Any>(), bindings("apofis-x"))
        assertFalse(exists(old)) // gone when the Delete returns
        assertTrue(exists(young))
        age(young)
        wait { c.collectImages() }
        assertFalse(exists(young))
    }

    @Test
    fun jobResultBetweenPutAndDraftKept() {
        val d = file(1)
        val bytes = File(dir, "$d.jpg").readBytes()
        // The steps of a processing job, with a collection between its put and its setImage.
        val id = c.composer.addImages("apofis-s", 1).single()
        assertEquals(d, wait { c.imageStore.put(bytes) })
        wait { c.collectImages() }
        assertTrue(exists(d))
        c.composer.setImage(id, d)
        age(d)
        wait { c.collectImages() }
        assertTrue(exists(d)) // now the draft names it
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
        wait { c.connected()!!.repo.delete("apofis-1") }
        assertTrue(exists(d))
        wait { c.connected()!!.repo.delete("apofis-2") }
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
}
