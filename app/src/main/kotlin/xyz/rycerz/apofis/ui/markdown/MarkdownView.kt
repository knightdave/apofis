package xyz.rycerz.apofis.ui.markdown

import xyz.rycerz.apofis.ui.theme.sidewaysScroll
import android.content.ClipData
import androidx.annotation.StringRes
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.delay
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.platform.Clipboard
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.isSpecified
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import xyz.rycerz.apofis.R
import kotlinx.coroutines.launch
import xyz.rycerz.apofis.ui.theme.ApofisIconButton
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import xyz.rycerz.apofis.ui.theme.ChatText
import xyz.rycerz.apofis.ui.theme.scaled
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import xyz.rycerz.apofis.images.Decoded
import xyz.rycerz.apofis.images.ImageFailure
import xyz.rycerz.apofis.images.ImageInput
import xyz.rycerz.apofis.images.decodeBounded
import xyz.rycerz.apofis.ui.image.ImagePlaceholder
import xyz.rycerz.apofis.ui.image.LocalImageOpen
import xyz.rycerz.apofis.ui.image.ReplyLimits
import xyz.rycerz.apofis.ui.image.ViewerImage
import xyz.rycerz.apofis.ui.image.rememberDecoded

/**
 * One display chunk of a reply. Links open only through [onLink], on a tap; nothing is fetched. [tableScroll] is
 * shared by every slice of one table, so they scroll sideways together.
 */
@Composable
fun MarkdownChunk(
    chunk: DisplayChunk,
    onLink: (String) -> Unit,
    modifier: Modifier = Modifier,
    tableScroll: ScrollState = rememberScrollState(),
    source: () -> String? = { null },
) {
    val body = scaled(ChatText.assistant)
    val link by rememberUpdatedState(onLink)
    val open: (String) -> Unit = { link(it) }
    when (chunk) {
        is DisplayChunk.Blocks -> Blocks(chunk.blocks, body, MaterialTheme.colorScheme.onSurface, false, open, modifier)
        is DisplayChunk.TableSlice -> Table(chunk.table, chunk.from, chunk.to, chunk.widths, chunk.first, chunk.last, tableScroll, open, source, modifier)
        is DisplayChunk.Literal -> Text(chunk.text, modifier.fillMaxWidth(), style = body, color = MaterialTheme.colorScheme.onSurface)
        is DisplayChunk.Code -> Text(
            chunk.text,
            modifier.sidewaysScroll(rememberScrollState(), LocalDensity.current),
            style = scaled(ChatText.code),
            color = MaterialTheme.colorScheme.onSurface,
            softWrap = false,
        )
    }
}

/**
 * Blocks 0.9 times the body size apart, or [itemGap] apart inside a list item; [strike] and [color] come from a checked
 * task item or a quote.
 */
@Composable
private fun Blocks(blocks: List<MdBlock>, body: TextStyle, color: Color, strike: Boolean, onLink: (String) -> Unit, modifier: Modifier = Modifier, itemGap: Dp? = null) {
    val gap = itemGap ?: with(LocalDensity.current) { (body.fontSize * 0.9f).toDp() }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        for (b in blocks) Block(b, body, color, strike, onLink)
    }
}

