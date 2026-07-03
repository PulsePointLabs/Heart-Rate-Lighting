package com.pulsepointlabs.polarwiz

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.widget.CheckBox
import android.widget.TextView
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageButton
import android.content.res.ColorStateList
import androidx.appcompat.app.AlertDialog
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.pulsepointlabs.polarwiz.databinding.ActivityMainBinding
import com.pulsepointlabs.polarwiz.model.Rgb
import com.pulsepointlabs.polarwiz.model.LightingTheme
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private var renderedPolarIds = emptyList<String>()
    private var renderedLights = emptyList<Triple<String, Boolean, String>>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        if (grants.values.all { it }) viewModel.scanPolar()
        else binding.errorText.text = "Bluetooth Nearby Devices permission was denied"
    }
    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* The foreground service still runs if notification permission is declined. */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.scanPolarButton.setOnClickListener { scanWithPermission() }
        binding.disconnectPolarButton.setOnClickListener { viewModel.disconnectPolar() }
        binding.discoverWizButton.setOnClickListener { viewModel.discoverLights() }
        binding.addIpButton.setOnClickListener { viewModel.addLightByIp(binding.manualIpText.text.toString()) }
        binding.demoSwitch.setOnCheckedChangeListener { _, checked -> viewModel.setDemo(checked) }
        binding.automationSwitch.setOnCheckedChangeListener { _, checked ->
            if (checked) requestNotificationPermissionIfNeeded()
            viewModel.setAutomation(checked)
        }
        binding.heartbeatPulseSwitch.setOnCheckedChangeListener { _, checked -> viewModel.setHeartbeatPulse(checked) }
        binding.warmButton.setOnClickListener { viewModel.manualColor(null, brightness(), 2700) }
        binding.violetButton.setOnClickListener { viewModel.manualColor(Rgb(135, 50, 255), brightness()) }
        binding.redButton.setOnClickListener { viewModel.manualColor(Rgb(255, 0, 0), brightness()) }
        binding.offButton.setOnClickListener { viewModel.turnOff() }
        binding.themeSpinner.adapter = ArrayAdapter(
            this,
            R.layout.spinner_theme_item,
            LightingTheme.entries.map { it.displayName }
        ).apply { setDropDownViewResource(R.layout.spinner_theme_dropdown_item) }
        binding.themeSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: android.view.View?, position: Int, id: Long) {
                viewModel.setLightingTheme(LightingTheme.entries[position])
            }
            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }

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
        if (heartbeatPulseSwitch.isChecked != state.heartbeatPulseEnabled) heartbeatPulseSwitch.isChecked = state.heartbeatPulseEnabled
        val themePosition = LightingTheme.entries.indexOf(state.lightingTheme)
        if (themeSpinner.selectedItemPosition != themePosition) themeSpinner.setSelection(themePosition)

        val polarIds = state.polarDevices.map { it.id }
        if (polarIds != renderedPolarIds) {
            renderedPolarIds = polarIds
            polarDeviceList.removeAllViews()
            state.polarDevices.forEach { device ->
                polarDeviceList.addView(TextView(this@MainActivity).apply {
                    text = "${device.name} • ${device.id} • ${device.rssi} dBm\nTap to connect"
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                    textSize = 16f
                    background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_list_item)
                    setPadding(14, 14, 14, 14)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 6, 0, 6) }
                    setOnClickListener {
                        requestNotificationPermissionIfNeeded()
                        viewModel.connectPolar(device.id)
                    }
                })
            }
        }

        val lightKeys = state.lights.map { Triple(it.address.hostAddress ?: "", it.selected, it.name) }
        if (lightKeys != renderedLights) {
            renderedLights = lightKeys
            wizLightList.removeAllViews()
            state.lights.forEach { light ->
                val address = light.address.hostAddress.orEmpty()
                val row = LinearLayout(this@MainActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = android.view.Gravity.CENTER_VERTICAL
                    background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_list_item)
                    setPadding(8, 6, 8, 6)
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { setMargins(0, 6, 0, 6) }
                }
                row.addView(CheckBox(this@MainActivity).apply {
                    text = "${light.name}\n${light.address.hostAddress}  •  ${if (light.online) "Online" else "Offline"}"
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.text_primary))
                    buttonTintList = ColorStateList.valueOf(ContextCompat.getColor(this@MainActivity, R.color.accent))
                    textSize = 15f
                    maxLines = 2
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    isChecked = light.selected
                    setOnCheckedChangeListener { _, checked ->
                        viewModel.setLightSelected(address, checked)
                    }
                    setOnLongClickListener {
                        showRenameLightDialog(address, light.name)
                        true
                    }
                })
                row.addView(ImageButton(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_edit)
                    background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_icon_button)
                    contentDescription = "Rename ${light.name}"
                    tooltipText = "Rename light"
                    setPadding(11, 11, 11, 11)
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(dp(4), 0, dp(4), 0) }
                    setOnClickListener { showRenameLightDialog(address, light.name) }
                })
                row.addView(ImageButton(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_identify)
                    background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_icon_button_primary)
                    contentDescription = "Flash ${light.name} to identify it"
                    tooltipText = "Identify light"
                    setPadding(11, 11, 11, 11)
                    layoutParams = LinearLayout.LayoutParams(dp(44), dp(44)).apply { setMargins(dp(4), 0, 0, 0) }
                    setOnClickListener { viewModel.identifyLight(address) }
                })
                wizLightList.addView(row)
            }
        }
    }

    private fun brightness() = binding.brightnessSeek.progress.coerceAtLeast(10)
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    private fun showRenameLightDialog(address: String, currentName: String) {
        val input = EditText(this).apply { setText(currentName); selectAll() }
        AlertDialog.Builder(this)
            .setTitle("Name this WiZ light")
            .setMessage(address)
            .setView(input)
            .setPositiveButton("Save") { _, _ -> viewModel.renameLight(address, input.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
