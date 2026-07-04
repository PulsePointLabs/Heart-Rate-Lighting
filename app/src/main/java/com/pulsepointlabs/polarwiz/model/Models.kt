package com.pulsepointlabs.polarwiz.model

import java.net.InetAddress

data class PolarDevice(val id: String, val name: String, val rssi: Int)
data class WizLight(
    val address: InetAddress,
    val deviceId: String = address.hostAddress ?: "unknown",
    val name: String = address.hostAddress ?: "WiZ light",
    val online: Boolean = true,
    val selected: Boolean = true,
    val group: String = "All lights",
    val lastSeenMs: Long = System.currentTimeMillis()
)
data class HueLight(
    val id: String,
    val name: String,
    val selected: Boolean = true,
    val online: Boolean = true,
    val supportsColor: Boolean = true,
    val supportsTemperature: Boolean = true
)

data class Rgb(val r: Int, val g: Int, val b: Int)

enum class HrZone(val label: String, val min: Int, val color: Rgb?, val temperature: Int?, val brightness: Int) {
    WARM("Warm white", 0, null, 2700, 25),
    LAVENDER("Soft lavender", 80, Rgb(190, 150, 255), null, 40),
    VIOLET("Violet", 100, Rgb(135, 50, 255), null, 60),
    PINK_RED("Pink / red", 120, Rgb(255, 35, 85), null, 80),
    RED("Bright red", 140, Rgb(255, 0, 0), null, 100)
}

data class LightStyle(val color: Rgb?, val temperature: Int?, val brightness: Int)

enum class LightingTheme(val displayName: String) {
    PULSE("Pulse — lavender to red"),
    OCEAN("Ocean — aqua to deep blue"),
    EMBER("Ember — candlelight to fire"),
    DAYLIGHT_TINT("Daylight Tint — bright with color accents"),
    DAYLIGHT_COLOR_PULSE("Daylight + colored heartbeat");

    fun styleFor(zone: HrZone): LightStyle = when (this) {
        PULSE -> LightStyle(zone.color, zone.temperature, zone.brightness)
        OCEAN -> when (zone) {
            HrZone.WARM -> LightStyle(Rgb(70, 190, 180), null, 25)
            HrZone.LAVENDER -> LightStyle(Rgb(50, 220, 255), null, 40)
            HrZone.VIOLET -> LightStyle(Rgb(30, 120, 255), null, 60)
            HrZone.PINK_RED -> LightStyle(Rgb(35, 40, 255), null, 80)
            HrZone.RED -> LightStyle(Rgb(155, 20, 255), null, 100)
        }
        EMBER -> when (zone) {
            HrZone.WARM -> LightStyle(null, 2200, 25)
            HrZone.LAVENDER -> LightStyle(Rgb(255, 180, 45), null, 40)
            HrZone.VIOLET -> LightStyle(Rgb(255, 105, 20), null, 60)
            HrZone.PINK_RED -> LightStyle(Rgb(255, 45, 5), null, 80)
            HrZone.RED -> LightStyle(Rgb(255, 0, 0), null, 100)
        }
        DAYLIGHT_TINT -> when (zone) {
            HrZone.WARM -> LightStyle(null, 5000, 80)
            HrZone.LAVENDER -> LightStyle(Rgb(225, 215, 255), null, 85)
            HrZone.VIOLET -> LightStyle(Rgb(210, 185, 255), null, 90)
            HrZone.PINK_RED -> LightStyle(Rgb(255, 195, 215), null, 95)
            HrZone.RED -> LightStyle(Rgb(255, 145, 125), null, 100)
        }
        DAYLIGHT_COLOR_PULSE -> LightStyle(null, 5000, 90)
    }

    fun heartbeatColor(zone: HrZone): Rgb? = if (this == DAYLIGHT_COLOR_PULSE) when (zone) {
        HrZone.WARM -> Rgb(255, 190, 90)
        HrZone.LAVENDER -> Rgb(190, 150, 255)
        HrZone.VIOLET -> Rgb(135, 50, 255)
        HrZone.PINK_RED -> Rgb(255, 35, 85)
        HrZone.RED -> Rgb(255, 0, 0)
    } else null
}