@Composable
private fun Block(b: MdBlock, body: TextStyle, color: Color, strike: Boolean, onLink: (String) -> Unit) {
    val colors = ApofisTheme.colors
    when (b) {
        is MdBlock.Paragraph -> MdTextView(b.text, body, color, strike, onLink)
        is MdBlock.Heading -> {
            val size = body.fontSize
            val style = when (b.level) {
                1 -> body.copy(fontSize = size * 1.45f, fontWeight = FontWeight.Medium, lineHeight = 1.3.em)
                2 -> body.copy(fontSize = size * 1.27f, fontWeight = FontWeight.Medium, lineHeight = 1.35.em)
                3 -> body.copy(fontSize = size * 1.13f, fontWeight = FontWeight.Medium, lineHeight = 1.4.em)
                4 -> body
                5 -> body.copy(fontSize = size * 0.94f)
                else -> body.copy(fontSize = size * 0.88f)
            }
            val tone = if (b.level >= 6 && color == MaterialTheme.colorScheme.onSurface) colors.muted else color
            MdTextView(b.text, style, tone, strike, onLink, Modifier.padding(top = 4.dp).semantics { heading() })
        }
        is MdBlock.Quote -> {
            val bar = colors.quoteBar
            Blocks(
                b.blocks,
                body,
                colors.quoteText,
                strike,
                onLink,
                Modifier
                    .drawBehind {
                        val w = 3.dp.toPx()
                        drawRoundRect(bar, Offset(if (layoutDirection == LayoutDirection.Rtl) size.width - w else 0f, 0f), Size(w, size.height), CornerRadius(1.5.dp.toPx()))
                    }
                    .padding(start = 12.dp),
            )
        }
        is MdBlock.ListBlock -> ListView(b, body, color, strike, onLink)
        is MdBlock.Table -> Table(b, 0, b.rows.size, remember(b) { tableWidths(b) }, first = true, last = true, rememberScrollState(), onLink, { null }) // unreachable: tables are their own logical blocks
        is MdBlock.Image -> ImageTile(b, onLink)
        MdBlock.Rule -> Box(Modifier.fillMaxWidth().padding(vertical = 12.dp).height(1.dp).background(colors.hairline))
    }
}

/** Text with its inline styles; inline code gets a rounded box drawn behind each line fragment, no characters added. */
@Composable
private fun MdTextView(text: MdText, style: TextStyle, color: Color, strike: Boolean, onLink: (String) -> Unit, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Unspecified) {
    val primary = MaterialTheme.colorScheme.primary
    val pressed = ApofisTheme.colors.pressWash
    val focused = ApofisTheme.colors.selectedRow
    val annotated = remember(text, primary, pressed, focused, strike) { annotate(text, LinkTones(primary, pressed, focused), strike, onLink) }
    val codes = remember(text) { text.spans.filter { it.kind == SpanKind.CODE } }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val fill = ApofisTheme.colors.inlineCodeFill
    val border = ApofisTheme.colors.hairline
    val codeSize = with(LocalDensity.current) { (style.fontSize * 0.86f).toPx() }
    Text(
        annotated,
        modifier
            .fillMaxWidth()
            .then(
                if (codes.isEmpty()) Modifier else Modifier.drawBehind {
                    val l = layout ?: return@drawBehind
                    val padX = 4.dp.toPx()
                    val padY = 3.dp.toPx()
                    val radius = CornerRadius(6.dp.toPx())
                    val stroke = Stroke(1.dp.toPx())
                    for (span in codes) {
                        if (span.end > l.layoutInput.text.length) continue
                        for (line in l.getLineForOffset(span.start)..l.getLineForOffset(span.end - 1)) {
                            val a = maxOf(span.start, l.getLineStart(line))
                            val z = minOf(span.end, l.getLineEnd(line, visibleEnd = true))
                            if (a >= z) continue
                            val left = l.getBoundingBox(a).left - padX
                            val right = l.getBoundingBox(z - 1).right + padX
                            val baseline = l.getLineBaseline(line)
                            // Glyph bounds of the code font: about 0.9 em above the baseline and 0.25 em below.
                            val top = baseline - codeSize * 0.9f - padY
                            val bottom = baseline + codeSize * 0.25f + padY
                            drawRoundRect(fill, Offset(left, top), Size(right - left, bottom - top), radius)
                            drawRoundRect(border, Offset(left, top), Size(right - left, bottom - top), radius, style = stroke)
                        }
                    }
                },
            ),
        style = style,
        color = color,
        textAlign = align,
        onTextLayout = { layout = it },
    )
}

/** Link text, and the backgrounds that show a held press and keyboard focus (the link handler draws no indication). */
private data class LinkTones(val text: Color, val pressed: Color, val focused: Color)

