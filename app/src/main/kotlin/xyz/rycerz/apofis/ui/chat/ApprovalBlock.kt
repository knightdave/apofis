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
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import xyz.rycerz.apofis.ui.theme.ApofisIconButton
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.ChatText
import xyz.rycerz.apofis.ui.theme.wash

/**
 * The command waiting for approval, in full, with Deny and Allow once; the wider grants sit behind an overflow.
 * Every answer carries this block's own request id and generation. Without command text there is nothing to judge,
 * so only Deny is offered.
 */
@Composable
internal fun ApprovalBlock(control: PendingControl, changed: Boolean, answering: Boolean, onChoice: (String, Long, ApprovalChoice) -> Unit) {
    val command = control.command?.takeIf { it.isNotBlank() }
    val choose = { choice: ApprovalChoice -> onChoice(control.requestId, control.executionGeneration, choice) }
    ControlCard(R.string.chat_approval_title, changed) {
        if (command != null) {
            CodeSurface {
                Text(
                    command,
                    Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                    style = ChatText.code,
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = false,
                )
            }
        } else {
            Text(stringResource(R.string.chat_approval_no_command), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End), verticalAlignment = Alignment.CenterVertically) {
            val deny = remember { MutableInteractionSource() }
            OutlinedButton(
                onClick = { choose(ApprovalChoice.DENY) },
                Modifier.wash(deny, ButtonDefaults.outlinedShape),
                enabled = !answering,
                interactionSource = deny,
            ) { Text(stringResource(R.string.chat_approval_deny)) }
            if (command != null) {
                val once = remember { MutableInteractionSource() }
                Button(onClick = { choose(ApprovalChoice.ONCE) }, Modifier.wash(once, ButtonDefaults.shape), enabled = !answering, interactionSource = once) {
                    Text(stringResource(R.string.chat_approval_once))
                }
                var menu by rememberSaveable(control.requestId, control.executionGeneration) { mutableStateOf(false) }
                Box {
                    ApofisIconButton(onClick = { menu = true }, contentDescription = stringResource(R.string.chat_approval_more), enabled = !answering) {
                        Icon(painterResource(R.drawable.lucide_ellipsis_vertical), null, Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = menu && !answering, onDismissRequest = { menu = false }) {
                        val session = remember { MutableInteractionSource() }
                        DropdownMenuItem(text = { Text(stringResource(R.string.chat_approval_session)) }, onClick = {
                            menu = false
                            choose(ApprovalChoice.SESSION)
                        }, Modifier.wash(session), interactionSource = session)
                        val always = remember { MutableInteractionSource() }
                        DropdownMenuItem(text = { Text(stringResource(R.string.chat_approval_always)) }, onClick = {
                            menu = false
                            choose(ApprovalChoice.ALWAYS)
                        }, Modifier.wash(always), interactionSource = always)
                    }
                }
            }
        }
    }
}

/** A question from Hermes, answered right here, by this block's own request id and generation. */
@Composable
internal fun ClarifyBlock(control: PendingControl, changed: Boolean, answering: Boolean, onAnswer: (String, Long, String) -> Unit) {
    ControlCard(R.string.chat_clarify_title, changed) {
        control.question?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) }
        var answer by rememberSaveable(control.requestId, control.executionGeneration) { mutableStateOf("") }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                answer,
                { answer = it },
                Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.chat_clarify_hint)) },
                maxLines = 5,
                enabled = !answering,
            )
            ApofisIconButton(
                onClick = { onAnswer(control.requestId, control.executionGeneration, answer) },
                contentDescription = stringResource(R.string.chat_clarify_send),
                enabled = !answering && answer.isNotBlank(),
            ) {
                Icon(painterResource(R.drawable.lucide_arrow_up), null, Modifier.size(20.dp))
            }
        }
    }
}

/** A request of a kind this version does not know: said plainly, with nothing that could grant anything. */
@Composable
internal fun UnsupportedBlock() {
    ControlCard(R.string.chat_unsupported_title, changed = false) {
        Text(stringResource(R.string.chat_unsupported_text), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ControlCard(@StringRes title: Int, changed: Boolean, content: @Composable () -> Unit) {
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
        if (changed) {
            Text(
                stringResource(R.string.chat_answer_changed),
                Modifier.padding(top = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
