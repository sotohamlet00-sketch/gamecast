package com.example.gamepadhid.mapping

import android.content.Context

/**
 * Guarda un mapa por juego en SharedPreferences ("gamecast_mappings"): la clave es
 * "mapping_<packageName>.json" y el valor es el JSON con las coordenadas en porcentaje.
 */
object MappingStore {
    private const val FILE = "gamecast_mappings"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private fun key(pkg: String) = "mapping_$pkg.json"

    fun load(c: Context, pkg: String): GameMapping? =
        sp(c).getString(key(pkg), null)?.let { GameMapping.fromJson(it) }

    fun has(c: Context, pkg: String): Boolean = load(c, pkg) != null

    fun save(c: Context, mapping: GameMapping) {
        sp(c).edit().putString(key(mapping.packageName), mapping.toJson()).apply()
    }

    fun delete(c: Context, pkg: String) {
        sp(c).edit().remove(key(pkg)).apply()
    }
}
