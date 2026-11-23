package xyz.rycerz.apofis.images

import java.io.File
import java.io.IOException
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val NAME = Regex("[0-9a-f]{64}")

/** Re-encoded JPEG files named by the lowercase SHA-256 of their bytes, in one directory. */
class ImageStore(private val dir: File, private val clock: () -> Long, internal val beforeRename: () -> Unit = {}) {
    /** Held by [put] and [collect]. */
    internal val lock = Mutex()

    /** The digest, or null with nothing written when [stillWanted] is false under the lock. */
    suspend fun put(jpeg: ByteArray, stillWanted: () -> Boolean = { true }): String? = withContext(Dispatchers.IO) {
        val sha = sha256(jpeg)
        lock.withLock {
            if (!stillWanted()) return@withLock null
            val target = File(dir, "$sha.jpg")
            if (!target.isFile) {
                dir.mkdirs()
                val temp = File.createTempFile("put-", ".tmp", dir)
                try {
                    temp.outputStream().use { out ->
                        out.write(jpeg)
                        out.fd.sync()
                    }
                    beforeRename()
                    if (!temp.renameTo(target)) throw IOException("rename failed")
                } finally {
                    temp.delete()
                }
            }
            // The age the collector reads; a repeated put makes an old file young again.
            target.setLastModified(clock())
            sha
        }
    }

    /** Null when missing, larger than any stored image can be, or no longer hashing to its name. */
    fun read(sha256: String): ByteArray? {
        val file = file(sha256) ?: return null
        if (file.length() > ImageLimits.MAX_SOURCE_BYTES) return null
        return runCatching { file.readBytes() }.getOrNull()?.takeIf { sha256(it) == sha256 }
    }

    fun size(sha256: String): Long? = file(sha256)?.length()

    /** Exactly 64 lowercase hex digits, in this directory only; null when absent. */
    fun file(sha256: String): File? =
        if (NAME.matches(sha256)) File(dir, "$sha256.jpg").takeIf { it.isFile } else null

    /**
     * Deletes every file that is not a referenced `<digest>.jpg` and is at least [minAgeMs] old. The references
     * are read under the store lock, so no put lands between reading them and deleting.
     */
    suspend fun collect(referenced: suspend () -> Set<String>, minAgeMs: Long = 600_000) = withContext(Dispatchers.IO) {
        lock.withLock {
            val keep = referenced()
            val now = clock()
            dir.listFiles()?.forEach { file ->
                val sha = file.name.removeSuffix(".jpg")
                val wanted = file.name.endsWith(".jpg") && NAME.matches(sha) && sha in keep
                if (!wanted && now - file.lastModified() >= minAgeMs) file.delete()
            }
        }
    }
}

private fun sha256(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
