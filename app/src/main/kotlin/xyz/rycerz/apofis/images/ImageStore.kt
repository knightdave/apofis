package xyz.rycerz.apofis.images

import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val NAME = Regex("[0-9a-f]{64}")

/**
 * Re-encoded JPEG files named by the lowercase SHA-256 of their bytes, in one directory of the app's private
 * storage. Entries are trusted to be regular files that do not change while they are read.
 */
class ImageStore(private val dir: File, private val clock: () -> Long, internal val beforeRename: () -> Unit = {}) {
    /** Held by [put] and [collect]. Internal for the tests' observation only. */
    internal val lock = Mutex()

    /**
     * Writes [jpeg] to a temp file and atomically replaces `<digest>.jpg`, so a damaged entry is repaired by the
     * next put of the good bytes. Returns the digest, or null with nothing written when [stillWanted] is false
     * under the lock. [stillWanted] must not call back into this store. Throws IOException when the bytes are
     * larger than any stored image may be or the file cannot be written, aged or renamed.
     */
    suspend fun put(jpeg: ByteArray, stillWanted: () -> Boolean = { true }): String? = withContext(Dispatchers.IO) {
        if (jpeg.size > ImageLimits.MAX_SOURCE_BYTES) throw IOException("larger than a stored image may be")
        val sha = sha256(jpeg)
        lock.withLock {
            if (!stillWanted()) return@withLock null
            dir.mkdirs()
            val temp = File.createTempFile("put-", ".tmp", dir)
            try {
                temp.outputStream().use { out ->
                    out.write(jpeg)
                    out.fd.sync()
                }
                beforeRename()
                // The age the collector reads, carried by the rename; a repeated put makes the entry young again.
                if (!temp.setLastModified(clock())) throw IOException("age not set")
                if (!temp.renameTo(File(dir, "$sha.jpg"))) throw IOException("rename failed")
            } finally {
                temp.delete()
            }
            sha
        }
    }

    /** Null when missing, larger than any stored image can be, or no longer hashing to its name. */
    fun read(sha256: String): ByteArray? {
        val file = file(sha256) ?: return null
        if (file.length() > ImageLimits.MAX_SOURCE_BYTES) return null
        return runCatching { file.readBytes() }.getOrNull()?.takeIf { sha256(it) == sha256 }
    }

    /** Null when missing, also when the file goes away while it is looked at. */
    fun size(sha256: String): Long? = file(sha256)?.let { runCatching { Files.size(it.toPath()) }.getOrNull() }

    /** Exactly 64 lowercase hex digits, in this directory only; null when absent. */
    fun file(sha256: String): File? =
        if (NAME.matches(sha256)) File(dir, "$sha256.jpg").takeIf { it.isFile } else null

    /**
     * Deletes every file that is not a referenced `<digest>.jpg` and is at least [minAgeMs] old; with [minAgeMs] at
     * most 0 the age is not read, so a file dated in the future goes too. The references
     * are read under the store lock, so no put lands between reading them and deleting. [referenced] must not
     * call back into this store.
     */
    suspend fun collect(referenced: suspend () -> Set<String>, minAgeMs: Long = 600_000) = withContext(Dispatchers.IO) {
        lock.withLock {
            val keep = referenced()
            val now = clock()
            dir.listFiles()?.forEach { file ->
                val sha = file.name.removeSuffix(".jpg")
                val wanted = file.name.endsWith(".jpg") && NAME.matches(sha) && sha in keep
                if (!wanted && (minAgeMs <= 0 || now - file.lastModified() >= minAgeMs)) file.delete()
            }
        }
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
