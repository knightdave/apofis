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
import androidx.compose.runtime.key
import androidx.compose.runtime.movableContentOf
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.isSpecified
import androidx.compose.ui.util.lerp
import kotlin.math.ceil
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
private val TARGET = 48.dp

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

private fun Density.lineHeight(style: TextStyle): Float =
    if (style.lineHeight.isEm) style.lineHeight.value * style.fontSize.toPx() else style.lineHeight.toPx()

/** Above and below the text: one line is as tall as the 48 dp send target (14 dp at font scale 1), at least 4 dp. */
private fun Density.fieldPadding(style: TextStyle): Dp = ((TARGET.toPx() - lineHeight(style)) / 2).toDp().coerceIn(4.dp, 14.dp)

/** The field's height for [lines] lines of [style], with its padding, px. */
private fun Density.fieldHeight(style: TextStyle, lines: Int): Int = ceil(lineHeight(style) * lines).toInt() + (fieldPadding(style) * 2).roundToPx()

/** The smallest card, px: margins, padding and one row as tall as one line of [style] or a 48 dp target, whichever is taller. */
internal fun Density.composerMinHeight(style: TextStyle): Int =
    ((COMPOSER_MARGIN + CARD_PADDING) * 2).roundToPx() + maxOf(TARGET.roundToPx(), fieldHeight(style, 1))

/**
 * A frosted card in one row: the text, then a button column with the expand button at the top (once the text is three
 * lines tall, and only when the column has room for two 48 dp targets) and the send button (with Stop before it while
 * steering) at the bottom.
 *
 * The card takes at most its constraints' height, never less than [composerMinHeight] (then its top is cut off and Send
 * stays on screen). Collapsed, the text shows 1 to 5 lines within that height and scrolls inside the field. [expanded]
 * grows the field over [Motion.COMPOSER_EXPAND] ms to the whole height (or [expandedHeight]); collapsing snaps.
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
    val progress = remember { Animatable(if (expanded) 1f else 0f) } // 0 collapsed, 1 expanded
    LaunchedEffect(expanded) { if (expanded) progress.animateTo(1f, tween(Motion.COMPOSER_EXPAND, easing = EaseOutCubic)) else progress.snapTo(0f) }
    val submit = { if (onSend()) onExpandedChange(false) }
    var lines by remember { mutableIntStateOf(1) }
    val manyLines by remember { derivedStateOf { showsExpand(lines, expanded = false) } }
    val pad = with(androidx.compose.ui.platform.LocalDensity.current) { fieldPadding(style) }

    val fillOnly = backdrop?.layer == null || !ApofisTheme.blur
    // Without blur the glass shows the content under it: a page backplate keeps text and icons at their contrast.
    val backplate = if (fillOnly) Modifier.background(scheme.surface, shape) else Modifier
    Layout(
        content = {
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
                    }
                    .layout { measurable, constraints ->
                        if (!expanded || !constraints.hasBoundedHeight) {
                            val p = measurable.measure(constraints) // 1 to 5 lines (maxLines), at most the room given
                            layout(p.width, p.height) { p.place(0, 0) }
                        } else {
                            val room = if (expandedHeight.isSpecified) minOf(expandedHeight.roundToPx(), constraints.maxHeight) else constraints.maxHeight
                            val collapsed = fieldHeight(style, lines.coerceIn(1, 5)).coerceAtMost(room)
                            val h = lerp(collapsed, room, progress.value)
                            val p = measurable.measure(constraints.copy(minHeight = h, maxHeight = h))
                            layout(p.width, h) { p.place(0, 0) }
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
                if (mode == ComposerMode.STEER) RoundButton(R.drawable.lucide_square, R.string.chat_stop, enabled = true, filled = false, tag = STOP_TAG, action = mode, onClick = onStop)
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
                    action = if (mode == ComposerMode.SEND_DISABLED) ComposerMode.SEND else mode,
                    onClick = if (stop) onStop else submit,
                )
            }
        },
        modifier = modifier
            .layout { measurable, constraints ->
                // Never smaller than one row: in a window too short for it the top is cut off, Send stays.
                if (!constraints.hasBoundedHeight) return@layout measurable.measure(constraints).let { p -> layout(p.width, p.height) { p.place(0, 0) } }
                val floor = composerMinHeight(style)
                val p = measurable.measure(constraints.copy(maxHeight = maxOf(constraints.maxHeight, floor)))
                val h = minOf(p.height, constraints.maxHeight).coerceAtLeast(constraints.minHeight)
                layout(p.width, h) { p.place(0, h - p.height) }
            }
            .padding(horizontal = 12.dp, vertical = COMPOSER_MARGIN)
            .fillMaxWidth()
            .then(backplate)
            .frostedGlass(backdrop, shape, colors.glassFill, colors.glassBorder)
            .semantics { isTraversalGroup = true }
            .padding(CARD_PADDING),
    ) { measurables, constraints ->
        // The buttons keep their own column at the end: the expand target never overlaps the text, send or stop.
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val buttons = measurables[2].measure(loose)
        val expand = measurables[1].measure(loose)
        // Expand is dropped before Send when the column cannot hold both 48 dp targets.
        val withExpand = expand.height > 0 && buttons.height + expand.height <= constraints.maxHeight
        val width = constraints.maxWidth
        val fieldWidth = (width - maxOf(expand.width, buttons.width)).coerceAtLeast(0)
        val field = measurables[0].measure(Constraints(fieldWidth, fieldWidth, 0, constraints.maxHeight))
        val height = maxOf(field.height, buttons.height + if (withExpand) expand.height else 0)
        layout(width, height) {
            field.placeRelative(0, height - field.height)
            if (withExpand) expand.placeRelative(width - expand.width, 0)
            buttons.placeRelative(width - buttons.width, height - buttons.height)
        }
    }
}

/**
 * A 32 dp circle with an 18 dp glyph inside a 48 dp touch target; the glyph swaps with a scale and fade. The clickable is
 * keyed by [action], so a press begun on one action is cancelled when the action changes; the glyph moves along.
 */
