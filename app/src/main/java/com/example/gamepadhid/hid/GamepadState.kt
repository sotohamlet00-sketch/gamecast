package com.example.gamepadhid.hid

import kotlin.math.roundToInt

/**
 * Estado actual del mando (botones, D-pad, sticks, gatillos).
 * La UI lo modifica desde el hilo principal; HidManager lo lee desde otro hilo
 * para enviarlo, por eso todo el acceso está sincronizado.
 */
class GamepadState {

    companion object {
        const val DPAD_UP = 1
        const val DPAD_RIGHT = 2
        const val DPAD_DOWN = 4
        const val DPAD_LEFT = 8

        /** Un gatillo analógico por encima de este valor también activa su botón digital. */
        private const val TRIGGER_BUTTON_THRESHOLD = 40
    }

    private val lock = Any()
    private var buttons = 0
    private var dpad = 0
    private var lx = 0
    private var ly = 0
    private var rx = 0
    private var ry = 0
    private var l2 = 0
    private var r2 = 0

    /** Se invoca (fuera del lock) cada vez que cambia algo. */
    @Volatile
    var onChanged: (() -> Unit)? = null

    private inline fun update(block: () -> Unit) {
        synchronized(lock) { block() }
        onChanged?.invoke()
    }

    private fun setBit(bit: Int, on: Boolean) {
        buttons = if (on) buttons or (1 shl bit) else buttons and (1 shl bit).inv()
    }

    private fun axis(v: Float): Int = (v.coerceIn(-1f, 1f) * 127f).roundToInt()

    fun setButton(bit: Int, pressed: Boolean) = update { setBit(bit, pressed) }

    fun setDpad(direction: Int, pressed: Boolean) = update {
        dpad = if (pressed) dpad or direction else dpad and direction.inv()
    }

    /** x, y en -1..1 (y negativo = arriba, igual que las coordenadas de pantalla). */
    fun setLeftStick(x: Float, y: Float) = update { lx = axis(x); ly = axis(y) }

    fun setRightStick(x: Float, y: Float) = update { rx = axis(x); ry = axis(y) }

    /** value 0..255. También activa/desactiva el botón digital L2/R2. */
    fun setTrigger(left: Boolean, value: Int) = update {
        val v = value.coerceIn(0, 255)
        if (left) {
            l2 = v
            setBit(GamepadDescriptor.BTN_L2, v > TRIGGER_BUTTON_THRESHOLD)
        } else {
            r2 = v
            setBit(GamepadDescriptor.BTN_R2, v > TRIGGER_BUTTON_THRESHOLD)
        }
    }

    fun reset() = update {
        buttons = 0; dpad = 0; lx = 0; ly = 0; rx = 0; ry = 0; l2 = 0; r2 = 0
    }

    private fun hatValue(): Int = when (dpad) {
        DPAD_UP -> 0
        DPAD_UP or DPAD_RIGHT -> 1
        DPAD_RIGHT -> 2
        DPAD_RIGHT or DPAD_DOWN -> 3
        DPAD_DOWN -> 4
        DPAD_DOWN or DPAD_LEFT -> 5
        DPAD_LEFT -> 6
        DPAD_LEFT or DPAD_UP -> 7
        else -> 8 // centro (o direcciones opuestas a la vez)
    }

    /** Construye el reporte de 9 bytes (sin Report ID) según GamepadDescriptor. */
    fun toReport(): ByteArray = synchronized(lock) {
        byteArrayOf(
            (buttons and 0xFF).toByte(),
            ((buttons shr 8) and 0xFF).toByte(),
            hatValue().toByte(),
            lx.toByte(), ly.toByte(), rx.toByte(), ry.toByte(),
            l2.toByte(), r2.toByte()
        )
    }
}
