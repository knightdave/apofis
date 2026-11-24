package xyz.rycerz.apofis.chat

import android.app.Application
import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.File
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import java.util.concurrent.atomic.AtomicBoolean
import xyz.rycerz.apofis.Connected
import xyz.rycerz.apofis.images.ImageStore
import xyz.rycerz.apofis.run.RunFollower
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.rycerz.apofis.AppContainer
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.db.ActiveRunEntity
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.images.Encoded
import xyz.rycerz.apofis.images.ImageFailure
import xyz.rycerz.apofis.settings.CredentialStore
import xyz.rycerz.apofis.settings.Credentials
import xyz.rycerz.apofis.sync.ChatRepository
import xyz.rycerz.apofis.sync.RunStarter
import xyz.rycerz.apofis.sync.SendQueue

private const val K = "apofis-a"
private val A = Credentials("https://a.example/", "key-a")

private fun sha(b: ByteArray) = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

private val D1 = sha(byteArrayOf(1))
private val D2 = sha(byteArrayOf(2))

/** Draft photos in the composer, their send through the repository and their conversion on the connection. */
@RunWith(RobolectricTestRunner::class)
class DraftImagesTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val composer = Composer(scope)
    private val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
    private val server = MockWebServer()
    private val routes = HashMap<String, String>()

    @Before
    fun setUp() {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = routes[request.target.substringBefore('?')]
                ?.let { MockResponse.Builder().body(it).addHeader("Content-Type", "application/json").build() }
                ?: MockResponse.Builder().code(500).build()
        }
        server.start()
    }

    @After
    fun tearDown() {
        scope.cancel()
        db.close()
        server.close()
    }

    private fun <T> wait(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun type(key: String, text: String) = composer.edit(key, text, composer.current(key).revision)

    /** Photos added and set ready with [digests], in order. */
    private fun ready(key: String, vararg digests: String) =
        composer.addImages(key, digests.size).also { ids -> ids.zip(digests).forEach { (id, d) -> composer.setImage(id, d) } }

    private fun repo(imageSize: (String) -> Long? = { 100L }): ChatRepository {
        val api = HermesApi(server.url("/").toString(), "secret-key")
        val queue = SendQueue(api, db, RunStarter { _, _ -> }, { 1_000L }, imageSize = imageSize)
        return ChatRepository(api, db, { 1_000L }, queue)
    }

    private fun addSession(key: String = K) = wait {
        db.sessionDao().upsert(
            SessionEntity(
                key = key, currentId = key, lineageIds = "", title = null, pinned = false, archived = false, startedAt = 1.0,
                lastActiveAt = null, messageCount = 0, preview = null, stale = false, existsOnServer = true,
            ),
        )
    }

    /** Sends through [repo] the way the view model will; returns null when nothing started. */
    private fun sendVia(repo: ChatRepository) = composer.sendWithImages(K) { text, images -> repo.send(K, text, images, onStored = { stored() }) }

    private fun container(): AppContainer {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        return AppContainer(app, CredentialStore(app) { key }) { db }.also { wait { it.startup.join() } }
    }

    private fun uri(i: Int) = Uri.parse("content://picker/$i")

    private fun bytesOf(uri: Uri) = byteArrayOf(uri.lastPathSegment!!.toInt().toByte())

    private fun imagesDir() = File(app.filesDir, "images")

    @Test
    fun capAtTen() {
        assertEquals(7, composer.addImages(K, 7).size)
        assertNull(composer.current(K).note)
        assertEquals(3, composer.addImages(K, 5).size) // only what fits
        assertEquals(ComposerNote(NoteKind.IMAGES_LIMIT), composer.current(K).note)
        assertEquals(emptyList<Long>(), composer.addImages(K, 1))
        assertEquals(10, composer.current(K).images.size)
        assertEquals(10, composer.current(K).images.map { it.id }.toSet().size) // ids unique

        // The connection: a picker answer of twelve is cut to ten before anything is converted.
        val c = container()
        wait { c.connect(A) }
        val converted = Collections.synchronizedList(mutableListOf<Uri>())
        c.convertImage = { converted += it; Encoded.Ok(bytesOf(it)) }
        val job = c.connected()!!.attachImages("apofis-b", (1..12).map(::uri))!!
        wait { job.join() }
        assertEquals((1..10).map(::uri), converted)
        assertEquals(10, c.composer.current("apofis-b").images.count { it.digest != null })
        assertEquals(ComposerNote(NoteKind.IMAGES_LIMIT), c.composer.current("apofis-b").note)
    }

    @Test
    fun sendWaitsForProcessing() {
        type(K, "look")
        val (id) = composer.addImages(K, 1)
        val stores = mutableListOf<Pair<String, List<String>>>()
        assertNull(composer.sendWithImages(K) { t, i -> stores += t to i })
        assertFalse(composer.current(K).sending)
        composer.setImage(id, D1)
        wait { composer.sendWithImages(K) { t, i -> stores += t to i }!!.join() }
        assertEquals(listOf("look" to listOf(D1)), stores)
        assertEquals(emptyList<DraftImage>(), composer.current(K).images)
        assertEquals("", composer.current(K).draft)
    }

    @Test
    fun blankTextWithImageSends() {
        ready(K, D1)
        // The entry for drafts without images starts nothing while the draft holds one.
        assertNull(composer.send(K) { error("not for images") })
        val stores = mutableListOf<Pair<String, List<String>>>()
        wait { composer.sendWithImages(K) { t, i -> stores += t to i }!!.join() }
        assertEquals(listOf("" to listOf(D1)), stores)
        assertEquals(emptyList<DraftImage>(), composer.current(K).images)
        // Nothing at all: still refused.
        assertNull(composer.sendWithImages(K) { _, _ -> error("blank") })
    }

    @Test
    fun failedImageDropped() {
        type(K, "two")
        ready(K, D1)
        val (failed) = composer.addImages(K, 1)
        composer.setImage(failed, null)
        assertEquals(ComposerNote(NoteKind.IMAGE_FAILED), composer.current(K).note)
        val stores = mutableListOf<List<String>>()
        wait { composer.sendWithImages(K) { _, i -> stores += i }!!.join() }
        assertEquals(listOf(listOf(D1)), stores)
        assertEquals(emptyList<DraftImage>(), composer.current(K).images) // the failed thumb left with the send

        // Only a failed photo and no text: nothing to send.
        composer.setImage(composer.addImages(K, 1).single(), null)
        assertNull(composer.sendWithImages(K) { _, _ -> error("nothing ready") })
    }

    @Test
    fun unsentKeepsImages() {
        type(K, "first")
        ready(K, D1)
        wait { composer.sendWithImages(K) { _, _ -> throw IllegalStateException("not stored") }!!.join() }
        val unsent = composer.current(K).unsent!!
        assertEquals("first", unsent.text)
        assertEquals(listOf(D1), unsent.images)
        assertEquals(emptyList<DraftImage>(), composer.current(K).images)

        type(K, "second")
        ready(K, D2)
        composer.putBack(K)
        val s = composer.current(K)
        assertEquals("first\nsecond", s.draft)
        assertEquals(listOf(D1, D2), s.images.map { it.digest })
        assertEquals(2, s.images.map { it.id }.toSet().size)
        assertNull(s.unsent)
    }

    @Test
    fun removeWhileProcessing() {
        type(K, "text")
        val (id) = composer.addImages(K, 1)
        composer.removeImage(K, id)
        composer.setImage(id, D1) // the job finishing later sets nothing
        assertEquals(emptyList<DraftImage>(), composer.current(K).images)
        val stores = mutableListOf<List<String>>()
        wait { composer.sendWithImages(K) { _, i -> stores += i }!!.join() }
        assertEquals(listOf(emptyList<String>()), stores)
    }

    @Test
    fun oversizedSendRefusedDraftKept() {
        addSession()
        val repo = repo(imageSize = { 9_500_000L }) // expanded, over the wire budget
        type(K, "cap")
        ready(K, D1)
        wait { sendVia(repo)!!.join() }
        val s = composer.current(K)
        assertEquals("cap", s.draft)
        assertEquals(listOf(D1), s.images.map { it.digest })
        assertFalse(s.sending)
        assertNull(s.unsent)
        assertEquals(ComposerNote(NoteKind.IMAGES_TOO_LARGE), s.note)
        assertTrue(wait { db.pendingSendDao().allImages() }.isEmpty())
        assertEquals(0, server.requestCount)
    }

    @Test
    fun captionEditedAfterAttachCounted() {
        addSession()
        val repo = repo()
        val (id) = composer.addImages(K, 1)
        type(K, "short")
        composer.setImage(id, D1)
        type(K, "x".repeat(800_000)) // edited after the attach: with its copy in the body, over the row budget
        wait { sendVia(repo)!!.join() }
        assertEquals(800_000, composer.current(K).draft.length)
        assertEquals(ComposerNote(NoteKind.IMAGES_TOO_LARGE), composer.current(K).note)

        type(K, "edited")
        routes["/v1/runs"] = """{"run_id":"run_1","status":"started","replayed":false}"""
        wait { sendVia(repo)!!.join() }
        assertEquals(listOf("edited"), wait { db.pendingSendDao().rows(K) }.map { it.text })
    }

    @Test
    fun imageSendQueuesDuringRun() {
        addSession()
        wait { db.activeRunDao().upsert(ActiveRunEntity("run_1", K, null, "running", 1L)) }
        type(K, "look")
        ready(K, D1)
        wait { sendVia(repo())!!.join() }
        val row = wait { db.pendingSendDao().rows(K) }.single()
        assertEquals("queued", row.state) // never a steer of the running reply
        assertEquals(D1, row.images)
        assertEquals(0, server.requestCount)
        assertEquals(emptyList<DraftImage>(), composer.current(K).images)
    }

    @Test
    fun referencedImagesCoversDrafts() {
        ready(K, D1)
        composer.addImages(K, 1) // processing: no file yet
        composer.setImage(composer.addImages("apofis-b", 1).single(), null) // failed
        type("apofis-c", "x")
        ready("apofis-c", D2)
        assertEquals(setOf(D1, D2), composer.referencedImages())

        val d3 = sha(byteArrayOf(3))
        ready("apofis-d", d3)
        wait { composer.sendWithImages("apofis-d") { _, _ -> throw IllegalStateException("not stored") }!!.join() }
        assertEquals(setOf(D1, D2, d3), composer.referencedImages()) // unsent photos too
        composer.remove("apofis-c")
        assertEquals(setOf(D1, d3), composer.referencedImages())
    }

    @Test
    fun hintTitleStoredAsUntitled() {
        fun session(id: String, title: String) =
            """{"id":"$id","source":"api_server","title":"$title","pinned":false,"archived":false,"hidden":false,""" +
                """"started_at":1.0,"last_active":2.0,"message_count":1,"preview":"p","_lineage_ids":[]}"""
        routes["/api/sessions"] = """{"object":"list","data":[""" +
            session("apofis-img", "[Image attached at: /home/h/.hermes/cache/native-inputs/api_${D1.take(32)}.jpg]") + "," +
            session("apofis-txt", "Hello [Image attached") + """],"has_more":false}"""
        wait { repo().refreshSessions() }
        assertNull(wait { db.sessionDao().get("apofis-img") }!!.title)
        assertEquals("Hello [Image attached", wait { db.sessionDao().get("apofis-txt") }!!.title)
    }

    @Test
    fun tenPickedAllBecomeReady() {
        val c = container()
        wait { c.connect(A) }
        val order = Collections.synchronizedList(mutableListOf<Uri>())
        val inFlight = AtomicInteger()
        var overlapped = false
        c.convertImage = {
            if (inFlight.incrementAndGet() > 1) overlapped = true
            order += it
            Encoded.Ok(bytesOf(it)).also { inFlight.decrementAndGet() }
        }
        wait { c.connected()!!.attachImages(K, (1..10).map(::uri))!!.join() }
        val images = c.composer.current(K).images
        assertEquals((1..10).map { sha(byteArrayOf(it.toByte())) }, images.map { it.digest })
        assertEquals((1..10).map(::uri), order)
        assertFalse(overlapped)
        images.forEach { assertNotNull(c.imageStore.file(it.digest!!)) }
    }

    @Test
    fun secondPickQueuesBehindFirst() {
        // A connection whose scope runs on a test scheduler: each step runs exactly what is ready, no real time involved.
        val scheduler = TestCoroutineScheduler()
        val scope = CoroutineScope(SupervisorJob() + StandardTestDispatcher(scheduler))
        val gate = CompletableDeferred<Unit>()
        val order = mutableListOf<Uri>()
        var inFlight = 0
        var overlapped = false
        val api = HermesApi(server.url("/").toString(), "secret-key")
        val queue = SendQueue(api, db, RunStarter { _, _ -> }, { 1_000L })
        val repo = ChatRepository(api, db, { 1_000L }, queue)
        val conn = Connected(
            A, api, db, repo, queue, RunFollower(api, db, repo, scope), AtomicBoolean(true), scope, composer, 0,
            ImageStore(imagesDir(), System::currentTimeMillis),
            { uri ->
                if (++inFlight > 1) overlapped = true
                order += uri
                if (uri == uri(1)) gate.await()
                inFlight--
                Encoded.Failed(ImageFailure.UNREADABLE) // no file, so nothing here leaves the scheduler
            },
        )
        val first = conn.attachImages(K, listOf(uri(1), uri(2)))!!
        scheduler.runCurrent() // job 1 takes the lock and is held in the converter
        val second = conn.attachImages(K, listOf(uri(3)))!!
        scheduler.runCurrent() // job 2 runs as far as it can: to the lock
        assertEquals(listOf(uri(1)), order)
        assertEquals(3, composer.current(K).images.count { it.processing })
        gate.complete(Unit)
        scheduler.advanceUntilIdle()
        assertTrue(first.isCompleted && second.isCompleted)
        assertEquals(listOf(uri(1), uri(2), uri(3)), order)
        assertFalse(overlapped)
        scope.cancel()
    }

    @Test
    fun switchCompletesWhileDecoderGated() {
        val c = container()
        wait { c.connect(A) }
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        // A decoder that does not return on cancellation until its gate opens.
        c.convertImage = { entered.complete(Unit); withContext(NonCancellable) { gate.await() }; Encoded.Ok(bytesOf(it)) }
        c.composer.edit(K, "draft", c.composer.current(K).revision)
        val job = c.connected()!!.attachImages(K, listOf(uri(1)))!!
        wait { entered.await() }

        wait { c.connect(Credentials("https://b.example/", "key-b")) } // completes with the decoder still held
        assertEquals(emptySet<String>(), c.composer.keys)
        gate.complete(Unit)
        wait { job.join() }
        assertEquals(emptySet<String>(), c.composer.keys) // nothing set under any key of the new connection
        assertEquals(emptyList<String>(), imagesDir().list().orEmpty().toList())
    }

    @Test
    fun keyChangeAtSameServerFailsProcessingImage() {
        val c = container()
        wait { c.connect(A) }
        val gate = CompletableDeferred<Unit>()
        val entered = CompletableDeferred<Unit>()
        c.convertImage = { entered.complete(Unit); gate.await(); Encoded.Ok(bytesOf(it)) }
        c.composer.edit(K, "caption", c.composer.current(K).revision)
        val job = c.connected()!!.attachImages(K, listOf(uri(1)))!!
        wait { entered.await() }
        assertNull(c.composer.sendWithImages(K) { _, _ -> error("processing") })

        wait { c.connect(A.copy(apiKey = "key-2")) }
        wait { job.join() }
        val s = c.composer.current(K)
        assertEquals("caption", s.draft) // the draft is kept at the same server
        assertTrue(s.images.single().failed)
        assertEquals(ComposerNote(NoteKind.IMAGE_FAILED), s.note)
        gate.complete(Unit)
        val stores = mutableListOf<List<String>>()
        wait { c.composer.sendWithImages(K) { _, i -> stores += i }!!.join() } // Send works again
        assertEquals(listOf(emptyList<String>()), stores)
        assertEquals(emptyList<String>(), imagesDir().list().orEmpty().toList())
    }

    @Test
    fun moreThanTenPutBackRefused() {
        val ten = (1..10).map { sha(byteArrayOf(it.toByte(), 9)) }
        ready(K, *ten.toTypedArray())
        wait { composer.sendWithImages(K) { _, _ -> throw IllegalStateException("not stored") }!!.join() }
        val one = ready(K, D1).single()
        composer.putBack(K)
        assertNull(composer.current(K).unsent)
        assertEquals(11, composer.current(K).images.size)
        assertNull(composer.sendWithImages(K) { _, _ -> error("over the cap") })
        val s = composer.current(K)
        assertEquals(ComposerNote(NoteKind.IMAGES_LIMIT), s.note)
        assertEquals(11, s.images.size)
        assertFalse(s.sending)
        composer.removeImage(K, one)
        val stores = mutableListOf<List<String>>()
        wait { composer.sendWithImages(K) { _, i -> stores += i }!!.join() }
        assertEquals(listOf(ten), stores)
    }

    @Test
    fun twentyJoinedInUnsentRefusedAndReferenced() {
        val all = (1..20).map { sha(byteArrayOf(it.toByte(), 9)) }
        for (part in all.chunked(10)) {
            ready(K, *part.toTypedArray())
            wait { composer.sendWithImages(K) { _, _ -> throw IllegalStateException("not stored") }!!.join() }
        }
        assertEquals(all, composer.current(K).unsent!!.images)
        composer.putBack(K)
        assertEquals(all, composer.current(K).images.map { it.digest })
        assertNull(composer.sendWithImages(K) { _, _ -> error("over the cap") })
        assertEquals(ComposerNote(NoteKind.IMAGES_LIMIT), composer.current(K).note)
        assertEquals(all.toSet(), composer.referencedImages())
    }

    @Test
    fun blankPartsNotJoined() {
        fun fail(text: String, vararg digests: String) {
            type(K, text)
            ready(K, *digests)
            wait { composer.sendWithImages(K) { _, _ -> throw IllegalStateException("not stored") }!!.join() }
        }
        // Put back: a blank unsent text leaves the draft as it is; a blank draft takes the unsent text.
        fail("", D1)
        type(K, "hello")
        composer.putBack(K)
        assertEquals("hello", composer.current(K).draft)
        wait { composer.sendWithImages(K) { _, _ -> }!!.join() }
        fail("caption", D1)
        composer.putBack(K)
        assertEquals("caption", composer.current(K).draft)
        wait { composer.sendWithImages(K) { _, _ -> }!!.join() }

        // Whitespace only counts as blank too, on either side.
        fail("  ", D1)
        type(K, "hello")
        composer.putBack(K)
        assertEquals("hello", composer.current(K).draft)
        wait { composer.sendWithImages(K) { _, _ -> }!!.join() }
        fail("caption", D1)
        type(K, "  ")
        composer.putBack(K)
        assertEquals("caption", composer.current(K).draft)
        wait { composer.sendWithImages(K) { _, _ -> }!!.join() }

        // The unsent join: blank on either side is left out; two captions join as before.
        fail("", D1)
        fail("second", D2)
        assertEquals("second", composer.current(K).unsent!!.text)
        fail("", D1)
        assertEquals("second", composer.current(K).unsent!!.text)
        fail("third", D2)
        assertEquals("second\n\nthird", composer.current(K).unsent!!.text)
        composer.discard(K, composer.current(K).unsent!!.id)
        fail("  ", D1)
        fail("fourth", D2)
        assertEquals("fourth", composer.current(K).unsent!!.text)
        fail("  ", D1)
        assertEquals("fourth", composer.current(K).unsent!!.text)
    }

    @Test
    fun oldConnectionAddsNothingAfterSwitch() {
        val c = container()
        wait { c.connect(A) }
        c.convertImage = { Encoded.Ok(bytesOf(it)) }
        val beforeUrl = c.connected()!!
        wait { c.connect(Credentials("https://b.example/", "key-b")) }
        assertNull(beforeUrl.attachImages(K, listOf(uri(1))))
        assertEquals(ComposerState(revision = c.composer.current(K).revision), c.composer.current(K))

        val beforeKey = c.connected()!!
        wait { c.connect(Credentials("https://b.example/", "key-2")) }
        assertNull(beforeKey.attachImages(K, listOf(uri(1))))
        assertEquals(ComposerState(revision = c.composer.current(K).revision), c.composer.current(K))
    }

    @Test
    fun closeBetweenAdmissionAndLaunchStillSettles() {
        // A connection whose scope is already cancelled while its open flag still reads true: the instant between the
        // admission and the launch. The job must still run its finally, or the photo stays processing for good.
        val api = HermesApi(server.url("/").toString(), "secret-key")
        val queue = SendQueue(api, db, RunStarter { _, _ -> }, { 1_000L })
        val repo = ChatRepository(api, db, { 1_000L }, queue)
        val closed = CoroutineScope(SupervisorJob() + Dispatchers.Default).apply { cancel() }
        val store = ImageStore(imagesDir(), System::currentTimeMillis)
        val conn = Connected(
            A, api, db, repo, queue, RunFollower(api, db, repo, closed), AtomicBoolean(true), closed, composer, 0, store,
            { Encoded.Ok(bytesOf(it)) },
        )
        val job = conn.attachImages(K, listOf(uri(1)))!!
        wait { job.join() }
        assertTrue(composer.current(K).images.single().failed)
    }

    @Test
    fun pickOfThreeFailuresContinue() {
        val c = container()
        wait { c.connect(A) }
        val calls = Collections.synchronizedList(mutableListOf<Uri>())
        c.convertImage = {
            calls += it
            when (it) {
                uri(1) -> Encoded.Failed(ImageFailure.BUSY)
                uri(2) -> throw IllegalStateException("decoder fault")
                else -> Encoded.Ok(bytesOf(it))
            }
        }
        wait { c.connected()!!.attachImages(K, listOf(uri(1), uri(2), uri(3)))!!.join() }
        val s = c.composer.current(K)
        assertEquals(listOf(true, true, false), s.images.map { it.failed })
        assertEquals(sha(byteArrayOf(3)), s.images[2].digest)
        assertEquals(ComposerNote(NoteKind.IMAGE_FAILED), s.note)
        assertEquals(listOf(uri(1), uri(2), uri(3)), calls)
    }

    @Test
    fun textOnlySendOverRowLimitKeepsDraft() {
        addSession()
        val repo = repo()
        val text = "x".repeat(800_000)
        type(K, text)
        wait { composer.send(K) { repo.send(K, it, onStored = { stored() }) }!!.join() }
        val s = composer.current(K)
        assertEquals(text, s.draft)
        assertNull(s.unsent)
        assertEquals(ComposerNote(NoteKind.IMAGES_TOO_LARGE), s.note)
    }
}
