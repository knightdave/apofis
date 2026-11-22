package xyz.rycerz.apofis.ui.chat

import androidx.annotation.DrawableRes
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.launch
import androidx.compose.runtime.rememberCoroutineScope
import xyz.rycerz.apofis.R
import xyz.rycerz.apofis.ui.theme.ApofisIconButton
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.BackdropState
import xyz.rycerz.apofis.ui.theme.EaseOutCubic
import xyz.rycerz.apofis.ui.theme.Motion
import xyz.rycerz.apofis.ui.theme.disabledFill
import xyz.rycerz.apofis.ui.theme.frostedGlass

// Test tags (end-to-end tests find the buttons by these ids).
internal const val SEND_TAG = "composer.send"
internal const val STOP_TAG = "composer.stop"
internal const val FIELD_TAG = "composer.field"

/** The card's outer margin above and below; the chat's bottom fade ends at the card's bottom edge (ChatScreen). */
internal val COMPOSER_MARGIN = 8.dp

/** Whether the main button works: a send in flight ([busy]) blocks another send or steer, never Stop. */
internal fun primaryEnabled(mode: ComposerMode, busy: Boolean) = when (mode) {
    ComposerMode.SEND_DISABLED -> false
    ComposerMode.SEND, ComposerMode.STEER -> !busy
    ComposerMode.STOP -> true
}

/** The expand button shows once the text is three lines tall, and always while expanded (it collapses). */
internal fun showsExpand(lineCount: Int, expanded: Boolean): Boolean = expanded || lineCount >= 3

/** Whether a submission (button, IME action or Enter) is accepted now. */
internal fun canSubmit(mode: ComposerMode, busy: Boolean): Boolean = (mode == ComposerMode.SEND || mode == ComposerMode.STEER) && !busy

/** A hardware Enter key-down sends: only with Enter to send on, without Shift, not a repeat, and only when submittable. */
internal fun enterSubmits(enterToSend: Boolean, shift: Boolean, repeatCount: Int, mode: ComposerMode, busy: Boolean): Boolean =
    enterToSend && !shift && repeatCount == 0 && canSubmit(mode, busy)

/**
 * A frosted card in one row: the text (1 to 5 lines, then it scrolls), then a button column with the expand button at
 * the top (once the text is three lines tall) and the send button (with Stop before it while steering) at the bottom.
 *
 * The text is the holder's [draft]: only user edits call [onDraftChange]; expanding, collapsing and recomposition never
 * do. [expanded] grows the field over [Motion.COMPOSER_EXPAND] ms to [expandedHeight], or when unspecified to all the
 * height its constraints give less [reservePx] (the chat's top bar), never below its collapsed height. An accepted
 * submission collapses at once. [enterToSend]: Return sends (IME action Send; hardware Enter, Shift+Enter is a new line).
 * [busy]: see [primaryEnabled]; the field also takes no edits then. [backdrop]: the glass samples it when blur is on.
 */
