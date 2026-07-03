package com.pulsepointlabs.polarwiz

import android.app.Application
import android.util.Log
import android.content.Intent
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.pulsepointlabs.polarwiz.ble.PolarH10Manager
import com.pulsepointlabs.polarwiz.hr.HeartRateProcessor
import com.pulsepointlabs.polarwiz.model.HrZone
import com.pulsepointlabs.polarwiz.model.PolarDevice
import com.pulsepointlabs.polarwiz.model.Rgb
import com.pulsepointlabs.polarwiz.model.WizLight
import com.pulsepointlabs.polarwiz.model.LightingTheme
import com.pulsepointlabs.polarwiz.wiz.WizLanManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlin.math.sin
import java.net.InetAddress
import org.json.JSONArray
import org.json.JSONObject

data class UiState(
    val polarStatus: String = "Disconnected",
    val polarDevices: List<PolarDevice> = emptyList(),
    val bpm: Int? = null,
    val smoothedBpm: Int? = null,
    val rrMs: Int? = null,
    val wizStatus: String = "No lights discovered",
    val lights: List<WizLight> = emptyList(),
    val lightingTheme: LightingTheme = LightingTheme.PULSE,
    val brightnessOverride: Int? = null,
    val automationEnabled: Boolean = false,
    val heartbeatPulseEnabled: Boolean = false,
    val automationPaused: Boolean = false,
    val activeGroup: String = "All lights",
    val demoEnabled: Boolean = false,
    val zone: HrZone? = null,
    val lastCommand: String = "none",
    val error: String? = null
)

