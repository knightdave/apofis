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
 * The node's pixels, drawn now from the window's view tree by a renderer of the test's own into a fresh target.
 *
 * Why not captureToImage: under Robolectric it skips Compose's forced redraw and copies the last buffer of the window's
 * surface. In a warm JVM its first capture after setContent was reproduced reading the bare window instead of the
 * composed content. That is consistent with HWUI dropping the content frame after Robolectric stamped it with a host
 * vsync earlier than the previous frame's (fake vsync plus host time minus fake time); the native drop itself is
 * inferred, not observed.
 *
 * Here every Compose layer is invalidated first, so a layer the dropped frame never painted (a blur, for one) is
 * recorded and painted again. A fresh renderer then draws one frame, its first, into a fresh reader, and
 * setWaitForPresent returns once it is presented: no older frame exists to be read, and a missing frame throws.
 */
fun SemanticsNodeInteraction.drawnPixels(): PixelMap {
    val node = fetchSemanticsNode() // waits until the rule is idle: composed, laid out
    val owner = node.root as ViewRootForTest
    owner.invalidateDescendants()
    val root = owner.view.rootView
    val content = RenderNode("drawnPixels").apply { setPosition(0, 0, root.width, root.height) }
    try {
        val canvas = content.beginRecording()
        try {
            root.draw(canvas)
        } finally {
            content.endRecording()
        }
        ImageReader.newInstance(root.width, root.height, PixelFormat.RGBA_8888, 1).use { reader ->
            val renderer = HardwareRenderer()
            try {
                renderer.setContentRoot(content)
                renderer.setSurface(reader.surface)
                val result = renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
                checkNotNull(reader.acquireNextImage()) { "drawnPixels: no frame presented (sync result $result)" }.use { image ->
                    val plane = image.planes[0]
                    val window = Bitmap.createBitmap(plane.rowStride / plane.pixelStride, root.height, Bitmap.Config.ARGB_8888)
                    try {
                        window.copyPixelsFromBuffer(plane.buffer)
                        val b = node.boundsInWindow
                        val left = b.left.roundToInt()
                        val top = b.top.roundToInt()
                        return window.asImageBitmap().toPixelMap(left, top, b.right.roundToInt() - left, b.bottom.roundToInt() - top)
                    } finally {
                        window.recycle()
                    }
                }
            } finally {
                renderer.destroy()
            }
        }
    } finally {
        content.discardDisplayList()
    }
}
