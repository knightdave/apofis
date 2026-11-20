package xyz.rycerz.apofis

import android.graphics.Bitmap
import android.os.Debug
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.FrameMetrics
import android.view.MotionEvent
import android.view.Window
import android.view.WindowInsets
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.withFrameNanos
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import android.os.Looper
import android.view.Choreographer
import android.view.ViewTreeObserver
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.key
import kotlin.math.PI
import kotlin.math.cos
import androidx.lifecycle.Lifecycle
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
import xyz.rycerz.apofis.ui.chat.StatusLine
import xyz.rycerz.apofis.ui.theme.ApofisTheme

/**
 * The cost of the backdrop blur, measured on a device with hardware rendering (tools/emulator/run-e2e.sh --app-test
 * BlurCostTest): the chat with a fixed 300 item transcript, a live reply growing by 40 characters every frame and a
 * scripted scroll up and down, for 600 frames with blur on and then off. Writes blur-cost.txt and one screenshot per
 * mode while text is behind the composer and the glass button, and one after the keyboard was shown and hidden.
 * No server, no key, no model run. Real time is unavoidable here: it measures real frames.
 */
@RunWith(AndroidJUnit4::class)
class BlurCostTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val out = File(instrumentation.targetContext.getExternalFilesDir(null), "blur").apply {
        deleteRecursively()
        mkdirs()
    }

    private val history: List<ChatItem> = (1..150).flatMap {
        listOf(
            UserMessage("u$it", "Question $it: how do the garden beds drain after a long rain?", steered = false, timestamp = NOW),
            AssistantReply("r$it", emptyList(), "Answer $it. Raised beds drain better and warm up sooner in spring, so seedlings can go in earlier.", NOW),
        )
    } // 300 items

    private data class Result(val frames: Int, val p50: Double, val p95: Double, val allocsPerFrame: Double)

    @Test
    fun measure() {
        ActivityScenario.launch(ComponentActivity::class.java).use { scenario ->
            SystemClock.sleep(3000) // a configuration change right after boot recreates the activity: let it settle
            lateinit var first: ComponentActivity
            scenario.onActivity { first = it }
            val hardware = first.window.decorView.isHardwareAccelerated
            val on = phase(scenario, blur = true)
            val off = phase(scenario, blur = false)
            val p95Delta = on.p95 - off.p95
            val allocDelta = on.allocsPerFrame - off.allocsPerFrame
            val accepted = p95Delta <= 4.0 && allocDelta <= 10.0
            val report = listOf(
                "hardware=$hardware",
                "blur=on frames=${on.frames} p50_ms=%.2f p95_ms=%.2f allocs_per_frame=%.1f".format(on.p50, on.p95, on.allocsPerFrame),
                "blur=off frames=${off.frames} p50_ms=%.2f p95_ms=%.2f allocs_per_frame=%.1f".format(off.p50, off.p95, off.allocsPerFrame),
                "p95_delta_ms=%.2f allocs_delta=%.1f accepted=%s (p95 at most 4 ms worse, at most 10 more allocations per frame)"
                    .format(p95Delta, allocDelta, if (accepted) "yes" else "no"),
            )
            report.forEach { Log.i(TAG, it) }
            File(out, "blur-cost.txt").writeText(report.joinToString("\n", postfix = "\n"))
            assertTrue("software rendering: blur cannot be measured", hardware)
            assertTrue("frames: ${on.frames} / ${off.frames}", on.frames >= FRAMES && off.frames >= FRAMES)
        }
    }

    private fun phase(scenario: ActivityScenario<ComponentActivity>, blur: Boolean): Result {
        val name = if (blur) "blur-on" else "blur-off"
        val text = mutableStateOf("")
        lateinit var activity: ComponentActivity
        for (attempt in 1..3) { // the current instance; set again if it was recreated meanwhile
            scenario.onActivity {
                activity = it
                it.setContent { key(blur) { Bench(blur, text) } } // a new composition per mode
            }
            SystemClock.sleep(3000) // first composition and layout of the transcript
            if (activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) break
            Log.i(TAG, "$name: the activity was recreated (attempt $attempt)")
        }
        diagnose(activity, name)

        val view = activity.window.decorView
        val x = view.width / 2f
        val top = view.height * 0.3f
        val amplitude = view.height * 0.3f
        // A real slow drag away from the bottom releases following (no fling: the finger rests before lifting).
        Finger(x, top).apply {
            for (i in 1..30) move(top + amplitude * i / 30)
            SystemClock.sleep(150)
            move(top + amplitude)
            up()
        }
        SystemClock.sleep(1500)

        val durations = ArrayList<Long>()
        val half = CountDownLatch(FRAMES / 2)
        val done = CountDownLatch(FRAMES)
        val thread = HandlerThread("frames").apply { start() }
        val listener = Window.OnFrameMetricsAvailableListener { _, metrics, _ ->
            synchronized(durations) { if (durations.size < FRAMES) durations += metrics.getMetric(FrameMetrics.TOTAL_DURATION) }
            half.countDown()
            done.countDown()
        }
        @Suppress("DEPRECATION")
        Debug.startAllocCounting()
        val allocsBefore = Debug.getGlobalAllocCount()
        instrumentation.runOnMainSync { activity.window.addOnFrameMetricsAvailableListener(listener, Handler(thread.looper)) }
        // The scripted scroll: one held drag, down and up again over about a second, until 600 frames were drawn.
        val finger = Finger(x, top)
        val until = SystemClock.uptimeMillis() + 120_000
        var step = 0
        var shot = false
        while (done.count > 0 && SystemClock.uptimeMillis() < until) {
            step++
            finger.move(top + amplitude * (1 - cos(step * 2 * PI / 60)).toFloat() / 2)
            if (!shot && half.count == 0L) {
                screenshot("$name.png") // text behind the composer and the glass button while it scrolls
                shot = true
            }
            SystemClock.sleep(16)
        }
        finger.up()
        val allocs = Debug.getGlobalAllocCount() - allocsBefore
        instrumentation.runOnMainSync { activity.window.removeOnFrameMetricsAvailableListener(listener) }
        @Suppress("DEPRECATION")
        Debug.stopAllocCounting()
        thread.quitSafely()
        if (!shot) diagnose(activity, "$name-stalled")

        // The keyboard shown and hidden again (a resize of the bottom area), then the glass button once more.
        SystemClock.sleep(1000)
        tap(view.width / 2f, view.height - 60 * view.resources.displayMetrics.density) // the composer's text field
        SystemClock.sleep(2500)
        instrumentation.runOnMainSync { activity.window.insetsController?.hide(WindowInsets.Type.ime()) }
        SystemClock.sleep(2000)
        Finger(x, top).apply {
            for (i in 1..20) {
                move(top + amplitude * i / 20)
                SystemClock.sleep(16)
            }
            SystemClock.sleep(300)
            screenshot("$name-keyboard.png")
            up()
        }

        val sorted = synchronized(durations) { durations.sorted() }
        fun percentile(p: Double) = if (sorted.isEmpty()) 0.0 else sorted[((sorted.size - 1) * p).toInt()] / 1e6
        return Result(sorted.size, percentile(0.5), percentile(0.95), allocs.toDouble() / sorted.size.coerceAtLeast(1))
    }

    private val compositions = AtomicInteger()

    /** What the device is doing, in logcat (kept by the harness): drawing, frames, compositions, the main thread. */
    private fun diagnose(activity: ComponentActivity, label: String) {
        val draws = AtomicInteger()
        val frames = AtomicInteger()
        val view = activity.window.decorView
        val onDraw = ViewTreeObserver.OnDrawListener { draws.incrementAndGet() }
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                frames.incrementAndGet()
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        val before = compositions.get()
        instrumentation.runOnMainSync {
            view.viewTreeObserver.addOnDrawListener(onDraw)
            Choreographer.getInstance().postFrameCallback(callback)
        }
        SystemClock.sleep(2000)
        val stack = Looper.getMainLooper().thread.stackTrace.take(30).joinToString(" <- ") { "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}" }
        instrumentation.runOnMainSync {
            view.viewTreeObserver.removeOnDrawListener(onDraw)
            Choreographer.getInstance().removeFrameCallback(callback)
        }
        Log.i(TAG, "diag $label: draws=${draws.get()} choreographer=${frames.get()} compositions=${compositions.get() - before} " +
            "state=${activity.lifecycle.currentState} focus=${activity.hasWindowFocus()} shown=${view.isShown} size=${view.width}x${view.height} " +
            "children=${(view as? android.view.ViewGroup)?.childCount}")
        Log.i(TAG, "diag $label main: $stack")
    }

    @androidx.compose.runtime.Composable
    private fun Bench(blur: Boolean, text: MutableState<String>) {
        ApofisTheme(ThemeMode.LIGHT, dynamicColor = false, pureBlack = false, reduceMotion = false, blur = blur) {
            val list = rememberLazyListState()
            SideEffect { compositions.incrementAndGet() }
            LaunchedEffect(Unit) {
                var n = 0
                while (true) {
                    withFrameNanos {}
                    // 40 characters a frame, a paragraph break about every 400.
                    text.value += if (++n % 10 == 0) "words go here\n\n" + "x".repeat(25) else "growing reply text, forty characters ok "
                }
            }
            val live = LiveReply("run_bench", "running", text.value, emptyList(), null, Connection.LIVE)
            val state = ChatUiState(
                "apofis-bench", "Blur cost", StatusLine.WORKING,
                history + UserMessage("u151", "Write a long answer", steered = false, timestamp = NOW) +
                    AssistantReply("live", emptyList(), text.value, NOW, live = true),
                ComposerMode.STOP, "", "emulator", live,
            )
            ChatScreen(state, object : ChatActions {}, onOpenConnection = {}, listState = list)
        }
    }

    /** A finger on the screen: injected events, each delivered before the next. */
    private inner class Finger(private val x: Float, y: Float) {
        private val downTime = SystemClock.uptimeMillis()
        private var y = y

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

    private fun tap(x: Float, y: Float) {
        val t = SystemClock.uptimeMillis()
        listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP).forEach {
            val e = MotionEvent.obtain(t, t + if (it == MotionEvent.ACTION_UP) 50 else 0, it, x, y, 0)
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
        const val NOW = 1773140400.0 // 2026-03-10T11:00Z
    }
}
