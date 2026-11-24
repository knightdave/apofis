package xyz.rycerz.apofis.ui.chat

import androidx.compose.ui.platform.LocalDensity
import xyz.rycerz.apofis.ui.theme.claimSideways
import xyz.rycerz.apofis.ui.theme.sidewaysScroll
import androidx.annotation.StringRes
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.annotation.DrawableRes
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.staticCompositionLocalOf
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
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.api.ApprovalChoice
import xyz.rycerz.apofis.api.PendingControl
import xyz.rycerz.apofis.ui.theme.ApofisIconButton
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.ChatText
import xyz.rycerz.apofis.ui.theme.wash

/**
 * The command waiting for approval, with Deny and Allow once; the wider grants sit behind an overflow. A command up to
 * [ONE_LINE_MAX] characters is shown whole in its row; a longer one shows its head there and a row that opens all of
 * it in the paged reader ([SelectTextScreen]), so nothing over the bound is laid out in one piece and what Allow grants
 * can always be read whole. Every answer carries this block's own request id and generation. Without command text
 * there is nothing to judge, so only Deny is offered.
 */
@Composable
internal fun ApprovalBlock(control: PendingControl, changed: Boolean, answering: Boolean, gate: AnswerGate, onChoice: (String, Long, ApprovalChoice) -> Unit) {
    // The request's current command, checked once per distinct command (the blank check walks a whitespace prefix).
    val command = remember(control.command) { control.command?.takeIf { it.isNotBlank() } }
    var reading by rememberSaveable(control.requestId, control.executionGeneration) { mutableStateOf(false) }
    val chatGate = LocalChatGate.current // a control of the chat layout the drawer covers
    val clock = LocalAnswerClock.current
    val choose = { press: Press, choice: ApprovalChoice -> if (gate.accepts(press)) onChoice(control.requestId, control.executionGeneration, choice) }
    ControlCard(R.string.chat_approval_title, changed, R.drawable.lucide_triangle_exclamation_point, ApofisTheme.colors.warning) {
        if (command != null) {
            CodeSurface {
                Text(
                    oneLine(command),
                    Modifier.sidewaysScroll(rememberScrollState(), LocalDensity.current).padding(horizontal = 12.dp, vertical = 8.dp),
                    style = ChatText.code,
                    color = MaterialTheme.colorScheme.onSurface,
                    softWrap = false,
                )
            }
            if (command.length > ONE_LINE_MAX) SmallTextButton(R.string.chat_approval_full_command, { chatGate { reading = true } })
            if (reading) {
                OverChat(gate.overlays)
                Dialog(onDismissRequest = { reading = false }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
                    SelectTextScreen(command, onBack = { reading = false })
                }
            }
        } else {
            Text(stringResource(R.string.chat_approval_no_command), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(
            Modifier.fillMaxWidth().padding(top = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Pill(stringResource(R.string.chat_approval_deny), filled = false, enabled = !answering, gate) { choose(it, ApprovalChoice.DENY) }
            if (command != null) {
                Pill(stringResource(R.string.chat_approval_once), filled = true, enabled = !answering, gate) { choose(it, ApprovalChoice.ONCE) }
                var menu by rememberSaveable(control.requestId, control.executionGeneration) { mutableStateOf(false) }
                Box {
                    // Marked when asked to open, not when composed: its own items are answer controls pressed only after
                    // it shows, so none of them may share the mark's millisecond.
                    ApofisIconButton(onClick = { chatGate { gate.overlays.openedAt = clock(); menu = true } }, contentDescription = stringResource(R.string.chat_approval_more), enabled = !answering) {
                        Icon(painterResource(R.drawable.lucide_ellipsis_vertical), null, Modifier.size(20.dp))
                    }
                    DropdownMenu(expanded = menu && !answering, onDismissRequest = { menu = false }) {
                        val session = remember { MutableInteractionSource() }
                        val sessionPress = remember { Press() }
                        DropdownMenuItem(text = { Text(stringResource(R.string.chat_approval_session)) }, onClick = {
                            menu = false
                            choose(sessionPress, ApprovalChoice.SESSION)
                        }, Modifier.pressStart(sessionPress, gate).wash(session), interactionSource = session)
                        val always = remember { MutableInteractionSource() }
                        val alwaysPress = remember { Press() }
                        DropdownMenuItem(text = { Text(stringResource(R.string.chat_approval_always)) }, onClick = {
                            menu = false
                            choose(alwaysPress, ApprovalChoice.ALWAYS)
                        }, Modifier.pressStart(alwaysPress, gate).wash(always), interactionSource = always)
                    }
                }
            }
        }
    }
}

/** A question from Hermes, answered right here, by this block's own request id and generation. */
@Composable
internal fun ClarifyBlock(control: PendingControl, changed: Boolean, answering: Boolean, gate: AnswerGate, onAnswer: (String, Long, String) -> Unit) {
    ControlCard(R.string.chat_clarify_title, changed) {
        control.question?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurface) }
        var answer by rememberSaveable(control.requestId, control.executionGeneration) { mutableStateOf("") }
        val sendPress = remember { Press() }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                answer,
                { answer = it },
                Modifier.weight(1f).claimSideways(),
                placeholder = { Text(stringResource(R.string.chat_clarify_hint)) },
                maxLines = 5,
                enabled = !answering,
                colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = ApofisTheme.colors.timelineOutline), // 3:1 on the card
            )
            ApofisIconButton(
                onClick = { if (gate.accepts(sendPress)) onAnswer(control.requestId, control.executionGeneration, answer) },
                contentDescription = stringResource(R.string.chat_clarify_send),
                Modifier.pressStart(sendPress, gate),
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

/** Header: a 24 dp icon column (approval: warning triangle; others: info), then the title as a heading. */
@Composable
private fun ControlCard(
    @StringRes title: Int,
    changed: Boolean,
    @DrawableRes icon: Int = R.drawable.lucide_info,
    tint: Color = ApofisTheme.colors.timelineStrong,
    content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 6.dp)
            .fillMaxWidth()
            .background(ApofisTheme.colors.timelineFill, shape)
            .border(1.dp, ApofisTheme.colors.hairlineStrong, shape)
            .padding(12.dp),
    ) {
        Row(Modifier.padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(24.dp), contentAlignment = Alignment.Center) { Icon(painterResource(icon), null, Modifier.size(18.dp), tint = tint) }
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

/**
 * An answer button: a 36 dp pill (outlined, or [filled] in primary) centred in a 48 dp touch target, one node with
 * the wash and focus ring on the pill itself.
 */
@Composable
private fun Pill(label: String, filled: Boolean, enabled: Boolean, gate: AnswerGate, onClick: (Press) -> Unit) {
    val source = remember { MutableInteractionSource() }
    val press = remember { Press() }
    val scheme = MaterialTheme.colorScheme
    Box(
        Modifier
            .heightIn(min = 48.dp)
            .pressStart(press, gate)
            .clickable(source, indication = null, enabled = enabled, role = Role.Button) { onClick(press) },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .heightIn(min = 36.dp)
                .alpha(if (enabled) 1f else 0.38f)
                .then(if (filled) Modifier.background(scheme.primary, CircleShape) else Modifier.border(1.dp, ApofisTheme.colors.hairlineStrong, CircleShape))
                .wash(source, CircleShape, ring = if (filled) scheme.onPrimary else Color.Unspecified)
                .padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = if (filled) scheme.onPrimary else scheme.onSurface)
        }
    }
}

