package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.api.ChatModel
import xyz.rycerz.apofis.ui.theme.ApofisSheet
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.SheetRow

/**
 * The model sheet: Server default first, then each provider's slug as a heading over its models. The current choice is
 * checked; a stored choice missing from the list checks nothing. While the list is missing (loading or failed) Server
 * default stays selectable. The list is lazy: a long one costs only what is on screen, inside the sheet's height.
 */
@Composable
internal fun ModelSheet(sheet: ChoiceSheet.Model, choice: ChatModel, onPick: (provider: String?, model: String?) -> Unit, onRetry: () -> Unit, onDismiss: () -> Unit) {
    ApofisSheet(onDismiss) {
        SheetTitle(R.string.model_title, R.string.model_note)
        LazyColumn(Modifier.testTag("model.list")) {
            item { ChoiceRow(stringResource(R.string.model_default), choice.model == null && choice.provider == null) { onPick(null, null) } }
            if (sheet.failed) {
                item {
                    Text(
                        stringResource(R.string.model_load_failed),
                        Modifier.padding(start = 14.dp, top = 8.dp, end = 14.dp).semantics { liveRegion = LiveRegionMode.Polite },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                item { SheetRow(null, stringResource(R.string.model_retry), onRetry, color = MaterialTheme.colorScheme.primary) }
            }
            for (p in sheet.options?.providers.orEmpty()) {
                item { ProviderHeading(p.slug) }
                items(p.models) { m -> ChoiceRow(m, choice.provider == p.slug && choice.model == m) { onPick(p.slug, m) } }
            }
        }
    }
}

/** The reasoning sheet: the five [REASONING_LEVELS], the current one checked. */
@Composable
internal fun ReasoningSheet(choice: ChatModel, onPick: (String?) -> Unit, onDismiss: () -> Unit) {
    ApofisSheet(onDismiss) {
        SheetTitle(R.string.reasoning_title, R.string.reasoning_note)
        Column(Modifier.verticalScroll(rememberScrollState())) { // a short window still reaches every level
            for (level in REASONING_LEVELS) ChoiceRow(stringResource(reasoningLabel(level)), choice.reasoning == level) { onPick(level) }
        }
    }
}

private fun reasoningLabel(level: String?) = when (level) {
    null -> R.string.reasoning_default
    "none" -> R.string.reasoning_off
    "low" -> R.string.reasoning_low
    "medium" -> R.string.reasoning_medium
    else -> R.string.reasoning_high
}

/** The sheet's title and one muted line under it, lined up with the rows' labels. */
@Composable
private fun ColumnScope.SheetTitle(title: Int, note: Int) {
    Text(
        stringResource(title),
        Modifier.padding(horizontal = 14.dp).semantics { heading() },
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
        stringResource(note),
        Modifier.padding(start = 14.dp, end = 14.dp, top = 2.dp, bottom = 8.dp),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** A provider's slug over its models: the settings section heading's style. */
@Composable
private fun ProviderHeading(slug: String) {
    Text(
        slug,
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, top = 12.dp, bottom = 4.dp).semantics { heading() },
        style = MaterialTheme.typography.labelLarge,
        color = ApofisTheme.colors.sectionTitle,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A choice without a glyph; the current one has the check in the primary color and is selected for accessibility. */
@Composable
private fun ChoiceRow(label: String, checked: Boolean, onClick: () -> Unit) {
    SheetRow(null, label, onClick, Modifier.semantics { selected = checked }) {
        if (checked) Icon(painterResource(R.drawable.lucide_check), null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
    }
}
