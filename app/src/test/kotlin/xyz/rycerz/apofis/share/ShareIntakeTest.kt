package xyz.rycerz.apofis.share

import android.app.Application
import android.content.ClipData
import android.content.Intent
import android.content.pm.ProviderInfo
import android.content.res.AssetFileDescriptor
import android.graphics.Bitmap
import android.net.Uri
import android.os.BadParcelableException
import android.os.ParcelFileDescriptor
import android.os.Parcelable
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.testing.WorkManagerTestInitHelper
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Random
import javax.crypto.KeyGenerator
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
import xyz.rycerz.apofis.AppContainer
import xyz.rycerz.apofis.ShareToken
import xyz.rycerz.apofis.chat.NoteKind
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.images.ImageInput
import xyz.rycerz.apofis.images.ImageLimits
import xyz.rycerz.apofis.images.Encoded
import xyz.rycerz.apofis.images.RouteProvider
import xyz.rycerz.apofis.images.toUploadJpeg
import xyz.rycerz.apofis.settings.CredentialStore
import xyz.rycerz.apofis.shareGate

private const val OTHER = "com.other.files"
private const val OWN = "xyz.rycerz.apofis.files"

/**
 * What an incoming intent may be and what reading it does: [validateShare] takes only the declared action and type
 * combinations and only URIs that carry their read grant; [readShare] reads at most ten images within the byte budget,
 * in memory only; the container's slot keeps the newest share only.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [37])
class ShareIntakeTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private var made: AppContainer? = null
    private val dbs = mutableListOf<AppDatabase>()

    @Before
    fun setUp() {
        RouteProvider.routes.clear()
        RouteProvider.opens.clear()
        register(OTHER, "com.other")
        register(OWN, app.packageName)
        WorkManagerTestInitHelper.initializeTestWorkManager(app)
    }

    @After
    fun tearDown() {
        made?.scope?.cancel()
        dbs.forEach { it.close() }
    }

    private fun register(authority: String, pkg: String) {
        val info = ProviderInfo().apply {
            this.authority = authority
            packageName = pkg
            name = RouteProvider::class.java.name
            exported = true
        }
        Robolectric.buildContentProvider(RouteProvider::class.java).create(info)
        shadowOf(app.packageManager).addOrUpdateProvider(info)
    }

    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()

    private fun newContainer() = AppContainer(app, CredentialStore(app) { key }) {
        Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).build().also { dbs += it }
    }

    private val container: AppContainer get() = made ?: newContainer().also { made = it }

    private fun uri(path: String, authority: String = OTHER): Uri = Uri.parse("content://$authority/$path")
    private fun opens(path: String) = RouteProvider.opens[path]?.get() ?: 0

    /** Noise, so its JPEG size grows with its area. */
    private fun noise(side: Int, seed: Long = 1): ByteArray {
        val r = Random(seed)
        val bitmap = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        bitmap.setPixels(IntArray(side * side) { 0xFF000000.toInt() or r.nextInt(0x1000000) }, 0, side, 0, 0, side, side)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun serve(path: String, bytes: ByteArray) {
        RouteProvider.routes[path] = {
            val file = File.createTempFile("source", ".bin", app.filesDir).apply { writeBytes(bytes) }
            AssetFileDescriptor(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY), 0, AssetFileDescriptor.UNKNOWN_LENGTH)
        }
    }

    private fun clip(uris: List<Uri>): ClipData? =
        uris.firstOrNull()?.let { first -> ClipData.newRawUri("", first).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } } }

    /** A SEND of one image, its stream also in the ClipData (as the platform puts it) and granted. */
    private fun sendImage(u: Uri, type: String = "image/png", grant: Boolean = true, clipped: List<Uri> = listOf(u)) =
        Intent(Intent.ACTION_SEND).setType(type).putExtra(Intent.EXTRA_STREAM, u).also { i ->
            i.clipData = clip(clipped)
            if (grant) i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    private fun sendImages(us: List<Uri>, type: String = "image/*", grant: Boolean = true, clipped: List<Uri> = us) =
        Intent(Intent.ACTION_SEND_MULTIPLE).setType(type).putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Parcelable>(us)).also { i ->
            i.clipData = clip(clipped)
            if (grant) i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    private fun sendText(text: String) = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)

    private fun read(intent: Intent, maxBytes: Long = ImageLimits.MAX_SHARE_BYTES) = runBlocking {
        readShare(app, checkNotNull(validateShare(intent)) { "not accepted" }, maxBytes)
    }

    private fun jpegOf(png: ByteArray) = runBlocking {
        val path = "probe-${png.size}-${png.contentHashCode()}"
        serve(path, png)
        (toUploadJpeg(app, ImageInput.Content(uri(path))) as Encoded.Ok).jpeg.also { RouteProvider.opens.remove(path) }
    }

    @Test
    fun textPlainIgnoresStreamAndClipData() {
        serve("a", noise(16))
        val intent = sendText("hello").putExtra(Intent.EXTRA_STREAM, uri("a")).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = clip(listOf(uri("a")))
        assertEquals(ShareIntent.Text("hello"), validateShare(intent))
        val share = read(intent)
        assertEquals("hello", share.text)
        assertTrue(share.images.isEmpty())
        assertEquals(emptySet<NoteKind>(), share.notes)
        assertEquals(0, opens("a"))
    }

    @Test
    fun sendMultipleTextIgnored() {
        val withStream = Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "hello")
            .putParcelableArrayListExtra(Intent.EXTRA_STREAM, arrayListOf<Parcelable>(uri("a"))).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        withStream.clipData = clip(listOf(uri("a")))
        assertNull(validateShare(withStream))
        val texts = Intent(Intent.ACTION_SEND_MULTIPLE).setType("text/plain").putCharSequenceArrayListExtra(Intent.EXTRA_TEXT, arrayListOf<CharSequence>("a", "b"))
        assertNull(validateShare(texts))
        assertNull(shareGate(texts, fresh = true))
    }

    @Test
    fun unknownExtrasIgnored() {
        serve("a", noise(16))
        serve("b", noise(16, seed = 2))
        val text = sendText("hello").putExtra(Intent.EXTRA_SUBJECT, "subject").putExtra(Intent.EXTRA_HTML_TEXT, "<b>html</b>")
            .putExtra("android.intent.extra.STREAM_LIST", arrayListOf<Parcelable>(uri("b")))
        assertEquals(ShareIntent.Text("hello"), validateShare(text))

        // Another URI in an extra the app does not know, even granted in the ClipData, is never taken.
        val image = sendImage(uri("a"), clipped = listOf(uri("a"), uri("b"))).putExtra(Intent.EXTRA_SUBJECT, "subject")
            .putExtra("android.intent.extra.REFERRER_STREAM", uri("b")).putExtra(Intent.EXTRA_HTML_TEXT, "<b>html</b>")
        assertEquals(ShareIntent.Images(listOf(uri("a")), 0, ""), validateShare(image))
        assertEquals(1, read(image).images.size)
        assertEquals(0, opens("b"))
    }

    @Test
    fun wildcardTypeIgnored() {
        serve("a", noise(16))
        for (type in listOf("*/*", "text/*", "application/octet-stream", "video/mp4", "image")) {
            val one = sendImage(uri("a"), type = type).putExtra(Intent.EXTRA_TEXT, "caption")
            val many = sendImages(listOf(uri("a")), type = type).putExtra(Intent.EXTRA_TEXT, "caption")
            assertNull(type, validateShare(one))
            assertNull(type, validateShare(many))
            assertNull(type, shareGate(one, fresh = true))
            assertNull(type, shareGate(many, fresh = true))
        }
        assertNull(validateShare(Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_TEXT, "no type")))
        assertNull(validateShare(Intent(Intent.ACTION_VIEW).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "view")))
        assertEquals(0, opens("a"))
    }

    @Test
    fun readableProviderWithoutGrantRefused() {
        serve("a", noise(16))
        val one = sendImage(uri("a"), grant = false)
        assertEquals(ShareIntent.Images(emptyList(), 1, ""), validateShare(one))
        val share = read(one)
        assertTrue(share.images.isEmpty())
        assertEquals(setOf(NoteKind.SHARE_IMAGES_FAILED), share.notes)

        val many = sendImages(listOf(uri("a"), uri("a")), grant = false)
        assertEquals(ShareIntent.Images(emptyList(), 2, ""), validateShare(many))
        assertEquals(setOf(NoteKind.SHARE_IMAGES_FAILED), read(many).notes)
        assertEquals(0, opens("a"))
    }

    @Test
    fun grantedFirstUngrantedSecond() {
        serve("a", noise(16))
        serve("b", noise(16, seed = 2))
        val intent = sendImages(listOf(uri("a"), uri("b")), clipped = listOf(uri("a"))).putExtra(Intent.EXTRA_TEXT, "two")
        assertEquals(ShareIntent.Images(listOf(uri("a")), 1, "two"), validateShare(intent))
        val share = read(intent)
        assertEquals(1, share.images.size)
        assertEquals("two", share.text)
        assertEquals(setOf(NoteKind.SHARE_IMAGES_FAILED), share.notes)
        assertEquals(1, opens("a"))
        assertEquals(0, opens("b"))

        // SEND: EXTRA_STREAM only (not in the ClipData) is refused; with no EXTRA_STREAM the ClipData item is the stream.
        assertEquals(ShareIntent.Images(emptyList(), 1, ""), validateShare(sendImage(uri("b"), clipped = listOf(uri("a")))))
        val clipOnly = Intent(Intent.ACTION_SEND).setType("image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        clipOnly.clipData = clip(listOf(uri("a")))
        assertEquals(ShareIntent.Images(listOf(uri("a")), 0, ""), validateShare(clipOnly))
    }

    @Test
    fun thousandUrisAttemptTen() {
        val png = noise(8)
        val uris = (0 until 1000).map { uri("u$it").also { u -> serve(u.lastPathSegment!!, png) } }
        val share = read(sendImages(uris))
        assertEquals(10, share.images.size)
        assertEquals(setOf(NoteKind.SHARE_TOO_MANY), share.notes)
        assertEquals((0 until 10).map { 1 }, (0 until 10).map { opens("u$it") })
        assertEquals(10, RouteProvider.opens.values.sumOf { it.get() })
    }

    @Test
    fun aggregateBytesCapped() {
        val small = noise(100)
        val big = noise(120, seed = 2)
        val s = jpegOf(small).size.toLong()
        val b = jpegOf(big).size.toLong()
        assertTrue("the larger image must not fit after the small one, but fit alone: $s $b", b in (s + 1)..(2 * s))
        serve("a", small)
        serve("b", big)
        serve("c", small)
        // Budget 2s: a (s) kept, b would pass the budget together with a, c fits exactly.
        val share = read(sendImages(listOf(uri("a"), uri("b"), uri("c"))), maxBytes = 2 * s)
        assertEquals(listOf(s, s), share.images.map { it.size.toLong() })
        assertEquals(setOf(NoteKind.SHARE_IMAGES_FAILED), share.notes)
    }

    @Test
    fun malformedExtrasIgnored() {
        // Extras that throw when read (a parcel the platform cannot unpack): ignored, nothing thrown.
        val broken = object : Intent(ACTION_SEND) {
            override fun <T : Any?> getParcelableExtra(name: String?, clazz: Class<T>): T? = throw BadParcelableException("bad")
            override fun <T : Any?> getParcelableArrayListExtra(name: String?, clazz: Class<out T>): ArrayList<T>? = throw BadParcelableException("bad")
            override fun getCharSequenceExtra(name: String?): CharSequence? = throw BadParcelableException("bad")
        }
        broken.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).clipData = clip(listOf(uri("a")))
        for (action in listOf(Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE)) {
            for (type in listOf("text/plain", "image/png")) assertNull("$action $type", validateShare(broken.setAction(action).setType(type)))
        }
        // Wrong types: a text that is no text, a stream that is no URI.
        assertNull(validateShare(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, 42)))
        val wrongStream = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, "content://$OTHER/a")
            .putExtra(Intent.EXTRA_TEXT, 7).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        assertNull(validateShare(wrongStream)) // no stream and no ClipData: not a share
        assertEquals(0, opens("a"))
    }

    @Test
    fun revokedUriNote() {
        RouteProvider.routes["revoked"] = { throw SecurityException("grant revoked") }
        RouteProvider.routes["gone"] = { null }
        serve("ok", noise(16))
        val share = read(sendImages(listOf(uri("revoked"), uri("gone"), uri("ok"))))
        assertEquals(1, share.images.size)
        assertEquals(setOf(NoteKind.SHARE_IMAGES_FAILED), share.notes)
    }

    @Test
    fun ownAuthorityRefused() {
        serve("mine", noise(16))
        val share = read(sendImage(uri("mine", OWN)))
        assertTrue(share.images.isEmpty())
        assertEquals(setOf(NoteKind.SHARE_IMAGES_FAILED), share.notes)
        assertEquals(0, opens("mine"))
    }

    @Test
    fun fileUriRefused() {
        val file = File(app.filesDir, "photo.png").apply { writeBytes(noise(16)) }
        val share = read(sendImage(Uri.fromFile(file)))
        assertTrue(share.images.isEmpty())
        assertEquals(setOf(NoteKind.SHARE_IMAGES_FAILED), share.notes)
    }

    @Test
    fun hugeTextCut() {
        val face = "😀" // one character, two chars: the cut must not split it
        val long = "a".repeat(99_999) + face + "tail"
        val share = read(sendText(long))
        assertEquals("a".repeat(99_999), share.text)
        assertEquals(setOf(NoteKind.SHARE_TEXT_CUT), share.notes)

        val exact = "b".repeat(100_000)
        assertEquals(exact, read(sendText(exact)).text)
        assertEquals(emptySet<NoteKind>(), read(sendText(exact)).notes)

        serve("a", noise(16))
        val caption = read(sendImage(uri("a")).putExtra(Intent.EXTRA_TEXT, "c".repeat(100_001)))
        assertEquals(100_000, caption.text.length)
        assertEquals(setOf(NoteKind.SHARE_TEXT_CUT), caption.notes)
    }

    private fun text(t: String) = Share(0, t, emptyList(), emptySet())

    @Test
    fun newestShareWins() = runBlocking<Unit> {
        val c = container
        val secondRead = CompletableDeferred<Unit>()
        c.readSharedItems = { val t = (it as ShareIntent.Text).text; if (t == "second") secondRead.await(); text(t) }
        c.offerShare(ShareIntent.Text("first"))
        val first = withTimeout(10_000) { c.pendingShare.first { it != null } }!!
        assertEquals("first", first.text)

        c.offerShare(ShareIntent.Text("second"))
        assertNull(c.pendingShare.value) // the older sheet closes at once, before the newer one is read
        secondRead.complete(Unit)
        val second = withTimeout(10_000) { c.pendingShare.first { it != null } }!!
        assertEquals("second", second.text)
        assertTrue(second.version > first.version)

        c.cancelShare(first.version) // a Cancel of the older sheet drops nothing it did not show
        assertEquals(second, c.pendingShare.value)
        assertFalse(c.claimShare(ShareToken(first.version, c.epoch(), "apofis-a")))
        assertEquals(second, c.pendingShare.value)
        c.cancelShare(second.version)
        assertNull(c.pendingShare.value)
    }

    @Test
    fun replacedSharesCannotLeakReads() = runBlocking<Unit> {
        val c = container
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        c.readSharedItems = { intent ->
            val t = (intent as ShareIntent.Text).text
            if (t == "old") {
                entered.complete(Unit)
                withContext(NonCancellable) { release.await() } // a read that ends after it was replaced
            }
            text(t)
        }
        c.offerShare(ShareIntent.Text("old"))
        val oldJob = checkNotNull(c.shareJob)
        withTimeout(10_000) { entered.await() }
        c.offerShare(ShareIntent.Text("new"))
        assertTrue(oldJob.isCancelled)
        val shown = withTimeout(10_000) { c.pendingShare.first { it != null } }!!
        assertEquals("new", shown.text)

        release.complete(Unit)
        withTimeout(10_000) { oldJob.join() }
        assertEquals(shown, c.pendingShare.value)
    }

    @Test
    fun processDeathDropsShare() = runBlocking<Unit> {
        val c = container
        c.readSharedItems = { text("kept in memory") }
        c.offerShare(ShareIntent.Text("x"))
        withTimeout(10_000) { c.pendingShare.first { it != null } }
        val next = newContainer() // a new process: nothing durable holds the share
        try {
            assertNull(next.pendingShare.value)
        } finally {
            next.scope.cancel()
        }
    }

    @Test
    fun nothingWrittenBeforeAccept() = runBlocking<Unit> {
        serve("a", noise(64))
        serve("b", noise(64, seed = 2))
        val c = container
        c.offerShare(checkNotNull(validateShare(sendImages(listOf(uri("a"), uri("b"))).putExtra(Intent.EXTRA_TEXT, "look"))))
        val share = withTimeout(10_000) { c.pendingShare.first { it != null } }!!
        assertEquals(2, share.images.size)
        assertEquals("look", share.text)
        val images = File(app.filesDir, "images")
        assertTrue(images.list().isNullOrEmpty())
        withContext(Dispatchers.IO) { assertNotNull(app.cacheDir.list()) }
        assertEquals(emptyList<String>(), app.cacheDir.list()!!.filter { it.startsWith("decode-") })
    }
}
