package com.example.gamepadhid.hid

import android.bluetooth.BluetoothDevice
import android.content.Context
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Punto único de acceso al HidManager para todo el proceso.
 * Así el mando sigue vivo (gracias a HidService) aunque cambies de pantalla, y varias
 * pantallas pueden observar su estado a la vez. Todo se invoca en el hilo principal.
 */
object HidSession {

    /** true mientras el modo "Mando HID" está encendido (HidService en marcha). */
    @Volatile
    var active = false
        private set

    @Volatile
    var lastDevice: BluetoothDevice? = null
        private set

    private var manager: HidManager? = null
    private val observers = CopyOnWriteArraySet<(HidManager.State, BluetoothDevice?) -> Unit>()

    private val forwarder = object : HidManager.Listener {
        override fun onStateChanged(state: HidManager.State, device: BluetoothDevice?) {
            lastDevice = device
            for (o in observers) o(state, device)
        }
    }

    @Synchronized
    fun manager(context: Context): HidManager =
        manager ?: HidManager(context.applicationContext, forwarder).also { manager = it }

    fun setActive(value: Boolean) {
        active = value
        val state = manager?.state ?: HidManager.State.IDLE
        for (o in observers) o(state, lastDevice)
    }

    fun addObserver(o: (HidManager.State, BluetoothDevice?) -> Unit) {
        observers.add(o)
    }

    fun removeObserver(o: (HidManager.State, BluetoothDevice?) -> Unit) {
        observers.remove(o)
    }
}
