package com.example.gamepadhid.overlay

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.gamepadhid.MainActivity
import com.example.gamepadhid.R
import com.example.gamepadhid.console.ConsoleService
import com.example.gamepadhid.console.ConsoleSession
import com.example.gamepadhid.mapping.ControlIds
import com.example.gamepadhid.mapping.ControlSpec
import com.example.gamepadhid.mapping.GameMapping
import com.example.gamepadhid.mapping.MappingStore
import com.example.gamepadhid.ui.Fx
import com.example.gamepadhid.ui.JoystickView
import com.example.gamepadhid.virtual.GameVirtualDisplay
import com.example.gamepadhid.virtual.ScreenInfo
import com.example.gamepadhid.virtual.TouchInjectorShizuku
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * MANDO TÁCTIL FLOTANTE en la PANTALLA PRINCIPAL del teléfono (TYPE_APPLICATION_OVERLAY).
 *
 * Cada pulsación se convierte en un toque en la PANTALLA VIRTUAL del juego (TouchInjectorShizuku), en las
 * coordenadas guardadas en el mapa del juego. El mando no aparece en la TV porque la TV recibe la pantalla
 * virtual, que es otra distinta.
 *
 * Flags: FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL (los toques fuera del mando llegan al sistema). La ventana
 * mide solo lo que mide el mando. La barra central tiene un asa para arrastrarlo y una X para cerrar todo.
 *
 * Servicio en primer plano de tipo specialUse (ver ConsoleService).
 */
class FloatingPadService : Service() {

    companion object {
        const val ACTION_STOP = "com.example.gamepadhid.action.STOP_PAD"
        private const val CHANNEL_ID = "pad"
        private const val NOTIFICATION_ID = 45
        private const val ASPECT = 2.2f // ancho / alto del mando

        @Volatile
        var running = false
            private set

        fun start(context: Context) {
            ContextCompat.startForegroundService(context, Intent(context, FloatingPadService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatingPadService::class.java))
        }
    }

