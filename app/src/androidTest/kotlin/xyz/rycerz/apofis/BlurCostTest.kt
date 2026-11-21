package xyz.rycerz.apofis

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.ViewTreeObserver
import android.view.Window
import android.view.WindowInsets
import android.view.accessibility.AccessibilityNodeInfo
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.cos
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.chat.ChatItem
import xyz.rycerz.apofis.chat.UserMessage
import xyz.rycerz.apofis.run.Connection
import xyz.rycerz.apofis.run.LiveReply
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.chat.ChatActions
import xyz.rycerz.apofis.ui.chat.ChatScreen
import xyz.rycerz.apofis.ui.chat.ChatUiState
import xyz.rycerz.apofis.ui.chat.ComposerMode
import xyz.rycerz.apofis.ui.chat.LiveMerger
import xyz.rycerz.apofis.ui.chat.StatusLine
import xyz.rycerz.apofis.ui.theme.ApofisTheme

/**
 * The cost of the backdrop blur, measured on a device with hardware rendering (tools/emulator/run-e2e.sh --app-test
 * BlurCostTest). The chat shows a fixed 300 item stored head merged with a live reply through the production
 * [LiveMerger] (the incremental path), the reply grows by 40 characters every frame, and one held drag scrolls up and
 * down on a schedule counted in drawn frames. Growth and the drag start at a frame-counted measurement start. Four
 * phases of 600 frames in balanced order (on, off, off, on); screenshots are taken outside the measured window.
 * Writes blur-cost.txt, a screenshot per mode while the list moves under the glass button, and one per mode after the
 * keyboard was shown (asserted from the window insets and the composer's position) and hidden again (asserted restored).
 * No server, no key, no model run. Real time is unavoidable: it measures real frames.
 *
 * Limits, kept on purpose (blur ships off; this measures, it does not gate): the drag samples delivered frame metrics
 * and may skip frame indices; "glass seen" is the button's visibility condition, not a check that its pixels were
 * drawn; allocation counts are process-wide; the list's endpoint padding is not measured with the keyboard open (the
 * composer and the scroll button are). Software GL on the emulator is not a phone GPU.
 */
