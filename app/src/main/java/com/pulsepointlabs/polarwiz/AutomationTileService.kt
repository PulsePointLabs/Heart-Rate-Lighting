package com.pulsepointlabs.polarwiz

import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

class AutomationTileService : TileService() {
    override fun onStartListening() { super.onStartListening(); refresh() }
    override fun onClick() {
        super.onClick()
        val runtime = (application as PolarWizApplication).runtime
        runtime.setAutomation(!runtime.ui.value.automationEnabled)
        refresh()
    }
    private fun refresh() {
        val state = (application as PolarWizApplication).runtime.ui.value
        qsTile?.apply {
            label = "HR Lights"
            subtitle = if (state.automationEnabled) "Automation on" else "Automation off"
            this.state = if (state.automationEnabled) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
            updateTile()
        }
    }
}
