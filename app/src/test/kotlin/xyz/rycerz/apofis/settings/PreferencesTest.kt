package xyz.rycerz.apofis.settings

import android.content.Context
import androidx.test.core.app.ApplicationProvider
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
        prefs.setEnterToSend(true)
        assertTrue(prefs.enterToSend.value)
        assertTrue("a new instance (process restart) reads it back", Preferences(context).enterToSend.value)
        prefs.setEnterToSend(false)
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
            prefs.setChatFontScale(asked)
            assertEquals("$asked", stored, prefs.chatFontScale.value)
            assertEquals("$asked read back by a new instance", stored, Preferences(context).chatFontScale.value)
        }
    }

    /** Every setter shows its value at once (no wait for the disk), and a new instance reads it. */
    @Test
    fun everySetterShowsAtOnceAndANewInstanceReadsIt() {
        val prefs = Preferences(context)
        prefs.setThemeMode(ThemeMode.DARK)
        prefs.setDynamicColor(false)
        prefs.setPureBlack(true)
        prefs.setHaptics(false)
        prefs.setEnterToSend(true)
        prefs.setBlur(true)
        prefs.setChatFontScale(1.25f)
        for (p in listOf(prefs, Preferences(context))) {
            assertEquals(ThemeMode.DARK, p.themeMode.value)
            assertFalse(p.dynamicColor.value)
            assertTrue(p.pureBlack.value)
            assertFalse(p.haptics.value)
            assertTrue(p.enterToSend.value)
            assertTrue(p.blur.value)
            assertEquals(1.25f, p.chatFontScale.value)
        }
    }
}
