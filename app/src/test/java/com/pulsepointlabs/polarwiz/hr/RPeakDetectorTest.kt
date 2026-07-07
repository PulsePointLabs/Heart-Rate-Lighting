package com.pulsepointlabs.polarwiz.hr

import org.junit.Assert.assertEquals
import org.junit.Test

class RPeakDetectorTest {
    @Test fun detectsOnePeakPerSyntheticBeatAndHonorsRefractoryPeriod() {
        val detector = RPeakDetector(130)
        var peaks = 0
        repeat(390) { sample ->
            val phase = sample % 65
            val voltage = when (phase) { 5 -> 1300; 6 -> -500; 15 -> 900; else -> 15 }
            if (detector.add(voltage)) peaks++
        }
        assertEquals(6, peaks)
    }
}
