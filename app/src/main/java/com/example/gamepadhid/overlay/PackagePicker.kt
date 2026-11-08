package com.example.gamepadhid.overlay

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.widget.doAfterTextChanged
import com.example.gamepadhid.R

/**
 * Selector de juego/app instalada con buscador. Lista las apps con icono de lanzador
 * (CATEGORY_LAUNCHER) y pone primero las que Android clasifica como juegos (CATEGORY_GAME).
 * Se usa la consulta de lanzadores + <queries> del manifiesto en vez del permiso restringido QUERY_ALL_PACKAGES.
 */
object PackagePicker {

    data class App(val label: String, val packageName: String, val isGame: Boolean)

    @Suppress("DEPRECATION")
    fun loadApps(context: Context): List<App> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val infos = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0))
        } else {
            pm.queryIntentActivities(intent, 0)
        }
        return infos
            .map {
                App(
                    it.loadLabel(pm).toString(),
                    it.activityInfo.packageName,
                    it.activityInfo.applicationInfo.category == ApplicationInfo.CATEGORY_GAME
                )
            }
            .filter { it.packageName != context.packageName }
            .distinctBy { it.packageName }
            .sortedWith(compareByDescending<App> { it.isGame }.thenBy { it.label.lowercase() })
    }

    fun labelOf(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        pm.getApplicationInfo(packageName, 0).loadLabel(pm).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        packageName
    }

    fun show(activity: AppCompatActivity, onPicked: (App) -> Unit) {
        val all = loadApps(activity)
        var shown: List<App> = all

        val adapter = object : ArrayAdapter<App>(activity, android.R.layout.simple_list_item_2) {
            override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
                val view = convertView ?: LayoutInflater.from(context)
                    .inflate(android.R.layout.simple_list_item_2, parent, false)
                val item = getItem(position)
                view.findViewById<TextView>(android.R.id.text1).text = item?.label
                view.findViewById<TextView>(android.R.id.text2).text =
                    if (item?.isGame == true) "${item.packageName} · ${context.getString(R.string.picker_game_tag)}"
                    else item?.packageName
                return view
            }
        }
        adapter.addAll(all)

        val density = activity.resources.displayMetrics.density
        val search = EditText(activity).apply {
            hint = activity.getString(R.string.picker_search_hint)
            setSingleLine()
        }
        val list = ListView(activity).apply { this.adapter = adapter }
        val container = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            val pad = (20 * density).toInt()
            setPadding(pad, pad / 2, pad, 0)
            addView(search, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(list, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (360 * density).toInt()))
        }

        val dialog = AlertDialog.Builder(activity)
            .setTitle(R.string.picker_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .create()

        search.doAfterTextChanged { text ->
            val q = text?.toString()?.trim()?.lowercase().orEmpty()
            shown = if (q.isEmpty()) all else all.filter {
                it.label.lowercase().contains(q) || it.packageName.lowercase().contains(q)
            }
            adapter.clear()
            adapter.addAll(shown)
        }
        list.setOnItemClickListener { _, _, position, _ ->
            val app = shown.getOrNull(position) ?: return@setOnItemClickListener
            dialog.dismiss()
            onPicked(app)
        }
        dialog.show()
    }
}
