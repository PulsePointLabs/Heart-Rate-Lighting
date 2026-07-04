package com.pulsepointlabs.polarwiz.hue

import com.pulsepointlabs.polarwiz.model.HueLight
import com.pulsepointlabs.polarwiz.model.Rgb
import com.pulsepointlabs.polarwiz.DiagnosticLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.net.URL
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import kotlin.math.pow

class HueBridgeManager {
    private val groupMutex = Mutex()
    private var cachedGroupId: String? = null
    private var cachedLightIds: Set<String> = emptySet()
    suspend fun pair(ip: String): Result<String> = withContext(Dispatchers.IO) { runCatching {
        val response = JSONArray(request(ip, "/api", "POST", JSONObject().put("devicetype", "polar_wiz_hr#android")))
        response.optJSONObject(0)?.optJSONObject("success")?.optString("username")
            ?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException(response.optJSONObject(0)?.optJSONObject("error")?.optString("description") ?: "Press the bridge button and try again")
    } }

    suspend fun lights(ip: String, key: String): Result<List<HueLight>> = withContext(Dispatchers.IO) { runCatching {
        val json = JSONObject(request(ip, "/api/$key/lights"))
        json.keys().asSequence().map { id ->
            val item = json.getJSONObject(id)
            val control = item.optJSONObject("capabilities")?.optJSONObject("control")
            HueLight(
                id,
                item.optString("name", "Hue light $id"),
                online = item.optJSONObject("state")?.optBoolean("reachable", true) != false,
                supportsColor = control?.has("colorgamut") ?: item.optJSONObject("state")?.has("xy") ?: true,
                supportsTemperature = control?.has("ct") ?: item.optJSONObject("state")?.has("ct") ?: true
            )
        }.toList().sortedBy { it.name }
    } }

    suspend fun setColor(ip: String, key: String, lights: List<HueLight>, color: Rgb?, brightness: Int, temperature: Int? = null): Result<Unit> =
        sendPerLight(ip, key, lights) { light -> JSONObject().apply {
            put("on", true); put("bri", (brightness.coerceIn(1, 100) * 254 / 100).coerceIn(1, 254)); put("transitiontime", 6)
            if (temperature != null && light.supportsTemperature) put("ct", (1_000_000 / temperature.coerceIn(2000, 6500)).coerceIn(153, 500))
            if (color != null && light.supportsColor) put("xy", rgbToXy(color))
        } }

    suspend fun setBrightness(ip: String, key: String, lights: List<HueLight>, brightness: Int) = send(ip, key, lights) {
        put("on", true); put("bri", (brightness.coerceIn(1, 100) * 254 / 100).coerceIn(1, 254)); put("transitiontime", 4)
    }
    suspend fun turnOff(ip: String, key: String, lights: List<HueLight>) = send(ip, key, lights) { put("on", false); put("transitiontime", 4) }
    suspend fun pulse(ip: String, key: String, lights: List<HueLight>, baseBrightness: Int, intensity: Int, durationMs: Int): Result<Unit> =
        withContext(Dispatchers.IO) { runCatching {
            val selected = lights.filter { it.selected && it.online }
            if (selected.isEmpty()) return@runCatching
            val groupId = ensureAppGroup(ip, key, selected)
            val base = (baseBrightness.coerceIn(10, 100) * 254 / 100).coerceIn(1, 254)
            val dip = ((baseBrightness - intensity).coerceIn(1, 100) * 254 / 100).coerceIn(1, 254)
            validateCommandResponse(
                request(ip, "/api/$key/groups/$groupId/action", "PUT", JSONObject().put("on", true).put("bri", dip).put("transitiontime", 1)),
                "group $groupId pulse down"
            )
            delay(durationMs.toLong())
            validateCommandResponse(
                request(ip, "/api/$key/groups/$groupId/action", "PUT", JSONObject().put("bri", base).put("transitiontime", 2)),
                "group $groupId pulse restore"
            )
        } }
    suspend fun colorPulse(
        ip: String,
        key: String,
        lights: List<HueLight>,
        color: Rgb,
        baseBrightness: Int,
        intensity: Int,
        durationMs: Int,
        baseTemperature: Int = 5000
    ): Result<Unit> = withContext(Dispatchers.IO) { runCatching {
        val selected = lights.filter { it.selected && it.online }
        if (selected.isEmpty()) return@runCatching
        val groupId = ensureAppGroup(ip, key, selected)
        val pulseBrightness = ((baseBrightness - intensity).coerceIn(1, 100) * 254 / 100).coerceIn(1, 254)
        val base = (baseBrightness.coerceIn(1, 100) * 254 / 100).coerceIn(1, 254)
        validateCommandResponse(request(ip, "/api/$key/groups/$groupId/action", "PUT", JSONObject().apply {
            put("on", true); put("bri", pulseBrightness); put("xy", rgbToXy(color)); put("transitiontime", 1)
        }), "group $groupId color pulse")
        delay(durationMs.toLong())
        validateCommandResponse(request(ip, "/api/$key/groups/$groupId/action", "PUT", JSONObject().apply {
            put("bri", base); put("ct", (1_000_000 / baseTemperature.coerceIn(2000, 6500)).coerceIn(153, 500)); put("transitiontime", 2)
        }), "group $groupId daylight restore")
    } }
    suspend fun snapshot(ip: String, key: String, lights: List<HueLight>): Map<String, JSONObject> = withContext(Dispatchers.IO) {
        buildMap { lights.forEach { light -> runCatching {
            val state = JSONObject(request(ip, "/api/$key/lights/${light.id}")).getJSONObject("state")
            put(light.id, JSONObject().apply { listOf("on", "bri", "xy", "ct", "hue", "sat", "effect").forEach { if (state.has(it)) put(it, state.get(it)) } })
        } } }
    }
    suspend fun restore(ip: String, key: String, states: Map<String, JSONObject>): Result<Unit> = withContext(Dispatchers.IO) { runCatching {
        states.forEach { (id, state) -> validateCommandResponse(request(ip, "/api/$key/lights/$id/state", "PUT", state.put("transitiontime", 6)), id) }
    } }