    private lateinit var windowManager: WindowManager
    private var padView: View? = null
    private var params: WindowManager.LayoutParams? = null
    private var screenW = 0
    private var screenH = 0

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this) || GameVirtualDisplay.displayId < 0) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            startAsForeground()
        } catch (e: RuntimeException) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (padView == null) {
            running = true
            buildPad()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        running = false
        padView?.let {
            try {
                windowManager.removeViewImmediate(it)
            } catch (e: IllegalArgumentException) {
                // la ventana ya no estaba
            }
        }
        padView = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Construcción

    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun buildPad() {
        val game = ConsoleSession.gamePackage.orEmpty()
        val mapping = MappingStore.load(this, game) ?: GameMapping.template(game, "")
        val screen = ScreenInfo.realSize(this)
        screenW = screen.x
        screenH = screen.y

        val padH = min(screenH * 0.72f, screenW / ASPECT).roundToInt()
        val padW = min((padH * ASPECT).roundToInt(), screenW)

        val themed = ContextThemeWrapper(this, R.style.Theme_GamepadHid)
        val view = LayoutInflater.from(themed).inflate(R.layout.view_pad, null)
        bindControls(view, mapping)
        bindBar(view)

        val lp = WindowManager.LayoutParams(
            padW, padH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = (screenW - padW) / 2
        lp.y = screenH - padH - dp(8)
        windowManager.addView(view, lp)
        padView = view
        params = lp
    }

    /** Une cada control del mando con su punto en la pantalla virtual. Los controles sin mapear se ocultan. */
    private fun bindControls(view: View, mapping: GameMapping) {
        val displayId = GameVirtualDisplay.displayId
        val vw = GameVirtualDisplay.width.toFloat()
        val vh = GameVirtualDisplay.height.toFloat()
        val allIds = ControlIds.BUTTONS + ControlIds.STICKS

        val buttons = mapOf(
            R.id.padA to ControlIds.A, R.id.padB to ControlIds.B, R.id.padX to ControlIds.X, R.id.padY to ControlIds.Y,
            R.id.padL1 to ControlIds.L1, R.id.padR1 to ControlIds.R1, R.id.padL2 to ControlIds.L2, R.id.padR2 to ControlIds.R2,
            R.id.padStart to ControlIds.START, R.id.padSelect to ControlIds.SELECT
        )
        for ((viewId, controlId) in buttons) {
            val target = view.findViewById<View>(viewId)
            val spec = mapping.find(controlId)
            if (spec == null) {
                target.visibility = View.INVISIBLE
                continue
            }
            bindButton(target, displayId, spec.tx * vw, spec.ty * vh, allIds.indexOf(controlId) + 1)
        }

        val sticks = mapOf(R.id.padLeftStick to ControlIds.L_STICK, R.id.padRightStick to ControlIds.R_STICK)
        for ((viewId, controlId) in sticks) {
            val stick = view.findViewById<JoystickView>(viewId)
            val spec = mapping.find(controlId)
            if (spec == null) {
                stick.visibility = View.INVISIBLE
                continue
            }
            bindStick(stick, displayId, spec, vw, vh, allIds.indexOf(controlId) + 1)
        }
    }

    /** Botón "mantener": el dedo virtual se pone al tocar y se levanta al soltar. */
    @SuppressLint("ClickableViewAccessibility")
    private fun bindButton(target: View, displayId: Int, x: Float, y: Float, pointerId: Int) {
        target.setOnTouchListener { v, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.isPressed = true
                    Fx.pressDown(v)
                    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
                    TouchInjectorShizuku.holdDown(displayId, x, y, pointerId)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.isPressed = false
                    Fx.pressUp(v)
                    TouchInjectorShizuku.release(displayId, pointerId)
                }
            }
            true
        }
    }

    /** Stick: holdDown en el centro del stick del juego, moveTo según el dedo y release al soltar. */
    private fun bindStick(stick: JoystickView, displayId: Int, spec: ControlSpec, vw: Float, vh: Float, pointerId: Int) {
        val cx = spec.tx * vw
        val cy = spec.ty * vh
        val travel = spec.radius * min(vw, vh)
        stick.onEngage = { down ->
            if (down) TouchInjectorShizuku.holdDown(displayId, cx, cy, pointerId)
            else TouchInjectorShizuku.release(displayId, pointerId)
        }
        stick.onMove = { x, y -> TouchInjectorShizuku.moveTo(displayId, cx + x * travel, cy + y * travel, pointerId) }
    }

    // ------------------------------------------------------------------ Barra: arrastrar y cerrar

    @SuppressLint("ClickableViewAccessibility")
    private fun bindBar(view: View) {
        val handle = view.findViewById<View>(R.id.padHandle)
        var startRawX = 0f
        var startRawY = 0f
        var startX = 0
        var startY = 0
        handle.setOnTouchListener { v, e ->
            val lp = params ?: return@setOnTouchListener true
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    startRawX = e.rawX
                    startRawY = e.rawY
                    startX = lp.x
                    startY = lp.y
                    Fx.pressDown(v)
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = (startX + (e.rawX - startRawX)).roundToInt().coerceIn(0, (screenW - lp.width).coerceAtLeast(0))
                    lp.y = (startY + (e.rawY - startRawY)).roundToInt().coerceIn(0, (screenH - lp.height).coerceAtLeast(0))
                    padView?.let {
                        try {
                            windowManager.updateViewLayout(it, lp)
                        } catch (ex: IllegalArgumentException) {
                            // ventana retirada
                        }
                    }
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> Fx.pressUp(v)
            }
            true
        }

        // X: cierra todo el Modo Consola y vuelve a la pantalla principal de GameCast
        view.findViewById<View>(R.id.padClose).setOnClickListener {
            ConsoleService.stop(this)
            try {
                startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: RuntimeException) {
                // sin permiso para abrir actividades desde aquí: queda la notificación
            }
        }
    }

    // ------------------------------------------------------------------ Notificación

    private fun startAsForeground() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_pad_channel), NotificationManager.IMPORTANCE_LOW
        )
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, buildNotification(), type)
    }

    private fun buildNotification(): Notification {
        val stop = PendingIntent.getService(
            this, 0, Intent(this, ConsoleService::class.java).setAction(ConsoleService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_gamepad)
            .setContentTitle(getString(R.string.notif_pad_title))
            .setContentText(getString(R.string.notif_pad_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
