package xyz.rycerz.apofis.ui.image

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.chat.AssistantReply
import xyz.rycerz.apofis.images.Decoded
import xyz.rycerz.apofis.images.ImageFailure
import xyz.rycerz.apofis.images.ImageInput
import xyz.rycerz.apofis.images.decodeBounded
import xyz.rycerz.apofis.settings.ThemeMode
import xyz.rycerz.apofis.ui.FIXED_NOW
import xyz.rycerz.apofis.ui.chat.ChatRows
import xyz.rycerz.apofis.ui.chat.TextBlock
import xyz.rycerz.apofis.ui.chat.TextRow
import xyz.rycerz.apofis.ui.markdown.dataUrl
import xyz.rycerz.apofis.ui.shot
import xyz.rycerz.apofis.ui.windowShot

/**
 * Thumbnails, the viewer and reply images. Every decoded picture is in place before the first frame (a filled cache,
 * or a decode that returns at once), so a golden never catches a decode half done. The fixture is drawn here.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37], qualifiers = "w360dp-h760dp-xhdpi")
class ImageScreenshotTest {
    @get:Rule
    val rule = createComposeRule()

    /** Four colored quarters and a white diagonal, so crop, fit and orientation show. */
    private fun picture(w: Int, h: Int): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
        val canvas = Canvas(b)
        val paint = Paint()
        listOf(0xFF1E88E5, 0xFFE53935, 0xFF43A047, 0xFFFDD835).forEachIndexed { i, color ->
            paint.color = color.toInt()
            canvas.drawRect((i % 2) * w / 2f, (i / 2) * h / 2f, (i % 2 + 1) * w / 2f, (i / 2 + 1) * h / 2f, paint)
        }
        paint.color = android.graphics.Color.WHITE
        paint.strokeWidth = h / 20f
        canvas.drawLine(0f, 0f, w.toFloat(), h.toFloat(), paint)
    }

    private fun png(bitmap: Bitmap) = java.io.ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()

    @Test
    fun image_thumbs() {
        val cache = ThumbCache().apply {
            put("a".repeat(64), Decoded.Ok(picture(320, 240)))
            put("b".repeat(64), Decoded.Ok(picture(180, 320)))
        }
        rule.shot("image_thumbs") {
            CompositionLocalProvider(LocalThumbCache provides cache) {
                Row(Modifier.background(MaterialTheme.colorScheme.surface).padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Three 96 dp squares and their gaps fit the 360 dp row.
                    ImageThumb("a".repeat(64), 96.dp, "Image 1 of 3") {}
                    ImageThumb("b".repeat(64), 96.dp, "Image 2 of 3") {}
                    ImageThumb(null, 96.dp, "Image 3 of 3", null)
                }
            }
        }
    }

    @Test
    fun image_viewer() {
        val bitmap = picture(400, 300)
        rule.windowShot("image_viewer", hasContentDescription("Close image")) {
            ImageViewer(ViewerImage.Data(ByteArray(1), "image/png"), onClose = {}) { _, _, _ -> Decoded.Ok(bitmap) }
        }
    }

    private fun mdData(name: String, mode: ThemeMode) {
        val url = dataUrl(png(picture(300, 200)))
        val broken = "data:image/png;base64,AAAAAAAA"
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        // The real decode of the tile's own bytes, at the reply side and limits, as the tile would cache it.
        val decoded = runBlocking { decodeBounded(ctx, ImageInput.Bytes(png(picture(300, 200)), "image/png"), 1024, limits = ReplyLimits) }
        val cache = ThumbCache().apply {
            put(url, decoded)
            put(broken, Decoded.Failed(ImageFailure.NOT_AN_IMAGE))
        }
        val rows = ChatRows(ZoneOffset.UTC).rows(listOf(AssistantReply("r1", emptyList(), "A picture:\n\n![A chart]($url)\n\n![](${broken})", FIXED_NOW))).filterIsInstance<TextRow>()
        rule.shot(name, mode) {
            CompositionLocalProvider(LocalThumbCache provides cache) {
                Column(Modifier.width(360.dp).background(MaterialTheme.colorScheme.surface)) { for (row in rows) TextBlock(row) }
            }
        }
    }

    @Test
    fun md_image_data_light() = mdData("md_image_data_light", ThemeMode.LIGHT)

    @Test
    fun md_image_data_dark() = mdData("md_image_data_dark", ThemeMode.DARK)
}