    private suspend fun send(ip: String, key: String, lights: List<HueLight>, body: JSONObject.() -> Unit): Result<Unit> = withContext(Dispatchers.IO) { runCatching {
        lights.filter { it.selected && it.online }.forEach {
            validateCommandResponse(request(ip, "/api/$key/lights/${it.id}/state", "PUT", JSONObject().apply(body)), it.id)
        }
    } }
    private suspend fun sendPerLight(ip: String, key: String, lights: List<HueLight>, body: (HueLight) -> JSONObject): Result<Unit> = withContext(Dispatchers.IO) { runCatching {
        lights.filter { it.selected && it.online }.forEach { light ->
            validateCommandResponse(request(ip, "/api/$key/lights/${light.id}/state", "PUT", body(light)), light.id)
        }
    } }
    private suspend fun ensureAppGroup(ip: String, key: String, lights: List<HueLight>): String = groupMutex.withLock {
        val ids = lights.map { it.id }.toSet()
        cachedGroupId?.takeIf { cachedLightIds == ids }?.let { return@withLock it }
        val groups = JSONObject(request(ip, "/api/$key/groups"))
        var groupId = groups.keys().asSequence().firstOrNull { groups.optJSONObject(it)?.optString("name") == APP_GROUP_NAME }
        val body = JSONObject().put("name", APP_GROUP_NAME).put("lights", JSONArray(ids.toList()))
        if (groupId == null) {
            body.put("type", "LightGroup")
            val created = JSONArray(request(ip, "/api/$key/groups", "POST", body))
            val error = created.optJSONObject(0)?.optJSONObject("error")
            if (error != null) throw IllegalStateException(error.optString("description", "Could not create Hue app group"))
            groupId = created.optJSONObject(0)?.optJSONObject("success")?.optString("id")
                ?: throw IllegalStateException("Hue Bridge did not return the app group ID")
        } else {
            validateCommandResponse(request(ip, "/api/$key/groups/$groupId", "PUT", body), "group $groupId update")
        }
        cachedGroupId = groupId
        cachedLightIds = ids
        DiagnosticLog.add("HueBridge", "App group $groupId contains Hue lights ${ids.sorted().joinToString()}")
        groupId
    }
    private fun validateCommandResponse(raw: String, lightId: String) {
        val response = JSONArray(raw)
        val error = (0 until response.length()).asSequence().mapNotNull { response.optJSONObject(it)?.optJSONObject("error") }.firstOrNull()
        if (error != null) throw IllegalStateException("Light $lightId: ${error.optString("description", "bridge rejected command")}")
        if ((0 until response.length()).none { response.optJSONObject(it)?.has("success") == true }) {
            throw IllegalStateException("Light $lightId: bridge returned no success result")
        }
        DiagnosticLog.add("HueBridge", "Command accepted for Hue light $lightId")
    }
    private fun request(ip: String, path: String, method: String = "GET", body: JSONObject? = null): String {
        val connection = URL("https://$ip$path").openConnection() as HttpsURLConnection
        connection.sslSocketFactory = sslContext.socketFactory
        connection.hostnameVerifier = HostnameVerifier { _, _ -> true }
        connection.requestMethod = method; connection.connectTimeout = 2500; connection.readTimeout = 3000
        if (body != null) { connection.doOutput = true; connection.setRequestProperty("Content-Type", "application/json"); connection.outputStream.use { it.write(body.toString().toByteArray()) } }
        return connection.inputStream.bufferedReader().use { it.readText() }.also { connection.disconnect() }
    }
    private fun rgbToXy(rgb: Rgb): JSONArray {
        fun linear(v: Int): Double { val n = v / 255.0; return if (n > .04045) ((n + .055) / 1.055).pow(2.4) else n / 12.92 }
        val r = linear(rgb.r); val g = linear(rgb.g); val b = linear(rgb.b)
        val x = r * .664511 + g * .154324 + b * .162028; val y = r * .283881 + g * .668433 + b * .047685; val z = r * .000088 + g * .07231 + b * .986039
        val sum = x + y + z
        return JSONArray().put(if (sum == 0.0) .3227 else x / sum).put(if (sum == 0.0) .329 else y / sum)
    }
    companion object {
        private const val APP_GROUP_NAME = "Polar WiZ HR"
        private val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) = Unit
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        }), SecureRandom()) }
    }
}
