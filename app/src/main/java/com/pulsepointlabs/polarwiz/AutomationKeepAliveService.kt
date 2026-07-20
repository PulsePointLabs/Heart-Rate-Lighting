package com.pulsepointlabs.polarwiz

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat

/** Keeps the BLE/Wi-Fi automation process alive while the app is backgrounded or the screen is locked. */
class AutomationKeepAliveService : Service() {
    override fun onCreate() {
        super.onCreate()
        (application as PolarWizApplication).runtime
        DiagnosticLog.add("KeepAlive", "Foreground service created")
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "SarahVS Glow background", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps SarahVS Glow lighting automation active in the background"
                setShowBadge(false)
            }
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val runtime = (application as PolarWizApplication).runtime
        when (intent?.action) {
            ACTION_PAUSE -> runtime.setAutomationPaused(!runtime.ui.value.automationPaused)
            ACTION_OFF -> runtime.turnOff()
        }
        DiagnosticLog.add("KeepAlive", "Foreground service started action=${intent?.action ?: "none"}")
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        fun serviceAction(action: String, requestCode: Int) = PendingIntent.getService(
            this, requestCode, Intent(this, AutomationKeepAliveService::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_polar_wiz)
            .setContentTitle("SarahVS Glow active")
            .setContentText("Heart feed, sleep detection, and light automation continue in the background")
            .setContentIntent(openApp)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(0, if (runtime.ui.value.automationPaused) "Resume" else "Pause", serviceAction(ACTION_PAUSE, 1))
            .addAction(0, "Lights off", serviceAction(ACTION_OFF, 2))
            .build()
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            notification,
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE else 0
        )
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "polar_wiz_background"
        private const val NOTIFICATION_ID = 1001
        const val ACTION_PAUSE = "com.pulsepointlabs.polarwiz.PAUSE"
        const val ACTION_OFF = "com.pulsepointlabs.polarwiz.OFF"
    }
}
