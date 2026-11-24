package xyz.rycerz.apofis.ui.markdown

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.time.ZoneOffset
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlinx.coroutines.runBlocking
import mockwebserver3.MockWebServer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.images.Decoded
import xyz.rycerz.apofis.images.ImageFailure
import xyz.rycerz.apofis.images.ImageInput
import xyz.rycerz.apofis.images.ImageLimits
import xyz.rycerz.apofis.images.ImageStore
import xyz.rycerz.apofis.images.decodeBounded
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.FIXED_NOW
import xyz.rycerz.apofis.ui.chat.ChatRows
import xyz.rycerz.apofis.ui.chat.TextBlock
import xyz.rycerz.apofis.ui.chat.TextRow
import xyz.rycerz.apofis.ui.image.ImageThumb
import xyz.rycerz.apofis.ui.image.LocalImageOpen
import xyz.rycerz.apofis.ui.image.LocalImageStore
import xyz.rycerz.apofis.ui.image.LocalThumbCache
import xyz.rycerz.apofis.ui.image.ThumbCache
import xyz.rycerz.apofis.ui.image.ViewerImage
import xyz.rycerz.apofis.ui.theme.ApofisTheme
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertNull
import xyz.rycerz.apofis.run.Connection
import xyz.rycerz.apofis.run.LiveReply
import xyz.rycerz.apofis.ui.chat.Work
import xyz.rycerz.apofis.ui.chat.finalImage
import xyz.rycerz.apofis.ui.chat.withLive
import xyz.rycerz.apofis.ui.image.rememberDecoded

/** An encoded PNG of one color, made by the platform encoder. */
internal fun pngBytes(w: Int, h: Int, color: Int = Color.RED): ByteArray {
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
    return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray().also { bitmap.recycle() }
}

/** A JPEG of one color. */
internal fun jpegBytes(w: Int, h: Int): ByteArray {
    val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
    return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray().also { bitmap.recycle() }
}

/**
 * A black (or [white]) 1-bit greyscale PNG written by hand, so a large image stays a few hundred bytes; [filler] adds a
 * text chunk of that many letters (ignored by decoders) to make the encoding longer.
 */
internal fun onebitPng(w: Int, h: Int, filler: Int = 0, seed: Int = 0, white: Boolean = false): ByteArray {
    val out = ByteArrayOutputStream()
    val data = DataOutputStream(out)
    data.write(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A))
    fun chunk(type: String, body: ByteArray) {
        val typed = type.toByteArray(Charsets.US_ASCII) + body
        data.writeInt(body.size)
        data.write(typed)
        data.writeInt(CRC32().apply { update(typed) }.value.toInt())
    }
    chunk("IHDR", ByteBuffer.allocate(13).putInt(w).putInt(h).put(1).put(0).put(0).put(0).put(0).array())
    if (filler > 0) chunk("tEXt", "Comment".toByteArray() + 0 + "$seed".padEnd(filler, 'x').toByteArray())
    val row = 1 + (w + 7) / 8
    val raw = ByteArray(row * h) { if (white && it % row != 0) -1 else 0 } // filter byte 0, then the pixels of each row
    val deflater = Deflater(9).apply { setInput(raw); finish() }
    val packed = ByteArrayOutputStream()
    val buffer = ByteArray(64 * 1024)
    while (!deflater.finished()) packed.write(buffer, 0, deflater.deflate(buffer))
    chunk("IDAT", packed.toByteArray())
    chunk("IEND", ByteArray(0))
    return out.toByteArray()
}

internal fun dataUrl(bytes: ByteArray, mime: String = "image/png") = "data:$mime;base64," + Base64.getEncoder().encodeToString(bytes)

@Composable
internal fun Themed(mode: ThemeMode = ThemeMode.LIGHT, content: @Composable () -> Unit) =
    ApofisTheme(mode, dynamicColor = false, pureBlack = false, reduceMotion = true, blur = false, content = content)

