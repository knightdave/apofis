package xyz.rycerz.apofis.ui.chat

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.sync.isTemporaryKey
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.EaseOutCubic
import xyz.rycerz.apofis.ui.theme.Motion
import xyz.rycerz.apofis.ui.theme.transparent

/** The bar's row height below the status bar. */
internal val TopBarHeight = 56.dp

/** The gradient's fade below the bar's row. */
internal val TopFade = 24.dp

/**
 * The transparent top bar over the list. Its bounds are the opaque part: the status bar and the row (at least
 * [TopBarHeight], taller with large text); a page-colored gradient continues [TopFade] below them and fades to clear,
 * so the title and the status line stay readable over scrolled text. No elevation, no scroll tint. Horizontal safe
 * insets (side navigation, cutouts) keep the controls clear of the edges.
 */
@Composable
internal fun ChatTopBar(
    state: ChatUiState,
    onOpenConnection: () -> Unit,
    onRetryLoad: () -> Unit,
    navigationIcon: @Composable () -> Unit,
    actions: @Composable RowScope.() -> Unit,
    modifier: Modifier = Modifier,
) {
    val surface = MaterialTheme.colorScheme.surface
    Box(
        modifier
            .fillMaxWidth()
            .drawBehind {
                val fade = TopFade.toPx()
                val total = size.height + fade
                drawRect(
                    Brush.verticalGradient(0f to surface, size.height / total to surface, 1f to surface.transparent(), endY = total),
                    size = Size(size.width, total),
                )
            }
            .testTag("chat.topbar"),
    ) {
        Row(
            Modifier
                .statusBarsPadding()
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))
                .padding(end = 4.dp)
                .heightIn(min = TopBarHeight)
                .fillMaxWidth()
                .testTag("chat.topbar.row"),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            navigationIcon()
            Box(Modifier.weight(1f)) { Title(state, onOpenConnection, onRetryLoad) }
            actions()
        }
    }
}

@Composable
private fun Title(state: ChatUiState, onOpenConnection: () -> Unit, onRetryLoad: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val action = when (state.status) {
        StatusLine.KEY_REJECTED -> stringResource(R.string.chat_status_open_connection) to onOpenConnection
        StatusLine.LOAD_FAILED -> stringResource(R.string.chat_load_retry) to onRetryLoad
        else -> null
    }
    // The whole title area is the touch target: the status line alone is too small.
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .then(if (action != null) Modifier.clickable(onClickLabel = action.first, onClick = action.second) else Modifier)
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        val title = if (isTemporaryKey(state.sessionKey)) stringResource(R.string.chat_temporary_title) else state.title.ifEmpty { stringResource(R.string.chat_new_title) }
        AnimatedContent(
            title,
            transitionSpec = {
                val spec = tween<Float>(Motion.TITLE_FADE, easing = EaseOutCubic)
                fadeIn(spec) togetherWith fadeOut(spec)
            },
            label = "title",
        ) { shown ->
            // Exactly one title node: the outgoing title is hidden from accessibility and the end-to-end tests.
            val leaving = transition.targetState != EnterExitState.Visible
            Text(
                shown,
                if (leaving) Modifier.clearAndSetSemantics {} else Modifier.testTag("chat.title"), // the end-to-end tests read the title here
                style = MaterialTheme.typography.titleMedium,
                color = scheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val (icon, text, color) = when (state.status) {
            StatusLine.IDLE -> Triple(null, state.host, ApofisTheme.colors.subtitle)
            StatusLine.WORKING -> Triple(null, stringResource(R.string.chat_status_working), scheme.primary)
            StatusLine.QUEUED -> Triple(null, stringResource(R.string.chat_status_queued), scheme.primary)
            StatusLine.OFFLINE -> Triple(R.drawable.lucide_wifi_off, stringResource(R.string.chat_status_offline), scheme.error)
            StatusLine.UNREACHABLE -> Triple(R.drawable.lucide_cloud_off, stringResource(R.string.chat_status_unreachable), scheme.error)
            StatusLine.KEY_REJECTED -> Triple(R.drawable.lucide_key_round, stringResource(R.string.chat_status_key_rejected), scheme.error)
            StatusLine.SERVER_ERROR -> Triple(R.drawable.lucide_triangle_exclamation_point, stringResource(R.string.chat_status_server_error), scheme.error)
            StatusLine.CHAT_GONE -> Triple(R.drawable.lucide_triangle_exclamation_point, stringResource(R.string.chat_status_chat_gone), scheme.error)
            StatusLine.LOAD_FAILED -> Triple(R.drawable.lucide_triangle_exclamation_point, stringResource(R.string.chat_status_load_failed), scheme.error)
        }
        StatusText(icon, text, color, tappable = action != null)
    }
}

@Composable
private fun StatusText(@DrawableRes icon: Int?, text: String, color: Color, tappable: Boolean) {
    Row(Modifier.testTag("status.line").semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(painterResource(icon), null, Modifier.size(12.dp), tint = color)
            Box(Modifier.width(4.dp))
        }
        Text(text, Modifier.weight(1f, fill = false), style = MaterialTheme.typography.labelSmall, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis) // measured after the chevron, which keeps its width
        if (tappable) Icon(painterResource(R.drawable.lucide_chevron_right), null, Modifier.size(12.dp), tint = color)
    }
}
