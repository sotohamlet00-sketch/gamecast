package com.example.gamepadhid.overlay

import android.os.Bundle
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.example.gamepadhid.R
import com.example.gamepadhid.console.ConsoleSession
import com.example.gamepadhid.mapping.ControlIds
import com.example.gamepadhid.mapping.ControlType
import com.example.gamepadhid.mapping.GameMapping
import com.example.gamepadhid.mapping.MappingStore
import com.example.gamepadhid.shizuku.GameCastClient
import com.example.gamepadhid.shizuku.ShizukuManager
import com.example.gamepadhid.ui.AspectFrameLayout
import com.example.gamepadhid.ui.Fx
import com.example.gamepadhid.ui.applySystemBarsPadding
import com.example.gamepadhid.virtual.GameLauncher
import com.example.gamepadhid.virtual.GameVirtualDisplay
import com.example.gamepadhid.virtual.TouchInjectorShizuku

/**
 * MAPEO DE CONTROLES POR JUEGO.
 * Abre el juego en la pantalla virtual y la muestra EN DIRECTO en esta pantalla (el propio SurfaceView es el
 * destino de la pantalla virtual). Encima se colocan los marcadores del mando, que se arrastran hasta los botones
 * reales del juego. Con "Jugar" los toques van al juego para poder llegar a la pantalla donde se juega.
 * Se guarda como mapping_<paquete>.json con coordenadas en porcentaje de la pantalla virtual.
 */
class ControlMapperActivity : AppCompatActivity(), SurfaceHolder.Callback {

    companion object {
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_START_AFTER = "start_after" // al guardar, devuelve RESULT_OK para que Inicio arranque la consola
        private const val PLAY_POINTER_ID = 20
        private const val STEP = 0.01f
    }

    private lateinit var bar: View
    private lateinit var surfaceView: SurfaceView
    private lateinit var mapper: MapperView
    private lateinit var tvStatus: TextView
    private lateinit var tvGame: TextView
    private lateinit var btnMode: TextView
    private lateinit var size: GameVirtualDisplay.Size

    private var packageName = ""
    private lateinit var mapping: GameMapping
    private var dirty = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (ConsoleSession.active) { // la pantalla virtual es única: no se puede mapear con el Modo Consola en marcha
            Toast.makeText(this, R.string.mapper_console_running, Toast.LENGTH_LONG).show()
            finish()
            return
        }
        ShizukuManager.init(applicationContext)
        if (ShizukuManager.state(this) != ShizukuManager.State.READY) {
            Toast.makeText(this, R.string.mapper_needs_shizuku, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_mapper)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            hide(WindowInsetsCompat.Type.systemBars())
        }

        bar = findViewById(R.id.bar)
        surfaceView = findViewById(R.id.previewSurface)
        mapper = findViewById(R.id.mapper)
        tvStatus = findViewById(R.id.tvMapperStatus)
        tvGame = findViewById(R.id.tvMapperGame)
        btnMode = findViewById(R.id.btnMapperMode)
        bar.applySystemBarsPadding(extraDp = 4)

        size = GameVirtualDisplay.computeSize(this)
        findViewById<AspectFrameLayout>(R.id.stage).aspect = size.width.toFloat() / size.height
        surfaceView.holder.setFixedSize(size.width, size.height)
        surfaceView.holder.addCallback(this)

        mapper.onPlayTouch = { action, fx, fy ->
            val x = fx * size.width
            val y = fy * size.height
            val id = GameVirtualDisplay.displayId
            if (id >= 0) {
                when (action) {
                    MotionEvent.ACTION_DOWN -> TouchInjectorShizuku.holdDown(id, x, y, PLAY_POINTER_ID)
                    MotionEvent.ACTION_MOVE -> TouchInjectorShizuku.moveTo(id, x, y, PLAY_POINTER_ID)
                    else -> TouchInjectorShizuku.release(id, PLAY_POINTER_ID)
                }
            }
        }
        mapper.onEdited = { dirty = true }

