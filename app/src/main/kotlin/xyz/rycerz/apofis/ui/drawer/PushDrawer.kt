package xyz.rycerz.apofis.ui.drawer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.FlingBehavior
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollScope
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.launch
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.ui.theme.EaseOutCubic
import xyz.rycerz.apofis.ui.theme.LocalHaptics
import xyz.rycerz.apofis.ui.theme.LocalReduceMotion
import xyz.rycerz.apofis.ui.theme.Motion

enum class PushDrawerValue { Closed, Open }

/** Whether the drawer panel is on screen (any part of it); the panel content stays composed while it is not. */
val LocalDrawerExposed = compositionLocalOf { true }

/** The panel's share of the screen width. */
private const val DRAWER_WIDTH = 0.75f

/** A release faster than this (dp/s) settles by its direction; slower ones by the halfway point. */
private const val FLING_DP_PER_SECOND = 365f

/**
 * Where the push drawer is: [fraction] 0 closed to 1 open, moved by drags and settle animations. [isOpen] is the value it
 * rests at or is settling to; a drag in progress does not change it, its release does.
 */
@Stable
class PushDrawerState internal constructor(initial: PushDrawerValue) {
    var target by mutableStateOf(initial)
        private set
    val isOpen: Boolean get() = target == PushDrawerValue.Open
    var fraction by mutableFloatStateOf(if (initial == PushDrawerValue.Open) 1f else 0f)
        private set

    internal var width = 0f // the panel width in px, from layout; not state, read only when a drag delta arrives
    internal var reduceMotion = false
    internal var onOpening: () -> Unit = {}

    /** Drags map linearly to [fraction]; animations hold the same mutex, so a drag interrupts a settle and the reverse. */
    internal val scroll = ScrollableState { delta ->
        if (width <= 0f) {
            0f
        } else {
            val old = fraction
            fraction = (old + delta / width).coerceIn(0f, 1f)
            (fraction - old) * width
        }
    }

    /** Opens over [Motion.DRAWER] ms; wins over a finger on the drawer (the drag in progress ends). */
    suspend fun open() = scroll.scroll(MutatePriority.PreventUserInput) { settle(PushDrawerValue.Open) }

    suspend fun close() = scroll.scroll(MutatePriority.PreventUserInput) { settle(PushDrawerValue.Closed) }

    /** Call while holding [scroll]'s mutex. Ends exactly at 0 or 1 unless cancelled; a cancelled settle is redone by [PushDrawer]. */
    internal suspend fun settle(value: PushDrawerValue) {
        if (target == PushDrawerValue.Closed && value == PushDrawerValue.Open) onOpening()
        target = value
        val end = if (value == PushDrawerValue.Open) 1f else 0f
        if (reduceMotion || fraction == end) {
            fraction = end
        } else {
            animate(fraction, end, animationSpec = tween(Motion.DRAWER, easing = EaseOutCubic)) { v, _ -> fraction = v }
        }
    }

    companion object {
        internal val Saver = Saver<PushDrawerState, PushDrawerValue>(save = { it.target }, restore = { PushDrawerState(it) })
    }
}

/** A drawer that survives rotation and process recreation at the value it was resting at or settling to. */
@Composable
fun rememberPushDrawerState(initial: PushDrawerValue = PushDrawerValue.Closed): PushDrawerState =
    rememberSaveable(saver = PushDrawerState.Saver) { PushDrawerState(initial) }

/** Where a release at [fraction] with [velocityDpPerSecond] (positive opens) comes to rest. */
internal fun settleTo(fraction: Float, velocityDpPerSecond: Float): PushDrawerValue = when {
    abs(velocityDpPerSecond) > FLING_DP_PER_SECOND -> if (velocityDpPerSecond > 0) PushDrawerValue.Open else PushDrawerValue.Closed
    fraction >= 0.5f -> PushDrawerValue.Open
    else -> PushDrawerValue.Closed
}

/** The drawer reached one of its ends between two consecutive fractions: the one haptic tick of the gesture. */
internal fun crossedEdge(previous: Float, current: Float): Boolean =
    (previous < 0.95f && current >= 0.95f) || (previous > 0.05f && current <= 0.05f)

private class Settle(private val state: PushDrawerState, private val density: Float) : FlingBehavior {
    override suspend fun ScrollScope.performFling(initialVelocity: Float): Float {
        state.settle(settleTo(state.fraction, initialVelocity / density))
        return 0f
    }
}

