package com.pulsepointlabs.polarwiz

import android.app.ActivityManager
import android.app.Application
import android.os.Build
import android.os.Process
import java.io.PrintWriter
import java.io.StringWriter

class PolarWizApplication : Application() {
    val runtime: LightingRuntime by lazy { LightingRuntime(this) }

    override fun onCreate() {
        super.onCreate()
        val preferences = getSharedPreferences("polar_wiz_preferences", 0)
        captureLastProcessExit(preferences)
        preferences.getString("last_fatal_crash", null)?.let { DiagnosticLog.add("PreviousFatalCrash", it) }
        preferences.getString("last_process_exit", null)?.let { DiagnosticLog.add("PreviousProcessExit", it) }
        val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                val trace = StringWriter().also { error.printStackTrace(PrintWriter(it)) }.toString()
                preferences.edit()
                    .putBoolean("precision_mode", false)
                    .putString("last_fatal_crash", "Thread=${thread.name}\n$trace")
                    .commit()
            }
            if (previousHandler != null) previousHandler.uncaughtException(thread, error)
            else { Process.killProcess(Process.myPid()); kotlin.system.exitProcess(10) }
        }
    }

    private fun captureLastProcessExit(preferences: android.content.SharedPreferences) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return
        runCatching {
            val manager = getSystemService(ActivityManager::class.java)
            val reason = manager.getHistoricalProcessExitReasons(packageName, 0, 1).firstOrNull() ?: return
            val description = buildString {
                append("reason=").append(reason.reason)
                append(" importance=").append(reason.importance)
                append(" status=").append(reason.status)
                append(" time=").append(reason.timestamp)
                reason.description?.takeIf { it.isNotBlank() }?.let { append(" description=").append(it.take(240)) }
            }
            preferences.edit().putString("last_process_exit", description).apply()
        }.onFailure {
            DiagnosticLog.add("ProcessExit", "Could not read previous process exit: ${it.message}")
        }
    }
}
