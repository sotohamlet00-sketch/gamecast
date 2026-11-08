package com.example.gamepadhid.cast

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.provider.Settings

/**
 * Detecta si el teléfono puede hacer Miracast (Wi-Fi Display).
 *
 * Android NO tiene una API pública que diga "este equipo soporta Miracast", así que
 * se combinan tres pistas:
 *  1. Wi-Fi Direct (requisito técnico de Miracast).
 *  2. El flag del sistema config_enableWifiDisplay (los fabricantes lo ponen en false
 *     cuando quitan Miracast; p. ej. los Pixel recientes). Se lee por nombre.
 *  3. Que exista el panel "Cast" del sistema (Settings.ACTION_CAST_SETTINGS).
 */
object CastCapabilities {

    enum class Level {
        SUPPORTED,   // Miracast probablemente disponible
        LIMITED,     // Wi-Fi Direct sí, pero no se encontró el panel de Cast estándar
        UNSUPPORTED  // sin Miracast: sugerir cable o el modo Mando HID
    }

    data class Result(
        val level: Level,
        val wifiDirect: Boolean,
        /** null = el sistema no expone el flag (no se sabe). */
        val miracastEnabledByRom: Boolean?,
        val castSettingsAvailable: Boolean
    )

    fun check(context: Context): Result {
        val pm = context.packageManager
        val wifiDirect = pm.hasSystemFeature(PackageManager.FEATURE_WIFI_DIRECT)
        val romFlag = readMiracastFlag()
        val castPanel = Intent(Settings.ACTION_CAST_SETTINGS).resolveActivity(pm) != null

        val level = when {
            romFlag == false || !wifiDirect -> Level.UNSUPPORTED
            !castPanel -> Level.LIMITED
            else -> Level.SUPPORTED
        }
        return Result(level, wifiDirect, romFlag, castPanel)
    }

    @SuppressLint("DiscouragedApi")
    private fun readMiracastFlag(): Boolean? {
        val res = Resources.getSystem()
        val id = res.getIdentifier("config_enableWifiDisplay", "bool", "android")
        if (id == 0) return null
        return try {
            res.getBoolean(id)
        } catch (e: Resources.NotFoundException) {
            null
        }
    }
}
