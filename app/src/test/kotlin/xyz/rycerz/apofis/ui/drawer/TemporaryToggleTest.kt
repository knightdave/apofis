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
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
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
import xyz.rycerz.apofis.sync.isBeingDeleted
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
import xyz.rycerz.apofis.ui.theme.Haptics
import xyz.rycerz.apofis.ui.theme.LocalHaptics
import androidx.compose.runtime.CompositionLocalProvider

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
        vm.shown(NewChatState.TEMPORARY_OFF)
        val tmp = vm.toggle()!!
        assertTrue(tmp.startsWith("apofis-tmp-"))
        assertEquals(tmp, vm.selected.value)
        assertNull(db.sessionDao().get(old))
        assertFalse(db.sessionDao().get(tmp)!!.existsOnServer)
        vm.shown(NewChatState.TEMPORARY_ON)

        val back = vm.toggle()!! // and back to an ordinary chat
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
        vm.newChat(vm.shown(NewChatState.NEW)) // the bar's button: a new chat, and the leave
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
        vm.shown(NewChatState.NEW)
        assertNull(vm.toggleTemporary(NewChat(A, NewChatState.TEMPORARY_OFF))) // a tap of a state it never had: dropped
        assertFalse(connected.repo.replaceEmptyLocal(A, "apofis-tmp-x")) // and the repository refuses it too
        assertEquals(A, vm.selected.value)
        assertNotNull(db.sessionDao().get(A))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun buttonStartsNewChatWhenNotEmpty() = runBlocking<Unit> {
        val (vm, old) = fresh()
        pending(old) // a message waits to be sent: no longer empty
        vm.newChat(vm.shown(NewChatState.NEW))
        val next = vm.selected.await { it != old }!!
        assertFalse(isTemporaryKey(next))
        assertNotNull(db.sessionDao().get(old))

        session(A) // on the server
        vm.onSelect(A)
        vm.selected.await { it == A }
        vm.newChat(vm.shown(NewChatState.NEW))
        assertFalse(isTemporaryKey(vm.selected.await { it != A }!!))
        assertTrue(requests.isEmpty())
    }

    @Test
    fun draftMovesWithToggle() = runBlocking<Unit> {
        val (vm, old) = fresh()
        composer.edit(old, "half a thought", composer.current(old).revision)
        val before = composer.current(old).revision
        val tmp = vm.toggle()!!
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
        val tmp = vm.toggle()!!
        assertEquals(choice, db.sessionDao().get(tmp)!!.choice())
        val back = vm.toggle()!!
        assertEquals(choice, db.sessionDao().get(back)!!.choice())
    }

    @Test
    fun draftImagesAndNoteMoveWithToggle() = runBlocking<Unit> {
        val (vm, old) = fresh()
        composer.edit(old, "lost words", composer.current(old).revision)
        composer.sendWithImages(old) { _, _ -> throw IllegalStateException("not stored") }!!.join() // an unsent entry
        composer.edit(old, "half", composer.current(old).revision)
        val ids = composer.addImages(old, 2)
        composer.setImage(ids[0], "d0")
        val note = ComposerNote(NoteKind.FAST_ON)
        composer.setNote(old, note)

        val tmp = vm.toggle()!!
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
        val shown = vm.shown(NewChatState.TEMPORARY_OFF)
        val stored = CompletableDeferred<Unit>()
        val send = composer.sendWithImages(old) { _, _ -> stored.await() }!!
        vm.shown(NewChatState.NEW)
        assertNull(vm.toggleTemporary(shown))
        assertEquals(old, vm.selected.value)
        assertEquals("hello", composer.current(old).draft)
        assertTrue(composer.current(old).sending)
        assertEquals(listOf(old), db.sessionDao().all().map { it.key })
        stored.complete(Unit)
        send.join()
    }

    /** The repository lock held as a refresh holds it; [release] lets it go. */
    private class HeldLock(scope: CoroutineScope) {
        val locked = CompletableDeferred<Unit>()
        private val released = CompletableDeferred<Unit>()
        val job = scope.launch(Dispatchers.IO) { withRepositoryLock { locked.complete(Unit); released.await() } }
        suspend fun release() {
            released.complete(Unit)
            job.join()
        }
    }

    private suspend fun heldLock() = HeldLock(side).also { it.locked.await() }

    @Test
    fun textTypedWhileTheReplacementWaitsStays() = runBlocking<Unit> {
        val (vm, old) = fresh()
        composer.edit(old, "first", composer.current(old).revision)
        val shown = vm.shown(NewChatState.TEMPORARY_OFF)
        val lock = heldLock()
        // Started here, it runs until it waits for the repository lock (nothing before that suspends).
        val toggle = side.async(start = CoroutineStart.UNDISPATCHED) { vm.toggleTemporary(shown) }
        composer.edit(old, "first second", composer.current(old).revision) // the field still shows the old key's draft
        lock.release()
        val new = withTimeout(60_000) { toggle.await() }!!
        assertEquals(new, vm.selected.value)
        assertEquals("first second", composer.current(new).draft)
        assertFalse(old in composer.keys)
    }

    @Test
    fun refusedReplacementKeepsTheWholeDraft() = runBlocking<Unit> {
        val (vm, old) = fresh()
        composer.edit(old, "first", composer.current(old).revision)
        val shown = vm.shown(NewChatState.TEMPORARY_OFF)
        val lock = heldLock()
        val toggle = side.async(start = CoroutineStart.UNDISPATCHED) { vm.toggleTemporary(shown) }
        composer.edit(old, "first second", composer.current(old).revision)
        pending(old) // a message stored while the replacement waits
        lock.release()
        assertNull(withTimeout(60_000) { toggle.await() })
        assertEquals(old, vm.selected.value)
        assertEquals("first second", composer.current(old).draft)
        assertEquals(listOf(old), db.sessionDao().all().map { it.key })
    }

    @Test
    fun toggleCancelledAfterItsCommitCompletes() = runBlocking<Unit> {
        val (vm, old) = fresh()
        composer.edit(old, "half", composer.current(old).revision)
        val shown = vm.shown(NewChatState.TEMPORARY_OFF)
        val caller = StandardTestDispatcher(TestCoroutineScheduler()) // runs only when the test says so
        val lock = heldLock()
        val toggle = side.launch(caller) { vm.toggleTemporary(shown) }
        caller.scheduler.runCurrent() // waits for the repository lock
        lock.release()
        // Driven step by step until the transaction has committed, checked before each step: its return then waits on
        // the caller, and the cancellation lands before it.
        until {
            db.sessionDao().all().any { isTemporaryKey(it.key) } || run {
                caller.scheduler.runCurrent()
                false
            }
        }
        toggle.cancel()
        until {
            caller.scheduler.runCurrent()
            toggle.isCompleted
        }
        val new = vm.selected.value!!
        assertTrue(isTemporaryKey(new))
        assertEquals(listOf(new), db.sessionDao().all().map { it.key })
        assertEquals("half", composer.current(new).draft)
        assertFalse(old in composer.keys)
    }

    @Test
    fun sendClaimedBeforeTheReplacementBecomesTheNewKeysUnsentText() = runBlocking<Unit> {
        val (vm, old) = fresh()
        composer.edit(old, "hello", composer.current(old).revision)
        val shown = vm.shown(NewChatState.TEMPORARY_OFF)
        val lock = heldLock()
        val toggle = side.async(start = CoroutineStart.UNDISPATCHED) { vm.toggleTemporary(shown) }
        val storing = CompletableDeferred<Unit>()
        composer.sendWithImages(old) { text, _ -> storing.await(); connected.repo.send(old, text) }!! // claimed now
        lock.release()
        until { db.sessionDao().all().any { isTemporaryKey(it.key) } } // replaced while the send is still storing
        storing.complete(Unit) // the store finds no chat: its text becomes unsent, which then moves
        val new = withTimeout(60_000) { toggle.await() }!!
        assertEquals(new, vm.selected.value)
        assertEquals("hello", composer.current(new).unsent?.text)
        assertTrue(db.pendingSendDao().rows(old).isEmpty() && db.pendingSendDao().rows(new).isEmpty())
    }

    @Test
    fun replacementIsOneTransaction() = runBlocking<Unit> {
        val (vm, old) = fresh()
        composer.edit(old, "half", composer.current(old).revision)
        connected.repo.setChoice(old, ChatModel("model-x", "provider-y", "high", true))
        val before = db.sessionDao().get(old)!!
        // The replacement row cannot be inserted: without one transaction the old row's delete would stay.
        db.openHelper.writableDatabase.execSQL(
            "CREATE TEMP TRIGGER refuse_temporary BEFORE INSERT ON SessionEntity WHEN NEW.`key` LIKE 'apofis-tmp-%' " +
                "BEGIN SELECT RAISE(ABORT, 'refused by the test'); END",
        )
        assertTrue(runCatching { vm.toggle() }.isFailure)
        assertEquals(listOf(before), db.sessionDao().all())
        assertEquals(old, vm.selected.value)
        assertEquals("half", composer.current(old).draft)
    }

    @Test
    fun staleToggleIsDropped() = runBlocking<Unit> {
        session(T, onServer = false)
        deleted(T)
        val vm = vm(lastChat = T)
        val shown = vm.shown(NewChatState.TEMPORARY_ON) // the press begins on "Make this a normal chat"
        pending(T) // a send stored before the release
        db.sessionDao().upsert(db.sessionDao().get(T)!!.copy(createStarted = true))
        vm.shown(NewChatState.NEW)
        var toggled = 0
        vm.newChat(shown) { toggled++ } // the release
        // Waits for the selection lock behind the release: true only if no New chat (and no leave of T) ran first.
        assertTrue(vm.selectTapped(T) { true })
        settled()
        assertFalse(isBeingDeleted(T))
        assertEquals(T, vm.selected.value)
        assertNotNull(db.sessionDao().get(T))
        assertTrue(deletes().isEmpty())
        assertEquals(0, toggled)
    }

    @Test
    fun newChatDoesNotReuseACreateStartedOrStoringChat() = runBlocking<Unit> {
        session(A, onServer = false, createStarted = true)
        val vm = vm(lastChat = A)
        vm.selected.await { it == A }
        val next = vm.onNewChat()
        assertNotEquals(A, next)

        composer.edit(next, "hello", composer.current(next).revision)
        val stored = CompletableDeferred<Unit>()
        val send = composer.sendWithImages(next) { _, _ -> stored.await() }!!
        assertNotEquals(next, vm.onNewChat()) // the chat about to hold that message is not reused
        stored.complete(Unit)
        send.join()
        assertTrue(requests.isEmpty())
    }

    @Test
    fun closedRepositoryChangesNothing() = runBlocking<Unit> {
        val (vm, old) = fresh()
        composer.edit(old, "half", composer.current(old).revision)
        val shown = vm.shown(NewChatState.TEMPORARY_OFF)
        var lock = heldLock()
        val toggle = side.async(start = CoroutineStart.UNDISPATCHED) { runCatching { vm.toggleTemporary(shown) } }
        connected.close() // a server switch while the toggle waits
        lock.release()
        assertTrue(withTimeout(60_000) { toggle.await() }.isFailure)
        assertEquals(listOf(old), db.sessionDao().all().map { it.key })
        assertEquals(old, vm.selected.value)
        assertEquals("half", composer.current(old).draft)

        lock = heldLock()
        var filled = false
        val share = side.async(start = CoroutineStart.UNDISPATCHED) { vm.openFreshChatIf(old, { true }) { filled = true } }
        lock.release()
        assertNull(withTimeout(60_000) { share.await() })
        assertFalse(filled)
        assertEquals(listOf(old), db.sessionDao().all().map { it.key })
        assertEquals(old, vm.selected.value)
    }

    @Test
    fun staleEditAfterToggleDropped() = runBlocking<Unit> {
        val (vm, old) = fresh()
        composer.edit(old, "half", composer.current(old).revision)
        val drawn = composer.current(old).revision // the field drawn before the toggle
        val tmp = vm.toggle()!!
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
        val page = new[0] // a corner: no glyph there
        fun mask(px: IntArray) = BooleanArray(px.size) { px[it] != page }
        fun differ(a: IntArray, b: IntArray) = mask(a).zip(mask(b)).count { (x, y) -> x != y }
        // The same glyph in two tints differs only in a few antialiased edge pixels; the pen differs in its shape.
        assertTrue("the dashed glyph replaces the pen", differ(new, off) > 0)
        assertTrue("on draws the same glyph as off", differ(on, off) * 10 < differ(new, off))
        assertFalse("on is tinted, off is not", on.contentEquals(off))
        assertFalse("off is not drawn in primary", off.any { it == primary })
        assertTrue("on is drawn in primary", on.any { it == primary })
    }

    @Test
    fun labelFollowsState() = runBlocking<Unit> {
        val (vm, _) = fresh()
        vm.shown(NewChatState.TEMPORARY_OFF)
        themed {
            val b = vm.newChatButton.collectAsState().value
            NewChatButton(b.state) { vm.newChat(b) }
        }
        rule.onNodeWithContentDescription(str(R.string.chat_temporary_on)).performClick()
        vm.shown(NewChatState.TEMPORARY_ON)
        rule.onNodeWithContentDescription(str(R.string.chat_temporary_off)).performClick()
        vm.shown(NewChatState.TEMPORARY_OFF)
        rule.onNodeWithContentDescription(str(R.string.chat_temporary_on)).performClick()
        val tmp = vm.shown(NewChatState.TEMPORARY_ON).key!!
        pending(tmp) // a temporary chat with a message: the plain New chat button
        vm.shown(NewChatState.NEW)
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
    private val owner = object : ViewModelStoreOwner {
        override val viewModelStore = ViewModelStore()
    }

    private var haptics = 0
    private var routeShown by mutableStateOf(true)
    private var routeContainer: AppContainer? = null

    /**
     * Ends a route test: the route leaves the composition (its chat view models are cleared), the container's own
     * collectors stop and Room's queue drains, all before the fixture closes the database (the rule disposes later).
     */
    private fun finishRoute() {
        routeShown = false
        rule.waitForIdle()
        owner.viewModelStore.clear()
        routeContainer?.scope?.cancel()
        runBlocking { settled() }
    }

    /** The app's chat route over this fixture's database, connected to its server, opening [lastChat]. */
    private fun route(lastChat: String, seed: suspend () -> Unit) {
        val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val container = AppContainer(app, CredentialStore(app) { key }) { db }.also { routeContainer = it }
        runBlocking {
            container.connect(Credentials(server.url("/").toString(), "key-1"))
            seed()
        }
        container.preferences.lastChat = lastChat
        val conn = container.connected()!!
        val counting = object : Haptics {
            override fun light() = Unit
            override fun selection() {
                haptics++
            }
        }
        themed {
            if (routeShown) CompositionLocalProvider(LocalHaptics provides counting) { ChatRoute(container, conn, owner, 0, onSettings = {}, onConnection = {}) }
        }
    }

    private fun drawer() = ViewModelProvider.create(owner.viewModelStore)[DrawerViewModel::class]

    private fun waitForLabel(id: Int) =
        rule.waitUntil(10_000) { rule.onAllNodesWithContentDescription(str(id)).fetchSemanticsNodes().isNotEmpty() }

    /** Through the real bar slot: the toggle changes the key with one haptic; in state New chat it opens a new chat. */
    @Test
    fun barTogglesThroughTheRoute() {
        route("apofis-o") { session("apofis-o", onServer = false) }
        waitForLabel(R.string.chat_temporary_on)
        rule.onNodeWithContentDescription(str(R.string.chat_temporary_on)).performClick()
        waitForLabel(R.string.chat_temporary_off)
        val tmp = drawer().selected.value!!
        assertTrue(isTemporaryKey(tmp))
        assertEquals(1, haptics)

        runBlocking { pending(tmp) } // no longer empty: the plain New chat
        waitForLabel(R.string.drawer_new_chat)
        rule.onNodeWithContentDescription(str(R.string.drawer_new_chat)).performClick()
        rule.waitUntil(10_000) { drawer().selected.value.let { it != tmp && it != null && !isTemporaryKey(it) } }
        assertEquals(1, haptics) // none for a New chat
        finishRoute()
    }

    /** Right after the selection changed, before Room delivered the new chat's state, the bar shows New chat. */
    @Test
    fun barShowsNewChatUntilItsStateCatchesUp() {
        route("apofis-o") { session("apofis-o", onServer = false) }
        waitForLabel(R.string.chat_temporary_on)
        val roomHeld = CountDownLatch(1)
        try {
            // The claim holds Room just before the selection changes, so the new key's state cannot arrive yet.
            val fresh = runBlocking { drawer().openFreshChatIf("apofis-o", { roomThread.execute { roomHeld.await(10, TimeUnit.SECONDS) }; true }) {} }
            assertEquals(fresh, drawer().selected.value)
            rule.waitForIdle()
            rule.onNodeWithContentDescription(str(R.string.drawer_new_chat)).assertExists()
            assertTrue(rule.onAllNodesWithContentDescription(str(R.string.chat_temporary_on)).fetchSemanticsNodes().isEmpty())
        } finally {
            roomHeld.countDown()
        }
        waitForLabel(R.string.chat_temporary_on) // the fresh empty chat's own state
        finishRoute()
    }

    @Test
    fun chromeStableWithTemporaryMark() {
        val ordinary = "apofis-o"
        route(ordinary) {
            session(ordinary, title = "Garden plans", onServer = false)
            session(T, title = "Secret plan", onServer = false)
        }
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

        drawer().onSelect(T)
        rule.waitUntil(10_000) { rule.onAllNodesWithText(str(R.string.chat_temporary_title)).fetchSemanticsNodes().isNotEmpty() }
        rule.onNodeWithTag("chat.title").assertTextEquals(str(R.string.chat_temporary_title))
        stream(T)
        finishRoute()
    }
}
