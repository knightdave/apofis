package xyz.rycerz.apofis.images

import android.app.Application
import android.content.ContentProvider
import android.content.ContentValues
import android.content.ContextWrapper
import android.content.pm.PackageManager
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.CancellationSignal
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import xyz.rycerz.apofis.images.ImageFailure.BUSY
import xyz.rycerz.apofis.images.ImageFailure.NOT_AN_IMAGE
import xyz.rycerz.apofis.images.ImageFailure.TOO_LARGE
import xyz.rycerz.apofis.images.ImageFailure.UNREADABLE

private const val OTHER = "com.other.files"
private const val OWN_REGISTERED = "xyz.rycerz.apofis.files"
private const val OWN_MERGED = "xyz.rycerz.apofis.androidx-startup" // WorkManager's merged startup provider
private const val USER_INFO = "0@com.other.files"

/** A provider of another package; each URI path names a route set by the test. */
class RouteProvider : ContentProvider() {
    companion object {
        val routes = ConcurrentHashMap<String, () -> AssetFileDescriptor?>()
        val opens = ConcurrentHashMap<String, AtomicInteger>()
        val threads = CopyOnWriteArrayList<Thread>()
    }

    override fun openAssetFile(uri: Uri, mode: String, signal: CancellationSignal?): AssetFileDescriptor? {
        val path = uri.lastPathSegment!!
        threads += Thread.currentThread()
        opens.getOrPut(path) { AtomicInteger() }.incrementAndGet()
        return routes.getValue(path)()
    }

    override fun openAssetFile(uri: Uri, mode: String): AssetFileDescriptor? = openAssetFile(uri, mode, null)
    override fun onCreate() = true
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0
}

