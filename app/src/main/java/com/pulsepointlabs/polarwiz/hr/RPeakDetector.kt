package com.pulsepointlabs.polarwiz.hr

import kotlin.math.abs

/** Lightweight adaptive R-peak detector for the H10's 130 Hz ECG stream. */
class RPeakDetector(private val sampleRate: Int = 130) {
    private var baseline = 0.0
    private var envelope = 100.0
    private var lastPeakSample = -sampleRate
    private var index = 0

    fun add(voltageUv: Int): Boolean {
        baseline += (voltageUv - baseline) * 0.015
        val magnitude = abs(voltageUv - baseline)
        envelope += (magnitude - envelope) * 0.01
        val refractorySamples = (sampleRate * 0.25).toInt()
        val threshold = (envelope * 3.2).coerceAtLeast(180.0)
        val peak = magnitude > threshold && index - lastPeakSample >= refractorySamples
        if (peak) lastPeakSample = index
        index++
        return peak
    }

    fun reset() { baseline = 0.0; envelope = 100.0; lastPeakSample = -sampleRate; index = 0 }
}
