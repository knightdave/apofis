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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
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
import xyz.rycerz.apofis.ui.drawer.DrawerRow
import xyz.rycerz.apofis.ui.drawer.DrawerViewModel

// A string literal (raw, plain or a character: group 1) or a comment. A template holding quotes splits into pieces,
// which is enough to keep a comment marker inside a string from being taken for a comment.
private val trivia = Regex("(\"{3}[\\s\\S]*?\"{3}|\"(?:\\\\.|[^\"\\\\\\n])*\"|'(?:\\\\.|[^'\\\\\\n])*')|//[^\\n]*|/\\*[\\s\\S]*?\\*/")

// What follows a plain import once whitespace is removed: a modifier or a declaration keyword (never "as" or ".").
private const val DECLARATION = "public|private|internal|protected|open|abstract|final|sealed|data|enum|inline|value|" +
    "annotation|expect|actual|const|lateinit|suspend|tailrec|operator|infix|external|override|fun|class|interface|object|val|var|typealias"

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
private const val SERVER_CODE = "cnry_server_0c3" // a code the client keeps ([a-z0-9_]{1,64})
private const val SERVER_TEXT = "cnry-server-msg-0c3" // free text in the server's error answers
private const val KEY = "cnry-key-7b1"

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
    private val uncaught = CopyOnWriteArrayList<Throwable>()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO + CoroutineExceptionHandler { _, e -> uncaught += e })
    private val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
    private val requests = CopyOnWriteArrayList<RecordedRequest>()
    private val store = ViewModelStore()
    private val created = CopyOnWriteArrayList<DrawerViewModel>()
    private val jpeg = "cnry-image-bytes".encodeToByteArray()
    private val digest = MessageDigest.getInstance("SHA-256").digest(jpeg).joinToString("") { "%02x".format(it) }

    /**
     * Every reference to the logger in every shipping source set, as text: a call from the start of its line to its
     * closing parenthesis (whitespace collapsed, so a call split over lines is pinned whole), and any other reference
     * (an aliased or member import, a callable reference, a qualified name) as the rest of its line. Comments are
     * removed first. The only reference left out is a plain import of android.util.Log, judged on the whole directive
     * (whitespace, comments and string contents removed): it must be followed by the next directive or a declaration,
     * so a member or alias continued on the next line is kept. Kotlin's print functions (also imported, aliased or
     * not), stack traces, the standard streams and a wildcard import of android.util are never allowed. This pins the
     * sinks, not what callers pass into them.
     */
    @Test
    fun everyLogLineIsKnown() {
        val src = File("src")
        val found = src.listFiles()!!.filter { it.isDirectory && !it.name.startsWith("test") && !it.name.startsWith("androidTest") }
            .flatMap { set -> set.walk().filter { it.isFile && it.extension in setOf("kt", "java") }.toList() }
            .flatMap { censusEntries(it.relativeTo(src).invariantSeparatorsPath, it.readText()) }
            .sorted()
        // Only class names, enum names, counts and literals are interpolated; reason, what and cause are literals of
        // their callers (read when this list was written).
        val known = listOf(
            "main/kotlin/xyz/rycerz/apofis/ApofisApp.kt: Thread { runCatching { recoverPush(this, pushSink) }.onFailure { Log.w(\"Apofis\", \"Push recovery failed: \${it.javaClass.name}\")",
            "main/kotlin/xyz/rycerz/apofis/AppContainer.kt: if (erased && !runCatching { cachePrefs.edit().remove(SWITCH_MARKER).commit() }.getOrDefault(false)) Log.w(TAG, \"Switch mark not removed\")",
            "main/kotlin/xyz/rycerz/apofis/AppContainer.kt: private fun logFailure(e: Throwable) = Log.w(TAG, \"Background task failed: \${e.javaClass.name}\")",
            "main/kotlin/xyz/rycerz/apofis/push/PushReceiver.kt: Log.w(TAG, \"PushReceiver: event lost, \$what\")",
            "main/kotlin/xyz/rycerz/apofis/push/PushReceiver.kt: Log.w(TAG, \"PushReceiver: event not handled: \${e.javaClass.name}\")",
            "main/kotlin/xyz/rycerz/apofis/push/PushReceiver.kt: if (!claimed && !finished) Log.w(TAG, \"PushReceiver: event lost, late\")",
            "main/kotlin/xyz/rycerz/apofis/push/PushRegistrar.kt: Log.w(TAG, \"Push not published: \$reason\")",
            "main/kotlin/xyz/rycerz/apofis/push/PushRegistrar.kt: private fun logFailure(e: Throwable) = Log.w(TAG, \"Push step failed: \${e.javaClass.name}\")",
            "main/kotlin/xyz/rycerz/apofis/push/WakeWorker.kt: Log.i(\"ApofisWake\", \"cause=\$cause runs=\$runs settled=\$settled failed=\$failed outcome=\${result?.name ?: \"none\"}\")",
            "main/kotlin/xyz/rycerz/apofis/share/ShareIntake.kt: if (BuildConfig.DEBUG) Log.i(\"ApofisShare\", \"image \${reason.name}\")",
        )
        assertEquals(known.joinToString("\n"), found.joinToString("\n"))
    }

    /** Scanner controls: each of these is flagged; a plain import, with or without a comment, is not. */
    @Test
    fun censusFlagsEveryOtherReference() {
        val flagged = listOf(
            "import android.util.Log\n    .w as warn\n\nfun recordTitle(title: String) { warn(\"Drawer\", title) }",
            "import android.util.Log\n    as L\n\nfun f(title: String) { L.w(\"Drawer\", title) }",
            "import android.util.Log /* plain */\n    .w as warn\n\nfun f(title: String) = warn(\"Drawer\", title)",
            "import android.util.Log as L\nfun f(title: String) = L.w(\"Drawer\", title)",
            "import android.util.Log\nfun f(title: String) = Log.w(\n    \"Drawer\",\n    \"title \$title\",\n)",
            "import android.util.Log\nval warn = Log::w",
            "fun f(title: String) = android.util.Log.w(\"Drawer\", title)",
            "import android.util.*",
            "import kotlin.io.println as emitLine\n\nfun f(title: String) { emitLine(title) }",
            "import kotlin.io\n    .print as emit\n\nfun f(title: String) { emit(title) }",
            "fun f(title: String) = print(title)",
            "fun f(e: Throwable) = e.printStackTrace()",
            "fun f(title: String) = System.err.write(title.toByteArray())",
        )
        flagged.forEach { assertTrue(it, censusEntries("control.kt", it).isNotEmpty()) }
        val plain = listOf(
            "import android.util.Log\nimport java.io.File\n\nclass A",
            "import android.util.Log // the logger\n\nprivate val x = 1",
            "import android.util.Log\n/* a comment */\nfun f() = \"Log in\".length", // the string still names Log: flagged below
        )
        assertEquals(emptyList<String>(), plain.take(2).flatMap { censusEntries("control.kt", it) })
        assertEquals(1, censusEntries("control.kt", plain[2]).size) // only the string, not the import
    }

    /** The census entries of one source file, each prefixed with [path]: see [everyLogLineIsKnown]. */
    private fun censusEntries(path: String, source: String): List<String> {
        val text = trivia.replace(source) { if (it.groups[1] != null) it.value else " " } // comments out, strings kept
        val flat = trivia.replace(source) { if (it.groups[1] != null) "\"\"" else " " }.replace(Regex("""\s+"""), "") // strings emptied too
        val plain = Regex("""importandroid\.util\.Log(?=;|import|@|$|$DECLARATION)""").findAll(flat).count()
        val refs = Regex("""\bLog\b""").findAll(text).map { reference(text, it.range.first).trim() }.toMutableList()
        repeat(plain) { refs.remove("import android.util.Log") }
        val sinks = Regex("""\bprint(ln)?\s*\(|::\s*print(ln)?\b|printStackTrace|System\s*\.\s*(out|err)\b""").findAll(text).map { it.value } +
            Regex("""importandroid\.util\.\*|importkotlin\.io\.(print|println|\*)""").findAll(flat).map { it.value }
        return (refs + sinks).map { "$path: $it" }
    }

    /** The reference to the logger at [at] in [text]: see [everyLogLineIsKnown]. */
    private fun reference(text: String, at: Int): String {
        val prefix = text.substring(text.lastIndexOf('\n', at - 1) + 1, at).trimStart()
        val call = Regex("""Log\s*\.\s*\w+\s*\(""").find(text, at)?.takeIf { it.range.first == at }
        val end = if (call != null) {
            var depth = 1
            var i = call.range.last + 1
            while (i < text.length && depth > 0) {
                if (text[i] == '(') depth++ else if (text[i] == ')') depth--
                i++
            }
            i
        } else {
            text.indexOf('\n', at).let { if (it < 0) text.length else it }
        }
        return (prefix + text.substring(at, end)).replace(Regex("""\s+"""), " ")
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
        val api = HermesApi(server.url("/").toString(), KEY)
        lateinit var f: RunFollower
        val image = { d: String -> jpeg.takeIf { d == digest } }
        val queue = SendQueue(api, db, { s, r -> f.onRunStarted(s, r) }, System::currentTimeMillis, readImage = image, imageSize = { d -> image(d)?.size?.toLong() })
        val repo = ChatRepository(api, db, System::currentTimeMillis, queue)
        f = RunFollower(api, db, repo, scope, delayMs = { delay(minOf(it, 20L)) })
        follower = f
        connected = Connected(Credentials(server.url("/").toString(), KEY), api, db, repo, queue, f, AtomicBoolean(true), scope)
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
        .body("""{"error":{"code":"$SERVER_CODE","message":"$SERVER_TEXT $TEXT"}}""").addHeader("Content-Type", "application/json").build()

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

    private suspend fun <T> await(what: suspend () -> T): T = withContext(Dispatchers.Default) { withTimeout(10_000) { what() } }

    /**
     * Returns once the connection's scope has no child left, cancelling nothing: a child's failure reaches the scope's
     * handler before the child counts as complete, so it is collected by then.
     */
    private suspend fun connectionIdle() = await {
        while (true) {
            val children = scope.coroutineContext.job.children.toList()
            if (children.isEmpty()) break
            children.joinAll()
        }
    }

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
        val deletes = mapOf(TEMP to CompletableDeferred<Unit>(), SID to CompletableDeferred<Unit>())
        deletes.forEach { (key, asked) -> routes["DELETE /api/sessions/$key"] = { asked.complete(Unit); serverError() } }
        val vm = drawer(lastChat = TEMP)
        await { vm.selected.first { it == TEMP } }
        vm.onSelect(SID)
        await { deletes.getValue(TEMP).await() } // the leave is in flight: it is listed as being left until it ends
        await { vm.leavesEnded() }
        await { vm.rows.first { rows -> rows.any { it is DrawerRow.Chat && it.key == SID } } }
        vm.onDeleteRequest(SID)
        val token = await { vm.prompt.first { it?.key == SID } }!!.token
        vm.confirmDelete(token)
        await { deletes.getValue(SID).await() }
        await { vm.prompt.first { it?.token == token && !it.busy && it.error != null } } // the Delete's failure is shown
        connectionIdle() // a failed delete or leave starts work in the connection's scope: read the sinks after it
        assertTrue(db.sessionDao().get(SID) != null && db.sessionDao().get(TEMP) != null) // both failed and stayed

        val logs = ShadowLog.getLogs()
        val logged = logs.joinToString("\n") { "${it.tag} ${it.msg} ${it.throwable?.stackTraceToString()}" } +
            uncaught.joinToString("\n") { it.stackTraceToString() } // the fixture's scope collects what nothing caught
        assertTrue("the capture works: $logged", logs.any { it.tag == "ApofisShare" && it.msg == "image UNREADABLE" })
        for (canary in listOf(TEXT, CAPTION, MODEL, TITLE, SID, TEMP, URI, FILE, SERVER_CODE, SERVER_TEXT, KEY, digest.take(16), "cnry")) {
            assertFalse("$canary logged", canary in logged)
        }
    }
}
