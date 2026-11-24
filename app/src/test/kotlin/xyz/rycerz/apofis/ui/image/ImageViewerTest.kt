package xyz.rycerz.apofis.ui.image

import android.graphics.Bitmap
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pinch
import androidx.test.espresso.Espresso
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.images.DecodeLimits
import xyz.rycerz.apofis.images.Decoded
import xyz.rycerz.apofis.images.ImageInput
import xyz.rycerz.apofis.images.ImageLimits
import xyz.rycerz.apofis.images.ImageStore
import xyz.rycerz.apofis.images.REPLY_FORMATS
import xyz.rycerz.apofis.images.decodeBounded
import xyz.rycerz.apofis.ui.markdown.Themed
import xyz.rycerz.apofis.ui.markdown.jpegBytes
import xyz.rycerz.apofis.ui.markdown.onebitPng
import xyz.rycerz.apofis.ui.markdown.pngBytes
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.test.core.app.ApplicationProvider

/** The full-screen viewer: bounded zoom and pan, close by Back or the button, one bitmap released on close. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class ImageViewerTest {
    @get:Rule
    val rule = createComposeRule()

    @get:Rule
    val folder = TemporaryFolder()

    private var closes = 0
    private val image = hasContentDescription("Image")

    /** A square bitmap handed over at once, so the viewer shows it in its first frames. */
    private fun square(): ViewerDecode {
        val bitmap = Bitmap.createBitmap(100, 100, Bitmap.Config.ARGB_8888)
        return { _, _, _ -> Decoded.Ok(bitmap) }
    }

    private fun open(decode: ViewerDecode = square(), viewer: ViewerImage = ViewerImage.Data(ByteArray(1), "image/png"), store: ImageStore? = null) {
        rule.setContent {
            Themed {
                CompositionLocalProvider(LocalImageStore provides store) {
                    var shown by remember { mutableStateOf(true) }
                    if (shown) ImageViewer(viewer, onClose = { closes++; shown = false }, decode)
                }
            }
        }
    }

    private fun bounds() = rule.onNode(image).fetchSemanticsNode().boundsInRoot

    @Test
    fun clampTransformBounds() {
        val viewport = Size(400f, 800f)
        val image = Size(400f, 300f)
        fun check(scale: Float, x: Float, y: Float, actual: Pair<Float, Offset>) {
            assertEquals(scale, actual.first, 0.001f)
            assertEquals(x, actual.second.x, 0.001f)
            assertEquals(y, actual.second.y, 0.001f)
        }
        check(1f, 0f, 0f, clampTransform(0.2f, Offset(50f, 50f), image, viewport)) // no zoom out, no pan
        check(5f, 0f, 0f, clampTransform(9f, Offset.Zero, image, viewport))
        // At 2x the image is 800 x 600: 200 px to pan sideways each way, none up or down (it still fits in 800).
        check(2f, 200f, 0f, clampTransform(2f, Offset(999f, 999f), image, viewport))
        check(2f, -200f, 0f, clampTransform(2f, Offset(-999f, -999f), image, viewport))
        check(2f, -10f, 0f, clampTransform(2f, Offset(-10f, 5f), image, viewport))
        // At 5x: 2000 x 1500, so 800 sideways and 350 up or down.
        check(5f, 800f, -350f, clampTransform(5f, Offset(5000f, -5000f), image, viewport))
    }

    @Test
    fun pinchChangesScale() {
        open()
        rule.waitForIdle()
        val before = bounds()
        rule.onNode(image).performTouchInput {
            pinch(center - Offset(20f, 0f), center - Offset(150f, 0f), center + Offset(20f, 0f), center + Offset(150f, 0f))
        }
        rule.waitForIdle()
        val after = bounds()
        assertTrue("$before -> $after", after.height > before.height)
    }

    @Test
    fun doubleTapToggles() {
        open()
        rule.waitForIdle()
        val whole = bounds()
        rule.onNode(image).performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        val zoomed = bounds()
        assertTrue("$whole -> $zoomed", zoomed.height > whole.height)
        rule.onNode(image).performTouchInput { doubleClick(center) }
        rule.waitForIdle()
        assertEquals(whole, bounds())
    }

    @Test
    fun backCloses() {
        open()
        rule.waitForIdle()
        Espresso.pressBack()
        rule.waitForIdle()
        assertEquals(1, closes)
        rule.onNodeWithContentDescription("Close image").assertDoesNotExist()
    }

    @Test
    fun closeButtonTargetAtLeast48dp() {
        open()
        rule.waitForIdle()
        val button = rule.onNodeWithContentDescription("Close image").fetchSemanticsNode()
        assertTrue("${button.size}", button.size.width >= 96 && button.size.height >= 96) // xhdpi: 2 px per dp
        rule.onNodeWithContentDescription("Close image").performClick()
        rule.waitForIdle()
        assertEquals(1, closes)
    }

    @Test
    fun bitmapReleasedOnClose() {
        val store = ImageStore(folder.newFolder("images"), clock = { 0L })
        val digest = runBlocking { store.put(jpegBytes(64, 48)) }!!
        val decoded = ArrayList<Bitmap>()
        open({ context, input, limits -> decodeBounded(context, input, ImageLimits.VIEW_SIDE, limits = limits).also { (it as? Decoded.Ok)?.let { ok -> decoded += ok.bitmap } } }, ViewerImage.Stored(digest), store)
        rule.waitUntil(10_000) { rule.onAllNodes(image).fetchSemanticsNodes().isNotEmpty() }
        val bitmap = decoded.single()
        assertEquals(64 to 48, bitmap.width to bitmap.height)
        assertFalse(bitmap.isRecycled)
        rule.onNodeWithContentDescription("Close image").performClick()
        rule.waitForIdle()
        assertTrue(bitmap.isRecycled)
    }

    @Test
    fun dataViewerUsesReplyLimits() {
        val seen = ArrayList<Pair<ImageInput, DecodeLimits>>()
        open({ _, input, limits -> seen += input to limits; Decoded.Ok(Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)) }, ViewerImage.Data(pngBytes(2, 2), "image/png"))
        rule.waitForIdle()
        val (input, limits) = seen.single()
        assertEquals("image/png", (input as ImageInput.Bytes).label)
        assertEquals(ImageLimits.REPLY_PIXELS, limits.maxPixels)
        assertEquals(REPLY_FORMATS, limits.formats)
    }

    /** The real decode: a data image over the reply pixel cap, or whose label its bytes do not match, fails; a fitting one shows. */
    @Test
    fun dataViewerRefusesWhatTheReplyLimitsRefuse() {
        rule.setContent {
            Themed {
                ImageViewer(ViewerImage.Data(onebitPng(2001, 2000), "image/png")) {}
                ImageViewer(ViewerImage.Data(pngBytes(8, 8), "image/jpeg")) {}
                ImageViewer(ViewerImage.Data(onebitPng(2000, 2000), "image/png")) {}
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodes(hasText("Image could not be shown")).fetchSemanticsNodes().size == 2 }
        rule.waitUntil(10_000) { rule.onAllNodes(image).fetchSemanticsNodes().size == 1 }
    }

    @Test
    fun viewerSurvivesRecreation() {
        val tester = StateRestorationTester(rule)
        val small = ViewerImage.Data(pngBytes(2, 2), "image/png")
        val large = ViewerImage.Data(ByteArray(16 * 1024 + 1), "image/png")
        lateinit var stored: MutableState<ViewerImage?>
        lateinit var data: MutableState<ViewerImage?>
        lateinit var big: MutableState<ViewerImage?>
        tester.setContent {
            stored = rememberSaveable(stateSaver = ViewerImageSaver) { mutableStateOf(null) }
            data = rememberSaveable(stateSaver = ViewerImageSaver) { mutableStateOf(null) }
            big = rememberSaveable(stateSaver = ViewerImageSaver) { mutableStateOf(null) }
        }
        stored.value = ViewerImage.Stored("cd".repeat(32))
        data.value = small
        big.value = large
        rule.waitForIdle()
        tester.emulateSavedInstanceStateRestore()
        rule.waitForIdle()
        assertEquals(ViewerImage.Stored("cd".repeat(32)), stored.value)
        assertArrayEquals(small.bytes, (data.value as ViewerImage.Data).bytes)
        assertTrue(small !== data.value) // restored from the saved state, not the same object
        assertEquals("image/png", (data.value as ViewerImage.Data).label)
        assertNull(big.value) // above 16 KiB: closed after recreation
    }

    /** A 3000 x 1 and a 1 x 3000 white strip, through the production decode: each still draws on the black page. */
    @Test
    fun aThinImageKeepsAPixel() {
        rule.setContent {
            Themed {
                ImageViewer(ViewerImage.Data(onebitPng(3000, 1, white = true), "image/png")) {}
                ImageViewer(ViewerImage.Data(onebitPng(1, 3000, white = true), "image/png")) {}
            }
        }
        rule.waitUntil(10_000) { rule.onAllNodes(image).fetchSemanticsNodes().size == 2 }
        for (i in 0..1) {
            val node = rule.onAllNodes(image)[i]
            val bounds = node.fetchSemanticsNode().size
            assertTrue("$bounds", bounds.width >= 1 && bounds.height >= 1)
            val bitmap = node.captureToImage().asAndroidBitmap()
            assertTrue("image $i draws a pixel", (0 until bitmap.width).any { x -> (0 until bitmap.height).any { y -> android.graphics.Color.red(bitmap.getPixel(x, y)) > 192 } })
        }
    }

    /** The production viewer decode asks for VIEW_SIDE: a picture wider than a reply tile's 1024 comes back as wide. */
    @Test
    fun productionViewerDecodeUsesViewSide() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val decoded = runBlocking { decodeForViewer(ctx, ImageInput.Bytes(onebitPng(2000, 10), "image/png"), ReplyLimits) } as Decoded.Ok
        assertEquals(2000, decoded.bitmap.width)
        val wide = runBlocking { decodeForViewer(ctx, ImageInput.Bytes(onebitPng(3000, 10), "image/png"), ReplyLimits) } as Decoded.Ok
        assertEquals(ImageLimits.VIEW_SIDE, wide.bitmap.width)
    }
}
