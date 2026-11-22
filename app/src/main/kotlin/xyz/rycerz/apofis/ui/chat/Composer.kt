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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.isTraversalGroup
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.traversalIndex
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.util.lerp
import xyz.rycerz.apofis.BuildConfig
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
private val CARD_PADDING = 8.dp

/** Whether the main button works: a send in flight ([busy]) blocks another send or steer, never Stop. Drawing only. */
internal fun primaryEnabled(mode: ComposerMode, busy: Boolean) = when (mode) {
    ComposerMode.SEND_DISABLED -> false
    ComposerMode.SEND, ComposerMode.STEER -> !busy
    ComposerMode.STOP -> true
}

/** The expand button shows once the text is three lines tall, and always while expanded (it collapses). */
internal fun showsExpand(lineCount: Int, expanded: Boolean): Boolean = expanded || lineCount >= 3

/** What an Enter key event does: goes to the field, attempts a send, or is consumed. */
internal enum class EnterKey { FIELD, SEND, CONSUME }

/**
 * Stateless. With Enter to send off, or for any other key, the field gets it. On: a first key-down without Shift attempts
 * a send (consumed whether the holder accepts or not), a first key-down with Shift goes to the field (a newline), and
 * every repeat and key-up of Enter is consumed, so a held key never sends twice nor types a newline.
 */
internal fun enterKey(enterToSend: Boolean, enter: Boolean, down: Boolean, repeatCount: Int, shift: Boolean): EnterKey = when {
    !enterToSend || !enter -> EnterKey.FIELD
    down && repeatCount == 0 -> if (shift) EnterKey.FIELD else EnterKey.SEND
    else -> EnterKey.CONSUME
}

/**
 * A frosted card in one row: the text, then a button column with the expand button at the top (once the text is three
 * lines tall, and only when the column has room for two 48 dp targets) and the send button (with Stop before it while
 * steering) at the bottom.
 *
 * The composer takes at most its constraints' height (its room) and reports the height it occupies: collapsed, 1 to 5
 * lines (the text scrolls inside the field), expanded the whole room (or [expandedHeight] for the field), growing over
 * [Motion.COMPOSER_EXPAND] ms; collapsing snaps. In a short room the card's margins and padding shrink first, so Send
 * keeps its 48 dp; see [BottomDims].
 *
 * The text is the holder's [draft]; only user edits call [onDraftChange]. [onSend] asks the holder and returns whether
 * it took the draft; only then the composer collapses. The button, the IME action (only with [enterToSend]) and
 * hardware Enter (see [enterKey]) all go through it. [busy]: see [primaryEnabled]; the field also takes no edits then.
 */
