package com.pulsepointlabs.polarwiz.ble

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
import android.util.Log
import androidx.core.content.ContextCompat
import com.pulsepointlabs.polarwiz.model.PolarDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.UUID

/** Standard BLE Heart Rate Service client for Polar H10; no ECG or vendor-specific streaming. */
class PolarH10Manager(context: Context, private val scope: CoroutineScope) {
    val devices = MutableStateFlow<List<PolarDevice>>(emptyList())
    val connectionState = MutableStateFlow("Disconnected")
    val readings = MutableSharedFlow<Pair<Int, Int?>>(extraBufferCapacity = 16)
    val errors = MutableSharedFlow<String>(extraBufferCapacity = 8)

    private val appContext = context.applicationContext
    private val bluetoothManager = appContext.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager.adapter
    private val scanner: BluetoothLeScanner? get() = adapter?.bluetoothLeScanner
    private var gatt: BluetoothGatt? = null
    private var scanTimeoutJob: Job? = null
    private var reconnectJob: Job? = null
    private var reconnectAddress: String? = null
    private var reconnectAttempts = 0
    private var intentionalDisconnect = false

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) = acceptScanResult(result)
        override fun onBatchScanResults(results: MutableList<ScanResult>) = results.forEach(::acceptScanResult)
        override fun onScanFailed(errorCode: Int) = fail("Bluetooth scan failed (code $errorCode)")
    }

    @SuppressLint("MissingPermission")
    private fun acceptScanResult(result: ScanResult) {
        if (!hasBlePermissions()) return
        val name = result.device.name ?: result.scanRecord?.deviceName ?: return
        if (!name.startsWith("Polar H10", ignoreCase = true)) return
        val item = PolarDevice(result.device.address, name, result.rssi)
        devices.value = (devices.value.filterNot { it.id == item.id } + item).sortedByDescending { it.rssi }
        Log.i(TAG, "Found ${item.name} ${item.id} RSSI=${item.rssi}")
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(target: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    reconnectAttempts = 0
                    connectionState.value = "Connected; discovering heart-rate service…"
                    Log.i(TAG, "GATT connected status=$status address=${target.device.address}")
                    if (!safeGatt("service discovery") { target.discoverServices() }) disconnectGatt(target)
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    val address = target.device.address
                    Log.w(TAG, "GATT disconnected status=$status address=$address")
                    disconnectGatt(target)
                    connectionState.value = if (status == BluetoothGatt.GATT_SUCCESS) "Disconnected" else "Connection lost (status $status)"
                    if (!intentionalDisconnect && reconnectAddress == address) scheduleReconnect()
                }
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(target: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                fail("Heart-rate service discovery failed (status $status)")
                disconnectGatt(target)
                return
            }
            val measurement = target.getService(HEART_RATE_SERVICE)?.getCharacteristic(HEART_RATE_MEASUREMENT)
            if (measurement == null) {
                fail("Connected device has no standard heart-rate service")
                disconnectGatt(target)
                return
            }
            val enabled = safeGatt("enable HR notifications") { target.setCharacteristicNotification(measurement, true) }
            val cccd = measurement.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG)
            if (!enabled || cccd == null || !writeNotificationDescriptor(target, cccd)) {
                fail("Could not enable heart-rate notifications")
                disconnectGatt(target)
                return
            }
            connectionState.value = "Connected: ${safeDeviceName(target) ?: "Polar H10"} (${target.device.address})"
            Log.i(TAG, connectionState.value)
        }

        @Deprecated("Used on Android 12 and older")
        override fun onCharacteristicChanged(target: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (characteristic.uuid == HEART_RATE_MEASUREMENT) parseHeartRate(characteristic.value)
        }

        override fun onCharacteristicChanged(target: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (characteristic.uuid == HEART_RATE_MEASUREMENT) parseHeartRate(value)
        }
    }

    @SuppressLint("MissingPermission")
    fun scan() {
        if (!hasBlePermissions()) return fail("Nearby devices permission is required")
        if (adapter?.isEnabled != true) return fail("Bluetooth is off")
        stopScan()
        devices.value = emptyList()
        connectionState.value = "Scanning…"
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(HEART_RATE_SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        runCatching { scanner?.startScan(filters, settings, scanCallback) ?: error("BLE scanner unavailable") }
            .onFailure { fail("Polar scan failed: ${it.message}") }
        scanTimeoutJob = scope.launch {
            delay(SCAN_DURATION_MS)
            stopScan()
            connectionState.value = if (devices.value.isEmpty()) "Scan finished; no H10 found" else "Tap an H10 to connect"
        }
    }

    @SuppressLint("MissingPermission")
    fun connect(deviceId: String) {
        if (!hasBlePermissions()) return fail("Nearby devices permission is required")
        stopScan()
        reconnectJob?.cancel()
        intentionalDisconnect = false
        reconnectAddress = deviceId
        connectionState.value = "Connecting to $deviceId…"
        runCatching {
            disconnectGatt(gatt)
            val device = adapter?.getRemoteDevice(deviceId) ?: error("Bluetooth adapter unavailable")
            gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
                ?: error("Android could not start the BLE connection")
        }.onFailure { fail("Connect failed: ${it.message}") }
    }

    fun disconnect() {
        intentionalDisconnect = true
        reconnectAddress = null
        reconnectJob?.cancel()
        stopScan()
        disconnectGatt(gatt)
        connectionState.value = "Disconnected"
    }

    fun close() = disconnect()

    private fun scheduleReconnect() {
        if (reconnectAttempts >= MAX_RECONNECT_ATTEMPTS) {
            fail("H10 disconnected; tap the device to try again")
            return
        }
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            reconnectAttempts++
            val delayMs = 2_000L * reconnectAttempts
            connectionState.value = "Disconnected; retrying in ${delayMs / 1_000}s (${reconnectAttempts}/$MAX_RECONNECT_ATTEMPTS)"
            delay(delayMs)
            reconnectAddress?.let(::connect)
        }
    }

    @SuppressLint("MissingPermission")
    private fun stopScan() {
        scanTimeoutJob?.cancel()
        scanTimeoutJob = null
        if (hasBlePermissions()) runCatching { scanner?.stopScan(scanCallback) }
    }

    @SuppressLint("MissingPermission")
    private fun disconnectGatt(target: BluetoothGatt?) {
        if (target == null) return
        if (gatt === target) gatt = null
        runCatching { target.disconnect() }
        runCatching { target.close() }
    }

    @SuppressLint("MissingPermission")
    private fun writeNotificationDescriptor(target: BluetoothGatt, descriptor: BluetoothGattDescriptor): Boolean =
        safeGatt("write HR notification descriptor") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                target.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                run { descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; target.writeDescriptor(descriptor) }
            }
        }

    private fun parseHeartRate(value: ByteArray) {
        if (value.size < 2) return
        val flags = value[0].toInt() and 0xFF
        val is16Bit = flags and 0x01 != 0
        val bpm = if (is16Bit) {
            if (value.size < 3) return
            (value[1].toInt() and 0xFF) or ((value[2].toInt() and 0xFF) shl 8)
        } else value[1].toInt() and 0xFF
        var offset = 1 + if (is16Bit) 2 else 1
        if (flags and 0x08 != 0) offset += 2
        var latestRrMs: Int? = null
        if (flags and 0x10 != 0) {
            while (offset + 1 < value.size) {
                val rr1024 = (value[offset].toInt() and 0xFF) or ((value[offset + 1].toInt() and 0xFF) shl 8)
                latestRrMs = (rr1024 * 1000.0 / 1024.0).toInt()
                offset += 2
            }
        }
        if (bpm in 1..250) {
            Log.d(TAG, "BPM=$bpm RR=$latestRrMs")
            readings.tryEmit(bpm to latestRrMs)
        }
    }

    @SuppressLint("MissingPermission")
    private fun safeDeviceName(target: BluetoothGatt): String? =
        if (hasBlePermissions()) runCatching { target.device.name }.getOrNull() else null

    private inline fun safeGatt(action: String, block: () -> Boolean): Boolean =
        runCatching(block).onFailure { fail("Bluetooth $action failed: ${it.message}") }.getOrDefault(false)

    private fun hasBlePermissions(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    private fun fail(message: String) {
        Log.e(TAG, message)
        connectionState.value = message
        errors.tryEmit(message)
    }

    companion object {
        private const val TAG = "PolarH10"
        private const val SCAN_DURATION_MS = 10_000L
        private const val MAX_RECONNECT_ATTEMPTS = 3
        private val HEART_RATE_SERVICE = UUID.fromString("0000180d-0000-1000-8000-00805f9b34fb")
        private val HEART_RATE_MEASUREMENT = UUID.fromString("00002a37-0000-1000-8000-00805f9b34fb")
        private val CLIENT_CHARACTERISTIC_CONFIG = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
