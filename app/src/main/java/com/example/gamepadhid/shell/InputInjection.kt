package com.example.gamepadhid.shell

import android.os.IBinder
import android.view.InputEvent
import java.lang.reflect.Method

/**
 * Inyección de eventos de entrada por reflexión (APIs ocultas). Se usa en dos sitios:
 *  - dentro del servicio de usuario de Shizuku (proceso shell): ServiceManager da el Binder de "input";
 *  - en la app, como RESPALDO: el Binder de "input" va envuelto en ShizukuBinderWrapper.
 *
 * Las firmas de IInputManager.injectInputEvent cambian entre versiones de Android (2 o 3 parámetros), por eso
 * se localiza el método por nombre y número de parámetros. Nada de esto se pudo probar en un teléfono real.
 */
object InputInjection {

    private var sink: ((InputEvent) -> Boolean)? = null
    private var displayIdMethod: Method? = null

    var description: String = "sin inicializar"
        private set

    @Volatile
    var lastError: String? = null
        private set

    /** Prepara la inyección. binderProvider devuelve el Binder del servicio "input" (con o sin envoltorio). */
    fun init(binderProvider: () -> IBinder?): Boolean {
        val errors = StringBuilder()

        // Estrategia 1: IInputManager por Binder
        try {
            val binder = binderProvider() ?: throw IllegalStateException("sin Binder de 'input'")
            val stub = Class.forName("android.hardware.input.IInputManager\$Stub")
            val iface = stub.getMethod("asInterface", IBinder::class.java).invoke(null, binder)
                ?: throw IllegalStateException("asInterface devolvió null")
            val methods = iface.javaClass.methods.filter { it.name == "injectInputEvent" }
            val two = methods.firstOrNull { it.parameterTypes.size == 2 }
            val three = methods.firstOrNull { it.parameterTypes.size == 3 }
            if (two != null) {
                sink = { ev -> two.invoke(iface, ev, 0) as? Boolean ?: true }
                description = "IInputManager.injectInputEvent(evento, modo)"
                return true
            }
            if (three != null) {
                // Android recientes: (evento, modo, targetUid); -1 = cualquier uid
                sink = { ev -> three.invoke(iface, ev, 0, -1) as? Boolean ?: true }
                description = "IInputManager.injectInputEvent(evento, modo, uid)"
                return true
            }
            throw NoSuchMethodException("injectInputEvent no encontrado")
        } catch (e: Throwable) {
            errors.append("binder: ").append(e.toString()).append("; ")
        }

        // Estrategia 2: instancia de InputManager (solo funciona dentro del proceso shell)
        try {
            val cls = Class.forName("android.hardware.input.InputManager")
            val getInstance = cls.getDeclaredMethod("getInstance")
            getInstance.isAccessible = true
            val instance = getInstance.invoke(null)
            val inject = cls.getMethod("injectInputEvent", InputEvent::class.java, Int::class.javaPrimitiveType)
            sink = { ev -> inject.invoke(instance, ev, 0) as? Boolean ?: true }
            description = "InputManager.injectInputEvent(evento, modo)"
            return true
        } catch (e: Throwable) {
            errors.append("InputManager: ").append(e.toString())
        }

        sink = null
        description = "sin método de inyección ($errors)"
        return false
    }

    /**
     * Asigna la pantalla de destino al evento. Si falla devuelve false y NO se debe inyectar: sin displayId el
     * toque iría a la pantalla principal del teléfono.
     */
    fun setDisplayId(event: InputEvent, displayId: Int): Boolean = try {
        val method = displayIdMethod
            ?: InputEvent::class.java.getMethod("setDisplayId", Int::class.javaPrimitiveType)
                .also { displayIdMethod = it }
        method.invoke(event, displayId)
        true
    } catch (e: Throwable) {
        lastError = "setDisplayId: $e"
        false
    }

    fun inject(event: InputEvent): Boolean {
        val current = sink ?: return false
        return try {
            current(event)
        } catch (e: Throwable) {
            lastError = "inject: ${e.cause ?: e}"
            false
        }
    }
}
