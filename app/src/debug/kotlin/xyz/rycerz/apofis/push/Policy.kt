package xyz.rycerz.apofis.push

import android.content.Context
import android.content.SharedPreferences

internal const val E2E_PACKAGE = "xyz.rycerz.apofis.e2e"

// Test keys the emulator harness injects into the push prefs. Without both, the debug policy differs from release
// only by also accepting the e2e distributor package.
internal const val E2E_ORIGIN = "e2e_origin"
internal const val E2E_NONCE = "e2e_nonce"
internal const val TEST_INTENTS = "test_intents"

private val LOOPBACK = Regex("http://127\\.0\\.0\\.1:[1-9][0-9]{0,4}")
private val NONCE = Regex("[A-Za-z0-9]{1,20}")

private fun nonce(prefs: SharedPreferences) = prefs.getString(E2E_NONCE, null)?.takeIf(NONCE::matches)

/** Debug: ntfy first, then the e2e distributor; the injected loopback origin; e2e-marked titles and their intent history. */
val pushPolicy: PushPolicy = object : PushPolicy {
    override fun distributors(c: Context) = setOf(NTFY_PACKAGE, E2E_PACKAGE)
    override fun origins(c: Context) = setOf(NTFY_ORIGIN)
    override fun loopbackOrigin(c: Context) = pushPrefs(c).takeIf { nonce(it) != null }?.getString(E2E_ORIGIN, null)?.takeIf(LOOPBACK::matches)
    override fun recordTitle(c: Context, id: String, opMarker: String) =
        "$id op-$opMarker" + (nonce(pushPrefs(c))?.let { " e2e-$it" } ?: "")

    override fun recordIntent(prefs: SharedPreferences, editor: SharedPreferences.Editor, id: String, title: String) {
        if (nonce(prefs) == null) return
        editor.putString(TEST_INTENTS, prefs.getString(TEST_INTENTS, null)?.let { "$it\n" }.orEmpty() + "$id\t$title")
    }
}