@Composable
internal fun Composer(
    draft: String,
    mode: ComposerMode,
    onDraftChange: (String) -> Unit,
    onSend: () -> Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    busy: Boolean = false,
    enterToSend: Boolean = false,
    expanded: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
    expandedHeight: Dp = Dp.Unspecified,
    backdrop: BackdropState? = null,
) {
    if (BuildConfig.DEBUG) SideEffect { ChromeProbe.composerBackdropId = System.identityHashCode(backdrop) }
    val shape = RoundedCornerShape(20.dp)
    val scheme = MaterialTheme.colorScheme
    val colors = ApofisTheme.colors
    val style = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface)
    val density = LocalDensity.current
    val dims = remember(density, style) { density.bottomDims(style) }
    val progress = remember { Animatable(if (expanded) 1f else 0f) } // 0 collapsed, 1 expanded
    LaunchedEffect(expanded) { if (expanded) progress.animateTo(1f, tween(Motion.COMPOSER_EXPAND, easing = EaseOutCubic)) else progress.snapTo(0f) }
    val submit = { if (onSend()) onExpandedChange(false) }
    var lines by remember { mutableIntStateOf(1) }
    val manyLines by remember { derivedStateOf { showsExpand(lines, expanded = false) } }
    val pad = with(density) { (dims.fieldPadding / 2f).toDp() }

    val fillOnly = backdrop?.layer == null || !ApofisTheme.blur
    // Without blur the glass shows the content under it: a page backplate keeps text and icons at their contrast.
    val backplate = if (fillOnly) Modifier.background(scheme.surface, shape) else Modifier
    Layout(
        content = {
            Box(Modifier.then(backplate).frostedGlass(backdrop, shape, colors.glassFill, colors.glassBorder)) // the card
            BasicTextField(
                draft,
                onDraftChange,
                Modifier
                    .fillMaxWidth()
                    .semantics { traversalIndex = -1f } // read before the buttons
                    .testTag(FIELD_TAG)
                    .onPreviewKeyEvent { e ->
                        val enter = e.key == Key.Enter || e.key == Key.NumPadEnter
                        when (enterKey(enterToSend, enter, e.type == KeyEventType.KeyDown, e.nativeKeyEvent.repeatCount, e.isShiftPressed)) {
                            EnterKey.FIELD -> false
                            EnterKey.SEND -> true.also { submit() }
                            EnterKey.CONSUME -> true
                        }
                    },
                readOnly = busy, // it shows the text being stored, which no keystroke may change
                textStyle = style,
                keyboardOptions = KeyboardOptions(imeAction = if (enterToSend) ImeAction.Send else ImeAction.Default),
                keyboardActions = KeyboardActions(onSend = { if (enterToSend) submit() }),
                maxLines = if (expanded) Int.MAX_VALUE else 5,
                onTextLayout = { lines = it.lineCount },
                cursorBrush = SolidColor(scheme.primary),
                decorationBox = { inner ->
                    Box(Modifier.padding(start = 4.dp, top = pad, bottom = pad)) {
                        if (draft.isEmpty()) Text(stringResource(R.string.chat_message_hint), style = MaterialTheme.typography.bodyLarge, color = colors.muted)
                        inner()
                    }
                },
            )
            Box {
                if (expanded || manyLines) {
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
                PrimaryButton(mode, busy, submit, onStop)
            }
        },
        modifier = modifier.fillMaxWidth().semantics { isTraversalGroup = true },
    ) { measurables, constraints ->
        val width = constraints.maxWidth
        val room = if (constraints.hasBoundedHeight) constraints.maxHeight else Int.MAX_VALUE / 2
        // Vertical margins and padding for this room: they shrink first (padding last), so the row keeps its height.
        val insets = if (room >= dims.row(1) + dims.cardInsets) dims.cardInsets else (room - dims.row(1)).coerceAtLeast(0)
        val padding = minOf(insets / 2, CARD_PADDING.roundToPx())
        val margin = insets / 2 - padding
        val rowRoom = (room - 2 * (margin + padding)).coerceAtLeast(0)
        val side = MARGIN_X.roundToPx() + CARD_PADDING.roundToPx()
        val inner = (width - 2 * side).coerceAtLeast(0)
        val loose = Constraints(0, inner, 0, rowRoom)
        val buttons = measurables[3].measure(loose)
        val expand = measurables[2].measure(loose)
        val withExpand = expand.height > 0 && dims.actions(rowRoom, wantsExpand = true) >= buttons.height + expand.height
        val fieldWidth = (inner - maxOf(expand.width, buttons.width)).coerceAtLeast(0)
        val field = if (!expanded) {
            measurables[1].measure(Constraints(fieldWidth, fieldWidth, 0, rowRoom)) // 1 to 5 lines (maxLines), scrolling inside
        } else {
            val full = if (expandedHeight.isSpecified) minOf(expandedHeight.roundToPx(), rowRoom) else rowRoom
            val from = minOf(dims.field(lines.coerceIn(1, 5)), full) // the collapsed field for the current text
            val h = lerp(from, full, progress.value)
            measurables[1].measure(Constraints.fixed(fieldWidth, h))
        }
        val row = minOf(rowRoom, maxOf(field.height, buttons.height + if (withExpand) expand.height else 0))
        val card = row + 2 * padding
        val height = card + 2 * margin
        val glass = measurables[0].measure(Constraints.fixed((width - 2 * MARGIN_X.roundToPx()).coerceAtLeast(0), card))
        layout(width, height) {
            glass.placeRelative(MARGIN_X.roundToPx(), margin)
            val top = margin + padding
            field.placeRelative(side, top + row - field.height)
            if (withExpand) expand.placeRelative(width - side - expand.width, top)
            buttons.placeRelative(width - side - buttons.width, top + row - buttons.height)
        }
    }
}

private val MARGIN_X = 12.dp

/**
 * The primary button: Send, Steer or Stop, one stable control (one focus target, one labelled node) whatever the mode.
 * A click counts only if its press began while the same action was shown: the action is recorded when the pointer goes
 * down or an activation key is pressed, and compared at the click (an accessibility action, with no press of its own,
 * is judged when it arrives). A press held while the action changes fires nothing.
 */
