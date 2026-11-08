package com.example.gamepadhid.virtual

import android.os.Handler
import android.os.Looper
import com.example.gamepadhid.shizuku.DiagLog
import com.example.gamepadhid.shizuku.GameCastClient

/**
 * Inyección de toques EN LA PANTALLA VIRTUAL (nunca en la principal: cada evento lleva el displayId).
 * Coordenadas en PÍXELES de la pantalla virtual. Varios pointerId distintos = varios dedos a la vez.
 *
 * Vía principal: servicio de usuario de Shizuku (llamadas oneway). Respaldo: ShizukuBinderWrapper desde la app.
 * Nota de API: las firmas de IInputManager.injectInputEvent y InputEvent.setDisplayId son APIs ocultas que
 * cambian entre Android 13, 14, 15 y 16; si fallan, queda el motivo en el diagnóstico y el modo espejo simple
 * ("Juego en TV") sigue disponible como alternativa.
 */
object TouchInjectorShizuku {

    private const val TAP_POINTER_ID = 30
    private const val TAP_MS = 40L
    private val handler = Handler(Looper.getMainLooper())
    private var loggedFailure = false

    fun tap(displayId: Int, x: Float, y: Float) {
        holdDown(displayId, x, y, TAP_POINTER_ID)
        handler.postDelayed({ release(displayId, TAP_POINTER_ID) }, TAP_MS)
    }

    fun holdDown(displayId: Int, x: Float, y: Float, pointerId: Int) {
        val service = GameCastClient.service
        if (service != null) call { service.pointerDown(displayId, pointerId, x, y) }
        else BinderInputBackend.down(displayId, pointerId, x, y)
    }

    fun moveTo(displayId: Int, x: Float, y: Float, pointerId: Int) {
        val service = GameCastClient.service
        if (service != null) call { service.pointerMove(displayId, pointerId, x, y) }
        else BinderInputBackend.move(displayId, pointerId, x, y)
    }

    fun release(displayId: Int, pointerId: Int) {
        val service = GameCastClient.service
        if (service != null) call { service.pointerUp(displayId, pointerId) }
        else BinderInputBackend.up(displayId, pointerId)
    }

    private inline fun call(block: () -> Unit) {
        try {
            block()
        } catch (e: Throwable) {
            if (!loggedFailure) {
                loggedFailure = true
                DiagLog.add("Inyección de toques falló: $e")
            }
        }
    }
}
