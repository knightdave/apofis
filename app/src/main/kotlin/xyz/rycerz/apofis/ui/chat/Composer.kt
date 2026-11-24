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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.ui.layout.Placeable
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.input.indirect.IndirectPointerEvent
import androidx.compose.ui.input.indirect.IndirectPointerEventType
import androidx.compose.ui.input.indirect.IndirectPointerInputModifierNode
import androidx.compose.ui.semantics.onClick
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
import xyz.rycerz.apofis.chat.DraftImage
import xyz.rycerz.apofis.ui.image.ImagePlaceholder
import xyz.rycerz.apofis.ui.image.ImageThumb
import xyz.rycerz.apofis.ui.image.LocalImageOpen
import xyz.rycerz.apofis.ui.image.ViewerImage
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
    ComposerMode.SEND, ComposerMode.STEER, ComposerMode.SEND_AFTER -> !busy
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

/** What the composer's layout needs from its composition (read during measure). */
internal class ComposerLook(
    val dims: BottomDims,
    val expanded: Boolean,
    val wantsExpand: Boolean, // Expand is composed (three lines, or expanded)
    val progress: Animatable<Float, *>,
    val lines: () -> Int,
    val expandedHeight: Dp,
    val room: ExpandRoom,
    val strip: Int, // the draft strip's natural height, px: 0 without photos
    val stripRoom: ExpandRoom, // whether the allocation leaves the strip its share (it is composed only then)
)

/** Whether the allocation leaves Expand its second 48 dp target: [now] for keys in this frame, [state] for composing it. */
internal class ExpandRoom {
    var now = true
    val state = mutableStateOf(true)
}

/**
 * The composer's parts, in this order: the card's glass, the field, the expand button (an empty box when not shown),
 * the action row (Stop before the primary button while steering or sending after the reply), Plus (the photo picker,
 * [onAttach]; disabled unless [attachEnabled]) and the strip of draft [images] (an empty box when hidden; each photo
 * has a remove control, [onRemoveImage], disabled while [busy]). They are laid out by [placeComposer]
 * with the numbers of [bottomShares]: by [Composer] on its own, or by the chat screen's bottom area together with the
 * notes, so one allocation decides every height.
 *
 * The text is the holder's [draft]; only user edits call [onDraftChange]. [onSend] asks the holder and returns whether
 * it took the draft; only then the composer collapses. The button, the IME action (only with [enterToSend]) and
 * hardware Enter (see [enterKey]) all go through it. [busy]: see [primaryEnabled]; the field also takes no edits then.
 * [subject]: what the primary action acts on (the run for Stop, Steer and Send after, the chat for Send); see [PrimaryButton].
 */