@Composable
internal fun Composer(
    draft: String,
    mode: ComposerMode,
    onDraftChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    enterToSend: Boolean = false,
    expanded: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
    expandedHeight: Dp = Dp.Unspecified,
    backdrop: BackdropState? = null,
    reservePx: Int = 0,
) {
    val shape = RoundedCornerShape(20.dp)
    val scheme = MaterialTheme.colorScheme
    val colors = ApofisTheme.colors
    val scope = rememberCoroutineScope()
    val progress = remember { Animatable(if (expanded) 1f else 0f) } // 0 collapsed, 1 expanded
    var collapseAtOnce by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        when {
            expanded -> progress.animateTo(1f, tween(Motion.COMPOSER_EXPAND, easing = EaseOutCubic))
            collapseAtOnce -> progress.snapTo(0f)
            else -> progress.animateTo(0f, tween(Motion.COMPOSER_EXPAND, easing = EaseOutCubic))
        }
        collapseAtOnce = false
    }
    val submit = {
        if (canSubmit(mode, busy)) {
            if (expanded) {
                collapseAtOnce = true
                // Undispatched: the snap happens before the next layout, whatever the effect does later.
                scope.launch(start = CoroutineStart.UNDISPATCHED) { progress.snapTo(0f) }
                onExpandedChange(false)
            }
            onSend()
        }
    }

    // The value-based field (as BasicTextField(String) does it), keeping the selection and the IME's composition so
    // Enter can tell a word still being composed.
    var held by remember { mutableStateOf(TextFieldValue(draft)) }
    val value = held.copy(text = draft)
    androidx.compose.runtime.SideEffect { if (value.selection != held.selection || value.composition != held.composition) held = value }
    var lastText by remember(draft) { mutableStateOf(draft) }
    var lines by remember { mutableIntStateOf(1) }
    val manyLines by remember { derivedStateOf { lines >= 3 } }
    val open by remember { derivedStateOf { progress.value > 0f } }
    val enterKey = remember { EnterKey() }

    val fillOnly = backdrop?.layer == null || !ApofisTheme.blur
    // Without blur the glass shows the content under it: a page backplate keeps text and icons at their contrast.
    val backplate = if (fillOnly) Modifier.background(scheme.surface, shape) else Modifier
    Layout(
        content = {
            BasicTextField(
                value,
                { v ->
                    held = v
                    val changed = lastText != v.text
                    lastText = v.text
                    if (changed) onDraftChange(v.text)
                },
                Modifier
                    .fillMaxWidth()
                    .semantics { traversalIndex = -1f } // read before the buttons
                    .testTag(FIELD_TAG)
                    .onPreviewKeyEvent { e ->
                        if (e.key != Key.Enter && e.key != Key.NumPadEnter) return@onPreviewKeyEvent false
                        when (e.type) {
                            KeyEventType.KeyDown -> {
                                // Off, or Shift: the field inserts the newline.
                                if (!enterToSend || e.isShiftPressed) return@onPreviewKeyEvent false
                                enterKey.swallowUp = true
                                when {
                                    e.nativeKeyEvent.repeatCount > 0 -> Unit // a held key sends at most once
                                    held.composition != null -> held = held.copy(composition = null) // finish the word, send nothing
                                    enterSubmits(enterToSend, shift = false, e.nativeKeyEvent.repeatCount, mode, busy) -> submit()
                                }
                                true
                            }
                            KeyEventType.KeyUp -> enterKey.swallowUp.also { enterKey.swallowUp = false }
                            else -> false
                        }
                    }
                    .layout { measurable, constraints ->
                        val f = progress.value
                        if (f == 0f) {
                            val p = measurable.measure(constraints)
                            enterKey.collapsedHeight = p.height
                            layout(p.width, p.height) { p.place(0, 0) }
                        } else {
                            val room = if (expandedHeight.isSpecified) minOf(expandedHeight.roundToPx(), constraints.maxHeight) else constraints.maxHeight - reservePx
                            val target = maxOf(room, enterKey.collapsedHeight)
                            val h = lerp(enterKey.collapsedHeight, target, f)
                            val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = maxOf(h, constraints.minHeight)))
                            layout(p.width, h) { p.place(0, 0) }
                        }
                    },
                readOnly = busy, // it shows the text being stored, which no keystroke may change
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                keyboardOptions = KeyboardOptions(imeAction = if (enterToSend) ImeAction.Send else ImeAction.Default),
                keyboardActions = KeyboardActions(onSend = { submit() }),
                maxLines = if (open) Int.MAX_VALUE else 5,
                onTextLayout = { lines = it.lineCount },
                cursorBrush = SolidColor(scheme.primary),
                decorationBox = { inner ->
                    // 14 dp above and below one 19.5 sp line: as tall as the 48 dp send target beside it.
                    Box(Modifier.padding(start = 4.dp, top = 14.dp, bottom = 14.dp)) {
                        if (draft.isEmpty()) Text(stringResource(R.string.chat_message_hint), style = MaterialTheme.typography.bodyLarge, color = colors.muted)
                        inner()
                    }
                },
            )
            Box {
                if (showsExpand(if (manyLines) 3 else 1, expanded)) {
                    ApofisIconButton(
                        onClick = { onExpandedChange(!expanded) },
                        contentDescription = stringResource(if (expanded) R.string.composer_collapse else R.string.composer_expand),
                        visualSize = 24.dp,
                    ) {
                        Icon(painterResource(if (expanded) R.drawable.lucide_minimize_2 else R.drawable.lucide_maximize_2), null, Modifier.size(16.dp), tint = colors.expandIcon)
                    }
                }
            }
            Row {
                if (mode == ComposerMode.STEER) RoundButton(R.drawable.lucide_square, R.string.chat_stop, enabled = true, filled = false, tag = STOP_TAG, onClick = onStop)
                val stop = mode == ComposerMode.STOP
                val label = when (mode) {
                    ComposerMode.STEER -> R.string.chat_steer
                    ComposerMode.STOP -> R.string.chat_stop
                    else -> R.string.chat_send
                }
                RoundButton(
                    if (stop) R.drawable.lucide_square else R.drawable.lucide_arrow_up,
                    label,
                    enabled = primaryEnabled(mode, busy),
                    tag = if (stop) STOP_TAG else SEND_TAG,
                    onClick = if (stop) onStop else submit,
                )
            }
        },
        modifier = modifier
            .padding(horizontal = 12.dp, vertical = COMPOSER_MARGIN)
            .fillMaxWidth()
            .then(backplate)
            .frostedGlass(backdrop, shape, colors.glassFill, colors.glassBorder)
            .semantics { isTraversalGroup = true }
            .padding(8.dp),
    ) { measurables, constraints ->
        // The buttons keep their own column at the end: the expand target never overlaps the text, send or stop.
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val expand = measurables[1].measure(loose)
        val buttons = measurables[2].measure(loose)
        val side = maxOf(expand.width, buttons.width)
        val width = constraints.maxWidth
        val fieldWidth = (width - side).coerceAtLeast(0)
        val field = measurables[0].measure(Constraints(fieldWidth, fieldWidth, 0, constraints.maxHeight))
        val height = maxOf(field.height, expand.height + buttons.height)
        layout(width, height) {
            field.placeRelative(0, height - field.height)
            expand.placeRelative(width - expand.width, 0)
            buttons.placeRelative(width - buttons.width, height - buttons.height)
        }
    }
}

