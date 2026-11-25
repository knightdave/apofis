package xyz.rycerz.apofis.ui.chat

import android.app.Application
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Looper
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.result.ActivityResultRegistry
import androidx.activity.result.ActivityResultRegistryOwner
import androidx.activity.result.contract.ActivityResultContract
import androidx.activity.result.contract.ActivityResultContracts.PickMultipleVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollToIndexAction
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.runtime.key
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityOptionsCompat
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.ZoneOffset
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.ApofisApp
import xyz.rycerz.apofis.AppContainer
import xyz.rycerz.apofis.Connected
import xyz.rycerz.apofis.MainActivity
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.api.HermesApi
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.ChatItem
import xyz.rycerz.apofis.chat.Composer
import xyz.rycerz.apofis.chat.ComposerNote
import xyz.rycerz.apofis.chat.DraftImage
import xyz.rycerz.apofis.chat.NoteKind
import xyz.rycerz.apofis.chat.SendState
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.db.ActiveRunEntity
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.db.ImageBindingEntity
import xyz.rycerz.apofis.db.MessageEntity
import xyz.rycerz.apofis.db.PendingSendEntity
import xyz.rycerz.apofis.db.SessionEntity
import xyz.rycerz.apofis.images.Decoded
import xyz.rycerz.apofis.images.Encoded
import xyz.rycerz.apofis.images.ImageStore
import xyz.rycerz.apofis.run.Connection
import xyz.rycerz.apofis.run.LiveReply
import xyz.rycerz.apofis.run.RunFollower
import xyz.rycerz.apofis.settings.CredentialStore
import xyz.rycerz.apofis.settings.Credentials
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.sync.ChatRepository
import xyz.rycerz.apofis.sync.SendQueue
import xyz.rycerz.apofis.ui.FIXED_NOW
import xyz.rycerz.apofis.ui.FIXED_TODAY
import xyz.rycerz.apofis.ui.image.LocalImageOpen
import xyz.rycerz.apofis.ui.image.LocalImageStore
import xyz.rycerz.apofis.ui.image.LocalThumbCache
import xyz.rycerz.apofis.ui.image.ThumbCache
import xyz.rycerz.apofis.ui.image.ViewerImage
import xyz.rycerz.apofis.ui.shot
import xyz.rycerz.apofis.ui.theme.ApofisTheme

/** A 64 hex digest for test photo [i]. */
private fun digest(i: Int) = "%064x".format(i)

private fun ready(n: Int, from: Int = 1) = List(n) { DraftImage(from + it.toLong(), digest(from + it)) }

/** Four colored quarters and a white diagonal, so the crop shows. */
private fun picture(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
    val canvas = Canvas(b)
    val paint = Paint()
    listOf(0xFF1E88E5, 0xFFE53935, 0xFF43A047, 0xFFFDD835).forEachIndexed { i, color ->
        paint.color = color.toInt()
        canvas.drawRect((i % 2) * w / 2f, (i / 2) * h / 2f, (i % 2 + 1) * w / 2f, (i / 2 + 1) * h / 2f, paint)
    }
    paint.color = android.graphics.Color.WHITE
    paint.strokeWidth = h / 20f
    canvas.drawLine(0f, 0f, w.toFloat(), h.toFloat(), paint)
}

private fun jpeg(bitmap: Bitmap) = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()

/** Decoded thumbnails for test photos 1 to [n], so a tile shows its picture from the first frame. */
private fun thumbs(n: Int) = ThumbCache().apply { for (i in 1..n) put(digest(i), Decoded.Ok(picture(if (i % 2 == 0) 180 else 320, 240))) }

