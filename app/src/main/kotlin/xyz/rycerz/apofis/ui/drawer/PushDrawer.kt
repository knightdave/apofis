package xyz.rycerz.apofis.ui.drawer

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.MutatePriority
import androidx.compose.foundation.MutatorMutex
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.input.pointer.util.addPointerInputChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineStart
import kotlin.math.sign
import kotlinx.coroutines.isActive
import xyz.rycerz.apofis.ui.theme.SidewaysClaims
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
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
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.dismiss
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.unit.LayoutDirection
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.launch
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.EaseOutCubic
import xyz.rycerz.apofis.ui.theme.LocalHaptics
import xyz.rycerz.apofis.ui.theme.LocalReduceMotion
import xyz.rycerz.apofis.ui.theme.Motion
import xyz.rycerz.apofis.ui.theme.wash

enum class PushDrawerValue { Closed, Open }

/** Whether the drawer panel is on screen (any part of it); the panel content stays composed while it is not. */
val LocalDrawerExposed = compositionLocalOf { true }

/** The panel's share of the screen width. */
private const val DRAWER_WIDTH = 0.75f

/** A release faster than this (dp/s) settles by its direction; slower ones by the halfway point. */
private const val FLING_DP_PER_SECOND = 365f

/** One finger's drag of the drawer. [fromScrim]: the scrim (its own hit target) stays until this drag has settled. */
internal class DragOwner(val fromScrim: Boolean)

/**
 * Where the push drawer is: [fraction] 0 closed to 1 open. Only a direct drag (on the chat's drag area, the panel or the
 * scrim) and code ([open], [close]) move it. [isOpen] is the value it rests at or settles to; a drag does not change it,
 * its end does. Whenever no drag owns it and nothing animates, [fraction] is 0 or 1 and equals [isOpen].
 */
@Stable
class PushDrawerState internal constructor(initial: PushDrawerValue) {
    var target by mutableStateOf(initial)
        private set
    val isOpen: Boolean get() = target == PushDrawerValue.Open
    var fraction by mutableFloatStateOf(if (initial == PushDrawerValue.Open) 1f else 0f)
        private set

    /** The chat is covered: the drawer is not fully closed. Live when read outside composition too. */
    val covered: Boolean by derivedStateOf { fraction > 0f || target == PushDrawerValue.Open }

    internal var width = 0f // the panel width in px, from layout; not state, read only when a drag delta arrives
    internal var reduceMotion = false
    internal var onOpening: () -> Unit = {}
    internal var scope: CoroutineScope? = null // the drawer's own: a drag's settle outlives the node the drag began on
    internal var owner by mutableStateOf<DragOwner?>(null) // the finger drag that owns the drawer, until its end has settled
    internal var settling by mutableStateOf(false) // a settle animation runs
    private val motion = MutatorMutex() // settles at Default, code at PreventUserInput
    private var codeMoves = 0
    private var settleJob: Job? = null

    /** Opens over [Motion.DRAWER] ms. Wins over a finger on the drawer: that finger's further moves and release do nothing. */
    suspend fun open() = byCode(PushDrawerValue.Open)

    suspend fun close() = byCode(PushDrawerValue.Closed)

    private suspend fun byCode(value: PushDrawerValue) {
        owner = null
        codeMoves++
        try {
            motion.mutate(MutatePriority.PreventUserInput) { settle(value) }
        } finally {
            codeMoves--
        }
    }

    /** A finger crossed the slop: it owns the drawer (stopping a release settle), unless code is moving it. */
    internal fun startDrag(owner: DragOwner): Boolean {
        if (codeMoves > 0) return false
        settleJob?.cancel()
        this.owner = owner
        return true
    }

    internal fun dragBy(owner: DragOwner, px: Float) {
        if (this.owner === owner && width > 0f) fraction = (fraction + px / width).coerceIn(0f, 1f)
    }

