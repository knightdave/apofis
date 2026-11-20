package xyz.rycerz.apofis.ui.chat

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.api.ApprovalChoice
import xyz.rycerz.apofis.api.PendingControl
import xyz.rycerz.apofis.run.AnswerResult
import xyz.rycerz.apofis.ui.theme.ApofisTheme

/** The command waiting for approval, in full, with Deny and Allow once; the wider grants sit behind an overflow. */
@Composable
internal fun ApprovalBlock(control: PendingControl, note: AnswerResult?, answering: Boolean, onChoice: (ApprovalChoice) -> Unit) {
    ControlCard(R.string.chat_approval_title, note) {
        control.command?.let { command ->
            CodeSurface {
                Text(
                    command,
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                    style = codeStyle,
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = false,
                )
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            var menu by rememberSaveable { mutableStateOf(false) }
            OutlinedButton(onClick = { onChoice(ApprovalChoice.DENY) }, enabled = !answering) { Text(stringResource(R.string.chat_approval_deny)) }
            Button(onClick = { onChoice(ApprovalChoice.ONCE) }, enabled = !answering) { Text(stringResource(R.string.chat_approval_once)) }
            Box {
                IconButton(onClick = { menu = true }, enabled = !answering) {
                    Icon(painterResource(R.drawable.lucide_ellipsis_vertical), stringResource(R.string.chat_approval_more), Modifier.size(20.dp))
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text(stringResource(R.string.chat_approval_session)) }, onClick = {
                        menu = false
                        onChoice(ApprovalChoice.SESSION)
                    })
                    DropdownMenuItem(text = { Text(stringResource(R.string.chat_approval_always)) }, onClick = {
                        menu = false
                        onChoice(ApprovalChoice.ALWAYS)
                    })
                }
            }
        }
    }
}

/** A question from Hermes, answered right here. */
@Composable
internal fun ClarifyBlock(control: PendingControl, note: AnswerResult?, answering: Boolean, onAnswer: (String) -> Unit) {
    ControlCard(R.string.chat_clarify_title, note) {
        control.question?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) }
        var answer by rememberSaveable(control.requestId) { mutableStateOf("") }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                answer,
                { answer = it },
                Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.chat_clarify_hint)) },
                maxLines = 5,
                enabled = !answering,
            )
            IconButton(onClick = { onAnswer(answer) }, enabled = !answering && answer.isNotBlank()) {
                Icon(painterResource(R.drawable.lucide_arrow_up), stringResource(R.string.chat_clarify_send), Modifier.size(20.dp))
            }
        }
    }
}

@Composable
private fun ControlCard(@StringRes title: Int, note: AnswerResult?, content: @Composable () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .background(ApofisTheme.colors.cardFill, shape)
            .border(1.dp, ApofisTheme.colors.hairlineStrong, shape)
            .padding(12.dp),
    ) {
        Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(R.drawable.lucide_triangle_exclamation_point), null, Modifier.size(18.dp), tint = ApofisTheme.colors.warning)
            Box(Modifier.width(8.dp))
            Text(
                stringResource(title),
                Modifier.semantics { heading() },
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
        }
        content()
        if (note == AnswerResult.CHANGED) {
            Text(
                stringResource(R.string.chat_answer_changed),
                Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
