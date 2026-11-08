package com.example.gamepadhid.virtual

import android.content.pm.PackageManager
import android.os.Build
import com.example.gamepadhid.shell.InputInjection
import com.example.gamepadhid.shell.PointerInjector
import com.example.gamepadhid.shizuku.DiagLog
import org.lsposed.hiddenapibypass.HiddenApiBypass
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuBinderWrapper
import rikka.shizuku.SystemServiceHelper

/**
 * RESPALDO de inyección de toques: se usa solo si el servicio de usuario de Shizuku no está disponible.
 * Llama a IInputManager desde la app a través de ShizukuBinderWrapper (las llamadas se ejecutan con permisos de
 * shell). Como la reflexión sobre APIs ocultas ocurre en el proceso de la app, hace falta HiddenApiBypass (LSPosed).
 */
object BinderInputBackend {

    private var injector: PointerInjector? = null
    private var tried = false

    private fun ensure(): PointerInjector? {
        injector?.let { return it }
        if (tried) return null
        tried = true
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) HiddenApiBypass.addHiddenApiExemptions("")
            if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                DiagLog.add("Respaldo BinderWrapper: Shizuku no está listo")
                return null
            }
            val ok = InputInjection.init {
                SystemServiceHelper.getSystemService("input")?.let { ShizukuBinderWrapper(it) }
            }
            DiagLog.add("Respaldo BinderWrapper: ${InputInjection.description}")
            if (ok) injector = PointerInjector { InputInjection.inject(it) }
        } catch (e: Throwable) {
            DiagLog.add("Respaldo BinderWrapper falló: $e")
        }
        return injector
    }

    fun isAvailable(): Boolean = ensure() != null

    fun down(displayId: Int, id: Int, x: Float, y: Float) { ensure()?.down(displayId, id, x, y) }

    fun move(displayId: Int, id: Int, x: Float, y: Float) { ensure()?.move(displayId, id, x, y) }

    fun up(displayId: Int, id: Int) { ensure()?.up(displayId, id) }
}