private typealias Decoder = (ImageDecoder.Source, ImageDecoder.OnHeaderDecodedListener) -> Bitmap

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class DecodeTest {
    private val ctx: Application = ApplicationProvider.getApplicationContext()

    /** Run first in tearDown: release every hold and close every FIFO writer. */
    private val releases = CopyOnWriteArrayList<() -> Unit>()

    /** Run after the workers are joined: close FIFO readers, unlink FIFOs. */
    private val disposals = CopyOnWriteArrayList<() -> Unit>()
    private val holdExpired = AtomicBoolean()

    // Deadlines and the gate wait run on this clock, stepped by hand: nothing waits in real time for them.
    private val clock = TestCoroutineScheduler()
    private val virtual = UnconfinedTestDispatcher(clock)

    private val real: Decoder = ImageDecoder::decodeBitmap

    @Before
    fun setUp() {
        assertTrue("workers left over from an earlier test", workers().isEmpty())
        RouteProvider.routes.clear()
        RouteProvider.opens.clear()
        RouteProvider.threads.clear()
        register(OTHER, "com.other")
        // Robolectric keys providers by component: one package per authority, or a later one replaces an earlier.
        register(USER_INFO, "com.other.user")
        register(OWN_REGISTERED, ctx.packageName)
        assertEquals("com.other", ctx.packageManager.resolveContentProvider(OTHER, 0)?.packageName)
    }

    @After
    fun tearDown() = runBlocking {
        try {
            releases.forEach { it() }
            withTimeout(10_000) { workers().joinAll() }
        } finally {
            // A failed release or join cannot skip closing both FIFO ends and unlinking them.
            disposals.forEach { runCatching { it() } }
        }
        assertFalse("a 10 s hold expired: the test did not release it", holdExpired.get())
        // Every success, failure, timeout and cancellation above has deleted its temp file.
        assertEquals(emptyList<String>(), temps())
    }

    private fun workers() = decodeWork.coroutineContext.job.children.toList()

    private fun register(authority: String, pkg: String) {
        val info = ProviderInfo().apply {
            this.authority = authority
            packageName = pkg
            name = RouteProvider::class.java.name
            exported = true
        }
        Robolectric.buildContentProvider(RouteProvider::class.java).create(info)
        shadowOf(ctx.packageManager).addOrUpdateProvider(info)
    }

    private fun temps() = ctx.cacheDir.list()!!.filter { it.startsWith("decode-") && it.endsWith(".tmp") }.sorted()
    private fun tempLengths() = temps().map { File(ctx.cacheDir, it).length() }
    private fun uri(path: String, authority: String = OTHER) = Uri.parse("content://$authority/$path")
    private fun opens(path: String) = RouteProvider.opens[path]?.get() ?: 0
    private fun route(path: String, open: () -> AssetFileDescriptor?) { RouteProvider.routes[path] = open }

    private fun fileAfd(bytes: ByteArray, declared: Long = AssetFileDescriptor.UNKNOWN_LENGTH): AssetFileDescriptor {
        val file = File.createTempFile("source", ".bin", ctx.filesDir).apply { writeBytes(bytes) }
        return AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, declared)
    }

    /** Waits for an observable state another thread produces, at most 10 s; expiry fails the test. */
    private suspend fun until(what: String, condition: () -> Boolean) {
        try {
            withTimeout(10_000) { while (!condition()) delay(1) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("not reached within 10 s: $what")
        }
    }

    /** True when some thread is inside the copy loop's file read: a FileInputStream read frame above copyCapped. */
    private fun copyBlockedInRead() = Thread.getAllStackTraces().values.any { stack ->
        val read = stack.indexOfFirst { it.className == "java.io.FileInputStream" && it.methodName.startsWith("read") }
        read >= 0 && stack.drop(read + 1).any { it.className.startsWith("xyz.rycerz.apofis.images.") && it.methodName.startsWith("copyCapped") }
    }

    /** A 10 s hold released by the test; expiry marks the test failed and never lets the fixture go on. */
    private inner class Hold {
        val entered = CompletableDeferred<Unit>()
        private val latch = CountDownLatch(1)

        init { releases += ::release }

        fun await() {
            entered.complete(Unit)
            if (!latch.await(10, TimeUnit.SECONDS)) {
                holdExpired.set(true)
                error("hold expired")
            }
        }

        fun release() = latch.countDown()
    }

    /**
     * A route whose open returns the read side of a FIFO (Robolectric's pipe is a plain file, which never blocks).
     * Both ends are opened here: the test's read-write end keeps a writer, so the read-only open does not wait and
     * a read blocks until [end]. [hold] holds the open before it returns.
     */
    private inner class Stall(path: String, prefix: ByteArray = ByteArray(0), hold: Hold? = null) {
        val opened = CompletableDeferred<Unit>()
        private val fifo = File(ctx.filesDir, "fifo-$path")
        private val writer: RandomAccessFile
        val reader: ParcelFileDescriptor

        init {
            fifo.delete()
            check(ProcessBuilder("mkfifo", fifo.path).start().waitFor() == 0)
            writer = RandomAccessFile(fifo, "rw") // O_RDWR on a FIFO opens without waiting for a reader
            reader = ParcelFileDescriptor.open(fifo, ParcelFileDescriptor.MODE_READ_ONLY)
            writer.write(prefix)
            route(path) {
                hold?.await()
                opened.complete(Unit)
                AssetFileDescriptor(reader, 0, AssetFileDescriptor.UNKNOWN_LENGTH)
            }
            releases += ::end
            disposals += {
                runCatching { reader.close() }
                fifo.delete()
            }
        }

        fun feed(bytes: ByteArray) = writer.write(bytes)

        /** Bytes written and not yet read by anyone (FIONREAD); never consumes them. */
        fun unread() = FileInputStream(writer.fd).available()

        fun end() { runCatching { writer.close() } }
    }

    /** A route whose open blocks until the test releases it. */
    private inner class BlockingOpen(path: String) {
        val hold = Hold()
        val opened get() = hold.entered

        init {
            route(path) {
                hold.await()
                fileAfd(png(4, 4))
            }
        }
    }

    /** A decoder that holds its caller until the test releases it, then decodes for real. */
    private inner class Held {
        val hold = Hold()
        val entered get() = hold.entered
        val calls = AtomicInteger()
        @Volatile var bitmap: Bitmap? = null
        val decoder: Decoder = { source, listener ->
            calls.incrementAndGet()
            hold.await()
            ImageDecoder.decodeBitmap(source, listener).also { bitmap = it }
        }

        fun release() = hold.release()
    }

    private fun counting(calls: AtomicInteger, inner: Decoder = real): Decoder = { s, l -> calls.incrementAndGet(); inner(s, l) }

    private suspend fun decode(
        input: ImageInput,
        side: Int = ImageLimits.VIEW_SIDE,
        limits: DecodeLimits = DecodeLimits(),
        decoder: Decoder = real,
        software: Boolean = true,
    ) = decodeBounded(ctx, input, side, software, limits, decoder)

    private fun CoroutineScope.onClock(input: ImageInput, decoder: Decoder = real): Deferred<Decoded> =
        async(virtual) { decode(input, decoder = decoder) }

    private fun step(ms: Long) {
        clock.advanceTimeBy(ms)
        clock.runCurrent()
    }

    private suspend fun <T> within10s(block: suspend () -> T): T = withTimeout(10_000) { block() }
    private fun Decoded.reason() = (this as? Decoded.Failed)?.reason
    private fun Decoded.bitmap() = (this as Decoded.Ok).bitmap

    // Fixtures, all built here; each builder checks what it claims, once.

    private fun bounds(bytes: ByteArray) = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        .also { BitmapFactory.decodeByteArray(bytes, 0, bytes.size, it) }.let { it.outWidth to it.outHeight }

    private fun png(w: Int, h: Int, color: Int = Color.RED, config: Bitmap.Config = Bitmap.Config.ARGB_8888): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, config).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            .also { assertEquals(w to h, bounds(it)) }
    }

    private fun jpeg(w: Int, h: Int, color: Int = Color.GREEN): ByteArray {
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
            .also { assertEquals(w to h, bounds(it)) }
    }

    /** A real PNG header with the given size and a few compressed bytes: what a decompression bomb looks like at first. */
    private fun pngHeader(w: Int, h: Int): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        fun chunk(type: String, data: ByteArray) = DataOutputStream(out).run {
            val body = type.toByteArray() + data
            writeInt(data.size)
            write(body)
            writeInt(CRC32().apply { update(body) }.value.toInt())
        }
        chunk("IHDR", ByteBuffer.allocate(13).putInt(w).putInt(h).put(8).put(2).put(0).put(0).put(0).array())
        val deflater = Deflater().apply { setInput(ByteArray(4096)); finish() }
        val idat = ByteArray(1024).let { it.copyOf(deflater.deflate(it)) }
        chunk("IDAT", idat)
        chunk("IEND", ByteArray(0))
        return out.toByteArray().also {
            assertTrue(it.size < 1024)
            assertEquals(w to h, bounds(it))
        }
    }

    private class Stop : RuntimeException()

    /** What the platform's header says about animation. */
    private fun animated(bytes: ByteArray): Boolean {
        var animated = false
        runCatching { ImageDecoder.decodeBitmap(ImageDecoder.createSource(bytes)) { _, info, _ -> animated = info.isAnimated; throw Stop() } }
        return animated
    }

    /** GIF, palette 0 red and 1 blue, one frame per entry of [frames]; LZW with a clear code before each pixel. */
    private fun gif(w: Int, h: Int, frames: List<Int>): ByteArray {
        val o = ByteArrayOutputStream()
        fun le(v: Int) { o.write(v and 0xff); o.write(v shr 8 and 0xff) }
        o.write("GIF89a".toByteArray()); le(w); le(h); o.write(0x80); o.write(0); o.write(0)
        o.write(byteArrayOf(0xff.toByte(), 0, 0, 0, 0, 0xff.toByte()))
        o.write(byteArrayOf(0x21, 0xff.toByte(), 11)); o.write("NETSCAPE2.0".toByteArray()); o.write(byteArrayOf(3, 1, 0, 0, 0))
        for (color in frames) {
            o.write(byteArrayOf(0x21, 0xf9.toByte(), 4, 0, 10, 0, 0, 0))
            o.write(0x2c); le(0); le(0); le(w); le(h); o.write(0)
            o.write(2)
            val codes = (0 until w * h).flatMap { listOf(4, color) } + 5
            val data = ByteArrayOutputStream()
            var acc = 0
            var n = 0
            for (code in codes) {
                acc = acc or (code shl n); n += 3
                while (n >= 8) { data.write(acc and 0xff); acc = acc shr 8; n -= 8 }
            }
            if (n > 0) data.write(acc and 0xff)
            val d = data.toByteArray()
            var i = 0
            while (i < d.size) { val len = minOf(255, d.size - i); o.write(len); o.write(d, i, len); i += len }
            o.write(0)
        }
        o.write(0x3b)
        return o.toByteArray().also {
            assertEquals(w to h, bounds(it))
            assertEquals(frames.size > 1, animated(it))
        }
    }

    /** True when a marker segment before the scan data is APP1 "Exif". */
    private fun hasExif(jpeg: ByteArray): Boolean {
        var i = 2
        while (i + 8 <= jpeg.size && jpeg[i] == 0xff.toByte()) {
            val marker = jpeg[i + 1].toInt() and 0xff
            if (marker == 0xda) return false
            if (marker == 0xe1 && String(jpeg, i + 4, 4, Charsets.US_ASCII) == "Exif") return true
            i += 2 + (((jpeg[i + 2].toInt() and 0xff) shl 8) or (jpeg[i + 3].toInt() and 0xff))
        }
        return false
    }

    /** A JPEG with an EXIF APP1 segment holding only the orientation tag; BitmapFactory ignores the tag. */
    private fun exifJpeg(w: Int, h: Int, orientation: Int): ByteArray {
        val plain = jpeg(w, h)
        val tiff = byteArrayOf(0x4d, 0x4d, 0, 0x2a, 0, 0, 0, 8, 0, 1, 0x01, 0x12, 0, 3, 0, 0, 0, 1, 0, orientation.toByte(), 0, 0, 0, 0, 0, 0)
        val payload = "Exif".toByteArray() + byteArrayOf(0, 0) + tiff
        val len = payload.size + 2
        return (byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xe1.toByte(), (len shr 8).toByte(), len.toByte()) +
            payload + plain.copyOfRange(2, plain.size)).also {
            assertTrue(hasExif(it))
            assertEquals(w to h, bounds(it))
        }
    }

    /** The first [n] bytes of [whole], keeping its signature and header. */
    private fun cut(whole: ByteArray, n: Int) = whole.copyOf(n).also { assertEquals(bounds(whole), bounds(it)) }

    /** [jpeg] cut halfway through its scan data, after the start-of-scan marker. */
    private fun cutScan(jpeg: ByteArray): ByteArray {
        val sos = (2 until jpeg.size - 1).first { jpeg[it] == 0xff.toByte() && jpeg[it + 1] == 0xda.toByte() }
        return cut(jpeg, sos + (jpeg.size - sos) / 2)
    }

    private fun decodeJpeg(bytes: ByteArray) = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)!!

    // Step 1: named checks of the platform decoder under Robolectric.

    @Test
    fun robolectricDecodesRealBytes() = runBlocking {
        val bytes = png(100, 50)
        assertTrue(bytes.size < 10_000)
        var thread: Thread? = null
        val result = decode(ImageInput.Bytes(bytes), decoder = { s, l -> thread = Thread.currentThread(); real(s, l) })
        val bitmap = result.bitmap()
        assertEquals(100 to 50, bitmap.width to bitmap.height)
        assertEquals(Color.RED, bitmap.getPixel(50, 25))
        assertNotEquals(Looper.getMainLooper().thread, thread)
        assertFalse(bitmap.isRecycled)
    }

    @Test
    fun exifOrientationApplied() = runBlocking {
        val bitmap = decode(ImageInput.Bytes(exifJpeg(4, 2, orientation = 6))).bitmap()
        assertEquals(2 to 4, bitmap.width to bitmap.height)
    }

    @Test
    fun headerAbortReachesCaller() = runBlocking {
        for (side in listOf(9000, 30_000)) {
            val bytes = pngHeader(side, side)
            var returned = false
            val result = decode(ImageInput.Bytes(bytes), decoder = { s, l -> real(s, l).also { returned = true } })
            // A decode that started would fail on the cut-off data as UNREADABLE; TOO_LARGE comes only from the header.
            assertEquals(TOO_LARGE, result.reason())
            assertFalse(returned)
        }
    }

    @Test
    fun pixelCapAtInjectedThreshold() = runBlocking {
        val bytes = png(100, 100)
        assertTrue(bytes.size < ImageLimits.MAX_SOURCE_BYTES)
        var bitmaps = 0
        val decoder: Decoder = { s, l -> real(s, l).also { bitmaps++ } }
        assertEquals(TOO_LARGE, decode(ImageInput.Bytes(bytes), limits = DecodeLimits(maxPixels = 9_999), decoder = decoder).reason())
        assertEquals(0, bitmaps)
        val ok = decode(ImageInput.Bytes(bytes), limits = DecodeLimits(maxPixels = 10_000), decoder = decoder).bitmap()
        assertEquals(100 to 100, ok.width to ok.height)
        assertEquals(1, bitmaps)
    }

    @Test
    fun animatedGifFirstFrame() = runBlocking {
        assertEquals(Color.BLUE, decode(ImageInput.Bytes(gif(3, 2, listOf(1)))).bitmap().getPixel(1, 1))
        val bitmap = decode(ImageInput.Bytes(gif(3, 2, listOf(0, 1)))).bitmap()
        assertEquals(3 to 2, bitmap.width to bitmap.height)
        assertEquals(Color.RED, bitmap.getPixel(1, 1))
    }

    @Test
    fun alphaBecomesWhite() = runBlocking {
        val bytes = png(20, 10, Color.TRANSPARENT)
        assertEquals(0, Color.alpha(BitmapFactory.decodeByteArray(bytes, 0, bytes.size).getPixel(5, 5)))
        val jpeg = (toUploadJpeg(ctx, ImageInput.Bytes(bytes)) as Encoded.Ok).jpeg
        val pixel = decodeJpeg(jpeg).getPixel(10, 5)
        for (channel in listOf(Color.red(pixel), Color.green(pixel), Color.blue(pixel))) assertTrue("pixel ${Integer.toHexString(pixel)}", channel >= 250)
    }

    @Test
    fun noExifInUpload() = runBlocking {
        val jpeg = (toUploadJpeg(ctx, ImageInput.Bytes(exifJpeg(4, 2, orientation = 6))) as Encoded.Ok).jpeg
        assertEquals(0xd8, jpeg[1].toInt() and 0xff)
        assertFalse(hasExif(jpeg))
        // The rotation is in the pixels now, not in a tag.
        assertEquals(2 to 4, bounds(jpeg))
    }

    // Step 2: content URIs.

    @Test
    fun ownAuthorityRefused() = runBlocking {
        assertEquals(ctx.packageName, ctx.packageManager.resolveContentProvider(OWN_MERGED, 0)?.packageName)
        assertEquals(ctx.packageName, ctx.packageManager.resolveContentProvider(OWN_REGISTERED, 0)?.packageName)
        route("x") { fileAfd(png(4, 4)) }
        assertEquals(NOT_AN_IMAGE, decode(ImageInput.Content(uri("x", OWN_MERGED))).reason())
        assertEquals(NOT_AN_IMAGE, decode(ImageInput.Content(uri("x", OWN_REGISTERED))).reason())
        assertEquals(0, opens("x"))
        // The same route through another package's authority is read: the refusal is the authority's.
        assertTrue(decode(ImageInput.Content(uri("x"))) is Decoded.Ok)
    }

    @Test
    fun unresolvableAuthorityRefused() = runBlocking {
        assertNull(ctx.packageManager.resolveContentProvider("nobody.home", 0))
        assertEquals(NOT_AN_IMAGE, decode(ImageInput.Content(uri("x", "nobody.home"))).reason())
    }

    @Test
    fun userInfoAuthorityRefused() = runBlocking {
        assertEquals("com.other.user", ctx.packageManager.resolveContentProvider(USER_INFO, 0)?.packageName)
        route("x") { fileAfd(png(4, 4)) }
        assertEquals(NOT_AN_IMAGE, decode(ImageInput.Content(uri("x", USER_INFO))).reason())
        assertEquals(NOT_AN_IMAGE, decode(ImageInput.Content(Uri.parse("content://0%40$OTHER/x"))).reason())
        assertEquals(0, opens("x"))
    }

    @Test
    fun fileSchemeRefused() = runBlocking {
        val file = File(ctx.filesDir, "photo.png").apply { writeBytes(png(4, 4)) }
        assertEquals(NOT_AN_IMAGE, decode(ImageInput.Content(Uri.fromFile(file))).reason())
        assertEquals(NOT_AN_IMAGE, decode(ImageInput.Content(Uri.parse("android.resource://${ctx.packageName}/1"))).reason())
        assertEquals(NOT_AN_IMAGE, decode(ImageInput.Content(Uri.parse("CONTENT://$OTHER/x"))).reason())
    }

    @Test
    fun securityExceptionUnreadable() = runBlocking {
        route("x") { throw SecurityException("no grant") }
        assertEquals(UNREADABLE, decode(ImageInput.Content(uri("x"))).reason())
        assertEquals(1, opens("x"))
    }

    @Test
    fun failedCopyReleasesBeforeReporting() = runBlocking {
        route("deny") { throw SecurityException("no grant") }
        route("png") { fileAfd(png(8, 8)) }
        repeat(100) {
            // Both slots each round: a slot given back after its failure was reported would make the next round BUSY.
            val both = List(2) { async(Dispatchers.Default) { decode(ImageInput.Content(uri("deny"))) } }
            for (r in both) assertEquals(UNREADABLE, within10s { r.await() }.reason())
            // Seen by the caller only after the worker deleted its temp file.
            assertEquals(emptyList<String>(), temps())
        }
        assertEquals(200, opens("deny"))
        assertTrue(decode(ImageInput.Content(uri("png"))) is Decoded.Ok)
    }

    @Test
    fun vanishingProvider() = runBlocking {
        val whole = png(64, 64, Color.BLUE)
        // The provider writes half the image and goes away.
        val half = Stall("half", cut(whole, whole.size / 2))
        val result = async(Dispatchers.Default) { decode(ImageInput.Content(uri("half"))) }
        within10s { half.opened.await() }
        half.end()
        assertEquals(UNREADABLE, within10s { result.await() }.reason())
        // Or answers nothing at all.
        route("gone") { null }
        assertEquals(UNREADABLE, decode(ImageInput.Content(uri("gone"))).reason())
        route("missing") { throw java.io.FileNotFoundException("deleted") }
        assertEquals(UNREADABLE, decode(ImageInput.Content(uri("missing"))).reason())
    }

    @Test
    fun copyCapExact() = runBlocking {
        val bytes = png(20, 20)
        val n = bytes.size.toLong()
        val calls = AtomicInteger()
        route("img") { fileAfd(bytes) }
        val exact = decode(ImageInput.Content(uri("img")), limits = DecodeLimits(maxSourceBytes = n), decoder = counting(calls)).bitmap()
        assertEquals(20 to 20, exact.width to exact.height)
        assertEquals(1, calls.get())
        // One byte too many, with the writer still open and no end of file: the copy itself stops, not a later check.
        assertTrue("fixture $n bytes", n <= 512) // a write this small into an empty FIFO cannot block the test thread
        Stall("over", bytes)
        assertEquals(TOO_LARGE, within10s { decode(ImageInput.Content(uri("over")), limits = DecodeLimits(maxSourceBytes = n - 1), decoder = counting(calls)) }.reason())
        assertEquals(emptyList<String>(), temps())
        route("empty") { fileAfd(ByteArray(0)) }
        assertEquals(NOT_AN_IMAGE, decode(ImageInput.Content(uri("empty")), limits = DecodeLimits(maxSourceBytes = 0), decoder = counting(calls)).reason())
        Stall("one", ByteArray(1))
        assertEquals(TOO_LARGE, within10s { decode(ImageInput.Content(uri("one")), limits = DecodeLimits(maxSourceBytes = 0), decoder = counting(calls)) }.reason())
        assertEquals(1, calls.get())
        assertEquals(emptyList<String>(), temps())
    }

    @Test
    fun endlessStream() = runBlocking {
        // The cap plus one byte, then nothing: the writer stays open, so no end of file ever comes.
        val cap = 31L
        assertTrue(cap + 1 <= 512) // a write this small into an empty FIFO cannot block the test thread
        Stall("long", ByteArray((cap + 1).toInt()))
        val calls = AtomicInteger()
        val result = within10s { decode(ImageInput.Content(uri("long")), limits = DecodeLimits(maxSourceBytes = cap), decoder = counting(calls)) }
        assertEquals(TOO_LARGE, result.reason())
        assertEquals(0, calls.get())
        assertEquals(emptyList<String>(), temps())
    }

    @Test
    fun negativeDeclaredSizeIgnored() = runBlocking {
        val bytes = png(30, 20)
        route("negative") { fileAfd(bytes, declared = -5) }
        val bitmap = decode(ImageInput.Content(uri("negative"))).bitmap()
        assertEquals(30 to 20, bitmap.width to bitmap.height)
        // A huge declared size does not lift the cap either: bytes read are counted.
        val cap = 1L shl 20
        val source = ByteArray((4 * cap).toInt())
        assertEquals(4 * cap, source.size.toLong())
        route("huge") { fileAfd(source, declared = Long.MAX_VALUE / 2) }
        assertEquals(TOO_LARGE, within10s { decode(ImageInput.Content(uri("huge")), limits = DecodeLimits(maxSourceBytes = cap)) }.reason())
    }

    @Test
    fun contentCopiedAndDecodedOffMainThread() = runBlocking {
        route("x") { fileAfd(png(8, 8)) }
        var thread: Thread? = null
        val result = decode(ImageInput.Content(uri("x")), decoder = { s, l -> thread = Thread.currentThread(); real(s, l) })
        assertTrue(result is Decoded.Ok)
        val main = Looper.getMainLooper().thread
        assertNotEquals(main, thread)
        assertFalse(RouteProvider.threads.isEmpty())
        assertTrue(RouteProvider.threads.none { it == main })
    }

    @Test
    fun stalledProviderTimesOut() = runBlocking {
        val stall = Stall("stall")
        val result = onClock(ImageInput.Content(uri("stall")))
        within10s { stall.opened.await() }
        until("the copy is blocked in its read") { copyBlockedInRead() }
        step(19_999)
        assertFalse(result.isCompleted)
        val before = workers().toSet()
        step(1)
        assertEquals(UNREADABLE, within10s { result.await() }.reason())
        assertEquals(20_000, clock.currentTime)
        // The abandon's cleanup closes the descriptor the provider returned; the read itself still blocks.
        within10s { (workers() - before).joinAll() }
        assertFalse(stall.reader.fileDescriptor.valid())
        assertEquals(1, temps().size)
    }

    @Test
    fun blockingOpenTimesOut() = runBlocking {
        val open = BlockingOpen("slow")
        val result = onClock(ImageInput.Content(uri("slow")))
        within10s { open.opened.await() }
        step(19_999)
        assertFalse(result.isCompleted)
        step(1)
        assertEquals(UNREADABLE, within10s { result.await() }.reason())
        assertEquals(20_000, clock.currentTime)
    }

    @Test
    fun lateDescriptorClosedUnread() = runBlocking {
        val holds = List(2) { Hold() }
        val stalls = holds.mapIndexed { i, hold -> Stall("late$i", ByteArray(16) { it.toByte() }, hold) }
        for ((i, stall) in stalls.withIndex()) {
            val result = onClock(ImageInput.Content(uri("late$i")))
            within10s { holds[i].entered.await() }
            step(20_000)
            assertEquals(UNREADABLE, within10s { result.await() }.reason())
            assertEquals(16, stall.unread())
        }
        // The opens return after their callers gave up: each descriptor is closed, not read.
        holds.forEach { it.release() }
        within10s { workers().joinAll() }
        for (stall in stalls) {
            assertFalse(stall.reader.fileDescriptor.valid())
            assertEquals(16, stall.unread())
        }
        assertEquals(emptyList<String>(), temps())
        // Both slots are free again: one stalled copy and one full decode go through together.
        val free = Stall("free")
        val holding = async(Dispatchers.Default) { decode(ImageInput.Content(uri("free"))) }
        within10s { free.opened.await() }
        route("png") { fileAfd(png(8, 8)) }
        assertTrue(within10s { decode(ImageInput.Content(uri("png"))) } is Decoded.Ok)
        free.end()
        within10s { holding.await() }
        Unit
    }

    @Test
    fun thirdStuckSourceRejectedAtOnce() = runBlocking {
        val first = BlockingOpen("one")
        val second = BlockingOpen("two")
        val a = onClock(ImageInput.Content(uri("one")))
        val b = onClock(ImageInput.Content(uri("two")))
        within10s { first.opened.await(); second.opened.await() }
        step(20_000)
        assertEquals(UNREADABLE, within10s { a.await() }.reason())
        assertEquals(UNREADABLE, within10s { b.await() }.reason())
        // Both opens still block and keep their slots: a third request fails at once, unopened.
        route("three") { fileAfd(png(4, 4)) }
        val c = onClock(ImageInput.Content(uri("three")))
        assertTrue(c.isCompleted)
        assertEquals(BUSY, c.await().reason())
        assertEquals(20_000, clock.currentTime)
        assertEquals(0, opens("three"))
    }

    @Test
    fun slotHeldUntilTempFileDeleted() = runBlocking {
        val permits = Semaphore(0)
        val entered = kotlinx.coroutines.channels.Channel<Unit>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        releases += { permits.release(10) }
        val gated: Decoder = { s, l ->
            entered.trySend(Unit)
            if (!permits.tryAcquire(10, TimeUnit.SECONDS)) {
                holdExpired.set(true)
                error("hold expired")
            }
            real(s, l)
        }
        val image = png(16, 16)
        val n = image.size.toLong()
        val opened = kotlinx.coroutines.channels.Channel<String>(kotlinx.coroutines.channels.Channel.UNLIMITED)
        for (path in listOf("p1", "p2", "p3")) route(path) { opened.trySend(path); fileAfd(image) }
        route("busy") { fileAfd(png(4, 4)) }
        val r1 = async(Dispatchers.Default) { decode(ImageInput.Content(uri("p1")), decoder = gated) }
        val r2 = async(Dispatchers.Default) { decode(ImageInput.Content(uri("p2")), decoder = gated) }
        within10s { entered.receive() }
        until("both copies are complete") { tempLengths() == listOf(n, n) }
        assertEquals(BUSY, decode(ImageInput.Content(uri("busy"))).reason())
        assertEquals(0, opens("busy"))
        permits.release()
        val first = within10s { select { r1.onAwait { it }; r2.onAwait { it } } }
        assertTrue(first is Decoded.Ok)
        // The finished decode deleted its temp file before it returned, and gave its slot back.
        assertEquals(listOf(n), tempLengths())
        val r3 = async(Dispatchers.Default) { decode(ImageInput.Content(uri("p3")), decoder = gated) }
        within10s { repeat(3) { opened.receive() } }
        permits.release(2)
        for (r in listOf(r1, r2, r3)) assertTrue(within10s { r.await() } is Decoded.Ok)
        assertEquals(emptyList<String>(), temps())
    }

    @Test
    fun stalledSourceDoesNotBlockOtherDecodes() = runBlocking {
        val stall = Stall("stall")
        val stalled = async(Dispatchers.Default) { decode(ImageInput.Content(uri("stall"))) }
        within10s { stall.opened.await() }
        stall.feed(byteArrayOf(0x42))
        until("the copy is in its read loop") { tempLengths() == listOf(1L) }
        assertTrue(within10s { decode(ImageInput.Bytes(png(10, 10))) } is Decoded.Ok)
        route("free") { fileAfd(png(10, 10)) }
        assertTrue(within10s { decode(ImageInput.Content(uri("free"))) } is Decoded.Ok)
        assertFalse(stalled.isCompleted)
        stall.end()
        assertEquals(NOT_AN_IMAGE, within10s { stalled.await() }.reason()) // one byte, then the end
    }

    @Test
    fun blockedDecoderTimesOutWaiters() = runBlocking {
        val stuck = Held()
        val first = onClock(ImageInput.Bytes(png(10, 10)), stuck.decoder)
        within10s { stuck.entered.await() }
        val calls = AtomicInteger()
        val later = onClock(ImageInput.Bytes(png(10, 10)), counting(calls))
        step(ImageLimits.GATE_WAIT_MS - 1)
        assertFalse(later.isCompleted)
        step(1)
        assertEquals(UNREADABLE, within10s { later.await() }.reason())
        val again = onClock(ImageInput.Bytes(png(10, 10)), counting(calls))
        step(ImageLimits.GATE_WAIT_MS)
        assertEquals(UNREADABLE, within10s { again.await() }.reason())
        assertEquals(0, calls.get())
        // Its own caller waits with it; cancelled, it leaves at once and the gate stays with the decode.
        assertFalse(first.isCompleted)
        within10s { first.cancelAndJoin() }
        val afterCancel = onClock(ImageInput.Bytes(png(10, 10)), counting(calls))
        step(ImageLimits.GATE_WAIT_MS)
        assertEquals(UNREADABLE, within10s { afterCancel.await() }.reason())
        assertEquals(0, calls.get())
        stuck.release()
        within10s { workers().joinAll() }
        assertTrue(decode(ImageInput.Bytes(png(10, 10)), decoder = counting(calls)) is Decoded.Ok)
        assertEquals(1, calls.get())
    }

    @Test
    fun gateSurvivesWaitersGivingUp() = runBlocking {
        val small = png(4, 4)
        repeat(200) { i ->
            val held = Held()
            val first = onClock(ImageInput.Bytes(small), held.decoder)
            within10s { held.entered.await() }
            val waiter = onClock(ImageInput.Bytes(small))
            // The gate comes free while the waiter gives up: by its wait expiring, or by its caller's cancellation.
            held.release()
            if (i % 2 == 0) step(ImageLimits.GATE_WAIT_MS) else waiter.cancel()
            assertTrue(within10s { first.await() } is Decoded.Ok)
            within10s { waiter.join() }
            within10s { workers().joinAll() }
        }
        // A real decode still enters the gate and returns.
        assertTrue(within10s { decode(ImageInput.Bytes(small)) } is Decoded.Ok)
    }

    @Test
    fun cancelledCallKeepsSlotUntilReadReturns() = runBlocking {
        val one = Stall("one")
        val caller = launch(Dispatchers.Default) { decode(ImageInput.Content(uri("one"))) }
        within10s { one.opened.await() }
        until("the copy is blocked in its read") { copyBlockedInRead() }
        val worker: Job = workers().single()
        within10s { caller.cancelAndJoin() } // gives up at once, the read still blocks
        assertFalse(worker.isCompleted)
        val two = Stall("two")
        val second = async(Dispatchers.Default) { decode(ImageInput.Content(uri("two"))) }
        within10s { two.opened.await() }
        route("png") { fileAfd(png(4, 4)) }
        assertEquals(BUSY, decode(ImageInput.Content(uri("png"))).reason())
        assertEquals(0, opens("png"))
        assertEquals(2, temps().size)
        one.end()
        within10s { worker.join() }
        assertEquals(1, temps().size)
        assertTrue(within10s { decode(ImageInput.Content(uri("png"))) } is Decoded.Ok)
        two.end()
        within10s { second.await() }
        Unit
    }

    @Test
    fun bitmapAfterCancelRecycled() = runBlocking {
        val held = Held()
        val caller = launch(Dispatchers.Default) { decode(ImageInput.Bytes(png(10, 10)), decoder = held.decoder) }
        within10s { held.entered.await() }
        within10s { caller.cancelAndJoin() }
        held.release()
        within10s { workers().joinAll() }
        val orphan = held.bitmap
        assertNotNull(orphan)
        assertTrue(orphan!!.isRecycled)
        // A delivered bitmap is its caller's.
        val delivered = decode(ImageInput.Bytes(png(10, 10))).bitmap()
        within10s { workers().joinAll() }
        assertFalse(delivered.isRecycled)
    }

    @Test
    fun cancelledUploadStartsNoEncode() = runBlocking {
        val held = Held()
        val encodes = AtomicInteger()
        val caller = launch(Dispatchers.Default) {
            toUploadJpeg(ctx, ImageInput.Bytes(png(20, 10)), DecodeLimits(), held.decoder) { b -> encodes.incrementAndGet(); flatJpeg(b) }
        }
        within10s { held.entered.await() }
        within10s { caller.cancelAndJoin() }
        held.release()
        within10s { workers().joinAll() }
        // The caller left during the decode: no white copy and no encode start, and the decoded bitmap is recycled.
        assertEquals(0, encodes.get())
        assertTrue(held.bitmap!!.isRecycled)
        assertTrue(within10s { decode(ImageInput.Bytes(png(10, 10))) } is Decoded.Ok)
    }

    @Test
    fun callerGoneBeforeFirstStepMakesNoProviderCall() = runBlocking {
        val lookups = AtomicInteger()
        val counted = object : ContextWrapper(ctx) {
            override fun getPackageManager(): PackageManager {
                lookups.incrementAndGet()
                return super.getPackageManager()
            }
        }
        route("x") { fileAfd(png(4, 4)) }
        // Every IO thread is held, so the worker cannot take its first step until its caller has left.
        assertNull(System.getProperty("kotlinx.coroutines.io.parallelism"))
        val threads = maxOf(64, Runtime.getRuntime().availableProcessors())
        val started = CountDownLatch(threads)
        val hold = Hold() // an expiry fails the test instead of freeing a lane early
        val blockers = mutableListOf<Job>()
        try {
            repeat(threads) {
                blockers += CoroutineScope(Dispatchers.IO).launch {
                    started.countDown()
                    hold.await()
                }
            }
            assertTrue(started.await(10, TimeUnit.SECONDS))
            val result = async(virtual) { decodeBounded(counted, ImageInput.Content(uri("x")), ImageLimits.VIEW_SIDE, true, DecodeLimits(), real) }
            step(20_000)
            assertEquals(UNREADABLE, within10s { result.await() }.reason())
        } finally {
            hold.release()
            within10s { blockers.joinAll() }
        }
        within10s { workers().joinAll() }
        assertEquals(0, lookups.get())
        assertEquals(0, opens("x"))
    }

    // Step 2: sizes, failures, formats.

    @Test
    fun extremeAspectRatio() = runBlocking {
        val t = decode(ImageInput.Bytes(png(1, 20_000)), side = ImageLimits.UPLOAD_SIDE).bitmap()
        assertEquals(1 to 1568, t.width to t.height)
        val w = decode(ImageInput.Bytes(png(20_000, 1)), side = ImageLimits.THUMB_SIDE).bitmap()
        assertEquals(320 to 1, w.width to w.height)
    }

    @Test
    fun targetArithmeticDoesNotOverflow() = runBlocking {
        assertEquals(2560 to 2559, targetSize(Int.MAX_VALUE.toLong(), Int.MAX_VALUE - 1L, 2560))
        assertEquals(1 to 2560, targetSize(1, Int.MAX_VALUE.toLong(), 2560))
        assertEquals(46_341 to 46_341, targetSize(46_341, 46_341, Int.MAX_VALUE))
        assertEquals(1568 to 1176, targetSize(4000, 3000, 1568))
        assertEquals(100 to 50, targetSize(100, 50, 1568))
        // 46341 x 46341 overflows an Int to a negative product; in Long it is over the cap.
        val bytes = pngHeader(46_341, 46_341)
        assertTrue(46_341 * 46_341 < 0)
        assertEquals(TOO_LARGE, decode(ImageInput.Bytes(bytes)).reason())
    }

    @Test
    fun nonPositiveSidesRefused() = runBlocking {
        assertNull(targetSize(0, 10, 100))
        assertNull(targetSize(10, 0, 100))
        assertNull(targetSize(-1, 10, 100))
        assertNull(targetSize(10, 10, 0))
        assertNull(targetSize(10, 10, -5))
        val calls = AtomicInteger()
        for (side in listOf(0, -1)) {
            assertEquals(UNREADABLE, decode(ImageInput.Bytes(png(10, 10)), side = side, decoder = counting(calls)).reason())
        }
        assertEquals(0, calls.get())
    }

    @Test
    fun outputLargerThanTargetRefused() = runBlocking {
        var oversized: Bitmap? = null
        val ignoresTarget: Decoder = { s, l ->
            real(s, l).recycle()
            Bitmap.createBitmap(51, 50, Bitmap.Config.ARGB_8888).also { oversized = it }
        }
        assertEquals(UNREADABLE, decode(ImageInput.Bytes(png(100, 100)), side = 50, decoder = ignoresTarget).reason())
        assertTrue(oversized!!.isRecycled)
        var unasked: Bitmap? = null
        val skipsHeader: Decoder = { _, _ -> Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888).also { unasked = it } }
        assertEquals(UNREADABLE, decode(ImageInput.Bytes(png(10, 10)), decoder = skipsHeader).reason())
        assertTrue(unasked!!.isRecycled)
        // Within its target the same seam is fine.
        val fits = decode(ImageInput.Bytes(png(100, 100)), side = 50).bitmap()
        assertEquals(50 to 50, fits.width to fits.height)
    }

    @Test
    fun allocatorFollowsSoftwareFlag() = runBlocking {
        for (software in listOf(true, false)) {
            var allocator = -1
            val recording: Decoder = { s, l ->
                real(s) { d, info, src -> l.onHeaderDecoded(d, info, src); allocator = d.allocator }
            }
            assertTrue(decode(ImageInput.Bytes(png(10, 10)), software = software, decoder = recording) is Decoded.Ok)
            assertEquals(if (software) ImageDecoder.ALLOCATOR_SOFTWARE else ImageDecoder.ALLOCATOR_DEFAULT, allocator)
        }
    }

    @Test
    fun decoderRuntimeExceptionIsUnreadable() = runBlocking {
        val bytes = png(10, 10)
        assertEquals(UNREADABLE, decode(ImageInput.Bytes(bytes), decoder = { _, _ -> throw IllegalStateException("codec") }).reason())
        assertEquals(UNREADABLE, decode(ImageInput.Bytes(bytes), decoder = { _, _ -> throw OutOfMemoryError("big") }).reason())
        // Only cancellation of the calling job propagates; a codec's own cancellation is an unreadable image.
        assertEquals(UNREADABLE, decode(ImageInput.Bytes(bytes), decoder = { _, _ -> throw java.util.concurrent.CancellationException("odd") }).reason())
        // The gate was given back each time.
        assertTrue(decode(ImageInput.Bytes(bytes)) is Decoded.Ok)
    }

    @Test
    fun formatOutsideSetRefused() = runBlocking {
        val avif = byteArrayOf(0, 0, 0, 0x1c) + "ftypavif".toByteArray() + byteArrayOf(0, 0, 0, 0) + "avifmif1miaf".toByteArray()
        val heic = byteArrayOf(0, 0, 0, 0x18) + "ftypheic".toByteArray() + byteArrayOf(0, 0, 0, 0) + "mif1heic".toByteArray()
        assertEquals(ImageFormat.AVIF, sniff(avif))
        assertEquals(ImageFormat.HEIF, sniff(heic))
        val calls = AtomicInteger()
        val reply = DecodeLimits(formats = REPLY_FORMATS)
        assertEquals(NOT_AN_IMAGE, decode(ImageInput.Bytes(avif), limits = reply, decoder = counting(calls)).reason())
        assertEquals(NOT_AN_IMAGE, decode(ImageInput.Bytes(heic), limits = reply, decoder = counting(calls)).reason())
        assertEquals(0, calls.get())
        // Uploads take both; these headers are cut off, so the decoder is reached and fails.
        assertEquals(UNREADABLE, decode(ImageInput.Bytes(avif), decoder = counting(calls)).reason())
        assertEquals(1, calls.get())
        assertEquals(NOT_AN_IMAGE, decode(ImageInput.Bytes(png(4, 4)), limits = DecodeLimits(formats = setOf(ImageFormat.JPEG))).reason())
    }

    @Test
    fun sniffRefusesMalformedAndForeignBoxes() = runBlocking {
        fun box(size: Int, vararg brands: String): ByteArray {
            val b = ByteBuffer.allocate(8 + 4 * (brands.size + 1)).putInt(size).put("ftyp".toByteArray())
            b.put(brands[0].toByteArray()).putInt(0)
            brands.drop(1).forEach { b.put(it.toByteArray()) }
            return b.array()
        }
        // Sizes under 16, including the to-end (0) and 64-bit (1) forms, with image brands behind them.
        for (size in listOf(0, 1, 8, 12, 15)) assertNull("size $size", sniff(box(size, "heic", "avif", "mif1")))
        assertNull(sniff(box(24, "heic").copyOf(15)))
        // Video brands.
        for (brand in listOf("isom", "mp42", "qt  ")) assertNull(brand, sniff(box(24, brand, "isom", "avc1")))
        // A generic major brand with avif among the compatible brands is AVIF; brands past the box are not read.
        assertEquals(ImageFormat.AVIF, sniff(box(24, "mif1", "miaf", "avif")))
        assertEquals(ImageFormat.HEIF, sniff(box(20, "mif1", "miaf") + "avif".toByteArray()))
        val riff = { kind: String -> "RIFF".toByteArray() + byteArrayOf(0x24, 0, 0, 0) + kind.toByteArray() + ByteArray(8) }
        assertEquals(ImageFormat.WEBP, sniff(riff("WEBP")))
        assertNull(sniff(riff("WAVE")))
        val calls = AtomicInteger()
        assertEquals(NOT_AN_IMAGE, decode(ImageInput.Bytes(box(8, "heic", "heic", "heic")), decoder = counting(calls)).reason())
        assertEquals(NOT_AN_IMAGE, decode(ImageInput.Bytes(riff("WEBP")), limits = DecodeLimits(formats = setOf(ImageFormat.JPEG)), decoder = counting(calls)).reason())
        assertEquals(0, calls.get())
    }

    @Test
    fun labelDiffersFromBytesRefused() = runBlocking {
        val bytes = png(10, 10)
        val calls = AtomicInteger()
        assertEquals(NOT_AN_IMAGE, decode(ImageInput.Bytes(bytes, "image/jpeg"), decoder = counting(calls)).reason())
        assertEquals(NOT_AN_IMAGE, decode(ImageInput.Bytes(bytes, "text/plain"), decoder = counting(calls)).reason())
        assertEquals(NOT_AN_IMAGE, decode(ImageInput.Bytes(bytes, "image/png;base64"), decoder = counting(calls)).reason())
        assertEquals(NOT_AN_IMAGE, decode(ImageInput.Bytes(bytes, "image/png" + "X".repeat(100_000)), decoder = counting(calls)).reason())
        assertEquals(0, calls.get())
        assertTrue(decode(ImageInput.Bytes(bytes, "image/png"), decoder = counting(calls)) is Decoded.Ok)
        assertTrue(decode(ImageInput.Bytes(bytes, "IMAGE/PNG"), decoder = counting(calls)) is Decoded.Ok)
        assertTrue(decode(ImageInput.Bytes(bytes)) is Decoded.Ok)
    }

    @Test
    fun corruptJpeg() = runBlocking {
        val whole = jpeg(64, 64)
        assertEquals(UNREADABLE, decode(ImageInput.Bytes(cutScan(whole))).reason())
        val scrambled = whole.copyOf().also { for (i in 20 until it.size) it[i] = 0x5a }
        assertEquals(UNREADABLE, decode(ImageInput.Bytes(scrambled)).reason())
        // A truncated file from a provider too.
        val truncated = cutScan(whole)
        route("cut") { fileAfd(truncated) }
        assertEquals(UNREADABLE, decode(ImageInput.Content(uri("cut"))).reason())
    }

    @Test
    fun notAnImage() = runBlocking {
        val calls = AtomicInteger()
        assertEquals(NOT_AN_IMAGE, decode(ImageInput.Bytes("<html>hello</html>".toByteArray()), decoder = counting(calls)).reason())
        assertEquals(NOT_AN_IMAGE, decode(ImageInput.Bytes(ByteArray(0)), decoder = counting(calls)).reason())
        route("empty") { fileAfd(ByteArray(0)) }
        assertEquals(NOT_AN_IMAGE, decode(ImageInput.Content(uri("empty")), decoder = counting(calls)).reason())
        route("text") { fileAfd("[core]\nkey=value\n".toByteArray()) }
        assertEquals(NOT_AN_IMAGE, decode(ImageInput.Content(uri("text")), decoder = counting(calls)).reason())
        val stored = File(ctx.filesDir, "notes.jpg").apply { writeBytes("not a jpeg".toByteArray()) }
        assertEquals(NOT_AN_IMAGE, decode(ImageInput.Stored(stored), decoder = counting(calls)).reason())
        assertEquals(0, calls.get())
    }

    @Test
    fun bytesOverCapRefusedBeforeDecode() = runBlocking {
        val bytes = png(10, 10)
        val calls = AtomicInteger()
        val cap = DecodeLimits(maxSourceBytes = bytes.size - 1L)
        assertEquals(TOO_LARGE, decode(ImageInput.Bytes(bytes), limits = cap, decoder = counting(calls)).reason())
        val stored = File(ctx.filesDir, "big.png").apply { writeBytes(bytes) }
        assertEquals(TOO_LARGE, decode(ImageInput.Stored(stored), limits = cap, decoder = counting(calls)).reason())
        assertEquals(0, calls.get())
        assertTrue(decode(ImageInput.Stored(stored), limits = DecodeLimits(maxSourceBytes = bytes.size.toLong())) is Decoded.Ok)
    }

    @Test
    fun uploadNeverUpscales() = runBlocking {
        val jpeg = (toUploadJpeg(ctx, ImageInput.Bytes(png(100, 50))) as Encoded.Ok).jpeg
        assertEquals(100 to 50, bounds(jpeg))
    }

    @Test
    fun uploadLongestSide() = runBlocking {
        val bytes = png(4000, 3000, Color.GRAY, Bitmap.Config.RGB_565)
        val jpeg = (toUploadJpeg(ctx, ImageInput.Bytes(bytes)) as Encoded.Ok).jpeg
        assertEquals(1568 to 1176, bounds(jpeg))
        assertEquals(TOO_LARGE, (toUploadJpeg(ctx, ImageInput.Bytes(bytes), DecodeLimits(maxPixels = 11_999_999)) as Encoded.Failed).reason)
    }

    @Test
    fun uploadEncodesInsideGate() = runBlocking {
        val hold = Hold()
        val first = async(virtual) {
            toUploadJpeg(ctx, ImageInput.Bytes(png(20, 10)), DecodeLimits(), real) { bitmap -> hold.await(); flatJpeg(bitmap) }
        }
        within10s { hold.entered.await() }
        // While the first upload encodes, a second does not get past the gate.
        val calls = AtomicInteger()
        val second = async(virtual) { toUploadJpeg(ctx, ImageInput.Bytes(png(20, 10)), DecodeLimits(), counting(calls), ::flatJpeg) }
        step(ImageLimits.GATE_WAIT_MS)
        assertEquals(UNREADABLE, (within10s { second.await() } as Encoded.Failed).reason)
        assertEquals(0, calls.get())
        hold.release()
        assertEquals(20 to 10, bounds((within10s { first.await() } as Encoded.Ok).jpeg))
    }

    @Test
    fun oneDecodeAtATime() = runBlocking {
        val held = Held()
        val first = onClock(ImageInput.Bytes(png(10, 10)), held.decoder)
        within10s { held.entered.await() }
        val calls = AtomicInteger()
        val second = onClock(ImageInput.Bytes(png(10, 10)), counting(calls))
        step(ImageLimits.GATE_WAIT_MS)
        assertEquals(UNREADABLE, within10s { second.await() }.reason())
        assertEquals(0, calls.get())
        held.release()
        assertTrue(within10s { first.await() } is Decoded.Ok)
        assertEquals(1, held.calls.get())
        assertTrue(decode(ImageInput.Bytes(png(10, 10)), decoder = counting(calls)) is Decoded.Ok)
        assertEquals(1, calls.get())
    }

    @Test
    fun leftoverTempsDeleted() {
        val dir = ctx.cacheDir
        val leftovers = listOf("decode-1.tmp", "decode-998877.tmp")
        val others = listOf("decode-1.txt", "other.tmp", "xdecode-1.tmp")
        (leftovers + others).forEach { File(dir, it).writeText("x") }
        clearDecodeTemps(dir)
        assertTrue(leftovers.none { File(dir, it).exists() })
        assertTrue(others.all { File(dir, it).exists() })
        others.forEach { File(dir, it).delete() }
    }
}
