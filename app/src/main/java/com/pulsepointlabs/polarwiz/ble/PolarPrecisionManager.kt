package com.pulsepointlabs.polarwiz.ble

import android.content.Context
import android.util.Log
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.model.EcgSample
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.androidcommunications.api.ble.model.DisInfo
import com.polar.sdk.api.model.PolarHealthThermometerData
import com.pulsepointlabs.polarwiz.DiagnosticLog
import com.pulsepointlabs.polarwiz.hr.RPeakDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.launch
import kotlin.math.sqrt

class PolarPrecisionManager(context: Context, private val scope: CoroutineScope) {
    val status = MutableStateFlow("ECG precision mode idle")
    val readings = MutableSharedFlow<Pair<Int, Int?>>(extraBufferCapacity = 16)
    val rPeaks = MutableSharedFlow<Long>(extraBufferCapacity = 32)
    val chestMotion = MutableSharedFlow<Float>(extraBufferCapacity = 32)
    val errors = MutableSharedFlow<String>(extraBufferCapacity = 8)
    private val detector = RPeakDetector()
    private var deviceId: String? = null
    private var ecgJob: Job? = null
    private var accJob: Job? = null
    private var hrJob: Job? = null
    private val api = PolarBleApiDefaultImpl.defaultImplementation(
        context.applicationContext,
        setOf(PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING)
    ).apply {
        setApiCallback(object : PolarBleApiCallback() {
            override fun deviceConnecting(polarDeviceInfo: PolarDeviceInfo) { status.value = "ECG mode connecting…" }
            override fun deviceConnected(polarDeviceInfo: PolarDeviceInfo) {
                deviceId = polarDeviceInfo.deviceId
                status.value = "ECG mode connected; starting streams…"
            }
            override fun deviceDisconnected(polarDeviceInfo: PolarDeviceInfo) { status.value = "ECG mode disconnected"; stopJobs() }
            override fun bleSdkFeatureReady(identifier: String, feature: PolarBleApi.PolarBleSdkFeature) {
                if (feature == PolarBleApi.PolarBleSdkFeature.FEATURE_POLAR_ONLINE_STREAMING) startStreams(identifier)
            }
            override fun disInformationReceived(identifier: String, disInfo: DisInfo) = Unit
            override fun htsNotificationReceived(identifier: String, data: PolarHealthThermometerData) = Unit
        })
        setApiLogger { Log.d(TAG, it) }
    }

    fun connect(identifier: String) {
        closeConnection()
        detector.reset()
        deviceId = identifier
        status.value = "ECG mode connecting to $identifier…"
        runCatching { api.connectToDevice(identifier) }.onFailure(::fail)
    }

    fun disconnect() { closeConnection(); status.value = "ECG precision mode idle" }

    private fun startStreams(identifier: String) {
        stopJobs()
        status.value = "ECG + chest motion streaming"
        hrJob = scope.launch {
            api.startHrStreaming(identifier).catch { fail(it) }.collect { data ->
                data.samples.forEach { sample -> readings.tryEmit(sample.hr to sample.rrsMs.lastOrNull()) }
            }
        }
        ecgJob = scope.launch {
            runCatching { api.requestStreamSettings(identifier, PolarBleApi.PolarDeviceDataType.ECG) }
                .onSuccess { settings ->
                    api.startEcgStreaming(identifier, settings.maxSettings()).catch { fail(it) }.collect { data ->
                        data.samples.forEach { sample ->
                            val voltage = (sample as? EcgSample)?.voltage ?: return@forEach
                            if (detector.add(voltage)) rPeaks.tryEmit(android.os.SystemClock.elapsedRealtimeNanos())
                        }
                    }
                }.onFailure(::fail)
        }
        accJob = scope.launch {
            runCatching { api.requestStreamSettings(identifier, PolarBleApi.PolarDeviceDataType.ACC) }
                .onSuccess { settings ->
                    api.startAccStreaming(identifier, settings.maxSettings()).catch { fail(it) }.collect { data ->
                        data.samples.forEach { sample ->
                            val magnitude = sqrt((sample.x * sample.x + sample.y * sample.y + sample.z * sample.z).toDouble()).toFloat()
                            chestMotion.tryEmit(kotlin.math.abs(magnitude - 1000f) / 1000f)
                        }
                    }
                }.onFailure(::fail)
        }
        DiagnosticLog.add(TAG, "ECG and accelerometer streams started")
    }

    private fun stopJobs() { hrJob?.cancel(); ecgJob?.cancel(); accJob?.cancel(); hrJob = null; ecgJob = null; accJob = null }
    private fun closeConnection() { stopJobs(); deviceId?.let { runCatching { api.disconnectFromDevice(it) } }; deviceId = null }
    fun shutdown() { closeConnection(); api.shutDown() }
    private fun fail(error: Throwable) { val message = error.message ?: error.javaClass.simpleName; errors.tryEmit(message); DiagnosticLog.add(TAG, message) }
    companion object { private const val TAG = "PolarPrecision" }
}