@Composable
private fun RoundButton(@DrawableRes icon: Int, label: Int, enabled: Boolean, tag: String, action: ComposerMode, filled: Boolean = true, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val fill = if (enabled && filled) scheme.primary else Color.Transparent
    // Disabled: the button fades the opaque glyph to 0.38, drawn over the 0.12 fill behind it (outside that fade).
    val tint = when {
        !enabled -> scheme.onSurface
        filled -> scheme.onPrimary
        else -> scheme.primary
    }
    val glyph = remember {
        movableContentOf { i: Int, t: Color ->
            val swap = tween<Float>(Motion.GLYPH_SWAP, easing = EaseOutCubic)
            AnimatedContent(i, transitionSpec = { (scaleIn(swap, 0.6f) + fadeIn(swap)) togetherWith (scaleOut(swap, 0.6f) + fadeOut(swap)) }, label = "glyph") {
                Icon(painterResource(it), contentDescription = null, Modifier.size(18.dp), tint = t)
            }
        }
    }
    Box(contentAlignment = Alignment.Center) {
        if (!enabled) Box(Modifier.size(32.dp).background(scheme.disabledFill, CircleShape))
        key(action) {
            ApofisIconButton(onClick = onClick, contentDescription = stringResource(label), Modifier.testTag(tag), enabled = enabled, visualSize = 32.dp) {
                Box(
                    Modifier
                        .fillMaxSize()
                        .background(fill, CircleShape)
                        .then(if (filled) Modifier else Modifier.border(1.5.dp, scheme.primary, CircleShape)),
                    contentAlignment = Alignment.Center,
                ) {
                    glyph(icon, tint)
                }
            }
        }
    }
}
