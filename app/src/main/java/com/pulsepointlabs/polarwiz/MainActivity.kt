package com.pulsepointlabs.polarwiz

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.CheckBox
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.pulsepointlabs.polarwiz.databinding.ActivityMainBinding
import com.pulsepointlabs.polarwiz.model.Rgb
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var renderedPolarIds = emptyList<String>()
    private var renderedLights = emptyList<Pair<String, Boolean>>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) viewModel.scanPolar()
        else binding.errorText.text = "Bluetooth Nearby Devices permission was denied"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.scanPolarButton.setOnClickListener { scanWithPermission() }
        binding.disconnectPolarButton.setOnClickListener { viewModel.disconnectPolar() }
        binding.discoverWizButton.setOnClickListener { viewModel.discoverLights() }
        binding.addIpButton.setOnClickListener { viewModel.addLightByIp(binding.manualIpText.text.toString()) }
        binding.demoSwitch.setOnCheckedChangeListener { _, checked -> viewModel.setDemo(checked) }
        binding.automationSwitch.setOnCheckedChangeListener { _, checked -> viewModel.setAutomation(checked) }
        binding.warmButton.setOnClickListener { viewModel.manualColor(null, brightness(), 2700) }
        binding.violetButton.setOnClickListener { viewModel.manualColor(Rgb(135, 50, 255), brightness()) }
        binding.redButton.setOnClickListener { viewModel.manualColor(Rgb(255, 0, 0), brightness()) }
        binding.offButton.setOnClickListener { viewModel.turnOff() }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ui.collect(::render)
            }
        }
    }

    private fun scanWithPermission() {
        val permissions = arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        if (permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
            viewModel.scanPolar()
        } else permissionLauncher.launch(permissions)
    }

    private fun render(state: UiState) = with(binding) {
        polarStatusText.text = state.polarStatus
        bpmText.text = state.bpm?.let { "$it BPM" } ?: "-- BPM"
        smoothedBpmText.text = "Smoothed: ${state.smoothedBpm ?: "--"}   RR: ${state.rrMs?.let { "$it ms" } ?: "--"}"
        wizStatusText.text = state.wizStatus
        zoneText.text = "Zone: ${state.zone?.label ?: "--"}"
        lastCommandText.text = "Last command: ${state.lastCommand}"
        errorText.text = state.error.orEmpty()
        if (demoSwitch.isChecked != state.demoEnabled) demoSwitch.isChecked = state.demoEnabled
        if (automationSwitch.isChecked != state.automationEnabled) automationSwitch.isChecked = state.automationEnabled

        val polarIds = state.polarDevices.map { it.id }
        if (polarIds != renderedPolarIds) {
            renderedPolarIds = polarIds
            polarDeviceList.removeAllViews()
            state.polarDevices.forEach { device ->
                polarDeviceList.addView(TextView(this@MainActivity).apply {
                    text = "${device.name} • ${device.id} • ${device.rssi} dBm\nTap to connect"
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                    textSize = 16f
                    setPadding(12, 16, 12, 16)
                    setOnClickListener { viewModel.connectPolar(device.id) }
                })
            }
        }

        val lightKeys = state.lights.map { (it.address.hostAddress ?: "") to it.selected }
        if (lightKeys != renderedLights) {
            renderedLights = lightKeys
            wizLightList.removeAllViews()
            state.lights.forEach { light ->
                wizLightList.addView(CheckBox(this@MainActivity).apply {
                    text = "${light.name} • ${light.address.hostAddress} • ${if (light.online) "online" else "offline"}"
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                    isChecked = light.selected
                    setOnCheckedChangeListener { _, checked ->
                        viewModel.setLightSelected(light.address.hostAddress ?: return@setOnCheckedChangeListener, checked)
                    }
                })
            }
        }
    }

    private fun brightness() = binding.brightnessSeek.progress.coerceAtLeast(10)
}
