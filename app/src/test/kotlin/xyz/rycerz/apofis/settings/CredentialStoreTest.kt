package xyz.rycerz.apofis.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import javax.crypto.KeyGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class CredentialStoreTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    // Robolectric has no AndroidKeyStore provider, so the tests supply a software AES key.
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private fun store() = CredentialStore(context) { key }

    @Test
    fun roundTrip() {
        val c = Credentials("https://hermes.example:8642/", "sk-secret-123")
        store().save(c)
        assertEquals(c, store().load())
    }

    @Test
    fun storedValueIsNotPlaintext() {
        store().save(Credentials("https://hermes.example/", "sk-very-secret-key"))
        val raw = context.filesDir.parentFile!!.resolve("shared_prefs").listFiles()!!.joinToString { File(it.path).readText() }
        assertFalse(raw.contains("sk-very-secret-key"))
    }

    @Test
    fun clearRemoves() {
        store().save(Credentials("https://hermes.example/", "k"))
        store().clear()
        assertNull(store().load())
    }
}
