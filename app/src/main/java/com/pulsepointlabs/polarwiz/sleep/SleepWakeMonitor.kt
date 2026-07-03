package com.pulsepointlabs.polarwiz.sleep

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import kotlin.math.abs
import kotlin.math.sqrt

class SleepWakeMonitor(
    context: Context,
    private val onMotion: (Float) -> Unit,
    private val onSignificantMotion: () -> Unit
) : SensorEventListener {
    private val sensors = context.getSystemService(SensorManager::class.java)
    private val accelerometer = sensors.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
    private val significantMotion = sensors.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION)
    private var running = false
    private val trigger = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent?) {
            onSignificantMotion()
            if (running) significantMotion?.let { sensors.requestTriggerSensor(this, it) }
        }
    }

    fun start(): Boolean {
        if (running) return accelerometer != null
        running = true
        val registered = accelerometer?.let {
            sensors.registerListener(this, it, SensorManager.SENSOR_DELAY_NORMAL)
        } ?: false
        significantMotion?.let { sensors.requestTriggerSensor(trigger, it) }
        return registered
    }

    fun stop() {
        running = false
        sensors.unregisterListener(this)
        significantMotion?.let { sensors.cancelTriggerSensor(trigger, it) }
    }

    override fun onSensorChanged(event: SensorEvent) {
        val magnitude = sqrt(event.values[0] * event.values[0] + event.values[1] * event.values[1] + event.values[2] * event.values[2])
        onMotion(abs(magnitude - SensorManager.GRAVITY_EARTH))
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
}
