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
    private var dieAtUnregister = false
    private var throwAtUnregister = false

    // Records whether the switch marker was on disk at every connector call.
    private val fake = FakePort()
    private val port = object : xyz.rycerz.apofis.push.UnifiedPushPort by fake {
        override fun save(c: Context, d: String) = fake.save(c, d).also { markerAtPushEffect += cache.contains(SWITCH_MARKER) }
        override fun register(c: Context, instance: String, journal: (String, String) -> Unit) {
            markerAtPushEffect += cache.contains(SWITCH_MARKER)
            fake.register(c, instance, journal)
            // Its NEW_ENDPOINT arrives while the switch still runs (connected() is null then).
            push.edit().putString(Keys.ENDPOINT_NEW, "https://ntfy.sh/upAbC123xyz_-9?up=1").putString(Keys.ENDPOINT_NEW_TOKEN, "tok-$instance").commit()
        }
        override fun unregister(c: Context, r: Registration, connector: Boolean) {
            if (dieAtUnregister) throw xyz.rycerz.apofis.push.Death()
            if (throwAtUnregister) throw IllegalStateException("broadcast failed")
            fake.unregister(c, r, connector).also { markerAtPushEffect += cache.contains(SWITCH_MARKER) }
        }
    }

    // WorkManager holds its work: in these tests nothing runs it in the background (it would use the app's own
    // container); each test runs what the jobs run, on its own container.
    @Before
    fun setUp() {
        val held = java.util.concurrent.Executor { } // workers never run; WorkManager's own bookkeeping does
        WorkManagerTestInitHelper.initializeTestWorkManager(app, androidx.work.Configuration.Builder()
            .setTaskExecutor(androidx.work.testing.SynchronousExecutor()).setExecutor(held).build())
    }

    @After
    fun tearDown() {
        WorkManager.getInstance(app).cancelAllWork().result.get() // no WorkManager task outlives the test
        db.close()
    }

    @Test
    fun theSwitchSendsNoUnregisterTheLocalJobDoes() = runTest {
        val container = AppContainer(app, store, port) { db }
        container.connect(Credentials("https://a.example/", "key-a"))
        // Push on, with a journaled registration for the current instance.
        assertTrue(push.edit().putBoolean(Keys.ENABLED, true).putLong(Keys.GENERATION, 1)
            .putString(Keys.REGISTRATION, Registration("g1", "io.heckel.ntfy", "tok-g1", connectionEpoch(app)).encode()).commit())
        markerAtPushEffect.clear()

        container.connect(Credentials("https://b.example/", "key-b"))

        assertEquals("connect sends nothing to the distributor", emptyList<String>(), fake.calls)
        assertTrue(push.contains(Keys.REVOKE))
        assertEquals(1, WorkManager.getInstance(app).getWorkInfosForUniqueWork(PublishWorker.LOCAL_NAME).get().size)
        assertEquals(1, WorkManager.getInstance(app).getWorkInfosForUniqueWork(PublishWorker.NAME).get().size)
        assertEquals(xyz.rycerz.apofis.push.PushState.Registering, container.push.ui.value.state)
        // The local job: the only place that sends it, after the marker.
        assertTrue(container.push.reconcileLocal())
        assertEquals(listOf("unregister g1 tok-g1", "save io.heckel.ntfy", "register g2"), fake.calls)
        assertEquals("registered under the epoch the switch advanced", connectionEpoch(app), xyz.rycerz.apofis.push.registrationOf(push)?.epoch)
        assertTrue(!push.contains(Keys.REVOKE))
    }

    @Test
    fun aThrowingUnregisterKeepsTheRevocationUntilSent() = runTest {
        val container = AppContainer(app, store, port) { db }
        container.connect(Credentials("https://a.example/", "key-a"))
        assertTrue(push.edit().putBoolean(Keys.ENABLED, true).putLong(Keys.GENERATION, 1)
            .putString(Keys.REGISTRATION, Registration("g1", "io.heckel.ntfy", "tok-g1", connectionEpoch(app)).encode()).commit())
        fake.subscribed += "tok-g1"
        throwAtUnregister = true
        container.connect(Credentials("https://b.example/", "key-b")) // the switch claims no revocation
        assertTrue(!container.push.reconcileLocal()) // the local job fails: the entry stays, the job retries
        assertTrue(push.contains(Keys.REVOKE))
        assertEquals(xyz.rycerz.apofis.push.PushState.Registering, container.push.ui.value.state)
        throwAtUnregister = false
        assertTrue(container.push.reconcileLocal())
        assertTrue(!push.contains(Keys.REVOKE))
        assertTrue("the old subscription is gone", "tok-g1" !in fake.subscribed)
    }

    @Test
    fun theRevocationIsOnDiskBeforeTheMarker() = runTest {
        val container = AppContainer(app, store, port) { db }
        container.connect(Credentials("https://a.example/", "key-a"))
        assertTrue(push.edit().putBoolean(Keys.ENABLED, true).putLong(Keys.GENERATION, 1)
            .putString(Keys.REGISTRATION, Registration("g1", "io.heckel.ntfy", "tok-g1").encode()).commit())
        var revokeAtMarker = false
        container.afterSwitchMarker = { // dies between the marker and any push step
            revokeAtMarker = push.contains(Keys.REVOKE)
            throw xyz.rycerz.apofis.push.Death()
        }
        runCatching { container.connect(Credentials("https://a.example/", "key-a2")) } // a same-URL key change
        assertTrue("the obligation was committed before the marker", revokeAtMarker)
        AppContainer(app, store, port) { db }.push.publishPending()
        assertTrue(fake.calls.contains("unregister g1 tok-g1"))
        assertTrue(!push.contains(Keys.REVOKE))
    }

    // Fix round 3.

    @Test
    fun aFailedSwitchPreparationLeavesTheOldConnectionIntact() = runTest {
        val disk = xyz.rycerz.apofis.push.FakePrefs()
        val container = AppContainer(app, store, port, disk) { db }
        val a = Credentials("https://a.example/", "key-a")
        container.connect(a)
        assertTrue(disk.edit().putBoolean(Keys.ENABLED, true).putLong(Keys.GENERATION, 1)
            .putString(Keys.REGISTRATION, Registration("g1", "io.heckel.ntfy", "tok-g1", 1).encode()).commit())
        val before = container.connected()!!
        disk.onCommit = { false } // the push intent cannot be written
        assertTrue(runCatching { container.connect(Credentials("https://b.example/", "key-b")) }.isFailure)
        assertTrue("still connected to the old server", container.connected() === before && before.isOpen)
        assertEquals(a, store.load())
        assertTrue(!cache.contains(SWITCH_MARKER))
        assertTrue("nothing revoked on disk", disk.disk[Keys.REVOKE] == null)
        assertTrue(fake.calls.isEmpty())
    }

    @Test
    fun localRevocationNeedsNoNetwork() = runTest {
        val disk = xyz.rycerz.apofis.push.FakePrefs(mutableMapOf(Keys.REVOKE to Registration("g1", "io.heckel.ntfy", "tok-g1").encode()))
        PublishWorker.recover(app, disk)
        val local = WorkManager.getInstance(app).getWorkInfosForUniqueWork(PublishWorker.LOCAL_NAME).get().single()
        assertEquals(androidx.work.NetworkType.NOT_REQUIRED, local.constraints.requiredNetworkType)
    }

    @Test
    fun aWakeForTheOldRegistrationIsDroppedOnceTheSwitchStarts() = runTest {
        val container = AppContainer(app, store, port) { db }
        container.connect(Credentials("https://a.example/", "key-a"))
        val epoch = connectionEpoch(app)
        assertTrue(push.edit().putBoolean(Keys.ENABLED, true).putLong(Keys.GENERATION, 1)
            .putString(Keys.REGISTRATION, Registration("g1", "io.heckel.ntfy", "tok-g1", epoch).encode()).commit())
        var wakes = 0
        val sink = xyz.rycerz.apofis.push.PushSink { _, _ -> wakes++; true }
        val ctx = xyz.rycerz.apofis.push.LogContext(app, xyz.rycerz.apofis.push.EventLog(), null, sink)
        container.beforeSwitch = { // the switch has begun (its generation already advanced): a MESSAGE for the old token
            xyz.rycerz.apofis.push.PushReceiver().receive(ctx, android.content.Intent("org.unifiedpush.android.connector.MESSAGE")
                .setPackage(app.packageName).putExtra("token", "tok-g1").putExtra("bytesMessage", byteArrayOf(1)), {}, schedule = {})
        }
        container.connect(Credentials("https://b.example/", "key-b"))
        assertEquals(0, wakes)
        assertEquals(epoch + 1, connectionEpoch(app))
    }
}
