package com.example.gamepadhid.shizuku

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Estado de Shizuku para toda la app. Shizuku NO es root: es un servicio que el usuario arranca una vez
 * (por ADB inalámbrico) y que presta permisos de shell a las apps que él autorice.
 */
object ShizukuManager {

    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"
    private const val REQUEST_CODE = 4242

    enum class State {
        NOT_INSTALLED,          // la app Shizuku no está instalada
        INSTALLED_NOT_RUNNING,  // instalada pero el servicio no está en marcha (o es demasiado antiguo)
        RUNNING_NO_PERMISSION,  // corriendo, pero GameCast aún no tiene permiso
        READY                   // listo
    }

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(State) -> Unit>()
    private var appContext: Context? = null
    private var initialized = false

    /** Registra los oyentes de Shizuku. Es seguro llamarlo varias veces. */
    fun init(context: Context) {
        if (initialized) return
        initialized = true
        appContext = context.applicationContext
        // "Sticky": si el binder ya llegó, avisa enseguida
        Shizuku.addBinderReceivedListenerSticky(Shizuku.OnBinderReceivedListener { notifyChanged() })
        Shizuku.addBinderDeadListener(Shizuku.OnBinderDeadListener { notifyChanged() })
        Shizuku.addRequestPermissionResultListener(
            Shizuku.OnRequestPermissionResultListener { _, _ -> notifyChanged() }
        )
    }

    fun isInstalled(context: Context): Boolean = try {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    } catch (e: PackageManager.NameNotFoundException) {
        false
    }

    fun state(context: Context): State {
        if (!isInstalled(context)) return State.NOT_INSTALLED
        val running = try {
            Shizuku.pingBinder()
        } catch (e: Throwable) {
            false
        }
        if (!running) return State.INSTALLED_NOT_RUNNING
        val oldVersion = try {
            Shizuku.isPreV11() // las versiones anteriores a v11 no sirven con esta API
        } catch (e: Throwable) {
            true
        }
        if (oldVersion) return State.INSTALLED_NOT_RUNNING
        val granted = try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Throwable) {
            false
        }
        return if (granted) State.READY else State.RUNNING_NO_PERMISSION
    }

    /**
     * Pide permiso a Shizuku. Devuelve false si no se pudo preguntar: el servicio no corre o el usuario ya marcó
     * "denegar y no volver a preguntar" (entonces debe autorizar GameCast desde la app Shizuku).
     */
    fun requestPermission(): Boolean = try {
        if (!Shizuku.pingBinder() || Shizuku.shouldShowRequestPermissionRationale()) {
            false
        } else {
            Shizuku.requestPermission(REQUEST_CODE)
            true
        }
    } catch (e: Throwable) {
        false
    }

    fun launchIntent(context: Context): Intent? = context.packageManager.getLaunchIntentForPackage(SHIZUKU_PACKAGE)

    fun addListener(l: (State) -> Unit) {
        listeners.add(l)
    }

    fun removeListener(l: (State) -> Unit) {
        listeners.remove(l)
    }

    /** Vuelve a calcular el estado y avisa a la UI (hilo principal). */
    fun notifyChanged() {
        val context = appContext ?: return
        main.post {
            val current = state(context)
            for (l in listeners) l(current)
        }
    }
}
