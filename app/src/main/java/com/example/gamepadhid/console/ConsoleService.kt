package com.example.gamepadhid.console

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.hardware.display.DisplayManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Surface
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.gamepadhid.MainActivity
import com.example.gamepadhid.R
import com.example.gamepadhid.cast.TvOutputWindow
import com.example.gamepadhid.overlay.FloatingPadService
import com.example.gamepadhid.shizuku.DiagLog
import com.example.gamepadhid.shizuku.GameCastClient
import com.example.gamepadhid.shizuku.ShizukuManager
import com.example.gamepadhid.virtual.GameLauncher
import com.example.gamepadhid.virtual.GameVirtualDisplay

/**
 * SESIÓN DEL MODO CONSOLA.
 *  1. Muestra una ventana en la TV (TvOutputWindow) con un SurfaceView.
 *  2. Crea la pantalla virtual "GameCast" apuntando DIRECTAMENTE a esa Surface (sin MediaProjection).
 *  3. Lanza el juego en la pantalla virtual y comprueba que haya respetado la pantalla secundaria.
 *  4. Arranca el mando flotante (FloatingPadService) en la pantalla principal del teléfono.
 *
 * Servicio en primer plano de tipo specialUse (con su permiso y su propiedad en el manifiesto): connectedDevice
 * falla en Android 14+ si no hay permisos de Bluetooth/USB/red concedidos.
 */
class ConsoleService : Service(), TvOutputWindow.Listener {

    companion object {
        const val ACTION_STOP = "com.example.gamepadhid.action.STOP_CONSOLE"
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_TV_DISPLAY_ID = "tv_display_id"
        private const val CHANNEL_ID = "console"
        private const val NOTIFICATION_ID = 44

        fun start(context: Context, packageName: String, tvDisplayId: Int) {
            val intent = Intent(context, ConsoleService::class.java)
                .putExtra(EXTRA_PACKAGE, packageName)
                .putExtra(EXTRA_TV_DISPLAY_ID, tvDisplayId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, ConsoleService::class.java))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var displayManager: DisplayManager
    private var window: TvOutputWindow? = null
    private var size: GameVirtualDisplay.Size? = null
    private var tvDisplayId = -1
    private var gamePackage = ""
    private var destroyed = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) {}
        override fun onDisplayChanged(displayId: Int) {}
        override fun onDisplayRemoved(displayId: Int) {
            if (displayId == tvDisplayId) {
                ConsoleSession.update(ConsoleSession.Phase.IDLE, gamePackage, getString(R.string.status_tv_lost))
                stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        displayManager = getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
        } else if (intent != null) {
            begin(intent)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        destroyed = true
        displayManager.unregisterDisplayListener(displayListener)
        FloatingPadService.stop(this)
        GameVirtualDisplay.release()
        window?.hide()
        window = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        if (ConsoleSession.active) ConsoleSession.update(ConsoleSession.Phase.IDLE, gamePackage, null)
        super.onDestroy()
    }

    // ------------------------------------------------------------------ Arranque

    private fun begin(intent: Intent) {
        if (ConsoleSession.active) return
        gamePackage = intent.getStringExtra(EXTRA_PACKAGE).orEmpty()
        tvDisplayId = intent.getIntExtra(EXTRA_TV_DISPLAY_ID, -1)
        ConsoleSession.update(ConsoleSession.Phase.STARTING, gamePackage, null)
        DiagLog.add("Modo Consola: inicio ($gamePackage, TV=$tvDisplayId)")
        startAsForeground()

        val tv = displayManager.getDisplay(tvDisplayId) ?: return fail(getString(R.string.err_display_gone))
        if (!Settings.canDrawOverlays(this)) return fail(getString(R.string.err_no_overlay))
        if (ShizukuManager.state(this) != ShizukuManager.State.READY) return fail(getString(R.string.console_err_shizuku))
        displayManager.registerDisplayListener(displayListener, handler)

        // Si el servicio de usuario no conecta, se sigue con el respaldo (BinderWrapper / pantalla creada en la app)
        GameCastClient.whenReady(this, 5000) {
            if (destroyed) return@whenReady
            val s = GameVirtualDisplay.computeSize(this)
            size = s
            val w = TvOutputWindow(this, tv, this)
            window = w
            w.show(s.width, s.height) // cuando la Surface existe, llega onSurfaceReady()
        }
    }

    override fun onSurfaceReady(surface: Surface) {
        val s = size ?: return
        if (GameVirtualDisplay.displayId >= 0) { // la Surface se recreó: se reconecta la misma pantalla
            GameVirtualDisplay.setSurface(surface)
            return
        }
        val error = GameVirtualDisplay.create(this, surface, s)
        if (error != null) {
            fail(getString(R.string.console_err_display, error))
            return
        }
        launchGame()
    }

    override fun onSurfaceLost() {
        GameVirtualDisplay.setSurface(null)
    }

    private fun launchGame() {
        GameLauncher.launchAndVerify(this, gamePackage, GameVirtualDisplay.displayId) { result ->
            if (destroyed) return@launchAndVerify
            when (result) {
                GameLauncher.Result.OK, GameLauncher.Result.UNVERIFIED -> {
                    val note = if (result == GameLauncher.Result.UNVERIFIED) getString(R.string.console_unverified) else null
                    ConsoleSession.update(ConsoleSession.Phase.RUNNING, gamePackage, note)
                    FloatingPadService.start(this)
                }
                GameLauncher.Result.NOT_ON_VIRTUAL -> {
                    ConsoleSession.update(
                        ConsoleSession.Phase.ERROR, gamePackage, getString(R.string.console_incompatible), notCompatible = true
                    )
                    stopSelf()
                }
                GameLauncher.Result.NOT_INSTALLED -> fail(getString(R.string.console_err_not_installed))
                GameLauncher.Result.FAILED -> fail(getString(R.string.console_err_launch))
            }
        }
    }

    private fun fail(message: String) {
        DiagLog.add("Modo Consola: error: $message")
        ConsoleSession.update(ConsoleSession.Phase.ERROR, gamePackage, message)
        stopSelf()
    }

    // ------------------------------------------------------------------ Notificación

    private fun startAsForeground() {
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.notif_console_channel), NotificationManager.IMPORTANCE_LOW
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
            this, 0, Intent(this, ConsoleService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE
        )
        val open = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cast)
            .setContentTitle(getString(R.string.notif_console_title))
            .setContentText(getString(R.string.notif_console_text))
            .setContentIntent(open)
            .addAction(0, getString(R.string.notif_stop), stop)
            .setOngoing(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
}