/**
 * Double-tap protection for one request (the block is keyed by its id and generation, so a changed request gets a
 * new gate). [shownAt] is when this block first showed the request, on the uptime clock. An answer is accepted only
 * from a press that began at least the double-tap interval ([settle]) after that, on this request. A press is timed
 * by its own down event (the pointer's or the key's own uptime), so one handled late never looks later than it was.
 * Each activation uses exactly the press that produced it, on its own control: a click from a pointer release uses
 * that pointer's down, one from a key release uses that key's down; an activation with no press of its own (an
 * accessibility action) uses none and is judged when it arrives. Nothing else blocks an answer.
 */
internal class AnswerGate(private val clock: () -> Long, private val settle: Long, val overlays: Overlays = Overlays()) {
    private val shownAt = clock()

    /** Called by an answer control's click with that control's press record: consumes the press it came from. */
    fun accepts(press: Press): Boolean {
        val begun: Pair<Long, AnswerGate>? = when {
            press.pointerReleasing -> press.pointerDown.also { press.pointerDown = null; press.pointerReleasing = false } ?: return false
            press.keyReleasing -> press.keyReleased.also { press.keyReleased = null; press.keyReleasing = false } ?: return false
            else -> null // no press of its own
        }
        if (begun != null && begun.second !== this) return false // begun on another request
        if (begun != null && begun.first <= overlays.openedAt) return false // it began before a window opened over the chat
        return (begun?.first ?: clock()) - shownAt >= settle
    }
}