/**
 * The composer's photos and the bubble's on the hosted chat screen, with fake actions: the picker and its contracts (a
 * test registry records each launch and delivers results), the strip and its controls, the note line, the follow rule on
 * the stored event, Send after the current reply and its Stop, and the strip in a short window with the keyboard.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class ImageSendTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun edgeToEdge() = rule.runOnUiThread { WindowCompat.setDecorFitsSystemWindows(rule.activity.window, false) }

    private fun insets(ime: Int = 0, top: Int = 0) {
        rule.runOnUiThread {
            val i = WindowInsetsCompat.Builder()
                .setInsets(WindowInsetsCompat.Type.ime(), Insets.of(0, 0, 0, ime)).setVisible(WindowInsetsCompat.Type.ime(), ime > 0)
                .setInsets(WindowInsetsCompat.Type.statusBars(), Insets.of(0, top, 0, 0))
                .build()
            ViewCompat.dispatchApplyWindowInsets(rule.activity.window.decorView, i)
        }
        frames(5)
    }

    private class Launch(val code: Int, val contract: ActivityResultContract<*, *>, val intent: Intent)

    private val launches = ArrayList<Launch>()
    private var launchFails = false // as on a device without a system photo picker
    private val registry = object : ActivityResultRegistry() {
        override fun <I, O> onLaunch(requestCode: Int, contract: ActivityResultContract<I, O>, input: I, options: ActivityOptionsCompat?) {
            if (launchFails) throw android.content.ActivityNotFoundException("no picker")
            launches += Launch(requestCode, contract, contract.createIntent(rule.activity, input))
        }
    }
    private val registryOwner = object : ActivityResultRegistryOwner {
        override val activityResultRegistry: ActivityResultRegistry get() = registry
    }

    private class Recorder : ChatActions {
        val picked = ArrayList<List<Uri>>()
        var limits = 0
        val removed = ArrayList<Long>()
        var sends = 0
        val stops = ArrayList<String>()

        override fun onImagesPicked(uris: List<Uri>) {
            picked += uris
        }

        override fun onImagesLimit() {
            limits++
        }

        override fun onRemoveImage(id: Long) {
            removed += id
        }

        override fun onSendClick(): Boolean {
            sends++
            return true // the holder took it: what the store makes of it is another matter
        }

        override fun onStopClick(runId: String) {
            stops += runId
        }
    }

    private val history: List<ChatItem> = (1..20).flatMap {
        listOf(UserMessage("u$it", "Question $it", steered = false, timestamp = FIXED_NOW), AssistantReply("a$it", emptyList(), "Answer $it", FIXED_NOW))
    }

    private fun state(images: List<DraftImage> = emptyList(), mode: ComposerMode = ComposerMode.SEND_DISABLED, draft: String = "", live: LiveReply? = null) =
        ChatUiState("k", "Chat", StatusLine.IDLE, withLive(history, live) { FIXED_NOW }, mode, draft, "hermes.example", live, images = images)

    private var ui by mutableStateOf(state())
    private var actions by mutableStateOf<ChatActions>(Recorder())
    private val recorder get() = actions as Recorder
    private var covered = false
    private val overlays = Overlays()
    private var answerClock = 1_000L
    private var cache: ThumbCache? = null
    private var store: ImageStore? = null
    private lateinit var list: LazyListState
    private lateinit var scope: CoroutineScope

    private fun frames(n: Int = 1) = repeat(n) {
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeByFrame()
    }

    /**
     * The chat at [start]; the list starts [index] rows up from the bottom (a reader when above 0). The screen is keyed by
     * the chat, as AppNav composes it: another chat is another screen. [restoration] hosts it for a saved-state restore.
     */
    private fun host(start: ChatUiState, index: Int = 0, restoration: StateRestorationTester? = null) {
        ui = start
        rule.mainClock.autoAdvance = false
        val content: @Composable () -> Unit = {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                CompositionLocalProvider(
                    LocalActivityResultRegistryOwner provides registryOwner,
                    LocalOverlays provides overlays,
                    LocalAnswerClock provides { answerClock },
                    LocalThumbCache provides cache,
                    LocalImageStore provides store,
                ) {
                    list = rememberLazyListState(index)
                    scope = rememberCoroutineScope()
                    key(ui.sessionKey) {
                        ChatScreen(ui, actions, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY, listState = list, coveredNow = { covered })
                    }
                }
            }
        }
        if (restoration != null) restoration.setContent(content) else rule.setContent(content)
        frames(10)
    }

    private val plus = hasContentDescription("Add photos")
    private val remove = hasContentDescription("Remove image")

    private fun tap(m: SemanticsMatcher) {
        rule.onNode(m, useUnmergedTree = true).performClick()
        frames(5)
    }

    private fun count(m: SemanticsMatcher) = rule.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().size

    private fun pickLimit(l: Launch) = l.intent.getIntExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, -1)

    @Test
    fun plusOpensPicker() {
        host(state())
        tap(plus)
        val first = launches.single()
        assertTrue("the multiple picker: ${first.contract}", first.contract is PickMultipleVisualMedia)
        assertEquals(10, pickLimit(first))
        assertEquals(MediaStore.ACTION_PICK_IMAGES, first.intent.action)
        assertEquals("images only", "image/*", first.intent.type)

        ui = state(ready(3))
        frames(3)
        tap(plus) // the count is read at tap time
        assertEquals(7, pickLimit(launches[1]))
        ui = state(ready(8))
        frames(3)
        tap(plus)
        assertTrue(launches[2].contract is PickMultipleVisualMedia)
        assertEquals(2, pickLimit(launches[2]))
        ui = state(ready(9))
        frames(3)
        tap(plus)
        assertTrue("one left: the single picker", launches[3].contract is PickVisualMedia)
        assertEquals("images only", "image/*", launches[3].intent.type)
        ui = state(ready(10))
        frames(3)
        tap(plus)
        assertEquals(4, launches.size)
        assertEquals(1, recorder.limits)
    }

    @Test
    fun nineAttachedOpensSinglePicker() {
        host(state(ready(9)))
        tap(plus)
        val launch = launches.single()
        assertTrue("the single picker: ${launch.contract}", launch.contract is PickVisualMedia)
        val uri = Uri.parse("content://picker/1")
        rule.runOnUiThread { registry.dispatchResult(launch.code, uri) }
        frames(2)
        assertEquals(listOf(listOf(uri)), recorder.picked)
        tap(plus)
        rule.runOnUiThread { registry.dispatchResult(launches[1].code, null) } // the picker cancelled
        frames(2)
        assertEquals(1, recorder.picked.size)
    }

    @Test
    fun tenAttachedShowsLimit() {
        host(state(ready(10)))
        tap(plus)
        assertEquals(0, launches.size)
        assertEquals(1, recorder.limits)
        ui = state(ready(12)) // more than ten after a Put back: no picker either
        frames(3)
        tap(plus)
        assertEquals(0, launches.size)
        assertEquals(2, recorder.limits)
    }

    /** The guard of the approval answers is marked when the picker opens, like an in-app window over the chat. */
    @Test
    fun pickerLaunchMarksApprovalGuard() {
        host(state())
        assertEquals(Long.MIN_VALUE, overlays.openedAt)
        answerClock = 5_000L
        tap(plus)
        assertEquals(1, launches.size)
        assertEquals(5_000L, overlays.openedAt)
        answerClock = 6_000L
        rule.runOnUiThread { registry.dispatchResult(launches[0].code, listOf(Uri.parse("content://picker/1"))) }
        frames(2)
        assertEquals("a result marks nothing", 5_000L, overlays.openedAt)
    }

    /** A launch that throws (no picker on the device) does nothing: no crash, no mark of the approval guard. */
    @Test
    fun aFailedLaunchDoesNothing() {
        launchFails = true
        host(state())
        answerClock = 5_000L
        tap(plus)
        assertEquals(0, launches.size)
        assertEquals(Long.MIN_VALUE, overlays.openedAt)
        ui = state(ready(9)) // the single picker
        frames(3)
        tap(plus)
        assertEquals(Long.MIN_VALUE, overlays.openedAt)
        launchFails = false
        tap(plus)
        assertEquals(5_000L, overlays.openedAt)
    }

    @Test
    fun plusUnderTheDrawerDoesNothing() {
        host(state())
        covered = true
        tap(plus)
        assertEquals(0, launches.size)
        assertEquals(Long.MIN_VALUE, overlays.openedAt)
        ui = state(ready(10))
        frames(3)
        tap(plus)
        assertEquals(0, recorder.limits)
        covered = false
        ui = state()
        frames(3)
        tap(plus)
        assertEquals(1, launches.size)
    }

    @Test
    fun plusDisabledWhileSendingOrWithoutAChat() {
        host(state())
        rule.onNode(plus, useUnmergedTree = true).assertIsEnabled()
        ui = state(mode = ComposerMode.SEND, draft = "hi").copy(sending = true)
        frames(3)
        rule.onNode(plus, useUnmergedTree = true).assertIsNotEnabled()
        ui = state().copy(sessionKey = "") // the placeholder chat AppNav shows with no chat selected
        frames(3)
        rule.onNode(plus, useUnmergedTree = true).assertIsNotEnabled()
        ui = state().copy(status = StatusLine.LOAD_FAILED)
        frames(3)
        rule.onNode(plus, useUnmergedTree = true).assertIsNotEnabled()
    }

    private val uris = listOf(Uri.parse("content://picker/1"), Uri.parse("content://picker/2"))

    /** On the same screen, the result goes to the actions passed when it arrives, not to those passed at the launch. */
    @Test
    fun pickerResultGoesToTheActionsCurrentOnDelivery() {
        host(state())
        val old = recorder
        tap(plus)
        val next = Recorder()
        actions = next
        frames(3)
        rule.runOnUiThread { registry.dispatchResult(launches.single().code, uris) }
        frames(2)
        assertEquals(emptyList<List<Uri>>(), old.picked)
        assertEquals(listOf(uris), next.picked)
    }

    /** Another chat is another screen (keyed, as AppNav does): a result for the left chat's launch reaches neither. */
    @Test
    fun pickerResultForALeftChatReachesNoOne() {
        host(state())
        val a = recorder
        tap(plus)
        val b = Recorder()
        actions = b
        ui = state().copy(sessionKey = "k2")
        frames(5)
        rule.runOnUiThread { registry.dispatchResult(launches.single().code, uris) }
        frames(2)
        assertEquals(emptyList<List<Uri>>(), a.picked)
        assertEquals(emptyList<List<Uri>>(), b.picked)
    }

    /** The screen rebuilt from saved state registers the same key again: the restored actions get the result, once. */
    @Test
    fun pickerResultReachesTheRestoredScreenOnce() {
        val restoration = StateRestorationTester(rule)
        host(state(), restoration = restoration)
        val before = recorder
        tap(plus)
        val restored = Recorder()
        actions = restored
        restoration.emulateSavedInstanceStateRestore()
        frames(5)
        rule.runOnUiThread { registry.dispatchResult(launches.single().code, uris) }
        frames(2)
        assertEquals(emptyList<List<Uri>>(), before.picked)
        assertEquals(listOf(uris), restored.picked)
    }

    @Test
    fun pickerResultDoesNotScroll() {
        host(state(), index = 10)
        tap(plus)
        rule.runOnUiThread { registry.dispatchResult(launches.single().code, listOf(Uri.parse("content://picker/1"))) }
        ui = state(listOf(DraftImage(1, null))) // the processing thumb the result adds
        frames(10)
        assertEquals(1, recorder.picked.size)
        assertEquals(10, list.firstVisibleItemIndex)
    }

    @Test
    fun refusedSendDoesNotScroll() {
        host(state(mode = ComposerMode.SEND, draft = "hello"), index = 10)
        tap(hasTestTag(SEND_TAG))
        assertEquals(1, recorder.sends)
        frames(10)
        assertEquals("taken by the holder but never stored: the reader stays", 10, list.firstVisibleItemIndex)
    }

    @Test
    fun imageCompletionDoesNotScroll() {
        host(state(listOf(DraftImage(1, null)), draft = "look"), index = 10)
        ui = state(ready(1), ComposerMode.SEND, "look")
        frames(10)
        assertEquals(10, list.firstVisibleItemIndex)
        ui = state(listOf(DraftImage(1, null, failed = true)), ComposerMode.SEND, "look")
        frames(10)
        assertEquals(10, list.firstVisibleItemIndex)
    }

    @Test
    fun storedSendPinsReader() {
        host(state(), index = 10)
        ui = ui.copy(storedSends = 1, storedKey = "q1", items = history + sent) // as the view model publishes: count, key, row
        frames(10)
        assertEquals(0, list.firstVisibleItemIndex)
        assertEquals(0, list.firstVisibleItemScrollOffset)
        // Scrolled up again: a recomposition with the same count (typing) pins nothing.
        rule.runOnUiThread { scope.launch { list.scrollToItem(10) } }
        frames(5)
        ui = ui.copy(draft = "next", composer = ComposerMode.SEND)
        frames(10)
        assertEquals(10, list.firstVisibleItemIndex)
    }

    private val sent = UserMessage("new", "And these photos", steered = false, timestamp = FIXED_NOW, state = SendState.QUEUED, idempotencyKey = "q1", images = listOf(digest(1)))

    /** The new row rests whole at the bottom: the first visible item, at offset 0. */
    private fun assertNewRowShown(case: String) {
        val first = list.layoutInfo.visibleItemsInfo.first()
        assertEquals("$case: the bottom item", "new", first.key)
        assertEquals("$case: index", 0, list.firstVisibleItemIndex)
        assertEquals("$case: offset", 0, list.firstVisibleItemScrollOffset)
    }

    /** The stored event and the transcript row reach the screen in either order, a frame apart: the new row ends shown. */
    @Test
    fun storedSendShowsTheNewRowWhenTheCounterComesFirst() {
        host(state(), index = 10)
        ui = ui.copy(storedSends = 1, storedKey = "q1")
        frames(1)
        ui = ui.copy(items = history + sent)
        frames(10)
        assertNewRowShown("counter first")
    }

    @Test
    fun storedSendShowsTheNewRowWhenTheRowComesFirst() {
        host(state(), index = 10)
        ui = ui.copy(items = history + sent)
        frames(1)
        ui = ui.copy(storedSends = 1, storedKey = "q1")
        frames(10)
        assertNewRowShown("row first")
    }

    /** Another pending row reaching the transcript does not satisfy a pin that waits for A; A arriving does. */
    @Test
    fun anotherPendingRowDoesNotSatisfyThePin() {
        host(state(), index = 10)
        ui = ui.copy(storedSends = 1, storedKey = "q1")
        frames(3)
        val other = UserMessage("other", "Retried", steered = false, timestamp = FIXED_NOW, state = SendState.QUEUED, idempotencyKey = "r1")
        ui = ui.copy(items = history + other)
        frames(10)
        assertTrue("another row: the reader stays up (${list.firstVisibleItemIndex})", list.firstVisibleItemIndex >= 10)
        ui = ui.copy(items = history + other + sent)
        frames(10)
        assertNewRowShown("then A")
    }

    /** A screen that starts with a count above zero pins nothing for it; the next stored send pins. */
    @Test
    fun counterAboveZeroPinsOnlyOnChange() {
        host(state().copy(storedSends = 3, storedKey = "q0"), index = 10)
        frames(10)
        assertEquals(10, list.firstVisibleItemIndex)
        ui = ui.copy(storedSends = 4, storedKey = "q1", items = history + sent)
        frames(10)
        assertEquals(0, list.firstVisibleItemIndex)
    }

    /** A press on the secondary Stop begun while run A was drawn is cancelled when run B is drawn; a fresh press stops B. */
    @Test
    fun secondaryStopPressDoesNotCrossRuns() {
        val a = LiveReply("run_a", "running", "Working", emptyList(), null, Connection.LIVE)
        host(state(ready(1), ComposerMode.SEND_AFTER, live = a))
        val at = rule.onNode(hasTestTag(STOP_TAG), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        rule.onRoot().performTouchInput { down(at) }
        frames(2)
        ui = state(ready(1), ComposerMode.SEND_AFTER, live = a.copy(runId = "run_b"))
        frames(3)
        rule.onRoot().performTouchInput { up() }
        frames(3)
        assertEquals("a press begun on run A stops nothing", emptyList<String>(), recorder.stops)
        tap(hasTestTag(STOP_TAG))
        assertEquals(listOf("run_b"), recorder.stops)
        assertEquals(0, recorder.sends)
    }

    @Test
    fun sendAfterDisabledWhileSending() {
        val live = LiveReply("run_1", "running", "Working", emptyList(), null, Connection.LIVE)
        host(state(ready(1), ComposerMode.SEND_AFTER, live = live).copy(sending = true))
        rule.onNode(hasTestTag(SEND_TAG), useUnmergedTree = true).assertIsNotEnabled()
        rule.onNode(hasTestTag(STOP_TAG), useUnmergedTree = true).assertIsEnabled()
    }

    /** A press begun on Steer and released once the button shows Send after (same run) sends nothing. */
    @Test
    fun pressBegunOnSteerSendsNothingOnSendAfter() {
        val live = LiveReply("run_1", "running", "Working", emptyList(), null, Connection.LIVE)
        host(state(mode = ComposerMode.STEER, draft = "and this", live = live))
        val at = rule.onNode(hasTestTag(SEND_TAG), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.center
        rule.onRoot().performTouchInput { down(at) }
        frames(2)
        ui = state(ready(1), ComposerMode.SEND_AFTER, "and this", live)
        frames(3)
        rule.onRoot().performTouchInput { up() }
        frames(3)
        assertEquals(0, recorder.sends)
        tap(hasTestTag(SEND_TAG))
        assertEquals(1, recorder.sends)
    }

    @Test
    fun removeUnderTheDrawerDoesNothing() {
        host(state(ready(2), ComposerMode.SEND))
        covered = true
        rule.onAllNodes(remove, useUnmergedTree = true)[0].performClick()
        frames(3)
        assertEquals(emptyList<Long>(), recorder.removed)
        covered = false
        rule.onAllNodes(remove, useUnmergedTree = true)[0].performClick()
        frames(3)
        assertEquals(listOf(1L), recorder.removed)
    }

    /** Thirty photos after Put backs: only the tiles on screen are composed (and decoded); the last can be reached and removed. */
    @Test
    fun thirtyPhotosStripIsLazy() {
        cache = thumbs(30)
        host(state(ready(30), ComposerMode.SEND))
        val composed = count(remove)
        assertTrue("only the tiles on screen: $composed", composed in 1..6)
        assertEquals(0, count(hasContentDescription("Image 30 of 30")))
        rule.onNode(hasScrollToIndexAction() and hasAnyDescendant(remove), useUnmergedTree = true).performScrollToIndex(29)
        frames(5)
        rule.onNode(hasContentDescription("Image 30 of 30"), useUnmergedTree = true).assertExists()
        val last = rule.onAllNodes(remove, useUnmergedTree = true).fetchSemanticsNodes().maxBy { it.boundsInRoot.left }
        rule.onNode(SemanticsMatcher("node ${last.id}") { it.id == last.id }, useUnmergedTree = true).performClick()
        frames(3)
        assertEquals(listOf(30L), recorder.removed)
    }

    @Test
    fun removeControlDisabledWhileSending() {
        host(state(ready(2), ComposerMode.SEND, "two"))
        assertEquals(2, count(remove))
        for (node in rule.onAllNodes(remove, useUnmergedTree = true).fetchSemanticsNodes()) {
            assertTrue("a 48 dp target", node.size.width >= 96 && node.size.height >= 96)
        }
        rule.onAllNodes(remove, useUnmergedTree = true)[1].performClick()
        frames(3)
        assertEquals(listOf(2L), recorder.removed)
        ui = ui.copy(sending = true)
        frames(3)
        rule.onAllNodes(remove, useUnmergedTree = true)[0].assertIsNotEnabled()
        rule.onAllNodes(remove, useUnmergedTree = true)[1].assertIsNotEnabled()
    }

    @Test
    fun stripShowsEveryState() {
        cache = thumbs(1)
        host(state(listOf(DraftImage(1, digest(1)), DraftImage(2, null), DraftImage(3, null, failed = true)), ComposerMode.SEND_DISABLED, "x"))
        assertEquals(1, count(hasContentDescription("Image 1 of 3")))
        assertEquals(1, count(hasContentDescription("Preparing image")))
        assertEquals(1, count(hasContentDescription("Image unavailable")))
        assertEquals("only the ready one opens", 0, count(hasContentDescription("Image unavailable") and hasClickAction()))
        assertEquals(3, count(remove))
    }

    /** A ready thumbnail opens its own photo: the store holds only photo 2's file, and the viewer shows a picture. */
    @Test
    fun readyThumbOpensViewer() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        store = ImageStore(File(app.filesDir, "strip-${System.nanoTime()}"), System::currentTimeMillis)
        val two = runBlocking { store!!.put(jpeg(picture(64, 48)))!! }
        host(state(listOf(DraftImage(1, digest(1)), DraftImage(2, two)), ComposerMode.SEND))
        val tile = hasContentDescription("Image 2 of 2") and hasClickAction()
        rule.waitUntil(10_000) { frames(1); count(tile) == 1 } // decoded from the store
        tap(tile)
        rule.waitUntil(10_000) { frames(1); count(hasContentDescription("Image") and hasAnyAncestor(isDialog())) == 1 }
        assertEquals(0, count(hasText("Image could not be shown")))
        assertEquals(emptyList<Long>(), recorder.removed)
    }

    /** Each kind set through the holder's setNote, drawn above the composer with its sentence and argument. */
    @Test
    fun everyNoteKindRendered() {
        val holder = Composer(CoroutineScope(Dispatchers.Unconfined))
        val expected = mapOf(
            NoteKind.REMOVED_MAY_HAVE_SENT to R.string.chat_note_removed_may_have_sent,
            NoteKind.CANCEL_TOO_LATE to R.string.chat_note_cancel_too_late,
            NoteKind.IMAGE_FAILED to R.string.chat_image_failed,
            NoteKind.IMAGES_LIMIT to R.string.chat_images_limit,
            NoteKind.IMAGES_TOO_LARGE to R.string.chat_images_too_large,
            NoteKind.SHARE_TOO_MANY to R.string.share_too_many,
            NoteKind.SHARE_IMAGES_FAILED to R.string.share_images_failed,
            NoteKind.SHARE_TEXT_CUT to R.string.share_text_cut,
            NoteKind.CMD_UNKNOWN to R.string.cmd_unknown,
            NoteKind.CMD_NO_TEXT to R.string.cmd_no_text,
            NoteKind.CMD_NOTHING_RUNNING to R.string.cmd_nothing_running,
            NoteKind.CMD_NEEDS_TEXT to R.string.cmd_needs_text,
            NoteKind.CMD_STEER_NO_IMAGES to R.string.cmd_steer_no_images,
            NoteKind.CMD_TITLE_REFUSED to R.string.cmd_title_refused,
            NoteKind.FAST_ON to R.string.fast_on,
            NoteKind.FAST_OFF to R.string.fast_off,
            NoteKind.TEMPORARY_NOT_DELETED to R.string.chat_temporary_not_deleted,
        )
        assertEquals(NoteKind.entries.toSet(), expected.keys)
        host(state())
        for ((kind, text) in expected) {
            val arg = if (kind == NoteKind.CMD_UNKNOWN || kind == NoteKind.CMD_NO_TEXT) "/frob" else null
            holder.setNote("k", ComposerNote(kind, arg))
            ui = state().copy(composerNote = holder.current("k").note)
            frames(3)
            val sentence = if (arg != null) rule.activity.getString(text, arg) else rule.activity.getString(text)
            assertEquals("$kind", 1, count(hasText(sentence)))
        }
    }

    @Test
    fun imageOnlyDraftDuringRunIsSendAfter() {
        val live = LiveReply("run_1", "running", "Working", emptyList(), null, Connection.LIVE)
        host(state(ready(1), ComposerMode.SEND_AFTER, live = live))
        rule.onNode(hasTestTag(SEND_TAG) and hasContentDescription("Send after the current reply"), useUnmergedTree = true).assertIsEnabled()
        assertEquals(0, count(hasContentDescription("Send to the running reply")))
    }

    @Test
    fun stopShownBesideSendAfter() {
        val live = LiveReply("run_1", "running", "Working", emptyList(), null, Connection.LIVE)
        host(state(ready(1), ComposerMode.SEND_AFTER, "and this", live))
        tap(hasTestTag(STOP_TAG))
        assertEquals(listOf("run_1"), recorder.stops)
        assertEquals(0, recorder.sends)
        tap(hasTestTag(SEND_TAG))
        assertEquals(1, recorder.sends)
        assertEquals(1, recorder.stops.size)
    }

    /** Streamed tokens never recompose the composer with its photos; a finishing photo never recomposes the transcript. */
    @Test
    fun chromeStableWithDraftImages() {
        val live = LiveReply("run_1", "running", "Work", emptyList(), null, Connection.LIVE)
        host(state(listOf(DraftImage(1, null), DraftImage(2, digest(2))), ComposerMode.STOP, live = live))
        frames(5)
        val bottom = ChromeProbe.bottomCompositions
        val body = ChromeProbe.bodyCompositions
        for (t in 1..5) {
            ui = ui.copy(live = live.copy(text = "Work" + " more".repeat(t)), items = withLive(history, live.copy(text = "Work" + " more".repeat(t))) { FIXED_NOW })
            frames(2)
        }
        assertEquals("the composer with its strip", bottom, ChromeProbe.bottomCompositions)
        val bodyAfterTokens = ChromeProbe.bodyCompositions
        assertTrue(bodyAfterTokens > body)
        ui = ui.copy(images = listOf(DraftImage(1, digest(1)), DraftImage(2, digest(2))))
        frames(5)
        assertEquals("the transcript", bodyAfterTokens, ChromeProbe.bodyCompositions)
        assertTrue(ChromeProbe.bottomCompositions > bottom)
    }

    /**
     * A 480 dp window with the keyboard and two draft photos: the top bar stays whole, the field and Send keep 48 dp, and
     * the strip is the piece that gave way (to nothing); with the keyboard closed it is back.
     */
    @Test
    @Config(qualifiers = "w360dp-h480dp-xhdpi")
    fun draftStripFitsShortWindowWithIme() {
        host(state(ready(2), ComposerMode.SEND, "two"))
        insets(top = 48)
        assertEquals("roomy: the strip is shown", 2, count(remove))
        fun height(tag: String) = rule.onNode(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.height
        val oneRow = height(FIELD_TAG) // the field's one-line height in a roomy window
        insets(top = 48, ime = 400) // 24 dp status, 200 dp keyboard: 256 dp of safe band
        val bar = rule.onNode(hasTestTag("chat.topbar.row"), useUnmergedTree = true).fetchSemanticsNode()
        assertTrue("the bar is placed whole: ${bar.boundsInRoot}", bar.layoutInfo.isPlaced && bar.boundsInRoot.top >= 48f)
        assertEquals("the field keeps its row", oneRow, height(FIELD_TAG), 0.5f)
        assertTrue("Send keeps 48 dp", height(SEND_TAG) >= 96f - 0.5f)
        for (tag in listOf(FIELD_TAG, SEND_TAG)) {
            val r = rule.onNode(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            assertTrue("$tag $r above the keyboard", r.bottom <= 960f - 400f + 0.5f)
        }
        assertEquals("the strip gave way", 0, count(remove))
        insets(top = 48, ime = 0)
        assertEquals(2, count(remove))
    }

    @Test
    fun discardDialogNamesTheImages() {
        val unsent = xyz.rycerz.apofis.chat.UnsentText(7, "look", xyz.rycerz.apofis.chat.ComposeError.NOT_STORED, listOf(digest(1)))
        host(state().copy(unsent = unsent, confirmDiscard = unsent))
        assertEquals(1, count(hasText("Its text and images will be gone for good.")))
    }
}

/** The bubble's photos, from the message's own images only. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class ImageBubbleTest {
    @get:Rule
    val rule = createComposeRule()

    private val opened = ArrayList<ViewerImage>()

    private fun bubble(message: UserMessage, cache: ThumbCache? = thumbs(3), store: ImageStore? = null) {
        rule.setContent {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                CompositionLocalProvider(LocalThumbCache provides cache, LocalImageStore provides store, LocalImageOpen provides { opened += it }) {
                    Column(Modifier.width(360.dp)) { UserBubble(message, onRetry = {}, onCancel = {}, actions = RowActions({ listOf(message) }, {})) }
                }
            }
        }
        rule.waitForIdle()
    }

    private fun count(m: SemanticsMatcher) = rule.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().size

    @Test
    fun bubbleShowsThumbs() {
        bubble(UserMessage("u1", "Two screenshots", steered = false, timestamp = FIXED_NOW, images = listOf(digest(1), digest(2))))
        assertEquals(1, count(hasContentDescription("Image 1 of 2")))
        rule.onNode(hasContentDescription("Image 2 of 2"), useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(listOf<ViewerImage>(ViewerImage.Stored(digest(2))), opened)
        assertEquals(1, count(hasText("Two screenshots")))
        assertEquals(1, count(hasContentDescription("Copy")))
    }

    @Test
    fun imageOnlyHasNoCopyRow() {
        bubble(UserMessage("u1", "", steered = false, timestamp = FIXED_NOW, images = listOf(digest(1))))
        assertEquals(1, count(hasContentDescription("Image 1 of 1")))
        assertEquals(0, count(hasContentDescription("Copy")))
        assertEquals(0, count(hasContentDescription("Select text")))
        assertEquals("no empty text bubble", 0, count(SemanticsMatcher("a text node") { it.config.getOrNull(SemanticsProperties.Text) != null }))
    }

    @Test
    fun queuedThumbsStayLabelled() {
        bubble(UserMessage("u1", "later", steered = false, timestamp = FIXED_NOW, state = SendState.QUEUED, idempotencyKey = "q1", images = listOf(digest(1))))
        assertEquals(1, count(hasContentDescription("Image 1 of 1")))
        assertEquals(1, count(hasText("Waiting to send")))
    }

    /** Queued photos are dimmed: the same pixel of the same photo differs from the sent message's. */
    @Test
    fun queuedPhotosAreDimmed() {
        var message by mutableStateOf(UserMessage("u1", "", steered = false, timestamp = FIXED_NOW, images = listOf(digest(1))))
        rule.setContent {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                CompositionLocalProvider(LocalThumbCache provides thumbs(1)) {
                    Column(Modifier.width(360.dp)) { UserBubble(message, onRetry = {}, onCancel = {}, actions = RowActions({ listOf(message) }, {})) }
                }
            }
        }
        rule.waitForIdle()
        fun pixel(): androidx.compose.ui.graphics.Color {
            val map = rule.onNode(hasContentDescription("Image 1 of 1"), useUnmergedTree = true).captureToImage().toPixelMap()
            return map[map.width * 3 / 4, map.height / 4] // inside the red quarter, away from the diagonal
        }
        val sent = pixel()
        message = message.copy(state = SendState.QUEUED, idempotencyKey = "q1")
        rule.waitForIdle()
        val queued = pixel()
        assertTrue("queued $queued against sent $sent", queued != sent && queued.green > sent.green)
    }

    /** A photo-only message that failed keeps Retry and Remove; one that is queued keeps Cancel (R12). */
    @Test
    fun photoOnlyUnsentRowsKeepTheirControls() {
        var message by mutableStateOf(UserMessage("u1", "", steered = false, timestamp = FIXED_NOW, state = SendState.FAILED, idempotencyKey = "f1", images = listOf(digest(1))))
        rule.setContent {
            ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                CompositionLocalProvider(LocalThumbCache provides thumbs(1)) {
                    Column(Modifier.width(360.dp)) { UserBubble(message, onRetry = {}, onCancel = {}, actions = RowActions({ listOf(message) }, {})) }
                }
            }
        }
        rule.waitForIdle()
        assertEquals(1, rule.onAllNodes(hasText("Retry") and hasClickAction()).fetchSemanticsNodes().size) // merged: the text is the button's
        assertEquals(1, count(hasContentDescription("Remove message") and hasClickAction()))
        message = message.copy(state = SendState.QUEUED, idempotencyKey = "q1")
        rule.waitForIdle()
        assertEquals(1, count(hasContentDescription("Cancel sending") and hasClickAction()))
    }

    /** A photo with a caption of only line breaks has no text box, so no Show more or Show less either. */
    @Test
    fun blankCaptionHasNoExpansionControls() {
        bubble(UserMessage("u1", "\n".repeat(12), steered = false, timestamp = FIXED_NOW, images = listOf(digest(1))))
        assertEquals(1, count(hasContentDescription("Image 1 of 1")))
        assertEquals(0, count(hasText("Show more")))
        assertEquals(0, count(hasText("Show less")))
    }

    /** An unbound row shows the placeholder, also when the store holds a photo whose digest starts with the server's hint. */
    @Test
    fun placeholderForUnboundRow() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val store = ImageStore(File(app.filesDir, "bubble-images"), System::currentTimeMillis)
        val stored = runBlocking { store.put(jpeg(picture(64, 48)))!! }
        val text = "See\n\n[Image attached at: /home/h/.hermes/cache/native-inputs/api_${stored.take(32)}.jpg]\n[screenshot]"
        bubble(UserMessage("u1", "See", steered = false, timestamp = FIXED_NOW, images = listOf(null), raw = text), cache = ThumbCache(), store = store)
        assertEquals(1, count(hasContentDescription("Image unavailable")))
        assertEquals(0, count(hasContentDescription("Image 1 of 1")))
        rule.onNode(hasContentDescription("Image unavailable"), useUnmergedTree = true).performClick()
        rule.waitForIdle()
        assertEquals(emptyList<ViewerImage>(), opened)
    }
}

