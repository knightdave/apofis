package xyz.rycerz.apofis.share

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import java.time.ZoneOffset
import java.util.Base64
import xyz.rycerz.apofis.api.fixture
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.ui.DrawerButton
import xyz.rycerz.apofis.ui.FIXED_NOW
import xyz.rycerz.apofis.ui.FIXED_TODAY
import xyz.rycerz.apofis.ui.NewChatButton
import xyz.rycerz.apofis.ui.chat.ChatActions
import xyz.rycerz.apofis.ui.chat.ChatScreen
import xyz.rycerz.apofis.ui.chat.ChatUiState
import xyz.rycerz.apofis.ui.chat.ComposerMode
import xyz.rycerz.apofis.ui.chat.SEND_TAG
import xyz.rycerz.apofis.ui.chat.StatusLine
import xyz.rycerz.apofis.ui.drawer.NewChatState
import xyz.rycerz.apofis.ui.image.LocalImageStore
import xyz.rycerz.apofis.ui.image.LocalThumbCache
import xyz.rycerz.apofis.ui.windowShot
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.images.ImageLimits
import androidx.compose.ui.test.performTouchInput
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.espresso.Espresso
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import androidx.lifecycle.viewModelScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.AppContainer
import xyz.rycerz.apofis.Connected
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.ShareToken
import xyz.rycerz.apofis.chat.ComposerNote
import xyz.rycerz.apofis.chat.ComposerState
import xyz.rycerz.apofis.chat.NoteKind
import xyz.rycerz.apofis.settings.CredentialStore
import xyz.rycerz.apofis.settings.Credentials
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.sync.isTemporaryKey
import xyz.rycerz.apofis.sync.withRepositoryLock
import xyz.rycerz.apofis.ui.AppNav
import xyz.rycerz.apofis.ui.ChatRoute
import xyz.rycerz.apofis.ui.drawer.DrawerViewModel
import xyz.rycerz.apofis.ui.drawer.TemporaryFixture
import xyz.rycerz.apofis.ui.theme.ApofisTheme

internal const val SHARED_T = "apofis-tmp-shared"
internal const val SHARED_A = "apofis-a"
internal const val SHARED_B = "apofis-b"

/**
 * The app's chat route over [TemporaryFixture]'s database and server, through a real container connected to it: as
 * AppNav does, one route and one view model owner per connection, so a switch shows the new connection's chat.
 */
abstract class ShareFixture : TemporaryFixture() {
    @get:Rule
    val rule = createComposeRule()

    protected val app: Application = ApplicationProvider.getApplicationContext()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    protected lateinit var container: AppContainer
    private val owners = HashMap<Long, ViewModelStoreOwner>()
    private var shown by mutableStateOf(true)
    protected val url: String get() = server.url("/").toString()

    protected fun str(id: Int, vararg args: Any): String = app.getString(id, *args)

    protected fun newContainer(): AppContainer {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        return AppContainer(app, CredentialStore(app) { key }) { db }.also { container = it }
    }

    /** Connects to the fixture's server, then [seed]s (a new server's cache is cleared by the switch), then stores [lastChat]. */
    /** [block] on this thread, failing the test after 60 s instead of hanging it. */
    protected fun <T> bounded(block: suspend CoroutineScope.() -> T): T = runBlocking { withTimeout(60_000, block) }

    protected fun connect(lastChat: String?, seed: suspend () -> Unit = {}) = bounded {
        container.connect(Credentials(url, "key-1"))
        seed()
        lastChat?.let { container.preferences.lastChat = it }
    }

    private fun ownerOf(conn: Connected) = owners.getOrPut(conn.generation) {
        object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
    }

