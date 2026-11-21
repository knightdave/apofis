package xyz.rycerz.apofis.notify

import android.Manifest
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
class NotificationAccessTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val nm = app.getSystemService(NotificationManager::class.java)
    private val prefs = app.getSharedPreferences("notify-access-test", Context.MODE_PRIVATE)
    private val asks = mutableListOf<String>()

    @Before
    fun setUp() {
        prefs.edit().clear().commit()
        ensureChannels(app)
    }

    private fun access() = notificationAccess(app, prefs)
    private fun ask() = askNotificationsOnce(app, prefs) { asks += it }

    @Test
    @Config(sdk = [31])
    fun api31NotNeededAndNeverAsked() {
        assertEquals(Runtime.NOT_NEEDED, access().runtime)
        assertFalse(ask())
        assertTrue(asks.isEmpty())
    }

    @Test
    @Config(sdk = [37])
    fun api37RuntimeStates() {
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(Runtime.NEVER_ASKED, access().runtime)

        assertTrue(ask()) // the first send
        assertEquals(listOf(Manifest.permission.POST_NOTIFICATIONS), asks)
        assertEquals(Runtime.DENIED, access().runtime) // the dialog was declined

        assertFalse(ask()) // never again, also not on later sends
        assertEquals(1, asks.size)

        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertEquals(Runtime.GRANTED, access().runtime)
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS) // revoked later in system settings
        assertEquals(Runtime.DENIED, access().runtime)
        assertFalse(ask())
        assertEquals(1, asks.size)
    }

    @Test
    @Config(sdk = [37])
    fun grantedAtFirstSendThenRevokedIsNeverAsked() {
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
        assertFalse(ask()) // the first send: nothing to ask, and its one chance is used
        shadowOf(app).denyPermissions(Manifest.permission.POST_NOTIFICATIONS) // revoked later
        assertEquals(Runtime.DENIED, access().runtime)
        assertFalse(ask())
        assertTrue(asks.isEmpty())
    }

    @Test
    @Config(sdk = [37])
    fun appAndChannelStatesSeparate() {
        assertEquals(NotificationAccess(Runtime.NEVER_ASKED, appEnabled = true, repliesEnabled = true, answersEnabled = true), access())

        shadowOf(nm).setNotificationsEnabled(false)
        assertEquals(NotificationAccess(Runtime.NEVER_ASKED, appEnabled = false, repliesEnabled = true, answersEnabled = true), access())
        shadowOf(nm).setNotificationsEnabled(true)

        nm.deleteNotificationChannel(CHANNEL_ANSWERS)
        nm.createNotificationChannel(NotificationChannel(CHANNEL_ANSWERS, "x", NotificationManager.IMPORTANCE_NONE))
        assertEquals(NotificationAccess(Runtime.NEVER_ASKED, appEnabled = true, repliesEnabled = true, answersEnabled = false), access())
    }

    @Test
    @Config(sdk = [37])
    fun channelsAreNamedInTheAppLanguage() {
        assertEquals(app.getString(xyz.rycerz.apofis.R.string.notif_channel_replies), nm.getNotificationChannel(CHANNEL_REPLIES).name)
        assertEquals(app.getString(xyz.rycerz.apofis.R.string.notif_channel_answers), nm.getNotificationChannel(CHANNEL_ANSWERS).name)
    }
}