private fun annotate(text: MdText, tones: LinkTones, strike: Boolean, onLink: (String) -> Unit): AnnotatedString = buildAnnotatedString {
    append(text.text)
    for (span in text.spans) {
        when (span.kind) {
            SpanKind.STRONG -> addStyle(SpanStyle(fontWeight = FontWeight.Medium), span.start, span.end)
            SpanKind.EMPHASIS -> addStyle(SpanStyle(fontStyle = FontStyle.Italic), span.start, span.end)
            SpanKind.STRIKE -> addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), span.start, span.end)
            SpanKind.CODE -> addStyle(SpanStyle(fontFamily = FontFamily.Monospace, fontSize = 0.86.em), span.start, span.end)
            SpanKind.LINK -> {
                val url = span.url ?: continue
                val link = SpanStyle(color = tones.text, textDecoration = TextDecoration.Underline)
                val styles = TextLinkStyles(
                    style = link,
                    focusedStyle = link.copy(background = tones.focused),
                    pressedStyle = link.copy(background = tones.pressed),
                )
                addLink(LinkAnnotation.Clickable(url, styles) { onLink(url) }, span.start, span.end)
            }
        }
    }
    if (strike) addStyle(SpanStyle(textDecoration = TextDecoration.LineThrough), 0, text.text.length)
}

@Composable
private fun ListView(list: MdBlock.ListBlock, body: TextStyle, color: Color, strike: Boolean, onLink: (String) -> Unit) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val widest = if (list.ordered) "${list.start + list.items.size - 1}." else "•"
    val textWidth = with(density) { remember(widest, body) { measurer.measure(widest, body).size.width }.toDp() }
    val marker = if (list.items.any { it.task }) maxOf(textWidth, 16.dp) else textWidth
    val line = with(density) { (body.fontSize * 1.5f).toDp() }
    val muted = ApofisTheme.colors.muted
    // A loose list's items and their blocks are as far apart as any blocks; a tight list's sit close.
    val gap = if (list.tight) ItemGap else with(density) { (body.fontSize * 0.9f).toDp() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) {
        list.items.forEachIndexed { i, item ->
            // A task item reads as checked or not, but is not a control: no click action, no press state.
            val task = if (item.task) {
                Modifier.semantics(mergeDescendants = true) { toggleableState = if (item.checked) ToggleableState.On else ToggleableState.Off }
            } else {
                Modifier
            }
            Row(Modifier.fillMaxWidth().then(task)) {
                Box(Modifier.width(marker), contentAlignment = Alignment.TopEnd) {
                    if (item.task) {
                        TaskBox(item.checked, Modifier.padding(top = (line - 16.dp) / 2))
                    } else {
                        Text(if (list.ordered) "${list.start + i}." else "•", style = body, color = color)
                    }
                }
                Box(Modifier.width(6.dp))
                val done = item.task && item.checked
                Blocks(item.blocks, body, if (done) muted else color, strike || done, onLink, Modifier.weight(1f), gap)
            }
        }
    }
}

private val ItemGap = 4.dp

/** A read-only task checkbox: no press state. */
@Composable
private fun TaskBox(checked: Boolean, modifier: Modifier) {
    val colors = ApofisTheme.colors
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier
            .size(16.dp)
            .then(if (checked) Modifier.background(colors.muted, shape) else Modifier.border(1.5.dp, colors.muted, shape)),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(painterResource(R.drawable.lucide_check), null, Modifier.size(12.dp), tint = MaterialTheme.colorScheme.surface)
    }
}

@Composable
private fun ImageTile(image: MdBlock.Image, onLink: (String) -> Unit) {
    if (image.data.isNotEmpty()) return DataImageTile(image.data, image.alt)
    val colors = ApofisTheme.colors
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .clip(shape)
            .background(colors.codeFill)
            .then(if (image.url.isNotEmpty()) Modifier.clickable(role = Role.Button) { onLink(image.url) } else Modifier)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(painterResource(R.drawable.lucide_image), null, Modifier.size(20.dp), tint = colors.muted)
        Box(Modifier.width(8.dp))
        Text(image.alt.ifEmpty { stringResource(R.string.md_image) }, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
    }
}

/**
 * A reply's `data:` image: 240 dp tall at the text column's width before and after decoding, the picture fitted
 * inside. Decoded off the main thread within the reply limits, memoised by its URL in [LocalThumbCache]; a tap opens
 * the viewer ([LocalImageOpen]). A failure shows the image-off glyph and is not clickable. The URL never reaches the
 * link handler, and only `clickable` is used, so a sideways drag stays the drawer's.
 */
