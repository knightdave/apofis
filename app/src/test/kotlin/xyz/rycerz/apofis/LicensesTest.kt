package xyz.rycerz.apofis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.rycerz.apofis.ui.LUCIDE_LICENSE_ASSET

@RunWith(RobolectricTestRunner::class)
class LicensesTest {
    @Test
    fun lucideLicenseShipsVerbatimInTheApp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shipped = context.assets.open(LUCIDE_LICENSE_ASSET).bufferedReader().readText()
        assertEquals(File("../licenses/LUCIDE-LICENSE").readText(), shipped)
        assertTrue(shipped.contains("Lucide Icons and Contributors"))
        assertTrue(shipped.contains("Cole Bemis"))
    }
}
