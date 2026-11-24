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
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
    protected fun connect(lastChat: String?, seed: suspend () -> Unit = {}) = runBlocking {
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

    /** The chat route of the container's current connection. */
    protected fun compose() = show {
        val conn = container.connection.collectAsState().value
        if (conn != null) key(conn) { ChatRoute(container, conn, ownerOf(conn), 0, onSettings = {}, onConnection = {}) }
    }

    /** Takes everything down before the fixture closes the database (also when an assertion failed). */
    protected fun finish() {
        shown = false
        rule.waitForIdle()
        owners.values.forEach { it.viewModelStore.clear() }
        container.scope.cancel()
        runBlocking { settled() }
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

    protected fun drawer(): DrawerViewModel = ViewModelProvider.create(ownerOf(container.connection.value!!).viewModelStore)[DrawerViewModel::class]

    protected fun waitSelected(condition: (String?) -> Boolean): String {
        rule.waitUntil(10_000) { runCatching { condition(drawer().selected.value) }.getOrDefault(false) }
        return drawer().selected.value!!
    }

    /**
     * The fresh chat an Accept from [old] opened, once its draft is filled. The test rule runs the route's scope
     * unconfined, so the drawer's step may go on off the main thread: the selection alone does not show that the fill
     * (and the set-aside of a temporary chat before it) has run.
     */
    protected fun acceptedFrom(old: String): String {
        val k = waitSelected { it != null && it != old }
        rule.waitUntil(10_000) { composerOf(k).let { it.draft.isNotEmpty() || it.images.isNotEmpty() } }
        return k
    }

    protected fun visible(text: String) = rule.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()
    protected fun waitVisible(text: String) = rule.waitUntil(10_000) { visible(text) }
    protected fun waitGone(text: String) = rule.waitUntil(10_000) { !visible(text) }

    /** Offers a share whose reading gives [text], [images] and [notes]; returns it once it is in the slot. */
    protected fun offer(text: String = "Shared words", images: List<ByteArray> = emptyList(), notes: Set<NoteKind> = emptySet()): Share = runBlocking {
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
        init { runBlocking { locked.await() } }
        fun release() = runBlocking {
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
        offer("Look at these", photos)
        waitVisible(title)
        rule.onNodeWithText(accept).performClick()
        val k = acceptedFrom(SHARED_A)
        waitGone(title)

        assertFalse(isTemporaryKey(k))
        assertNotNull(runBlocking { db.sessionDao().get(k) })
        assertNotNull(runBlocking { db.sessionDao().get(SHARED_A) })
        val draft = composerOf(k)
        assertEquals("Look at these", draft.draft)
        assertEquals(photos.map(::sha), draft.images.map { it.digest })
        assertTrue(draft.images.none { it.processing || it.failed })
        draft.images.forEach { assertNotNull(container.imageStore.read(it.digest!!)) }
        assertNull(draft.note)
        assertNull(container.pendingShare.value)
        assertEquals(emptyList<String>(), writes())
        waitVisible("Look at these") // the composer shows the draft
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
        val k = acceptedFrom(SHARED_T)
        runBlocking { drawer().leavesEnded() }

        assertEquals(listOf("DELETE /api/sessions/$SHARED_T"), deletes())
        assertNotNull(runBlocking { db.sessionDao().get(SHARED_T) })
        rule.waitUntil(10_000) { SHARED_T in drawer().rows.value.chats() } // listed again: it is no longer open
        assertEquals(ComposerNote(NoteKind.TEMPORARY_NOT_DELETED), composerOf(k).note) // replaces the share's note
        assertEquals("Shared words", composerOf(k).draft)
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
            val digest = runBlocking { container.imageStore.put(same)!! }
            val id = container.composer.addImages(SHARED_T, 1).single()
            container.composer.setImage(id, digest)

            offer(images = listOf(same))
            waitVisible(title)
            rule.onNodeWithText(accept).performClick()
            val k = acceptedFrom(SHARED_T)
            runBlocking { drawer().leavesEnded() }
            assertNull(runBlocking { db.sessionDao().get(SHARED_T) }) // left and deleted
            assertNull(composerOf(k).note) // the leave succeeded: no note

            // Old enough for any collection, still named by the new draft only.
            val file = checkNotNull(container.imageStore.file(digest))
            assertTrue(file.setLastModified(System.currentTimeMillis() - 11 * 60_000))
            runBlocking { container.collectImages() }
            assertEquals(listOf(digest), composerOf(k).images.map { it.digest })
            assertNotNull(container.imageStore.read(digest))
        }
    }

    /** Accepts [share] directly, as the sheet would for [selected], with [open] standing in for the drawer's step. */
    private fun acceptAs(share: Share, selected: String, open: suspend (String, () -> Boolean, suspend (String) -> Unit) -> String?): Boolean =
        runBlocking { container.acceptShare(ShareToken(share.version, container.connection.value!!.generation, selected), open) }

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
                runBlocking { container.connect(Credentials(url, "key-2")) }
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
        offer("only once", listOf(photo(Color.RED)))
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
        val k = acceptedFrom(SHARED_A)
        waitGone(title)
        rule.waitForIdle()
        assertEquals(keys + k, sessionKeys())
        assertEquals("only once", composerOf(k).draft)
        assertEquals(1, composerOf(k).images.size)
        assertFalse(container.claimShare(ShareToken(0, container.connection.value!!.generation, k)))
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
        val k = acceptedFrom(SHARED_B)
        assertEquals("the share", composerOf(k).draft)
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
        acceptedFrom(SHARED_A)
        rule.waitForIdle()
        assertEquals(emptyList<String>(), stops())
        assertEquals(emptyList<String>(), writes())
        assertEquals(1, runBlocking { db.activeRunDao().allForSession(SHARED_A) }.size)
    }

    @Test
    fun oldDraftUntouched() = inRoute(SHARED_A, seedA()) {
        waitSelected { it == SHARED_A }
        container.composer.edit(SHARED_A, "mine", composerOf(SHARED_A).revision)
        val before = composerOf(SHARED_A)
        offer("theirs")
        waitVisible(title)
        rule.onNodeWithText(accept).performClick()
        val k = acceptedFrom(SHARED_A)
        assertEquals(before, composerOf(SHARED_A))
        assertEquals("theirs", composerOf(k).draft)
    }

    @Test
    fun acceptFromEmptyChatWithDraftKeepsThatDraft() = inRoute(null) {
        val empty = waitSelected { it != null } // a new, never-created empty chat
        container.composer.edit(empty, "typed", composerOf(empty).revision)
        offer("shared")
        waitVisible(title)
        rule.onNodeWithText(accept).performClick()
        val k = acceptedFrom(empty) // always a fresh chat, also from an empty one
        assertNotNull(runBlocking { db.sessionDao().get(empty) })
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
        val k = acceptedFrom(SHARED_A)
        rule.waitForIdle()
        runBlocking { container.composer.settle() }
        assertEquals("/stop", composerOf(k).draft) // nothing parsed it
        assertFalse(composerOf(k).sending)
        assertEquals(keys + k, sessionKeys())
        assertEquals(emptyList<String>(), writes())
        assertEquals(1, runBlocking { db.activeRunDao().allForSession(SHARED_A) }.size)
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
    }
}
