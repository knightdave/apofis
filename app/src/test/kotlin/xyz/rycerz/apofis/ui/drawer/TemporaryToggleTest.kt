package xyz.rycerz.apofis.ui.drawer

import android.Manifest
import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.performClick
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import java.time.ZoneOffset
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.AppContainer
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.api.ChatModel
import xyz.rycerz.apofis.chat.ComposerNote
import xyz.rycerz.apofis.chat.NoteKind
import xyz.rycerz.apofis.db.MessageEntity
import xyz.rycerz.apofis.db.choice
import xyz.rycerz.apofis.notify.Notice
import xyz.rycerz.apofis.notify.Notifier
import xyz.rycerz.apofis.notify.ensureChannels
import xyz.rycerz.apofis.settings.CredentialStore
import xyz.rycerz.apofis.settings.Credentials
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.sync.isTemporaryKey
import xyz.rycerz.apofis.sync.withRepositoryLock
import xyz.rycerz.apofis.ui.ChatRoute
import xyz.rycerz.apofis.ui.NewChatButton
import xyz.rycerz.apofis.ui.chat.ChatActions
import xyz.rycerz.apofis.ui.chat.ChatScreen
import xyz.rycerz.apofis.ui.chat.ChatUiState
import xyz.rycerz.apofis.ui.chat.ChromeProbe
import xyz.rycerz.apofis.ui.chat.ComposerMode
import xyz.rycerz.apofis.ui.chat.StatusLine
import xyz.rycerz.apofis.ui.theme.ApofisTheme

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE) // the glyph test reads drawn pixels
@Config(sdk = [37])
class TemporaryToggleTest : TemporaryFixture() {
    @get:Rule
    val rule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private fun str(id: Int) = app.getString(id)
    private val composer get() = connected.composer

    /** A fresh drawer on an empty phone: its new, never-created ordinary chat is open. */
    private suspend fun fresh(): Pair<DrawerViewModel, String> {
        val vm = vm()
        return vm to vm.selected.await { it != null }!!
    }

    @Test
    fun toggleOnEmptyChat() = runBlocking<Unit> {
        val (vm, old) = fresh()
        vm.newChatButton.await { it == NewChatState.TEMPORARY_OFF }
        val tmp = vm.toggleTemporary()!!
        assertTrue(tmp.startsWith("apofis-tmp-"))
        assertEquals(tmp, vm.selected.value)
        assertNull(db.sessionDao().get(old))
        assertFalse(db.sessionDao().get(tmp)!!.existsOnServer)
        vm.newChatButton.await { it == NewChatState.TEMPORARY_ON }

        val back = vm.toggleTemporary()!! // and back to an ordinary chat
        assertFalse(isTemporaryKey(back))
        assertEquals(back, vm.selected.value)
        assertNull(db.sessionDao().get(tmp))
        assertEquals(listOf(back), db.sessionDao().all().map { it.key })
        assertTrue(requests.isEmpty())
    }

    @Test
    fun toggleRefusedForCreateStartedRow() = runBlocking<Unit> {
        session(T, onServer = false, createStarted = true) // its create may have reached the server
        deleted(T)
        val vm = vm(lastChat = T)
        vm.selected.await { it == T }
        assertEquals(NewChatState.NEW, vm.newChatButton.await { it == NewChatState.NEW })
        vm.newChat(toggle = true) // the bar's button: a new chat, and the leave
        val next = vm.selected.await { it != T }!!
        assertFalse(isTemporaryKey(next))
        until { db.sessionDao().get(T) == null }
        assertEquals(listOf("DELETE /api/sessions/$T"), deletes())
    }

