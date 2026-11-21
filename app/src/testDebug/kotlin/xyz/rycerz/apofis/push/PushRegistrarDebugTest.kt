package xyz.rycerz.apofis.push

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The debug policy (app/src/debug): only it knows the e2e distributor, the loopback origin and the intent history. */
@RunWith(RobolectricTestRunner::class)
class PushRegistrarDebugTest {
    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val server = FakeServer("http://10.0.2.2:8642/")

    @Test
    fun intentInSameCommitAsReservation() = runTest {
        for (killAtReservation in listOf(false, true)) {
            val log = EventLog()
            val commits = mutableListOf<Set<String>>()
            val base = pushPrefs(app)
            base.edit().clear().putString(E2E_ORIGIN, "http://127.0.0.1:41234").putString(E2E_NONCE, "run42").commit()
            val prefs = HookedPrefs(base) {
                if (killAtReservation && Keys.PENDING_ID in it) throw Death()
                commits += it
                true
            }
            val port = FakePort(log).apply { installed.clear(); installed += E2E_PACKAGE }
            val r = PushRegistrar(app, prefs, { FakeRecordApi(server, log) }, { 0L }, { 0L }, backgroundScope, pushPolicy, port)
            r.enableNow()
            assertEquals(listOf("save $E2E_PACKAGE", "register g1"), port.calls)
            prefs.edit().putString(Keys.ENDPOINT_NEW, "http://127.0.0.1:41234/upWIRECHECK012?up=1").putString(Keys.ENDPOINT_NEW_INSTANCE, "g1").commit()
            val died = runCatching { r.publishPending() }.isFailure
            assertEquals(killAtReservation, died)
            if (died) {
                // Death at the reservation commit: neither the reservation nor its intent exists.
                assertNull(base.getString(Keys.PENDING_ID, null))
                assertNull(base.getString(TEST_INTENTS, null))
                continue
            }
            val reservation = commits.first { Keys.PENDING_ID in it }
            assertTrue(reservation.toString(), TEST_INTENTS in reservation)
            val id = base.getString(Keys.RECORD, null)!!
            val title = server.rows.getValue(id).title!!
            assertTrue(title, title.matches(Regex("""$id op-[0-9a-f]{16} e2e-run42""")))
            assertEquals("$id\t$title", base.getString(TEST_INTENTS, null))
            assertEquals(PushState.On(E2E_PACKAGE), r.ui.value.state)
        }
    }

    @Test
    fun loopbackNeedsBothTestKeys() {
        val prefs = pushPrefs(app)
        prefs.edit().clear().putString(E2E_ORIGIN, "http://127.0.0.1:41234").commit()
        assertNull(pushPolicy.loopbackOrigin(app))
        prefs.edit().putString(E2E_NONCE, "run42").commit()
        assertEquals("http://127.0.0.1:41234", pushPolicy.loopbackOrigin(app))
        prefs.edit().putString(E2E_ORIGIN, "http://localhost:41234").commit()
        assertNull(pushPolicy.loopbackOrigin(app))
        assertEquals(listOf(NTFY_PACKAGE, E2E_PACKAGE), pushPolicy.distributors(app).toList())
    }
}
