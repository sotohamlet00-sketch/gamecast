package com.example.gamepadhid.shizuku

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import rikka.shizuku.Shizuku
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Cliente del servicio de usuario (GameCastUserService), que Shizuku arranca en un proceso aparte con identidad
 * de shell. Todo se llama desde el hilo principal.
 */
object GameCastClient {

    private const val SERVICE_VERSION = 1 // súbela si cambias el AIDL: Shizuku reinicia el servicio

    private val main = Handler(Looper.getMainLooper())
    private val waiters = CopyOnWriteArraySet<(IGameCastService?) -> Unit>()
    private var binding = false

    @Volatile
    var service: IGameCastService? = null
        private set

    private fun args(context: Context) = Shizuku.UserServiceArgs(
        ComponentName(context.packageName, GameCastUserService::class.java.name)
    )
        .daemon(false)                 // se cierra cuando se desvincula
        .processNameSuffix("gamecast") // nombre del proceso del servicio
        .debuggable(false)
        .version(SERVICE_VERSION)

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, binder: IBinder) {
            main.post {
                binding = false
                service = IGameCastService.Stub.asInterface(binder)
                DiagLog.add("Servicio de usuario conectado")
                for (w in waiters) w(service)
            }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            main.post {
                service = null
                binding = false
                DiagLog.add("Servicio de usuario desconectado")
            }
        }
    }

    /** Pide a Shizuku que arranque el servicio de usuario (si no está ya). */
    fun bind(context: Context) {
        if (service != null || binding) return
        if (ShizukuManager.state(context) != ShizukuManager.State.READY) {
            DiagLog.add("bind: Shizuku no está listo")
            return
        }
        binding = true
        try {
            Shizuku.bindUserService(args(context), connection)
        } catch (e: Throwable) {
            binding = false
            DiagLog.add("bindUserService falló: $e")
        }
    }

    fun unbind(context: Context) {
        try {
            Shizuku.unbindUserService(args(context), connection, true)
        } catch (e: Throwable) {
            DiagLog.add("unbindUserService: $e")
        }
        service = null
        binding = false
    }

    /** Llama a callback con el servicio, o con null si no conecta a tiempo. */
    fun whenReady(context: Context, timeoutMs: Long, callback: (IGameCastService?) -> Unit) {
        service?.let {
            callback(it)
            return
        }
        bind(context)
        var done = false
        lateinit var waiter: (IGameCastService?) -> Unit
        val timeout = Runnable {
            if (!done) {
                done = true
                waiters.remove(waiter)
                DiagLog.add("Servicio de usuario: sin respuesta en ${timeoutMs} ms")
                callback(null)
            }
        }
        waiter = { svc ->
            if (svc != null && !done) {
                done = true
                main.removeCallbacks(timeout)
                waiters.remove(waiter)
                callback(svc)
            }
        }
        waiters.add(waiter)
        main.postDelayed(timeout, timeoutMs)
    }
}
