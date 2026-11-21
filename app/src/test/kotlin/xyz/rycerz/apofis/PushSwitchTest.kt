package xyz.rycerz.apofis

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import javax.crypto.KeyGenerator
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import xyz.rycerz.apofis.db.AppDatabase
import xyz.rycerz.apofis.push.FakePort
import xyz.rycerz.apofis.push.Keys
import xyz.rycerz.apofis.push.PublishWorker
import xyz.rycerz.apofis.push.Registration
import xyz.rycerz.apofis.push.pushPrefs
import xyz.rycerz.apofis.settings.CredentialStore
import xyz.rycerz.apofis.settings.Credentials

/** The push part of AppContainer.connect, with the real container and a fake connector. */
@RunWith(RobolectricTestRunner::class)
class PushSwitchTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
    private val key = KeyGenerator.getInstance("AES").apply { init(256) }.generateKey()
    private val store = CredentialStore(app) { key }
    private val cache = app.getSharedPreferences(CACHE_PREFS, Context.MODE_PRIVATE)
    private val push = pushPrefs(app)
    private val markerAtPushEffect = mutableListOf<Boolean>()

    // Records whether the switch marker was on disk at every connector call.
    private val fake = FakePort()
    private val port = object : xyz.rycerz.apofis.push.UnifiedPushPort by fake {
        override fun save(c: Context, d: String) = fake.save(c, d).also { markerAtPushEffect += cache.contains(SWITCH_MARKER) }
        override fun register(c: Context, instance: String, journal: (String, String) -> Unit) {
            markerAtPushEffect += cache.contains(SWITCH_MARKER)
            fake.register(c, instance, journal)
            // Its NEW_ENDPOINT arrives while the switch still runs (connected() is null then).
            push.edit().putString(Keys.ENDPOINT_NEW, "https://ntfy.sh/upAbC123xyz_-9?up=1").putString(Keys.ENDPOINT_NEW_INSTANCE, instance).commit()
        }
        override fun unregister(c: Context, r: Registration) = fake.unregister(c, r).also { markerAtPushEffect += cache.contains(SWITCH_MARKER) }
    }

    @Before
    fun setUp() = WorkManagerTestInitHelper.initializeTestWorkManager(app)

    @After
    fun tearDown() = db.close()

    @Test
    fun pushEffectsHappenUnderTheSwitchMarkerAndPublishFollows() = runTest {
        val container = AppContainer(app, store, port) { db }
        container.connect(Credentials("https://a.example/", "key-a"))
        // Push on, with a journaled registration for the current instance.
        assertTrue(push.edit().putBoolean(Keys.ENABLED, true).putLong(Keys.GENERATION, 1)
            .putString(Keys.REGISTRATION, Registration("g1", "io.heckel.ntfy", "tok-g1").encode()).commit())
        markerAtPushEffect.clear()

        container.connect(Credentials("https://b.example/", "key-b"))

        assertEquals(listOf(true, true, true), markerAtPushEffect) // unregister g1, save, register g2
        assertEquals(2L, push.getLong(Keys.GENERATION, 0))
        // The endpoint that arrived during the switch is published by work scheduled after it.
        val work = WorkManager.getInstance(app).getWorkInfosForUniqueWork(PublishWorker.NAME).get()
        assertEquals(1, work.size)
    }
}
