package xyz.rycerz.apofis.push

import org.unifiedpush.android.connector.data.PublicKeySet
import org.unifiedpush.android.connector.keys.KeyManager

/**
 * No Web Push keys: nothing is generated, nothing decrypted. The connector then hands a message over as plain bytes,
 * which the app never reads. Every connector call passes this, so the library's tink-based default is never loaded.
 */
object PlainKeyManager : KeyManager {
    override fun decrypt(instance: String, sealed: ByteArray): ByteArray? = null
    override fun generate(instance: String) {}
    override fun getPublicKeySet(instance: String): PublicKeySet? = null
    override fun exists(instance: String) = true
    override fun delete(instance: String) {}
}
