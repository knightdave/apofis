package xyz.rycerz.apofis.ui

import android.graphics.Bitmap
import android.graphics.HardwareRenderer
import android.graphics.PixelFormat
import android.graphics.RenderNode
import android.media.ImageReader
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.ViewRootForTest
import androidx.compose.ui.test.SemanticsNodeInteraction
import kotlin.math.roundToInt

/**
 * The node's pixels, drawn now from the window's current display lists by a renderer of the test's own.
 *
 * Why not captureToImage: under Robolectric it skips Compose's forced redraw and copies the last buffer of the window's
 * surface. Robolectric stamps each frame with a host vsync (fake vsync plus host time minus fake time), which can come out
 * earlier than the previous frame's; HWUI then counts the frame as already drawn and drops it, so the surface keeps the
 * frame from before the change (the bare window right after setContent) until something invalidates again.
 *
 * Here a fresh renderer draws one frame, its first, which HWUI never skips, and setWaitForPresent returns only once that
 * frame is in a fresh reader: no older frame exists to be read, and a missing frame throws instead of passing.
 */
fun SemanticsNodeInteraction.drawnPixels(): PixelMap {
    val node = fetchSemanticsNode() // waits until the rule is idle: composed, laid out
    val root = (node.root as ViewRootForTest).view.rootView
    val content = RenderNode("drawnPixels").apply { setPosition(0, 0, root.width, root.height) }
    root.draw(content.beginRecording())
    content.endRecording()
    val reader = ImageReader.newInstance(root.width, root.height, PixelFormat.RGBA_8888, 1)
    val renderer = HardwareRenderer()
    try {
        renderer.setContentRoot(content)
        renderer.setSurface(reader.surface)
        renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
        reader.acquireNextImage().use { image ->
            val plane = image.planes[0]
            val window = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, root.height, Bitmap.Config.ARGB_8888)
            window.copyPixelsFromBuffer(plane.buffer)
            val b = node.boundsInWindow
            val left = b.left.roundToInt()
            val top = b.top.roundToInt()
            return Bitmap.createBitmap(window, left, top, b.right.roundToInt() - left, b.bottom.roundToInt() - top).asImageBitmap().toPixelMap()
        }
    } finally {
        renderer.destroy()
        reader.close()
        content.discardDisplayList()
    }
}
