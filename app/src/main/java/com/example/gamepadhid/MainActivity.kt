package com.example.gamepadhid

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import com.example.gamepadhid.cast.CastTarget
import com.example.gamepadhid.cast.DisplayScanner
import com.example.gamepadhid.cast.ProjectionService
import com.example.gamepadhid.cast.TvPicker
import com.example.gamepadhid.console.ConsoleService
import com.example.gamepadhid.console.ConsoleSession
import com.example.gamepadhid.mapping.MappingStore
import com.example.gamepadhid.overlay.ControlMapperActivity
import com.example.gamepadhid.overlay.PackagePicker
import com.example.gamepadhid.shizuku.ShizukuManager
import com.example.gamepadhid.shizuku.ShizukuSetupActivity
import com.example.gamepadhid.hid.HidManager
import com.example.gamepadhid.hid.HidService
import com.example.gamepadhid.hid.HidSession
import com.example.gamepadhid.hid.HidTexts
import com.example.gamepadhid.ui.Fx
import com.example.gamepadhid.ui.applySystemBarsPadding

/**
 * Pantalla principal: tres modos independientes.
 *  - Mando HID: el teléfono es un gamepad Bluetooth (HidService).
 *  - Juego en TV: espejo simple de la pantalla del teléfono en la TV (ProjectionService).
 *  - Modo Consola (Shizuku): el juego corre en una pantalla virtual que va a la TV y el mando táctil queda
 *    en la pantalla del teléfono (ConsoleService + FloatingPadService).
 * Cada modo se enciende y se apaga por separado; los permisos se piden al encender cada uno.
 */
class MainActivity : AppCompatActivity() {

    private var pendingHidStart = false
    private var pendingTarget: CastTarget? = null
    private var targets: List<CastTarget> = emptyList()
    private lateinit var scanner: DisplayScanner

    private lateinit var dotHid: View
    private lateinit var tvHidStatus: TextView
    private lateinit var btnHidToggle: TextView
    private lateinit var dotTv: View
    private lateinit var tvTvStatus: TextView
    private lateinit var tvTvTarget: TextView
    private lateinit var btnTvToggle: TextView
    private lateinit var dotCon: View
    private lateinit var tvConStatus: TextView
    private lateinit var tvConGame: TextView
    private lateinit var btnConToggle: TextView

    private var pendingGame: String? = null
    private var lastErrorShown: String? = null

    private val hidObserver: (HidManager.State, BluetoothDevice?) -> Unit = { _, _ -> renderHid() }
    private val tvListener: (ProjectionService.SessionState, String?) -> Unit =
        { state, msg -> renderTv(state, msg) }
    private val shizukuListener: (ShizukuManager.State) -> Unit = { renderConsole() }
    private val consoleListener: () -> Unit = { renderConsole() }

