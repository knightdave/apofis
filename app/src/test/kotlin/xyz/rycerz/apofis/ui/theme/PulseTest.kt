package xyz.rycerz.apofis.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PulseTest {
    @Test
    fun dotWaveBounds() {
        for (t in 0L..4400L step 7) for (i in 0..2) {
            val f = dotWave(t, i)
            assertTrue("scale ${f.scale} at $t/$i", f.scale in 0.85f..1.0f)
            assertTrue("alpha ${f.alpha} at $t/$i", f.alpha in 0.45f..0.90f)
        }
        assertEquals(0.45f, dotWave(0, 0).alpha, 1e-6f)
        assertEquals(0.85f, dotWave(0, 0).scale, 1e-6f)
        // Each dot trails the previous by 0.22 of the 1100 ms period (242 ms).
        assertEquals(dotWave(0, 0).alpha, dotWave(242, 1).alpha, 1e-3f)
        assertEquals(dotWave(0, 0).scale, dotWave(242, 1).scale, 1e-3f)
        // The peak halfway through the period, and the period itself.
        assertEquals(0.90f, dotWave(550, 0).alpha, 1e-6f)
        assertEquals(1.0f, dotWave(550, 0).scale, 1e-6f)
        assertEquals(dotWave(123, 2).alpha, dotWave(123 + Motion.DOTS_PERIOD.toLong(), 2).alpha, 1e-5f)
    }

    @Test
    fun pulseAlphaBounds() {
        for (t in 0L..5400L step 11) {
            val a = pulseAlpha(t, Motion.PULSE_PERIOD, 0.4f, 1f)
            assertTrue("alpha $a at $t", a in 0.4f..1f)
        }
        assertEquals(0.4f, pulseAlpha(0, Motion.PULSE_PERIOD, 0.4f, 1f), 1e-6f)
        assertEquals(1f, pulseAlpha(900, Motion.PULSE_PERIOD, 0.4f, 1f), 1e-6f)
        assertEquals(0.4f, pulseAlpha(1800, Motion.PULSE_PERIOD, 0.4f, 1f), 1e-6f)
    }
}