private const val KEY = "apofis-a"
private const val RUN = "run_1"

/** The view model's side: the picker result, the note, the mode with photos, the image send and the stored counter. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageSendViewModelTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private lateinit var server: MockWebServer
    private lateinit var db: AppDatabase
    private lateinit var connected: Connected
    private lateinit var follower: RunFollower
    private lateinit var store: ImageStore
    private lateinit var api: HermesApi
    private lateinit var queue: SendQueue
    private lateinit var repo: ChatRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val routes = ConcurrentHashMap<String, (RecordedRequest) -> MockResponse>()
    private val requests = java.util.concurrent.CopyOnWriteArrayList<RecordedRequest>()
    private val vms = ViewModelStore()
    private val created = ArrayList<ChatViewModel>()
    private val release = CountDownLatch(1) // opened by the test only (in tearDown at the latest)
    private val watchdog = java.util.concurrent.atomic.AtomicInteger() // held streams whose wait ran out: each fails the test

    @Volatile private var convert: suspend (Uri) -> Encoded = { Encoded.Ok(jpeg(picture(64, 48))) }

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                requests += request
                return routes[request.target.substringBefore('?')]?.invoke(request) ?: MockResponse.Builder().code(500).build()
            }
        }
        server.start()
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        store = ImageStore(File(app.filesDir, "vm-images-${System.nanoTime()}"), System::currentTimeMillis)
        api = HermesApi(server.url("/").toString(), "secret-key")
        lateinit var f: RunFollower
        queue = SendQueue(api, db, { s, r -> f.onRunStarted(s, r) }, System::currentTimeMillis, readImage = store::read, imageSize = store::size)
        repo = ChatRepository(api, db, System::currentTimeMillis, queue)
        f = RunFollower(api, db, repo, scope, delayMs = { delay(minOf(it, 20L)) })
        follower = f
        connected = Connected(
            Credentials(server.url("/").toString(), "secret-key"), api, db, repo, queue, f, AtomicBoolean(true), scope,
            Composer(scope), 0, store, { convert(it) },
        )
    }

    @After
    fun tearDown() {
        release.countDown()
        val jobs = created.map { it.viewModelScope.coroutineContext.job }
        vms.clear()
        runBlocking { withTimeout(60_000) { jobs.joinAll() } }
        follower.pauseAll()
        runBlocking { withTimeout(60_000) { scope.coroutineContext.job.cancelAndJoin() } }
        db.close()
        server.close()
        Dispatchers.resetMain()
        assertEquals("held streams whose watchdog fired", 0, watchdog.get())
    }

    private fun vm(c: Connected = connected): ChatViewModel = ViewModelProvider.create(vms, viewModelFactory { initializer { ChatViewModel(KEY, c, kotlinx.coroutines.flow.flowOf(true)) } })
        .get("vm${created.size}", ChatViewModel::class).also { created += it }

    private fun <T> wait(block: suspend () -> T): T = runBlocking { withTimeout(10_000) { block() } }

    private fun ChatViewModel.await(condition: (ChatUiState) -> Boolean): ChatUiState =
        wait { withContext(Dispatchers.Default) { state.first(condition) } }

    private fun ChatViewModel.type(text: String) = onDraftChange(text, connected.composer.current(KEY).revision)

    private fun addSession() = wait {
        db.sessionDao().upsert(
            SessionEntity(
                key = KEY, currentId = KEY, lineageIds = "", title = "Plans", pinned = false, archived = false, startedAt = 1.0,
                lastActiveAt = null, messageCount = 0, preview = null, stale = false, existsOnServer = false,
            ),
        )
    }

    /** A ready draft photo whose file is in the store. */
    private fun readyPhoto(): String {
        val d = wait { store.put(jpeg(picture(64, 48)))!! }
        connected.composer.setImage(connected.composer.addImages(KEY, 1).single(), d)
        return d
    }

    private fun json(body: String) = { _: RecordedRequest -> MockResponse.Builder().body(body).addHeader("Content-Type", "application/json").build() }

    /** A followed run whose stream stays open until the test ends. */
    private fun liveRun() = wait {
        routes["/v1/runs/$RUN"] = json(
            """{"object":"hermes.run","run_id":"$RUN","status":"running","session_id":"$KEY","admission_id":"adm_1",""" +
                """"execution_generation":3,"output":"","pending_controls":[]}""",
        )
        routes["/v1/runs/$RUN/events"] = {
            if (release.await(10, TimeUnit.SECONDS)) {
                MockResponse.Builder().body(": open\n\n").addHeader("Content-Type", "text/event-stream").build()
            } else {
                watchdog.incrementAndGet() // never a normal answer: tearDown fails the test
                MockResponse.Builder().code(500).build()
            }
        }
        follower.resumeAll()
        db.activeRunDao().upsert(ActiveRunEntity(RUN, KEY, null, "running", 1L))
        db.pendingSendDao().upsert(PendingSendEntity("p1", KEY, "hello", "{}", System.currentTimeMillis(), "sending", RUN, null, attempted = true))
        follower.onRunStarted(KEY, RUN)
    }

    /**
     * The stored event names the row this send stored, even when another pending row of the chat appears between the
     * commit and the next state (a retry of an expired row stores a fresh key with a later creation time). The view
     * model's main dispatcher is a queue the test drains, so nothing of it runs in between unless pumped.
     */
    @Test
    fun storedKeyIsTheSentRowsEvenWhenAFresherRowAppears() {
        addSession()
        val mainTasks = java.util.concurrent.LinkedBlockingQueue<Runnable>()
        Dispatchers.setMain(object : kotlinx.coroutines.CoroutineDispatcher() {
            override fun dispatch(context: kotlin.coroutines.CoroutineContext, block: Runnable) = mainTasks.put(block)
        })
        fun pumpUntil(what: String, done: () -> Boolean) {
            while (!done()) (mainTasks.poll(10, TimeUnit.SECONDS) ?: throw AssertionError("timed out waiting for $what")).run()
        }
        try {
            val vm = vm()
            connected.composer.edit(KEY, "A", connected.composer.current(KEY).revision)
            assertTrue(vm.onSendClick())
            val a = wait { db.pendingSendDao().observe(KEY).first { rows -> rows.any { it.text == "A" } } }.single { it.text == "A" }.idempotencyKey
            wait { db.pendingSendDao().upsert(PendingSendEntity("r-fresh", KEY, "retried", "{}", System.currentTimeMillis() + 60_000, "queued", null, null, attempted = false)) }
            pumpUntil("the stored event") { vm.state.value.storedSends == 1L }
            assertEquals("the sent row's key, never the other's", a, vm.state.value.storedKey)
        } finally {
            Dispatchers.setMain(UnconfinedTestDispatcher())
            while (true) (mainTasks.poll() ?: break).run()
        }
    }

    /**
     * Sending makes no read of the pending table first. The view model's own database handle has no pending table (any
     * read through it throws); the send goes through the repository's database, so the message is stored.
     */
    @Test
    fun sendingReadsNoPendingRowsFirst() {
        addSession()
        val broken = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
        broken.openHelper.writableDatabase.execSQL("DROP TABLE PendingSendEntity")
        val c = Connected(
            Credentials(server.url("/").toString(), "secret-key"), api, broken, repo, queue, follower, AtomicBoolean(true), scope,
            connected.composer, 0, store, { convert(it) },
        )
        val vm = vm(c)
        connected.composer.edit(KEY, "kept", connected.composer.current(KEY).revision)
        assertTrue(vm.onSendClick())
        val done = vm.await { !it.sending && (it.storedSends == 1L || it.unsent != null) }
        assertNull("not moved to Not stored", done.unsent)
        assertEquals(1L, done.storedSends)
        assertEquals(listOf("kept"), wait { db.pendingSendDao().rows(KEY) }.map { it.text })
        broken.close()
    }

    /** The first state after the view model is built already carries the draft's photos. */
    @Test
    fun firstStateCarriesTheDraftPhotos() {
        connected.composer.addImages(KEY, 1)
        assertEquals(1, vm().state.value.images.size)
    }

    /** The Stop beside Send after posts one stop of the drawn run and starts no run; the photo stays in the draft. */
    @Test
    fun stopBesideSendAfterPostsOneStop() {
        addSession()
        routes["/v1/runs/$RUN/stop"] = json("""{"run_id":"$RUN","status":"stopping"}""")
        val vm = vm()
        liveRun()
        val d = readyPhoto()
        assertEquals(ComposerMode.SEND_AFTER, vm.await { it.live != null && it.images.singleOrNull()?.digest == d }.composer)
        val children = { vm.viewModelScope.coroutineContext.job.children.toSet() }
        val before = children()
        vm.onStopClick(RUN)
        val action = children() - before // the request the tap started: the stop POST and the status read after it
        assertTrue(action.isNotEmpty())
        wait { action.forEach { it.join() } } // settled: only now are the requests counted
        assertEquals(1, requests.count { it.method == "POST" && it.target == "/v1/runs/$RUN/stop" })
        assertEquals(0, requests.count { it.method == "POST" && it.target.substringBefore('?') == "/v1/runs" })
        assertEquals(listOf(d), connected.composer.current(KEY).images.map { it.digest })
    }

    @Test
    fun pickedUriProcessedOffMain() {
        val vm = vm()
        var onMain: Boolean? = null
        convert = { onMain = Looper.getMainLooper().isCurrentThread; Encoded.Ok(jpeg(picture(64, 48))) }
        vm.onImagesPicked(emptyList())
        assertEquals(emptyList<DraftImage>(), connected.composer.current(KEY).images)
        vm.onImagesPicked(listOf(Uri.parse("content://picker/1")))
        val shown = vm.await { s -> s.images.size == 1 && s.images.single().digest != null }
        assertEquals(false, onMain)
        assertNotNull(store.file(shown.images.single().digest!!))
        assertNull(shown.composerNote)
    }

    @Test
    fun securityExceptionShowsNote() {
        val vm = vm()
        convert = { throw SecurityException("no grant for this uri") }
        vm.onImagesPicked(listOf(Uri.parse("content://picker/1")))
        val failed = vm.await { s -> s.images.singleOrNull()?.failed == true }
        assertEquals(ComposerNote(NoteKind.IMAGE_FAILED), failed.composerNote)
        assertEquals(ComposerMode.SEND_DISABLED, failed.composer) // a failed photo alone sends nothing
    }

    @Test
    fun removeAndLimitGoToTheHolder() {
        val vm = vm()
        readyPhoto()
        val id = vm.await { it.images.size == 1 }.images.single().id
        vm.onImagesLimit()
        assertEquals(ComposerNote(NoteKind.IMAGES_LIMIT), vm.await { it.composerNote != null }.composerNote)
        vm.onRemoveImage(id)
        vm.await { it.images.isEmpty() }
        vm.type("x") // a keystroke clears the note, whatever its kind
        assertNull(vm.await { it.draft == "x" }.composerNote)
        assertNull(connected.composer.current(KEY).note)
    }

    @Test
    fun theSendGoesThroughTheImageEntry() {
        addSession()
        val vm = vm()
        val d = readyPhoto()
        vm.await { it.composer == ComposerMode.SEND } // a ready photo alone makes the draft sendable
        connected.composer.setNote(KEY, ComposerNote(NoteKind.IMAGE_FAILED))
        assertTrue(vm.onSendClick())
        val done = vm.await { !it.sending && it.images.isEmpty() && it.storedSends == 1L }
        assertNull("the claim cleared the note", done.composerNote)
        val row = wait { db.pendingSendDao().rows(KEY) }.single()
        assertEquals(d, row.images)
    }

    @Test
    fun storedCounterCountsOnlyStoredRows() {
        val vm = vm() // no session row: the store refuses before storing anything
        vm.type("lost")
        assertTrue(vm.onSendClick())
        vm.await { it.unsent != null && !it.sending }
        assertEquals(0L, vm.state.value.storedSends)
        addSession()
        vm.type("kept")
        assertTrue(vm.onSendClick())
        val stored = vm.await { it.storedSends == 1L && !it.sending }
        assertEquals("the stored row's key travels with the counter", wait { db.pendingSendDao().rows(KEY) }.single().idempotencyKey, stored.storedKey)
    }

    @Test
    fun imageOnlyDraftDuringRunOffersSendNotStop() {
        addSession()
        val vm = vm()
        liveRun()
        assertEquals(ComposerMode.STOP, vm.await { it.live != null }.composer)
        val processing = connected.composer.addImages(KEY, 1).single()
        assertEquals(ComposerMode.STOP, vm.await { it.images.size == 1 }.composer) // processing: nothing to send yet, never Steer
        vm.type("and this")
        assertEquals(ComposerMode.STOP, vm.await { it.draft == "and this" }.composer)
        connected.composer.setImage(processing, null)
        assertEquals("text and only a failed photo steers", ComposerMode.STEER, vm.await { it.images.single().failed }.composer)
        connected.composer.removeImage(KEY, processing)
        vm.type("")
        vm.await { it.images.isEmpty() && it.draft.isEmpty() }
        val d = readyPhoto()
        assertEquals(ComposerMode.SEND_AFTER, vm.await { it.images.singleOrNull()?.digest == d }.composer) // the ready snapshot itself
        vm.type("caption")
        assertEquals(ComposerMode.SEND_AFTER, vm.await { it.draft == "caption" }.composer)
    }

    /**
     * A photo sent and its run followed to the end, the stand-in server answering with the user row as the real one
     * stores it for a run (kind "internal_notification"): the send is confirmed and bound, and the bubble shows the photo.
     */
    @Test
    fun photoSendConfirmsAgainstARunsUserRow() {
        addSession()
        wait { db.sessionDao().upsert(db.sessionDao().get(KEY)!!.copy(existsOnServer = true)) } // the send starts the run directly
        // Empty until the run's events are asked for: the chat's opening read can never see the turn before it is sent.
        routes["/api/sessions/$KEY/messages"] = json("""{"object":"list","session_id":"$KEY","data":[]}""")
        val vm = vm()
        val d = readyPhoto()
        vm.type("Look at this")
        vm.await { it.composer == ComposerMode.SEND && it.draft == "Look at this" }
        val content = "Look at this\n\n[Image attached at: /srv/hermes/cache/documents/native-inputs/$d/api_${d.take(32)}.jpg]\n[screenshot]"
        routes["/v1/runs"] = json("""{"run_id":"$RUN","status":"started","replayed":false}""")
        routes["/v1/runs/$RUN/events"] = {
            // The events request follows the stored send and its active run: only now the server holds the finished turn.
            val now = System.currentTimeMillis() / 1000.0 // the follower confirms a send only by a row from the last minute
            routes["/api/sessions/$KEY/messages"] = json(
                """{"object":"list","session_id":"$KEY","data":[""" +
                    """{"id":10,"role":"user","display_kind":"internal_notification","content":${kotlinx.serialization.json.JsonPrimitive(content)},"timestamp":$now},""" +
                    """{"id":11,"role":"assistant","content":"A cat.","timestamp":$now}]}""",
            )
            MockResponse.Builder().body(": open\n\nid: 0\ndata: {\"event\":\"run.completed\",\"seq\":0}\n\n")
                .addHeader("Content-Type", "text/event-stream").build()
        }
        wait { follower.resumeAll() } // as at app start: the follower follows the runs it is told of
        assertTrue(vm.onSendClick())

        // Settled: the follower attached to the run and its active row is gone (removed in the settling transaction).
        wait {
            withContext(Dispatchers.IO) {
                while (requests.none { it.target.startsWith("/v1/runs/$RUN/events") } || db.activeRunDao().get(RUN) != null) delay(10)
            }
        }
        assertEquals(emptyList<String>(), wait { db.pendingSendDao().rows(KEY) }.map { "${it.state} ${it.error}" }) // confirmed, not unconfirmed
        assertEquals(listOf(10L to d), wait { db.imageBindingDao().forSession(KEY) }.map { it.messageId to it.digests })
        // The settled screen, not an earlier one: the run is over and the one user bubble already shows its bound photo.
        val done = vm.await { s ->
            s.live == null && s.items.any { it.key == "r11" } && s.items.filterIsInstance<UserMessage>().singleOrNull()?.images == listOf<String?>(d)
        }
        val bubble = done.items.filterIsInstance<UserMessage>().single() // no pending bubble beside it, so no Retry
        assertEquals("u10", bubble.key)
        assertEquals("Look at this", bubble.text)
        assertEquals(SendState.SENT, bubble.state)
        assertEquals(listOf<String?>(d), bubble.images)
    }

    @Test
    fun imageDraftDuringRunIsLabelledAsQueued() {
        addSession()
        val vm = vm()
        liveRun()
        vm.await { it.live != null }
        val d = readyPhoto()
        vm.type("after this")
        vm.await { it.composer == ComposerMode.SEND_AFTER && it.draft == "after this" }
        assertTrue(vm.onSendClick())
        val sent = vm.await { s -> s.items.any { it is UserMessage && it.text == "after this" } }
        val row = sent.items.filterIsInstance<UserMessage>().single { it.text == "after this" }
        assertEquals(SendState.QUEUED, row.state)
        assertFalse(row.steered)
        assertEquals(listOf<String?>(d), row.images)
        assertTrue(requests.none { it.target.contains("/steer") })
    }
}

