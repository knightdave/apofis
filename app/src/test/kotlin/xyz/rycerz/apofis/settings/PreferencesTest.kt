package xyz.rycerz.apofis.settings

import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class PreferencesTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test
    fun enterToSendIsOffUntilTurnedOnAndThenStored() {
        val prefs = Preferences(context)
        assertFalse(prefs.enterToSend.value)
        runBlocking { prefs.setEnterToSend(true) }
        assertTrue(prefs.enterToSend.value)
        assertTrue("a new instance (process restart) reads it back", Preferences(context).enterToSend.value)
        runBlocking { prefs.setEnterToSend(false) }
        assertFalse(Preferences(context).enterToSend.value)
    }

    @Test
    fun defaults() {
        val prefs = Preferences(context)
        assertTrue(prefs.haptics.value)
        assertFalse(prefs.enterToSend.value)
        assertFalse("frosted glass is off until turned on", prefs.blur.value)
        assertEquals(1f, prefs.chatFontScale.value)
    }

    @Test
    fun chatFontScaleClampedAndRounded() {
        val prefs = Preferences(context)
        assertEquals(1f, prefs.chatFontScale.value)
        for ((asked, stored) in listOf(2f to 1.5f, 0.5f to 0.85f, 1.12f to 1.1f, 1.13f to 1.15f, Float.NaN to 1f)) {
            runBlocking { prefs.setChatFontScale(asked) }
            assertEquals("$asked", stored, prefs.chatFontScale.value)
            assertEquals("$asked read back by a new instance", stored, Preferences(context).chatFontScale.value)
        }
    }

    @Test
    fun blurIsStored() {
        val prefs = Preferences(context)
        runBlocking { prefs.setBlur(true) }
        assertTrue(prefs.blur.value)
        assertTrue(Preferences(context).blur.value)
    }

    /** A write the storage refuses does not show as applied: every flow keeps the stored value. */
    @Test
    fun aFailedWriteIsNotShown() {
        val prefs = Preferences(Refusing(context))
        runBlocking {
            prefs.setThemeMode(ThemeMode.DARK)
            prefs.setDynamicColor(false)
            prefs.setPureBlack(true)
            prefs.setHaptics(false)
            prefs.setEnterToSend(true)
            prefs.setBlur(true)
            prefs.setChatFontScale(1.5f)
        }
        assertEquals(ThemeMode.SYSTEM, prefs.themeMode.value)
        assertTrue(prefs.dynamicColor.value)
        assertFalse(prefs.pureBlack.value)
        assertTrue(prefs.haptics.value)
        assertFalse(prefs.enterToSend.value)
        assertFalse(prefs.blur.value)
        assertEquals(1f, prefs.chatFontScale.value)
    }

    /** Settings storage whose every commit fails (a full disk). */
    private class Refusing(base: Context) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String, mode: Int): SharedPreferences {
            val real = super.getSharedPreferences("refusing", mode)
            return object : SharedPreferences by real {
                override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor by real.edit() {
                    override fun commit() = false
                }
            }
        }
    }
}
