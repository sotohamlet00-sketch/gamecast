package com.example.gamepadhid.virtual

import android.content.Context
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.RemoteException
import android.view.Surface
import com.example.gamepadhid.shizuku.DiagLog
import com.example.gamepadhid.shizuku.GameCastClient
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * LA PANTALLA VIRTUAL DEL JUEGO ("GameCast").
 * El juego se dibuja aquí y la imagen va directamente a la Surface que se le da (la ventana de la TV, o la vista
 * previa del mapeador): no hay captura ni codificación, así que no hay latencia añadida.
 *
 * Orden de intentos:
 *  1) crearla en el servicio de usuario de Shizuku (identidad de shell): es la que permite lanzar juegos ajenos;
 *  2) respaldo: crearla desde la app con la API pública. Los juegos solo se podrán lanzar en ella si Android lo permite.
 * Banderas: PUBLIC | OWN_CONTENT_ONLY | PRESENTATION (las tres son API pública de DisplayManager).
 */
object GameVirtualDisplay {

    private const val MAX_LONG_EDGE = 1920

    data class Size(val width: Int, val height: Int, val dpi: Int)

    @Volatile
    var displayId = -1
        private set

    @Volatile
    var width = 0
        private set

    @Volatile
    var height = 0
        private set

    private var localDisplay: VirtualDisplay? = null
    private var viaShell = false

    /**
     * Resolución del teléfono limitada a 1920 en el lado largo, en HORIZONTAL (la TV y casi todos los juegos son
     * horizontales) y con la densidad del teléfono (escalada).
     */
    fun computeSize(context: Context): Size {
        val metrics = ScreenInfo.realMetrics(context)
        val longSide = max(metrics.widthPixels, metrics.heightPixels)
        val shortSide = min(metrics.widthPixels, metrics.heightPixels)
        val scale = min(1f, MAX_LONG_EDGE / longSide.toFloat())
        fun even(v: Float) = (v.roundToInt() and 1.inv()).coerceAtLeast(2)
        return Size(even(longSide * scale), even(shortSide * scale), (metrics.densityDpi * scale).roundToInt().coerceAtLeast(1))
    }

    /** Devuelve null si todo fue bien, o un texto con el motivo del fallo. */
    fun create(context: Context, surface: Surface, size: Size): String? {
        release()
        width = size.width
        height = size.height

        val service = GameCastClient.service
        if (service != null) {
            try {
                val result = service.createDisplay("GameCast", size.width, size.height, size.dpi, surface)
                DiagLog.add("createDisplay (shell): $result")
                if (result.startsWith("OK:")) {
                    displayId = result.split(":")[1].toInt()
                    viaShell = true
                    return null
                }
            } catch (e: RemoteException) {
                DiagLog.add("createDisplay (shell) falló: $e")
            }
        } else {
            DiagLog.add("Sin servicio de usuario: se crea la pantalla desde la app")
        }

        return try {
            val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
            val flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY or
                DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
            val display = manager.createVirtualDisplay("GameCast", size.width, size.height, size.dpi, surface, flags)
                ?: return "DisplayManager devolvió null"
            localDisplay = display
            displayId = display.display.displayId
            viaShell = false
            DiagLog.add("createDisplay (app): id=$displayId")
            null
        } catch (e: Throwable) {
            DiagLog.add("createDisplay (app) falló: $e")
            "${e.javaClass.simpleName}: ${e.message}"
        }
    }

    /** Cambia la Surface de destino (null = pausar). */
    fun setSurface(surface: Surface?) {
        if (displayId < 0) return
        try {
            if (viaShell) GameCastClient.service?.setSurface(surface) else localDisplay?.surface = surface
        } catch (e: Throwable) {
            DiagLog.add("setSurface falló: $e")
        }
    }

    fun release() {
        if (displayId >= 0) {
            try {
                if (viaShell) GameCastClient.service?.releaseDisplay() else localDisplay?.release()
            } catch (e: Throwable) {
                DiagLog.add("release falló: $e")
            }
        }
        localDisplay = null
        displayId = -1
        viaShell = false
    }
}