        btnMode.setOnClickListener { toggleMode() }
        findViewById<View>(R.id.btnMapperAdd).setOnClickListener { addControl() }
        findViewById<View>(R.id.btnMapperDelete).setOnClickListener { deleteSelected() }
        findViewById<View>(R.id.btnMapperSmaller).setOnClickListener { resizeSelected(-STEP) }
        findViewById<View>(R.id.btnMapperBigger).setOnClickListener { resizeSelected(STEP) }
        findViewById<View>(R.id.btnMapperTemplate).setOnClickListener { confirmTemplate() }
        findViewById<View>(R.id.btnMapperGame).setOnClickListener { pickGame() }
        findViewById<View>(R.id.btnMapperSave).setOnClickListener { save() }
        findViewById<View>(R.id.btnMapperExit).setOnClickListener { confirmExit() }
        findViewById<View>(R.id.btnMapperBarToggle).setOnClickListener {
            bar.visibility = if (bar.visibility == View.VISIBLE) View.GONE else View.VISIBLE
        }
        listOf(
            R.id.btnMapperMode, R.id.btnMapperAdd, R.id.btnMapperDelete, R.id.btnMapperSmaller, R.id.btnMapperBigger,
            R.id.btnMapperTemplate, R.id.btnMapperGame, R.id.btnMapperSave, R.id.btnMapperExit
        ).forEach { Fx.attachPress(findViewById<View>(it)) }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = confirmExit()
        })

        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        if (pkg != null) {
            setPackage(pkg)
        } else {
            PackagePicker.show(this) { setPackage(it.packageName) }
        }
        updateMode()
    }

    // ------------------------------------------------------------------ Vista previa (pantalla virtual en directo)

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (packageName.isNotEmpty()) startPreview(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        GameVirtualDisplay.release()
    }

    private fun setPackage(pkg: String) {
        packageName = pkg
        mapping = MappingStore.load(this, pkg) ?: GameMapping.template(pkg, PackagePicker.labelOf(this, pkg))
        mapping.label = PackagePicker.labelOf(this, pkg)
        mapper.mapping = mapping
        mapper.selectedId = null
        tvGame.text = mapping.label
        if (surfaceView.holder.surface?.isValid == true) startPreview(surfaceView.holder.surface)
    }

    private fun startPreview(surface: android.view.Surface) {
        tvStatus.setText(R.string.mapper_connecting)
        GameCastClient.whenReady(this, 5000) {
            if (isFinishing || isDestroyed) return@whenReady
            val error = GameVirtualDisplay.create(this, surface, size)
            if (error != null) {
                tvStatus.text = getString(R.string.console_err_display, error)
                return@whenReady
            }
            tvStatus.setText(R.string.mapper_launching)
            GameLauncher.launchAndVerify(this, packageName, GameVirtualDisplay.displayId) { result ->
                if (isFinishing || isDestroyed) return@launchAndVerify
                tvStatus.setText(
                    when (result) {
                        GameLauncher.Result.OK -> R.string.mapper_ready
                        GameLauncher.Result.UNVERIFIED -> R.string.mapper_unverified
                        GameLauncher.Result.NOT_ON_VIRTUAL -> R.string.console_incompatible
                        GameLauncher.Result.NOT_INSTALLED -> R.string.console_err_not_installed
                        GameLauncher.Result.FAILED -> R.string.console_err_launch
                    }
                )
            }
        }
    }

    // ------------------------------------------------------------------ Edición

    private fun toggleMode() {
        mapper.mode = if (mapper.mode == MapperView.Mode.PLACE) MapperView.Mode.PLAY else MapperView.Mode.PLACE
        updateMode()
    }

    private fun updateMode() {
        btnMode.setText(if (mapper.mode == MapperView.Mode.PLACE) R.string.mapper_mode_place else R.string.mapper_mode_play)
    }

    private fun addControl() {
        val used = mapping.controls.map { it.id }.toSet()
        val missing = (ControlIds.BUTTONS + ControlIds.STICKS).filter { it !in used }
        if (missing.isEmpty()) {
            Toast.makeText(this, R.string.mapper_all_present, Toast.LENGTH_SHORT).show()
            return
        }
        AlertDialog.Builder(this)
            .setTitle(R.string.mapper_add_title)
            .setItems(missing.toTypedArray()) { _, which ->
                val id = missing[which]
                val template = GameMapping.template("", "").find(id)
                if (template != null) mapping.controls.add(template.copy())
                mapper.selectedId = id
                mapper.invalidate()
                dirty = true
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun deleteSelected() {
        val id = mapper.selectedId ?: return Toast.makeText(this, R.string.mapper_select_first, Toast.LENGTH_SHORT).show()
        mapping.controls.removeAll { it.id == id }
        mapper.selectedId = null
        mapper.invalidate()
        dirty = true
    }

    /** "+" y "−" cambian el recorrido del dedo virtual de un stick. */
    private fun resizeSelected(delta: Float) {
        val id = mapper.selectedId ?: return Toast.makeText(this, R.string.mapper_select_first, Toast.LENGTH_SHORT).show()
        val control = mapping.find(id) ?: return
        if (control.type != ControlType.STICK) {
            Toast.makeText(this, R.string.mapper_only_sticks, Toast.LENGTH_SHORT).show()
            return
        }
        control.radius = (control.radius + delta).coerceIn(0.03f, 0.30f)
        mapper.invalidate()
        dirty = true
    }

    private fun confirmTemplate() {
        AlertDialog.Builder(this)
            .setMessage(R.string.mapper_template_confirm)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                val t = GameMapping.template(packageName, mapping.label)
                mapping.controls.clear()
                mapping.controls.addAll(t.controls)
                mapper.selectedId = null
                mapper.invalidate()
                dirty = true
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun pickGame() {
        PackagePicker.show(this) { app ->
            GameVirtualDisplay.release() // se reinicia la vista previa con el otro juego
            setPackage(app.packageName)
            dirty = false
        }
    }

    // ------------------------------------------------------------------ Guardar / salir

    private fun save() {
        if (packageName.isEmpty()) return
        MappingStore.save(this, mapping)
        dirty = false
        Toast.makeText(this, R.string.mapper_saved, Toast.LENGTH_SHORT).show()
        if (intent.getBooleanExtra(EXTRA_START_AFTER, false)) {
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun confirmExit() {
        if (!dirty) {
            finish()
            return
        }
        AlertDialog.Builder(this)
            .setMessage(R.string.mapper_discard_confirm)
            .setPositiveButton(R.string.mapper_save_exit) { _, _ -> save(); finish() }
            .setNeutralButton(R.string.mapper_discard) { _, _ -> finish() }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    override fun onDestroy() {
        if (::mapper.isInitialized && !ConsoleSession.active) GameVirtualDisplay.release()
        super.onDestroy()
    }
}
