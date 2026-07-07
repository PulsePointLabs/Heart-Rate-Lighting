package com.pulsepointlabs.polarwiz.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class LightingThemeTest {
    @Test fun eachThemeMapsEveryZone() {
        LightingTheme.entries.forEach { theme ->
            val styles = HrZone.entries.map(theme::styleFor)
            styles.forEach { style -> assertEquals(true, style.brightness in 10..100) }
            assertEquals(styles.map { it.brightness }.sorted(), styles.map { it.brightness })
        }
    }

    @Test fun themesProduceDifferentHighZoneColors() {
        assertNotEquals(
            LightingTheme.PULSE.styleFor(HrZone.PINK_RED).color,
            LightingTheme.OCEAN.styleFor(HrZone.PINK_RED).color
        )
        assertNotEquals(
            LightingTheme.OCEAN.styleFor(HrZone.RED).color,
            LightingTheme.EMBER.styleFor(HrZone.RED).color
        )
    }

    @Test fun daylightColorPulseKeepsDaylightBaseAndMapsEveryHeartbeatColor() {
        HrZone.entries.forEach { zone ->
            val base = LightingTheme.DAYLIGHT_COLOR_PULSE.styleFor(zone)
            assertEquals(5000, base.temperature)
            assertEquals(90, base.brightness)
            assertEquals(true, LightingTheme.DAYLIGHT_COLOR_PULSE.heartbeatColor(zone) != null)
        }
    }

    @Test fun everyPulseShapeHasAUsableEnvelope() {
        PulseShape.entries.forEach { assertEquals(true, it.durationMs in 80..300) }
    }
}