    // ------------------------------------------------------------------ Lanzadores de resultados

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onPermissionsResult() }

    private val enableBluetoothLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) startHidNow() else toast(R.string.toast_bt_required)
        }

    private val notificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { launchCapture() }

    private val captureLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { onCaptureResult(it) }

    // Mapeo de controles: al guardar (RESULT_OK) se continúa con el arranque del Modo Consola
    private val mapperLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == RESULT_OK) pendingGame?.let { beginConsole(it) }
        }

    private val conNotificationLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { startConsoleNow() }

    // ------------------------------------------------------------------ Ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_main)
        findViewById<View>(R.id.root).applySystemBarsPadding()

        dotHid = findViewById(R.id.dotHid)
        tvHidStatus = findViewById(R.id.tvHidStatus)
        btnHidToggle = findViewById(R.id.btnHidToggle)
        dotTv = findViewById(R.id.dotTv)
        tvTvStatus = findViewById(R.id.tvTvStatus)
        tvTvTarget = findViewById(R.id.tvTvTarget)
        btnTvToggle = findViewById(R.id.btnTvToggle)
        dotCon = findViewById(R.id.dotCon)
        tvConStatus = findViewById(R.id.tvConStatus)
        tvConGame = findViewById(R.id.tvConGame)
        btnConToggle = findViewById(R.id.btnConToggle)
        ShizukuManager.init(applicationContext)

        scanner = DisplayScanner(this) { list ->
            targets = list
            renderTarget()
        }

        btnHidToggle.setOnClickListener {
            if (HidSession.active) HidService.stop(this) else startHidMode()
        }
        btnTvToggle.setOnClickListener {
            if (isTvActive()) ProjectionService.stop(this) else startTvMode()
        }
        btnConToggle.setOnClickListener { onConsoleToggle() }
        findViewById<View>(R.id.btnControllers).setOnClickListener { openController() }
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this, SettingsActivity::class.java))
        }

        listOf<View>(btnHidToggle, btnTvToggle, btnConToggle, findViewById<View>(R.id.btnControllers), findViewById<View>(R.id.btnSettings))
            .forEach { Fx.attachPress(it) }

        Fx.enter(listOf(R.id.header, R.id.cardHid, R.id.cardTv, R.id.cardCon, R.id.footer).map { findViewById<View>(it) })
    }

    override fun onStart() {
        super.onStart()
        scanner.start()
        HidSession.addObserver(hidObserver)
        ProjectionService.addListener(tvListener)
        if (HidSession.active && HidSession.manager(this).state == HidManager.State.REGISTRATION_FAILED) {
            HidSession.manager(this).start() // reintenta el registro del mando
        }
        ShizukuManager.addListener(shizukuListener)
        ConsoleSession.addListener(consoleListener)
        renderHid()
        renderTv(ProjectionService.state, ProjectionService.message)
        renderTarget()
        renderConsole()
    }

    override fun onResume() {
        super.onResume()
        renderConsole() // el estado de Shizuku pudo cambiar mientras estaba en su pantalla de configuración
    }

    override fun onStop() {
        ConsoleSession.removeListener(consoleListener)
        ShizukuManager.removeListener(shizukuListener)
        ProjectionService.removeListener(tvListener)
        HidSession.removeObserver(hidObserver)
        scanner.stop()
        super.onStop()
    }

    // ------------------------------------------------------------------ Modo Mando HID

    private fun startHidMode() {
        val missing = Permissions.bluetoothMissing(this) + Permissions.notificationsMissing(this)
        pendingHidStart = true
        if (missing.isEmpty()) onPermissionsResult() else permissionLauncher.launch(missing.toTypedArray())
    }

    private fun onPermissionsResult() {
        if (!pendingHidStart) return
        pendingHidStart = false
        if (!Permissions.hasBluetoothConnect(this)) { // sin este permiso no se puede usar el mando
            AlertDialog.Builder(this)
                .setMessage(R.string.dialog_bt_denied)
                .setPositiveButton(R.string.dialog_open_app_settings) { _, _ -> Permissions.openAppSettings(this) }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            return
        }
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        when {
            adapter == null -> toast(R.string.hid_no_bluetooth)
            !adapter.isEnabled -> try {
                enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
            } catch (e: ActivityNotFoundException) {
                toast(R.string.toast_enable_bt_manually)
            }
            else -> startHidNow()
        }
    }

    private fun startHidNow() {
        HidService.start(this)
        openController()
    }

    // ------------------------------------------------------------------ Modo Juego en TV

    private fun isTvActive() = ProjectionService.state == ProjectionService.SessionState.STARTING ||
        ProjectionService.state == ProjectionService.SessionState.STREAMING

    private fun startTvMode() {
        val connected = targets.filter { it.connected }
        val saved = Prefs.tvName(this)
        val target = connected.firstOrNull { it.name == saved } ?: connected.singleOrNull()
        if (target == null) {
            // Ninguna TV conectada (o varias): se elige desde el selector
            TvPicker.show(this) { picked -> beginTv(picked) }
        } else {
            beginTv(target)
        }
    }

    private fun beginTv(target: CastTarget) {
        pendingTarget = target
        when {
            !Settings.canDrawOverlays(this) -> AlertDialog.Builder(this)
                .setMessage(R.string.dialog_overlay_msg)
                .setPositiveButton(R.string.dialog_overlay_ok) { _, _ ->
                    startActivity(
                        Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))
                    )
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
            Permissions.notificationsMissing(this).isNotEmpty() ->
                notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            else -> launchCapture()
        }
    }

    private fun launchCapture() {
        val manager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14+: pantalla completa, sin la opción de compartir una sola app
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else {
            manager.createScreenCaptureIntent()
        }
        captureLauncher.launch(intent)
    }

    private fun onCaptureResult(result: ActivityResult) {
        val data = result.data
        if (result.resultCode != RESULT_OK || data == null) {
            toast(R.string.status_consent_denied)
            return
        }
        val displayId = pendingTarget?.display?.displayId
        if (displayId == null) {
            // El usuario dio permiso, pero la TV ya no está conectada
            toast(R.string.err_tv_not_connected)
            return
        }
        ProjectionService.start(this, result.resultCode, data, displayId)
        openController() // los controles táctiles siguen visibles durante el juego
    }

    // ------------------------------------------------------------------ Modo Consola (Shizuku)

    private fun onConsoleToggle() {
        when {
            ConsoleSession.active -> ConsoleService.stop(this)
            ShizukuManager.state(this) != ShizukuManager.State.READY ->
                startActivity(Intent(this, ShizukuSetupActivity::class.java)) // "Preparar Shizuku"
            else -> PackagePicker.show(this) { app -> onGamePicked(app.packageName) }
        }
    }

    /** Con mapa guardado se arranca directo; si no, primero se abre el mapeo de controles. */
    private fun onGamePicked(pkg: String) {
        pendingGame = pkg
        if (MappingStore.has(this, pkg)) {
            beginConsole(pkg)
        } else {
            mapperLauncher.launch(
                Intent(this, ControlMapperActivity::class.java)
                    .putExtra(ControlMapperActivity.EXTRA_PACKAGE, pkg)
                    .putExtra(ControlMapperActivity.EXTRA_START_AFTER, true)
            )
        }
    }

    private fun beginConsole(pkg: String) {
        pendingGame = pkg
        val connected = targets.filter { it.connected }
        val saved = Prefs.tvName(this)
        val target = connected.firstOrNull { it.name == saved } ?: connected.singleOrNull()
        if (target == null) {
            TvPicker.show(this) { picked -> proceedConsole(picked) }
        } else {
            proceedConsole(target)
        }
    }

    private fun proceedConsole(target: CastTarget) {
        pendingTarget = target
        when {
            !Settings.canDrawOverlays(this) -> showOverlaySettingsDialog(R.string.dialog_overlay_console_msg)
            Permissions.notificationsMissing(this).isNotEmpty() ->
                conNotificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
            else -> startConsoleNow()
        }
    }

    private fun startConsoleNow() {
        val pkg = pendingGame ?: return
        val displayId = pendingTarget?.display?.displayId
        if (displayId == null) {
            toast(R.string.err_tv_not_connected)
            return
        }
        ConsoleService.start(this, pkg, displayId)
        toast(R.string.toast_console_started)
        moveTaskToBack(true) // el mando flotante queda en la pantalla del teléfono
    }

    private fun showOverlaySettingsDialog(messageRes: Int) {
        AlertDialog.Builder(this)
            .setMessage(messageRes)
            .setPositiveButton(R.string.dialog_overlay_ok) { _, _ ->
                startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun renderConsole() {
        val shizuku = ShizukuManager.state(this)
        val phase = ConsoleSession.phase
        val active = ConsoleSession.active
        val ready = shizuku == ShizukuManager.State.READY

        tvConStatus.text = when {
            phase == ConsoleSession.Phase.STARTING -> getString(R.string.con_starting)
            phase == ConsoleSession.Phase.RUNNING -> ConsoleSession.message ?: getString(R.string.con_running)
            phase == ConsoleSession.Phase.ERROR -> ConsoleSession.message ?: getString(R.string.err_generic)
            !ready -> getString(R.string.con_shizuku_needed)
            else -> getString(R.string.con_ready)
        }
        val (dotColor, dotPulse) = when {
            phase == ConsoleSession.Phase.RUNNING -> color(R.color.gc_secondary) to false
            phase == ConsoleSession.Phase.STARTING -> color(R.color.gc_secondary) to true
            phase == ConsoleSession.Phase.ERROR || !ready -> color(R.color.gc_danger) to false
            else -> color(R.color.gc_text_muted) to false
        }
        Fx.setDot(dotCon, dotColor, dotPulse)

        val game = ConsoleSession.gamePackage
        tvConGame.text = getString(
            R.string.con_game,
            if (game == null) getString(R.string.con_game_none) else PackagePicker.labelOf(this, game)
        )
        btnConToggle.setText(
            when {
                active -> R.string.btn_stop_mode
                !ready -> R.string.btn_con_prepare
                else -> R.string.btn_con_start
            }
        )
        btnConToggle.setBackgroundResource(if (active) R.drawable.bg_btn_danger else R.drawable.bg_btn_primary)

        // Si algo falla (juego incompatible, API oculta que cambió...), se ofrece el espejo simple
        val error = if (phase == ConsoleSession.Phase.ERROR) ConsoleSession.message else null
        if (error != null && error != lastErrorShown) {
            lastErrorShown = error
            showConsoleFallbackDialog(error)
        }
        if (phase != ConsoleSession.Phase.ERROR) lastErrorShown = null
    }

    private fun showConsoleFallbackDialog(error: String) {
        AlertDialog.Builder(this)
            .setMessage(getString(R.string.con_fallback_msg, error))
            .setPositiveButton(R.string.con_fallback_ok) { _, _ -> startTvMode() }
            .setNeutralButton(R.string.con_fallback_diag) { _, _ ->
                startActivity(Intent(this, ShizukuSetupActivity::class.java))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    // ------------------------------------------------------------------ Pintar estado

    private fun renderHid() {
        val active = HidSession.active
        val hid = HidSession.manager(this)
        val state = hid.state
        tvHidStatus.text = if (active) {
            HidTexts.describe(this, state, HidSession.lastDevice?.let { hid.label(it) } ?: "")
        } else {
            getString(R.string.hid_off)
        }
        val (dotColor, dotPulse) = when {
            !active -> color(R.color.gc_text_muted) to false
            state == HidManager.State.CONNECTED -> color(R.color.gc_secondary) to false
            state == HidManager.State.READY || state == HidManager.State.CONNECTING ||
                state == HidManager.State.IDLE -> color(R.color.gc_secondary) to true
            else -> color(R.color.gc_danger) to false
        }
        Fx.setDot(dotHid, dotColor, dotPulse)
        styleToggle(btnHidToggle, active)
    }

    private fun renderTv(state: ProjectionService.SessionState, msg: String?) {
        val active = state == ProjectionService.SessionState.STARTING ||
            state == ProjectionService.SessionState.STREAMING
        tvTvStatus.text = msg ?: getString(
            when (state) {
                ProjectionService.SessionState.IDLE -> R.string.status_idle
                ProjectionService.SessionState.STARTING -> R.string.status_starting
                ProjectionService.SessionState.STREAMING -> R.string.status_streaming_generic
                ProjectionService.SessionState.ERROR -> R.string.err_generic
            }
        )
        val (dotColor, dotPulse) = when (state) {
            ProjectionService.SessionState.IDLE -> color(R.color.gc_text_muted) to false
            ProjectionService.SessionState.STARTING -> color(R.color.gc_secondary) to true
            ProjectionService.SessionState.STREAMING -> color(R.color.gc_secondary) to false
            ProjectionService.SessionState.ERROR -> color(R.color.gc_danger) to false
        }
        Fx.setDot(dotTv, dotColor, dotPulse)
        styleToggle(btnTvToggle, active)
    }

    private fun renderTarget() {
        tvTvTarget.text = getString(R.string.tv_target, Prefs.tvName(this) ?: getString(R.string.tv_none))
    }

    /** Botón principal del modo: "Iniciar" (degradado índigo) o "Detener" (degradado coral). */
    private fun styleToggle(button: TextView, active: Boolean) {
        button.setText(if (active) R.string.btn_stop_mode else R.string.btn_start_mode)
        button.setBackgroundResource(if (active) R.drawable.bg_btn_danger else R.drawable.bg_btn_primary)
    }

    private fun color(id: Int) = ContextCompat.getColor(this, id)

    private fun openController() = startActivity(Intent(this, ControllerActivity::class.java))

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_LONG).show()
}
