package xyz.rycerz.apofis.images

import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** Plain JVM: the store uses only java.io, java.security and coroutines. */
class ImageStoreTest {
    private val dir = Files.createTempDirectory("images").toFile()
    private var now = 1_700_000_000_000L
    private val store = ImageStore(dir, clock = { now })

    // SHA-256 of the three ASCII bytes "abc" (FIPS 180-2, appendix B.1): an independent check of the name.
    private val abc = "abc".toByteArray()
    private val abcSha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

    private fun names() = dir.list()!!.sorted()

    @Test
    fun putIsIdempotent() = runBlocking {
        assertEquals(3, abc.size)
        assertEquals(abcSha, store.put(abc))
        assertEquals(abcSha, store.put(abc.copyOf()))
        assertEquals(listOf("$abcSha.jpg"), names())
        assertArrayEquals(abc, store.read(abcSha))
        assertEquals(3L, store.size(abcSha))
    }

    @Test
    fun putRefreshesAgeOfExistingFile() = runBlocking {
        store.put(abc)
        assertEquals(now, File(dir, "$abcSha.jpg").lastModified())
        now += 700_000
        store.put(abc)
        assertEquals(now, File(dir, "$abcSha.jpg").lastModified())
        now += 599_999
        store.collect({ emptySet() })
        assertEquals(listOf("$abcSha.jpg"), names())
    }

    @Test
    fun putNotWantedWritesNothing() = runBlocking {
        var askedUnderLock = false
        val sha = store.put(abc, stillWanted = { askedUnderLock = store.lock.isLocked; false })
        assertNull(sha)
        assertTrue(askedUnderLock)
        assertTrue(names().isEmpty())
        assertNull(store.file(abcSha))
    }

    @Test
    fun readRejectsTamperedFile() = runBlocking {
        store.put(abc)
        File(dir, "$abcSha.jpg").writeBytes("abd".toByteArray())
        assertNull(store.read(abcSha))
        assertNotNull(store.file(abcSha))
    }

    @Test
    fun fileByDigestOnly() = runBlocking {
        store.put(abc)
        assertEquals(File(dir, "$abcSha.jpg"), store.file(abcSha))
        assertNull(store.file(abcSha.take(32)))
        assertNull(store.file(abcSha.uppercase()))
        assertNull(store.file("$abcSha.jpg"))
        assertNull(store.file("../" + abcSha.drop(3)))
        assertNull(store.file("0".repeat(64)))
        assertNull(store.read(abcSha.take(32)))
        assertNull(store.size(abcSha.take(32)))
    }

    @Test
    fun collectKeepsReferencedAndYoung() = runBlocking {
        val old = store.put("old".toByteArray())!!
        val kept = store.put("kept".toByteArray())!!
        now += 600_000
        val young = store.put("young".toByteArray())!!
        store.collect({ setOf(kept) })
        assertEquals(listOf("$kept.jpg", "$young.jpg").sorted(), names())
        assertNull(store.file(old))
    }

    @Test
    fun collectReadsReferencesUnderLock() = runBlocking {
        val sha = store.put(abc)!!
        now += 600_000
        var readUnderLock = false
        store.collect({ readUnderLock = store.lock.isLocked; setOf(sha) })
        assertTrue(readUnderLock)
        assertEquals(listOf("$sha.jpg"), names())
        assertFalse(store.lock.isLocked)
    }

    @Test
    fun ageBoundaryExact() = runBlocking {
        val a = store.put("a".toByteArray())!!
        val b = store.put("b".toByteArray())!! // same mtime as a
        now += 1
        val c = store.put("c".toByteArray())!!
        assertEquals(File(dir, "$a.jpg").lastModified(), File(dir, "$b.jpg").lastModified())
        now += 599_999 // a and b exactly 600,000 ms old, c 599,999 ms
        store.collect({ emptySet() })
        assertEquals(listOf("$c.jpg"), names())
    }

    @Test
    fun atomicWrite() = runBlocking {
        var seen: List<String> = emptyList()
        val crashing = ImageStore(dir, clock = { now }, beforeRename = {
            seen = names()
            throw IOException("power cut")
        })
        try {
            crashing.put(abc)
            fail("put must fail when the rename never happens")
        } catch (e: IOException) {
            assertEquals("power cut", e.message)
        }
        // Before the rename only a temp file holds the bytes; after the failure nothing is left.
        assertEquals(1, seen.size)
        assertFalse(seen.single().endsWith(".jpg"))
        assertTrue(names().isEmpty())
        assertNull(store.read(abcSha))
        assertEquals(abcSha, store.put(abc))
        assertArrayEquals(abc, store.read(abcSha))
    }

    @Test
    fun staleTempFileCollected() = runBlocking {
        val sha = store.put(abc)!!
        File(dir, "put-123.tmp").writeBytes(abc)
        File(dir, "notes.txt").writeBytes(abc)
        File(dir, "${sha.take(32)}.jpg").writeBytes(abc)
        listOf("put-123.tmp", "notes.txt", "${sha.take(32)}.jpg").forEach { File(dir, it).setLastModified(now) }
        File(dir, "put-456.tmp").writeBytes(abc)
        File(dir, "put-456.tmp").setLastModified(now + 1)
        now += 600_000
        // The referenced name protects only its own file; the look-alikes go by age alone.
        store.collect({ setOf(sha, sha.take(32), "put-123.tmp", "notes.txt") })
        assertEquals(listOf("$sha.jpg", "put-456.tmp").sorted(), names())
    }
}