@Composable
private fun PrimaryButton(mode: ComposerMode, busy: Boolean, submit: () -> Unit, onStop: () -> Unit) {
    val action = if (mode == ComposerMode.SEND_DISABLED) ComposerMode.SEND else mode
    val shown = rememberUpdatedState(action)
    val press = remember { ActionPress() }
    val stop = mode == ComposerMode.STOP
    RoundButton(
        if (stop) R.drawable.lucide_square else R.drawable.lucide_arrow_up,
        when (mode) {
            ComposerMode.STEER -> R.string.chat_steer
            ComposerMode.STOP -> R.string.chat_stop
            else -> R.string.chat_send
        },
        enabled = primaryEnabled(mode, busy),
        tag = if (stop) STOP_TAG else SEND_TAG,
        modifier = Modifier.pressStart(press) { shown.value },
        onClick = { if (press.accepts(action)) (if (action == ComposerMode.STOP) onStop else submit)() },
    )
}

/** The primary button's presses: the action shown when the pointer went down and when each activation key went down. */
private class ActionPress {
    var pointer: ComposerMode? = null
    var pointerReleasing = false // the release of that gesture is being dispatched
    val keys = HashMap<Key, ComposerMode>()
    var keyReleased: ComposerMode? = null // the action recorded for the key whose release is being dispatched
    var keyReleasing = false

    /** Called by the click with the action it would run: consumes the press it came from. */
    fun accepts(now: ComposerMode): Boolean {
        val begun = when {
            pointerReleasing -> pointer.also { pointer = null; pointerReleasing = false } ?: return false
            keyReleasing -> keyReleased.also { keyReleased = null; keyReleasing = false } ?: return false
            else -> now // no press of its own (an accessibility action)
        }
        return begun == now
    }
}

private val activationKeys = setOf(Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.DirectionCenter)

/** Records the shown action at each press, before the click handling sees the press. */
private fun Modifier.pressStart(press: ActionPress, shown: () -> ComposerMode): Modifier = this
    .pointerInput(press) {
        awaitEachGesture {
            try {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                press.pointer = shown()
                do {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                } while (event.changes.any { it.pressed })
                press.pointerReleasing = true // the click, if any, comes from this release (Main pass)
                awaitPointerEvent(PointerEventPass.Final) // the same release, after the click
            } finally {
                press.pointer = null
                press.pointerReleasing = false
            }
        }
    }
    .onFocusChanged { if (!it.hasFocus) press.keys.clear() }
    .onPreviewKeyEvent {
        if (it.key in activationKeys) {
            if (it.type == KeyEventType.KeyDown && it.nativeKeyEvent.repeatCount == 0) {
                press.keys[it.key] = shown()
            } else if (it.type == KeyEventType.KeyUp) {
                press.keyReleased = press.keys.remove(it.key)
                press.keyReleasing = true
            }
        }
        false
    }
    .onKeyEvent {
        // Reached only when the button did not take the release as a click: drop what that release recorded.
        if (it.type == KeyEventType.KeyUp) {
            press.keyReleased = null
            press.keyReleasing = false
        }
        false
    }

/** A 32 dp circle with an 18 dp glyph inside a 48 dp touch target; the glyph swaps with a scale and fade. */
@Composable
private fun RoundButton(@DrawableRes icon: Int, label: Int, enabled: Boolean, tag: String, modifier: Modifier = Modifier, filled: Boolean = true, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val fill = if (enabled && filled) scheme.primary else Color.Transparent
    // Disabled: the button fades the opaque glyph to 0.38, drawn over the 0.12 fill behind it (outside that fade).
    val tint = when {
        !enabled -> scheme.onSurface
        filled -> scheme.onPrimary
        else -> scheme.primary
    }
    Box(contentAlignment = Alignment.Center) {
        if (!enabled) Box(Modifier.size(32.dp).background(scheme.disabledFill, CircleShape))
        ApofisIconButton(onClick = onClick, contentDescription = stringResource(label), modifier.testTag(tag), enabled = enabled, visualSize = 32.dp) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(fill, CircleShape)
                    .then(if (filled) Modifier else Modifier.border(1.5.dp, scheme.primary, CircleShape)),
                contentAlignment = Alignment.Center,
            ) {
                val swap = tween<Float>(Motion.GLYPH_SWAP, easing = EaseOutCubic)
                AnimatedContent(icon, transitionSpec = { (scaleIn(swap, 0.6f) + fadeIn(swap)) togetherWith (scaleOut(swap, 0.6f) + fadeOut(swap)) }, label = "glyph") {
                    Icon(painterResource(it), contentDescription = null, Modifier.size(18.dp), tint = tint)
                }
            }
        }
    }
}