@Composable
private fun DataImageTile(url: String, alt: String) {
    val colors = ApofisTheme.colors
    val context = LocalContext.current
    val decoded = rememberDecoded(url) {
        val bytes = withContext(Dispatchers.Default) { dataImageBytes(url) }
        if (bytes == null) Decoded.Failed(ImageFailure.NOT_AN_IMAGE) else decodeBounded(context, ImageInput.Bytes(bytes, dataImageType(url)), REPLY_SIDE, limits = ReplyLimits)
    }
    val open = LocalImageOpen.current
    val scope = rememberCoroutineScope()
    val label = alt.ifEmpty { stringResource(R.string.md_image) }
    val tile = Modifier.fillMaxWidth().height(240.dp).clip(MaterialTheme.shapes.medium)
    if (decoded !is Decoded.Ok) return ImagePlaceholder(tile, label, glyph = decoded != null, glyphSize = 20.dp) {
        Text(stringResource(R.string.md_image_failed), style = MaterialTheme.typography.bodyMedium, color = colors.muted)
    }
    Image(
        remember(decoded.bitmap) { decoded.bitmap.asImageBitmap() },
        label,
        tile
            .background(colors.thumbPlaceholder)
            .then(
                if (open == null) {
                    Modifier
                } else {
                    // A fresh array for the viewer, decoded off the main thread; the decoder's own array is never shared.
                    Modifier.clickable(role = Role.Button) { scope.launch { withContext(Dispatchers.Default) { dataImageBytes(url) }?.let { open(ViewerImage.Data(it, dataImageType(url))) } } }
                },
            ),
        contentScale = ContentScale.Fit,
    )
}

private const val REPLY_SIDE = 1024

private val TableRadius = 12.dp
private val CellPadX = 12.dp
private val MaxColumn = 280.dp

/**
 * Rows [from, to) of [table] with the toolbar and header on the first slice. The frame is drawn so that slices of one
 * table join into one rounded block: only the first has the top corners, only the last the bottom ones.
 */
