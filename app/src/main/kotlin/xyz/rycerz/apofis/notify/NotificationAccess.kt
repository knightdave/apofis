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
import kotlinx.coroutines.NonCancellable
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
private var askInFlight = false // a dialog is about to be launched by some UI; guarded by [askLock]

/**
 * The first accepted send's one chance, decided off the main thread. On API 33+: already granted uses the chance up
 * (committed now), so a later revocation is never asked about; otherwise true, and the caller launches the dialog and
 * then calls [askLaunched], which uses the chance up. A UI that goes away before launching calls [askAbandoned]
 * instead, leaving the chance for the next accepted send. Serialized, so repeated sends launch at most once.
 */
@SuppressLint("UseKtx", "ApplySharedPref") // on IO, and durable before the answer; androidx.core is not a direct dependency
internal fun shouldAskNotifications(context: Context, prefs: SharedPreferences): Boolean = synchronized(askLock) {
    if (Build.VERSION.SDK_INT < 33 || askInFlight || prefs.getBoolean(ASKED, false)) return false
    if (runtime(context, prefs) == Runtime.GRANTED) {
        prefs.edit().putBoolean(ASKED, true).commit()
        return false
    }
    askInFlight = true
    true
}

/** The dialog was launched: the chance is used up (on IO; durable before the next decision). */
@SuppressLint("UseKtx", "ApplySharedPref") // on IO, and durable before the next decision
internal fun askLaunched(prefs: SharedPreferences) = synchronized(askLock) {
    prefs.edit().putBoolean(ASKED, true).commit()
    askInFlight = false
}

/** The UI went away before launching: the next accepted send may ask. */
internal fun askAbandoned() = synchronized(askLock) { askInFlight = false }

/**
 * For the first accepted send (and an explicit Settings tap): asks once ever, on API 33+, while never asked. The
 * decision and the mark run on IO; only the dialog launch runs here, through this composition's own launcher, and
 * the chance is used up only once that launch happened.
 */
@SuppressLint("InlinedApi") // launched only after shouldAskNotifications, which is API 33+
@Composable
fun rememberAskNotificationsOnce(prefs: SharedPreferences): () -> Unit {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    return remember(launcher, prefs) {
        {
            scope.launch {
                var asking = false // set inside the IO block, so a cancellation at its return still reaches the finally
                var launched = false
                try {
                    withContext(Dispatchers.IO) { asking = shouldAskNotifications(context, prefs) }
                    if (!asking) return@launch
                    launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    launched = true
                } finally {
                    if (asking) withContext(NonCancellable + Dispatchers.IO) { if (launched) askLaunched(prefs) else askAbandoned() }
                }
            }
        }
    }
}
