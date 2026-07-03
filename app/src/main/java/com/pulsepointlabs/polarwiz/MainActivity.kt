package com.pulsepointlabs.polarwiz

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import android.content.Intent
import android.widget.CheckBox
import android.widget.TextView
import android.widget.ArrayAdapter
import android.widget.AdapterView
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ImageButton
import android.widget.SeekBar
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
    private var renderedLights = emptyList<String>()

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
        binding.heartbeatIntensitySeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.heartbeatIntensityLabel.text = "Heartbeat reaction: ${progress.coerceIn(2, 40)}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                viewModel.setHeartbeatPulseIntensity(binding.heartbeatIntensitySeek.progress)
            }
        })
        binding.warmButton.setOnClickListener { viewModel.manualColor(null, brightness(), 2700) }
        binding.violetButton.setOnClickListener { viewModel.manualColor(Rgb(135, 50, 255), brightness()) }
        binding.redButton.setOnClickListener { viewModel.manualColor(Rgb(255, 0, 0), brightness()) }
        binding.offButton.setOnClickListener { viewModel.turnOff() }
        binding.brightnessSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (fromUser) binding.brightnessLabel.text = "Brightness override: ${progress.coerceAtLeast(10)}%"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) {
                viewModel.setAutomationBrightness(binding.brightnessSeek.progress)
            }
        })
        binding.themeBrightnessButton.setOnClickListener { viewModel.clearAutomationBrightness() }
        binding.groupFilterButton.setOnClickListener { showGroupPicker() }
        binding.pauseAutomationButton.setOnClickListener {
            viewModel.setAutomationPaused(!viewModel.ui.value.automationPaused)
        }
        binding.shareDiagnosticsButton.setOnClickListener { shareDiagnostics() }
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
        groupFilterButton.text = "Control group: ${state.activeGroup}"
        pauseAutomationButton.text = if (state.automationPaused) "Resume" else "Pause"
        if (demoSwitch.isChecked != state.demoEnabled) demoSwitch.isChecked = state.demoEnabled
        if (automationSwitch.isChecked != state.automationEnabled) automationSwitch.isChecked = state.automationEnabled
        if (heartbeatPulseSwitch.isChecked != state.heartbeatPulseEnabled) heartbeatPulseSwitch.isChecked = state.heartbeatPulseEnabled
        if (!heartbeatIntensitySeek.isPressed && heartbeatIntensitySeek.progress != state.heartbeatPulseIntensity) {
            heartbeatIntensitySeek.progress = state.heartbeatPulseIntensity
        }
        heartbeatIntensityLabel.text = "Heartbeat reaction: ${state.heartbeatPulseIntensity}%"
        val displayedBrightness = state.brightnessOverride
            ?: state.zone?.let { state.lightingTheme.styleFor(it).brightness }
            ?: 60
        if (!brightnessSeek.isPressed && brightnessSeek.progress != displayedBrightness) brightnessSeek.progress = displayedBrightness
        brightnessLabel.text = state.brightnessOverride?.let { "Brightness override: $it%" }
            ?: "Brightness: theme default ($displayedBrightness%)"
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

        val lightKeys = state.lights.map { "${it.address.hostAddress}|${it.selected}|${it.name}|${it.group}|${it.online}" }
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
                    val groupSuffix = light.group.takeIf { it != "All lights" }?.let { "  •  $it" }.orEmpty()
                    text = "${light.name}\n${light.address.hostAddress}  •  ${if (light.online) "Online" else "Offline"}$groupSuffix"
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
                    setPadding(9, 9, 9, 9)
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(dp(3), 0, dp(3), 0) }
                    setOnClickListener { showRenameLightDialog(address, light.name) }
                })
                row.addView(ImageButton(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_group)
                    background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_icon_button)
                    contentDescription = "Assign ${light.name} to a group"
                    tooltipText = "Assign group"
                    setPadding(9, 9, 9, 9)
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(dp(3), 0, dp(3), 0) }
                    setOnClickListener { showLightGroupPicker(address, light.name, light.group) }
                })
                row.addView(ImageButton(this@MainActivity).apply {
                    setImageResource(R.drawable.ic_identify)
                    background = ContextCompat.getDrawable(this@MainActivity, R.drawable.bg_icon_button_primary)
                    contentDescription = "Flash ${light.name} to identify it"
                    tooltipText = "Identify light"
                    setPadding(9, 9, 9, 9)
                    layoutParams = LinearLayout.LayoutParams(dp(40), dp(40)).apply { setMargins(dp(3), 0, 0, 0) }
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
        val nameInput = EditText(this).apply { hint = "Light name"; setText(currentName); selectAll() }
        AlertDialog.Builder(this)
            .setTitle("Rename WiZ light")
            .setMessage(address)
            .setView(nameInput)
            .setPositiveButton("Save") { _, _ -> viewModel.renameLight(address, nameInput.text.toString()) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showGroupPicker() {
        val state = viewModel.ui.value
        val groups = state.groups.toTypedArray()
        val checked = groups.indexOf(state.activeGroup).coerceAtLeast(0)
        AlertDialog.Builder(this)
            .setTitle("Control group")
            .setSingleChoiceItems(groups, checked) { dialog, which ->
                viewModel.setActiveGroup(groups[which])
                dialog.dismiss()
            }
            .setPositiveButton("New group") { _, _ -> showCreateGroupDialog() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showCreateGroupDialog(assignAddress: String? = null, lightName: String? = null) {
        val input = EditText(this).apply { hint = "Room or group name" }
        AlertDialog.Builder(this)
            .setTitle(if (lightName == null) "Create group" else "New group for $lightName")
            .setView(input)
            .setPositiveButton("Create") { _, _ ->
                val name = input.text.toString().trim()
                if (viewModel.createGroup(name) && assignAddress != null) viewModel.assignLightGroup(assignAddress, name)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showLightGroupPicker(address: String, lightName: String, currentGroup: String) {
        val groups = viewModel.ui.value.groups.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Assign $lightName")
            .setSingleChoiceItems(groups, groups.indexOf(currentGroup).coerceAtLeast(0)) { dialog, which ->
                viewModel.assignLightGroup(address, groups[which])
                dialog.dismiss()
            }
            .setPositiveButton("New group") { _, _ -> showCreateGroupDialog(address, lightName) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun shareDiagnostics() {
        startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "Polar WiZ HR diagnostics")
            putExtra(Intent.EXTRA_TEXT, DiagnosticLog.export())
        }, "Share diagnostics"))
    }
}
