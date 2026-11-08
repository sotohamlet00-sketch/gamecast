package com.example.gamepadhid.cast

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.media.MediaRouter
import android.os.Handler
import android.os.Looper
import android.view.Display

/** Una pantalla o ruta de video a la que se puede enviar la imagen. */
data class CastTarget(
    val key: String,
    val name: String,
    val detail: String,
    /** Display listo para usar. null = la ruta existe pero aún no está conectada. */
    val display: Display?,
    val route: MediaRouter.RouteInfo?
) {
    val connected: Boolean get() = display != null
}

/**
 * SELECTOR DE PANTALLAS.
 *
 * Combina dos fuentes públicas de Android:
 *  - DisplayManager: pantallas secundarias aptas para "Presentation" (HDMI/USB-C,
 *    TV Miracast ya conectada, etc.).
 *  - MediaRouter (ROUTE_TYPE_LIVE_VIDEO): rutas de video que el sistema conoce.
 *
 * LIMITACIÓN IMPORTANTE: escanear y conectar TVs Miracast nuevas exige el permiso
 * CONFIGURE_WIFI_DISPLAY, reservado a apps del sistema. Una app normal solo ve las TVs que
 * el sistema ya conoce o a las que el usuario se conectó desde el panel Cast. Por eso la
 * app ofrece abrir ese panel (ver CastActivity).
 */
@Suppress("DEPRECATION")
class DisplayScanner(
    context: Context,
    private val onChanged: (List<CastTarget>) -> Unit
) {
    private val appContext = context.applicationContext
    private val displayManager = appContext.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
    private val mediaRouter = appContext.getSystemService(Context.MEDIA_ROUTER_SERVICE) as MediaRouter
    private val handler = Handler(Looper.getMainLooper())
    private var started = false

    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = refresh()
        override fun onDisplayRemoved(displayId: Int) = refresh()
        override fun onDisplayChanged(displayId: Int) = refresh()
    }

    private val routeCallback = object : MediaRouter.SimpleCallback() {
        override fun onRouteAdded(router: MediaRouter, info: MediaRouter.RouteInfo) = refresh()
        override fun onRouteRemoved(router: MediaRouter, info: MediaRouter.RouteInfo) = refresh()
        override fun onRouteChanged(router: MediaRouter, info: MediaRouter.RouteInfo) = refresh()
        override fun onRoutePresentationDisplayChanged(router: MediaRouter, info: MediaRouter.RouteInfo) = refresh()
        override fun onRouteSelected(router: MediaRouter, type: Int, info: MediaRouter.RouteInfo) = refresh()
        override fun onRouteUnselected(router: MediaRouter, type: Int, info: MediaRouter.RouteInfo) = refresh()
    }

    fun start() {
        if (started) return
        started = true
        displayManager.registerDisplayListener(displayListener, handler)
        // PERFORM_ACTIVE_SCAN pide al sistema buscar; en apps normales solo muestra lo ya conocido.
        mediaRouter.addCallback(
            MediaRouter.ROUTE_TYPE_LIVE_VIDEO,
            routeCallback,
            MediaRouter.CALLBACK_FLAG_PERFORM_ACTIVE_SCAN
        )
        refresh()
    }

    fun stop() {
        if (!started) return
        started = false
        displayManager.unregisterDisplayListener(displayListener)
        mediaRouter.removeCallback(routeCallback)
    }

    /** Reconstruye la lista y avisa a la UI. */
    fun refresh() {
        val targets = LinkedHashMap<String, CastTarget>()

        // 1) Pantallas secundarias disponibles para mostrar contenido
        for (d in displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION)) {
            if (d.displayId == Display.DEFAULT_DISPLAY) continue
            val key = "d${d.displayId}"
            targets[key] = CastTarget(key, d.name, describe(d), d, null)
        }

        // 2) Rutas de video conocidas por el sistema (pueden aportar el nombre de la TV)
        val defaultRoute = mediaRouter.defaultRoute
        for (i in 0 until mediaRouter.routeCount) {
            val route = mediaRouter.getRouteAt(i)
            if (route == defaultRoute) continue
            if ((route.supportedTypes and MediaRouter.ROUTE_TYPE_LIVE_VIDEO) == 0) continue

            val name = route.getName(appContext).toString()
            val display = route.presentationDisplay
            if (display != null) {
                val key = "d${display.displayId}"
                targets[key] = CastTarget(key, name, describe(display), display, route)
            } else {
                val key = "r$i-$name"
                val status = if (route.isConnecting) "Conectando…" else "Sin conectar"
                targets[key] = CastTarget(key, name, status, null, route)
            }
        }
        onChanged(targets.values.toList())
    }

    /**
     * Intenta seleccionar la ruta (conectar la TV). En apps sin permisos de sistema suele
     * fallar o no hacer nada; devuelve false para que la UI abra el panel Cast del sistema.
     */
    fun tryConnect(target: CastTarget): Boolean {
        val route = target.route ?: return false
        return try {
            mediaRouter.selectRoute(MediaRouter.ROUTE_TYPE_LIVE_VIDEO, route)
            true
        } catch (e: RuntimeException) { // incluye SecurityException
            false
        }
    }

    private fun describe(display: Display): String {
        val size = Point()
        display.getRealSize(size)
        return "${size.x}×${size.y} · pantalla ${display.displayId}"
    }
}
