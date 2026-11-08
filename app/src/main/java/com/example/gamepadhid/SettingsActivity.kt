package com.example.gamepadhid

import android.Manifest
import android.bluetooth.BluetoothManager
import android.os.Bundle
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import com.example.gamepadhid.cast.CastCapabilities
import com.example.gamepadhid.cast.TvPicker
import com.example.gamepadhid.hid.PairingDialog
import com.example.gamepadhid.ui.Fx
import com.example.gamepadhid.ui.applySystemBarsPadding
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout

/** Ajustes mínimos: nombre Bluetooth, botón de emparejar y selección de la TV de proyección. */
class SettingsActivity : AppCompatActivity() {

    private lateinit var tilName: TextInputLayout
    private lateinit var etName: TextInputEditText
    private lateinit var tvTvCurrent: TextView
    private var pendingName: String? = null

    // BLUETOOTH_CONNECT hace falta para cambiar el nombre del adaptador (Android 12+)
    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            val name = pendingName
            pendingName = null
            if (granted && name != null) applyAdapterName(name) else toast(R.string.toast_name_no_permission)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContentView(R.layout.activity_settings)
        findViewById<View>(R.id.root).applySystemBarsPadding()

        tilName = findViewById(R.id.tilName)
        etName = findViewById(R.id.etName)
        tvTvCurrent = findViewById(R.id.tvTvCurrent)

        etName.setText(Prefs.btName(this))
        renderTv()
        findViewById<TextView>(R.id.tvCapability).setText(
            when (CastCapabilities.check(this).level) {
                CastCapabilities.Level.SUPPORTED -> R.string.cap_supported
                CastCapabilities.Level.LIMITED -> R.string.cap_limited
                CastCapabilities.Level.UNSUPPORTED -> R.string.cap_unsupported
            }
        )

        findViewById<View>(R.id.btnBack).setOnClickListener { finish() }
        findViewById<View>(R.id.btnSave).setOnClickListener { saveName() }
        findViewById<View>(R.id.btnPair).setOnClickListener { PairingDialog.show(this) }
        findViewById<View>(R.id.btnPickTv).setOnClickListener {
            TvPicker.show(this) { renderTv() }
        }
        listOf(R.id.btnSave, R.id.btnPair, R.id.btnPickTv).forEach { Fx.attachPress(findViewById<View>(it)) }
    }

    private fun renderTv() {
        tvTvCurrent.text = getString(R.string.tv_target, Prefs.tvName(this) ?: getString(R.string.tv_none))
    }

    private fun saveName() {
        val name = etName.text?.toString()?.trim().orEmpty()
        if (name.isEmpty()) {
            tilName.error = getString(R.string.err_name_empty)
            return
        }
        tilName.error = null
        Prefs.setBtName(this, name)
        if (Permissions.hasBluetoothConnect(this)) {
            applyAdapterName(name)
        } else {
            pendingName = name
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        }
    }

    /** Es el nombre que verá la TV al buscar dispositivos. Cambia el nombre Bluetooth del teléfono. */
    private fun applyAdapterName(name: String) {
        val adapter = getSystemService(BluetoothManager::class.java)?.adapter
        val ok = try {
            adapter?.setName(name) == true
        } catch (e: SecurityException) {
            false
        }
        toast(if (ok) R.string.toast_name_saved else R.string.toast_name_saved_partial)
    }

    private fun toast(resId: Int) = Toast.makeText(this, resId, Toast.LENGTH_LONG).show()
}