@Composable
internal fun ComposerParts(
    draft: String,
    mode: ComposerMode,
    onDraftChange: (String) -> Unit,
    onSend: () -> Boolean,
    onStop: () -> Unit,
    busy: Boolean = false,
    enterToSend: Boolean = false,
    expanded: Boolean = false,
    onExpandedChange: (Boolean) -> Unit = {},
    expandedHeight: Dp = Dp.Unspecified,
    backdrop: BackdropState? = null,
    subject: Any? = null,
    transcript: Boolean = true,
    clock: () -> Long = System::nanoTime,
    images: List<DraftImage> = emptyList(),
    attachEnabled: Boolean = true,
    onAttach: () -> Unit = {},
    onRemoveImage: (Long) -> Unit = {},
): ComposerLook {
    if (BuildConfig.DEBUG) SideEffect { ChromeProbe.composerBackdropId = System.identityHashCode(backdrop) }
    val shape = RoundedCornerShape(20.dp)
    val scheme = MaterialTheme.colorScheme
    val colors = ApofisTheme.colors
    val style = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface)
    val density = LocalDensity.current
    val dims = remember(density, style, transcript) { density.bottomDims(style, transcript) }
    val progress = remember { Animatable(if (expanded) 1f else 0f) } // 0 collapsed, 1 expanded
    LaunchedEffect(expanded) { if (expanded) progress.animateTo(1f, tween(Motion.COMPOSER_EXPAND, easing = EaseOutCubic)) else progress.snapTo(0f) }
    val submit = { if (onSend()) onExpandedChange(false) }
    var lines by remember { mutableIntStateOf(1) }
    val manyLines by remember { derivedStateOf { showsExpand(lines, expanded = false) } }
    val pad = with(density) { (dims.fieldPadding / 2f).toDp() }

    val fillOnly = backdrop?.layer == null || !ApofisTheme.blur
    // Without blur the glass shows the content under it: a page backplate keeps text and icons at their contrast.
    val backplate = if (fillOnly) Modifier.background(scheme.surface, shape) else Modifier
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
    val wantsExpand = expanded || manyLines
    val room = remember { ExpandRoom() }
    Box {
        // Composed only while the allocation has room for it: without room it is removed, so a focused Expand gives up
        // focus; until that recomposition, its key filter lets no key through.
        if (wantsExpand && room.state.value) {
            ApofisIconButton(
                modifier = Modifier.onPreviewKeyEvent { !room.now },
                onClick = { onExpandedChange(!expanded) },
                contentDescription = stringResource(if (expanded) R.string.composer_collapse else R.string.composer_expand),
                visualSize = 24.dp,
            ) {
                Icon(painterResource(if (expanded) R.drawable.lucide_minimize_2 else R.drawable.lucide_maximize_2), null, Modifier.size(16.dp), tint = colors.expandIcon)
            }
        }
    }
    Row {
        if (mode == ComposerMode.STEER || mode == ComposerMode.SEND_AFTER) RoundButton(R.drawable.lucide_square, R.string.chat_stop, enabled = true, filled = false, tag = STOP_TAG, onClick = onStop)
        PrimaryButton(mode, busy, subject, submit, onStop, clock)
    }
    ApofisIconButton(onClick = onAttach, contentDescription = stringResource(R.string.chat_attach), enabled = attachEnabled, visualSize = 24.dp) {
        Icon(painterResource(R.drawable.lucide_plus), null, Modifier.size(20.dp), tint = colors.muted)
    }
    val stripRoom = remember { ExpandRoom() }
    Box {
        // Composed only while the allocation gives it its share, like Expand: a hidden thumbnail keeps no focus.
        if (images.isNotEmpty() && stripRoom.state.value) {
            Row(
                Modifier.onPreviewKeyEvent { !stripRoom.now }.semantics { traversalIndex = -2f }.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                images.forEachIndexed { i, image -> key(image.id) { DraftThumb(image, i, images.size, busy, onRemoveImage) } }
            }
        }
    }
    val strip = if (images.isEmpty()) 0 else with(density) { (REMOVE_RISE + DRAFT_THUMB + STRIP_GAP).roundToPx() }
    return ComposerLook(dims, expanded, wantsExpand, progress, { lines }, expandedHeight, room, strip, stripRoom)
}

private val DRAFT_THUMB = 88.dp
private val STRIP_GAP = 8.dp // between the strip and the field's row
// The remove target rises this far above its photo, so the 48 dp target ends above the photo's centre (a tap there opens it).
private val REMOVE_RISE = 11.dp

/**
 * One draft photo: ready (opens the viewer when the chat provides one), processing (the spinner) or failed (the
 * placeholder), with its remove control at the top end: an 18 dp chip 4 dp from the corner, in a 48 dp target that
 * rises [REMOVE_RISE] above the photo; disabled while [busy].
 */
