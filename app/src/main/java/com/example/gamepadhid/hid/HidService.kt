package com.example.gamepadhid.hid

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.gamepadhid.MainActivity
import com.example.gamepadhid.Prefs
import com.example.gamepadhid.R

/**
 * Servicio en primer plano del modo "Mando HID" (tipo connectedDevice).
 * Mantiene el proceso y el registro HID vivos aunque salgas de la app o apagues la pantalla.
 * Encender el modo = arrancar este servicio; apagarlo = pararlo.
 *
 * Android 14+: exige el permiso FOREGROUND_SERVICE_CONNECTED_DEVICE en el manifiesto y
 * tener concedido BLUETOOTH_CONNECT antes de llamar a startForeground().
 */
class HidService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.gamepadhid.action.STOP_HID"
        private const val CHANNEL_ID = "hid"
        private const val NOTIFICATION_ID = 41

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, HidService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HidService::class.java))
        }
    }

    private lateinit var hid: HidManager
    private var receiverRegistered = false

    /** Si el usuario enciende/apaga Bluetooth con el modo activo, el mando se re-registra solo. */
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                BluetoothAdapter.STATE_TURNING_OFF -> hid.stop()
                BluetoothAdapter.STATE_OFF, BluetoothAdapter.STATE_ON -> hid.start()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        hid = HidSession.manager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startAsForeground()
        } catch (e: RuntimeException) { // permiso revocado o inicio no permitido
            Toast.makeText(this, R.string.err_hid_service, Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!HidSession.active) {
            HidSession.setActive(true)
            hid.deviceName = Prefs.btName(this)
            ContextCompat.registerReceiver(
                this, bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            receiverRegistered = true
        }
        hid.start()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(bluetoothReceiver)
            receiverRegistered = false
        }
        hid.gamepad.reset()
        hid.stop()
        HidSession.setActive(false)
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startAsForeground() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_hid_channel), NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, HidService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_gamepad)
            .setContentTitle(getString(R.string.notif_hid_title))
            .setContentText(getString(R.string.notif_hid_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
