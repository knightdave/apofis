package xyz.rycerz.apofis.notify

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationManager
import android.content.Context
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

const val NOTIFY_PREFS = "notify"
private const val ASKED = "permission_asked" // the first send happened (asked, or already granted then): never asked again automatically

/** What may show a notification; each part is shown separately in Settings. */
data class NotificationAccess(val runtime: Runtime, val appEnabled: Boolean, val repliesEnabled: Boolean, val answersEnabled: Boolean)

enum class Runtime { NOT_NEEDED, NEVER_ASKED, DENIED, GRANTED }

fun notificationAccess(context: Context, prefs: SharedPreferences): NotificationAccess {
    val nm = context.getSystemService(NotificationManager::class.java)
    fun enabled(channel: String) = nm.getNotificationChannel(channel)?.let { it.importance != NotificationManager.IMPORTANCE_NONE } == true
    return NotificationAccess(runtime(context, prefs), nm.areNotificationsEnabled(), enabled(CHANNEL_REPLIES), enabled(CHANNEL_ANSWERS))
}

private fun runtime(context: Context, prefs: SharedPreferences): Runtime = when {
    Build.VERSION.SDK_INT < 33 -> Runtime.NOT_NEEDED
    context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED -> Runtime.GRANTED
    prefs.getBoolean(ASKED, false) -> Runtime.DENIED
    else -> Runtime.NEVER_ASKED
}

private val askLock = Any()

/**
 * The first accepted send's one chance, decided off the main thread: on API 33+ it is used up by the first call
 * whatever the state, so a permission granted then and revoked later is never asked for again. The mark is committed
 * before the answer, so a death or a failed write never leads to a second automatic ask; serialized, so repeated sends
 * ask at most once. True when the caller should launch the permission dialog now.
 */
@SuppressLint("UseKtx") // commit()'s result is checked; androidx.core is not a direct dependency
internal fun askNotificationsOnce(context: Context, prefs: SharedPreferences): Boolean = synchronized(askLock) {
    if (Build.VERSION.SDK_INT < 33 || prefs.getBoolean(ASKED, false)) return false
    val granted = runtime(context, prefs) == Runtime.GRANTED
    if (!prefs.edit().putBoolean(ASKED, true).commit()) return false
    !granted
}

/**
 * For the first accepted send (and an explicit Settings tap): asks once ever, on API 33+, while never asked. The
 * decision runs on IO; only the dialog launch runs here, through this composition's own launcher.
 */
@SuppressLint("InlinedApi") // launched only after askNotificationsOnce, which is API 33+
@Composable
fun rememberAskNotificationsOnce(prefs: SharedPreferences): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return remember(launcher, prefs) {
        {
            scope.launch {
                if (withContext(Dispatchers.IO) { askNotificationsOnce(context, prefs) }) launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
