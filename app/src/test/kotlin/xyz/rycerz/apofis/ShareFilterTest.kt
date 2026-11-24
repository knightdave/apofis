package xyz.rycerz.apofis

import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The manifest offers Apofis for shared text and images only, and asks for no permission more. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [37])
class ShareFilterTest {
    private val app = ApplicationProvider.getApplicationContext<ApofisApp>()

    /** The activities the chooser would list for [action] with [type] (DEFAULT, as an implicit start adds it). */
    private fun offered(action: String, type: String) =
        app.packageManager.queryIntentActivities(Intent(action).setType(type).setPackage(app.packageName), PackageManager.MATCH_DEFAULT_ONLY)
            .map { it.activityInfo.name }

    @Test
    fun offeredForTextAndImages() {
        val main = listOf(MainActivity::class.java.name)
        for ((action, type) in listOf(
            Intent.ACTION_SEND to "text/plain",
            Intent.ACTION_SEND to "image/png",
            Intent.ACTION_SEND to "image/jpeg",
            Intent.ACTION_SEND to "image/*",
            Intent.ACTION_SEND_MULTIPLE to "image/jpeg",
            Intent.ACTION_SEND_MULTIPLE to "image/*",
        )) {
            assertEquals("$action $type", main, offered(action, type))
        }
    }

    @Test
    fun notOfferedForOthers() {
        for ((action, type) in listOf(
            Intent.ACTION_SEND to "application/pdf",
            Intent.ACTION_SEND to "video/mp4",
            Intent.ACTION_SEND to "text/html",
            Intent.ACTION_SEND_MULTIPLE to "text/plain",
            Intent.ACTION_SEND_MULTIPLE to "video/mp4",
            Intent.ACTION_VIEW to "image/png",
            Intent.ACTION_SENDTO to "text/plain",
            Intent.ACTION_EDIT to "image/png",
        )) {
            assertEquals("$action $type", emptyList<String>(), offered(action, type))
        }
    }

    /** The same list as scripts/check-apk-permissions.sh: the filters add no permission. */
    @Test
    fun permissionsUnchanged() {
        @Suppress("DEPRECATION")
        val requested = app.packageManager.getPackageInfo(app.packageName, PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        val allowed = setOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.WAKE_LOCK",
            "android.permission.RECEIVE_BOOT_COMPLETED",
            "android.permission.ACCESS_LOCAL_NETWORK",
            "android.permission.POST_NOTIFICATIONS",
            "${app.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION",
        )
        assertTrue(requested.toString(), "android.permission.INTERNET" in requested)
        assertTrue((requested - allowed).toString(), allowed.containsAll(requested))
    }
}
