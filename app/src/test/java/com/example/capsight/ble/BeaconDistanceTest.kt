package com.example.capsight.ble

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CLAUDE.md §4.6: log-distance path loss, rounded steps, step hysteresis, calibration. */
class BeaconDistanceTest {

    private fun m(rssi: Double, at1m: Double = -52.0, n: Double = 2.5) = BeaconDistance.metres(rssi, at1m, n)

    @Test
    fun `path loss formula at known values`() {
        assertEquals(1.0, m(-52.0), 1e-9)
        assertEquals(10.0, m(-77.0), 1e-9)
        assertEquals(Math.sqrt(10.0), m(-64.5), 1e-9)
        // n = 2 (open space): 20 dB per decade.
        assertEquals(10.0, m(-72.0, n = 2.0), 1e-9)
        // Measured on the Pixel 9a: -42..-49 dBm at ~0.5 m reads as under 1 m.
        assertTrue(m(-45.0) < 1.0)
    }

    @Test
    fun `distance is clamped to 0_3 - 30 m and bad exponents read as far`() {
        assertEquals(0.3, m(-20.0), 0.0)
        assertEquals(30.0, m(-120.0), 0.0)
        assertEquals(30.0, m(-60.0, n = 0.0), 0.0)
        assertEquals(30.0, m(Double.NaN), 0.0)
    }

    @Test
    fun `steps and their boundaries`() {
        val cases = listOf(
            0.3 to 1, 1.49 to 1, 1.5 to 2, 2.49 to 2, 2.5 to 3, 3.99 to 3, 4.0 to 5, 6.49 to 5,
            6.5 to 8, 8.99 to 8, 9.0 to 10, 12.49 to 10, 12.5 to 15, 17.49 to 15, 17.5 to 20, 30.0 to 20,
        )
        cases.forEach { (metres, step) -> assertEquals("$metres m", step, BeaconDistance.step(metres)) }
        assertEquals(listOf(1, 2, 3, 5, 8, 10, 15, 20), BeaconDistance.STEPS)
    }

    @Test
    fun `step filter - first reading at once, a new step must persist 1_5 s`() {
        val f = StepFilter(1_500)
        assertEquals(3, f.update(3, 0))
        assertEquals(3, f.update(2, 100))
        assertEquals(3, f.update(2, 1_599))
        assertEquals(2, f.update(2, 1_600))
    }

    @Test
    fun `step filter - jitter around a boundary does not flip the step`() {
        val f = StepFilter(1_500)
        f.update(3, 0)
        var t = 0L
        repeat(20) {
            t += 400
            assertEquals(3, f.update(if (it % 2 == 0) 2 else 3, t))
        }
        // Same for jitter to the farther side.
        repeat(20) {
            t += 400
            assertEquals(3, f.update(if (it % 2 == 0) 5 else 3, t))
        }
    }

    @Test
    fun `step filter - steady approach takes the newest step, reset forgets`() {
        val f = StepFilter(1_500)
        f.update(8, 0)
        f.update(5, 100)
        f.update(3, 900)
        // Below 8 since 100 ms: at 1.6 s it takes the newest raw step, not the first one seen.
        assertEquals(2, f.update(2, 1_600))
        f.reset()
        assertEquals(10, f.update(10, 1_700))
    }

    private fun view(dbm: Double, name: String = "CS:DOOR:Room 304") =
        BeaconView(name, Landmark.parse(name)!!, dbm, armed = true, recentlyAnnounced = false)

    @Test
    fun `calibration takes the strongest beacon, rounded`() {
        val c = Calibration.from(listOf(view(-63.0, "CS:LIFT:"), view(-50.6), view(-71.0, "CS:STAIRS:")))
        assertEquals(Calibration.Ok(-51, Landmark(LandmarkType.DOOR, "Room 304")), c)
        assertEquals("Calibrated: -51 dBm at 1 m", c.message)
    }

    @Test
    fun `calibration with no beacon or an implausible signal saves nothing`() {
        assertEquals(Calibration.NoBeacon, Calibration.from(emptyList()))
        assertTrue(Calibration.from(emptyList()).message.startsWith("No beacon heard"))
        assertEquals(Calibration.OutOfRange(-95), Calibration.from(listOf(view(-95.0))))
        assertEquals(Calibration.OutOfRange(-18), Calibration.from(listOf(view(-18.4))))
        assertEquals(Calibration.Ok(-90, Landmark(LandmarkType.DOOR, "Room 304")), Calibration.from(listOf(view(-90.0))))
    }
}
