package xyz.rycerz.apofis

import android.app.Application
import android.content.pm.ProviderInfo
import android.net.Uri
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.io.FileNotFoundException
import java.security.MessageDigest
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import mockwebserver3.Dispatcher
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import mockwebserver3.RecordedRequest
import mockwebserver3.SocketEffect
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.shadows.ShadowLog
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.chat.NoteKind
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.images.Encoded
import xyz.rycerz.apofis.images.ImageInput
import xyz.rycerz.apofis.images.RouteProvider
import xyz.rycerz.apofis.images.toUploadJpeg
import xyz.rycerz.apofis.run.RunFollower
import xyz.rycerz.apofis.settings.Credentials
import xyz.rycerz.apofis.share.ShareIntent
import xyz.rycerz.apofis.share.readShare
import xyz.rycerz.apofis.sync.ChatRepository
import xyz.rycerz.apofis.sync.SendQueue
import xyz.rycerz.apofis.ui.drawer.DrawerViewModel

// Every canary holds "cnry", so one search also catches a canary cut short or joined with other text.
private const val TEXT = "cnry-text-5d1"
private const val CAPTION = "cnry-caption-8a2"
private const val MODEL = "cnry-model-3c7"
private const val TITLE = "cnry-title-2f4"
private const val SID = "apofis-cnry-sid-4e9"
private const val TEMP = "apofis-tmp-cnry-tmp-6b0"
private const val AUTHORITY = "cnry.authority-7e3"
private const val PATH = "cnry-path-1a8"
private const val URI = "content://$AUTHORITY/$PATH"
private const val FILE = "cnry-file-9d5.jpg"
private const val SERVER = "cnry-server-0c3" // text in the server's error answers

