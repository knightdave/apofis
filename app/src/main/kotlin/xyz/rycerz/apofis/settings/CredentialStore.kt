package xyz.rycerz.apofis.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.core.content.edit
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Credentials(val baseUrl: String, val apiKey: String) {
    override fun toString() = "Credentials(baseUrl=$baseUrl, apiKey=***)"
}

private const val FILE = "credentials"
private const val BASE_URL = "base_url"
private const val API_KEY = "api_key" // base64 of IV followed by the AES-GCM ciphertext
private const val KEY_ALIAS = "apofis-credentials"
private const val TRANSFORMATION = "AES/GCM/NoPadding"
private const val IV_BYTES = 12

/**
 * The server URL in plain app-private preferences and the API key (it grants terminal access on the server)
 * encrypted with an AES-GCM key that never leaves the Android Keystore. Neither value is ever logged.
 * [key] exists for tests: Robolectric has no AndroidKeyStore provider.
 */
class CredentialStore(context: Context, private val key: () -> SecretKey = ::keystoreKey) {
    private val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Null when nothing is stored or the key can no longer be decrypted (the user then connects again). */
    fun load(): Credentials? {
        val url = prefs.getString(BASE_URL, null) ?: return null
        val blob = prefs.getString(API_KEY, null) ?: return null
        return try {
            val bytes = Base64.decode(blob, Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, bytes, 0, IV_BYTES))
            Credentials(url, String(cipher.doFinal(bytes, IV_BYTES, bytes.size - IV_BYTES), Charsets.UTF_8))
        } catch (_: GeneralSecurityException) {
            null
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    fun save(c: Credentials) {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, key()) // the provider picks a fresh random IV
        val blob = cipher.iv + cipher.doFinal(c.apiKey.toByteArray(Charsets.UTF_8))
        check(cipher.iv.size == IV_BYTES)
        prefs.edit(commit = true) {
            putString(BASE_URL, c.baseUrl)
            putString(API_KEY, Base64.encodeToString(blob, Base64.NO_WRAP))
        }
    }

    fun clear() = prefs.edit(commit = true) { clear() }
}

@Synchronized
private fun keystoreKey(): SecretKey {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
    val spec = KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
        .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
        .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
        .setKeySize(256)
        .build()
    return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply { init(spec) }.generateKey()
}
