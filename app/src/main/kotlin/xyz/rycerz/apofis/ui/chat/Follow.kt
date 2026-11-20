package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
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
import xyz.rycerz.apofis.chat.ChatItem
import xyz.rycerz.apofis.ui.theme.Motion

/**
 * Whether the chat keeps the bottom of the reversed list pinned (UI map section 2, "Auto-scroll"). Pure functions of
 * the state and the frame time, so every rule is tested at its exact boundary.
 */
internal data class FollowState(val following: Boolean, val holdUntilMs: Long)

/** A user drag releases the bottom at once, also during a hold. */
internal fun onUserDrag(state: FollowState): FollowState = FollowState(following = false, holdUntilMs = 0)

/** When scrolling settles within [thresholdPx] (56 dp) of the bottom, following resumes; farther away it stops. */
internal fun onScrollSettled(state: FollowState, distanceFromBottomPx: Float, thresholdPx: Float): FollowState =
    state.copy(following = distanceFromBottomPx <= thresholdPx)

/** The run ended: a follower stays pinned for [Motion.FOLLOW_HOLD] ms across the live-to-stored swap; a reader is left alone. */
internal fun onRunEnded(state: FollowState, nowMs: Long): FollowState =
    if (state.following) state.copy(holdUntilMs = nowMs + Motion.FOLLOW_HOLD) else state

internal fun shouldPin(state: FollowState, contentGrew: Boolean, atBottom: Boolean, nowMs: Long): Boolean {
    val held = nowMs < state.holdUntilMs
    return (state.following || held) && !atBottom && (contentGrew || held)
}

/**
 * The chat's follow and scroll-button state. Nothing here reads the transcript: each streamed update costs one reference
 * comparison, and scrolling costs a few O(1) reads of the list state.
 */
@Stable
internal class Follower(private val list: LazyListState, internal var wasLive: Boolean) {
    internal var state = FollowState(following = true, holdUntilMs = 0) // read by effects only, so changes recompose nothing
    internal var frameMs = 0L // the latest frame time an effect saw; the hold is judged against it
    internal var items: List<ChatItem>? = null
    internal var recentlyScrolled by mutableStateOf(false)
    internal var dismissed by mutableStateOf(false) // tapped: hidden until the next drag
    // Recomputed on scroll (O(1)); the screen recomposes only when the answer changes.
    val buttonVisible by derivedStateOf { !dismissed && list.canScrollBackward && (list.isScrollInProgress || recentlyScrolled) }

    /** The scroll button: follow again and hide it while the list animates down. */
    fun onButton() {
        state = state.copy(following = true)
        dismissed = true
    }

    /** A send scrolls to the bottom and follows its reply. */
    fun onSend() {
        state = state.copy(following = true)
    }
}

@Composable
internal fun rememberFollow(list: LazyListState, ui: ChatUiState): Follower {
    val live = ui.live != null
    val follower = remember(list) { Follower(list, live) }
    val threshold = with(LocalDensity.current) { 56.dp.toPx() }
    LaunchedEffect(list, follower) {
        list.interactionSource.interactions.collect {
            if (it is DragInteraction.Start) {
                follower.state = onUserDrag(follower.state)
                follower.dismissed = false
            }
        }
    }
    LaunchedEffect(list, follower, threshold) {
        snapshotFlow { list.isScrollInProgress }.drop(1).collectLatest { scrolling -> // the first value is not a settle
            if (scrolling) {
                follower.recentlyScrolled = true
                return@collectLatest
            }
            // The bottom row's shift from its resting place, when it is on screen (a few visible rows: bounded).
            val distance = list.layoutInfo.visibleItemsInfo.firstOrNull { it.index == 0 }?.let { -it.offset.toFloat() } ?: Float.POSITIVE_INFINITY
            follower.state = onScrollSettled(follower.state, distance, threshold)
            val stop = withFrameMillis { it }
            while (scrollButtonVisible(away = true, scrolling = false, idleMs = withFrameMillis { it } - stop)) Unit
            follower.recentlyScrolled = false
        }
    }
    // After the change is applied and before the list measures it: a follower stays pinned with no frame of lag. The
    // list state is not read here (that would recompose on every scroll), so atBottom is unknown: false.
    SideEffect {
        if (follower.items !== ui.items) {
            val grew = follower.items != null
            follower.items = ui.items
            if (grew && shouldPin(follower.state, contentGrew = true, atBottom = false, nowMs = follower.frameMs)) list.requestScrollToItem(0)
        }
    }
    LaunchedEffect(live) {
        val ended = follower.wasLive && !live
        follower.wasLive = live
        if (!ended) return@LaunchedEffect
        var now = withFrameMillis { it }
        follower.state = onRunEnded(follower.state, now)
        while (now < follower.state.holdUntilMs) {
            follower.frameMs = now
            val atBottom = list.firstVisibleItemIndex == 0 && list.firstVisibleItemScrollOffset == 0
            if (shouldPin(follower.state, contentGrew = false, atBottom = atBottom, nowMs = now)) list.scrollToItem(0)
            now = withFrameMillis { it }
        }
        follower.frameMs = now
    }
    return follower
}
