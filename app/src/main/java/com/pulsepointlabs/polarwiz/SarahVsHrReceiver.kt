package com.pulsepointlabs.polarwiz

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SarahVsHrReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_HR_SAMPLE) return
        val bpm = intent.getIntExtra(EXTRA_BPM, -1)
        val rr = intent.getIntExtra(EXTRA_RR_MS, -1).takeIf { it > 0 }
        if (bpm in 25..240) {
            (context.applicationContext as PolarWizApplication).runtime.acceptSarahVsHeartRate(bpm, rr)
        }
    }

    companion object {
        const val ACTION_HR_SAMPLE = "com.pulsepointlabs.polarwiz.action.SARAHVS_HR_SAMPLE"
        const val EXTRA_BPM = "bpm"
        const val EXTRA_RR_MS = "rr_ms"
    }
}
