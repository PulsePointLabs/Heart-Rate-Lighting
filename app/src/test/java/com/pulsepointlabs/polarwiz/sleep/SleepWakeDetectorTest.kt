package com.pulsepointlabs.polarwiz.sleep

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SleepWakeDetectorTest {
    @Test fun `stable HR and low motion detects sleep then sustained motion detects wake`() {
        val detector = SleepWakeDetector(sleepDelayMs = 1_000, wakeDelayMs = 200, hrWakeDelayMs = 300)
        detector.reset(0)
        repeat(5) { detector.onHeartRate(62 + it % 2, 100L + it * 100) }
        assertEquals(SleepWakeDetector.Event.SLEEP, detector.evaluate(1_100))
        assertNull(detector.onMotion(1f, 1_200))
        assertEquals(SleepWakeDetector.Event.WAKE, detector.onMotion(1f, 1_450))
    }

    @Test fun `movement prevents false sleep`() {
        val detector = SleepWakeDetector(sleepDelayMs = 1_000)
        detector.reset(0)
        repeat(5) { detector.onHeartRate(60, 100L + it * 100) }
        detector.onMotion(1f, 900)
        assertNull(detector.evaluate(1_100))
    }
}
