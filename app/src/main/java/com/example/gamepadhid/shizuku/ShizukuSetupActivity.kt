package com.example.gamepadhid.shizuku

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.example.gamepadhid.R
import com.example.gamepadhid.ui.Fx
import com.example.gamepadhid.ui.applySystemBarsPadding

/**
 * Onboarding de Shizuku: qué es, para qué lo usa GameCast, cómo activarlo y estado en vivo.
 * Cuando está READY se habilita "Continuar".
 */
class ShizukuSetupActivity : AppCompatActivity() {

    private lateinit var dot: View
    private lateinit var tvStatus: TextView
    private lateinit var btnInstall: TextView
    private lateinit var btnOpen: TextView
    private lateinit var btnPermission: TextView
    private lateinit var btnTest: TextView
    private lateinit var btnContinue: TextView
    private lateinit var tvDiag: TextView

    private val listener: (ShizukuManager.State) -> Unit = { render(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_shizuku_setup)
        findViewById<View>(R.id.root).applySystemBarsPadding()
        ShizukuManager.init(applicationContext)

        dot = findViewById(R.id.dotShizuku)
        tvStatus = findViewById(R.id.tvShizukuStatus)
        btnInstall = findViewById(R.id.btnShizukuInstall)
        btnOpen = findViewById(R.id.btnShizukuOpen)
        btnPermission = findViewById(R.id.btnShizukuPermission)
        btnTest = findViewById(R.id.btnShizukuTest)
        btnContinue = findViewById(R.id.btnShizukuContinue)
        tvDiag = findViewById(R.id.tvDiag)

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        btnInstall.setOnClickListener { installShizuku() }
        btnOpen.setOnClickListener {
            val intent = ShizukuManager.launchIntent(this)
            if (intent != null) startActivity(intent) else toast(R.string.shizuku_not_installed_toast)
        }
        btnPermission.setOnClickListener {
            if (!ShizukuManager.requestPermission()) toast(R.string.shizuku_permission_manual)
        }
        btnTest.setOnClickListener { runTest() }
        btnContinue.setOnClickListener { finish() }
        findViewById<View>(R.id.btnShizukuGuide).setOnClickListener {
            openUrl("https://shizuku.rikka.app/")
        }
        findViewById<View>(R.id.btnCopyDiag).setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("GameCast diagnóstico", DiagLog.dump()))
            toast(R.string.shizuku_diag_copied)
        }
        listOf<View>(btnInstall, btnOpen, btnPermission, btnTest, btnContinue)
            .forEach { Fx.attachPress(it) }
    }

    override fun onStart() {
        super.onStart()
        ShizukuManager.addListener(listener)
        render(ShizukuManager.state(this))
    }

    override fun onStop() {
        ShizukuManager.removeListener(listener)
        super.onStop()
    }

    private fun render(state: ShizukuManager.State) {
        tvStatus.setText(
            when (state) {
                ShizukuManager.State.NOT_INSTALLED -> R.string.shizuku_state_not_installed
                ShizukuManager.State.INSTALLED_NOT_RUNNING -> R.string.shizuku_state_not_running
                ShizukuManager.State.RUNNING_NO_PERMISSION -> R.string.shizuku_state_no_permission
                ShizukuManager.State.READY -> R.string.shizuku_state_ready
            }
        )
        val ready = state == ShizukuManager.State.READY
        Fx.setDot(
            dot,
            ContextCompat.getColor(this, if (ready) R.color.gc_secondary else R.color.gc_danger),
            state == ShizukuManager.State.RUNNING_NO_PERMISSION
        )
        btnInstall.visibility = if (state == ShizukuManager.State.NOT_INSTALLED) View.VISIBLE else View.GONE
        btnOpen.visibility = if (state == ShizukuManager.State.NOT_INSTALLED) View.GONE else View.VISIBLE
        btnPermission.visibility = if (state == ShizukuManager.State.RUNNING_NO_PERMISSION) View.VISIBLE else View.GONE
        btnTest.visibility = if (ready) View.VISIBLE else View.GONE
        Fx.setEnabled(btnContinue, ready)
        tvDiag.text = DiagLog.dump()
    }

    /** Arranca el servicio de usuario y anota en el diagnóstico lo que responde. */
    private fun runTest() {
        DiagLog.add("Prueba: conectando con el servicio de usuario…")
        tvDiag.text = DiagLog.dump()
        GameCastClient.whenReady(this, 6000) { service ->
            if (service != null) {
                try {
                    DiagLog.add("ping: ${service.ping()}")
                    DiagLog.add("entrada: ${service.inputBackend()}")
                } catch (e: Throwable) {
                    DiagLog.add("La prueba falló: $e")
                }
            }
            tvDiag.text = DiagLog.dump()
        }
    }

    private fun installShizuku() {
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=${ShizukuManager.SHIZUKU_PACKAGE}"))
            )
        } catch (e: ActivityNotFoundException) {
            openUrl("https://github.com/RikkaApps/Shizuku/releases") // sin Play Store: descarga desde GitHub
        }
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (e: ActivityNotFoundException) {
            toast(R.string.shizuku_no_browser)
        }
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_LONG).show()
}