/**
 * What a user typed or a server sent never reaches a log. (a) A census pins the exact text of every log line in the
 * app, so a new or changed line fails until it is reviewed. (b) Canaries driven through send, image decode, share and
 * delete under Robolectric's log capture appear in no captured line. The send, delete and leave paths can log only
 * through the container's handler for uncaught failures, which this hand-built graph does not install: for them (b)
 * shows only that nothing is logged, and (a) is the guarantee. The push endpoint is covered by
 * PushRegistrarTest.endpointNeverLogged.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class LogPrivacyTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var connected: Connected
    private lateinit var follower: RunFollower
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val store = ViewModelStore()
    private val created = CopyOnWriteArrayList<DrawerViewModel>()
    private val jpeg = "cnry-image-bytes".encodeToByteArray()
    private val digest = MessageDigest.getInstance("SHA-256").digest(jpeg).joinToString("") { "%02x".format(it) }

    @Test
    fun everyLogLineIsKnown() {
        val call = Regex("""\bLog\.(v|d|i|w|e|wtf|println)\(""")
        val root = File("src/main/kotlin/xyz/rycerz/apofis")
        val lines = root.walk().filter { it.isFile && it.extension == "kt" }.flatMap { f ->
            f.readLines().filter { call.containsMatchIn(it) }.map { "${f.relativeTo(root).invariantSeparatorsPath}: ${it.trim()}" }
        }.sorted().toList()
        // Only class names, enum names, counts and literals are interpolated; reason, what and cause are literals of
        // their callers (read when this list was written; the census pins the lines, not those callers).
        val known = listOf(
            "ApofisApp.kt: Thread { runCatching { recoverPush(this, pushSink) }.onFailure { Log.w(\"Apofis\", \"Push recovery failed: \${it.javaClass.name}\") } }.start()",
            "AppContainer.kt: if (erased && !runCatching { cachePrefs.edit().remove(SWITCH_MARKER).commit() }.getOrDefault(false)) Log.w(TAG, \"Switch mark not removed\")",
            "AppContainer.kt: private fun logFailure(e: Throwable) = Log.w(TAG, \"Background task failed: \${e.javaClass.name}\")",
            "push/PushReceiver.kt: Log.w(TAG, \"PushReceiver: event lost, \$what\")",
            "push/PushReceiver.kt: Log.w(TAG, \"PushReceiver: event not handled: \${e.javaClass.name}\")",
            "push/PushReceiver.kt: if (!claimed && !finished) Log.w(TAG, \"PushReceiver: event lost, late\")",
            "push/PushRegistrar.kt: Log.w(TAG, \"Push not published: \$reason\")",
            "push/PushRegistrar.kt: private fun logFailure(e: Throwable) = Log.w(TAG, \"Push step failed: \${e.javaClass.name}\")",
            "push/WakeWorker.kt: Log.i(\"ApofisWake\", \"cause=\$cause runs=\$runs settled=\$settled failed=\$failed outcome=\${result?.name ?: \"none\"}\")",
            "share/ShareIntake.kt: if (BuildConfig.DEBUG) Log.i(\"ApofisShare\", \"image \${reason.name}\") // the reason only: no URI, size or digest",
        )
        assertEquals(known.joinToString("\n"), lines.joinToString("\n"))
        val otherSinks = Regex("""printStackTrace|println\(|System\.(out|err)""")
        assertEquals(emptyList<File>(), root.walk().filter { it.isFile && it.extension == "kt" && otherSinks.containsMatchIn(it.readText()) }.toList())
    }

    @Before
    fun setUp() {
        ShadowLog.clear()
        // A content provider whose stream fails to open, with a canary in its exception.
        val info = ProviderInfo().apply {
            authority = AUTHORITY
            packageName = "com.other"
            name = RouteProvider::class.java.name
            exported = true
        }
        Robolectric.buildContentProvider(RouteProvider::class.java).create(info)
        shadowOf(app.packageManager).addOrUpdateProvider(info)
        RouteProvider.routes[PATH] = { throw FileNotFoundException("$PATH $TEXT") }
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return routes["${request.method} ${request.target.substringBefore('?')}"]?.invoke(request) ?: serverError()
            }
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        val api = HermesApi(server.url("/").toString(), "secret-key")
        lateinit var f: RunFollower
        val image = { d: String -> jpeg.takeIf { d == digest } }
        val queue = SendQueue(api, db, { s, r -> f.onRunStarted(s, r) }, System::currentTimeMillis, readImage = image, imageSize = { d -> image(d)?.size?.toLong() })
        val repo = ChatRepository(api, db, System::currentTimeMillis, queue)
        f = RunFollower(api, db, repo, scope, delayMs = { delay(minOf(it, 20L)) })
        follower = f
        connected = Connected(Credentials(server.url("/").toString(), "secret-key"), api, db, repo, queue, f, AtomicBoolean(true), scope)
    }

    @After
    fun tearDown() {
        val jobs = created.map { it.viewModelScope.coroutineContext.job }
        store.clear()
        runBlocking { withTimeout(60_000) { jobs.joinAll() } }
        follower.pauseAll()
        runBlocking { withTimeout(60_000) { scope.coroutineContext.job.cancelAndJoin() } }
        db.close()
        server.close()
        Dispatchers.resetMain()
        RouteProvider.routes.remove(PATH)
    }

    private fun serverError() = MockResponse.Builder().code(500)
        .body("""{"error":{"code":"$SERVER","message":"$SERVER $TEXT"}}""").addHeader("Content-Type", "application/json").build()

    private suspend fun session(key: String) = db.sessionDao().upsert(
        SessionEntity(
            key = key, currentId = key, lineageIds = "", title = TITLE, pinned = false, archived = false, startedAt = 1.0,
            lastActiveAt = 1.0, messageCount = 2, preview = TEXT, stale = false, existsOnServer = true, model = MODEL,
        ),
    )

    private fun drawer(lastChat: String) = ViewModelProvider.create(
        store,
        viewModelFactory { initializer { DrawerViewModel(connected, lastChat = lastChat, zone = { ZoneOffset.UTC }) } },
    ).get(DrawerViewModel::class).also { created += it }

    private suspend fun until(what: String, condition: () -> Boolean) = withContext(Dispatchers.IO) {
        repeat(1000) {
            if (condition()) return@withContext
            Thread.sleep(10)
        }
        throw AssertionError("timed out waiting for $what")
    }

    private fun sent(method: String, path: String) = requests.count { it.method == method && it.target.substringBefore('?') == path }

    @Test
    fun canariesNeverLogged() = runBlocking<Unit> {
        session(SID)
        session(TEMP)

        // A send answered 500, then one whose connection drops; both carry the text, the photo and the stored model.
        val runs = AtomicInteger()
        routes["POST /v1/runs"] = { if (runs.incrementAndGet() == 1) serverError() else MockResponse.Builder().onResponseStart(SocketEffect.CloseSocket()).build() }
        connected.repo.send(SID, TEXT, listOf(digest))
        connected.repo.send(SID, TEXT, listOf(digest))
        connected.sendQueue.drain()
        assertTrue(runs.get() >= 2)
        val body = requests.first { it.target == "/v1/runs" }.body!!.utf8()
        assertTrue(TEXT in body && MODEL in body && SID in body) // the canaries went out

        // Decoding a corrupt file and a URI whose stream fails.
        val corrupt = File(app.cacheDir, FILE).apply { writeText("not an image $TEXT") }
        assertTrue(toUploadJpeg(app, ImageInput.Stored(corrupt)) is Encoded.Failed)
        assertTrue(toUploadJpeg(app, ImageInput.Content(Uri.parse(URI))) is Encoded.Failed)

        // A share whose stream fails to open: its ApofisShare line is written, with the reason only.
        val share = readShare(app, ShareIntent.Images(listOf(Uri.parse(URI)), 0, CAPTION))
        assertEquals(CAPTION, share.text)
        assertTrue(NoteKind.SHARE_IMAGES_FAILED in share.notes)

        // Leaving a temporary chat whose DELETE fails, then the drawer's Delete answered 500.
        val vm = drawer(lastChat = TEMP)
        withTimeout(60_000) { vm.selected.first { it == TEMP } }
        vm.onSelect(SID)
        until("the leave's DELETE") { sent("DELETE", "/api/sessions/$TEMP") > 0 }
        withTimeout(60_000) { vm.leavesEnded() }
        vm.onDeleteRequest(SID)
        vm.confirmDelete(vm.prompt.value!!.token)
        until("the Delete's answer") { sent("DELETE", "/api/sessions/$SID") > 0 && vm.busyActions() == 0 }
        assertTrue(db.sessionDao().get(SID) != null && db.sessionDao().get(TEMP) != null) // both failed and stayed

        val logs = ShadowLog.getLogs()
        val logged = logs.joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable?.stackTraceToString()}" }
        assertTrue("the capture works: $logged", logs.any { it.tag == "ApofisShare" && it.msg == "image UNREADABLE" })
        for (canary in listOf("cnry", TEXT, CAPTION, MODEL, TITLE, SID, TEMP, URI, FILE, SERVER, digest.take(16))) {
            assertFalse("$canary logged", canary in logged)
        }
    }
}
