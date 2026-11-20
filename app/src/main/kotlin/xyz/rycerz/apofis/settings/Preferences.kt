package xyz.rycerz.apofis.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

enum class ThemeMode { SYSTEM, LIGHT, DARK }

/** Appearance settings. Use the single instance in AppContainer: the flows do not see writes made by another instance. */
class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val mode = MutableStateFlow(
        prefs.getString(THEME_MODE, null)?.let { s -> ThemeMode.entries.firstOrNull { it.name == s } } ?: ThemeMode.SYSTEM,
    )
    private val dynamic = MutableStateFlow(prefs.getBoolean(DYNAMIC_COLOR, true))
    private val black = MutableStateFlow(prefs.getBoolean(PURE_BLACK, false))

    // StateFlows so the first frame already has the stored theme.
    val themeMode: StateFlow<ThemeMode> = mode
    val dynamicColor: StateFlow<Boolean> = dynamic
    val pureBlack: StateFlow<Boolean> = black

    suspend fun setThemeMode(value: ThemeMode) = write(mode, value) { putString(THEME_MODE, value.name) }
    suspend fun setDynamicColor(value: Boolean) = write(dynamic, value) { putBoolean(DYNAMIC_COLOR, value) }
    suspend fun setPureBlack(value: Boolean) = write(black, value) { putBoolean(PURE_BLACK, value) }

    /** The chat open last, restored after the process was killed when it still exists. */
    var lastChat: String?
        get() = prefs.getString(LAST_CHAT, null)
        set(value) = prefs.edit { putString(LAST_CHAT, value) }

    private suspend fun <T> write(flow: MutableStateFlow<T>, value: T, put: SharedPreferences.Editor.() -> Unit) {
        flow.value = value
        withContext(Dispatchers.IO) { prefs.edit(commit = true, action = put) }
    }

    private companion object {
        const val THEME_MODE = "theme_mode"
        const val DYNAMIC_COLOR = "dynamic_color"
        const val PURE_BLACK = "pure_black"
        const val LAST_CHAT = "last_chat"
    }
}
