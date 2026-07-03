package com.pulsepointlabs.polarwiz

import android.app.Application

class PolarWizApplication : Application() {
    val runtime: LightingRuntime by lazy { LightingRuntime(this) }
}
