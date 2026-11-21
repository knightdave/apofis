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
import xyz.rycerz.apofis.ui.COMMONMARK_NOTICE_ASSET
import xyz.rycerz.apofis.ui.LUCIDE_LICENSE_ASSET
import xyz.rycerz.apofis.ui.UNIFIEDPUSH_LICENSE_ASSET

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

    @Test
    fun commonmarkJarNoticeShipsVerbatim() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shipped = context.assets.open(COMMONMARK_NOTICE_ASSET).use { it.readBytes() }
        assertTrue(shipped.contentEquals(File("../licenses/COMMONMARK-NOTICE").readBytes()))
        val sha = MessageDigest.getInstance("SHA-256").digest(shipped).joinToString("") { "%02x".format(it) }
        assertEquals("11cfcf6da0142fb1971c1f40f5924ddded14454e2793d7f1dd87c9f4abe42dfd", sha)
        // The notice inside each of the four commonmark jars on the classpath is the one shipped.
        val inJars = ClassLoader.getSystemClassLoader().getResources("META-INF/LICENSE.txt").toList().filter { "/org.commonmark/" in it.toString() }
        assertEquals(inJars.toString(), 4, inJars.size)
        for (url in inJars) assertTrue("$url carries another notice", url.openStream().use { it.readBytes() }.contentEquals(shipped))
    }

    @Test
    fun unifiedPushLicenseIsPinned() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val shipped = context.assets.open(UNIFIEDPUSH_LICENSE_ASSET).use { it.readBytes() }
        val file = File("../licenses/UNIFIEDPUSH-CONNECTOR-LICENSE").readBytes()
        assertTrue(shipped.contentEquals(file))
        // LICENSE of UnifiedPush/android-connector at tag 3.3.6, as recorded in the Milestone 3 Task 2 qualification.
        val sha = MessageDigest.getInstance("SHA-256").digest(file).joinToString("") { "%02x".format(it) }
        assertEquals("65e7ce63d83ef0a5aa7b8a568f0524b7d0943179257f4ba086c4a126d57f08fa", sha)
        assertTrue(context.getString(R.string.licenses_unifiedpush).contains("Apache"))
    }
}