@Composable
private fun DraftThumb(image: DraftImage, index: Int, count: Int, busy: Boolean, onRemove: (Long) -> Unit) {
    val colors = ApofisTheme.colors
    val open = LocalImageOpen.current
    Box {
        Box(Modifier.padding(top = REMOVE_RISE).size(DRAFT_THUMB)) {
            val digest = image.digest
            if (image.processing) {
                Box(Modifier.matchParentSize().clip(MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                    ImagePlaceholder(Modifier.matchParentSize(), stringResource(R.string.chat_image_processing), glyph = false, glyphSize = 0.dp)
                    Spinner(24.dp, MaterialTheme.colorScheme.primary)
                }
            } else {
                ImageThumb(digest, DRAFT_THUMB, stringResource(R.string.chat_image, index + 1, count), if (digest != null && open != null) ({ open(ViewerImage.Stored(digest)) }) else null)
            }
        }
        ApofisIconButton(
            onClick = { onRemove(image.id) },
            contentDescription = stringResource(R.string.chat_image_remove),
            modifier = Modifier.align(Alignment.TopEnd),
            enabled = !busy,
            visualSize = 48.dp,
        ) {
            Box(Modifier.fillMaxSize().padding(top = REMOVE_RISE + 4.dp, end = 4.dp), contentAlignment = Alignment.TopEnd) {
                Box(Modifier.size(18.dp).background(colors.thumbChip, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(painterResource(R.drawable.lucide_x), null, Modifier.size(12.dp), tint = colors.thumbChipGlyph)
                }
            }
        }
    }
}

/** The composer's six measurables (see [ComposerParts]), measured with [shares] and placed from [y]. */
internal class ComposerPlacement(
    private val glass: Placeable,
    private val field: Placeable,
    private val expand: Placeable?,
    private val buttons: Placeable,
    private val plus: Placeable,
    private val strip: Placeable?,
    private val s: BottomShares,
    private val side: Int,
    private val marginX: Int,
) {
    fun Placeable.PlacementScope.place(width: Int, y: Int) {
        glass.placeRelative(marginX, y + s.marginTop)
        val inner = y + s.marginTop + s.paddingTop
        strip?.placeRelative(side, inner)
        val top = inner + s.strip // the row's top
        plus.placeRelative(side, top + s.row - plus.height)
        field.placeRelative(side + plus.width, top + s.row - field.height)
        expand?.placeRelative(width - side - expand.width, top)
        buttons.placeRelative(width - side - buttons.width, top + s.row - buttons.height)
    }
}

/**
 * Lays out the composer's parts in one [bottomShares] allocation: the bar, notes and transcript inputs come from the
 * caller ([bar] own height, [notes] natural height, [unsent]); [available] is the safe band. Returns the shares and the
 * placement; every part is measured with exactly its share (the field once, through the allocation).
 */
internal fun MeasureScope.measureComposer(
    parts: List<Measurable>,
    look: ComposerLook,
    width: Int,
    available: Int,
    bar: Int = 0,
    notes: Int = 0,
    unsent: Boolean = false,
): Pair<BottomShares, ComposerPlacement> {
    val d = look.dims
    val marginX = MARGIN_X.roundToPx()
    val side = marginX + CARD_PADDING.roundToPx()
    val inner = (width - 2 * side).coerceAtLeast(0)
    val buttons = parts[3].measure(Constraints(0, inner, 0, d.target))
    val expandBox = parts[2].measure(Constraints(0, inner, 0, d.target))
    val plus = parts[4].measure(Constraints(0, inner, 0, d.target))
    val fieldWidth = (inner - plus.width - maxOf(expandBox.width, buttons.width)).coerceAtLeast(0)
    var field: Placeable? = null
    val expansion = if (look.expanded) Expansion(look.progress.value, look.lines(), if (look.expandedHeight.isSpecified) look.expandedHeight.roundToPx() else Int.MAX_VALUE) else null
    val s = bottomShares(available, bar, notes, unsent, look.wantsExpand, expansion, d, look.strip) { room ->
        parts[1].measure(Constraints(fieldWidth, fieldWidth, 0, room)).also { field = it }.height // 1 to 5 lines (maxLines), scrolling inside
    }
    val f = field ?: parts[1].measure(Constraints.fixed(fieldWidth, s.field))
    val glass = parts[0].measure(Constraints.fixed((width - 2 * marginX).coerceAtLeast(0), s.card))
    val fits = s.actions >= 2 * d.target
    look.room.now = fits
    if (look.room.state.value != fits) look.room.state.value = fits
    val expand = expandBox.takeIf { fits && it.height > 0 } // dropped before Send when both do not fit
    val stripShown = s.strip > 0
    look.stripRoom.now = stripShown
    if (look.stripRoom.state.value != stripShown) look.stripRoom.state.value = stripShown
    val strip = parts[5].measure(Constraints(0, inner, 0, (s.strip - STRIP_GAP.roundToPx()).coerceAtLeast(0))).takeIf { stripShown }
    return s to ComposerPlacement(glass, f, expand, buttons, plus, strip, s, side, marginX)
}

/**
 * The composer on its own (previews, goldens, component tests): the parts of [ComposerParts] in one [bottomShares]
 * allocation with no bar, notes or transcript; it takes at most its constraints' height and reports what it occupies.
 * Collapsed, the text shows 1 to 5 lines (it scrolls inside the field); expanded, the field takes the room (or
 * [expandedHeight]), growing over [Motion.COMPOSER_EXPAND] ms; collapsing snaps.
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
    subject: Any? = null,
    clock: () -> Long = System::nanoTime,
    images: List<DraftImage> = emptyList(),
    attachEnabled: Boolean = true,
    onAttach: () -> Unit = {},
    onRemoveImage: (Long) -> Unit = {},
) {
    var look: ComposerLook? = null
    Layout(
        content = { look = ComposerParts(draft, mode, onDraftChange, onSend, onStop, busy, enterToSend, expanded, onExpandedChange, expandedHeight, backdrop, subject, transcript = false, clock = clock, images = images, attachEnabled = attachEnabled, onAttach = onAttach, onRemoveImage = onRemoveImage) },
        modifier = modifier.fillMaxWidth().semantics { isTraversalGroup = true },
    ) { measurables, constraints ->
        val available = if (constraints.hasBoundedHeight) constraints.maxHeight else 1_000_000
        val (s, placement) = measureComposer(measurables, look!!, constraints.maxWidth, available)
        layout(constraints.maxWidth, s.composer) { with(placement) { place(constraints.maxWidth, 0) } }
    }
}

private val MARGIN_X = 12.dp

/**
 * The primary button: Send, Steer or Stop, one stable control (one focus target, one labelled node) whatever the mode.
 * Two times on [clock]: `changedAt`, when the shown action or its [subject] changed (the run for Stop and Steer, the
 * chat for Send), and `downAt`, the latest press-down on the button (a pointer, an indirect pointer, the first key-down
 * of an activation key). A click from a pointer or a key counts only when its press came after the change; an
 * accessibility click, which has no press, runs the action shown when it arrives. No press is tracked beyond that, so
 * a lost release or a cancelled pointer can never block a later press.
 */
@Composable
private fun PrimaryButton(mode: ComposerMode, busy: Boolean, subject: Any?, submit: () -> Unit, onStop: () -> Unit, clock: () -> Long) {
    val action = if (mode == ComposerMode.SEND_DISABLED) ComposerMode.SEND else mode
    val changedAt = remember(action, subject) { clock() }
    val downAt = remember { longArrayOf(Long.MIN_VALUE) }
    val now = rememberUpdatedState(clock)
    val stop = mode == ComposerMode.STOP
    val enabled = primaryEnabled(mode, busy)
    val run = if (stop) onStop else submit
    val labelId = when (mode) {
        ComposerMode.STEER -> R.string.chat_steer
        ComposerMode.STOP -> R.string.chat_stop
        ComposerMode.SEND_AFTER -> R.string.chat_send_after
        else -> R.string.chat_send
    }
    val label = stringResource(labelId)
    val pressed = { downAt[0] = now.value() }
    RoundButton(
        if (stop) R.drawable.lucide_square else R.drawable.lucide_arrow_up,
        labelId,
        enabled = enabled,
        tag = if (stop) STOP_TAG else SEND_TAG,
        modifier = Modifier
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.changedToDownIgnoreConsumed() }) pressed()
                    }
                }
            }
            .then(IndirectPressElement(pressed))
            .onPreviewKeyEvent {
                if (it.key in activationKeys && it.type == KeyEventType.KeyDown && it.nativeKeyEvent.repeatCount == 0) pressed()
                false
            }
            // The accessibility click: no press of its own, so it runs what is shown now.
            .then(if (enabled) Modifier.semantics { onClick(label) { run(); true } } else Modifier),
        onClick = { if (downAt[0] > changedAt) run() }, // every physical activation
    )
}

private val activationKeys = setOf(Key.Enter, Key.NumPadEnter, Key.Spacebar, Key.DirectionCenter)

/** Notes indirect pointer presses (touch navigation devices), which the clickable turns into clicks without pointerInput. */
private data class IndirectPressElement(val pressed: () -> Unit) : ModifierNodeElement<IndirectPressNode>() {
    override fun create() = IndirectPressNode(pressed)

    override fun update(node: IndirectPressNode) {
        node.pressed = pressed
    }
}

private class IndirectPressNode(var pressed: () -> Unit) : Modifier.Node(), IndirectPointerInputModifierNode {
    override fun onIndirectPointerEvent(event: IndirectPointerEvent, pass: PointerEventPass) {
        if (pass == PointerEventPass.Initial && event.type == IndirectPointerEventType.Press) pressed()
    }

    override fun onCancelIndirectPointerInput() = Unit
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
