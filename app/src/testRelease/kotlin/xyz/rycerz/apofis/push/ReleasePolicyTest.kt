package xyz.rycerz.apofis.push

import android.app.Application
import android.content.ComponentName
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.Properties
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** Release only (app/src/testRelease): the shipping policy is ntfy at https://ntfy.sh and nothing else. */
@RunWith(RobolectricTestRunner::class)
class ReleasePolicyTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()

    @Test
    fun ntfyOnly() {
        val pm = shadowOf(app.packageManager)
        for (pkg in listOf("xyz.rycerz.apofis.e2e", NTFY_PACKAGE)) {
            pm.installPackage(PackageInfo().apply { packageName = pkg; applicationInfo = ApplicationInfo().apply { packageName = pkg } })
            val cn = ComponentName(pkg, "$pkg.Distributor")
            pm.addReceiverIfNotPresent(cn).exported = true
            pm.addIntentFilterForReceiver(cn, IntentFilter("org.unifiedpush.android.distributor.REGISTER"))
        }
        assertTrue("xyz.rycerz.apofis.e2e" in RealUnifiedPush.distributors(app))
        assertEquals(setOf(NTFY_PACKAGE), pushPolicy.distributors(app))
        assertEquals(setOf("https://ntfy.sh"), pushPolicy.origins(app))
    }

    @Test
    fun testKeysChangeNothing() {
        // The keys the debug policy reads; a release build ignores them.
        val prefs = pushPrefs(app)
        assertTrue(prefs.edit().putString("e2e_origin", "http://127.0.0.1:41234").putString("e2e_nonce", "abc123").commit())
        assertNull(pushPolicy.loopbackOrigin(app))
        assertEquals("apofis-push-x-1 op-0123456789abcdef", pushPolicy.recordTitle(app, "apofis-push-x-1", "0123456789abcdef"))
        val written = mutableListOf<String>()
        val editor = object : SharedPreferences.Editor by prefs.edit() {
            override fun putString(key: String, value: String?) = also { written += key }
        }
        pushPolicy.recordIntent(prefs, editor, "apofis-push-x-1", "t")
        assertEquals(emptyList<String>(), written)
        assertNull(validateEndpoint("http://127.0.0.1:41234/upWIRECHECK012?up=1", pushPolicy.origins(app), pushPolicy.loopbackOrigin(app)))
    }

    @Test
    fun mergedManifestHasNoTestDistributorOrHost() {
        // The merged release manifest Robolectric runs this test with (AGP's test_config.properties).
        val config = Properties().apply { ClassLoader.getSystemClassLoader().getResourceAsStream("com/android/tools/test_config.properties")!!.use(::load) }
        val manifest = File(config.getProperty("android_merged_manifest")).readText()
        assertTrue("release" in config.getProperty("android_merged_manifest"))
        assertTrue("<package android:name=\"$NTFY_PACKAGE\" />" in manifest)
        assertTrue("xyz.rycerz.apofis.push.PushReceiver" in manifest)
        for (absent in listOf("xyz.rycerz.apofis.e2e", "ComponentActivity", "MessagingReceiverImpl", "RaiseToForegroundService", "LinkActivity", "FOREGROUND_SERVICE")) {
            assertFalse(absent, absent in manifest)
        }
    }

    @Test
    fun registrationLeftByADebugBuildIsReplaced() = runTest {
        // Same app id and key: a debug install journaled an e2e registration and a loopback endpoint, then release was installed over it.
        val prefs = FakePrefs(mutableMapOf(Keys.ENABLED to true, Keys.GENERATION to 1L, Keys.REGISTRATION to "g1\txyz.rycerz.apofis.e2e\ttok-e2e\t0",
            Keys.ENDPOINT_NEW to "http://127.0.0.1:41234/upWIRECHECK012?up=1", Keys.ENDPOINT_NEW_TOKEN to "tok-e2e"))
        val port = FakePort().apply { installed += "xyz.rycerz.apofis.e2e" }
        val server = FakeServer("https://a.example/")
        val r = PushRegistrar(app, prefs, { FakeRecordApi(server) }, { 0L }, { 0L }, backgroundScope, pushPolicy, port)
        assertNull("the current epoch, yet outside the release policy", currentRegistration(prefs, setOf(NTFY_PACKAGE), 0))
        r.publishPending()
        assertEquals(listOf("unregister g1 tok-e2e", "save $NTFY_PACKAGE", "register g2"), port.calls)
        assertTrue("no record for the loopback endpoint", server.created.isEmpty())
        assertEquals(NTFY_PACKAGE, registrationOf(prefs)?.distributor)
    }
}
