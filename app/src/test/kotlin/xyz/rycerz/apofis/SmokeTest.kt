package xyz.rycerz.apofis

import android.content.Context
import android.content.res.Configuration
import androidx.test.core.app.ApplicationProvider
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SmokeTest {
    @Test
    fun appNameIsApofis() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("Apofis", context.getString(R.string.app_name))

        val pl = Configuration(context.resources.configuration).apply { setLocale(Locale.forLanguageTag("pl")) }
        assertEquals("Apofis", context.createConfigurationContext(pl).getString(R.string.app_name))
    }
}
