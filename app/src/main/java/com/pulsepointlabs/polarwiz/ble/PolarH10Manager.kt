package com.pulsepointlabs.polarwiz.ble

import android.content.Context
import android.util.Log
import com.polar.sdk.api.PolarBleApi
import com.polar.sdk.api.PolarBleApiCallback
import com.polar.sdk.api.PolarBleApiDefaultImpl
import com.polar.sdk.api.model.PolarDeviceInfo
import com.polar.sdk.api.model.PolarHrData
import com.polar.sdk.api.model.PolarHealthThermometerData
import com.polar.androidcommunications.api.ble.model.DisInfo
import com.pulsepointlabs.polarwiz.model.PolarDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

class PolarH10Manager(context: Context, private val scope: CoroutineScope) {
    val devices = MutableStateFlow<List<PolarDevice>>(emptyList())
    val connectionState = MutableStateFlow("Disconnected")
    val readings = MutableSharedFlow<Pair<Int, Int?>>(extraBufferCapacity = 16)
    val errors = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private val api = PolarBleApiDefaultImpl.defaultImplementation(
        context.applicationContext,
        setOf(PolarBleApi.PolarBleSdkFeature.FEATURE_HR)
    )
    private var scanJob: Job? = null
    private var connectedId: String? = null
    private var reconnectId: String? = null
    private var intentionalDisconnect = false

    init {
        api.setApiLogger { Log.d(TAG, it) }
        api.setApiCallback(object : PolarBleApiCallback() {
            override fun disInformationReceived(identifier: String, disInfo: DisInfo) = Unit
            override fun htsNotificationReceived(identifier: String, data: PolarHealthThermometerData) = Unit
            override fun blePowerStateChanged(powered: Boolean) {
                Log.i(TAG, "Bluetooth powered=$powered")
                if (!powered) connectionState.value = "Bluetooth is off"
            }
            override fun deviceConnecting(info: PolarDeviceInfo) {
                connectionState.value = "Connecting to ${info.name} (${info.deviceId})"
            }
            override fun deviceConnected(info: PolarDeviceInfo) {
                connectedId = info.deviceId
                reconnectId = info.deviceId
                intentionalDisconnect = false
                connectionState.value = "Connected: ${info.name} (${info.deviceId})"
                Log.i(TAG, connectionState.value)
            }
            override fun deviceDisconnected(info: PolarDeviceInfo) {
                connectedId = null
                connectionState.value = "Disconnected: ${info.name}"
                Log.w(TAG, connectionState.value)
                if (!intentionalDisconnect && reconnectId != null) {
                    scope.launch {
                        kotlinx.coroutines.delay(2_000)
                        runCatching { api.connectToDevice(reconnectId!!) }
                            .onFailure { errors.tryEmit("Reconnect failed: ${it.message}") }
                    }
                }
            }
            override fun hrNotificationReceived(identifier: String, data: PolarHrData.PolarHrSample) {
                val rr = data.rrsMs.lastOrNull()
                Log.d(TAG, "BPM=${data.hr} RR=$rr")
                readings.tryEmit(data.hr to rr)
            }
        })
    }

    fun scan() {
        scanJob?.cancel()
        devices.value = emptyList()
        connectionState.value = "Scanning…"
        scanJob = scope.launch {
            api.searchForDevice("Polar H10")
                .catch { errors.emit("Polar scan failed: ${it.message}"); connectionState.value = "Scan failed" }
                .collect { info ->
                    if (!info.isConnectable || !info.hasHeartRateService) return@collect
                    val item = PolarDevice(info.deviceId, info.name, info.rssi)
                    devices.value = (devices.value.filterNot { it.id == item.id } + item).sortedByDescending { it.rssi }
                    Log.i(TAG, "Found ${item.name} ${item.id} RSSI=${item.rssi}")
                }
        }
    }

    fun connect(deviceId: String) {
        scanJob?.cancel()
        intentionalDisconnect = false
        reconnectId = deviceId
        runCatching { api.connectToDevice(deviceId) }
            .onFailure { errors.tryEmit("Connect failed: ${it.message}") }
    }

    fun disconnect() {
        intentionalDisconnect = true
        reconnectId = null
        connectedId?.let { id -> runCatching { api.disconnectFromDevice(id) } }
        connectionState.value = "Disconnected"
    }

    fun close() { scanJob?.cancel(); api.shutDown() }

    companion object { private const val TAG = "PolarH10" }
}
