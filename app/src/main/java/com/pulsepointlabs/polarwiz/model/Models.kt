package com.pulsepointlabs.polarwiz.model

import java.net.InetAddress

data class PolarDevice(val id: String, val name: String, val rssi: Int)
data class WizLight(
    val address: InetAddress,
    val name: String = address.hostAddress ?: "WiZ light",
    val online: Boolean = true,
    val selected: Boolean = true
)

data class Rgb(val r: Int, val g: Int, val b: Int)

enum class HrZone(val label: String, val min: Int, val color: Rgb?, val temperature: Int?, val brightness: Int) {
    WARM("Warm white", 0, null, 2700, 25),
    LAVENDER("Soft lavender", 80, Rgb(190, 150, 255), null, 40),
    VIOLET("Violet", 100, Rgb(135, 50, 255), null, 60),
    PINK_RED("Pink / red", 120, Rgb(255, 35, 85), null, 80),
    RED("Bright red", 140, Rgb(255, 0, 0), null, 100)
}
