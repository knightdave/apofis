package xyz.rycerz.apofis.push

import android.content.Context
import android.content.SharedPreferences

const val NTFY_PACKAGE = "io.heckel.ntfy"
const val NTFY_ORIGIN = "https://ntfy.sh"
internal const val PUSH_PREFS = "push"

/**
 * Which distributors and endpoint origins the build accepts. `pushPolicy` is defined in the variant source set: the
 * release one accepts only ntfy at https://ntfy.sh, and only the debug one knows the e2e distributor and a loopback origin.
 */
interface PushPolicy {
    /** In preference order. */
    fun distributors(c: Context): Set<String>
    fun origins(c: Context): Set<String>
    fun loopbackOrigin(c: Context): String?
    fun recordTitle(c: Context, id: String, opMarker: String): String

    /** Runs on the editor that commits the reservation of [id], so both reach the disk together or not at all. */
    fun recordIntent(prefs: SharedPreferences, editor: SharedPreferences.Editor, id: String, title: String)
}

internal fun pushPrefs(c: Context): SharedPreferences = c.getSharedPreferences(PUSH_PREFS, Context.MODE_PRIVATE)
