package com.example.gamepadhid.shizuku

import android.content.Context
import android.content.ContextWrapper
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.os.IBinder
import android.system.Os
import android.view.Surface
import androidx.annotation.Keep
import com.example.gamepadhid.shell.InputInjection
import com.example.gamepadhid.shell.PointerInjector
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/**
 * SERVICIO DE USUARIO DE SHIZUKU. Shizuku lo instancia en un proceso aparte con identidad de shell (uid 2000)
 * usando este APK. Por eso aquí SÍ hay permisos para crear pantallas, lanzar actividades y inyectar eventos.
 *
 * Todo usa reflexión sobre APIs ocultas (no hay otra vía). Cada paso devuelve un texto de diagnóstico y nunca
 * lanza excepciones hacia la app. Nada de esto se pudo probar en un teléfono real: puede necesitar ajustes en
 * Android 13, 14, 15 o 16.
 *
 * Shizuku exige: constructor sin argumentos o con Context, y el método destroy() (código 16777114).
 */
@Keep
class GameCastUserService() : IGameCastService.Stub() {

    private var context: Context? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var inputReady = false
    private val injector = PointerInjector { InputInjection.inject(it) }

    @Keep
    constructor(context: Context) : this() {
        this.context = context
    }

    private companion object {
        // Banderas PÚBLICAS de DisplayManager (las que pide el diseño)
        const val FLAGS_BASE = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION or
            DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY

        // Banderas OCULTAS de AOSP (valores recordados: si no coinciden, el intento falla y se prueba el siguiente)
        const val FLAG_SUPPORTS_TOUCH = 1 shl 6
        const val FLAG_DESTROY_CONTENT_ON_REMOVAL = 1 shl 8
        const val FLAG_TRUSTED = 1 shl 10               // Android 13+: pantalla de confianza (permite lanzar juegos ajenos)
        const val FLAG_OWN_DISPLAY_GROUP = 1 shl 11
        const val FLAG_ALWAYS_UNLOCKED = 1 shl 12
        const val FLAG_TOUCH_FEEDBACK_DISABLED = 1 shl 13

        const val INTENT_FLAGS = "0x18200000" // NEW_TASK | MULTIPLE_TASK | RESET_TASK_IF_NEEDED
    }

    // ------------------------------------------------------------------ Shizuku

    override fun destroy() {
        releaseDisplayInternal()
        exitProcess(0)
    }

    override fun ping(): String =
        "uid=${Os.getuid()} sdk=${Build.VERSION.SDK_INT} context=${context != null}"

    // ------------------------------------------------------------------ Pantalla virtual

    override fun createDisplay(name: String?, width: Int, height: Int, dpi: Int, surface: Surface?): String =
        try {
            releaseDisplayInternal()
            doCreateDisplay(name ?: "GameCast", width, height, dpi, surface)
        } catch (e: Throwable) {
            "ERR:${e.javaClass.simpleName}: ${e.message}"
        }

    private fun doCreateDisplay(name: String, width: Int, height: Int, dpi: Int, surface: Surface?): String {
        val manager = newDisplayManager()
        val attempts = ArrayList<Int>()
        if (Build.VERSION.SDK_INT >= 33) {
            attempts.add(
                FLAGS_BASE or FLAG_SUPPORTS_TOUCH or FLAG_DESTROY_CONTENT_ON_REMOVAL or FLAG_TRUSTED or
                    FLAG_OWN_DISPLAY_GROUP or FLAG_ALWAYS_UNLOCKED or FLAG_TOUCH_FEEDBACK_DISABLED
            )
        }
        attempts.add(FLAGS_BASE or FLAG_SUPPORTS_TOUCH or FLAG_DESTROY_CONTENT_ON_REMOVAL)
        attempts.add(FLAGS_BASE)

        val errors = StringBuilder()
        for (flags in attempts) {
            val hex = "0x" + Integer.toHexString(flags)
            try {
                val display = manager.createVirtualDisplay(name, width, height, dpi, surface, flags)
                if (display != null) {
                    virtualDisplay = display
                    return "OK:${display.display.displayId}:$hex"
                }
                errors.append("$hex devolvió null; ")
            } catch (e: Throwable) {
                errors.append("$hex ${e.javaClass.simpleName}: ${e.message}; ")
            }
        }
        return "ERR:$errors"
    }

    /** DisplayManager tiene un constructor oculto que recibe el Context; así se crea desde el proceso shell. */
    private fun newDisplayManager(): DisplayManager {
        val constructor = DisplayManager::class.java.getDeclaredConstructor(Context::class.java)
        constructor.isAccessible = true
        return constructor.newInstance(shellContext())
    }

