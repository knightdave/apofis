package xyz.rycerz.apofis.settings

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class PreferencesTest {
    @Test
    fun enterToSendIsOffUntilTurnedOnAndThenStored() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val prefs = Preferences(context)
        assertFalse(prefs.enterToSend.value)
        runBlocking { prefs.setEnterToSend(true) }
        assertTrue(prefs.enterToSend.value)
        assertTrue("a new instance (process restart) reads it back", Preferences(context).enterToSend.value)
        runBlocking { prefs.setEnterToSend(false) }
        assertFalse(Preferences(context).enterToSend.value)
    }
}
