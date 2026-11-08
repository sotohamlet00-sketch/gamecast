package com.example.gamepadhid

import android.content.Context

/** Ajustes mínimos guardados en SharedPreferences. */
object Prefs {
    private const val FILE = "gamecast_prefs"
    private const val KEY_BT_NAME = "bt_name"
    private const val KEY_TV_NAME = "tv_name"

    private fun sp(c: Context) = c.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun btName(c: Context): String =
        sp(c).getString(KEY_BT_NAME, null) ?: c.getString(R.string.default_bt_name)

    fun setBtName(c: Context, value: String) {
        sp(c).edit().putString(KEY_BT_NAME, value).apply()
    }

    /** Nombre de la última TV elegida (el id del display cambia en cada conexión; el nombre no). */
    fun tvName(c: Context): String? = sp(c).getString(KEY_TV_NAME, null)

    fun setTvName(c: Context, value: String?) {
        sp(c).edit().putString(KEY_TV_NAME, value).apply()
    }
}
