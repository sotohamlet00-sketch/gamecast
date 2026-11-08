package com.example.gamepadhid

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

/**
 * Los permisos se piden SOLO cuando el usuario inicia la función que los necesita
 * (nunca al abrir la app), y si faltan se muestra un mensaje en vez de cerrar la app.
 */
object Permissions {

    private fun granted(c: Context, permission: String) =
        ContextCompat.checkSelfPermission(c, permission) == PackageManager.PERMISSION_GRANTED

    /** Android 12+: "Dispositivos cercanos". En Android 9-11 van en el manifiesto. */
    fun bluetoothMissing(c: Context): List<String> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return emptyList()
        return listOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE
        ).filter { !granted(c, it) }
    }

    /** Android 13+: opcional; sin él los servicios funcionan pero la notificación no se ve. */
    fun notificationsMissing(c: Context): List<String> =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !granted(c, Manifest.permission.POST_NOTIFICATIONS)
        ) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()

    fun hasBluetoothConnect(c: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(c, Manifest.permission.BLUETOOTH_CONNECT)

    fun hasBluetoothAdvertise(c: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S || granted(c, Manifest.permission.BLUETOOTH_ADVERTISE)

    fun openAppSettings(c: Context) {
        c.startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", c.packageName, null))
        )
    }
}