    protected fun show(content: @Composable () -> Unit) = rule.setContent {
        ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) { if (shown) content() }
    }

    /** The chat route of the container's current connection, with the image store and thumbnails MainActivity provides. */
    protected fun compose() = show {
        val conn = container.connection.collectAsState().value
        if (conn != null) {
            key(conn) {
                CompositionLocalProvider(LocalImageStore provides container.imageStore, LocalThumbCache provides conn.thumbs) {
                    ChatRoute(container, conn, ownerOf(conn), 0, onSettings = {}, onConnection = {})
                }
            }
        }
    }

    /**
     * Takes everything down before the fixture closes the database (also when an assertion failed): the route leaves the
     * composition, then every drawer this fixture's owners hold and the container's own work (its connections and
     * composer included) are cancelled and have ended, or the test fails after 60 s.
     */
    protected fun finish() {
        shown = false
        rule.waitForIdle()
        val drawers = owners.values.map { ViewModelProvider.create(it.viewModelStore)[DrawerViewModel::class].viewModelScope.coroutineContext.job }
        owners.values.forEach { it.viewModelStore.clear() }
        bounded {
            (drawers + container.scope.coroutineContext.job).forEach { it.cancelAndJoin() }
            settled()
        }
    }

    protected fun inRoute(lastChat: String?, seed: suspend () -> Unit = {}, body: () -> Unit) {
        newContainer()
        try {
            connect(lastChat, seed)
            compose()
            body()
        } finally {
            finish()
        }
    }

    /** The drawer of the route composed for the current connection. */
    protected fun drawer(): DrawerViewModel =
        ViewModelProvider.create(owners.getValue(container.connection.value!!.generation).viewModelStore)[DrawerViewModel::class]

    protected fun waitSelected(condition: (String?) -> Boolean): String {
        rule.waitUntil(10_000) { runCatching { condition(drawer().selected.value) }.getOrDefault(false) }
        return drawer().selected.value!!
    }

    /**
     * The fresh chat an Accept from [old] opened, once its draft is [done]. The test rule runs the route's scope
     * unconfined, so the drawer's step may go on off the main thread: neither the selection nor a first write shows that
     * the whole fill has run, so [done] names the complete expected state (see [filled]).
     */
    protected fun acceptedFrom(old: String, done: (ComposerState) -> Boolean): String {
        val k = waitSelected { it != null && it != old }
        try {
            rule.waitUntil(10_000) { done(composerOf(k)) }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            throw AssertionError("the fill did not end as expected: ${composerOf(k)}", e)
        }
        return k
    }

    /** The whole fill: the text, every image ready in order, and the one note (written last). */
    protected fun filled(text: String, digests: List<String> = emptyList(), note: ComposerNote? = null): (ComposerState) -> Boolean = { s ->
        s.draft == text && s.images.map { it.digest } == digests && s.images.none { it.processing || it.failed } && s.note == note
    }

    /**
     * The draft photo [index] of [count] is drawn from its stored file: only a decoded thumbnail opens the viewer, the
     * placeholder (loading or unavailable) is never clickable.
     */
    protected fun thumbDrawn(index: Int, count: Int) = rule.onAllNodes(
        hasContentDescription(str(R.string.chat_image, index, count)) and hasClickAction(),
        useUnmergedTree = true,
    ).fetchSemanticsNodes().isNotEmpty()

    /** Waits until [thumbDrawn] holds for each of [indices] of [count]; on a timeout, says what the image nodes carry. */
    protected fun waitThumbs(count: Int, vararg indices: Int) {
        try {
            rule.waitUntil(10_000) { indices.all { thumbDrawn(it, count) } }
        } catch (e: androidx.compose.ui.test.ComposeTimeoutException) {
            val nodes = rule.onAllNodes(hasContentDescription(str(R.string.chat_image, 1, count).substringBefore(" 1"), substring = true), useUnmergedTree = true)
                .fetchSemanticsNodes().map { it.config.toString() }
            throw AssertionError("thumbnails not drawn: $nodes", e)
        }
    }

    protected fun visible(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    protected fun waitVisible(text: String) = rule.waitUntil(10_000) { visible(text) }
    protected fun waitGone(text: String) = rule.waitUntil(10_000) { !visible(text) }

    /** Offers a share whose reading gives [text], [images] and [notes]; returns it once it is in the slot. */
    protected fun offer(text: String = "Shared words", images: List<ByteArray> = emptyList(), notes: Set<NoteKind> = emptySet()): Share = bounded {
        container.readSharedItems = { Share(0, text, images, notes) }
        container.offerShare(ShareIntent.Text("read by the hook"))
        container.pendingShare.await { it != null }!!
    }

    protected fun photo(color: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 85, it) }.toByteArray()
    }

    protected fun sha(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Every row of every table of the cache. */
    protected fun tables(): List<String> = db.query("SELECT name FROM sqlite_master WHERE type = 'table' ORDER BY name", null).use { names ->
        generateSequence { if (names.moveToNext()) names.getString(0) else null }.toList()
    }.filter { it != "room_master_table" && it != "android_metadata" }.flatMap { t ->
        db.query("SELECT * FROM `$t`", null).use { c ->
            generateSequence { if (c.moveToNext()) t + (0 until c.columnCount).map { c.getString(it) } else null }.toList()
        }
    }

    protected fun sessionKeys(): Set<String> = db.query("SELECT `key` FROM SessionEntity", null).use { c ->
        generateSequence { if (c.moveToNext()) c.getString(0) else null }.toSet()
    }

    /** Requests that change something on the server (a plain start only reads). */
    protected fun writes() = lines().filterNot { it.startsWith("GET ") }

    protected fun imageFiles(): List<String> = File(app.filesDir, "images").list().orEmpty().sorted()

    protected fun composerOf(key: String): ComposerState = container.composer.current(key)

    /** Holds the repository lock (a fresh row waits for it) until [release]. */
    protected inner class HeldLock {
        private val locked = CompletableDeferred<Unit>()
        private val released = CompletableDeferred<Unit>()
        private val job = side.launch(Dispatchers.IO) { withRepositoryLock { locked.complete(Unit); released.await() } }
        init { bounded { locked.await() } }
        fun release() = bounded {
            released.complete(Unit)
            job.join()
        }
    }
}

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class ShareSheetTest : ShareFixture() {
    private val accept get() = str(R.string.share_accept)
    private val cancel get() = str(R.string.share_cancel)
    private val title get() = str(R.string.share_title)

    private fun seedA() = suspend {
        session(SHARED_A, onServer = true)
        message(SHARED_A)
    }

    /** What Cancel and a lost Accept must leave as it was. */
    private fun everything(vararg keys: String) =
        listOf(drawer().selected.value, tables(), writes(), container.preferences.lastChat, imageFiles()) + keys.map { composerOf(it) }

    @Test
    fun cancelLeavesEverythingAsItWas() = inRoute(SHARED_T, {
        session(SHARED_T, onServer = true)
        message(SHARED_T)
        session(SHARED_A)
        pending(SHARED_A)
    }) {
        waitSelected { it == SHARED_T }
        container.composer.edit(SHARED_T, "my draft", composerOf(SHARED_T).revision)
        container.composer.edit(SHARED_A, "other draft", composerOf(SHARED_A).revision)
        rule.waitForIdle()
        val before = everything(SHARED_T, SHARED_A)

        offer(images = listOf(photo(Color.RED)), notes = setOf(NoteKind.SHARE_TEXT_CUT))
        waitVisible(title)
        assertTrue(visible(str(R.string.share_deletes_temporary)))
        assertEquals(before, everything(SHARED_T, SHARED_A)) // shown: nothing changed yet

        rule.onNodeWithText(cancel).performClick()
        waitGone(title)
        assertNull(container.pendingShare.value)
        assertEquals(before, everything(SHARED_T, SHARED_A))
        assertEquals(emptyList<String>(), writes())
    }

    @Test
    fun backCancels() = inRoute(SHARED_A, seedA()) {
        waitSelected { it == SHARED_A }
        rule.waitForIdle()
        val before = everything(SHARED_A)
        offer()
        waitVisible(title)
        Espresso.pressBack()
        waitGone(title)
        assertNull(container.pendingShare.value)
        assertEquals(before, everything(SHARED_A))
    }


    @Test
    fun acceptOpensNewChatWithDraft() = inRoute(SHARED_A, seedA()) {
        waitSelected { it == SHARED_A }
        val photos = listOf(photo(Color.RED), photo(Color.BLUE))
        offer("Look at these", photos, setOf(NoteKind.SHARE_TEXT_CUT, NoteKind.SHARE_IMAGES_FAILED))
        waitVisible(str(R.string.share_images_failed))
        rule.onNodeWithText(accept).performClick()
        val k = acceptedFrom(SHARED_A, filled("Look at these", photos.map(::sha), ComposerNote(NoteKind.SHARE_IMAGES_FAILED)))
        waitGone(title)

        assertFalse(isTemporaryKey(k))
        assertNotNull(bounded { db.sessionDao().get(k) })
        assertNotNull(bounded { db.sessionDao().get(SHARED_A) })
        val draft = composerOf(k)
        assertEquals("Look at these", draft.draft)
        assertEquals(photos.map(::sha), draft.images.map { it.digest })
        assertTrue(draft.images.none { it.processing || it.failed })
        draft.images.forEach { assertNotNull(container.imageStore.read(it.digest!!)) }
        assertEquals(ComposerNote(NoteKind.SHARE_IMAGES_FAILED), draft.note) // one note, in the sheet's order
        assertNull(container.pendingShare.value)
        assertEquals(emptyList<String>(), writes())
        waitVisible("Look at these") // the composer shows the draft
        waitThumbs(2, 1, 2) // both photos drawn from their files
        assertTrue(visible(str(R.string.share_images_failed))) // the note line (the sheet that also said it is gone)
    }

    @Test
    fun acceptFromTemporaryLeavesIt() = inRoute(SHARED_T, {
        session(SHARED_T, onServer = true)
        message(SHARED_T)
    }) {
        routes["DELETE /api/sessions/$SHARED_T"] = { json(500, "{}") }
        waitSelected { it == SHARED_T }
        offer(notes = setOf(NoteKind.SHARE_TEXT_CUT))
        waitVisible(str(R.string.share_deletes_temporary))
        rule.onNodeWithText(accept).performClick()
        // Only the set-aside matters before leavesEnded(): the drawer sets the chat aside before the fill's first write,
        // and the leave's note may replace the share's at any moment after the fill.
        val k = acceptedFrom(SHARED_T) { it.draft == "Shared words" }
        bounded { drawer().leavesEnded() }

        assertEquals(listOf("DELETE /api/sessions/$SHARED_T"), deletes())
        assertNotNull(bounded { db.sessionDao().get(SHARED_T) })
        rule.waitUntil(10_000) { SHARED_T in drawer().rows.value.chats() } // listed again: it is no longer open
        assertEquals(ComposerNote(NoteKind.TEMPORARY_NOT_DELETED), composerOf(k).note) // replaces the share's note
        assertEquals("Shared words", composerOf(k).draft)
        waitVisible(str(R.string.chat_temporary_not_deleted)) // drawn in the new chat
        assertFalse(visible(str(R.string.share_text_cut)))
    }

    @Test
    fun acceptFromTemporaryHoldingTheSamePhotoKeepsTheFile() {
        val same = photo(Color.GREEN)
        inRoute(SHARED_T, {
            session(SHARED_T, onServer = true)
            message(SHARED_T)
        }) {
            deleted(SHARED_T)
            waitSelected { it == SHARED_T }
            val digest = bounded { container.imageStore.put(same)!! }
            val id = container.composer.addImages(SHARED_T, 1).single()
            container.composer.setImage(id, digest)

            offer(images = listOf(same))
            waitVisible(title)
            rule.onNodeWithText(accept).performClick()
            val k = acceptedFrom(SHARED_T, filled("Shared words", listOf(digest)))
            bounded { drawer().leavesEnded() }
            assertNull(bounded { db.sessionDao().get(SHARED_T) }) // left and deleted
            assertNull(composerOf(k).note) // the leave succeeded: no note

            // Old enough for any collection, still named by the new draft only.
            val file = checkNotNull(container.imageStore.file(digest))
            assertTrue(file.setLastModified(System.currentTimeMillis() - 11 * 60_000))
            bounded { container.collectImages() }
            assertEquals(listOf(digest), composerOf(k).images.map { it.digest })
            assertNotNull(container.imageStore.read(digest))
            waitThumbs(1, 1) // the new draft's thumbnail resolves

            // A send from the new chat (the composer's own path) creates the chat and starts a run carrying the photo.
            routes["POST /api/sessions"] = { json(409, fixture("session_exists_409.json")) }
            routes["POST /v1/runs"] = { json(202, """{"run_id":"run_1","status":"started","replayed":false}""") }
            rule.onNodeWithTag(SEND_TAG).performClick()
            rule.waitUntil(10_000) { requests.any { it.method == "POST" && it.target == "/v1/runs" } }
            val run = requests.single { it.method == "POST" && it.target == "/v1/runs" }
            val body = run.body!!.utf8()
            assertTrue("the run carries the shared photo", Base64.getEncoder().encodeToString(same) in body)
            assertTrue(body.contains("Shared words"))
        }
    }

    /** Accepts [share] directly, as the sheet would for [selected], with [open] standing in for the drawer's step. */
    private fun acceptAs(share: Share, selected: String, open: suspend (String, () -> Boolean, suspend (String) -> Unit) -> String?): Boolean =
        bounded { container.acceptShare(ShareToken(share.version, container.connection.value!!.generation, selected), open) }

    @Test
    fun shareReplacedAfterPutReshows() = inRoute(SHARED_A, seedA()) {
        waitSelected { it == SHARED_A }
        val a = offer("first share", listOf(photo(Color.RED)))
        waitVisible("first share")
        val keys = sessionKeys()
        val drawer = drawer()
        val ok = acceptAs(a, SHARED_A) { sel, claim, fill ->
            container.readSharedItems = { Share(0, "second share", emptyList(), emptySet()) }
            container.offerShare(ShareIntent.Text("b"))
            container.pendingShare.await { it != null }
            drawer.openFreshChatIf(sel, claim, fill)
        }
        assertFalse(ok)
        assertEquals(keys, sessionKeys())
        assertEquals(SHARED_A, drawer.selected.value)
        assertEquals("second share", container.pendingShare.value?.text)
        waitVisible("second share")
        assertFalse(visible("first share"))
        assertEquals(emptyList<String>(), writes())
    }

    @Test
    fun shareReplacedAfterSelectionCheckReshows() = inRoute(SHARED_A, seedA()) {
        waitSelected { it == SHARED_A }
        val a = offer("first share")
        waitVisible("first share")
        val keys = sessionKeys()
        val composed = container.composer.keys
        val drawer = drawer()
        val ok = acceptAs(a, SHARED_A) { sel, claim, fill ->
            drawer.openFreshChatIf(sel, {
                // B arrives while the fresh row is being created, before the claim.
                container.readSharedItems = { Share(0, "second share", emptyList(), emptySet()) }
                container.offerShare(ShareIntent.Text("b"))
                claim()
            }, fill)
        }
        assertFalse(ok)
        assertEquals(keys, sessionKeys()) // no fresh row remains
        assertEquals(composed, container.composer.keys) // no draft written
        assertEquals(SHARED_A, drawer.selected.value)
        waitVisible("second share")
        assertFalse(visible("first share"))
    }

    /** An Accept of a share no longer in the slot, or for a connection no longer current, stores nothing and opens nothing. */
    @Test
    fun staleAcceptStoresNothing() = inRoute(SHARED_A, seedA()) {
        waitSelected { it == SHARED_A }
        val a = offer("the share", listOf(photo(Color.RED)))
        var opened = 0
        val count: suspend (String, () -> Boolean, suspend (String) -> Unit) -> String? = { _, _, _ -> opened++; null }
        assertFalse(acceptAs(a.copy(version = a.version + 1), SHARED_A, count)) // a version the slot does not hold
        assertEquals(emptyList<String>(), imageFiles())

        val generation = container.connection.value!!.generation
        bounded { container.connect(Credentials(url, "key-2")) } // a key change at the same server keeps the cache
        val stale = ShareToken(a.version, generation, SHARED_A)
        assertFalse(bounded { container.acceptShare(stale, count) })
        assertEquals(emptyList<String>(), imageFiles())
        assertEquals(0, opened)
        assertEquals(a, container.pendingShare.value)
    }

    @Test
    fun switchAfterPutReshows() = inRoute(SHARED_A, seedA()) {
        waitSelected { it == SHARED_A }
        val a = offer("first share", listOf(photo(Color.RED)))
        waitVisible("first share")
        val old = drawer()
        val ok = acceptAs(a, SHARED_A) { sel, claim, fill ->
            container.connect(Credentials(server.url("/other/").toString(), "key-1")) // another server: a new cache
            old.openFreshChatIf(sel, claim, fill)
        }
        assertFalse(ok)
        assertEquals(a, container.pendingShare.value)
        val fresh = waitSelected { it != null } // the new server's own new chat
        waitVisible("first share") // shown again over the new connection
        assertEquals(setOf(fresh), sessionKeys())
        assertEquals("", composerOf(fresh).draft)
        assertEquals(emptySet<String>(), container.composer.keys)
        assertEquals(emptyList<String>(), imageFiles()) // the stored photo went with the old cache
    }

    @Test
    fun switchAfterSelectionCheckReshows() = inRoute(SHARED_A, seedA()) {
        waitSelected { it == SHARED_A }
        val a = offer("first share")
        waitVisible("first share")
        val keys = sessionKeys()
        val composed = container.composer.keys
        val old = drawer()
        val generation = container.connection.value!!.generation
        val ok = acceptAs(a, SHARED_A) { sel, claim, fill ->
            old.openFreshChatIf(sel, {
                // A key change at the same server completes while the fresh row is being created.
                bounded { container.connect(Credentials(url, "key-2")) }
                claim()
            }, fill)
        }
        assertFalse(ok)
        assertNotEquals(generation, container.connection.value!!.generation)
        assertEquals(a, container.pendingShare.value)
        assertEquals(keys, sessionKeys())
        assertEquals(composed, container.composer.keys)
        waitSelected { it == SHARED_A } // the new connection's drawer restores the same chat
        waitVisible("first share")
    }

    @Test
    fun doubleAcceptConsumesOnce() = inRoute(SHARED_A, seedA()) {
        waitSelected { it == SHARED_A }
        val shot = photo(Color.RED)
        offer("only once", listOf(shot))
        waitVisible(title)
        val keys = sessionKeys()
        val held = HeldLock() // the first Accept waits for its fresh row while the second is pressed
        try {
            rule.onNodeWithText(accept).performClick()
            rule.onNodeWithText(accept).performClick()
            rule.waitForIdle()
        } finally {
            held.release()
        }
        val k = acceptedFrom(SHARED_A, filled("only once", listOf(sha(shot))))
        waitGone(title)
        rule.waitForIdle()
        assertEquals(keys + k, sessionKeys())
        assertEquals("only once", composerOf(k).draft)
        assertEquals(1, composerOf(k).images.size)
        assertNull(container.pendingShare.value)
    }

    @Test
    fun sheetWaitsForDrawerPrompt() = inRoute(SHARED_A, seedA()) {
        waitSelected { it == SHARED_A }
        drawer().onLongPress(SHARED_A)
        waitVisible(str(R.string.drawer_rename))
        offer("after the prompt")
        rule.waitForIdle()
        assertFalse(visible(title)) // a drawer prompt is shown first
        Espresso.pressBack()
        waitVisible("after the prompt")
        assertTrue(visible(title))
    }

    @Test
    fun selectionChangedDuringAcceptReshows() = inRoute(SHARED_A, {
        seedA()()
        session(SHARED_B)
    }) {
        waitSelected { it == SHARED_A }
        val a = offer("the share")
        waitVisible("the share")
        val keys = sessionKeys()
        val drawer = drawer()
        val ok = acceptAs(a, SHARED_A) { sel, claim, fill ->
            drawer.onSelect(SHARED_B)
            drawer.selected.await { it == SHARED_B }
            drawer.openFreshChatIf(sel, claim, fill)
        }
        assertFalse(ok)
        assertEquals(keys, sessionKeys())
        assertEquals(a, container.pendingShare.value)
        waitSelected { it == SHARED_B }
        waitVisible("the share") // shown again, now for B
        rule.onNodeWithText(accept).performClick()
        acceptedFrom(SHARED_B, filled("the share"))
    }

    @Test
    fun liveRunKeepsRunningAfterAccept() = inRoute(SHARED_A, {
        seedA()()
        run(SHARED_A, "run-1")
    }) {
        waitSelected { it == SHARED_A }
        offer()
        waitVisible(title)
        rule.onNodeWithText(accept).performClick()
        acceptedFrom(SHARED_A, filled("Shared words"))
        rule.waitForIdle()
        assertEquals(emptyList<String>(), stops())
        assertEquals(emptyList<String>(), writes())
        assertEquals(1, bounded { db.activeRunDao().allForSession(SHARED_A) }.size)
    }

    @Test
    fun oldDraftUntouched() = inRoute(SHARED_A, seedA()) {
        waitSelected { it == SHARED_A }
        container.composer.edit(SHARED_A, "mine", composerOf(SHARED_A).revision)
        val before = composerOf(SHARED_A)
        offer("theirs")
        waitVisible(title)
        rule.onNodeWithText(accept).performClick()
        acceptedFrom(SHARED_A, filled("theirs"))
        assertEquals(before, composerOf(SHARED_A))
    }

    @Test
    fun acceptFromEmptyChatWithDraftKeepsThatDraft() = inRoute(null) {
        val empty = waitSelected { it != null } // a new, never-created empty chat
        container.composer.edit(empty, "typed", composerOf(empty).revision)
        offer("shared")
        waitVisible(title)
        rule.onNodeWithText(accept).performClick()
        val k = acceptedFrom(empty, filled("shared")) // always a fresh chat, also from an empty one
        assertNotNull(bounded { db.sessionDao().get(empty) })
        assertEquals("typed", composerOf(empty).draft)
        assertEquals("shared", composerOf(k).draft)
    }

    @Test
    fun sharedCommandTextRunsNothingOnAccept() = inRoute(SHARED_A, {
        seedA()()
        run(SHARED_A, "run-1")
    }) {
        waitSelected { it == SHARED_A }
        val keys = sessionKeys()
        offer("/stop")
        waitVisible(title)
        rule.onNodeWithText(accept).performClick()
        val k = acceptedFrom(SHARED_A, filled("/stop"))
        rule.waitForIdle()
        bounded { container.composer.settle() }
        assertEquals("/stop", composerOf(k).draft) // nothing parsed it
        assertFalse(composerOf(k).sending)
        assertEquals(keys + k, sessionKeys())
        assertEquals(emptyList<String>(), writes())
        assertEquals(1, bounded { db.activeRunDao().allForSession(SHARED_A) }.size)
    }

    @Test
    fun sheetWaitsForConnection() {
        newContainer()
        try {
            show { AppNav(container) }
            offer("waiting")
            rule.waitUntil(10_000) { visible(str(R.string.connect_title)) }
            assertFalse(visible(title))

            connect(null)
            waitVisible(title)
            assertTrue(visible("waiting"))
        } finally {
            finish()
        }
    }

    private var notes by mutableStateOf<Share?>(null)

    private fun sheetOnly(share: Share, temporary: Boolean = false) {
        notes = share
        show { notes?.let { ShareSheet(it, temporary, onAccept = {}, onCancel = {}) } }
    }

    @Test
    fun notesCollapseToOne() {
        val all = setOf(NoteKind.SHARE_TEXT_CUT, NoteKind.SHARE_IMAGES_FAILED, NoteKind.SHARE_TOO_MANY)
        sheetOnly(Share(1, "text", listOf(photo(Color.RED), photo(Color.BLUE)), all))
        waitVisible(title)
        assertTrue(visible(str(R.string.share_images_count, 2)))
        assertTrue(visible(str(R.string.share_too_many)))
        assertFalse(visible(str(R.string.share_images_failed)))
        assertFalse(visible(str(R.string.share_text_cut)))

        notes = Share(2, "text", listOf(photo(Color.RED)), all - NoteKind.SHARE_TOO_MANY)
        waitVisible(str(R.string.share_images_failed))
        assertFalse(visible(str(R.string.share_too_many)))
        assertFalse(visible(str(R.string.share_text_cut)))

        notes = Share(3, "text", emptyList(), setOf(NoteKind.SHARE_TEXT_CUT))
        waitVisible(str(R.string.share_text_cut))
        assertFalse(visible(str(R.string.share_images_failed)))
        assertFalse(visible(str(R.string.share_images_count, 0)))
    }

    @Test
    fun emptyShareOffersOnlyCancel() {
        sheetOnly(Share(1, "", emptyList(), setOf(NoteKind.SHARE_IMAGES_FAILED)))
        waitVisible(title)
        assertTrue(visible(str(R.string.share_images_failed)))
        assertTrue(visible(cancel))
        assertFalse(visible(accept))

        notes = Share(2, " \n ", emptyList(), emptySet()) // only whitespace: nothing to open either
        rule.waitForIdle()
        assertTrue(visible(cancel))
        assertFalse(visible(accept))
    }

    private val question = UserMessage("u1", "Which plants can share a raised bed with tomatoes?", steered = false, timestamp = FIXED_NOW)
    private val answer = AssistantReply("r1", emptyList(), "Basil, marigolds and lettuce grow well beside tomatoes.", FIXED_NOW)

    /** The sheet over a chat screen with its composer; the chat is drawn from a fixed state, so nothing loads during the capture. */
    private fun sheetShot(name: String, key: String, share: Share, temporary: Boolean) = rule.windowShot(name, hasText(str(R.string.share_title))) {
        ChatScreen(
            ChatUiState(key, "Which plants can share a raised bed", StatusLine.IDLE, listOf(question, answer), ComposerMode.SEND_DISABLED, "", "hermes.example"),
            object : ChatActions {}, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY,
            navigationIcon = { DrawerButton {} }, appBarActions = { NewChatButton(NewChatState.NEW) {} },
        )
        ShareSheet(share, temporary, onAccept = {}, onCancel = {})
    }

    @Test
    @Config(qualifiers = "w360dp-h760dp-xhdpi")
    fun share_sheet() = sheetShot(
        "share_sheet", "apofis-1",
        Share(1, "Tomato bed, June: basil along the south edge, marigolds at the corners, lettuce in the shade of the stakes.", List(3) { ByteArray(1) }, setOf(NoteKind.SHARE_IMAGES_FAILED)),
        temporary = false,
    )

    @Test
    @Config(qualifiers = "w360dp-h760dp-xhdpi")
    fun share_sheet_temporary() = sheetShot("share_sheet_temporary", "apofis-tmp-1", Share(1, "Basil, marigolds and lettuce", emptyList(), emptySet()), temporary = true)

    /** The sheet lays out and reads out a short preview; Accept still fills the whole text. */
    @Test
    fun previewIsBoundedAcceptFillsAll() = inRoute(SHARED_A, seedA()) {
        waitSelected { it == SHARED_A }
        val long = "word ".repeat(20_000)
        assertEquals(100_000, long.length)
        offer(long)
        waitVisible(title)
        val preview = rule.onNodeWithTag("share_preview").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
        assertTrue(preview.length.toString(), preview.length in 1..PREVIEW_CHARS)
        assertTrue(long.startsWith(preview))
        rule.onNodeWithText(accept).performClick()
        val k = acceptedFrom(SHARED_A, filled(long))
        assertEquals(100_000, composerOf(k).draft.length)
    }

    /** A put that fails drops that photo only: the chat still opens with the others in order and the failed note. */
    @Test
    fun failedPutDropsThatImageOnly() = inRoute(SHARED_A, seedA()) {
        waitSelected { it == SHARED_A }
        val red = photo(Color.RED)
        val blue = photo(Color.BLUE)
        offer("", listOf(red, ByteArray(ImageLimits.MAX_SOURCE_BYTES.toInt() + 1), blue)) // the store refuses the middle one
        waitVisible(title)
        rule.onNodeWithText(accept).performClick()
        acceptedFrom(SHARED_A, filled("", listOf(sha(red), sha(blue)), ComposerNote(NoteKind.SHARE_IMAGES_FAILED)))
        assertEquals(2, imageFiles().size)
    }

    private fun shown(text: String) = runCatching { rule.onNodeWithText(text).assertIsDisplayed() }.isSuccess

    /** Accept over the archive with the drawer open shows the new chat: the archive and the drawer close, as for a tap. */
    @Test
    fun acceptClosesArchiveAndDrawer() = inRoute(SHARED_A, {
        seedA()()
        db.sessionDao().upsert(
            SessionEntity(
                key = "apofis-old", currentId = "apofis-old", lineageIds = "", title = "Old chat", pinned = false, archived = true,
                startedAt = 1.0, lastActiveAt = 1.0, messageCount = 1, preview = null, stale = false, existsOnServer = true,
            ),
        )
    }) {
        waitSelected { it == SHARED_A }
        rule.onNodeWithContentDescription(str(R.string.drawer_open)).performClick()
        rule.waitUntil(10_000) { shown(str(R.string.settings_title)) }
        rule.onNodeWithText(str(R.string.drawer_archived)).performClick()
        waitVisible(str(R.string.archived_title))
        offer("from the archive")
        waitVisible(title)
        rule.onNodeWithText(accept).performClick()
        acceptedFrom(SHARED_A, filled("from the archive"))
        rule.waitUntil(10_000) { !visible(str(R.string.archived_title)) && !shown(str(R.string.settings_title)) }
        waitVisible("from the archive") // the new chat's composer
    }
}