/**
 * When a window last opened over the chat (a viewer, sheet, dialog or menu; [OverChat]), on the answer clock: the
 * uptime domain of a press's own down event. A press whose down is not later answers nothing; closing a window
 * changes nothing. Main thread.
 */
internal class Overlays {
    var openedAt = Long.MIN_VALUE
}

/**
 * The [Overlays] of the chat and every window over it, provided once above them (AppNav). None: the chat screen and
 * an answer block each keep their own, so nothing is shared between roots or tests.
 */
internal val LocalOverlays = staticCompositionLocalOf<Overlays?> { null }

/**
 * Placed in the composition of a window that opens over the chat: marks [overlays] with the answer clock when it
 * enters, and again when [showing] changes (another kind of prompt is another showing). See [Overlays].
 */
@Composable
internal fun OverChat(overlays: Overlays?, showing: Any? = null) {
    val clock = LocalAnswerClock.current
    DisposableEffect(overlays, showing) {
        overlays?.openedAt = clock()
        onDispose {}
    }
}

/**
 * One answer control's presses: the pointer gesture in progress (its down's own uptime and request) and the
 * activation keys held on it (each key's own down time), plus which release is being dispatched right now.
 */
internal class Press {
    var pointerDown: Pair<Long, AnswerGate>? = null
    var pointerReleasing = false // the release of that pointer gesture is being dispatched
    val keyDowns = HashMap<Key, Pair<Long, AnswerGate>>()
    var keyReleased: Pair<Long, AnswerGate>? = null // the down of the key whose release is being dispatched
    var keyReleasing = false
}

private val activationKeys = setOf(Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.DirectionCenter)

/**
 * Records this answer control's presses before its own click handling sees them: pointer downs and releases (cleared
 * once the gesture ends, cancelled or not) and activation key downs (not repeats) and releases (cleared once the
 * release has been handled without a click, and all of them when the control loses focus).
 */
private fun Modifier.pressStart(press: Press, gate: AnswerGate): Modifier = this
    .pointerInput(press, gate) {
        awaitEachGesture {
            try {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                press.pointerDown = down.uptimeMillis to gate
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                } while (event.changes.any { it.pressed })
                press.pointerReleasing = true // the click, if any, comes from this release (Main pass)
                awaitPointerEvent(PointerEventPass.Final) // the same release, after the click
            } finally {
                press.pointerDown = null
                press.pointerReleasing = false
            }
        }
    }
    .onFocusChanged { if (!it.hasFocus) press.keyDowns.clear() }
    .onPreviewKeyEvent {
        if (it.key in activationKeys) {
            if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.repeatCount == 0) {
                press.keyDowns[it.key] = it.nativeKeyEvent.eventTime to gate
            } else if (it.type == KeyEventType.KeyUp) {
                press.keyReleased = press.keyDowns.remove(it.key)
                press.keyReleasing = true
            }
        }
        false
    }
    .onKeyEvent {
        // Reached only when the control did not take the release as a click: drop what that release recorded.
        if (it.type == KeyEventType.KeyUp) {
            press.keyReleased = null
            press.keyReleasing = false
        }
        false
    }
