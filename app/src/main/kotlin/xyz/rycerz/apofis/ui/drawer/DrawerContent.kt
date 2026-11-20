package xyz.rycerz.apofis.ui.drawer

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.rememberTextFieldState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import xyz.rycerz.apofis.R

/** What a chat row can do besides opening; the same actions are offered to accessibility services on the row. */
interface DrawerActions {
    fun onSelect(key: String) {}
    fun onLongPress(key: String) {}
    fun onRenameRequest(key: String) {}
    fun onPin(key: String, pinned: Boolean) {}
    fun onArchive(key: String, archived: Boolean) {}
    fun onDeleteRequest(key: String) {}
}

/**
 * The drawer body: search, a new-chat entry, the grouped chats, an "Archived" row when there are archived chats, and
 * the Settings footer. With no chats at all only the new-chat entry and the footer show.
 */
@Composable
fun DrawerContent(
    rows: List<DrawerRow>,
    query: String,
    refreshFailed: Boolean,
    hasArchived: Boolean,
    actions: DrawerActions,
    onQuery: (String) -> Unit,
    onNewChat: () -> Unit,
    onArchived: () -> Unit,
    onSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val empty = rows.isEmpty() && query.isEmpty() && !hasArchived
    // The drawer moves focus into its sheet when it opens, which would focus the search field and raise the keyboard.
    // That programmatic entry is refused; moving focus in with a keyboard (Tab, arrows) still works.
    Column(
        modifier
            .fillMaxHeight()
            .focusProperties { onEnter = { if (requestedFocusDirection == FocusDirection.Enter) cancelFocusChange() } }
            .focusGroup(),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp)) {
            if (refreshFailed) {
                Text(
                    stringResource(R.string.drawer_refresh_failed),
                    Modifier.padding(start = 14.dp, bottom = 4.dp),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
            if (!empty) SearchField(query, onQuery)
            Box(Modifier.padding(top = 8.dp)) { IconRow(R.drawable.lucide_square_pen, stringResource(R.string.drawer_new_chat), onNewChat) }
        }
        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            var pinned = false
            val labelled = rows.map { row ->
                if (row is DrawerRow.Header) pinned = row.label == GroupLabel.Pinned
                row to pinned
            }
            items(labelled, key = { (row, _) -> if (row is DrawerRow.Chat) row.key else (row as DrawerRow.Header).label.toString() }) { (row, isPinned) ->
                when (row) {
                    is DrawerRow.Header -> GroupHeader(row.label)
                    is DrawerRow.Chat -> ChatRow(
                        row,
                        onClick = { actions.onSelect(row.key) },
                        onLongPress = { actions.onLongPress(row.key) },
                        a11yActions = listOf(
                            stringResource(R.string.drawer_rename) to { actions.onRenameRequest(row.key) },
                            stringResource(if (isPinned) R.string.drawer_unpin else R.string.drawer_pin) to { actions.onPin(row.key, !isPinned) },
                            stringResource(R.string.drawer_archive) to { actions.onArchive(row.key, true) },
                            stringResource(R.string.drawer_delete) to { actions.onDeleteRequest(row.key) },
                        ),
                    )
                }
            }
            if (query.isNotEmpty() && rows.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.drawer_no_match),
                        Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            if (hasArchived) {
                item(key = "archived") {
                    Box(Modifier.padding(top = 8.dp)) { IconRow(R.drawable.lucide_archive, stringResource(R.string.drawer_archived), onArchived, chevron = true) }
                }
            }
        }
        Box(Modifier.padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 12.dp)) {
            IconRow(R.drawable.lucide_settings, stringResource(R.string.settings_title), onSettings)
        }
    }
}

@Composable
private fun SearchField(query: String, onQuery: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val style = MaterialTheme.typography.bodyMedium.copy(color = scheme.onSurface)
    val hint = stringResource(R.string.drawer_search_hint)
    val state = rememberTextFieldState(query)
    val latest by rememberUpdatedState(onQuery)
    LaunchedEffect(state) { snapshotFlow { state.text.toString() }.collect { latest(it) } }
    BasicTextField(
        state,
        Modifier.fillMaxWidth().heightIn(min = 48.dp).semantics { contentDescription = hint },
        textStyle = style,
        lineLimits = TextFieldLineLimits.SingleLine,
        cursorBrush = SolidColor(scheme.primary),
        decorator = { field ->
            Row(
                Modifier
                    .clip(RoundedCornerShape(16.dp))
                    .background(scheme.surfaceContainerHighest)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(painterResource(R.drawable.lucide_search), null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
                Box(Modifier.padding(start = 10.dp).weight(1f)) {
                    if (state.text.isEmpty()) Text(hint, style = style, color = scheme.onSurfaceVariant)
                    field()
                }
            }
        },
    )
}

@Composable
private fun GroupHeader(label: GroupLabel) {
    val text = when (label) {
        GroupLabel.Pinned -> stringResource(R.string.drawer_pinned)
        GroupLabel.Today -> stringResource(R.string.chat_today)
        GroupLabel.Yesterday -> stringResource(R.string.chat_yesterday)
        is GroupLabel.Date -> {
            val locale = LocalConfiguration.current.locales[0]
            LocalDate.ofEpochDay(label.epochDay).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale))
        }
    }
    Text(
        text,
        Modifier.padding(start = 14.dp, top = 6.dp, bottom = 6.dp).semantics { heading() },
        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
        color = MaterialTheme.colorScheme.primary,
    )
}

/** A chat row: at least 48 dp, radius 16, one-line title; the open chat is tinted. */
@Composable
internal fun ChatRow(row: DrawerRow.Chat, onClick: () -> Unit, onLongPress: () -> Unit, a11yActions: List<Pair<String, () -> Unit>>) {
    val scheme = MaterialTheme.colorScheme
    val moreLabel = stringResource(R.string.drawer_more_actions)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (row.selected) scheme.primary.copy(alpha = 0.12f) else Color.Transparent)
            .combinedClickable(onLongClickLabel = moreLabel, onLongClick = onLongPress, onClick = onClick)
            .semantics { customActions = a11yActions.map { (label, run) -> CustomAccessibilityAction(label) { run(); true } } }
            .padding(start = 14.dp, top = 10.dp, end = 8.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            row.title.ifEmpty { stringResource(R.string.chat_new_title) },
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = scheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (row.needsInput) {
            val label = stringResource(R.string.drawer_needs_answer)
            Box(
                Modifier
                    .padding(start = 8.dp, end = 4.dp)
                    .size(9.dp)
                    .background(scheme.primary, CircleShape)
                    .semantics { contentDescription = label },
            )
        } else if (row.running) {
            Icon(
                painterResource(R.drawable.lucide_loader_circle),
                stringResource(R.string.drawer_working),
                Modifier.padding(start = 8.dp).size(16.dp),
                tint = scheme.primary,
            )
        }
    }
}

/** A plain drawer entry with a leading icon: new chat, Archived, Settings. */
@Composable
private fun IconRow(@DrawableRes icon: Int, label: String, onClick: () -> Unit, chevron: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = scheme.onSurface)
        Text(label, Modifier.padding(start = 12.dp).weight(1f), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
        if (chevron) Icon(painterResource(R.drawable.lucide_chevron_right), null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
    }
}