/**
 * A confirmed photo after a restart: the row and its binding are stored, the database is closed, and a fresh graph over
 * the reopened file shows the row in the chat with a decoded, clickable tile for that photo.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class ImageRestartTest {
    @get:Rule
    val rule = createComposeRule()

    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun confirmedImageShowsThumbAfterRestart() {
        val name = "restart-${System.nanoTime()}.db"
        val dir = File(app.filesDir, "restart-images-${System.nanoTime()}")
        try {
            val d = runBlocking { ImageStore(dir, System::currentTimeMillis).put(jpeg(picture(64, 48)))!! }
            // The first life: the confirmed row and its binding are stored, then the database closes.
            val before = Room.databaseBuilder(app, AppDatabase::class.java, name).build()
            runBlocking {
                before.sessionDao().upsert(
                    SessionEntity(
                        key = KEY, currentId = KEY, lineageIds = "", title = "Plans", pinned = false, archived = false, startedAt = 1.0,
                        lastActiveAt = null, messageCount = 1, preview = null, stale = false, existsOnServer = true,
                    ),
                )
                val content = "Look\n\n[Image attached at: /home/h/.hermes/cache/native-inputs/api_${d.take(32)}.jpg]\n[screenshot]"
                before.messageDao().insertAll(listOf(MessageEntity(KEY, 0, 7, "user", "internal_notification", content, null, null, null, null, FIXED_NOW, null)))
                before.imageBindingDao().upsert(ImageBindingEntity(KEY, 7, d))
            }
            before.close()

            // The second life: the file reopened under a fresh graph, store object and thumbnail cache.
            val db = Room.databaseBuilder(app, AppDatabase::class.java, name).build()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
            val server = MockWebServer()
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse.Builder().code(500).build()
            }
            server.start()
            val vms = ViewModelStore()
            lateinit var follower: RunFollower
            try {
                val api = HermesApi(server.url("/").toString(), "secret-key")
                val queue = SendQueue(api, db, { s, r -> follower.onRunStarted(s, r) }, System::currentTimeMillis)
                val repo = ChatRepository(api, db, System::currentTimeMillis, queue)
                follower = RunFollower(api, db, repo, scope)
                val connected = Connected(Credentials(server.url("/").toString(), "secret-key"), api, db, repo, queue, follower, AtomicBoolean(true), scope)
                val vm = ViewModelProvider.create(vms, viewModelFactory { initializer { ChatViewModel(KEY, connected, kotlinx.coroutines.flow.flowOf(true)) } })[ChatViewModel::class]
                val cache = ThumbCache()
                val store = ImageStore(dir, System::currentTimeMillis)
                rule.setContent {
                    ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false) {
                        CompositionLocalProvider(LocalImageStore provides store, LocalThumbCache provides cache) {
                            val state by vm.state.collectAsState()
                            ChatScreen(state, vm, onOpenConnection = {}, zone = ZoneOffset.UTC, today = FIXED_TODAY)
                        }
                    }
                }
                val tile = hasContentDescription("Image 1 of 1") and hasClickAction()
                rule.waitUntil(10_000) { rule.onAllNodes(tile, useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
                assertTrue("decoded into the fresh cache", cache.get(d) is Decoded.Ok)
                rule.onNode(tile, useUnmergedTree = true).performClick()
                rule.waitUntil(10_000) { rule.onAllNodes(hasContentDescription("Image") and hasAnyAncestor(isDialog()), useUnmergedTree = true).fetchSemanticsNodes().size == 1 }
            } finally {
                vms.clear()
                follower.pauseAll()
                runBlocking { withTimeout(60_000) { scope.coroutineContext.job.cancelAndJoin() } }
                db.close()
                server.close()
            }
        } finally {
            app.deleteDatabase(name)
            dir.deleteRecursively()
        }
    }
}

/** The two locals MainActivity provides: the thumbnail cache is the current connection's, a new one after a key change. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageLocalsTest {
    @get:Rule
    val rule = createEmptyComposeRule() // no activity of its own: its waitUntil is the watchdog over the one built below

    private val app = ApplicationProvider.getApplicationContext<ApofisApp>()
    private val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
    private val key = javax.crypto.KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    /** Runs what the paused main looper holds (the activity's composition, the drawer's work); waitUntil does not. */
    private fun idle() = org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun draftThumbDecodedIntoTheConnectionsCache() {
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
        val server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse.Builder().code(500).build()
        }
        server.start()
        // The app's own container needs the Android keystore, which Robolectric lacks: this one takes its place, and the
        // original goes back whatever happens.
        val delegate = ApofisApp::class.java.getDeclaredField("container\$delegate").apply { isAccessible = true }
        val original = delegate.get(app)
        val container = AppContainer(app, CredentialStore(app) { key }) { db }
        var controller: org.robolectric.android.controller.ActivityController<MainActivity>? = null
        try {
            delegate.set(app, lazyOf(container))
            runBlocking { container.connect(Credentials(server.url("/").toString(), "key-1")) }
            controller = Robolectric.buildActivity(MainActivity::class.java).setup()
            val first = container.connected()!!
            // The chat the drawer opens: with none stored, the new local chat it creates (read from Room).
            var opened: String? = null
            rule.waitUntil(10_000) { idle(); opened = runBlocking { first.repo.sessions().first() }.firstOrNull()?.key; opened != null }
            val d = runBlocking { container.imageStore.put(jpeg(picture(64, 48)))!! }
            container.composer.setImage(container.composer.addImages(opened!!, 1).single(), d)
            rule.waitUntil(10_000) { idle(); first.thumbs.get(d) is Decoded.Ok }

            runBlocking { container.connect(Credentials(server.url("/").toString(), "key-2")) }
            val second = container.connected()!!
            assertNotSame(first.thumbs, second.thumbs)
            rule.waitUntil(10_000) { idle(); second.thumbs.get(d) is Decoded.Ok }
        } finally {
            controller?.pause()?.stop()?.destroy()
            runBlocking { withTimeout(60_000) { container.scope.coroutineContext.job.cancelAndJoin() } } // before its database and server close
            delegate.set(app, original)
            db.close()
            server.close()
        }
    }
}