    /**
     * El servidor comprueba que el nombre de paquete de quien crea la pantalla corresponda a su uid (shell).
     * Se fuerza "com.android.shell" como paquete de operaciones del Context base.
     */
    private fun shellContext(): Context {
        val base = context ?: systemContext()
        var inner: Context = base
        while (inner is ContextWrapper) inner = inner.baseContext
        try {
            val field = inner.javaClass.getDeclaredField("mOpPackageName")
            field.isAccessible = true
            field.set(inner, "com.android.shell")
        } catch (e: Throwable) {
            // si no existe el campo, se sigue con el Context tal cual
        }
        return base
    }

    private fun systemContext(): Context {
        val activityThread = Class.forName("android.app.ActivityThread")
        val current = activityThread.getMethod("currentActivityThread").invoke(null)
            ?: activityThread.getMethod("systemMain").invoke(null)
            ?: throw IllegalStateException("sin ActivityThread")
        return activityThread.getMethod("getSystemContext").invoke(current) as Context
    }

    override fun setSurface(surface: Surface?) {
        try {
            virtualDisplay?.surface = surface
        } catch (e: Throwable) {
            // la pantalla ya no existe
        }
    }

    override fun releaseDisplay() = releaseDisplayInternal()

    private fun releaseDisplayInternal() {
        try {
            virtualDisplay?.release()
        } catch (e: Throwable) {
            // ya liberada
        }
        virtualDisplay = null
    }

    // ------------------------------------------------------------------ Lanzar el juego

    override fun launch(component: String?, displayId: Int): String {
        if (component.isNullOrEmpty()) return "ERR:sin componente"
        // Equivale a ActivityOptions.setLaunchDisplayId(displayId) con identidad de shell
        val result = runShell(
            arrayOf("am", "start", "--display", displayId.toString(), "-f", INTENT_FLAGS, "-n", component),
            10
        )
        return if (result.exit == 0 && !result.text.contains("Error")) "OK" else "ERR:${result.text.trim().take(300)}"
    }

    /** Busca el paquete en la salida de "dumpsys activity activities", que agrupa las tareas por pantalla. */
    override fun whereIsPackage(packageName: String?, displayId: Int): Int {
        if (packageName.isNullOrEmpty()) return -1
        val out = runShell(arrayOf("dumpsys", "activity", "activities"), 15).text
        val displayHeader = Regex("""Display #(\d+)""")
        var current = -1
        var onTarget = false
        var elsewhere = false
        for (line in out.lineSequence()) {
            val match = displayHeader.find(line)
            if (match != null) {
                current = match.groupValues[1].toInt()
                continue
            }
            if (line.contains("ActivityRecord{") && line.contains(" $packageName/")) {
                if (current == displayId) onTarget = true else elsewhere = true
            }
        }
        return if (onTarget) 0 else if (elsewhere) 1 else -1
    }

    private class ShellResult(val exit: Int, val text: String)

    private fun runShell(command: Array<String>, timeoutSeconds: Long): ShellResult = try {
        val process = ProcessBuilder(*command).redirectErrorStream(true).start()
        val text = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(timeoutSeconds, TimeUnit.SECONDS)) {
            process.destroy()
            ShellResult(-1, "tiempo agotado: ${command.joinToString(" ")}")
        } else {
            ShellResult(process.exitValue(), text)
        }
    } catch (e: Throwable) {
        ShellResult(-1, "${e.javaClass.simpleName}: ${e.message}")
    }

    // ------------------------------------------------------------------ Toques

    private fun ensureInput() {
        if (inputReady) return
        inputReady = true
        InputInjection.init { serviceBinder("input") }
    }

    private fun serviceBinder(name: String): IBinder? = try {
        Class.forName("android.os.ServiceManager")
            .getMethod("getService", String::class.java)
            .invoke(null, name) as? IBinder
    } catch (e: Throwable) {
        null
    }

    override fun pointerDown(displayId: Int, pointerId: Int, x: Float, y: Float) {
        ensureInput()
        injector.down(displayId, pointerId, x, y)
    }

    override fun pointerMove(displayId: Int, pointerId: Int, x: Float, y: Float) {
        injector.move(displayId, pointerId, x, y)
    }

    override fun pointerUp(displayId: Int, pointerId: Int) {
        injector.up(displayId, pointerId)
    }

    override fun inputBackend(): String {
        ensureInput()
        return InputInjection.description + (InputInjection.lastError?.let { " | último error: $it" } ?: "")
    }
}
