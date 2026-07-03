package com.pulsepointlabs.polarwiz

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object DiagnosticLog {
    private const val MAX_LINES = 500
    private val lines = ArrayDeque<String>()
    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Synchronized fun add(tag: String, message: String) {
        lines.addLast("${format.format(Date())} [$tag] $message")
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized fun export(): String = buildString {
        appendLine("Polar WiZ HR diagnostics")
        appendLine("Generated: ${format.format(Date())}")
        appendLine()
        lines.forEach(::appendLine)
    }
}
