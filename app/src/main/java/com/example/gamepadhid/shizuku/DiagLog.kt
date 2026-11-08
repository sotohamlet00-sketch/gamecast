package com.example.gamepadhid.shizuku

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registro de diagnóstico (en memoria) del Modo Consola. Se muestra en la pantalla de Shizuku para poder
 * copiar el motivo exacto si algún paso falla (Android 13 a 16 cambia mucho las APIs ocultas).
 */
object DiagLog {
    private const val MAX_LINES = 150
    private val lines = ArrayDeque<String>()
    private val format = SimpleDateFormat("HH:mm:ss", Locale.US)

    @Synchronized
    fun add(message: String) {
        lines.addLast("${format.format(Date())} $message")
        while (lines.size > MAX_LINES) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String = if (lines.isEmpty()) "(sin registros)" else lines.joinToString("\n")

    @Synchronized
    fun clear() = lines.clear()
}
