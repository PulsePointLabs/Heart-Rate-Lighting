package com.pulsepointlabs.polarwiz

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class SarahVsHrReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_HR_SAMPLE) return
        val bpm = intent.getIntExtra(EXTRA_BPM, -1)
        val rr = intent.getIntExtra(EXTRA_RR_MS, -1).takeIf { it > 0 }
        val rPeak = intent.getBooleanExtra(EXTRA_R_PEAK, false)
        val runtime = (context.applicationContext as PolarWizApplication).runtime
        if (bpm in 25..240) {
            runtime.acceptSarahVsHeartRate(bpm, rr)
        }
        if (rPeak) runtime.acceptSarahVsRPeak()
    }

    companion object {
        const val ACTION_HR_SAMPLE = "com.pulsepointlabs.polarwiz.action.SARAHVS_HR_SAMPLE"
        const val EXTRA_BPM = "bpm"
        const val EXTRA_RR_MS = "rr_ms"
        const val EXTRA_R_PEAK = "r_peak"
    }
}
