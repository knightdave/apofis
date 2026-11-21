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
    private val keys = mapOf(E2E_ORIGIN to "http://127.0.0.1:41234", E2E_NONCE to "run42")

    @Test
    fun intentInSameCommitAsReservation() = runTest {
        pushPrefs(app).edit().clear().putString(E2E_ORIGIN, keys[E2E_ORIGIN]).putString(E2E_NONCE, keys[E2E_NONCE]).commit()
        for (killAtReservation in listOf(false, true)) {
            val log = EventLog()
            val commits = mutableListOf<Set<String>>()
            val prefs = FakePrefs(keys.toMutableMap()) {
                if (killAtReservation && Keys.PENDING_ID in it) throw Death()
                commits += it
                true
            }
            val port = FakePort(log).apply { installed.clear(); installed += E2E_PACKAGE }
            val r = PushRegistrar(app, prefs, { FakeRecordApi(server, log) }, { 0L }, { 0L }, backgroundScope, pushPolicy, port)
            r.enableNow()
            assertEquals(listOf("save $E2E_PACKAGE", "register g1"), port.calls)
            prefs.edit().putString(Keys.ENDPOINT_NEW, "http://127.0.0.1:41234/upWIRECHECK012?up=1").putString(Keys.ENDPOINT_NEW_TOKEN, registrationOf(prefs)?.token).commit()
            val died = runCatching { r.publishPending() }.isFailure
            assertEquals(killAtReservation, died)
            if (died) {
                // Death at the reservation commit: on disk there is neither the reservation nor its intent.
                assertNull(prefs.disk[Keys.PENDING_ID])
                assertNull(prefs.disk[TEST_INTENTS])
                continue
            }
            val reservation = commits.first { Keys.PENDING_ID in it }
            assertTrue("intent in the reservation commit", TEST_INTENTS in reservation)
            val id = prefs.getString(Keys.RECORD, null)!!
            val title = server.rows.getValue(id).title!!
            assertTrue("e2e title", title.matches(Regex("""$id op-[0-9a-f]{16} e2e-run42""")))
            assertEquals("$id\t$title", prefs.getString(TEST_INTENTS, null))
            assertEquals(PushState.On(E2E_PACKAGE), r.ui.value.state)
        }
    }

    @Test
    fun testKeysWorkOnlyAsAValidPair() {
        val prefs = pushPrefs(app)
        fun set(origin: String?, nonce: String?) = prefs.edit().clear().apply {
            origin?.let { putString(E2E_ORIGIN, it) }
            nonce?.let { putString(E2E_NONCE, it) }
        }.commit()
        val plain = "apofis-push-x-1 op-0123456789abcdef"
        for ((origin, nonce) in listOf("http://127.0.0.1:41234" to null, null to "run42", "http://127.0.0.1:65536" to "run42",
            "http://127.0.0.1:0" to "run42", "http://localhost:41234" to "run42", "http://127.0.0.1:41234" to "bad nonce")) {
            set(origin, nonce)
            assertNull(pushPolicy.loopbackOrigin(app))
            assertEquals(plain, pushPolicy.recordTitle(app, "apofis-push-x-1", "0123456789abcdef"))
            val editor = prefs.edit()
            pushPolicy.recordIntent(prefs, editor, "apofis-push-x-1", plain)
            editor.commit()
            assertNull(prefs.getString(TEST_INTENTS, null))
        }
        set("http://127.0.0.1:65535", "run42")
        assertEquals("http://127.0.0.1:65535", pushPolicy.loopbackOrigin(app))
        assertEquals("$plain e2e-run42", pushPolicy.recordTitle(app, "apofis-push-x-1", "0123456789abcdef"))
        assertEquals(listOf(NTFY_PACKAGE, E2E_PACKAGE), pushPolicy.distributors(app).toList())
    }
}
