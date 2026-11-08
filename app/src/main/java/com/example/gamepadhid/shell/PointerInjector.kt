package com.example.gamepadhid.shell

import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent

/**
 * Convierte "dedos virtuales" (down / move / up) en MotionEvents multitáctiles completos y los entrega a sink.
 * Es la misma lógica en el servicio de usuario y en el respaldo de la app.
 * Todos los eventos llevan el displayId de la pantalla virtual: así el juego recibe los toques y la pantalla
 * principal (donde está el mando) no.
 */
class PointerInjector(private val sink: (MotionEvent) -> Boolean) {

    private class Pointer(val id: Int, var x: Float, var y: Float)

    private val pointers = ArrayList<Pointer>()
    private var downTime = 0L

    @Synchronized
    fun down(displayId: Int, id: Int, x: Float, y: Float) {
        val existing = pointers.firstOrNull { it.id == id }
        if (existing != null) { // ya estaba pulsado: se trata como un movimiento
            existing.x = x
            existing.y = y
            send(displayId, MotionEvent.ACTION_MOVE)
            return
        }
        if (pointers.isEmpty()) downTime = SystemClock.uptimeMillis()
        pointers.add(Pointer(id, x, y))
        val index = pointers.size - 1
        val action = if (index == 0) {
            MotionEvent.ACTION_DOWN
        } else {
            MotionEvent.ACTION_POINTER_DOWN or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
        send(displayId, action)
    }

    @Synchronized
    fun move(displayId: Int, id: Int, x: Float, y: Float) {
        val pointer = pointers.firstOrNull { it.id == id } ?: return
        pointer.x = x
        pointer.y = y
        send(displayId, MotionEvent.ACTION_MOVE)
    }

    @Synchronized
    fun up(displayId: Int, id: Int) {
        val index = pointers.indexOfFirst { it.id == id }
        if (index < 0) return
        val action = if (pointers.size == 1) {
            MotionEvent.ACTION_UP
        } else {
            MotionEvent.ACTION_POINTER_UP or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        }
        send(displayId, action) // el dedo que se levanta aún va en el evento
        pointers.removeAt(index)
    }

    private fun send(displayId: Int, action: Int) {
        val count = pointers.size
        val properties = Array(count) { i ->
            MotionEvent.PointerProperties().also {
                it.id = pointers[i].id
                it.toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(count) { i ->
            MotionEvent.PointerCoords().also {
                it.x = pointers[i].x
                it.y = pointers[i].y
                it.pressure = 1f
                it.size = 1f
            }
        }
        val event = MotionEvent.obtain(
            downTime, SystemClock.uptimeMillis(), action, count, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        try {
            if (InputEvents.tag(event, displayId)) sink(event)
        } finally {
            event.recycle()
        }
    }
}

/** Pequeño puente para no repetir la llamada a InputInjection.setDisplayId. */
internal object InputEvents {
    fun tag(event: MotionEvent, displayId: Int): Boolean = InputInjection.setDisplayId(event, displayId)
}