    @Test
    fun toggleRefusedForCreateStartedOrdinaryRow() = runBlocking<Unit> {
        session(A, onServer = false, createStarted = true)
        val vm = vm(lastChat = A)
        vm.selected.await { it == A }
        vm.newChatButton.await { it == NewChatState.NEW }
        assertNull(vm.toggleTemporary())
        assertEquals(A, vm.selected.value)
        assertNotNull(db.sessionDao().get(A))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun buttonStartsNewChatWhenNotEmpty() = runBlocking<Unit> {
        val (vm, old) = fresh()
        pending(old) // a message waits to be sent: no longer empty
        vm.newChatButton.await { it == NewChatState.NEW }
        vm.newChat(toggle = true)
        val next = vm.selected.await { it != old }!!
        assertFalse(isTemporaryKey(next))
        assertNotNull(db.sessionDao().get(old))

        session(A) // on the server
        vm.onSelect(A)
        vm.selected.await { it == A }
        vm.newChatButton.await { it == NewChatState.NEW }
        vm.newChat(toggle = true)
        assertFalse(isTemporaryKey(vm.selected.await { it != A }!!))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun draftMovesWithToggle() = runBlocking<Unit> {
        val (vm, old) = fresh()
        composer.edit(old, "half a thought", composer.current(old).revision)
        val before = composer.current(old).revision
        val tmp = vm.toggleTemporary()!!
        assertEquals("half a thought", composer.current(tmp).draft)
        assertNotEquals(before, composer.current(tmp).revision) // a fresh revision
        assertFalse(old in composer.keys)
        assertEquals("", composer.current(old).draft)
    }

    @Test
    fun choiceMovesWithToggle() = runBlocking<Unit> {
        val (vm, old) = fresh()
        val choice = ChatModel("model-x", "provider-y", "high", true)
        connected.repo.setChoice(old, choice)
        val tmp = vm.toggleTemporary()!!
        assertEquals(choice, db.sessionDao().get(tmp)!!.choice())
        val back = vm.toggleTemporary()!!
        assertEquals(choice, db.sessionDao().get(back)!!.choice())
    }

    @Test
    fun draftImagesAndNoteMoveWithToggle() = runBlocking<Unit> {
        val (vm, old) = fresh()
        composer.edit(old, "lost words", composer.current(old).revision)
        composer.send(old) { throw IllegalStateException("not stored") }!!.join() // an unsent entry
        composer.edit(old, "half", composer.current(old).revision)
        val ids = composer.addImages(old, 2)
        composer.setImage(ids[0], "d0")
        val note = ComposerNote(NoteKind.FAST_ON)
        composer.setNote(old, note)

        val tmp = vm.toggleTemporary()!!
        composer.setImage(ids[1], "d1") // still processing at the toggle: lands on the new key
        val moved = composer.current(tmp)
        assertEquals("half", moved.draft)
        assertEquals(listOf("d0", "d1"), moved.images.map { it.digest })
        assertEquals(note, moved.note)
        assertEquals("lost words", moved.unsent?.text)
        assertFalse(old in composer.keys)
    }

    @Test
    fun toggleRefusedWhileSendIsBeingStored() = runBlocking<Unit> {
        val (vm, old) = fresh()
        composer.edit(old, "hello", composer.current(old).revision)
        val stored = CompletableDeferred<Unit>()
        val send = composer.send(old) { stored.await() }!!
        vm.newChatButton.await { it == NewChatState.NEW }
        assertNull(vm.toggleTemporary())
        assertEquals(old, vm.selected.value)
        assertEquals("hello", composer.current(old).draft)
        assertTrue(composer.current(old).sending)
        assertEquals(listOf(old), db.sessionDao().all().map { it.key })
        stored.complete(Unit)
        send.join()
    }

    @Test
    fun refusedReplacePutsDraftBack() = runBlocking<Unit> {
        val (vm, old) = fresh()
        composer.edit(old, "half", composer.current(old).revision)
        val locked = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val lock = launch(Dispatchers.IO) { withRepositoryLock { locked.complete(Unit); release.await() } }
        locked.await()
        val toggle = async(Dispatchers.Default) { vm.toggleTemporary() }
        until { old !in composer.keys } // moved; the replacement waits for the repository lock
        pending(old) // a message stored between the move and the replacement
        release.complete(Unit)
        lock.join()
        assertNull(withTimeout(60_000) { toggle.await() })
        assertEquals(old, vm.selected.value)
        assertEquals("half", composer.current(old).draft)
        assertEquals(listOf(old), db.sessionDao().all().map { it.key })
        assertEquals(setOf(old), composer.keys)
    }

    @Test
    fun staleEditAfterToggleDropped() = runBlocking<Unit> {
        val (vm, old) = fresh()
        composer.edit(old, "half", composer.current(old).revision)
        val drawn = composer.current(old).revision // the field drawn before the toggle
        val tmp = vm.toggleTemporary()!!
        composer.edit(tmp, "late keystroke", drawn)
        composer.edit(old, "late keystroke", drawn)
        assertEquals("half", composer.current(tmp).draft)
        assertEquals("", composer.current(old).draft)
    }

    // ---- the bar, the drawer row and the notification ----

    private fun themed(content: @androidx.compose.runtime.Composable () -> Unit) =
        rule.setContent { ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false, content = content) }

    @Test
    fun glyphFollowsState() {
        var state by mutableStateOf(NewChatState.NEW)
        var primary = 0
        themed {
            primary = MaterialTheme.colorScheme.primary.toArgb()
            NewChatButton(state) {}
        }
        fun pixels(label: Int): IntArray {
            rule.waitForIdle()
            val b = rule.onNodeWithContentDescription(str(label)).captureToImage().asAndroidBitmap()
            return IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        }
        val new = pixels(R.string.drawer_new_chat)
        state = NewChatState.TEMPORARY_OFF
        val off = pixels(R.string.chat_temporary_on)
        state = NewChatState.TEMPORARY_ON
        val on = pixels(R.string.chat_temporary_off)
        assertFalse("the dashed glyph replaces the pen", new.contentEquals(off))
        assertFalse("off is not drawn in primary", off.any { it == primary })
        assertTrue("on is drawn in primary", on.any { it == primary })
    }

    @Test
    fun labelFollowsState() = runBlocking<Unit> {
        val (vm, _) = fresh()
        vm.newChatButton.await { it == NewChatState.TEMPORARY_OFF }
        themed { NewChatButton(vm.newChatButton.collectAsState().value) { vm.newChat(toggle = true) } }
        rule.onNodeWithContentDescription(str(R.string.chat_temporary_on)).performClick()
        vm.newChatButton.await { it == NewChatState.TEMPORARY_ON }
        rule.onNodeWithContentDescription(str(R.string.chat_temporary_off)).performClick()
        vm.newChatButton.await { it == NewChatState.TEMPORARY_OFF }
        rule.onNodeWithContentDescription(str(R.string.chat_temporary_on)).performClick()
        val tmp = vm.newChatButton.await { it == NewChatState.TEMPORARY_ON }.let { vm.selected.value!! }
        pending(tmp) // a temporary chat with a message: the plain New chat button
        vm.newChatButton.await { it == NewChatState.NEW }
        rule.onNodeWithContentDescription(str(R.string.drawer_new_chat)).assertExists()
    }

    @Test
    fun titleShowsTemporary() {
        var state by mutableStateOf(ChatUiState(T, "Secret plan", StatusLine.IDLE, emptyList(), ComposerMode.SEND_DISABLED, ""))
        themed { ChatScreen(state, object : ChatActions {}, onOpenConnection = {}, zone = ZoneOffset.UTC) }
        rule.onNodeWithTag("chat.title").assertTextEquals(str(R.string.chat_temporary_title))
        state = state.copy(sessionKey = "apofis-1", title = "")
        rule.waitForIdle()
        rule.onNodeWithTag("chat.title").assertTextEquals(str(R.string.chat_new_title))
        state = state.copy(title = "Garden plans")
        rule.waitForIdle()
        rule.onNodeWithTag("chat.title").assertTextEquals("Garden plans")
    }

    @Test
    fun drawerRowShowsFixedTitle() {
        themed {
            androidx.compose.foundation.layout.Column {
                ChatRow(DrawerRow.Chat(T, "Secret plan", selected = false, running = false, needsInput = false), {}, {}, emptyList())
                ChatRow(DrawerRow.Chat(A, "Garden plans", selected = false, running = false, needsInput = false), {}, {}, emptyList())
            }
        }
        rule.onNodeWithText(str(R.string.chat_temporary_title)).assertExists()
        rule.onNodeWithText("Garden plans").assertExists()
        assertTrue(rule.onAllNodesWithText("Secret plan").fetchSemanticsNodes().isEmpty())
    }

    @Test
    fun notificationTitleIsFixed() = runBlocking<Unit> {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        ensureChannels(app)
        session(T, title = "Secret plan")
        session(A, title = "Garden plans")
        val prefs = app.getSharedPreferences("temporary-notify-test", Context.MODE_PRIVATE)
        val notifier = Notifier(app, prefs, db, { 1L }, { null }, scope)
        notifier.notice(T, "run_t", 1, Notice.FINISHED, "run:run_t", 1L, replay = false)
        notifier.notice(A, "run_a", 1, Notice.FINISHED, "run:run_a", 1L, replay = false)
        notifier.idle()
        val shown = app.getSystemService(NotificationManager::class.java).activeNotifications
        fun extras(key: String) = shown.single { it.tag == key }.notification.extras
        val tmp = extras(T)
        assertEquals(str(R.string.chat_temporary_title), tmp.getCharSequence(Notification.EXTRA_TITLE).toString())
        assertEquals(str(R.string.notif_reply_finished), tmp.getCharSequence(Notification.EXTRA_TEXT).toString())
        assertTrue(tmp.keySet().none { tmp.get(it)?.toString()?.contains("Secret") == true })
        assertEquals("Garden plans", extras(A).getCharSequence(Notification.EXTRA_TITLE).toString())
    }

    /**
     * Through the app's chat route: 100 streamed updates of the open chat (Room writes to its message rows, as a sync
     * makes them) leave the bar, the bottom area and the root measure alone, in an ordinary and in a temporary chat.
     */
    @Test
    fun chromeStableWithTemporaryMark() {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val container = AppContainer(app, CredentialStore(app) { key }) { db }
        val ordinary = "apofis-o"
        runBlocking {
            container.connect(Credentials(server.url("/").toString(), "key-1"))
            session(ordinary, title = "Garden plans", onServer = false)
            session(T, title = "Secret plan", onServer = false)
        }
        container.preferences.lastChat = ordinary
        val conn = container.connected()!!
        val owner = object : ViewModelStoreOwner {
            override val viewModelStore = ViewModelStore()
        }
        themed { ChatRoute(container, conn, owner, 0, onSettings = {}, onConnection = {}) }
        rule.onNodeWithTag("chat.title").assertTextEquals("Garden plans")

        fun stream(chat: String) {
            rule.waitForIdle()
            val bar = ChromeProbe.barCompositions
            val bottom = ChromeProbe.bottomCompositions
            val root = ChromeProbe.rootMeasures
            for (i in 1..100) {
                runBlocking {
                    db.messageDao().replaceSession(
                        chat,
                        listOf(
                            MessageEntity(chat, 0, 1, "user", null, "Question", null, null, null, null, 1.0, null),
                            MessageEntity(chat, 1, 2, "assistant", null, "Streamed reply $i", null, null, null, null, 2.0, null),
                        ),
                    )
                }
                rule.waitUntil(10_000) { rule.onAllNodesWithText("Streamed reply $i", substring = true).fetchSemanticsNodes().isNotEmpty() }
            }
            rule.waitForIdle()
            assertEquals("bar recomposed in $chat", bar, ChromeProbe.barCompositions)
            assertEquals("bottom recomposed in $chat", bottom, ChromeProbe.bottomCompositions)
            assertEquals("root measured in $chat", root, ChromeProbe.rootMeasures)
        }
        stream(ordinary)

        val drawer = ViewModelProvider.create(owner.viewModelStore)[DrawerViewModel::class]
        drawer.onSelect(T)
        rule.waitUntil(10_000) { rule.onAllNodesWithText(str(R.string.chat_temporary_title)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("chat.title").assertTextEquals(str(R.string.chat_temporary_title))
        stream(T)
        owner.viewModelStore.clear()
    }
}