/**
 * The chat list pushes the chat aside from the start edge: the panel ([drawerContent], 75 % wide) and the content move
 * together by translation only, so a drag never composes or measures either. [content] gets the drag area, a horizontal
 * `scrollable`, to put on the part of the screen that may start a drag (never on editable fields); vertical and nested
 * horizontal scrolls keep their gestures, a child at its edge hands the rest of an outward drag on through nested scroll.
 * Whichever side is hidden takes no input, no focus and is absent from accessibility. [onOpening] runs once per
 * opening; on close focus returns to [restoreFocus].
 */
@Composable
fun PushDrawer(
    state: PushDrawerState,
    drawerContent: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    onOpening: () -> Unit = {},
    restoreFocus: FocusRequester? = null,
    content: @Composable (dragArea: Modifier) -> Unit,
) {
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current.density
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val sign = if (rtl) -1f else 1f
    val reduceMotion = LocalReduceMotion.current
    val latestOpening by rememberUpdatedState(onOpening)
    SideEffect {
        state.reduceMotion = reduceMotion
        state.onOpening = { latestOpening() }
    }
    // A horizontal scrollable latches only after horizontal touch slop, so vertical scrolls and taps stay with the content.
    val dragArea = remember(state, rtl, density) {
        Modifier.scrollable(state.scroll, Orientation.Horizontal, reverseDirection = rtl, flingBehavior = Settle(state, density))
    }
    val contentHidden by remember(state) { derivedStateOf { state.fraction > 0f } }
    val panelHidden by remember(state) { derivedStateOf { state.fraction == 0f } }
    val close: () -> Unit = { scope.launch { state.close() } }
    BackHandler(state.isOpen || contentHidden, close)

    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val haptics = LocalHaptics.current
    val panelFocus = remember { FocusRequester() }
    val latestRestore by rememberUpdatedState(restoreFocus)
    // Per frame of movement, outside composition: the haptic tick, the keyboard leaving, focus moving in and back.
    LaunchedEffect(state) {
        var previous = state.fraction
        snapshotFlow { state.fraction }.collect { current ->
            if (crossedEdge(previous, current)) haptics.selection()
            if (previous == 0f && current > 0f) {
                keyboard?.hide()
                focus.clearFocus()
            }
            if (previous < 1f && current == 1f) panelFocus.requestFocus()
            if (previous > 0f && current == 0f) {
                focus.clearFocus()
                latestRestore?.requestFocus()
            }
            previous = current
        }
    }
    // Never left half open: whenever nothing moves it (a settle cancelled by a touch, a disposed gesture), it settles.
    LaunchedEffect(state) {
        snapshotFlow { state.scroll.isScrollInProgress }.filter { !it }.collect {
            if (state.fraction > 0f && state.fraction < 1f) {
                runCatching { state.scroll.scroll { state.settle(settleTo(state.fraction, 0f)) } }
            }
        }
    }

    val scrim = MaterialTheme.colorScheme.onSurface
    val closeLabel = stringResource(R.string.drawer_close)
    val paneLabel = stringResource(R.string.drawer_open)
    Box(modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { translationX = sign * state.fraction * (size.width * DRAWER_WIDTH).roundToInt() },
        ) {
            Box(
                Modifier
                    .fillMaxSize()
                    .then(if (contentHidden) Modifier.clearAndSetSemantics {} else Modifier)
                    .focusProperties { onEnter = { if (state.fraction > 0f) cancelFocusChange() } }
                    .focusGroup(),
            ) { content(dragArea) }
            if (contentHidden) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag("drawer.scrim")
                        .drawBehind { drawRect(scrim, alpha = 0.12f * state.fraction) }
                        .then(dragArea)
                        .clickable(interactionSource = null, indication = null, onClickLabel = closeLabel, onClick = close),
                )
            }
        }
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(DRAWER_WIDTH)
                .onSizeChanged { state.width = it.width.toFloat() }
                .graphicsLayer { translationX = sign * (state.fraction - 1f) * size.width }
                .then(
                    if (panelHidden) {
                        Modifier.clearAndSetSemantics {}
                    } else {
                        Modifier.semantics {
                            paneTitle = paneLabel
                            dismiss { close(); true }
                        }
                    },
                )
                .focusProperties { onEnter = { if (state.fraction == 0f) cancelFocusChange() } }
                .focusGroup()
                .testTag("drawer.panel")
                .background(MaterialTheme.colorScheme.surface)
                .then(dragArea)
                .windowInsetsPadding(
                    WindowInsets.systemBars.union(WindowInsets.displayCutout).union(WindowInsets.ime)
                        .only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
                )
                .focusRequester(panelFocus)
                .focusProperties { canFocus = !panelHidden }
                .focusable(),
        ) { CompositionLocalProvider(LocalDrawerExposed provides !panelHidden, content = drawerContent) }
    }
}
