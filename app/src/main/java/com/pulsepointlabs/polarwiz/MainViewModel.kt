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
import com.pulsepointlabs.polarwiz.model.HueLight
import com.pulsepointlabs.polarwiz.hue.HueBridgeManager
import com.pulsepointlabs.polarwiz.model.LightingTheme
import com.pulsepointlabs.polarwiz.wiz.WizLanManager
import com.pulsepointlabs.polarwiz.sleep.SleepWakeDetector
import com.pulsepointlabs.polarwiz.sleep.SleepWakeMonitor
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
    val hueBridgeIp: String = "192.168.0.15",
    val hueStatus: String = "Hue Bridge not paired",
    val hueLights: List<HueLight> = emptyList(),
    val lightingTheme: LightingTheme = LightingTheme.PULSE,
    val brightnessOverride: Int? = null,
    val automationEnabled: Boolean = false,
    val heartbeatPulseEnabled: Boolean = false,
    val heartbeatPulseIntensity: Int = 8,
    val sleepAutomationEnabled: Boolean = false,
    val restoreLightsOnWake: Boolean = true,
    val sleepStatus: String = "Awake",
    val automationPaused: Boolean = false,
    val activeGroup: String = "All lights",
    val groups: List<String> = listOf("All lights"),
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
    private val restoredGroups = loadSavedGroups(restoredLights)
    private val _ui = MutableStateFlow(
        UiState(
            wizStatus = if (restoredLights.isEmpty()) "No lights discovered" else "Restored ${restoredLights.size} saved light(s)",
            lights = restoredLights,
            hueBridgeIp = preferences.getString("hue_bridge_ip", "192.168.0.15") ?: "192.168.0.15",
            lightingTheme = runCatching {
                LightingTheme.valueOf(preferences.getString("lighting_theme", LightingTheme.PULSE.name)!!)
            }.getOrDefault(LightingTheme.PULSE),
            brightnessOverride = preferences.getInt("brightness_override", -1).takeIf { it in 10..100 },
            automationEnabled = preferences.getBoolean("automation_enabled", false),
            heartbeatPulseEnabled = preferences.getBoolean("heartbeat_pulse_enabled", false),
            heartbeatPulseIntensity = preferences.getInt("heartbeat_pulse_intensity", 8).coerceIn(2, 40),
            sleepAutomationEnabled = preferences.getBoolean("sleep_automation_enabled", false),
            restoreLightsOnWake = preferences.getBoolean("restore_lights_on_wake", true),
            groups = restoredGroups,
            activeGroup = preferences.getString("active_group", "All lights")
                ?.takeIf { it in restoredGroups } ?: "All lights"
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private var demoJob: Job? = null
    private var automationJob: Job? = null
    private var heartbeatJob: Job? = null
    private var healthJob: Job? = null
    private var sleepEvaluationJob: Job? = null
    private var lastSentZone: HrZone? = null
    private var lastCommandAt = 0L
    private var polarSessionActive = false
    @Volatile private var lastSarahVsSampleAt = 0L
    private var previousLightStates: Map<String, JSONObject> = emptyMap()
    private var preSleepLightStates: Map<String, JSONObject> = emptyMap()
    private var preSleepHueStates: Map<String, JSONObject> = emptyMap()
    private val hue = HueBridgeManager()
    private val hueKey get() = preferences.getString("hue_key", null)
    private val sleepDetector = SleepWakeDetector()
    private val sleepMonitor = SleepWakeMonitor(application, ::acceptMotion, ::acceptSignificantMotion)

    init {
        scope.launch { polar.devices.collect { _ui.value = _ui.value.copy(polarDevices = it) } }
        scope.launch { polar.connectionState.collect { _ui.value = _ui.value.copy(polarStatus = it) } }
        scope.launch { polar.errors.collect { setError(it) } }
        scope.launch { polar.readings.collect { (bpm, rr) ->
            if (!_ui.value.demoEnabled && System.currentTimeMillis() - lastSarahVsSampleAt > SARAHVS_FEED_TIMEOUT_MS) acceptBpm(bpm, rr)
        } }
        if (_ui.value.automationEnabled) updateBackgroundService()
        if (_ui.value.heartbeatPulseEnabled) setHeartbeatPulse(true)
        if (_ui.value.sleepAutomationEnabled) {
            startSleepDetection()
            updateBackgroundService()
        }
        preferences.getString("last_h10_address", null)?.let { address ->
            _ui.value = _ui.value.copy(polarStatus = "Reconnecting to saved H10…")
            connectPolar(address)
        }
        if (restoredLights.isNotEmpty()) discoverLights(silentRefresh = true)
        if (hueKey != null) refreshHueLights()
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

    fun pairHueBridge(rawIp: String) {
        val ip = rawIp.trim()
        if (!IPV4.matches(ip)) return setError("Enter the Hue Bridge IPv4 address")
        scope.launch {
            _ui.value = _ui.value.copy(hueBridgeIp = ip, hueStatus = "Pairing — press the bridge button…", error = null)
            hue.pair(ip).fold(onSuccess = { key ->
                preferences.edit().putString("hue_bridge_ip", ip).putString("hue_key", key).apply()
                _ui.value = _ui.value.copy(hueStatus = "Hue Bridge paired")
                refreshHueLights()
            }, onFailure = { setError("Hue pairing failed: ${it.message}") })
        }
    }

    fun refreshHueLights() {
        val key = hueKey ?: return
        val ip = _ui.value.hueBridgeIp
        val selected = preferences.getStringSet("hue_selected", emptySet()).orEmpty()
        scope.launch { hue.lights(ip, key).fold(onSuccess = { found ->
            val firstLoad = selected.isEmpty()
            _ui.value = _ui.value.copy(hueLights = found.map { it.copy(selected = firstLoad || it.id in selected) }, hueStatus = "${found.size} Hue light(s) connected", error = null)
        }, onFailure = { setError("Hue Bridge failed: ${it.message}") }) }
    }

    fun setHueLightSelected(id: String, selected: Boolean) {
        val lights = _ui.value.hueLights.map { if (it.id == id) it.copy(selected = selected) else it }
        _ui.value = _ui.value.copy(hueLights = lights)
        preferences.edit().putStringSet("hue_selected", lights.filter { it.selected }.map { it.id }.toSet()).apply()
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

    fun assignLightGroup(address: String, requestedGroup: String) {
        val requested = requestedGroup.trim().take(30).ifBlank { "All lights" }
        val light = _ui.value.lights.firstOrNull { it.address.hostAddress == address } ?: return
        val group = _ui.value.groups.firstOrNull { it.equals(requested, ignoreCase = true) }
            ?: requested.also { createGroup(it, activate = false) }
        _ui.value = _ui.value.copy(lights = _ui.value.lights.map {
            if (it.deviceId == light.deviceId) it.copy(group = group) else it
        })
        persistLights()
    }

    fun createGroup(requestedName: String, activate: Boolean = true): Boolean {
        val name = requestedName.trim().take(30)
        if (name.isBlank() || name.equals("All lights", ignoreCase = true)) return false
        val canonicalName = _ui.value.groups.firstOrNull { it.equals(name, ignoreCase = true) } ?: name
        val groups = (_ui.value.groups + canonicalName).distinctBy { it.lowercase() }
        _ui.value = _ui.value.copy(groups = groups, activeGroup = if (activate) canonicalName else _ui.value.activeGroup)
        persistGroups()
        if (activate) preferences.edit().putString("active_group", canonicalName).apply()
        return true
    }

    fun setActiveGroup(group: String) {
        if (group !in _ui.value.groups) return
        _ui.value = _ui.value.copy(activeGroup = group)
        preferences.edit().putString("active_group", group).apply()
    }

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
            selectedLights().takeIf { it.isNotEmpty() }?.let { wiz.setBrightness(it, value).fold(
                onSuccess = { _ui.value = _ui.value.copy(lastCommand = "Brightness set to $value%", error = null) },
                onFailure = { setError("Brightness command failed: ${it.message}") }
            ) }
            hueCredentials()?.takeIf { selectedHueLights().isNotEmpty() }?.let { (ip, key) ->
                hue.setBrightness(ip, key, selectedHueLights(), value).onFailure { setError("Hue brightness failed: ${it.message}") }
            }
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
            selected.takeIf { it.isNotEmpty() }?.let { wiz.setColor(it, color, brightness, temperature).fold(
                onSuccess = { _ui.value = _ui.value.copy(lastCommand = "Manual color at $brightness%", error = null) },
                onFailure = { setError("WiZ command failed: ${it.message}") }
            ) }
            hueCredentials()?.takeIf { selectedHueLights().isNotEmpty() }?.let { (ip, key) ->
                hue.setColor(ip, key, selectedHueLights(), color, brightness, temperature).fold(
                    onSuccess = { _ui.value = _ui.value.copy(lastCommand = "Manual color at $brightness%", error = null) },
                    onFailure = { setError("Hue command failed: ${it.message}") }
                )
            }
        }
    }

    fun turnOff() {
        scope.launch {
            selectedLights().takeIf { it.isNotEmpty() }?.let { wiz.turnOff(it).fold(
                onSuccess = { _ui.value = _ui.value.copy(lastCommand = "Lights off", error = null) },
                onFailure = { setError("WiZ command failed: ${it.message}") }
            ) }
            hueCredentials()?.takeIf { selectedHueLights().isNotEmpty() }?.let { (ip, key) ->
                hue.turnOff(ip, key, selectedHueLights()).fold(
                    onSuccess = { _ui.value = _ui.value.copy(lastCommand = "Lights off", error = null) },
                    onFailure = { setError("Hue command failed: ${it.message}") }
                )
            }
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
                    if (state.automationEnabled && !state.automationPaused && sleepDetector.state != SleepWakeDetector.State.SLEEPING && bpm != null && selectedLights().isNotEmpty()) {
                        val intervalMs = (state.rrMs?.toLong()?.coerceIn(300L, 2_000L)
                            ?: (60_000L / bpm.coerceIn(40, 200))).coerceAtLeast(500L)
                        val durationMs = (intervalMs / 3).toInt().coerceIn(120, 220)
                        wiz.pulse(selectedLights(), delta = -state.heartbeatPulseIntensity, durationMs = durationMs)
                            .onFailure { Log.w(TAG, "Heartbeat pulse skipped: ${it.message}") }
                        delay(intervalMs)
                    } else {
                        delay(500)
                    }
                }
            }
        }
    }

    fun setHeartbeatPulseIntensity(intensity: Int) {
        val value = intensity.coerceIn(2, 40)
        _ui.value = _ui.value.copy(heartbeatPulseIntensity = value)
        preferences.edit().putInt("heartbeat_pulse_intensity", value).apply()
    }

    fun setSleepAutomation(enabled: Boolean) {
        val wasSleeping = sleepDetector.state == SleepWakeDetector.State.SLEEPING
        preferences.edit().putBoolean("sleep_automation_enabled", enabled).apply()
        _ui.value = _ui.value.copy(
            sleepAutomationEnabled = enabled,
            sleepStatus = if (enabled) "Monitoring motion and heart rate" else "Awake"
        )
        if (enabled) {
            startSleepDetection()
        } else {
            if (wasSleeping) handleSleepEvent(sleepDetector.onSignificantMotion())
            stopSleepDetection(clearSnapshot = !wasSleeping)
        }
        updateBackgroundService()
    }

    fun setRestoreLightsOnWake(enabled: Boolean) {
        preferences.edit().putBoolean("restore_lights_on_wake", enabled).apply()
        _ui.value = _ui.value.copy(restoreLightsOnWake = enabled)
    }

    private fun startSleepDetection() {
        sleepDetector.reset()
        val hasAccelerometer = sleepMonitor.start()
        _ui.value = _ui.value.copy(sleepStatus = if (hasAccelerometer) {
            "Monitoring — sleep requires 20 min low motion + stable HR"
        } else {
            "Accelerometer unavailable"
        })
        sleepEvaluationJob?.cancel()
        if (hasAccelerometer) {
            sleepEvaluationJob = scope.launch {
                while (true) {
                    delay(5_000)
                    handleSleepEvent(sleepDetector.evaluate())
                }
            }
        }
    }

    private fun stopSleepDetection(clearSnapshot: Boolean = true) {
        sleepEvaluationJob?.cancel()
        sleepEvaluationJob = null
        sleepMonitor.stop()
        sleepDetector.reset()
        if (clearSnapshot) {
            preSleepLightStates = emptyMap()
            preSleepHueStates = emptyMap()
        }
    }

    private fun acceptMotion(acceleration: Float) {
        if (_ui.value.sleepAutomationEnabled) handleSleepEvent(sleepDetector.onMotion(acceleration))
    }

    private fun acceptSignificantMotion() {
        if (_ui.value.sleepAutomationEnabled) handleSleepEvent(sleepDetector.onSignificantMotion())
    }

    private fun handleSleepEvent(event: SleepWakeDetector.Event?) {
        when (event) {
            SleepWakeDetector.Event.SLEEP -> scope.launch {
                val lights = selectedLights()
                preSleepLightStates = wiz.snapshot(lights)
                hueCredentials()?.let { (ip, key) -> preSleepHueStates = hue.snapshot(ip, key, selectedHueLights()) }
                if (lights.isNotEmpty()) wiz.turnOff(lights).fold(
                    onSuccess = {
                        _ui.value = _ui.value.copy(sleepStatus = "Sleeping — lights off", lastCommand = "Sleep detected: lights off", error = null)
                        DiagnosticLog.add(TAG, "Sleep detected; captured ${preSleepLightStates.size} light states and turned lights off")
                    },
                    onFailure = { setError("Sleep lights-off failed: ${it.message}") }
                )
                hueCredentials()?.takeIf { selectedHueLights().isNotEmpty() }?.let { (ip, key) ->
                    hue.turnOff(ip, key, selectedHueLights()).onFailure { setError("Hue sleep lights-off failed: ${it.message}") }
                }
                if (lights.isEmpty() && selectedHueLights().isNotEmpty()) _ui.value = _ui.value.copy(sleepStatus = "Sleeping — lights off", lastCommand = "Sleep detected: lights off", error = null)
            }
            SleepWakeDetector.Event.WAKE -> scope.launch {
                val state = _ui.value
                val wizLights = selectedLights()
                val result = if (state.restoreLightsOnWake && preSleepLightStates.isNotEmpty()) {
                    wiz.restore(state.lights, preSleepLightStates)
                } else if (wizLights.isEmpty()) {
                    Result.success(Unit)
                } else {
                    val brightness = state.brightnessOverride
                        ?: state.zone?.let { state.lightingTheme.styleFor(it).brightness }
                        ?: 60
                    wiz.setBrightness(wizLights, brightness)
                }
                result.fold(
                    onSuccess = {
                        _ui.value = _ui.value.copy(
                            sleepStatus = "Awake — lights restored",
                            lastCommand = if (state.restoreLightsOnWake) "Wake detected: prior light state restored" else "Wake detected: lights on",
                            error = null
                        )
                        preSleepLightStates = emptyMap()
                        lastSentZone = null
                        DiagnosticLog.add(TAG, "Wake detected; lights restored")
                    },
                    onFailure = { setError("Wake light restore failed: ${it.message}") }
                )
                hueCredentials()?.let { (ip, key) ->
                    val hueResult = if (state.restoreLightsOnWake && preSleepHueStates.isNotEmpty()) hue.restore(ip, key, preSleepHueStates)
                    else {
                        val brightness = state.brightnessOverride ?: state.zone?.let { state.lightingTheme.styleFor(it).brightness } ?: 60
                        hue.setBrightness(ip, key, selectedHueLights(), brightness)
                    }
                    hueResult.onFailure { setError("Hue wake restore failed: ${it.message}") }
                    preSleepHueStates = emptyMap()
                }
            }
            null -> Unit
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
        if (_ui.value.sleepAutomationEnabled) handleSleepEvent(sleepDetector.onHeartRate(smooth))
        if (_ui.value.automationEnabled && !_ui.value.automationPaused && sleepDetector.state != SleepWakeDetector.State.SLEEPING && zone != lastSentZone) queueAutomation(zone)
    }

    private fun queueAutomation(zone: HrZone) {
        automationJob?.cancel()
        automationJob = scope.launch {
            val remaining = (lastCommandAt + MIN_COMMAND_INTERVAL_MS - System.currentTimeMillis()).coerceAtLeast(0)
            delay(remaining)
            if (!_ui.value.automationEnabled || _ui.value.automationPaused || sleepDetector.state == SleepWakeDetector.State.SLEEPING || _ui.value.zone != zone || lastSentZone == zone) return@launch
            val style = _ui.value.lightingTheme.styleFor(zone)
            val brightness = _ui.value.brightnessOverride ?: style.brightness
            var succeeded = false
            val wizLights = selectedLights()
            if (wizLights.isNotEmpty()) wiz.setColor(wizLights, style.color, brightness, style.temperature).fold(
                onSuccess = { succeeded = true },
                onFailure = { setError("Automation command failed: ${it.message}") }
            )
            hueCredentials()?.takeIf { selectedHueLights().isNotEmpty() }?.let { (ip, key) ->
                hue.setColor(ip, key, selectedHueLights(), style.color, brightness, style.temperature).fold(
                    onSuccess = { succeeded = true },
                    onFailure = { setError("Hue automation failed: ${it.message}") }
                )
            }
            if (succeeded) {
                    lastSentZone = zone
                    lastCommandAt = System.currentTimeMillis()
                    _ui.value = _ui.value.copy(lastCommand = "${_ui.value.lightingTheme.displayName}: ${zone.label}, $brightness%", error = null)
            }
        }
    }

    private fun selectedLights() = _ui.value.lights.filter {
        it.selected && it.online && (_ui.value.activeGroup == "All lights" || it.group == _ui.value.activeGroup)
    }
    private fun selectedHueLights() = _ui.value.hueLights.filter { it.selected && it.online }
    private fun hueCredentials() = hueKey?.let { _ui.value.hueBridgeIp to it }
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

    private fun persistGroups() {
        val array = JSONArray()
        _ui.value.groups.filterNot { it == "All lights" }.forEach(array::put)
        preferences.edit().putString("saved_groups", array.toString()).apply()
    }

    private fun loadSavedGroups(lights: List<WizLight>): List<String> = runCatching {
        val saved = JSONArray(preferences.getString("saved_groups", "[]"))
        buildList {
            add("All lights")
            for (index in 0 until saved.length()) saved.optString(index).takeIf { it.isNotBlank() }?.let(::add)
            lights.map { it.group }.filter { it.isNotBlank() && it != "All lights" }.forEach(::add)
        }.distinctBy { it.lowercase() }
    }.getOrDefault(listOf("All lights"))

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
            if (polarSessionActive || _ui.value.automationEnabled || _ui.value.sleepAutomationEnabled) {
                ContextCompat.startForegroundService(context, intent)
            } else {
                context.stopService(intent)
            }
        }.onFailure { setError("Background service failed: ${it.message}") }
    }

    fun shutdown() {
        heartbeatJob?.cancel()
        healthJob?.cancel()
        stopSleepDetection()
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
    fun pairHueBridge(ip: String) = runtime.pairHueBridge(ip)
    fun refreshHueLights() = runtime.refreshHueLights()
    fun setHueLightSelected(id: String, selected: Boolean) = runtime.setHueLightSelected(id, selected)
    fun renameLight(address: String, name: String) = runtime.renameLight(address, name)
    fun assignLightGroup(address: String, group: String) = runtime.assignLightGroup(address, group)
    fun createGroup(name: String) = runtime.createGroup(name)
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
    fun setHeartbeatPulseIntensity(intensity: Int) = runtime.setHeartbeatPulseIntensity(intensity)
    fun setSleepAutomation(enabled: Boolean) = runtime.setSleepAutomation(enabled)
    fun setRestoreLightsOnWake(enabled: Boolean) = runtime.setRestoreLightsOnWake(enabled)
    fun setDemo(enabled: Boolean) = runtime.setDemo(enabled)
}
