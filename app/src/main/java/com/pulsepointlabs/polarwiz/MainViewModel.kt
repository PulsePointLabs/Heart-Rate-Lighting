package com.pulsepointlabs.polarwiz

import android.app.Application
import android.util.Log
import android.content.Intent
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import com.pulsepointlabs.polarwiz.ble.PolarH10Manager
import com.pulsepointlabs.polarwiz.ble.PolarPrecisionManager
import com.pulsepointlabs.polarwiz.hr.HeartRateProcessor
import com.pulsepointlabs.polarwiz.model.HrZone
import com.pulsepointlabs.polarwiz.model.PolarDevice
import com.pulsepointlabs.polarwiz.model.Rgb
import com.pulsepointlabs.polarwiz.model.WizLight
import com.pulsepointlabs.polarwiz.model.HueLight
import com.pulsepointlabs.polarwiz.model.PulseShape
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
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetSocketAddress
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
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
    val lowLatencyMode: Boolean = true,
    val precisionMode: Boolean = false,
    val precisionStatus: String = "Standard RR timing",
    val pulseShape: PulseShape = PulseShape.SINGLE,
    val wizTimingOffsetMs: Int = 0,
    val hueTimingOffsetMs: Int = 0,
    val chestMotion: Float = 0f,
    val rPeakCount: Long = 0,
    val signalStatus: String = "Waiting for heart signal",
    val circadianEnabled: Boolean = false,
    val sleepAutomationEnabled: Boolean = false,
    val restoreLightsOnWake: Boolean = true,
    val sleepStatus: String = "Awake",
    val sleepHistory: List<String> = emptyList(),
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
    private val precision = PolarPrecisionManager(application, scope)
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
            lowLatencyMode = preferences.getBoolean("low_latency_mode", true),
            precisionMode = false,
            pulseShape = runCatching { PulseShape.valueOf(preferences.getString("pulse_shape", PulseShape.SINGLE.name)!!) }.getOrDefault(PulseShape.SINGLE),
            wizTimingOffsetMs = preferences.getInt("wiz_timing_offset", 0).coerceIn(0, 300),
            hueTimingOffsetMs = preferences.getInt("hue_timing_offset", 0).coerceIn(0, 300),
            circadianEnabled = preferences.getBoolean("circadian_enabled", false),
            sleepAutomationEnabled = preferences.getBoolean("sleep_automation_enabled", false),
            restoreLightsOnWake = preferences.getBoolean("restore_lights_on_wake", true),
            sleepHistory = loadSleepHistory(),
            groups = restoredGroups,
            activeGroup = preferences.getString("active_group", "All lights")
                ?.takeIf { it in restoredGroups } ?: "All lights",
            error = preferences.getString("last_fatal_crash", null)?.let { "Previous crash captured — share Diagnostics" }
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private var demoJob: Job? = null
    private var automationJob: Job? = null
    private var heartbeatJob: Job? = null
    private var wizPulseJob: Job? = null
    private var huePulseJob: Job? = null
    private var healthJob: Job? = null
    private var sleepEvaluationJob: Job? = null
    private var watchdogJob: Job? = null
    private var circadianJob: Job? = null
    private var lastSentZone: HrZone? = null
    private var lastCommandAt = 0L
    private var polarSessionActive = false
    @Volatile private var lastSarahVsSampleAt = 0L
    @Volatile private var lastSarahVsRPeakAt = 0L
    @Volatile private var lastHeartDataAt = 0L
    @Volatile private var lastRPeakAt = 0L
    @Volatile private var previousRPeakAt = 0L
    @Volatile private var precisionConnectStartedAt = 0L
    private var precisionFallbackAttempted = false
    private var previousLightStates: Map<String, JSONObject> = emptyMap()
    private var preSleepLightStates: Map<String, JSONObject> = emptyMap()
    private var preSleepHueStates: Map<String, JSONObject> = emptyMap()
    private val hue = HueBridgeManager()
    private val hueKey get() = preferences.getString("hue_key", null)
    private val sleepDetector = SleepWakeDetector()
    private val sleepMonitor = SleepWakeMonitor(application, ::acceptMotion, ::acceptSignificantMotion)
    private val sleepHistoryFormat = SimpleDateFormat("MMM d, HH:mm:ss", Locale.US)

    init {
        startSarahVsUdpFeed()
        scope.launch { polar.devices.collect { _ui.value = _ui.value.copy(polarDevices = it) } }
        scope.launch { polar.connectionState.collect { _ui.value = _ui.value.copy(polarStatus = it) } }
        scope.launch { polar.errors.collect { setError(it) } }
        scope.launch { polar.readings.collect { (bpm, rr) ->
            if (!_ui.value.demoEnabled && System.currentTimeMillis() - lastSarahVsSampleAt > SARAHVS_FEED_TIMEOUT_MS) acceptBpm(bpm, rr)
        } }
        scope.launch { precision.status.collect { _ui.value = _ui.value.copy(polarStatus = it, precisionStatus = it) } }
        scope.launch { precision.errors.collect { error ->
            DiagnosticLog.add(TAG, "Precision stream: $error")
            if (_ui.value.precisionMode) {
                preferences.edit().putBoolean("precision_mode", false).apply()
                _ui.value = _ui.value.copy(precisionMode = false, precisionStatus = "ECG safely disabled: $error", error = "ECG unavailable; standard BLE restored")
                precision.disconnect()
                preferences.getString("last_h10_address", null)?.let { address -> scope.launch { delay(750); polar.connect(address) } }
            }
        } }
        scope.launch { precision.readings.collect { (bpm, rr) -> if (_ui.value.precisionMode && !_ui.value.demoEnabled) acceptBpm(bpm, rr) } }
        scope.launch { precision.chestMotion.collect { motion ->
            if (_ui.value.precisionMode) { _ui.value = _ui.value.copy(chestMotion = motion); acceptMotion(motion) }
        } }
        scope.launch { precision.rPeaks.collect {
            if (_ui.value.precisionMode) {
                val now = System.currentTimeMillis()
                val rr = (now - previousRPeakAt).toInt().takeIf { previousRPeakAt > 0 && it in 300..2_000 }
                previousRPeakAt = now
                lastRPeakAt = now
                rr?.let { acceptBpm((60_000 / it).coerceIn(30, 220), it) }
                _ui.value = _ui.value.copy(rPeakCount = _ui.value.rPeakCount + 1, precisionStatus = "ECG R-wave live")
                fireHeartbeatPulse(_ui.value, _ui.value.pulseShape.durationMs)
            }
        } }
        if (_ui.value.automationEnabled) updateBackgroundService()
        if (_ui.value.heartbeatPulseEnabled) setHeartbeatPulse(true)
        if (_ui.value.sleepAutomationEnabled) {
            startSleepDetection()
            updateBackgroundService()
        }
        if (preferences.getString("last_h10_address", null) != null) {
            _ui.value = _ui.value.copy(polarStatus = "Waiting for SarahVS feed — tap Connect only for direct fallback")
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
                    _ui.value = _ui.value.copy(polarStatus = "SarahVS feed stopped — tap Connect for direct H10 fallback")
                }
            }
        }
        watchdogJob = scope.launch {
            while (true) {
                delay(2_000)
                val age = System.currentTimeMillis() - lastHeartDataAt
                val signal = when {
                    lastHeartDataAt == 0L -> "Waiting for heart signal"
                    age < 3_000 -> if (_ui.value.precisionMode && System.currentTimeMillis() - lastRPeakAt < 2_000) "ECG R-wave signal healthy" else "HR/RR signal healthy"
                    age < 8_000 -> "Heart signal delayed (${age / 1000}s)"
                    else -> "Heart signal lost — lighting held safely"
                }
                _ui.value = _ui.value.copy(signalStatus = signal)
                if (_ui.value.precisionMode && !precisionFallbackAttempted && precisionConnectStartedAt > 0 &&
                    System.currentTimeMillis() - precisionConnectStartedAt > 12_000 && lastHeartDataAt < precisionConnectStartedAt
                ) {
                    precisionFallbackAttempted = true
                    preferences.edit().putBoolean("precision_mode", false).apply()
                    _ui.value = _ui.value.copy(precisionMode = false, precisionStatus = "ECG unavailable — standard BLE fallback")
                    preferences.getString("last_h10_address", null)?.let(::connectPolar)
                }
            }
        }
        startCircadianLoop()
    }

    fun scanPolar() = polar.scan()
    fun connectPolar(id: String) {
        preferences.edit().putString("last_h10_address", id).apply()
        polarSessionActive = true
        updateBackgroundService()
        if (_ui.value.precisionMode) {
            precisionConnectStartedAt = System.currentTimeMillis(); precisionFallbackAttempted = false
            polar.disconnect(); scope.launch { delay(3_000); if (_ui.value.precisionMode) precision.connect(id) }
        }
        else { precision.disconnect(); polar.connect(id) }
    }
    fun disconnectPolar() {
        polarSessionActive = false
        polar.disconnect()
        precision.disconnect()
        updateBackgroundService()
    }

    fun setPrecisionMode(enabled: Boolean) {
        preferences.edit().putBoolean("precision_mode", false).apply()
        _ui.value = _ui.value.copy(precisionMode = enabled, precisionStatus = if (enabled) "ECG mode reconnecting…" else "Standard RR timing")
        if (enabled) { previousRPeakAt = 0L; preferences.edit().remove("last_fatal_crash").apply() }
        preferences.getString("last_h10_address", null)?.let(::connectPolar)
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

    fun testHueLights() {
        val credentials = hueCredentials() ?: return setError("Pair the Hue Bridge first")
        val lights = selectedHueLights()
        if (lights.isEmpty()) return setError("Select at least one online Hue light")
        scope.launch {
            _ui.value = _ui.value.copy(hueStatus = "Sending test command…", error = null)
            hue.setColor(credentials.first, credentials.second, lights, null, 60, 3000).fold(
                onSuccess = { _ui.value = _ui.value.copy(hueStatus = "Test accepted by ${lights.size} Hue light(s)", lastCommand = "Hue test: warm white 60%", error = null) },
                onFailure = { setError("Hue test failed: ${it.message}"); _ui.value = _ui.value.copy(hueStatus = "Hue command rejected") }
            )
        }
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
        updateLowLatencyMode()
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
        wizPulseJob?.cancel()
        huePulseJob?.cancel()
        _ui.value = _ui.value.copy(heartbeatPulseEnabled = enabled)
        preferences.edit().putBoolean("heartbeat_pulse_enabled", enabled).apply()
        updateLowLatencyMode()
        if (enabled) {
            heartbeatJob = scope.launch {
                var nextBeatAt = SystemClock.elapsedRealtime()
                var smoothedIntervalMs: Double? = null
                while (true) {
                    val state = _ui.value
                    val bpm = state.smoothedBpm
                    val ecgDriving = (state.precisionMode && System.currentTimeMillis() - lastRPeakAt < 2_000) ||
                        System.currentTimeMillis() - lastSarahVsRPeakAt < 2_000
                    if (!ecgDriving && System.currentTimeMillis() - lastHeartDataAt < 8_000 && state.automationEnabled && !state.automationPaused && sleepDetector.state != SleepWakeDetector.State.SLEEPING && bpm != null && (selectedLights().isNotEmpty() || selectedHueLights().isNotEmpty())) {
                        val rawInterval = (state.rrMs?.toLong()?.coerceIn(300L, 2_000L)
                            ?: (60_000L / bpm.coerceIn(40, 200))).coerceAtLeast(300L).toDouble()
                        smoothedIntervalMs = smoothedIntervalMs?.let { previous -> previous * 0.72 + rawInterval * 0.28 } ?: rawInterval
                        val intervalMs = smoothedIntervalMs.toLong().coerceIn(300L, 2_000L)
                        val now = SystemClock.elapsedRealtime()
                        if (nextBeatAt < now - intervalMs || nextBeatAt > now + intervalMs * 2) nextBeatAt = now
                        if (nextBeatAt > now) delay(nextBeatAt - now)
                        nextBeatAt += intervalMs
                        fireHeartbeatPulse(state, state.pulseShape.durationMs)
                    } else {
                        nextBeatAt = SystemClock.elapsedRealtime()
                        smoothedIntervalMs = null
                        delay(500)
                    }
                }
            }
        }
    }

    private fun fireHeartbeatPulse(state: UiState, durationMs: Int) {
        if (!state.heartbeatPulseEnabled || !state.automationEnabled || state.automationPaused || sleepDetector.state == SleepWakeDetector.State.SLEEPING) return
        val zone = state.zone
        val baseStyle = zone?.let { state.lightingTheme.styleFor(it) }
        val baseBrightness = state.brightnessOverride ?: baseStyle?.brightness ?: 60
        val heartbeatColor = zone?.let { state.lightingTheme.heartbeatColor(it) }
        val wizLights = selectedLights()
        if (wizLights.isNotEmpty() && wizPulseJob?.isActive != true) wizPulseJob = scope.launch {
            delay(state.wizTimingOffsetMs.toLong())
            suspend fun beat() = if (heartbeatColor != null) wiz.colorPulse(wizLights, heartbeatColor, baseBrightness, state.heartbeatPulseIntensity, durationMs, baseStyle?.temperature ?: 5000)
                else wiz.pulse(wizLights, -state.heartbeatPulseIntensity, durationMs)
            beat().onFailure { Log.w(TAG, "WiZ heartbeat pulse skipped: ${it.message}") }
            if (state.pulseShape == PulseShape.LUB_DUB) { delay(110); beat() }
        }
        val hueLights = selectedHueLights()
        hueCredentials()?.takeIf { hueLights.isNotEmpty() && huePulseJob?.isActive != true }?.let { (ip, key) ->
            huePulseJob = scope.launch {
                delay(state.hueTimingOffsetMs.toLong())
                suspend fun beat() = if (heartbeatColor != null) hue.colorPulse(ip, key, hueLights, heartbeatColor, baseBrightness, state.heartbeatPulseIntensity, durationMs, baseStyle?.temperature ?: 5000)
                    else hue.pulse(ip, key, hueLights, baseBrightness, state.heartbeatPulseIntensity, durationMs)
                beat().onFailure { DiagnosticLog.add(TAG, "Hue pulse failed: ${it.message}") }
                if (state.pulseShape == PulseShape.LUB_DUB) { delay(110); beat() }
            }
        }
    }

    fun setPulseShape(shape: PulseShape) { preferences.edit().putString("pulse_shape", shape.name).apply(); _ui.value = _ui.value.copy(pulseShape = shape) }
    fun setTimingOffsets(wizMs: Int? = null, hueMs: Int? = null) {
        val wizValue = wizMs?.coerceIn(0, 300) ?: _ui.value.wizTimingOffsetMs
        val hueValue = hueMs?.coerceIn(0, 300) ?: _ui.value.hueTimingOffsetMs
        preferences.edit().putInt("wiz_timing_offset", wizValue).putInt("hue_timing_offset", hueValue).apply()
        _ui.value = _ui.value.copy(wizTimingOffsetMs = wizValue, hueTimingOffsetMs = hueValue)
    }

    fun autoCalibrateTiming() {
        val wizLight = selectedLights().firstOrNull()
        val hueLight = selectedHueLights().firstOrNull()
        if (wizLight == null || hueLight == null) return setError("Select at least one online WiZ and Hue light for calibration")
        val credentials = hueCredentials() ?: return setError("Pair the Hue Bridge first")
        scope.launch {
            _ui.value = _ui.value.copy(lastCommand = "Measuring WiZ and Hue latency…", error = null)
            val wizRtt = wiz.measureLatency(wizLight)
            val hueRtt = hue.measureLatency(credentials.first, credentials.second, hueLight)
            val wizDelay = ((hueRtt - wizRtt) / 2).coerceIn(0, 300).toInt()
            val hueDelay = ((wizRtt - hueRtt) / 2).coerceIn(0, 300).toInt()
            setTimingOffsets(wizDelay, hueDelay)
            _ui.value = _ui.value.copy(lastCommand = "Calibrated: WiZ ${wizRtt}ms RTT, Hue ${hueRtt}ms RTT")
            DiagnosticLog.add(TAG, "Auto calibration WiZ=$wizRtt ms Hue=$hueRtt ms offsets=$wizDelay/$hueDelay")
        }
    }

    fun setHeartbeatPulseIntensity(intensity: Int) {
        val value = intensity.coerceIn(2, 40)
        _ui.value = _ui.value.copy(heartbeatPulseIntensity = value)
        preferences.edit().putInt("heartbeat_pulse_intensity", value).apply()
    }

    fun setLowLatencyMode(enabled: Boolean) {
        preferences.edit().putBoolean("low_latency_mode", enabled).apply()
        _ui.value = _ui.value.copy(lowLatencyMode = enabled)
        updateLowLatencyMode()
    }

    private fun updateLowLatencyMode() {
        val active = _ui.value.lowLatencyMode && _ui.value.heartbeatPulseEnabled && _ui.value.automationEnabled
        wiz.setLowLatency(active)
        polar.setLowLatency(active)
    }

    fun setSleepAutomation(enabled: Boolean) {
        val wasSleeping = sleepDetector.state == SleepWakeDetector.State.SLEEPING
        preferences.edit().putBoolean("sleep_automation_enabled", enabled).apply()
        _ui.value = _ui.value.copy(
            sleepAutomationEnabled = enabled,
            sleepStatus = if (enabled) "Monitoring motion and heart rate" else "Awake"
        )
        if (enabled) {
            addSleepHistory("Sleep/wake automation enabled")
            startSleepDetection()
        } else {
            addSleepHistory("Sleep/wake automation disabled")
            if (wasSleeping) handleSleepEvent(sleepDetector.onSignificantMotion())
            stopSleepDetection(clearSnapshot = !wasSleeping)
        }
        updateBackgroundService()
    }

    fun setRestoreLightsOnWake(enabled: Boolean) {
        preferences.edit().putBoolean("restore_lights_on_wake", enabled).apply()
        _ui.value = _ui.value.copy(restoreLightsOnWake = enabled)
    }

    fun setCircadianEnabled(enabled: Boolean) {
        preferences.edit().putBoolean("circadian_enabled", enabled).apply()
        _ui.value = _ui.value.copy(circadianEnabled = enabled)
        if (enabled) applyCircadianNow()
    }

    private fun startCircadianLoop() {
        circadianJob?.cancel()
        circadianJob = scope.launch { while (true) { if (_ui.value.circadianEnabled) applyCircadianNow(); delay(5 * 60_000L) } }
    }

    private fun applyCircadianNow() {
        if (_ui.value.automationEnabled || sleepDetector.state == SleepWakeDetector.State.SLEEPING) return
        val calendar = Calendar.getInstance()
        val hour = calendar.get(Calendar.HOUR_OF_DAY)
        val minute = calendar.get(Calendar.MINUTE)
        val (temperature, brightness) = when {
            hour >= 22 || hour < 6 -> 2400 to 25
            hour == 6 -> 3200 to (30 + minute * 40 / 59)
            hour < 9 -> 5000 to 80
            hour < 17 -> 5500 to 90
            hour < 20 -> 4000 to 70
            else -> 3000 to 45
        }
        scope.launch {
            selectedLights().takeIf { it.isNotEmpty() }?.let { wiz.setColor(it, null, brightness, temperature) }
            hueCredentials()?.let { (ip, key) -> hue.setColor(ip, key, selectedHueLights(), null, brightness, temperature) }
            _ui.value = _ui.value.copy(lastCommand = "Circadian: ${temperature}K at $brightness%")
        }
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
                addSleepHistory("Sleep detected — turning selected lights off")
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
                addSleepHistory(if (state.restoreLightsOnWake) "Wake detected — restoring prior light state" else "Wake detected — turning selected lights on")
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

    private fun addSleepHistory(message: String) {
        val stamped = "${sleepHistoryFormat.format(Date())} — $message"
        val updated = (listOf(stamped) + _ui.value.sleepHistory).take(SLEEP_HISTORY_LIMIT)
        preferences.edit().putString("sleep_history", JSONArray(updated).toString()).apply()
        _ui.value = _ui.value.copy(sleepHistory = updated)
        DiagnosticLog.add(TAG, stamped)
    }

    private fun loadSleepHistory(): List<String> {
        val raw = preferences.getString("sleep_history", null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) add(array.optString(index))
            }.filter { it.isNotBlank() }.take(SLEEP_HISTORY_LIMIT)
        }.getOrDefault(emptyList())
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

    private fun startSarahVsUdpFeed() {
        scope.launch(Dispatchers.IO) {
            runCatching {
                DatagramSocket(null).use { socket ->
                    socket.reuseAddress = true
                    socket.bind(InetSocketAddress(InetAddress.getLoopbackAddress(), SARAHVS_UDP_PORT))
                    DiagnosticLog.add(TAG, "SarahVS localhost HR receiver listening")
                    val buffer = ByteArray(128)
                    while (true) {
                        val packet = DatagramPacket(buffer, buffer.size)
                        socket.receive(packet)
                        val message = packet.data.decodeToString(packet.offset, packet.offset + packet.length)
                        val fields = message.split('|')
                        if (fields.firstOrNull() != "PPHR1") continue
                        when (fields.getOrNull(1)) {
                            "HR" -> {
                                val bpm = fields.getOrNull(2)?.toIntOrNull()
                                val rr = fields.getOrNull(3)?.toIntOrNull()?.takeIf { it > 0 }
                                if (bpm != null && bpm in 25..240) acceptSarahVsHeartRate(bpm, rr)
                            }
                            "BEAT" -> acceptSarahVsRPeak()
                        }
                    }
                }
            }.onFailure { DiagnosticLog.add(TAG, "SarahVS localhost HR receiver failed: ${it.message}") }
        }
    }

    fun acceptSarahVsRPeak() {
        if (_ui.value.demoEnabled) return
        val now = System.currentTimeMillis()
        lastSarahVsSampleAt = now
        lastSarahVsRPeakAt = now
        lastRPeakAt = now
        _ui.value = _ui.value.copy(
            rPeakCount = _ui.value.rPeakCount + 1,
            polarStatus = "Live ECG/HR shared by SarahVS",
            precisionStatus = "SarahVS R-wave live"
        )
        fireHeartbeatPulse(_ui.value, _ui.value.pulseShape.durationMs)
    }

    private fun acceptBpm(bpm: Int, rr: Int?) {
        lastHeartDataAt = System.currentTimeMillis()
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
        wizPulseJob?.cancel()
        huePulseJob?.cancel()
        healthJob?.cancel()
        watchdogJob?.cancel()
        circadianJob?.cancel()
        stopSleepDetection()
        polar.close()
        precision.shutdown()
        wiz.close()
        application.stopService(Intent(application, AutomationKeepAliveService::class.java))
        scope.cancel()
    }

    companion object {
        private const val TAG = "PolarWizVM"
        private const val MIN_COMMAND_INTERVAL_MS = 3_000L
        private const val SARAHVS_FEED_TIMEOUT_MS = 6_000L
        private const val SARAHVS_UDP_PORT = 48_511
        private const val SLEEP_HISTORY_LIMIT = 20
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
    fun testHueLights() = runtime.testHueLights()
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
    fun setLowLatencyMode(enabled: Boolean) = runtime.setLowLatencyMode(enabled)
    fun setPrecisionMode(enabled: Boolean) = runtime.setPrecisionMode(enabled)
    fun setPulseShape(shape: PulseShape) = runtime.setPulseShape(shape)
    fun setTimingOffsets(wizMs: Int? = null, hueMs: Int? = null) = runtime.setTimingOffsets(wizMs, hueMs)
    fun autoCalibrateTiming() = runtime.autoCalibrateTiming()
    fun setCircadianEnabled(enabled: Boolean) = runtime.setCircadianEnabled(enabled)
    fun setSleepAutomation(enabled: Boolean) = runtime.setSleepAutomation(enabled)
    fun setRestoreLightsOnWake(enabled: Boolean) = runtime.setRestoreLightsOnWake(enabled)
    fun setDemo(enabled: Boolean) = runtime.setDemo(enabled)
}