    /**
     * The one settle rule at a drag's end, also exactly at an end: by position and velocity. Only the owner ends itself,
     * so a settle it outlived never clears a newer drag. A [lost] drag (its node gone) lets go at once.
     */
    internal fun endDrag(owner: DragOwner, velocityDpPerSecond: Float, lost: Boolean) {
        if (this.owner !== owner) return
        if (lost || scope?.isActive != true) this.owner = null // nothing to keep, or nothing can settle now: the idle rule will
        // Undispatched: the identity-checked cleanup is installed before anything can cancel the job, and the settle has
        // entered the mutex before a later code command, which then supersedes it by priority.
        settleJob = scope?.launch(start = CoroutineStart.UNDISPATCHED) {
            try {
                motion.mutate { settle(settleTo(fraction, velocityDpPerSecond)) }
            } finally {
                if (this@PushDrawerState.owner === owner) this@PushDrawerState.owner = null
            }
        }
    }

    /** Nothing owns the drawer and nothing animates: a cancelled settle (the drawer left the screen) is finished here. */
    internal fun idle() {
        if (fraction != if (isOpen) 1f else 0f) settleJob = scope?.launch { motion.mutate { settle(settleTo(fraction, 0f)) } }
    }

    /** Call while holding [motion]. Ends exactly at 0 or 1 unless cancelled; a cancelled settle is redone by [idle]. */
    internal suspend fun settle(value: PushDrawerValue) {
        if (target == PushDrawerValue.Closed && value == PushDrawerValue.Open) onOpening()
        target = value
        val end = if (value == PushDrawerValue.Open) 1f else 0f
        if (reduceMotion || fraction == end) {
            fraction = end
            return
        }
        settling = true
        try {
            animate(fraction, end, animationSpec = tween(Motion.DRAWER, easing = EaseOutCubic)) { v, _ -> fraction = v }
        } finally {
            settling = false
        }
    }

    companion object {
        internal val Saver = Saver<PushDrawerState, PushDrawerValue>(save = { it.target }, restore = { PushDrawerState(it) })
    }
}

/**
 * The drawer's drag recognizer, one finger per gesture: it follows only the finger whose down it checked, through the
 * touch slop and the drag, and never hands over to another finger. A gesture whose down began on an element owning
 * sideways drags ([SidewaysClaims]) never moves the drawer. Before the slop, other fingers are ignored and nothing of
 * theirs is consumed; if the checked finger lifts or another element consumed its move, the gesture ends without moving
 * the drawer. During the drag every change of that finger is consumed while the finger is down (also without sideways
 * motion, so no list picks the gesture up) and goes to the velocity tracker; the up goes to the velocity tracker and is
 * not consumed, and ends the drag with that velocity. A change someone else already consumed, or a missing one, ends the
 * drag as a cancel without being sampled. Other fingers are drained until all are up. No nested scrolling.
 */
private fun Modifier.drawerDrag(state: PushDrawerState, rtl: Boolean, fromScrim: Boolean): Modifier = pointerInput(state, rtl, fromScrim) {
    val sign = if (rtl) -1f else 1f
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (SidewaysClaims.isClaimed(down.id)) return@awaitEachGesture
        val id = down.id
        val owner = DragOwner(fromScrim)
        val velocity = VelocityTracker().apply { addPointerInputChange(down) }
        val slop = viewConfiguration.touchSlop
        var started = false
        var ended = false
        try {
            var dx = 0f
            while (!started) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: return@awaitEachGesture
                if (!change.pressed || change.isConsumed) return@awaitEachGesture
                velocity.addPointerInputChange(change)
                dx += change.positionChange().x
                if (abs(dx) > slop) {
                    started = state.startDrag(owner)
                    if (!started) return@awaitEachGesture // code is moving the drawer
                    change.consume()
                    state.dragBy(owner, sign * (dx - slop * dx.sign))
                }
            }
            while (true) {
                val change = awaitPointerEvent().changes.firstOrNull { it.id == id }
                if (change == null || change.isConsumed) {
                    ended = true
                    state.endDrag(owner, 0f, lost = false)
                    return@awaitEachGesture
                }
                velocity.addPointerInputChange(change)
                if (!change.pressed) {
                    ended = true
                    state.endDrag(owner, sign * velocity.calculateVelocity().x / density, lost = false)
                    return@awaitEachGesture
                }
                state.dragBy(owner, sign * change.positionChange().x)
                change.consume()
            }
        } finally {
            if (started && !ended) state.endDrag(owner, 0f, lost = true)
        }
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

