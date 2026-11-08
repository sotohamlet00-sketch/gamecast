package com.example.gamepadhid.virtual

import android.app.ActivityOptions
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.example.gamepadhid.shizuku.DiagLog
import com.example.gamepadhid.shizuku.GameCastClient

/**
 * Lanza un juego en la pantalla virtual.
 *
 * ADVERTENCIA: no todos los juegos respetan setLaunchDisplayId(). Los que fuerzan la pantalla principal (o no
 * son redimensionables y Android no los deja en una pantalla secundaria) aparecen en el teléfono. Por eso, tras
 * lanzar, se comprueba en qué pantalla quedó el juego y se avisa al usuario si no es compatible con el modo
 * "mando separado".
 *
 * Intent: getLaunchIntentForPackage + FLAG_ACTIVITY_NEW_TASK | MULTIPLE_TASK | RESET_TASK_IF_NEEDED.
 */
object GameLauncher {

    enum class Result {
        OK,              // el juego está en la pantalla virtual
        UNVERIFIED,      // se lanzó, pero no se pudo comprobar dónde quedó
        NOT_ON_VIRTUAL,  // quedó en la pantalla principal: no es compatible con el modo mando separado
        NOT_INSTALLED,
        FAILED
    }

    private const val VERIFY_DELAY_MS = 2500L
    private val handler = Handler(Looper.getMainLooper())

    fun launchAndVerify(context: Context, packageName: String, displayId: Int, callback: (Result) -> Unit) {
        val intent = context.packageManager.getLaunchIntentForPackage(packageName)
        val component = intent?.component
        if (intent == null || component == null) {
            callback(Result.NOT_INSTALLED)
            return
        }
        // 1) con identidad de shell (equivale a ActivityOptions.setLaunchDisplayId); 2) desde la app con ActivityOptions
        val launched = launchViaShell(component, displayId) || launchFromApp(context, intent, displayId)
        if (!launched) {
            callback(Result.FAILED)
            return
        }
        verify(packageName, displayId, attemptsLeft = 2, callback = callback)
    }

    private fun launchViaShell(component: ComponentName, displayId: Int): Boolean {
        val service = GameCastClient.service ?: return false
        return try {
            val result = service.launch(component.flattenToString(), displayId)
            DiagLog.add("launch (shell): $result")
            result == "OK"
        } catch (e: Throwable) {
            DiagLog.add("launch (shell) falló: $e")
            false
        }
    }

    private fun launchFromApp(context: Context, intent: Intent, displayId: Int): Boolean = try {
        intent.addFlags(
            Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK or
                Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED
        )
        val options = ActivityOptions.makeBasic().setLaunchDisplayId(displayId)
        context.startActivity(intent, options.toBundle())
        DiagLog.add("launch (app, ActivityOptions): enviado")
        true
    } catch (e: Throwable) {
        DiagLog.add("launch (app) falló: $e")
        false
    }

    private fun verify(packageName: String, displayId: Int, attemptsLeft: Int, callback: (Result) -> Unit) {
        handler.postDelayed({
            val service = GameCastClient.service
            if (service == null) {
                callback(Result.UNVERIFIED)
                return@postDelayed
            }
            val where = try {
                service.whereIsPackage(packageName, displayId)
            } catch (e: Throwable) {
                DiagLog.add("whereIsPackage falló: $e")
                -1
            }
            DiagLog.add("whereIsPackage($packageName) = $where")
            when {
                where == 0 -> callback(Result.OK)
                where == 1 -> callback(Result.NOT_ON_VIRTUAL)
                attemptsLeft > 1 -> verify(packageName, displayId, attemptsLeft - 1, callback)
                else -> callback(Result.UNVERIFIED)
            }
        }, VERIFY_DELAY_MS)
    }
}
