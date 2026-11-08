package com.example.gamepadhid.cast

import android.view.View
import android.widget.ListView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import com.example.gamepadhid.Prefs
import com.example.gamepadhid.R

/**
 * SELECTOR DE TV. Muestra en vivo las pantallas que el sistema expone (ver DisplayScanner).
 * Si la TV todavía no está conectada, se intenta conectar y, si Android no lo permite a una app
 * normal, se abre el panel Cast del sistema.
 */
object TvPicker {

    fun show(activity: AppCompatActivity, onPicked: (CastTarget) -> Unit) {
        val adapter = TargetAdapter(activity)
        val list = ListView(activity).apply {
            this.adapter = adapter
            divider = null
        }
        var current: List<CastTarget> = emptyList()
        val scanner = DisplayScanner(activity) { targets ->
            current = targets
            adapter.clear()
            adapter.addAll(targets)
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.tv_picker_title)
            .setMessage(R.string.tv_picker_hint)
            .setView(list)
            .setNeutralButton(R.string.btn_cast_settings) { _, _ -> openCast(activity) }
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        list.setOnItemClickListener { _, _: View?, position, _ ->
            val target = current.getOrNull(position) ?: return@setOnItemClickListener
            if (target.connected) {
                Prefs.setTvName(activity, target.name)
                dialog.dismiss()
                onPicked(target)
            } else if (scanner.tryConnect(target)) {
                Toast.makeText(activity, R.string.toast_connect_tried, Toast.LENGTH_LONG).show()
            } else {
                openCast(activity)
            }
        }
        dialog.setOnDismissListener { scanner.stop() }
        dialog.show()
        scanner.start()
    }

    private fun openCast(activity: AppCompatActivity) {
        if (!CastNav.openCastSettings(activity)) {
            Toast.makeText(activity, R.string.toast_open_cast_failed, Toast.LENGTH_LONG).show()
        }
    }
}