/** Per-composer key and layout bookkeeping read outside composition (no recomposition). */
private class EnterKey {
    var swallowUp = false // the key-up of an Enter press this composer consumed
    var collapsedHeight = 0 // the field's last collapsed height, px: where expanding starts and collapsing ends
}

/** A 32 dp circle with an 18 dp glyph inside a 48 dp touch target; the glyph swaps with a scale and fade. */
@Composable
private fun RoundButton(@DrawableRes icon: Int, label: Int, enabled: Boolean, tag: String, filled: Boolean = true, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val fill = if (enabled && filled) scheme.primary else Color.Transparent
    // Disabled: the button fades the opaque glyph to 0.38, drawn over the 0.12 fill behind it (outside that fade).
    val glyph = when {
        !enabled -> scheme.onSurface
        filled -> scheme.onPrimary
        else -> scheme.primary
    }
    Box(contentAlignment = androidx.compose.ui.Alignment.Center) {
        if (!enabled) Box(Modifier.size(32.dp).background(scheme.disabledFill, CircleShape))
        ApofisIconButton(onClick = onClick, contentDescription = stringResource(label), Modifier.testTag(tag), enabled = enabled, visualSize = 32.dp) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(fill, CircleShape)
                    .then(if (filled) Modifier else Modifier.border(1.5.dp, scheme.primary, CircleShape)),
                contentAlignment = androidx.compose.ui.Alignment.Center,
            ) {
                val swap = tween<Float>(Motion.GLYPH_SWAP, easing = EaseOutCubic)
                AnimatedContent(icon, transitionSpec = { (scaleIn(swap, 0.6f) + fadeIn(swap)) togetherWith (scaleOut(swap, 0.6f) + fadeOut(swap)) }, label = "glyph") {
                    Icon(painterResource(it), contentDescription = null, Modifier.size(18.dp), tint = glyph)
                }
            }
        }
    }
}
