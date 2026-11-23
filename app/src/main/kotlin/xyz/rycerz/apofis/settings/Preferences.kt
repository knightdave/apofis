package xyz.rycerz.apofis.settings

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

enum class ThemeMode { SYSTEM, LIGHT, DARK }

const val CHAT_FONT_SCALE_MIN = 0.85f
const val CHAT_FONT_SCALE_MAX = 1.5f

/** [value] clamped to the chat text size range and rounded to the nearest 0.05; not a number is the default 1. */
fun chatFontScaleStep(value: Float): Float =
    if (value.isNaN()) 1f else (value.coerceIn(CHAT_FONT_SCALE_MIN, CHAT_FONT_SCALE_MAX) * 20).roundToInt() / 20f

/** Appearance settings. Use the single instance in AppContainer: the flows do not see writes made by another instance. */
class Preferences(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val mode = MutableStateFlow(
        prefs.getString(THEME_MODE, null)?.let { s -> ThemeMode.entries.firstOrNull { it.name == s } } ?: ThemeMode.SYSTEM,
    )
    private val dynamic = MutableStateFlow(prefs.getBoolean(DYNAMIC_COLOR, true))
    private val black = MutableStateFlow(prefs.getBoolean(PURE_BLACK, false))
    private val vibrate = MutableStateFlow(prefs.getBoolean(HAPTICS, true))
    private val enter = MutableStateFlow(prefs.getBoolean(ENTER_TO_SEND, false))
    private val frosted = MutableStateFlow(prefs.getBoolean(BLUR, false))
    private val fontScale = MutableStateFlow(chatFontScaleStep(prefs.getFloat(CHAT_FONT_SCALE, 1f)))
    private val writes = Mutex() // first come, first stored: the last write asked for is the one kept and shown

    // StateFlows so the first frame already has the stored theme.
    val themeMode: StateFlow<ThemeMode> = mode
    val dynamicColor: StateFlow<Boolean> = dynamic
    val pureBlack: StateFlow<Boolean> = black
    val haptics: StateFlow<Boolean> = vibrate
    val enterToSend: StateFlow<Boolean> = enter // Return sends instead of starting a new line
    val blur: StateFlow<Boolean> = frosted // frosted glass behind the composer and the scroll button
    val chatFontScale: StateFlow<Float> = fontScale // the message list's text size

    suspend fun setThemeMode(value: ThemeMode) = write(mode, value) { putString(THEME_MODE, value.name) }
    suspend fun setDynamicColor(value: Boolean) = write(dynamic, value) { putBoolean(DYNAMIC_COLOR, value) }
    suspend fun setPureBlack(value: Boolean) = write(black, value) { putBoolean(PURE_BLACK, value) }
    suspend fun setHaptics(value: Boolean) = write(vibrate, value) { putBoolean(HAPTICS, value) }
    suspend fun setEnterToSend(value: Boolean) = write(enter, value) { putBoolean(ENTER_TO_SEND, value) }
    suspend fun setBlur(value: Boolean) = write(frosted, value) { putBoolean(BLUR, value) }
    suspend fun setChatFontScale(value: Float) = chatFontScaleStep(value).let { write(fontScale, it) { putFloat(CHAT_FONT_SCALE, it) } }

    /** The chat open last, restored after the process was killed when it still exists. */
    var lastChat: String?
        get() = prefs.getString(LAST_CHAT, null)
        set(value) = prefs.edit { putString(LAST_CHAT, value) }

    /** Shown only once stored: a write the storage refuses leaves the flow at the stored value. */
    private suspend fun <T> write(flow: MutableStateFlow<T>, value: T, put: SharedPreferences.Editor.() -> Unit) = writes.withLock {
        if (withContext(Dispatchers.IO) { prefs.edit().apply(put).commit() }) flow.value = value
    }

    private companion object {
        const val THEME_MODE = "theme_mode"
        const val DYNAMIC_COLOR = "dynamic_color"
        const val PURE_BLACK = "pure_black"
        const val HAPTICS = "haptics"
        const val ENTER_TO_SEND = "enter_to_send"
        const val BLUR = "blur"
        const val CHAT_FONT_SCALE = "chat_font_scale"
        const val LAST_CHAT = "last_chat"
    }
}
