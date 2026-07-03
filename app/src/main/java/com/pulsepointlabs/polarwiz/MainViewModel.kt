package com.pulsepointlabs.polarwiz

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pulsepointlabs.polarwiz.ble.PolarH10Manager
import com.pulsepointlabs.polarwiz.hr.HeartRateProcessor
import com.pulsepointlabs.polarwiz.model.HrZone
import com.pulsepointlabs.polarwiz.model.PolarDevice
import com.pulsepointlabs.polarwiz.model.Rgb
import com.pulsepointlabs.polarwiz.model.WizLight
import com.pulsepointlabs.polarwiz.wiz.WizLanManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.sin
import java.net.InetAddress

data class UiState(
    val polarStatus: String = "Disconnected",
    val polarDevices: List<PolarDevice> = emptyList(),
    val bpm: Int? = null,
    val smoothedBpm: Int? = null,
    val rrMs: Int? = null,
    val wizStatus: String = "No lights discovered",
    val lights: List<WizLight> = emptyList(),
    val automationEnabled: Boolean = false,
    val demoEnabled: Boolean = false,
    val zone: HrZone? = null,
    val lastCommand: String = "none",
    val error: String? = null
)

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private val polar = PolarH10Manager(application, viewModelScope)
    private val wiz = WizLanManager(application)
    private val processor = HeartRateProcessor()
    private val _ui = MutableStateFlow(UiState())
    val ui: StateFlow<UiState> = _ui.asStateFlow()
    private var demoJob: Job? = null
    private var automationJob: Job? = null
    private var lastSentZone: HrZone? = null
    private var lastCommandAt = 0L

    init {
        viewModelScope.launch { polar.devices.collect { _ui.value = _ui.value.copy(polarDevices = it) } }
        viewModelScope.launch { polar.connectionState.collect { _ui.value = _ui.value.copy(polarStatus = it) } }
        viewModelScope.launch { polar.errors.collect { setError(it) } }
        viewModelScope.launch { polar.readings.collect { (bpm, rr) -> if (!_ui.value.demoEnabled) acceptBpm(bpm, rr) } }
    }

    fun scanPolar() = polar.scan()
    fun connectPolar(id: String) = polar.connect(id)
    fun disconnectPolar() = polar.disconnect()

    fun discoverLights() {
        viewModelScope.launch {
            _ui.value = _ui.value.copy(wizStatus = "Discovering on local Wi-Fi…", error = null)
            runCatching { wiz.discover() }
                .onSuccess { found ->
                    _ui.value = _ui.value.copy(
                        lights = found,
                        wizStatus = if (found.isEmpty()) "No WiZ lights replied" else "${found.size} light(s) online"
                    )
                }
                .onFailure { setError("WiZ discovery failed: ${it.message}") }
        }
    }

    fun setLightSelected(address: String, selected: Boolean) {
        _ui.value = _ui.value.copy(lights = _ui.value.lights.map {
            if (it.address.hostAddress == address) it.copy(selected = selected) else it
        })
    }

    fun addLightByIp(rawIp: String) {
        viewModelScope.launch {
            runCatching {
                val ip = rawIp.trim()
                require(IPV4.matches(ip)) { "Enter a numeric IPv4 address" }
                val address = with(kotlinx.coroutines.Dispatchers.IO) { InetAddress.getByName(ip) }
                val light = WizLight(address = address, name = "Manual WiZ light")
                _ui.value = _ui.value.copy(
                    lights = (_ui.value.lights.filterNot { it.address.hostAddress == ip } + light),
                    wizStatus = "Manual light added: $ip",
                    error = null
                )
            }.onFailure { setError("Could not add WiZ IP: ${it.message}") }
        }
    }

    fun manualColor(color: Rgb?, brightness: Int, temperature: Int? = null) {
        viewModelScope.launch {
            val selected = selectedLights()
            wiz.setColor(selected, color, brightness, temperature).fold(
                onSuccess = { _ui.value = _ui.value.copy(lastCommand = "Manual color at $brightness%", error = null) },
                onFailure = { setError("WiZ command failed: ${it.message}") }
            )
        }
    }

    fun turnOff() {
        viewModelScope.launch {
            wiz.turnOff(selectedLights()).fold(
                onSuccess = { _ui.value = _ui.value.copy(lastCommand = "Lights off", error = null) },
                onFailure = { setError("WiZ command failed: ${it.message}") }
            )
        }
    }

    fun setAutomation(enabled: Boolean) {
        lastSentZone = null
        _ui.value = _ui.value.copy(automationEnabled = enabled)
        if (enabled) _ui.value.zone?.let(::queueAutomation)
    }

    fun setDemo(enabled: Boolean) {
        demoJob?.cancel()
        processor.reset()
        _ui.value = _ui.value.copy(demoEnabled = enabled, bpm = null, smoothedBpm = null, rrMs = null)
        if (enabled) {
            demoJob = viewModelScope.launch {
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

    private fun acceptBpm(bpm: Int, rr: Int?) {
        val smooth = processor.add(bpm)
        val zone = processor.zoneFor(smooth)
        _ui.value = _ui.value.copy(bpm = bpm, smoothedBpm = smooth, rrMs = rr, zone = zone)
        if (_ui.value.automationEnabled && zone != lastSentZone) queueAutomation(zone)
    }

    private fun queueAutomation(zone: HrZone) {
        automationJob?.cancel()
        automationJob = viewModelScope.launch {
            val remaining = (lastCommandAt + MIN_COMMAND_INTERVAL_MS - System.currentTimeMillis()).coerceAtLeast(0)
            delay(remaining)
            if (!_ui.value.automationEnabled || _ui.value.zone != zone || lastSentZone == zone) return@launch
            wiz.setColor(selectedLights(), zone.color, zone.brightness, zone.temperature).fold(
                onSuccess = {
                    lastSentZone = zone
                    lastCommandAt = System.currentTimeMillis()
                    _ui.value = _ui.value.copy(lastCommand = "${zone.label}, ${zone.brightness}%", error = null)
                },
                onFailure = { setError("Automation command failed: ${it.message}") }
            )
        }
    }

    private fun selectedLights() = _ui.value.lights.filter { it.selected && it.online }
    private fun setError(message: String) { Log.e(TAG, message); _ui.value = _ui.value.copy(error = message) }

    override fun onCleared() { polar.close(); super.onCleared() }

    companion object {
        private const val TAG = "PolarWizVM"
        private const val MIN_COMMAND_INTERVAL_MS = 3_000L
        private val IPV4 = Regex("^(?:[0-9]{1,3}\\.){3}[0-9]{1,3}$")
    }
}