/**
 * The chat list pushes the chat aside from the start edge: the panel ([drawerContent], 75 % wide) and the content move
 * together by translation only, so a drag never composes or measures either. [content] gets the drag area, a horizontal
 * drag recognizer to put where a drag may start (never on editable fields). It takes part in no nested scrolling: an
 * inner sideways scroller keeps its own drags, flings and accessibility scrolls. Whichever side is hidden takes no input,
 * no focus and is absent from accessibility. [onOpening] runs once per opening; on close focus returns to [restoreFocus].
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
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val sign = if (rtl) -1f else 1f
    val reduceMotion = LocalReduceMotion.current
    val latestOpening by rememberUpdatedState(onOpening)
    SideEffect {
        state.reduceMotion = reduceMotion
        state.onOpening = { latestOpening() }
        state.scope = scope
    }
    // A horizontal drag latches only after horizontal touch slop, so vertical scrolls and taps stay with the content.
    val dragArea = remember(state, rtl) { Modifier.drawerDrag(state, rtl, fromScrim = false) }
    val scrimDrag = remember(state, rtl) { Modifier.drawerDrag(state, rtl, fromScrim = true) }
    val contentHidden by remember(state) { derivedStateOf { state.fraction > 0f } }
    val panelHidden by remember(state) { derivedStateOf { state.fraction == 0f } }
    val close: () -> Unit = { scope.launch { state.close() } }
    BackHandler(state.covered, close)

    val keyboard = LocalSoftwareKeyboardController.current
    val focus = LocalFocusManager.current
    val haptics = LocalHaptics.current
    val panelFocus = remember { FocusRequester() }
    val panelHasFocus = remember { booleanArrayOf(false) }
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
            if (previous < 1f && current == 1f && !panelHasFocus[0]) panelFocus.requestFocus()
            if (previous > 0f && current == 0f) {
                focus.clearFocus()
                latestRestore?.requestFocus()
            }
            previous = current
        }
    }
    // The one settle rule, for what no drag end covers (a settle cancelled because the drawer left the screen): once no
    // drag owns the drawer and nothing animates, a frame later, it goes to the end its position says, with that target.
    LaunchedEffect(state) {
        snapshotFlow { state.owner != null || state.settling }.collect { busy ->
            if (busy) return@collect
            withFrameNanos { }
            if (state.owner == null && !state.settling) state.idle()
        }
    }

    val scrim = ApofisTheme.colors.scrim
    val closeLabel = stringResource(R.string.drawer_close)
    val paneLabel = stringResource(R.string.drawer_title)
    val panelSource = remember { MutableInteractionSource() }
    val keyboardMode = LocalInputModeManager.current.inputMode == InputMode.Keyboard // touch opens focus the panel too, without a ring
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
            // Kept while its own drag owns the drawer, so a drag that reaches the closed end keeps its finger until it lifts.
            if (contentHidden || state.owner?.fromScrim == true) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .testTag("drawer.scrim")
                        .graphicsLayer { alpha = state.fraction }
                        .background(scrim)
                        .then(scrimDrag)
                        .semantics {
                            contentDescription = closeLabel
                            traversalIndex = 1f
                        }
                        .clickable(onClickLabel = closeLabel, onClick = close), // the theme's wash and focus ring
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
                .onFocusChanged { panelHasFocus[0] = it.hasFocus }
                .focusGroup()
                .testTag("drawer.panel")
                .background(MaterialTheme.colorScheme.surface)
                .then(dragArea)
                .then(if (keyboardMode) Modifier.wash(panelSource) else Modifier) // a ring while the panel itself holds keyboard focus
                .windowInsetsPadding(
                    WindowInsets.systemBars.union(WindowInsets.displayCutout).union(WindowInsets.ime)
                        .only(WindowInsetsSides.Vertical + WindowInsetsSides.Start),
                )
                .focusRequester(panelFocus)
                .focusProperties { canFocus = !panelHidden }
                .focusable(interactionSource = panelSource),
        ) { CompositionLocalProvider(LocalDrawerExposed provides !panelHidden, content = drawerContent) }
    }
}
