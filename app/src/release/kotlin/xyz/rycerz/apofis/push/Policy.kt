package xyz.rycerz.apofis.push

import android.content.Context
import android.content.SharedPreferences

/** Release: ntfy at https://ntfy.sh only. No other distributor, no loopback origin, no test markers in any code path. */
val pushPolicy: PushPolicy = object : PushPolicy {
    override fun distributors(c: Context) = setOf(NTFY_PACKAGE)
    override fun origins(c: Context) = setOf(NTFY_ORIGIN)
    override fun loopbackOrigin(c: Context): String? = null
    override fun recordTitle(c: Context, id: String, opMarker: String) = "$id op-$opMarker"
    override fun recordIntent(prefs: SharedPreferences, editor: SharedPreferences.Editor, id: String, title: String) {}
}
