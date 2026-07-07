package com.pulsepointlabs.polarwiz.wiz

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.pulsepointlabs.polarwiz.model.Rgb
import com.pulsepointlabs.polarwiz.model.WizLight
import com.pulsepointlabs.polarwiz.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import android.os.SystemClock

class WizLanManager(context: Context) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val commandSocket = DatagramSocket()
    private val socketLock = Any()
    @Suppress("DEPRECATION")
    private val highPerformanceLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "polar-wiz-high-performance").apply { setReferenceCounted(false) }
    private val lowLatencyLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_LOW_LATENCY, "polar-wiz-low-latency").apply { setReferenceCounted(false) }

    fun setLowLatency(enabled: Boolean) {
        runCatching {
            if (enabled) {
                if (!highPerformanceLock.isHeld) highPerformanceLock.acquire()
                if (!lowLatencyLock.isHeld) lowLatencyLock.acquire()
            } else {
                if (lowLatencyLock.isHeld) lowLatencyLock.release()
                if (highPerformanceLock.isHeld) highPerformanceLock.release()
            }
            DiagnosticLog.add(TAG, "Wi-Fi low-latency mode ${if (enabled) "enabled" else "disabled"}")
        }.onFailure { Log.w(TAG, "Could not change Wi-Fi latency mode", it) }
    }

    fun close() {
        setLowLatency(false)
        commandSocket.close()
    }

    suspend fun discover(timeoutMs: Int = 3_000): List<WizLight> = withContext(Dispatchers.IO) {
        val found = linkedMapOf<String, WizLight>()
        val lock = wifi.createMulticastLock("polar-wiz-discovery").apply { setReferenceCounted(false); acquire() }
        try {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = 300
                val query = "{\"method\":\"getPilot\",\"params\":{}}".toByteArray()
                socket.send(DatagramPacket(query, query.size, InetAddress.getByName("255.255.255.255"), PORT))
                val deadline = System.currentTimeMillis() + timeoutMs
                while (System.currentTimeMillis() < deadline) {
                    try {
                        val buffer = ByteArray(2048)
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val json = JSONObject(String(packet.data, 0, packet.length))
                        if (json.optString("method") != "getPilot") continue
                        val result = json.optJSONObject("result")
                        val mac = result?.optString("mac")?.takeIf { it.isNotBlank() }
                        val name = result?.optString("moduleName")?.takeIf { it.isNotBlank() }
                            ?: mac
                            ?: "WiZ light"
                        found[mac ?: packet.address.hostAddress ?: name] = WizLight(
                            address = packet.address,
                            deviceId = mac ?: packet.address.hostAddress ?: name,
                            name = name,
                            lastSeenMs = System.currentTimeMillis()
                        )
                        Log.i(TAG, "Discovered $name at ${packet.address.hostAddress}")
                        DiagnosticLog.add(TAG, "Discovered id=${mac ?: "unknown"} ip=${packet.address.hostAddress}")
                    } catch (_: SocketTimeoutException) { /* continue until deadline */ }
                }
            }
        } finally { if (lock.isHeld) lock.release() }
        found.values.toList()
    }

    suspend fun setColor(lights: List<WizLight>, color: Rgb?, brightness: Int, temperature: Int? = null): Result<Unit> =
        send(lights) {
            put("state", true); put("dimming", brightness.coerceIn(10, 100))
            if (temperature != null) put("temp", temperature.coerceIn(2200, 6500))
            if (color != null) { put("r", color.r); put("g", color.g); put("b", color.b) }
        }

    suspend fun turnOff(lights: List<WizLight>): Result<Unit> = send(lights) { put("state", false) }

    suspend fun setBrightness(lights: List<WizLight>, brightness: Int): Result<Unit> = send(lights) {
        put("state", true)
        put("dimming", brightness.coerceIn(10, 100))
    }

    suspend fun probe(lights: List<WizLight>): Set<String> = withContext(Dispatchers.IO) {
        buildSet {
            lights.forEach { light ->
                runCatching {
                    DatagramSocket().use { socket ->
                        socket.soTimeout = 450
                        val payload = "{\"method\":\"getPilot\",\"params\":{}}".toByteArray()
                        socket.send(DatagramPacket(payload, payload.size, light.address, PORT))
                        val buffer = ByteArray(2048)
                        val response = DatagramPacket(buffer, buffer.size)
                        socket.receive(response)
                        val json = JSONObject(String(response.data, 0, response.length))
                        if (json.optString("method") == "getPilot") add(light.deviceId)
                    }
                }
            }
        }
    }

    suspend fun measureLatency(light: WizLight): Long = withContext(Dispatchers.IO) {
        List(5) {
            val start = SystemClock.elapsedRealtime()
            if (queryPilot(light) != null) SystemClock.elapsedRealtime() - start else 500L
        }.sorted()[2]
    }

    suspend fun snapshot(lights: List<WizLight>): Map<String, JSONObject> = withContext(Dispatchers.IO) {
        buildMap {
            lights.forEach { light ->
                queryPilot(light)?.let { result ->
                    val restorable = JSONObject()
                    listOf("state", "dimming", "temp", "r", "g", "b", "c", "w", "sceneId", "speed").forEach { key ->
                        if (result.has(key)) restorable.put(key, result.get(key))
                    }
                    put(light.deviceId, restorable)
                }
            }
        }
    }

    suspend fun restore(lights: List<WizLight>, states: Map<String, JSONObject>): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            lights.forEach { light ->
                val params = states[light.deviceId] ?: return@forEach
                sendPayload(listOf(light), JSONObject().put("method", "setPilot").put("params", params)).getOrThrow()
            }
        }
    }

    private fun queryPilot(light: WizLight): JSONObject? = runCatching {
        DatagramSocket().use { socket ->
            socket.soTimeout = 500
            val payload = "{\"method\":\"getPilot\",\"params\":{}}".toByteArray()
            socket.send(DatagramPacket(payload, payload.size, light.address, PORT))
            val buffer = ByteArray(2048)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            JSONObject(String(response.data, 0, response.length)).optJSONObject("result")
        }
    }.getOrNull()

    suspend fun pulse(lights: List<WizLight>, delta: Int = -8, durationMs: Int = 180): Result<Unit> =
        sendPayload(
            lights,
            JSONObject().put("method", "pulse").put(
                "params",
                JSONObject().put("delta", delta.coerceIn(-80, -1)).put("duration", durationMs.coerceIn(100, 700))
            )
        )

    suspend fun colorPulse(
        lights: List<WizLight>,
        color: Rgb,
        baseBrightness: Int,
        intensity: Int,
        durationMs: Int,
        baseTemperature: Int = 5000
    ): Result<Unit> = withContext(Dispatchers.IO) { runCatching {
        val pulseBrightness = (baseBrightness - intensity).coerceIn(10, 100)
        sendPayload(lights, JSONObject().put("method", "setPilot").put("params", JSONObject().apply {
            put("state", true); put("dimming", pulseBrightness); put("r", color.r); put("g", color.g); put("b", color.b)
        })).getOrThrow()
        delay(durationMs.toLong())
        sendPayload(lights, JSONObject().put("method", "setPilot").put("params", JSONObject().apply {
            put("state", true); put("dimming", baseBrightness.coerceIn(10, 100)); put("temp", baseTemperature.coerceIn(2200, 6500))
        })).getOrThrow()
    } }

    private suspend fun send(lights: List<WizLight>, params: JSONObject.() -> Unit): Result<Unit> =
        sendPayload(lights, JSONObject().put("method", "setPilot").put("params", JSONObject().apply(params)))

    private suspend fun sendPayload(lights: List<WizLight>, message: JSONObject): Result<Unit> = withContext(Dispatchers.IO) {
        if (lights.isEmpty()) return@withContext Result.failure(IllegalStateException("Select at least one WiZ light"))
        runCatching {
            val payload = message.toString().toByteArray()
            synchronized(socketLock) {
                lights.forEach { light ->
                    commandSocket.send(DatagramPacket(payload, payload.size, light.address, PORT))
                    Log.i(TAG, "Command ${String(payload)} -> ${light.address.hostAddress}")
                    DiagnosticLog.add(TAG, "${message.optString("method")} -> ${light.name} ${light.address.hostAddress}")
                }
            }
        }
    }

    companion object { private const val TAG = "WizLan"; private const val PORT = 38899 }
}
