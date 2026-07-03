package com.pulsepointlabs.polarwiz.wiz

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.pulsepointlabs.polarwiz.model.Rgb
import com.pulsepointlabs.polarwiz.model.WizLight
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException

class WizLanManager(context: Context) {
    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

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
                        val name = result?.optString("moduleName")?.takeIf { it.isNotBlank() }
                            ?: result?.optString("mac")?.takeIf { it.isNotBlank() }
                            ?: "WiZ light"
                        found[packet.address.hostAddress ?: name] = WizLight(packet.address, name)
                        Log.i(TAG, "Discovered $name at ${packet.address.hostAddress}")
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

    suspend fun pulse(lights: List<WizLight>, delta: Int = -8, durationMs: Int = 180): Result<Unit> =
        sendPayload(
            lights,
            JSONObject().put("method", "pulse").put(
                "params",
                JSONObject().put("delta", delta.coerceIn(-30, -1)).put("duration", durationMs.coerceIn(100, 500))
            )
        )

    private suspend fun send(lights: List<WizLight>, params: JSONObject.() -> Unit): Result<Unit> =
        sendPayload(lights, JSONObject().put("method", "setPilot").put("params", JSONObject().apply(params)))

    private suspend fun sendPayload(lights: List<WizLight>, message: JSONObject): Result<Unit> = withContext(Dispatchers.IO) {
        if (lights.isEmpty()) return@withContext Result.failure(IllegalStateException("Select at least one WiZ light"))
        runCatching {
            val payload = message.toString().toByteArray()
            DatagramSocket().use { socket ->
                lights.forEach { light ->
                    socket.send(DatagramPacket(payload, payload.size, light.address, PORT))
                    Log.i(TAG, "Command ${String(payload)} -> ${light.address.hostAddress}")
                }
            }
        }
    }

    companion object { private const val TAG = "WizLan"; private const val PORT = 38899 }
}
