package xyz.rycerz.apofis.push

import android.content.Context
import android.content.SharedPreferences

internal const val E2E_PACKAGE = "xyz.rycerz.apofis.e2e"

// Test keys the emulator harness injects into the push prefs. Only a valid pair of both changes anything beyond also
// accepting the e2e distributor package.
internal const val E2E_ORIGIN = "e2e_origin"
internal const val E2E_NONCE = "e2e_nonce"
internal const val TEST_INTENTS = "test_intents"

private val LOOPBACK = Regex("http://127\\.0\\.0\\.1:([1-9][0-9]{0,4})")
private val NONCE = Regex("[A-Za-z0-9]{1,20}")

/** (loopback origin, nonce), only when both are valid and the port is 1 to 65535. */
private fun testKeys(prefs: SharedPreferences): Pair<String, String>? {
    val origin = prefs.getString(E2E_ORIGIN, null) ?: return null
    val nonce = prefs.getString(E2E_NONCE, null)?.takeIf(NONCE::matches) ?: return null
    val port = LOOPBACK.matchEntire(origin)?.groupValues?.get(1)?.toInt() ?: return null
    return if (port <= 65535) origin to nonce else null
}

/** Debug: ntfy first, then the e2e distributor; with the test keys, the loopback origin, e2e titles and their intents. */
val pushPolicy: PushPolicy = object : PushPolicy {
    override fun distributors(c: Context) = setOf(NTFY_PACKAGE, E2E_PACKAGE)
    override fun origins(c: Context) = setOf(NTFY_ORIGIN)
    override fun loopbackOrigin(c: Context) = testKeys(pushPrefs(c))?.first
    override fun recordTitle(c: Context, id: String, opMarker: String) =
        "$id op-$opMarker" + (testKeys(pushPrefs(c))?.let { " e2e-${it.second}" } ?: "")

    override fun recordIntent(prefs: SharedPreferences, editor: SharedPreferences.Editor, id: String, title: String) {
        if (testKeys(prefs) == null) return
        editor.putString(TEST_INTENTS, prefs.getString(TEST_INTENTS, null)?.let { "$it\n" }.orEmpty() + "$id\t$title")
    }
}