/**
 * Images in replies through the production row builder ([ChatRows] and [TextBlock]): a `data:` image of an accepted
 * type is decoded on the phone within the reply limits and never reaches the link handler; an http(s) image keeps its
 * tile and browser hand-off and is never fetched. The cache's accounted weight stays bounded.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class MarkdownImageTest {
    @get:Rule
    val rule = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private val links = ArrayList<String>()
    private val opened = ArrayList<ViewerImage>()

    private fun rows(text: String) = ChatRows(ZoneOffset.UTC).rows(listOf(AssistantReply("r1", emptyList(), text, FIXED_NOW))).filterIsInstance<TextRow>()

    private fun blocks(text: String) = rows(text).flatMap { (it.chunk as? DisplayChunk.Blocks)?.blocks.orEmpty() }

    private fun show(vararg texts: String, cache: ThumbCache? = null, open: Boolean = true) {
        val rows = texts.flatMap { rows(it) }
        rule.setContent {
            Themed {
                CompositionLocalProvider(LocalThumbCache provides cache, LocalImageOpen provides if (open) { image -> opened += image } else null) {
                    Column(Modifier.width(360.dp)) { for (row in rows) TextBlock(row, onLink = { links += it }) }
                }
            }
        }
    }

    private val failed = hasText("Image could not be shown")

    private fun exists(m: SemanticsMatcher) =
        rule.onAllNodes(m).fetchSemanticsNodes().isNotEmpty() || rule.onAllNodes(m, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun waitFor(m: SemanticsMatcher) = rule.waitUntil(10_000) { exists(m) }

    private val dot = dataUrl(pngBytes(4, 3))

    /** The cache's weight by the brief's rule, from what it holds: max(64 KiB, the bitmap's bytes or 64 KiB) plus two per key character. */
    private fun expectedWeight(cache: ThumbCache) = cache.snapshot().entries.sumOf { (key, value) ->
        maxOf(64 * 1024, (value as? Decoded.Ok)?.bitmap?.allocationByteCount ?: 64 * 1024) + 2 * key.length
    }

    /** Whether the node's own drawing holds a pixel darker than a quarter grey (the black fixture on the light fill). */
    private fun drawsDark(m: SemanticsMatcher): Boolean {
        val bitmap = rule.onNode(m).captureToImage().asAndroidBitmap()
        return (0 until bitmap.width).any { x -> (0 until bitmap.height).any { y -> android.graphics.Color.red(bitmap.getPixel(x, y)) < 64 } }
    }

    @Test
    fun dataUrlBecomesImage() {
        for (mime in listOf("image/png", "image/jpeg", "image/gif", "image/webp")) {
            val url = "data:$mime;base64,iVBORw0KGgo="
            assertEquals(listOf(MdBlock.Image("dot", "", url)), blocks("![dot]($url)"))
        }
        // Only as a whole paragraph: an inline data image is its alt text, with no link.
        assertEquals(listOf(MdBlock.Paragraph(MdText("see dot here", emptyList()))), blocks("see ![dot]($dot) here"))
    }

    @Test
    fun otherDataMimeStaysEmpty() {
        for (url in listOf(
            "data:image/svg+xml;base64,PHN2Zz4=", "data:text/html;base64,PGI+", "data:image/jpg;base64,AAAA",
            "data:image/PNG;base64,AAAA", "data:image/png,AAAA", "data:image/png;base64,AA%41", "data:image/png;base64,",
        )) {
            assertEquals(url, listOf(MdBlock.Image("x", "", "")), blocks("![x]($url)"))
        }
        assertEquals(null, safeUrl(dot)) // the browser path never sees it
    }

    @Test
    fun httpTileNeverFetches() {
        val server = MockWebServer()
        server.start()
        try {
            val url = server.url("/diagram.png").toString()
            show("![A diagram]($url)", cache = ThumbCache())
            rule.waitForIdle()
            rule.onNode(hasText("A diagram") and hasClickAction()).performClick()
            rule.waitForIdle()
            assertEquals(listOf(url), links)
            assertNull(server.takeRequest(500, TimeUnit.MILLISECONDS)) // nor later
            assertEquals(0, server.requestCount)
            assertTrue(opened.isEmpty())
        } finally {
            server.close()
        }
    }

    @Test
    fun dataTileNeverCallsOnLink() {
        show("![A dot]($dot)", cache = ThumbCache())
        waitFor(hasContentDescription("A dot") and hasClickAction())
        rule.onNode(hasContentDescription("A dot") and hasClickAction()).performClick()
        rule.waitUntil(10_000) { opened.isNotEmpty() }
        assertTrue("$links", links.isEmpty())
        val image = opened.single() as ViewerImage.Data
        assertArrayEquals(pngBytes(4, 3), image.bytes)
        assertEquals("image/png", image.label)
    }

    @Test
    fun dataTileWithoutAViewerIsNotClickable() {
        val cache = ThumbCache()
        show("![A dot]($dot)", cache = cache, open = false)
        rule.waitUntil(10_000) { cache.get(dot) != null }
        rule.waitForIdle()
        assertTrue(exists(hasContentDescription("A dot")))
        assertFalse(exists(hasContentDescription("A dot") and hasClickAction()))
    }

    @Test
    fun oversizedDataStaysLiteral() {
        val url = "data:image/png;base64," + "A".repeat(20_000)
        val rows = rows("![big]($url)")
        assertTrue(rows.none { (it.chunk as? DisplayChunk.Blocks)?.blocks.orEmpty().any { b -> b is MdBlock.Image } })
        assertTrue(rows.any { (it.chunk as? DisplayChunk.Literal)?.text?.contains("data:image/png;base64,AAAA") == true })
    }

    @Test
    fun corruptDataShowsFailure() {
        val urls = listOf("data:image/png;base64,AAAAAAAA", "data:image/png;base64,QUJD" + "A") // not an image; bad padding
        val cache = ThumbCache()
        show("![broken 0](${urls[0]})", "![broken 1](${urls[1]})", cache = cache)
        rule.waitUntil(10_000) { rule.onAllNodes(failed, useUnmergedTree = true).fetchSemanticsNodes().size == 2 }
        assertFalse(exists(hasClickAction()))
        assertEquals(listOf(true, true), urls.map { cache.get(it) is Decoded.Failed })
        assertTrue(links.isEmpty() && opened.isEmpty())
        // The merged node says the image failed (its alt, then why), offers no action and keeps the tile's height.
        for (i in 0..1) {
            val node = rule.onNode(hasContentDescription("broken $i, Image could not be shown")).fetchSemanticsNode()
            assertFalse(node.config.contains(SemanticsActions.OnClick))
            assertEquals(240f, node.size.height / 2f, 0.5f)
        }
    }

    @Test
    fun extremeAspectDataImage() {
        val tall = dataUrl(onebitPng(1, 3000))
        val wide = dataUrl(onebitPng(3000, 1))
        val cache = ThumbCache()
        show("![tall]($tall)", "![wide]($wide)", cache = cache)
        for ((name, url) in listOf("tall" to tall, "wide" to wide)) {
            waitFor(hasContentDescription(name) and hasClickAction())
            val bitmap = (cache.get(url) as Decoded.Ok).bitmap
            assertEquals(1024, maxOf(bitmap.width, bitmap.height)) // the reply side
            assertEquals(1, minOf(bitmap.width, bitmap.height))
            assertEquals(240f, rule.onNode(hasContentDescription(name) and hasClickAction()).fetchSemanticsNode().size.height / 2f, 0.5f)
            assertTrue("$name draws a pixel", drawsDark(hasContentDescription(name) and hasClickAction()))
        }
    }

    @Test
    fun dataImageOverPixelCapShowsFailure() {
        val url = dataUrl(onebitPng(2001, 2000)) // 4,002,000 pixels: over REPLY_PIXELS
        assertTrue("block of ${url.length}", "![big]($url)".length < 20_000)
        assertTrue(2001L * 2000 > ImageLimits.REPLY_PIXELS)
        val atCap = dataUrl(onebitPng(2000, 2000)) // control: the same image at the cap decodes
        val cache = ThumbCache()
        show("![big]($url)", "![cap]($atCap)", cache = cache)
        waitFor(failed)
        waitFor(hasContentDescription("cap") and hasClickAction())
        assertEquals(Decoded.Failed(ImageFailure.TOO_LARGE), cache.get(url))
    }

    @Test
    fun dataImageWrongFormatShowsFailure() {
        val urls = listOf(dataUrl(jpegBytes(8, 8), "image/png"), dataUrl(pngBytes(8, 8), "image/gif"))
        val cache = ThumbCache()
        show("![wrong 0](${urls[0]})", "![wrong 1](${urls[1]})", cache = cache)
        rule.waitUntil(10_000) { rule.onAllNodes(failed, useUnmergedTree = true).fetchSemanticsNodes().size == 2 }
        assertEquals(List(2) { Decoded.Failed(ImageFailure.NOT_AN_IMAGE) }, urls.map { cache.get(it) })
    }

    @Test
    fun dataImageDecodedOnceWhileResident() {
        val cache = ThumbCache()
        var shown by mutableStateOf(true)
        val rows = rows("![A dot]($dot)")
        rule.setContent {
            Themed {
                CompositionLocalProvider(LocalThumbCache provides cache) {
                    Column(Modifier.width(360.dp)) { if (shown) for (row in rows) TextBlock(row) }
                }
            }
        }
        rule.waitUntil(10_000) { cache.get(dot) != null }
        val first = (cache.get(dot) as Decoded.Ok).bitmap
        shown = false
        rule.waitForIdle()
        rule.mainClock.autoAdvance = false
        shown = true
        Snapshot.sendApplyNotifications()
        rule.mainClock.advanceTimeByFrame()
        // Drawn from the cache in its first frame back, and no second decode replaced the entry.
        assertTrue(exists(hasContentDescription("A dot") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Image)))
        rule.mainClock.autoAdvance = true
        rule.waitForIdle()
        assertSame(first, (cache.get(dot) as Decoded.Ok).bitmap)
        assertFalse(first.isRecycled)
    }

    @Test
    fun failuresCannotFillCache() {
        val cache = ThumbCache()
        repeat(1_000) { cache.put("data:image/png;base64,AAAA$it", Decoded.Failed(ImageFailure.NOT_AN_IMAGE)) }
        assertEquals(expectedWeight(cache), cache.size())
        assertTrue("weight ${cache.size()}", cache.size() <= 16 * 1024 * 1024)
        assertTrue("entries ${cache.snapshot().size}", cache.snapshot().size <= 256)
        // Long keys are charged too: fewer of them fit.
        repeat(1_000) { cache.put("data:image/png;base64,$it" + "A".repeat(19_000), Decoded.Failed(ImageFailure.UNREADABLE)) }
        assertEquals(expectedWeight(cache), cache.size())
        assertTrue("weight ${cache.size()}", cache.size() <= 16 * 1024 * 1024)
        assertTrue("entries ${cache.snapshot().size}", cache.snapshot().size < 256)
    }

    @Test
    fun smallImagesWithLongKeysBounded() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val urls = (0 until 1_000).map { dataUrl(onebitPng(1, 1, filler = 11_200, seed = it)) }
        assertTrue(urls.all { it.length in 15_000 until 20_000 } && urls.toSet().size == 1_000)
        // The fixture is a real 1 x 1 image.
        val decoded = runBlocking { decodeBounded(ctx, ImageInput.Bytes(dataImageBytes(urls[0])!!, "image/png"), 1024) } as Decoded.Ok
        assertEquals(1 to 1, decoded.bitmap.width to decoded.bitmap.height)
        val cache = ThumbCache()
        for (url in urls) cache.put(url, Decoded.Ok(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)))
        assertEquals(expectedWeight(cache), cache.size())
        assertTrue("weight ${cache.size()}", cache.size() <= 16 * 1024 * 1024)
        assertTrue("entries ${cache.snapshot().size}", cache.snapshot().size < 256) // the keys' charge leaves room for fewer
        assertTrue(cache.get(urls.last()) != null && cache.get(urls.first()) == null) // the oldest went first
    }

    @Test
    fun tileHeightFixed() {
        val url = dataUrl(pngBytes(100, 20))
        rule.mainClock.autoAdvance = false
        show("![A strip]($url)")
        val before = rule.onNode(hasContentDescription("A strip")).fetchSemanticsNode()
        assertFalse("not decoded yet", exists(hasContentDescription("A strip") and hasClickAction()))
        assertEquals(240f, before.size.height / 2f, 0.5f)
        rule.mainClock.autoAdvance = true
        waitFor(hasContentDescription("A strip") and hasClickAction())
        assertEquals(240f, rule.onNode(hasContentDescription("A strip") and hasClickAction()).fetchSemanticsNode().size.height / 2f, 0.5f)
        assertEquals(before.size.width, rule.onNode(hasContentDescription("A strip") and hasClickAction()).fetchSemanticsNode().size.width)
    }

    @Test
    fun thumbMissingIsPlaceholderAndNotClickable() {
        val store = ImageStore(folder.newFolder("images"), clock = { 0L })
        val present = runBlocking { store.put(jpegBytes(40, 30)) }!!
        val clicks = ArrayList<String>()
        rule.setContent {
            Themed {
                CompositionLocalProvider(LocalImageStore provides store, LocalThumbCache provides ThumbCache()) {
                    Column {
                        ImageThumb(null, 88.dp, "none") { clicks += "none" }
                        ImageThumb("ab".repeat(32), 88.dp, "missing") { clicks += "missing" }
                        ImageThumb(present, 88.dp, "present") { clicks += "present" }
                    }
                }
            }
        }
        waitFor(hasContentDescription("present") and hasClickAction())
        assertEquals(2, rule.onAllNodes(hasContentDescription("Image unavailable")).fetchSemanticsNodes().size)
        assertFalse(exists(hasContentDescription("Image unavailable") and hasClickAction()))
        rule.onAllNodes(hasContentDescription("Image unavailable")).fetchSemanticsNodes().forEach {
            rule.onNode(SemanticsMatcher("node ${it.id}") { n -> n.id == it.id }).performClick()
        }
        rule.onNode(hasContentDescription("present")).performSemanticsAction(SemanticsActions.OnClick)
        assertEquals(listOf("present"), clicks)
    }

    @Test
    fun noNetworkClassReferenced() {
        val root = File("src/main/kotlin/xyz/rycerz/apofis/ui")
        val sources = listOf("image", "markdown").flatMap { File(root, it).walk().filter { f -> f.extension == "kt" }.toList() } + File(root, "chat/ChatScreen.kt")
        assertTrue(sources.size >= 5 && sources.all { it.isFile })
        for (file in sources) {
            val text = file.readText()
            // As calls or types: ChatScreen's own "openConnection" callback (the Connection screen) is not a network call.
            for (word in listOf("\\bOkHttpClient\\b", "\\bURL\\(", "HttpURLConnection", "\\.openConnection\\b", "\\bokhttp3\\b", "\\.toURL\\b", "\\.openStream\\b", "Socket\\b", "\\.openInputStream\\b")) {
                assertFalse("${file.name} names $word", Regex(word).containsMatchIn(text))
            }
        }
    }

    // ---- fix round 1 ----

    /** The composition's own memo, with a decoder that never leaves the composition dispatcher: one decode while shown, hidden and shown. */
    @Test
    fun rememberDecodedDecodesOnceWhileResident() {
        val cache = ThumbCache()
        var decodes = 0
        var shown by mutableStateOf(true)
        rule.setContent {
            CompositionLocalProvider(LocalThumbCache provides cache) {
                if (shown) rememberDecoded("k") { decodes++; Decoded.Ok(Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)) }
            }
        }
        rule.waitForIdle()
        shown = false
        rule.waitForIdle()
        shown = true
        rule.waitForIdle()
        assertEquals(1, decodes)
    }

    /** BUSY is shown and not cached: composed anew, the image decodes again; any other failure stays cached. */
    @Test
    fun busyIsNotCached() {
        val cache = ThumbCache()
        val decodes = HashMap<String, Int>()
        val results = HashMap<String, Decoded?>()
        var shown by mutableStateOf(true)
        rule.setContent {
            CompositionLocalProvider(LocalThumbCache provides cache) {
                if (shown) {
                    for ((key, reason) in listOf("busy" to ImageFailure.BUSY, "bad" to ImageFailure.NOT_AN_IMAGE)) {
                        results[key] = rememberDecoded(key) { decodes.merge(key, 1, Int::plus); Decoded.Failed(reason) }
                    }
                }
            }
        }
        rule.waitForIdle()
        assertEquals(Decoded.Failed(ImageFailure.BUSY), results["busy"]) // shown as a failure
        shown = false
        rule.waitForIdle()
        shown = true
        rule.waitForIdle()
        assertEquals(mapOf("busy" to 2, "bad" to 1), decodes)
        assertNull(cache.get("busy"))
    }

    private fun tiles(text: String) = rows(text).map { row -> (row.chunk as? DisplayChunk.Blocks)?.blocks.orEmpty().sumOf(::dataTiles) }

    private fun dataTiles(b: MdBlock): Int = when (b) {
        is MdBlock.Image -> if (b.data.isNotEmpty()) 1 else 0
        is MdBlock.Quote -> b.blocks.sumOf(::dataTiles)
        is MdBlock.ListBlock -> b.items.sumOf { it.blocks.sumOf(::dataTiles) }
        else -> 0
    }

    @Test
    fun fiftyImageParagraphsAreFiftyRows() {
        val tiles = tiles((1..50).joinToString("\n\n") { "![p$it]($dot)" })
        assertEquals(List(50) { 1 }, tiles)
    }

    @Test
    fun aTightListOfImagesHasOneTilePerRowAndKeepsItsNumbers() {
        val rows = rows((1..24).joinToString("\n") { "$it. ![i$it]($dot)" })
        assertEquals(List(24) { 1 }, rows.map { row -> (row.chunk as DisplayChunk.Blocks).blocks.sumOf(::dataTiles) })
        assertEquals((1..24).toList(), rows.map { ((it.chunk as DisplayChunk.Blocks).blocks.single() as MdBlock.ListBlock).start })
    }

    @Test
    fun aQuoteOfImagesIsCut() {
        val tiles = tiles((1..6).joinToString("\n>\n") { "> ![q$it]($dot)" })
        assertEquals(List(6) { 1 }, tiles)
    }

    @Test
    fun oneListItemWithManyImagesIsLiteral() {
        val parses = Work.imageDecodes
        val rows = rows("- ![a]($dot)\n\n" + (1..6).joinToString("\n\n") { "  ![b$it]($dot)" })
        assertTrue(rows.isNotEmpty() && rows.all { it.chunk is DisplayChunk.Literal })
        show("- ![a]($dot)\n\n" + (1..6).joinToString("\n\n") { "  ![b$it]($dot)" })
        rule.waitForIdle()
        assertEquals("no decode at all", parses, Work.imageDecodes)
    }

    /** A finished reply has no open block: its last image paragraph over the live cutoff becomes a tile with nothing appended. */
    @Test
    fun aFinishedReplysLastImageIsParsed() {
        val url = dataUrl(onebitPng(4, 4, filler = 8_000))
        assertTrue("${url.length}", url.length in 10_000..19_000)
        val text = "Here:\n\n![late]($url)" // no line break after it
        val live = LiveReply("run_1", "running", text, emptyList(), null, Connection.LIVE)
        val reply = AssistantReply("r1", emptyList(), text, FIXED_NOW)
        val chat = ChatRows(ZoneOffset.UTC)
        val streaming = chat.rows(withLive(emptyList(), live) { FIXED_NOW }).filterIsInstance<TextRow>()
        assertTrue("literal while live", streaming.any { (it.chunk as? DisplayChunk.Literal)?.text?.contains("![late]") == true })
        val stored = chat.rows(listOf(reply)).filterIsInstance<TextRow>()
        assertEquals(1, stored.sumOf { (it.chunk as? DisplayChunk.Blocks)?.blocks.orEmpty().sumOf(::dataTiles) })
        // Control: over the parser's own bound it stays literal when finished too.
        val big = "Here:\n\n![big](data:image/png;base64," + "A".repeat(20_000) + ")"
        val finished = ChatRows(ZoneOffset.UTC).rows(listOf(reply.copy(text = big))).filterIsInstance<TextRow>()
        assertTrue(finished.none { (it.chunk as? DisplayChunk.Blocks)?.blocks.orEmpty().any { b -> b is MdBlock.Image } })
    }

    /** What the parser calls one image paragraph is one, however it is written; anything more stays literal. */
    @Test
    fun aFinishedReplysLastImageIsWhatTheParserSays() {
        val url = dataUrl(onebitPng(4, 4, filler = 8_000))
        fun finished(last: String) = ChatRows(ZoneOffset.UTC).rows(listOf(AssistantReply("r1", emptyList(), "Here:\n\n$last", FIXED_NOW))).filterIsInstance<TextRow>()
        fun finishedTiles(last: String) = finished(last).sumOf { (it.chunk as? DisplayChunk.Blocks)?.blocks.orEmpty().sumOf(::dataTiles) }
        assertEquals("one leading space", 1, finishedTiles(" ![late]($url)"))
        assertEquals("an escaped closing bracket in the alt", 1, finishedTiles("![a\\]b]($url)"))
        assertEquals("a title", 1, finishedTiles("![late]($url \"A title\")"))
        // An image and more text in the block: the literal rows of today, not a parsed paragraph.
        assertTrue(finished("![late]($url)\nand more text").any { (it.chunk as? DisplayChunk.Literal)?.text?.contains("and more text") == true })
    }

    /** Over the parser's bounds a finished last block is judged on its range alone: nothing is copied or parsed. */
    @Test
    fun anOversizedFinalBlockIsNeverCopied() {
        fun source(length: Int, char: Char) = object : CharSequence {
            override val length = length
            override fun get(index: Int) = char
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = throw AssertionError("copied")
            override fun toString(): String = throw AssertionError("copied")
        }
        for (text in listOf(source(25_000, 'x'), source(10_000, '['))) {
            val block = LogicalBlock(0, 0, text.length, BlockKind.TEXT, "", stable = false, fenceClosed = false, BlockMode.LITERAL, text.length)
            assertNull(finalImage(text, block))
        }
    }
}
