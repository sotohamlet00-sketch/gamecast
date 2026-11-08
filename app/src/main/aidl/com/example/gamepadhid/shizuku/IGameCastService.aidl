package com.example.gamepadhid.shizuku;

import android.view.Surface;

/**
 * Servicio de usuario de Shizuku: se ejecuta en un PROCESO SEPARADO con la identidad de shell (uid 2000).
 * Todo lo que necesita permisos de shell (crear la pantalla virtual, lanzar el juego en ella, inyectar toques)
 * ocurre aquí. Los números son los códigos de transacción.
 */
interface IGameCastService {
    // Reservado por Shizuku: se llama para terminar el servicio.
    void destroy() = 16777114;

    // Datos de diagnóstico: uid, versión de Android, si recibió Context.
    String ping() = 1;

    // Crea la pantalla virtual "GameCast". Devuelve "OK:<displayId>:<flags>" o "ERR:<motivo>".
    String createDisplay(String name, int width, int height, int dpi, in Surface surface) = 2;

    // Cambia (o quita, con null) la Surface de destino de la pantalla virtual.
    void setSurface(in Surface surface) = 3;

    void releaseDisplay() = 4;

    // Lanza "paquete/clase" en esa pantalla. Devuelve "OK" o "ERR:<motivo>".
    String launch(String component, int displayId) = 5;

    // 0 = el juego está en displayId, 1 = está en otra pantalla (la principal), -1 = no se encontró.
    int whereIsPackage(String packageName, int displayId) = 6;

    // Toques multitáctiles en la pantalla indicada. oneway = no bloquean al mando.
    oneway void pointerDown(int displayId, int pointerId, float x, float y) = 7;
    oneway void pointerMove(int displayId, int pointerId, float x, float y) = 8;
    oneway void pointerUp(int displayId, int pointerId) = 9;

    // Qué método de inyección quedó activo (para diagnóstico).
    String inputBackend() = 10;
}