class LightingRuntime(private val application: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val polar = PolarH10Manager(application, scope)
    private val wiz = WizLanManager(application)
    private val processor = HeartRateProcessor()
    private val preferences = application.getSharedPreferences("polar_wiz_preferences", 0)
    private val restoredLights = loadSavedLights()
    private val _ui = MutableStateFlow(
        UiState(
            wizStatus = if (restoredLights.isEmpty()) "No lights discovered" else "Restored ${restoredLights.size} saved light(s)",
            lights = restoredLights,
            lightingTheme = runCatching {
                LightingTheme.valueOf(preferences.getString("lighting_theme", LightingTheme.PULSE.name)!!)
            }.getOrDefault(LightingTheme.PULSE),
            brightnessOverride = preferences.getInt("brightness_override", -1).takeIf { it in 10..100 },
            automationEnabled = preferences.getBoolean("automation_enabled", false),
            heartbeatPulseEnabled = preferences.getBoolean("heartbeat_pulse_enabled", false)
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private var demoJob: Job? = null
    private var automationJob: Job? = null
    private var heartbeatJob: Job? = null
    private var healthJob: Job? = null
    private var lastSentZone: HrZone? = null
    private var lastCommandAt = 0L
    private var polarSessionActive = false
    @Volatile private var lastSarahVsSampleAt = 0L
    private var previousLightStates: Map<String, JSONObject> = emptyMap()

    init {
        scope.launch { polar.devices.collect { _ui.value = _ui.value.copy(polarDevices = it) } }
        scope.launch { polar.connectionState.collect { _ui.value = _ui.value.copy(polarStatus = it) } }
        scope.launch { polar.errors.collect { setError(it) } }
        scope.launch { polar.readings.collect { (bpm, rr) ->
            if (!_ui.value.demoEnabled && System.currentTimeMillis() - lastSarahVsSampleAt > SARAHVS_FEED_TIMEOUT_MS) acceptBpm(bpm, rr)
        } }
        if (_ui.value.automationEnabled) updateBackgroundService()
        if (_ui.value.heartbeatPulseEnabled) setHeartbeatPulse(true)
        preferences.getString("last_h10_address", null)?.let { address ->
            _ui.value = _ui.value.copy(polarStatus = "Reconnecting to saved H10…")
            connectPolar(address)
        }
        if (restoredLights.isNotEmpty()) discoverLights(silentRefresh = true)
        healthJob = scope.launch {
            while (true) {
                delay(30_000)
                val snapshot = _ui.value.lights
                if (snapshot.isNotEmpty()) {
                    val onlineIds = wiz.probe(snapshot)
                    val now = System.currentTimeMillis()
                    _ui.value = _ui.value.copy(lights = snapshot.map {
                        if (it.deviceId in onlineIds) it.copy(online = true, lastSeenMs = now) else it.copy(online = false)
                    })
                    DiagnosticLog.add(TAG, "WiZ health: ${onlineIds.size}/${snapshot.size} responding")
                }
            }
        }
        scope.launch {
            while (true) {
                delay(3_000)
                if (lastSarahVsSampleAt > 0 && System.currentTimeMillis() - lastSarahVsSampleAt > SARAHVS_FEED_TIMEOUT_MS) {
                    lastSarahVsSampleAt = 0
                    preferences.getString("last_h10_address", null)?.let { connectPolar(it) }
                }
            }
        }
    }

    fun scanPolar() = polar.scan()
    fun connectPolar(id: String) {
        preferences.edit().putString("last_h10_address", id).apply()
        polarSessionActive = true
        updateBackgroundService()
        polar.connect(id)
    }
    fun disconnectPolar() {
        polarSessionActive = false
        polar.disconnect()
        updateBackgroundService()
    }

    fun discoverLights(silentRefresh: Boolean = false) {
        scope.launch {
            if (!silentRefresh) _ui.value = _ui.value.copy(wizStatus = "Discovering on local Wi-Fi…", error = null)
            runCatching { wiz.discover() }
                .onSuccess { found ->
                    val existingById = _ui.value.lights.associateBy { it.deviceId }
                    val existingByIp = _ui.value.lights.associateBy { it.address.hostAddress }
                    val named = found.sortedBy { it.address.hostAddress.orEmpty().split('.').lastOrNull()?.toIntOrNull() ?: 0 }
                        .mapIndexed { index, light ->
                            val previous = existingById[light.deviceId] ?: existingByIp[light.address.hostAddress]
                            light.copy(
                                name = savedLightName(light.deviceId, light.address.hostAddress, index + 1),
                                selected = previous?.selected ?: true,
                                group = previous?.group ?: "All lights"
                            )
                        }
                    val discoveredIds = named.map { it.deviceId }.toSet()
                    val discoveredAddresses = named.map { it.address.hostAddress }.toSet()
                    val savedOnly = _ui.value.lights.filterNot {
                        it.deviceId in discoveredIds || it.address.hostAddress in discoveredAddresses
                    }
                    val merged = named + savedOnly
                    _ui.value = _ui.value.copy(
                        lights = merged,
                        wizStatus = when {
                            found.isNotEmpty() -> "${found.size} light(s) online"
                            merged.isNotEmpty() -> "Using ${merged.size} saved light address(es)"
                            else -> "No WiZ lights replied"
                        }
                    )
                    persistLights()
                }
                .onFailure { if (!silentRefresh) setError("WiZ discovery failed: ${it.message}") }
        }
    }

    fun setLightSelected(address: String, selected: Boolean) {
        _ui.value = _ui.value.copy(lights = _ui.value.lights.map {
            if (it.address.hostAddress == address) it.copy(selected = selected) else it
        })
        persistLights()
    }

    fun renameLight(address: String, requestedName: String) {
        val name = requestedName.trim().take(40)
        if (name.isBlank()) return
        val identity = _ui.value.lights.firstOrNull { it.address.hostAddress == address }?.deviceId ?: address
        preferences.edit().putString("light_name_$identity", name).apply()
        _ui.value = _ui.value.copy(lights = _ui.value.lights.map {
            if (it.address.hostAddress == address) it.copy(name = name) else it
        })
        persistLights()
    }

    fun updateLightDetails(address: String, requestedName: String, requestedGroup: String) {
        val name = requestedName.trim().take(40).ifBlank { return }
        val group = requestedGroup.trim().take(30).ifBlank { "All lights" }
        val light = _ui.value.lights.firstOrNull { it.address.hostAddress == address } ?: return
        preferences.edit().putString("light_name_${light.deviceId}", name).apply()
        _ui.value = _ui.value.copy(lights = _ui.value.lights.map {
            if (it.deviceId == light.deviceId) it.copy(name = name, group = group) else it
        })
        persistLights()
    }

    fun setActiveGroup(group: String) { _ui.value = _ui.value.copy(activeGroup = group) }

    fun setAutomationPaused(paused: Boolean) {
        _ui.value = _ui.value.copy(automationPaused = paused)
        DiagnosticLog.add(TAG, if (paused) "Automation paused" else "Automation resumed")
        if (!paused) {
            lastSentZone = null
            _ui.value.zone?.let(::queueAutomation)
        }
    }

    fun identifyLight(address: String) {
        val light = _ui.value.lights.firstOrNull { it.address.hostAddress == address }
            ?: return setError("Light is no longer in the discovered list")
        scope.launch {
            _ui.value = _ui.value.copy(lastCommand = "Identifying ${light.name}…", error = null)
            repeat(3) { index ->
                wiz.pulse(listOf(light), delta = -60, durationMs = 500).onFailure {
                    setError("Could not identify ${light.name}: ${it.message}")
                    return@launch
                }
                if (index < 2) delay(700)
            }
            _ui.value = _ui.value.copy(lastCommand = "Identified ${light.name}")
        }
    }

    fun setLightingTheme(theme: LightingTheme) {
        if (_ui.value.lightingTheme == theme) return
        lastSentZone = null
        _ui.value = _ui.value.copy(lightingTheme = theme)
        preferences.edit().putString("lighting_theme", theme.name).apply()
        if (_ui.value.automationEnabled) _ui.value.zone?.let(::queueAutomation)
    }

    fun setAutomationBrightness(brightness: Int) {
        val value = brightness.coerceIn(10, 100)
        preferences.edit().putInt("brightness_override", value).apply()
        _ui.value = _ui.value.copy(brightnessOverride = value)
        lastSentZone = null
        scope.launch {
            wiz.setBrightness(selectedLights(), value).fold(
                onSuccess = { _ui.value = _ui.value.copy(lastCommand = "Brightness set to $value%", error = null) },
                onFailure = { setError("Brightness command failed: ${it.message}") }
            )
        }
    }

    fun clearAutomationBrightness() {
        preferences.edit().remove("brightness_override").apply()
        _ui.value = _ui.value.copy(brightnessOverride = null)
        lastSentZone = null
        if (_ui.value.automationEnabled) _ui.value.zone?.let(::queueAutomation)
    }

    fun addLightByIp(rawIp: String) {
        scope.launch {
            runCatching {
                val ip = rawIp.trim()
                require(IPV4.matches(ip)) { "Enter a numeric IPv4 address" }
                val address = with(kotlinx.coroutines.Dispatchers.IO) { InetAddress.getByName(ip) }
                val identity = "ip:$ip"
                val light = WizLight(
                    address = address,
                    deviceId = identity,
                    name = savedLightName(identity, ip, _ui.value.lights.size + 1)
                )
                _ui.value = _ui.value.copy(
                    lights = (_ui.value.lights.filterNot { it.address.hostAddress == ip } + light),
                    wizStatus = "Manual light added: $ip",
                    error = null
                )
                persistLights()
            }.onFailure { setError("Could not add WiZ IP: ${it.message}") }
        }
    }

    fun manualColor(color: Rgb?, brightness: Int, temperature: Int? = null) {
        scope.launch {
            val selected = selectedLights()
            wiz.setColor(selected, color, brightness, temperature).fold(
                onSuccess = { _ui.value = _ui.value.copy(lastCommand = "Manual color at $brightness%", error = null) },
                onFailure = { setError("WiZ command failed: ${it.message}") }
            )
        }
    }

    fun turnOff() {
        scope.launch {
            wiz.turnOff(selectedLights()).fold(
                onSuccess = { _ui.value = _ui.value.copy(lastCommand = "Lights off", error = null) },
                onFailure = { setError("WiZ command failed: ${it.message}") }
            )
        }
    }

    fun setAutomation(enabled: Boolean) {
        lastSentZone = null
        _ui.value = _ui.value.copy(automationEnabled = enabled)
        preferences.edit().putBoolean("automation_enabled", enabled).apply()
        updateBackgroundService()
        if (enabled) {
            scope.launch {
                previousLightStates = wiz.snapshot(selectedLights())
                DiagnosticLog.add(TAG, "Captured ${previousLightStates.size} pre-automation light states")
                _ui.value.zone?.let(::queueAutomation)
            }
        } else if (previousLightStates.isNotEmpty()) {
            scope.launch {
                wiz.restore(_ui.value.lights, previousLightStates).onSuccess {
                    _ui.value = _ui.value.copy(lastCommand = "Restored pre-automation light state")
                }.onFailure { setError("Could not restore prior light state: ${it.message}") }
                previousLightStates = emptyMap()
            }
        }
    }

    fun setHeartbeatPulse(enabled: Boolean) {
        heartbeatJob?.cancel()
        heartbeatJob = null
        _ui.value = _ui.value.copy(heartbeatPulseEnabled = enabled)
        preferences.edit().putBoolean("heartbeat_pulse_enabled", enabled).apply()
        if (enabled) {
            heartbeatJob = scope.launch {
                while (true) {
                    val state = _ui.value
                    val bpm = state.smoothedBpm
                    if (state.automationEnabled && !state.automationPaused && bpm != null && selectedLights().isNotEmpty()) {
                        val intervalMs = (state.rrMs?.toLong()?.coerceIn(300L, 2_000L)
                            ?: (60_000L / bpm.coerceIn(40, 200))).coerceAtLeast(500L)
                        val durationMs = (intervalMs / 3).toInt().coerceIn(120, 220)
                        wiz.pulse(selectedLights(), delta = -8, durationMs = durationMs)
                            .onFailure { Log.w(TAG, "Heartbeat pulse skipped: ${it.message}") }
                        delay(intervalMs)
                    } else {
                        delay(500)
                    }
                }
            }
        }
    }

    fun setDemo(enabled: Boolean) {
        demoJob?.cancel()
        processor.reset()
        _ui.value = _ui.value.copy(demoEnabled = enabled, bpm = null, smoothedBpm = null, rrMs = null)
        if (enabled) {
            demoJob = scope.launch {
                var tick = 0
                while (true) {
                    val bpm = (108 + 48 * sin(tick / 9.0)).toInt().coerceIn(58, 158)
                    acceptBpm(bpm, 60_000 / bpm)
                    tick++
                    delay(1_000)
                }
            }
        }
    }

    fun acceptSarahVsHeartRate(bpm: Int, rr: Int?) {
        if (_ui.value.demoEnabled) return
        val firstSharedSample = System.currentTimeMillis() - lastSarahVsSampleAt > SARAHVS_FEED_TIMEOUT_MS
        lastSarahVsSampleAt = System.currentTimeMillis()
        if (firstSharedSample) {
            polar.disconnect()
            polarSessionActive = false
        }
        _ui.value = _ui.value.copy(polarStatus = "Live HR shared by SarahVS")
        acceptBpm(bpm, rr)
    }

    private fun acceptBpm(bpm: Int, rr: Int?) {
        val smooth = processor.add(bpm)
        val zone = processor.zoneFor(smooth)
        _ui.value = _ui.value.copy(bpm = bpm, smoothedBpm = smooth, rrMs = rr, zone = zone)
        if (_ui.value.automationEnabled && !_ui.value.automationPaused && zone != lastSentZone) queueAutomation(zone)
    }

    private fun queueAutomation(zone: HrZone) {
        automationJob?.cancel()
        automationJob = scope.launch {
            val remaining = (lastCommandAt + MIN_COMMAND_INTERVAL_MS - System.currentTimeMillis()).coerceAtLeast(0)
            delay(remaining)
            if (!_ui.value.automationEnabled || _ui.value.automationPaused || _ui.value.zone != zone || lastSentZone == zone) return@launch
            val style = _ui.value.lightingTheme.styleFor(zone)
            val brightness = _ui.value.brightnessOverride ?: style.brightness
            wiz.setColor(selectedLights(), style.color, brightness, style.temperature).fold(
                onSuccess = {
                    lastSentZone = zone
                    lastCommandAt = System.currentTimeMillis()
                    _ui.value = _ui.value.copy(lastCommand = "${_ui.value.lightingTheme.displayName}: ${zone.label}, $brightness%", error = null)
                },
                onFailure = { setError("Automation command failed: ${it.message}") }
            )
        }
    }

    private fun selectedLights() = _ui.value.lights.filter {
        it.selected && it.online && (_ui.value.activeGroup == "All lights" || it.group == _ui.value.activeGroup)
    }
    private fun savedLightName(identity: String?, legacyAddress: String?, number: Int): String =
        identity?.let { preferences.getString("light_name_$it", null) }
            ?: legacyAddress?.let { preferences.getString("light_name_$it", null) }
            ?: "WiZ Light $number"
    private fun setError(message: String) {
        Log.e(TAG, message)
        DiagnosticLog.add(TAG, message)
        _ui.value = _ui.value.copy(error = message)
    }

    private fun persistLights() {
        val array = JSONArray()
        _ui.value.lights.forEach { light ->
            array.put(JSONObject().apply {
                put("ip", light.address.hostAddress)
                put("id", light.deviceId)
                put("name", light.name)
                put("selected", light.selected)
                put("group", light.group)
            })
        }
        preferences.edit().putString("saved_lights", array.toString()).apply()
    }

    private fun loadSavedLights(): List<WizLight> = runCatching {
        val array = JSONArray(preferences.getString("saved_lights", "[]"))
        buildList {
            for (index in 0 until array.length()) {
                val item = array.getJSONObject(index)
                val ip = item.getString("ip")
                add(WizLight(
                    address = InetAddress.getByName(ip),
                    deviceId = item.optString("id", "ip:$ip"),
                    name = item.optString("name", "WiZ Light ${index + 1}"),
                    selected = item.optBoolean("selected", true),
                    online = true,
                    group = item.optString("group", "All lights")
                ))
            }
        }
    }.getOrElse {
        Log.w(TAG, "Could not restore saved lights", it)
        emptyList()
    }

    private fun updateBackgroundService() {
        val context = application
        runCatching {
            val intent = Intent(context, AutomationKeepAliveService::class.java)
            if (polarSessionActive || _ui.value.automationEnabled) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.stopService(intent)
            }
        }.onFailure { setError("Background service failed: ${it.message}") }
    }

    fun shutdown() {
        heartbeatJob?.cancel()
        healthJob?.cancel()
        polar.close()
        application.stopService(Intent(application, AutomationKeepAliveService::class.java))
        scope.cancel()
    }

    companion object {
        private const val TAG = "PolarWizVM"
        private const val MIN_COMMAND_INTERVAL_MS = 3_000L
        private const val SARAHVS_FEED_TIMEOUT_MS = 6_000L
        private val IPV4 = Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")
    }
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val runtime = (application as PolarWizApplication).runtime
    val ui: StateFlow<UiState> = runtime.ui

    fun scanPolar() = runtime.scanPolar()
    fun connectPolar(id: String) = runtime.connectPolar(id)
    fun disconnectPolar() = runtime.disconnectPolar()
    fun discoverLights() = runtime.discoverLights()
    fun setLightSelected(address: String, selected: Boolean) = runtime.setLightSelected(address, selected)
    fun renameLight(address: String, name: String) = runtime.renameLight(address, name)
    fun updateLightDetails(address: String, name: String, group: String) = runtime.updateLightDetails(address, name, group)
    fun setActiveGroup(group: String) = runtime.setActiveGroup(group)
    fun setAutomationPaused(paused: Boolean) = runtime.setAutomationPaused(paused)
    fun identifyLight(address: String) = runtime.identifyLight(address)
    fun setLightingTheme(theme: LightingTheme) = runtime.setLightingTheme(theme)
    fun setAutomationBrightness(brightness: Int) = runtime.setAutomationBrightness(brightness)
    fun clearAutomationBrightness() = runtime.clearAutomationBrightness()
    fun addLightByIp(ip: String) = runtime.addLightByIp(ip)
    fun manualColor(color: Rgb?, brightness: Int, temperature: Int? = null) = runtime.manualColor(color, brightness, temperature)
    fun turnOff() = runtime.turnOff()
    fun setAutomation(enabled: Boolean) = runtime.setAutomation(enabled)
    fun setHeartbeatPulse(enabled: Boolean) = runtime.setHeartbeatPulse(enabled)
    fun setDemo(enabled: Boolean) = runtime.setDemo(enabled)
}
