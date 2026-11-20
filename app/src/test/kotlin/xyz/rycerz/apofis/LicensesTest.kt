package xyz.rycerz.apofis

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.security.MessageDigest
import xyz.rycerz.apofis.ui.COMMONMARK_LICENSE_ASSET
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

    @Test
    fun commonmarkLicenseIsPinned() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shipped = context.assets.open(COMMONMARK_LICENSE_ASSET).use { it.readBytes() }
        val file = File("../licenses/COMMONMARK-LICENSE").readBytes()
        assertTrue(shipped.contentEquals(file))
        // LICENSE.txt of commonmark/commonmark-java at tag commonmark-parent-0.33.0, as recorded in the Task 2 qualification.
        val sha = MessageDigest.getInstance("SHA-256").digest(file).joinToString("") { "%02x".format(it) }
        assertEquals("db0ca9fbe898f07ba8a637e6f7b53ce7f8669c67673a8edcf30562e62935e8bb", sha)
    }
}
