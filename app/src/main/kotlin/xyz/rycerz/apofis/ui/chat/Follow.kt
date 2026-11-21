package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import xyz.rycerz.apofis.ui.theme.Motion

/**
 * Whether the chat keeps the bottom of the reversed list pinned (UI map section 2, "Auto-scroll"). Pure functions of
 * the state and the frame time, so every rule is tested at its exact boundary.
 */
internal data class FollowState(val following: Boolean, val holdUntilMs: Long)

/** A user drag releases the bottom at once, also during a hold. */
internal fun onUserDrag(state: FollowState): FollowState = FollowState(following = false, holdUntilMs = 0)

/**
 * When scrolling settles within [thresholdPx] (56 dp) of the bottom, following resumes; farther away it stops and any
 * hold ends with it: a reader who scrolled away during a handoff is not pulled back when the hold would run out.
 */
internal fun onScrollSettled(state: FollowState, distanceFromBottomPx: Float, thresholdPx: Float): FollowState =
    if (distanceFromBottomPx <= thresholdPx) state.copy(following = true) else FollowState(following = false, holdUntilMs = 0)

/** The run ended: a follower stays pinned for [Motion.FOLLOW_HOLD] ms across the live-to-stored swap; a reader is left alone. */
internal fun onRunEnded(state: FollowState, nowMs: Long): FollowState =
    if (state.following) state.copy(holdUntilMs = nowMs + Motion.FOLLOW_HOLD) else state

internal fun shouldPin(state: FollowState, contentGrew: Boolean, atBottom: Boolean, nowMs: Long): Boolean {
    val held = nowMs < state.holdUntilMs
    return (state.following || held) && !atBottom && (contentGrew || held)
}

/**
 * The chat's follow and scroll-button state. Nothing here reads the transcript: growth arrives from [MessageList]
 * ([Follower.onGrew]), and scrolling costs a few O(1) reads of the list state.
 */
@Stable
internal class Follower(private val list: LazyListState, internal var wasLive: Boolean) {
    // Read by effects only, so changes recompose nothing. Not following until the first measured layout says the list
    // is at the bottom: a position restored many screens up (recreation) is a reader.
    internal var state = FollowState(following = false, holdUntilMs = 0)
    internal var measured = false
    internal var dragging = false
    internal var frameMs = 0L // the latest frame time an effect saw; the hold is judged against it
    internal var recentlyScrolled by mutableStateOf(false)
    internal var dismissed by mutableStateOf(false) // tapped: hidden until the next scroll settles
    val buttonVisible by derivedStateOf {
        !dismissed && scrollButtonVisible(list.canScrollBackward, list.isScrollInProgress, if (recentlyScrolled) 0L else Long.MAX_VALUE)
    }

    /** The scroll button: hidden while the list animates down; following resumes when that scroll settles at the bottom. */
    fun onButton() {
        dismissed = true
    }

    /** A send scrolls to the bottom and follows its reply (also in a chat that was empty, so never measured with data). */
    fun onSend() {
        state = state.copy(following = true)
        measured = true
    }

    /**
     * What the run shows at the bottom grew ([MessageList]'s `onRevealGrew`: revealed text, steps, a pending request, or
     * the stored transcript while polling), reported after the change is applied and before the list measures it, so a
     * follower stays pinned with no frame of lag. Never against a scroll someone else owns; a reader is left alone.
     */
    fun onGrew() {
        if (measured && free() && shouldPin(state, contentGrew = true, atBottom = atBottom(), nowMs = frameMs)) {
            ChromeProbe.pins++
            list.requestScrollToItem(0)
        }
    }

    /** A pin may move the list only when no one else scrolls it: a drag owns it through [state], any other scroll (TalkBack, keyboard, the button's animation) by being in progress. */
    internal fun free() = dragging || !list.isScrollInProgress

    internal fun atBottom() = list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0

    /** The bottom row's shift from its resting place (a few visible rows: bounded). */
    internal fun distance(): Float =
        list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }?.let { -it.offset.toFloat() } ?: Float.POSITIVE_INFINITY
}

@Composable
internal fun rememberFollow(list: LazyListState, ui: ChatUiState): Follower {
    val live = ui.live
    val follower = remember(list) { Follower(list, live != null) }
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    LaunchedEffect(list, follower, threshold) {
        // The first layout that shows data: an empty first layout (the transcript still loading) decides nothing, and a
        // saved position takes effect only once items exist.
        snapshotFlow { list.layoutInfo.viewportSize.height > 0 && list.layoutInfo.totalItemsCount > 0 }.first { it }
        follower.state = onScrollSettled(follower.state, follower.distance(), threshold)
        follower.measured = true
    }
    LaunchedEffect(list, follower) {
        list.interactionSource.interactions.collect {
            when (it) {
                is DragInteraction.Start -> {
                    follower.dragging = true
                    follower.state = onUserDrag(follower.state)
                }
                is DragInteraction.Stop, is DragInteraction.Cancel -> follower.dragging = false
            }
        }
    }
    LaunchedEffect(list, follower, threshold) {
        snapshotFlow { list.isScrollInProgress }.drop(1).collectLatest { scrolling -> // the first value is not a settle
            if (scrolling) {
                follower.recentlyScrolled = true
                return@collectLatest
            }
            if (follower.measured) follower.state = onScrollSettled(follower.state, follower.distance(), threshold)
            follower.dismissed = false
            if (list.canScrollBackward) delay(Motion.SCROLL_BUTTON_HIDE.toLong()) // one timeout, cancelled by the next scroll
            follower.recentlyScrolled = false
        }
    }
    LaunchedEffect(live != null) {
        val ended = follower.wasLive && live == null
        follower.wasLive = live != null
        if (!ended) return@LaunchedEffect
        var now = withFrameMillis { it }
        follower.state = onRunEnded(follower.state, now)
        while (now < follower.state.holdUntilMs) {
            follower.frameMs = now
            if (follower.free() && shouldPin(follower.state, contentGrew = false, atBottom = follower.atBottom(), nowMs = now)) {
                ChromeProbe.pins++
                list.scrollToItem(0)
            }
            now = withFrameMillis { it }
        }
        follower.frameMs = now
    }
    return follower
}
