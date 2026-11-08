package com.example.gamepadhid.hid

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.core.content.ContextCompat
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Gestiona todo el ciclo de vida del mando Bluetooth HID (Bluetooth Classic, BR/EDR):
 *  1. Obtiene el proxy del perfil HID_DEVICE.
 *  2. Registra el teléfono como dispositivo HID "Game Pad" (SDP + descriptor).
 *  3. Mantiene la conexión con el host (la TV) y le envía los reportes.
 *
 * Los permisos se comprueban a mano; por eso se silencia MissingPermission.
 */
@SuppressLint("MissingPermission")
class HidManager(
    context: Context,
    private val listener: Listener
) {

    enum class State {
        IDLE,                // sin iniciar
        NO_BLUETOOTH,        // el equipo no tiene Bluetooth
        BLUETOOTH_OFF,       // Bluetooth apagado
        NO_PERMISSION,       // falta BLUETOOTH_CONNECT
        NO_HID_SUPPORT,      // el equipo/ROM no ofrece el perfil HID Device
        REGISTRATION_FAILED, // otra app ya usa el perfil o el sistema lo rechazó
        READY,               // registrado como mando, esperando a un host
        CONNECTING,
        CONNECTED
    }

    interface Listener {
        /** Siempre se llama en el hilo principal. */
        fun onStateChanged(state: State, device: BluetoothDevice?)
    }

    companion object {
        /** Intervalo mínimo entre reportes (~125 Hz máx.) para no saturar el stack Bluetooth. */
        private const val MIN_INTERVAL_MS = 8L
    }

    private val appContext = context.applicationContext
    private val mainExecutor = ContextCompat.getMainExecutor(appContext)
    private val btAdapter: BluetoothAdapter? =
        (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    /** Estado del mando; la UI lo modifica y HidManager lo envía solo. */
    val gamepad = GamepadState()

    /** Nombre del servicio HID publicado en SDP. Se aplica en el siguiente start(). */
    var deviceName: String = "Mando Android"

    var state: State = State.IDLE
        private set

    val isReady: Boolean
        get() = state == State.READY || state == State.CONNECTING || state == State.CONNECTED

    @Volatile private var hidDevice: BluetoothHidDevice? = null
    @Volatile private var host: BluetoothDevice? = null
    private var appRegistered = false
    private var proxyRequested = false

    // Hilo propio para enviar reportes sin bloquear la UI
    private var senderThread: HandlerThread? = null
    private var senderHandler: Handler? = null
    private val sendPending = AtomicBoolean(false)
    @Volatile private var lastSendMs = 0L

    // ------------------------------------------------------------------ API pública

    fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED

    /** Inicia (o reintenta) el registro. Es seguro llamarlo varias veces. */
    fun start() {
        val existing = hidDevice
        if (existing != null) {
            // El proxy ya está abierto. Si el registro falló antes, se repite el registro
            // (antes este caso salía sin hacer nada y el reintento del usuario no servía).
            if (!appRegistered && state == State.REGISTRATION_FAILED) {
                try {
                    existing.unregisterApp() // limpia un registro a medias
                } catch (e: RuntimeException) {
                    // sin permiso o servicio caído: se intenta registrar igualmente
                }
                registerApp()
            }
            return
        }
        if (proxyRequested) return // esperando a que el sistema entregue el proxy
        val adapter = btAdapter
        if (adapter == null) {
            setState(State.NO_BLUETOOTH)
            return
        }
        if (!hasConnectPermission()) {
            setState(State.NO_PERMISSION)
            return
        }
        if (!adapter.isEnabled) {
            setState(State.BLUETOOTH_OFF)
            return
        }

        proxyRequested = true
        // Si el equipo no soporta HID Device, getProfileProxy devuelve false.
        val ok = try {
            adapter.getProfileProxy(appContext, profileListener, BluetoothProfile.HID_DEVICE)
        } catch (e: SecurityException) {
            false
        }
        if (!ok) {
            proxyRequested = false
            setState(State.NO_HID_SUPPORT)
        }
    }

    /** Libera todo: desregistra el mando y cierra el proxy. */
    fun stop() {
        stopSender()
        val hid = hidDevice
        if (hid != null) {
            try {
                hid.unregisterApp()
                btAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hid)
            } catch (e: SecurityException) {
                // sin permiso no podemos limpiar; el sistema lo hará al cerrar el proceso
            }
        }
        hidDevice = null
        host = null
        appRegistered = false
        proxyRequested = false
        setState(State.IDLE)
    }

    /** Dispositivos ya emparejados (la TV debería aparecer aquí tras vincularla). */
    fun bondedDevices(): List<BluetoothDevice> {
        if (!hasConnectPermission()) return emptyList()
        return btAdapter?.bondedDevices?.toList().orEmpty()
    }

    /** Pide la conexión HID hacia un host ya emparejado. */
    fun connect(device: BluetoothDevice): Boolean {
        val hid = hidDevice ?: return false
        if (!appRegistered) return false
        return hid.connect(device)
    }

    fun disconnect() {
        val hid = hidDevice ?: return
        host?.let { hid.disconnect(it) }
    }

    fun label(device: BluetoothDevice): String =
        try { device.name ?: device.address } catch (e: SecurityException) { device.address }

    // ------------------------------------------------------------------ Proxy y registro

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            hidDevice = proxy as BluetoothHidDevice
            registerApp()
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile != BluetoothProfile.HID_DEVICE) return
            stopSender()
            hidDevice = null
            host = null
            appRegistered = false
            proxyRequested = false
            setState(State.IDLE)
        }
    }

    /**
     * EMPAREJAMIENTO / REGISTRO HID:
     * registerApp publica en SDP un servicio HID cuyo descriptor es nuestro gamepad.
     * Cuando la TV (host) vincula al teléfono lee ese descriptor y lo trata como un
     * mando físico. Solo una app a la vez puede tener registrado el perfil HID.
     */
    private fun registerApp() {
        val hid = hidDevice ?: return
        val sdp = BluetoothHidDeviceAppSdpSettings(
            deviceName,
            "Gamepad virtual",
            "Android",
            BluetoothHidDevice.SUBCLASS2_GAMEPAD, // clase de dispositivo: Game Pad
            GamepadDescriptor.DESCRIPTOR
        )
        val ok = try {
            hid.registerApp(sdp, null, null, mainExecutor, hidCallback)
        } catch (e: SecurityException) {
            false
        }
        if (!ok) setState(State.REGISTRATION_FAILED)
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {

        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            appRegistered = registered
            if (registered) {
                if (state != State.CONNECTED && state != State.CONNECTING) setState(State.READY)
            } else {
                stopSender()
                host = null
                setState(State.REGISTRATION_FAILED)
            }
        }

        override fun onConnectionStateChanged(device: BluetoothDevice, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTING -> setState(State.CONNECTING, device)
                BluetoothProfile.STATE_CONNECTED -> {
                    host = device
                    startSender()
                    setState(State.CONNECTED, device)
                }
                BluetoothProfile.STATE_DISCONNECTING,
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (host == null || host == device) {
                        host = null
                        stopSender()
                        if (appRegistered) setState(State.READY)
                    }
                }
            }
        }

        /** El host pide el estado actual (GET_REPORT por el canal de control). */
        override fun onGetReport(device: BluetoothDevice, type: Byte, id: Byte, bufferSize: Int) {
            val hid = hidDevice ?: return
            if (type == BluetoothHidDevice.REPORT_TYPE_INPUT &&
                (id.toInt() == 0 || id.toInt() == GamepadDescriptor.REPORT_ID)
            ) {
                hid.replyReport(device, type, GamepadDescriptor.REPORT_ID.toByte(), gamepad.toReport())
            } else {
                hid.reportError(device, BluetoothHidDevice.ERROR_RSP_UNSUPPORTED_REQ)
            }
        }

        /** El gamepad no tiene reportes de salida (LEDs, rumble): confirmamos y ya. */
        override fun onSetReport(device: BluetoothDevice, type: Byte, id: Byte, data: ByteArray) {
            hidDevice?.reportError(device, BluetoothHidDevice.ERROR_RSP_SUCCESS)
        }

        override fun onVirtualCableUnplug(device: BluetoothDevice) {
            host = null
            stopSender()
            if (appRegistered) setState(State.READY)
        }
    }

    // ------------------------------------------------------------------ Envío de reportes

    private fun startSender() {
        if (senderThread != null) return
        val thread = HandlerThread("hid-sender").also { it.start() }
        senderThread = thread
        senderHandler = Handler(thread.looper)
        gamepad.onChanged = { scheduleSend() }
        scheduleSend() // primer reporte: estado neutro, para que el host "vea" el mando
    }

    private fun stopSender() {
        gamepad.onChanged = null
        senderHandler?.removeCallbacksAndMessages(null)
        senderThread?.quitSafely()
        senderThread = null
        senderHandler = null
        sendPending.set(false)
    }

    /** Junta cambios muy seguidos en un solo envío, respetando MIN_INTERVAL_MS. */
    private fun scheduleSend() {
        val handler = senderHandler ?: return
        if (!sendPending.compareAndSet(false, true)) return
        val wait = (MIN_INTERVAL_MS - (SystemClock.uptimeMillis() - lastSendMs)).coerceAtLeast(0L)
        handler.postDelayed(sendRunnable, wait)
    }

    private val sendRunnable = Runnable {
        sendPending.set(false)
        val hid = hidDevice
        val target = host
        if (hid != null && target != null) {
            try {
                // Android antepone el Report ID; "data" va SIN el ID.
                hid.sendReport(target, GamepadDescriptor.REPORT_ID, gamepad.toReport())
            } catch (e: SecurityException) {
                // permiso revocado en caliente: se ignora
            }
            lastSendMs = SystemClock.uptimeMillis()
        }
    }

    private fun setState(newState: State, device: BluetoothDevice? = null) {
        state = newState
        listener.onStateChanged(newState, device)
    }
}
