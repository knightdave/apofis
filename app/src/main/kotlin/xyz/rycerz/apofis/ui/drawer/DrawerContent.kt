package xyz.rycerz.apofis.ui.drawer

import xyz.rycerz.apofis.ui.theme.claimSideways
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.PulsingDot
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
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import xyz.rycerz.apofis.ui.theme.transparent
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
import androidx.compose.ui.semantics.Role
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
    val minList = with(LocalDensity.current) { (48 * fontScale).dp.roundToPx() } // one row at the current text size
    // The drawer moves focus into its sheet when it opens, which would focus the search field and raise the keyboard.
    // That programmatic entry is refused; moving focus in with a keyboard (Tab, arrows) still works.
    BoxWithConstraints(
        modifier
            .fillMaxHeight()
            .focusProperties { onEnter = { if (requestedFocusDirection == FocusDirection.Enter) cancelFocusChange() } }
            .focusGroup(),
    ) {
        val viewport = constraints.maxHeight
        var fixed by remember { mutableIntStateOf(0) } // header plus footer, px, from the last layout
        // Header, list and footer fill the panel, the list scrolling between them. When that leaves the list less than
        // one row (keyboard, landscape, large text) the rows become part of the one scrolling column: one swipe moves
        // header, rows and footer together. The header stays the same composition, so the search keeps its focus.
        val short = fixed > 0 && viewport - fixed < minList
        Layout(
            content = {
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
                ChatList(rows, query, hasArchived, actions, onArchived, lazy = !short)
                val surface = MaterialTheme.colorScheme.surface
                Box(
                    Modifier
                        .drawBehind { // the list fades out under the footer over 20 dp
                            val fade = 20.dp.toPx()
                            drawRect(Brush.verticalGradient(listOf(surface.transparent(), surface), startY = -fade, endY = 0f), Offset(0f, -fade), Size(size.width, fade))
                        }
                        .padding(start = 16.dp, top = 10.dp, end = 16.dp, bottom = 12.dp),
                ) {
                    IconRow(R.drawable.lucide_settings, stringResource(R.string.settings_title), onSettings, navigation = true)
                }
            },
            modifier = Modifier.verticalScroll(rememberScrollState()),
        ) { measurables, constraints ->
            val width = constraints.maxWidth
            val loose = Constraints(maxWidth = width)
            val header = measurables[0].measure(loose)
            val footer = measurables[2].measure(loose)
            if (header.height + footer.height != fixed) fixed = header.height + footer.height
            val list = if (short) measurables[1].measure(loose) else measurables[1].measure(Constraints.fixed(width, maxOf(viewport - fixed, minList)))
            val listHeight = list.height
            layout(width, header.height + listHeight + footer.height) {
                header.place(0, 0)
                list.place(0, header.height)
                footer.place(0, header.height + listHeight)
            }
        }
    }
}

/** The rows: a lazy list of their own, or ([lazy] false, a short window) plain rows inside the panel's one scroll. */
@Composable
private fun ChatList(rows: List<DrawerRow>, query: String, hasArchived: Boolean, actions: DrawerActions, onArchived: () -> Unit, lazy: Boolean) {
    var pinned = false
    val labelled = rows.map { row ->
        if (row is DrawerRow.Header) pinned = row.label == GroupLabel.Pinned
        row to pinned
    }
    val keyOf = { row: DrawerRow -> if (row is DrawerRow.Chat) row.key else (row as DrawerRow.Header).label.toString() }
    val row: @Composable (DrawerRow, Boolean) -> Unit = { row, isPinned ->
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
    val noMatch: @Composable () -> Unit = {
        Text(
            stringResource(R.string.drawer_no_match),
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    val archived: @Composable () -> Unit = {
        Box(Modifier.padding(top = 8.dp)) { IconRow(R.drawable.lucide_archive, stringResource(R.string.drawer_archived), onArchived, chevron = true, navigation = true) }
    }
    val showNoMatch = query.isNotEmpty() && rows.isEmpty()
    if (lazy) {
        LazyColumn(contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(labelled, key = { (r, _) -> keyOf(r) }) { (r, isPinned) -> row(r, isPinned) }
            if (showNoMatch) item { noMatch() }
            if (hasArchived) item(key = "archived") { archived() }
        }
    } else {
        // shortcut: composes every row while the window is short (keyboard up, large text); a search usually narrows them.
        Column(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            labelled.forEach { (r, isPinned) -> key(keyOf(r)) { row(r, isPinned) } }
            if (showNoMatch) noMatch()
            if (hasArchived) archived()
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
        Modifier.fillMaxWidth().heightIn(min = 48.dp).claimSideways().semantics { contentDescription = hint },
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
    val haptics = ApofisTheme.haptics
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(if (row.selected) ApofisTheme.colors.selectedRow else Color.Transparent)
            .combinedClickable(
                onLongClickLabel = moreLabel,
                onLongClick = {
                    haptics.light()
                    onLongPress()
                },
                hapticFeedbackEnabled = false, // the app's own light() is the one feedback for this gesture
                onClick = onClick,
            )
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
            val label = stringResource(R.string.drawer_working)
            // The panel stays composed while closed: the dot runs only while the drawer shows it.
            PulsingDot(scheme.primary, Modifier.padding(start = 8.dp, end = 4.dp).semantics { contentDescription = label }, active = LocalDrawerExposed.current)
        }
    }
}

/** A plain drawer entry with a leading icon: new chat, Archived, Settings; [navigation] (another page) gives one selection. */
@Composable
private fun IconRow(@DrawableRes icon: Int, label: String, onClick: () -> Unit, chevron: Boolean = false, navigation: Boolean = false) {
    val scheme = MaterialTheme.colorScheme
    val haptics = ApofisTheme.haptics
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Button) {
                if (navigation) haptics.selection()
                onClick()
            }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(icon), null, Modifier.size(20.dp), tint = scheme.onSurface)
        Text(label, Modifier.padding(start = 12.dp).weight(1f), style = MaterialTheme.typography.bodyLarge, color = scheme.onSurface)
        if (chevron) Icon(painterResource(R.drawable.lucide_chevron_right), null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
    }
}
