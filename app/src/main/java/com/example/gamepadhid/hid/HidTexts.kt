package com.example.gamepadhid.hid

import android.content.Context
import com.example.gamepadhid.R

/** Mensajes de estado del mando, compartidos por la pantalla de inicio y la del mando. */
object HidTexts {
    fun describe(c: Context, state: HidManager.State, deviceLabel: String): String = when (state) {
        HidManager.State.IDLE -> c.getString(R.string.hid_idle)
        HidManager.State.NO_BLUETOOTH -> c.getString(R.string.hid_no_bluetooth)
        HidManager.State.BLUETOOTH_OFF -> c.getString(R.string.hid_bluetooth_off)
        HidManager.State.NO_PERMISSION -> c.getString(R.string.hid_no_permission)
        HidManager.State.NO_HID_SUPPORT -> c.getString(R.string.hid_no_hid)
        HidManager.State.REGISTRATION_FAILED -> c.getString(R.string.hid_registration_failed)
        HidManager.State.READY -> c.getString(R.string.hid_ready)
        HidManager.State.CONNECTING -> c.getString(R.string.hid_connecting, deviceLabel)
        HidManager.State.CONNECTED -> c.getString(R.string.hid_connected, deviceLabel)
    }
}
