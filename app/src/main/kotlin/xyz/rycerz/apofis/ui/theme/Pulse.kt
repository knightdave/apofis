package xyz.rycerz.apofis.ui.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlin.math.PI
import kotlin.math.cos
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/** Scale and alpha of one dot of a three-dot wave. */
data class DotFrame(val scale: Float, val alpha: Float)

/** 0 at the start of each period, 1 halfway: `0.5 - 0.5 cos(2 pi phase)`. */
private fun wave(phase: Double): Float = (0.5 - 0.5 * cos(2 * PI * phase)).toFloat()

/** Dot [index] (0 to 2) at [timeMs]: phase `t / DOTS_PERIOD - 0.22 i`, scale 0.85 to 1, alpha 0.45 to 0.9. */
fun dotWave(timeMs: Long, index: Int): DotFrame {
    val phase = (timeMs.toDouble() / Motion.DOTS_PERIOD - 0.22 * index).mod(1.0)
    val v = wave(phase)
    return DotFrame(0.85f + 0.15f * v, 0.45f + 0.45f * v)
}

/** From [min] at the start of each [periodMs] to [max] halfway and back. */
fun pulseAlpha(timeMs: Long, periodMs: Int, min: Float, max: Float): Float = min + (max - min) * wave((timeMs % periodMs).toDouble() / periodMs)

/** Frame loops run by [rememberTimeMs]; for tests. */
internal object PulseProbe {
    var frames = 0L
}

private object NoTime : State<Long> {
    override val value = 0L
}

/**
 * Milliseconds into the current [periodMs], one value per frame, for an infinite animation. Read it only in a draw or
 * graphics layer lambda, so a frame redraws and recomposes nothing. The loop runs only while the caller is composed
 * (a lazy row that leaves the screen stops it) and the lifecycle is at least STARTED; under reduced motion there is no
 * loop and the value is 0.
 */
@Composable
fun rememberTimeMs(periodMs: Int): State<Long> {
    if (ApofisTheme.reduceMotion) return NoTime
    val time = remember { mutableLongStateOf(0L) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, periodMs) {
        lifecycle.currentStateFlow.map { it.isAtLeast(Lifecycle.State.STARTED) }.distinctUntilChanged().collectLatest { started ->
            if (!started) return@collectLatest
            val start = withFrameMillis { it } - time.longValue // resume where it stopped
            while (true) {
                val now = withFrameMillis { it }
                time.longValue = (now - start) % periodMs
                PulseProbe.frames++
            }
        }
    }
    return time
}

/** A 9 dp dot whose alpha pulses 0.4 to 1 over [Motion.PULSE_PERIOD]; under reduced motion a 9 dp ring, 1.5 dp stroke. */
@Composable
fun PulsingDot(color: Color, modifier: Modifier = Modifier) {
    val reduce = ApofisTheme.reduceMotion
    val time = rememberTimeMs(Motion.PULSE_PERIOD)
    Box(
        modifier.size(9.dp).drawBehind {
            if (reduce) {
                val stroke = 1.5.dp.toPx()
                drawCircle(color, radius = (size.minDimension - stroke) / 2, style = Stroke(stroke))
            } else {
                drawCircle(color, alpha = pulseAlpha(time.value, Motion.PULSE_PERIOD, 0.4f, 1f))
            }
        },
    )
}
