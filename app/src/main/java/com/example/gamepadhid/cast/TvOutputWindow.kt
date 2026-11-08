package com.example.gamepadhid.cast

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.os.Build
import android.view.Display
import android.view.Gravity
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.WindowManager
import android.widget.FrameLayout
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Ventana negra a pantalla completa sobre la pantalla externa (la TV) con un SurfaceView
 * centrado que respeta la proporción de la captura (bandas negras si hace falta).
 *
 * El VirtualDisplay de MediaProjection escribe DIRECTAMENTE en la Surface de este
 * SurfaceView: no hay copias intermedias ni codificación propia.
 *
 * Se dibuja como ventana de tipo TYPE_APPLICATION_OVERLAY (permiso SYSTEM_ALERT_WINDOW)
 * para que siga visible aunque la app esté en segundo plano (p. ej. mientras juegas).
 */
@Suppress("DEPRECATION")
class TvOutputWindow(
    private val serviceContext: Context,
    private val display: Display,
    private val listener: Listener
) {
    interface Listener {
        fun onSurfaceReady(surface: Surface)
        fun onSurfaceLost()
    }

    private var windowManager: WindowManager? = null
    private var root: FrameLayout? = null
    private var surfaceView: SurfaceView? = null
    private var captureW = 0
    private var captureH = 0

    fun show(captureWidth: Int, captureHeight: Int) {
        captureW = captureWidth
        captureH = captureHeight

        // Contexto asociado a la pantalla externa
        val windowContext: Context =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                serviceContext.createWindowContext(
                    display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null
                )
            } else {
                serviceContext.createDisplayContext(display)
            }
        val wm = windowContext.getSystemService(WindowManager::class.java)

        val sv = SurfaceView(windowContext)
        sv.holder.setFixedSize(captureW, captureH)
        sv.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) = listener.onSurfaceReady(holder.surface)
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
            override fun surfaceDestroyed(holder: SurfaceHolder) = listener.onSurfaceLost()
        })

        val frame = FrameLayout(windowContext).apply {
            setBackgroundColor(Color.BLACK)
            addView(sv, fitParams())
        }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.OPAQUE
        )
        wm.addView(frame, params)

        windowManager = wm
        root = frame
        surfaceView = sv
    }

    /** Cambia la resolución de captura (p. ej. al girar el teléfono). */
    fun resize(captureWidth: Int, captureHeight: Int) {
        captureW = captureWidth
        captureH = captureHeight
        val sv = surfaceView ?: return
        sv.holder.setFixedSize(captureW, captureH)
        sv.layoutParams = fitParams()
    }

    fun hide() {
        val view = root
        if (view != null) {
            try {
                windowManager?.removeViewImmediate(view)
            } catch (e: IllegalArgumentException) {
                // la ventana ya no existía (pantalla desconectada)
            }
        }
        root = null
        surfaceView = null
        windowManager = null
    }

    /** Escala la captura para que quepa entera en la TV sin deformarse. */
    private fun fitParams(): FrameLayout.LayoutParams {
        val size = Point()
        display.getRealSize(size)
        val scale = min(size.x.toFloat() / captureW, size.y.toFloat() / captureH)
        return FrameLayout.LayoutParams(
            (captureW * scale).roundToInt(),
            (captureH * scale).roundToInt(),
            Gravity.CENTER
        )
    }
}