@RunWith(AndroidJUnit4::class)
class BlurCostTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val out = File(instrumentation.targetContext.getExternalFilesDir(null), "blur").apply {
        deleteRecursively()
        mkdirs()
    }

    // One fixed stored head, reused as the same list: the merger then builds only the live tail per update.
    private val head: List<ChatItem> = (1..150).flatMap {
        listOf(
            UserMessage("u$it", "Question $it: how do the garden beds drain after a long rain?", steered = false, timestamp = NOW),
            AssistantReply("r$it", emptyList(), "Answer $it. Raised beds drain better and warm up sooner in spring, so seedlings can go in earlier.", NOW),
        )
    } + UserMessage("u151", "Write a long answer", steered = false, timestamp = NOW, runId = RUN)

    private class Phase(val durations: List<Long>, val allocs: Long, val positions: Int, val glassSeen: Boolean)

    private val compositions = AtomicInteger()
    private val draws = AtomicInteger()

    @Test
    fun measure() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            val order = listOf(true, false, false, true) // balanced: drift over the run affects both modes alike
            val phases = order.mapIndexed { i, blur -> blur to phase(scenario, blur, shots = i < 2, index = i) }
            fun summary(blur: Boolean): Triple<Double, Double, Double> {
                val mine = phases.filter { it.first == blur }.map { it.second }
                val sorted = mine.flatMap { it.durations }.sorted()
                fun pct(p: Double) = if (sorted.isEmpty()) 0.0 else sorted[((sorted.size - 1) * p).toInt()] / 1e6
                val frames = sorted.size.coerceAtLeast(1)
                return Triple(pct(0.5), pct(0.95), mine.sumOf { it.allocs }.toDouble() / frames)
            }
            val (onP50, onP95, onAlloc) = summary(true)
            val (offP50, offP95, offAlloc) = summary(false)
            var hardware = false
            scenario.onActivity { hardware = it.window.decorView.isHardwareAccelerated }
            val accepted = onP95 - offP95 <= 4.0 && onAlloc - offAlloc <= 10.0
            val report = listOf(
                "hardware=$hardware",
                "blur=on frames=${phases.filter { it.first }.sumOf { it.second.durations.size }} p50_ms=%.2f p95_ms=%.2f allocs_per_frame=%.1f".format(onP50, onP95, onAlloc),
                "blur=off frames=${phases.filter { !it.first }.sumOf { it.second.durations.size }} p50_ms=%.2f p95_ms=%.2f allocs_per_frame=%.1f".format(offP50, offP95, offAlloc),
                "p95_delta_ms=%.2f allocs_delta=%.1f accepted=%s (p95 at most 4 ms worse, at most 10 more allocations per frame; order on off off on)"
                    .format(onP95 - offP95, onAlloc - offAlloc, if (accepted) "yes" else "no"),
            )
            report.forEach { Log.i(TAG, it) }
            File(out, "blur-cost.txt").writeText(report.joinToString("\n", postfix = "\n"))
            assertTrue("software rendering: blur cannot be measured", hardware)
            for ((blur, p) in phases) {
                assertTrue("blur=$blur: ${p.durations.size} frames", p.durations.size >= FRAMES)
                assertTrue("blur=$blur: the list did not move (${p.positions} positions)", p.positions >= 10)
                assertTrue("blur=$blur: the glass button was not shown during the measurement", p.glassSeen)
            }
        }
    }

    private fun current(scenario: ActivityScenario<ComponentActivity>): ComponentActivity {
        lateinit var a: ComponentActivity
        scenario.onActivity { a = it }
        return a
    }

    /** Waits until the same activity instance stayed resumed for 3 s (a configuration change after boot recreates it). */
    private fun settled(scenario: ActivityScenario<ComponentActivity>) {
        var last = current(scenario)
        var since = SystemClock.uptimeMillis()
        val until = since + 60_000
        while (SystemClock.uptimeMillis() - since < 3000 && SystemClock.uptimeMillis() < until) {
            SystemClock.sleep(200)
            val now = current(scenario)
            if (now !== last || !now.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                last = now
                since = SystemClock.uptimeMillis()
            }
        }
    }

    /** Waits, bounded, for [condition] (checked every 10 ms); fails with [what] when it does not come. */
    private fun await(what: String, timeoutMs: Long = 20_000, condition: () -> Boolean) {
        val until = SystemClock.uptimeMillis() + timeoutMs
        while (!condition()) {
            check(SystemClock.uptimeMillis() < until) { "timed out waiting for $what" }
            SystemClock.sleep(10)
        }
    }

    private fun phase(scenario: ActivityScenario<ComponentActivity>, blur: Boolean, shots: Boolean, index: Int): Phase {
        val name = if (blur) "blur-on" else "blur-off"
        val growing = mutableStateOf(false)
        val text = mutableStateOf("") // this phase's own reply text
        lateinit var list: LazyListState
        lateinit var activity: ComponentActivity
        for (attempt in 1..3) { // the current instance; set again if a configuration change recreated it meanwhile
            settled(scenario)
            val drawsBefore = draws.get()
            val listener = ViewTreeObserver.OnDrawListener { draws.incrementAndGet() }
            scenario.onActivity {
                activity = it
                it.enableEdgeToEdge() // as MainActivity: the chat itself handles the system bars and the keyboard
                @Suppress("DEPRECATION")
                it.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE) // as MainActivity's manifest entry
                it.window.decorView.viewTreeObserver.addOnDrawListener(listener) // before the content: its first frame counts
                it.setContent { key(index, attempt) { Bench(blur, growing, text) { l -> list = l } } } // a new composition per phase
            }
            runCatching { await("the first frame of $name", 10_000) { compositions.get() > 0 && draws.get() > drawsBefore } }
            instrumentation.runOnMainSync { activity.window.decorView.viewTreeObserver.removeOnDrawListener(listener) }
            if (current(scenario) === activity && activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && draws.get() > drawsBefore) break
            Log.i(TAG, "$name: no frames or the activity was recreated (attempt $attempt)")
            check(attempt < 3) { "$name: no frame of this phase's content after 3 attempts" }
        }
        // Each phase starts alike: no reply text yet, the list at the bottom.
        var start = Triple(-1, -1, -1)
        instrumentation.runOnMainSync { start = Triple(text.value.length, list.firstVisibleItemIndex, list.firstVisibleItemScrollOffset) }
        check(start == Triple(0, 0, 0)) { "$name (phase $index) did not start reset: text, index, offset = $start" }
        val view = activity.window.decorView
        val x = view.width / 2f
        val top = view.height * 0.35f
        val amplitude = view.height * 0.25f
        // A real slow drag away from the bottom releases following (no fling: the finger rests before lifting).
        Finger(x, top).apply {
            for (i in 1..30) {
                move(top + amplitude * i / 30)
                SystemClock.sleep(16)
            }
            SystemClock.sleep(150)
            up()
        }
        await("the drag to settle") { !list.isScrollInProgress }

        // Measurement start, counted in drawn frames: growth and the drag schedule begin here.
        val durations = ArrayList<Long>()
        val frames = AtomicInteger()
        val thread = HandlerThread("frames").apply { start() }
        val metrics = Window.OnFrameMetricsAvailableListener { _, m, _ ->
            synchronized(durations) { if (durations.size < FRAMES) durations += m.getMetric(FrameMetrics.TOTAL_DURATION) }
            frames.incrementAndGet()
        }
        val positions = HashSet<Pair<Int, Int>>()
        var glassSeen = false
        instrumentation.runOnMainSync {
            activity.window.addOnFrameMetricsAvailableListener(metrics, Handler(thread.looper))
            growing.value = true
        }
        @Suppress("DEPRECATION")
        Debug.startAllocCounting()
        val allocsBefore = Debug.getGlobalAllocCount()
        val finger = Finger(x, top)
        var seen = 0
        val until = SystemClock.uptimeMillis() + 180_000
        while (frames.get() < FRAMES && SystemClock.uptimeMillis() < until) {
            val f = frames.get()
            if (f == seen) {
                SystemClock.sleep(2)
                continue
            }
            seen = f
            finger.move(top + amplitude * (1 - cos(f * 2 * PI / 60)).toFloat() / 2) // one period every 60 drawn frames
            instrumentation.runOnMainSync {
                positions += list.firstVisibleItemIndex to list.firstVisibleItemScrollOffset
                if (list.canScrollBackward && list.isScrollInProgress) glassSeen = true // the button's condition
            }
        }
        val allocs = (Debug.getGlobalAllocCount() - allocsBefore).toLong()
        @Suppress("DEPRECATION")
        Debug.stopAllocCounting()
        instrumentation.runOnMainSync {
            activity.window.removeOnFrameMetricsAvailableListener(metrics)
            growing.value = false
        }
        thread.quitSafely()
        if (shots) {
            SystemClock.sleep(300) // outside the measured window; the finger still holds the list under the glass
            screenshot("$name.png")
        }
        finger.up()
        await("the list to settle") { !list.isScrollInProgress }
        if (shots) keyboard(activity, name, x, top, amplitude)
        return Phase(synchronized(durations) { durations.toList() }, allocs, positions.size, glassSeen)
    }

    /** Shows the keyboard on the real composer field, checks the resize, hides it, checks the restoration, then a screenshot. */
    private fun keyboard(activity: ComponentActivity, name: String, x: Float, top: Float, amplitude: Float) {
        val view = activity.window.decorView
        fun field(): AccessibilityNodeInfo? = instrumentation.uiAutomation.rootInActiveWindow?.let { root ->
            fun find(n: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
                if (n == null) return null
                if (n.viewIdResourceName == "composer.field") return n
                for (i in 0 until n.childCount) find(n.getChild(i))?.let { return it }
                return null
            }
            find(root)
        }
        fun fieldTop(): Int = Rect().also { r -> field()?.apply { refresh() }?.getBoundsInScreen(r) }.top // fresh, not cached
        fun imeShown(): Boolean {
            var shown = false
            instrumentation.runOnMainSync { shown = ViewCompat.getRootWindowInsets(view)?.isVisible(WindowInsetsCompat.Type.ime()) == true }
            return shown
        }
        // The emulator emulates a hardware keyboard and would show no on-screen keyboard (a 0 px inset): ask for it
        // (a setting of this throwaway AVD, deleted with it after the run).
        instrumentation.uiAutomation.executeShellCommand("settings put secure show_ime_with_hard_keyboard 1").close()
        await("the composer field") { field() != null }
        val restTop = fieldTop()
        check(field()!!.performAction(AccessibilityNodeInfo.ACTION_CLICK)) { "could not click the composer field" }
        await("the keyboard to show") { imeShown() }
        fun bottoms(): Pair<Int, Int> {
            var r = 0 to 0
            instrumentation.runOnMainSync {
                val insets = ViewCompat.getRootWindowInsets(view)!!
                r = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom to insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            }
            return r
        }
        runCatching { await("the keyboard to open (taller than the navigation bar)") { bottoms().let { it.first > it.second } } }
            .onFailure { throw IllegalStateException("the keyboard has no height: ${bottoms()} (keyboard, navigation bar px)", it) }
        // The app applies max(keyboard, navigation bar) once: the composer moves up by exactly what the keyboard adds.
        SystemClock.sleep(500) // the keyboard's opening animation ends
        val (ime, nav) = bottoms()
        val shift = maxOf(ime, nav) - nav
        Log.i(TAG, "$name: keyboard inset $ime px, navigation bar $nav px")
        runCatching { await("the composer to move up by $shift px") { kotlin.math.abs(restTop - fieldTop() - shift) <= 4 } }
            .onFailure { throw IllegalStateException("composer top ${fieldTop()} (was $restTop), expected a shift of $shift px", it) }
        Log.i(TAG, "$name: keyboard shown, composer top $restTop -> ${fieldTop()}")
        // With the keyboard open the scroll button follows the composer up: shown by a held drag, it lies above it.
        Finger(x, top).apply {
            for (i in 1..10) {
                move(top + amplitude * i / 20)
                SystemClock.sleep(16)
            }
            var button = Rect()
            await("the scroll button with the keyboard open") {
                instrumentation.uiAutomation.rootInActiveWindow?.findAccessibilityNodeInfosByText("Scroll to the latest message")?.firstOrNull()?.let {
                    it.refresh()
                    button = Rect().also(it::getBoundsInScreen)
                    true
                } ?: false
            }
            check(button.bottom <= fieldTop()) { "the scroll button $button is not above the composer (top ${fieldTop()}) with the keyboard open" }
            up()
        }
        instrumentation.runOnMainSync { activity.window.insetsController?.hide(WindowInsets.Type.ime()) }
        await("the keyboard to hide") { !imeShown() }
        await("the composer to return") { kotlin.math.abs(fieldTop() - restTop) <= 2 }
        Log.i(TAG, "$name: keyboard hidden, composer top back at ${fieldTop()}")
        Finger(x, top).apply {
            for (i in 1..20) {
                move(top + amplitude * i / 20)
                SystemClock.sleep(16)
            }
            SystemClock.sleep(300)
            screenshot("$name-keyboard.png")
            up()
        }
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Composable
    private fun Bench(blur: Boolean, growing: MutableState<Boolean>, text: MutableState<String>, onList: (LazyListState) -> Unit) {
        ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = false, blur = blur) {
            val list = rememberLazyListState()
            onList(list)
            val merger = remember { LiveMerger { NOW } }
            SideEffect { compositions.incrementAndGet() }
            LaunchedEffect(growing.value) {
                if (!growing.value) return@LaunchedEffect
                var n = 0
                while (true) {
                    withFrameNanos {}
                    // 40 characters a frame, a paragraph break about every 400.
                    text.value += if (++n % 10 == 0) "words go here\n\n" + "x".repeat(25) else "growing reply text, forty characters ok "
                }
            }
            val live = LiveReply(RUN, "running", text.value, emptyList(), null, Connection.LIVE)
            val state = ChatUiState("apofis-bench", "Blur cost", StatusLine.WORKING, merger.merge(head, live), ComposerMode.STOP, "", "emulator", live)
            Box(Modifier.semantics { testTagsAsResourceId = true }) {
                ChatScreen(state, object : ChatActions {}, onOpenConnection = {}, listState = list)
            }
        }
    }

    /** A finger on the screen: injected events, each delivered before the next. */
    private inner class Finger(private val x: Float, private var y: Float) {
        private val downTime = SystemClock.uptimeMillis()

        init {
            send(MotionEvent.ACTION_DOWN)
        }

        fun move(to: Float) {
            y = to
            send(MotionEvent.ACTION_MOVE)
        }

        fun up() = send(MotionEvent.ACTION_UP)

        private fun send(action: Int) {
            val e = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
            instrumentation.sendPointerSync(e)
            e.recycle()
        }
    }

    private fun screenshot(name: String) {
        val bitmap = instrumentation.uiAutomation.takeScreenshot() ?: return
        File(out, name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }

    private companion object {
        const val TAG = "ApofisE2e" // the harness keeps this tag's logcat lines
        const val FRAMES = 600
        const val RUN = "run_bench"
        const val NOW = 1773140400.0 // 2026-03-10T11:00Z
    }
}