@Composable
private fun Table(
    table: MdBlock.Table,
    from: Int,
    to: Int,
    widths: List<Int>,
    first: Boolean,
    last: Boolean,
    scroll: ScrollState,
    onLink: (String) -> Unit,
    source: () -> String?,
    modifier: Modifier = Modifier,
) {
    val colors = ApofisTheme.colors
    val headerStyle = scaled(TextStyle(fontSize = 13.sp, fontWeight = FontWeight.Medium, lineHeight = 1.4.em))
    val bodyStyle = scaled(TextStyle(fontSize = 13.5.sp, lineHeight = 1.4.em))
    val measurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val charWidth = with(density) { remember(bodyStyle) { measurer.measure("0", bodyStyle).size.width }.toDp() }
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val columns = widths.map { (charWidth * it + CellPadX * 2).coerceIn(48.dp, MaxColumn) }
        fun width(cols: List<Dp>) = cols.fold(0.dp) { a, w -> a + w } + 1.dp * (cols.size - 1).coerceAtLeast(0)
        val total = width(columns)
        // A narrow table shares the spare width, each column up to the cap; what is left stays outside the frame.
        val fitted = if (total < maxWidth && columns.isNotEmpty()) columns.map { minOf(MaxColumn, it + (maxWidth - total) / columns.size) } else columns
        Column(Modifier.width(minOf(maxWidth, width(fitted))).blockFrame(first, last, colors.tableBorder, TableRadius)) {
            if (first) {
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp).background(colors.codeHeaderFill).padding(start = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(painterResource(R.drawable.lucide_table), null, Modifier.size(16.dp), tint = colors.codeLabel)
                    Box(Modifier.width(6.dp))
                    Text(stringResource(R.string.md_table), Modifier.weight(1f), style = TextStyle(fontSize = 12.sp), color = colors.codeLabel, maxLines = 1)
                    CopyButton(R.string.md_table_copy, source)
                }
            }
            Column(Modifier.sidewaysScroll(scroll, density)) {
                if (first) TableRow(table.header, table.align, fitted, headerStyle, MaterialTheme.colorScheme.onSurface, colors.tableHeaderFill, colors.tableDivider, onLink)
                for (r in from until to) TableRow(table.rows[r], table.align, fitted, bodyStyle, colors.tableBody, Color.Unspecified, colors.tableDivider, onLink)
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<MdText>, align: List<Align>, widths: List<Dp>, style: TextStyle, color: Color, fill: Color, divider: Color, onLink: (String) -> Unit) {
    Row(
        Modifier
            .height(IntrinsicSize.Min)
            .then(if (fill.isSpecified) Modifier.background(fill) else Modifier)
            .drawBehind { drawRect(divider, size = Size(size.width, 1.dp.toPx())) }, // the line above every row
    ) {
        cells.forEachIndexed { c, cell ->
            if (c > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(divider))
            val textAlign = when (align.getOrNull(c)) {
                Align.CENTER -> TextAlign.Center
                Align.END -> TextAlign.End
                else -> TextAlign.Start
            }
            Box(Modifier.width(widths[c]).padding(horizontal = CellPadX, vertical = 8.dp)) {
                MdTextView(cell, style, color, false, onLink, align = textAlign)
            }
        }
    }
}

/** Clips to a rounded frame and draws its outline; an edge that continues in the next or previous slice is open. */
internal fun Modifier.blockFrame(first: Boolean, last: Boolean, color: Color, radius: Dp) = clipToBounds().drawWithCache {
    val r = radius.toPx()
    val stroke = 1.dp.toPx()
    val top = if (first) 0f else -r
    val bottom = if (last) size.height else size.height + r
    val clip = Path().apply { addRoundRect(RoundRect(0f, top, size.width, bottom, CornerRadius(r))) }
    val outline = Path().apply {
        addRoundRect(RoundRect(stroke / 2, top + stroke / 2, size.width - stroke / 2, bottom - stroke / 2, CornerRadius(r - stroke / 2)))
    }
    onDrawWithContent {
        clipPath(clip) { this@onDrawWithContent.drawContent() }
        drawPath(outline, color, style = Stroke(stroke))
    }
}

/**
 * The most text put on the clipboard. ClipData carries text inline through one Binder transaction, whose buffer is
 * 1 MiB shared by the whole process, as UTF-16 (2 bytes per character): 100,000 characters is about 200 KB, a fifth of
 * it. Longer text is refused, never cut.
 */
internal const val MAX_CLIP_CHARS = 100_000

/** How long the Copy result stays beside the button. */
internal const val COPY_NOTE_MS = 2000L

internal enum class CopyResult(@StringRes val note: Int) {
    COPIED(R.string.copy_done),
    TOO_LARGE(R.string.copy_too_large),
    FAILED(R.string.copy_failed),
}

/** Puts [text] on [clipboard] as plain text, whole or not at all; a rejected write (for example by Binder) is caught. */
internal suspend fun copyText(clipboard: Clipboard, text: String): CopyResult {
    if (text.length > MAX_CLIP_CHARS) return CopyResult.TOO_LARGE
    return try {
        clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(null, text)))
        CopyResult.COPIED
    } catch (e: CancellationException) {
        throw e
    } catch (_: Exception) {
        CopyResult.FAILED
    }
}

/**
 * A header action: copies [source] (the raw source of the whole block) to the clipboard, no toast. What happened
 * (copied, too large, failed) shows beside the button for [COPY_NOTE_MS] and is announced.
 */
@Composable
internal fun CopyButton(@StringRes description: Int, source: () -> String?) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val current by rememberUpdatedState(source)
    var note by remember { mutableStateOf<CopyResult?>(null) }
    var taps by remember { mutableIntStateOf(0) }
    LaunchedEffect(taps) {
        delay(COPY_NOTE_MS)
        note = null
    }
    note?.let {
        Text(
            stringResource(it.note),
            Modifier.padding(start = 8.dp).semantics { liveRegion = LiveRegionMode.Polite },
            style = TextStyle(fontSize = 12.sp),
            color = ApofisTheme.colors.codeLabel,
            maxLines = 1,
        )
    }
    ApofisIconButton(
        onClick = {
            val text = current() ?: return@ApofisIconButton
            scope.launch {
                note = copyText(clipboard, text)
                taps++
            }
        },
        contentDescription = stringResource(description),
        visualSize = 24.dp,
    ) {
        Icon(painterResource(R.drawable.lucide_copy), null, Modifier.size(16.dp), tint = ApofisTheme.colors.codeLabel)
    }
}
