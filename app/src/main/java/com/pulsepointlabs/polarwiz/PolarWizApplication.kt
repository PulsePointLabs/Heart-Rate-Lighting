package com.pulsepointlabs.polarwiz

import android.app.Application
import android.os.Process
import java.io.PrintWriter
import java.io.StringWriter

class PolarWizApplication : Application() {
    val runtime: LightingRuntime by lazy { LightingRuntime(this) }

    override fun onCreate() {
        super.onCreate()
        val preferences = getSharedPreferences("polar_wiz_preferences", 0)
        preferences.getString("last_fatal_crash", null)?.let { DiagnosticLog.add("PreviousFatalCrash", it) }
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
}
