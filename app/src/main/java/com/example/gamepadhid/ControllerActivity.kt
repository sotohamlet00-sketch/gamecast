package com.example.gamepadhid

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.os.Bundle
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.gamepadhid.cast.ProjectionService
import com.example.gamepadhid.hid.GamepadDescriptor
import com.example.gamepadhid.hid.GamepadState
import com.example.gamepadhid.hid.HidManager
import com.example.gamepadhid.hid.HidSession
import com.example.gamepadhid.hid.HidTexts
import com.example.gamepadhid.hid.PairingDialog
import com.example.gamepadhid.ui.Fx
import com.example.gamepadhid.ui.JoystickView
import com.example.gamepadhid.ui.applySystemBarsPadding

/**
 * Mando táctil (siempre horizontal). Envía por Bluetooth SOLO si el modo "Mando HID" está activo.
 * Estando el modo "Juego en TV" activo, esta pantalla se sigue viendo en la TV como cualquier otra.
 * Los controles no pueden manejar el juego de otra app del teléfono: Android no lo permite.
 */
class ControllerActivity : AppCompatActivity() {

    private lateinit var hid: HidManager
    private lateinit var dotCtl: View
    private lateinit var tvHid: TextView
    private lateinit var tvTv: TextView
    private lateinit var btnPair: TextView
    private lateinit var btnDisconnect: TextView

    private val hidObserver: (HidManager.State, BluetoothDevice?) -> Unit = { _, _ -> renderHid() }
    private val tvListener: (ProjectionService.SessionState, String?) -> Unit =
        { state, msg -> renderTv(state, msg) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_controller)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        findViewById<View>(R.id.root).applySystemBarsPadding(extraDp = 8)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        hid = HidSession.manager(this)
        dotCtl = findViewById(R.id.dotCtl)
        tvHid = findViewById(R.id.tvHid)
        tvTv = findViewById(R.id.tvTv)
        btnPair = findViewById(R.id.btnPair)
        btnDisconnect = findViewById(R.id.btnDisconnect)

        bindControls()

        btnPair.setOnClickListener { PairingDialog.show(this) }
        btnDisconnect.setOnClickListener { hid.disconnect() }
        findViewById<View>(R.id.btnHome).setOnClickListener { finish() }
        listOf<View>(btnPair, btnDisconnect, findViewById<View>(R.id.btnHome)).forEach { Fx.attachPress(it) }
    }

    override fun onStart() {
        super.onStart()
        HidSession.addObserver(hidObserver)
        ProjectionService.addListener(tvListener)
        renderHid()
        renderTv(ProjectionService.state, ProjectionService.message)
    }

    override fun onStop() {
        ProjectionService.removeListener(tvListener)
        HidSession.removeObserver(hidObserver)
        hid.gamepad.reset() // no dejar botones "pegados" al salir de la pantalla
        super.onStop()
    }

    // ------------------------------------------------------------------ Estado

    private fun renderHid() {
        val active = HidSession.active
        val state = hid.state
        tvHid.text = if (active) {
            HidTexts.describe(this, state, HidSession.lastDevice?.let { hid.label(it) } ?: "")
        } else {
            getString(R.string.ctl_hid_off)
        }
        val (color, pulse) = when {
            !active -> R.color.pad_text_muted to false
            state == HidManager.State.CONNECTED -> R.color.pad_accent2 to false
            state == HidManager.State.READY || state == HidManager.State.CONNECTING ||
                state == HidManager.State.IDLE -> R.color.pad_accent2 to true
            else -> R.color.pad_danger to false
        }
        Fx.setDot(dotCtl, ContextCompat.getColor(this, color), pulse)
        Fx.setEnabled(btnPair, active)
        Fx.setEnabled(btnDisconnect, active && state == HidManager.State.CONNECTED)
    }

    private fun renderTv(state: ProjectionService.SessionState, msg: String?) {
        tvTv.text = when (state) {
            ProjectionService.SessionState.STREAMING -> msg ?: getString(R.string.status_streaming_generic)
            ProjectionService.SessionState.STARTING -> getString(R.string.status_starting)
            ProjectionService.SessionState.ERROR -> msg ?: getString(R.string.err_generic)
            ProjectionService.SessionState.IDLE -> getString(R.string.ctl_tv_off)
        }
    }

    // ------------------------------------------------------------------ Controles

    private fun bindControls() {
        val pad = hid.gamepad

        findViewById<JoystickView>(R.id.joyLeft).onMove = { x, y -> pad.setLeftStick(x, y) }
        findViewById<JoystickView>(R.id.joyRight).onMove = { x, y -> pad.setRightStick(x, y) }

        val buttons = mapOf(
            R.id.btnA to GamepadDescriptor.BTN_A,
            R.id.btnB to GamepadDescriptor.BTN_B,
            R.id.btnX to GamepadDescriptor.BTN_X,
            R.id.btnY to GamepadDescriptor.BTN_Y,
            R.id.btnL1 to GamepadDescriptor.BTN_L1,
            R.id.btnR1 to GamepadDescriptor.BTN_R1,
            R.id.btnSelect to GamepadDescriptor.BTN_SELECT,
            R.id.btnStart to GamepadDescriptor.BTN_START,
            R.id.btnMode to GamepadDescriptor.BTN_MODE,
            R.id.btnL3 to GamepadDescriptor.BTN_L3,
            R.id.btnR3 to GamepadDescriptor.BTN_R3
        )
        for ((viewId, bit) in buttons) bindHold(viewId) { pressed -> pad.setButton(bit, pressed) }

        bindHold(R.id.btnL2) { pressed -> pad.setTrigger(left = true, value = if (pressed) 255 else 0) }
        bindHold(R.id.btnR2) { pressed -> pad.setTrigger(left = false, value = if (pressed) 255 else 0) }

        bindHold(R.id.dpUp) { pad.setDpad(GamepadState.DPAD_UP, it) }
        bindHold(R.id.dpRight) { pad.setDpad(GamepadState.DPAD_RIGHT, it) }
        bindHold(R.id.dpDown) { pad.setDpad(GamepadState.DPAD_DOWN, it) }
        bindHold(R.id.dpLeft) { pad.setDpad(GamepadState.DPAD_LEFT, it) }
    }

    /** Botón "mantener": true al tocar y false al soltar, con animación de pulsación y háptico. */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindHold(viewId: Int, onChange: (Boolean) -> Unit) {
        findViewById<View>(viewId).setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    Fx.pressDown(v)
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    onChange(true)
                }
                MotionEvent.ACTION_UP -> {
                    v.isPressed = false
                    Fx.pressUp(v)
                    onChange(false)
                    v.performClick()
                }
                MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    Fx.pressUp(v)
                    onChange(false)
                }
            }
            true
        }
    }
}
