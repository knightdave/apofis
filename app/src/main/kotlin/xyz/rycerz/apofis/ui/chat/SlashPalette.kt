package xyz.rycerz.apofis.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.chat.Command
import xyz.rycerz.apofis.ui.theme.ApofisTheme

internal const val PALETTE_TAG = "chat.palette"

/**
 * The slash palette: one row per command in [matches], its word and its description ([modelLabel] on the /model row). A
 * tap runs a command without text ([onRun]) or fills one that takes text ([onFill]), with one selection feedback. The
 * palette fills the height it is given and scrolls inside it; the chat's bottom area decides that height. Each row is
 * keyed by its command, so a press begun on one row ends with that row when the matches change.
 */
@Composable
fun SlashPalette(matches: List<Command>, modelLabel: String, onRun: (Command) -> Unit, onFill: (Command) -> Unit, modifier: Modifier = Modifier) {
    val shape = MaterialTheme.shapes.large
    val colors = ApofisTheme.colors
    val haptics = ApofisTheme.haptics
    Column(
        modifier
            .testTag(PALETTE_TAG)
            .clip(shape)
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, colors.hairline, shape)
            .padding(1.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        for (command in matches) key(command) {
            // SheetRow's metrics; the word is its own first text, so the row's label begins with "/word".
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .clickable(role = Role.Button) {
                        haptics.selection()
                        if (command.takesText) onFill(command) else onRun(command)
                    }
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("/" + command.word, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface)
                Box(Modifier.width(12.dp))
                Text(
                    description(command, modelLabel),
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    color = colors.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun description(command: Command, modelLabel: String): String = when (command) {
    Command.NEW -> stringResource(R.string.cmd_new)
    Command.STOP -> stringResource(R.string.cmd_stop)
    Command.STEER -> stringResource(R.string.cmd_steer)
    Command.QUEUE -> stringResource(R.string.cmd_queue)
    Command.TITLE -> stringResource(R.string.cmd_title)
    Command.MODEL -> modelLabel
    Command.REASONING -> stringResource(R.string.cmd_reasoning)
    Command.FAST -> stringResource(R.string.cmd_fast)
}
