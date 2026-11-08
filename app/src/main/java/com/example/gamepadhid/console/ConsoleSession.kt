package com.example.gamepadhid.console

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

/** Estado compartido del Modo Consola (lo escriben los servicios, lo leen la pantalla de inicio y el mando). */
object ConsoleSession {

    enum class Phase { IDLE, STARTING, RUNNING, ERROR }

    @Volatile
    var phase = Phase.IDLE
        private set

    @Volatile
    var message: String? = null
        private set

    @Volatile
    var gamePackage: String? = null
        private set

    /** true si el juego no respetó la pantalla virtual y apareció en el teléfono. */
    @Volatile
    var incompatible = false
        private set

    val active: Boolean get() = phase == Phase.STARTING || phase == Phase.RUNNING

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<() -> Unit>()

    fun update(newPhase: Phase, game: String? = gamePackage, text: String? = null, notCompatible: Boolean = false) {
        phase = newPhase
        gamePackage = game
        message = text
        incompatible = notCompatible
        main.post { for (l in listeners) l() }
    }

    fun addListener(l: () -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: () -> Unit) {
        listeners.remove(l)
    }
}
