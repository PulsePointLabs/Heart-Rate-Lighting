package com.pulsepointlabs.polarwiz.hr

import com.pulsepointlabs.polarwiz.model.HrZone
import org.junit.Assert.assertEquals
import org.junit.Test

class HeartRateProcessorTest {
    @Test fun rollingAverageDropsOldSamples() {
        val p = HeartRateProcessor()
        assertEquals(80, p.add(80, 0))
        assertEquals(90, p.add(100, 2_000))
        assertEquals(100, p.add(100, 6_000))
    }

    @Test fun hysteresisRequiresThreeBpmPastBoundary() {
        val p = HeartRateProcessor(windowMs = 0)
        assertEquals(HrZone.WARM, p.zoneFor(p.add(79, 1)))
        assertEquals(HrZone.WARM, p.zoneFor(p.add(81, 2)))
        assertEquals(HrZone.LAVENDER, p.zoneFor(p.add(83, 3)))
        assertEquals(HrZone.LAVENDER, p.zoneFor(p.add(78, 4)))
        assertEquals(HrZone.WARM, p.zoneFor(p.add(76, 5)))
    }
}