/** The three goldens of this task: the composer with draft photos, a message with photos, a message of photos only. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class ImageSendScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    @Test
    fun composer_draft_images() = rule.shot("composer_draft_images") {
        CompositionLocalProvider(LocalThumbCache provides thumbs(2)) {
            Box(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                Text(
                    "The composer floats over the end of the conversation, with the photos of the draft above the field.",
                    Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Composer(
                    "Why does the build fail here?", ComposerMode.SEND_DISABLED, {}, { false }, {}, Modifier.align(Alignment.BottomCenter),
                    images = listOf(DraftImage(1, digest(1)), DraftImage(4, null, failed = true), DraftImage(3, null), DraftImage(2, digest(2))), // the failed tile inside the frame
                )
            }
        }
    }

    @Composable
    private fun Bubble(message: UserMessage) {
        CompositionLocalProvider(LocalThumbCache provides thumbs(3)) {
            Column(Modifier.width(360.dp).background(MaterialTheme.colorScheme.surface)) {
                UserBubble(message, onRetry = {}, onCancel = {}, actions = RowActions({ listOf(message) }, {}))
            }
        }
    }

    @Test
    fun user_bubble_images() = rule.shot("user_bubble_images") {
        Bubble(UserMessage("u1", "Here are the three screens where it fails.", steered = false, timestamp = FIXED_NOW, images = listOf(digest(1), digest(2), digest(3))))
    }

    @Test
    fun user_images_only() = rule.shot("user_images_only") {
        Bubble(UserMessage("u1", "", steered = false, timestamp = FIXED_NOW, images = listOf(digest(1), null)))
    }
}
