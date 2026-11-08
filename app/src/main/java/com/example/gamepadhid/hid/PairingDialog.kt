package com.example.gamepadhid.hid

import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.gamepadhid.Permissions
import com.example.gamepadhid.R

/**
 * EMPAREJAMIENTO. Tres caminos, según quién inicie el vínculo:
 *  1. Hacer visible el teléfono 5 min y vincularlo desde el menú Bluetooth de la TV.
 *  2. Conectar desde el teléfono a una TV que ya está emparejada.
 *  3. Abrir los ajustes de Bluetooth del sistema para emparejar una TV nueva.
 */
object PairingDialog {

    fun show(activity: AppCompatActivity) {
        val hid = HidSession.manager(activity)
        if (!HidSession.active || !hid.isReady) {
            toast(activity, R.string.toast_start_hid_first)
            return
        }
        val items = arrayOf(
            activity.getString(R.string.pair_visible),
            activity.getString(R.string.pair_pick),
            activity.getString(R.string.pair_settings)
        )
        AlertDialog.Builder(activity)
            .setTitle(R.string.pair_title)
            .setItems(items) { _, which ->
                when (which) {
                    0 -> makeVisible(activity)
                    1 -> pickPaired(activity, hid)
                    else -> openBluetoothSettings(activity)
                }
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun makeVisible(activity: AppCompatActivity) {
        if (!Permissions.hasBluetoothAdvertise(activity)) {
            toast(activity, R.string.toast_need_nearby)
            Permissions.openAppSettings(activity)
            return
        }
        val intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            .putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
        try {
            activity.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            toast(activity, R.string.toast_discoverable_failed)
        }
    }

    private fun pickPaired(activity: AppCompatActivity, hid: HidManager) {
        val devices = hid.bondedDevices()
        if (devices.isEmpty()) {
            AlertDialog.Builder(activity)
                .setMessage(R.string.dialog_no_paired)
                .setPositiveButton(R.string.pair_settings) { _, _ -> openBluetoothSettings(activity) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val labels = devices.map { "${hid.label(it)}\n${it.address}" }.toTypedArray()
        AlertDialog.Builder(activity)
            .setTitle(R.string.dialog_pick_title)
            .setItems(labels) { _, index ->
                if (!hid.connect(devices[index])) toast(activity, R.string.toast_connect_failed)
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openBluetoothSettings(activity: AppCompatActivity) {
        try {
            activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS))
        } catch (e: ActivityNotFoundException) {
            toast(activity, R.string.toast_enable_bt_manually)
        }
    }

    private fun toast(activity: AppCompatActivity, resId: Int) =
        Toast.makeText(activity, resId, Toast.LENGTH_LONG).show()
}
