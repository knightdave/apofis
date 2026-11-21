package xyz.rycerz.apofis.push

import android.app.Application
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The connector without tink: every step completes with PlainKeyManager and reaches the prefs and the sink. */
@RunWith(RobolectricTestRunner::class)
class ConnectorWithoutTinkTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val log = EventLog()
    private var pushes = 0
    private val ctx = LogContext(app, log, pushSink = PushSink { pushes++; true })

    @Before
    fun setUp() {
        resetConnectorStore(app)
        installDistributor(app, NTFY_PACKAGE)
    }

    private fun deliver(action: String, token: String, build: Intent.() -> Unit = {}) = PushReceiver().receive(
        ctx,
        Intent("org.unifiedpush.android.connector.$action").setPackage(app.packageName).putExtra("token", token).apply(build),
        finish = {}, schedule = {},
    )

    @Test
    fun registerDeliverAndUnregisterWithoutTink() {
        for (cls in listOf("com.google.crypto.tink.KeysetHandle", "com.google.crypto.tink.subtle.EllipticCurves")) {
            try {
                Class.forName(cls)
                fail("$cls is on the classpath")
            } catch (_: ClassNotFoundException) {
            }
        }
        assertTrue("the receiver never falls back to the tink key manager", PushReceiver().getKeyManager(app) === PlainKeyManager)
        val prefs = pushPrefs(app)
        prefs.edit().putLong(Keys.GENERATION, 1).commit()
        RealUnifiedPush.save(ctx, NTFY_PACKAGE)
        var journaled: Pair<String, String>? = null
        RealUnifiedPush.register(ctx, "g1") { d, t ->
            journaled = d to t
            prefs.edit().putString(Keys.REGISTRATION, Registration("g1", d, t).encode()).commit()
        }
        val register = ctx.sent.single { it.action == "org.unifiedpush.android.distributor.REGISTER" }
        assertEquals(NTFY_PACKAGE, register.`package`)
        assertNull("no Web Push keys", register.getStringExtra("vapid"))
        val token = register.getStringExtra("token")!!
        assertEquals("journaled before the broadcast", NTFY_PACKAGE to token, journaled)

        deliver("NEW_ENDPOINT", token) { putExtra("endpoint", VALID) }
        assertEquals(VALID, prefs.getString(Keys.ENDPOINT_NEW, null))
        assertEquals(NTFY_PACKAGE, RealUnifiedPush.acked(ctx))

        deliver("MESSAGE", token) { putExtra("bytesMessage", "plain".toByteArray()) }
        assertEquals(1, pushes)

        val before = prefs.all.toMap()
        deliver("TEMP_UNAVAILABLE", token)
        assertEquals(before, prefs.all.toMap())

        ctx.sent.clear()
        RealUnifiedPush.unregister(ctx, Registration("g1", null, null))
        assertEquals(token, ctx.sent.single { it.action == "org.unifiedpush.android.distributor.UNREGISTER" }.getStringExtra("token"))
        assertNull(RealUnifiedPush.saved(ctx))
        deliver("MESSAGE", token) { putExtra("bytesMessage", "late".toByteArray()) }
        assertTrue("an unregistered token reaches nothing", pushes == 1)
    }

    @Test
    fun unregisterSurvivesDeathInsideTheConnector() {
        RealUnifiedPush.save(ctx, NTFY_PACKAGE)
        var journaled: Registration? = null
        RealUnifiedPush.register(ctx, "g1") { d, t -> journaled = Registration("g1", d, t) }
        val r = journaled!!
        // Death at the connector's own UNREGISTER broadcast, after it deleted its token: ours went out first.
        ctx.sent.clear()
        log.killAt = log.count() + 2 // our broadcast, then death before the connector's
        runCatching { RealUnifiedPush.unregister(ctx, r) }.onSuccess { error("no death") }
        log.killAt = 0
        assertEquals(listOf(r.token), ctx.sent.filter { it.action == "org.unifiedpush.android.distributor.UNREGISTER" }.map { it.getStringExtra("token") })
        // The replay after restart: the connector no longer knows the token, the journal still does.
        ctx.sent.clear()
        RealUnifiedPush.unregister(ctx, r)
        val replay = ctx.sent.single { it.action == "org.unifiedpush.android.distributor.UNREGISTER" }
        assertEquals(NTFY_PACKAGE, replay.`package`)
        assertEquals(r.token, replay.getStringExtra("token"))
    }
}
