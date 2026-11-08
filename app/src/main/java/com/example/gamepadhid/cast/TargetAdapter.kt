package com.example.gamepadhid.cast

import android.content.Context
import android.content.res.ColorStateList
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import com.example.gamepadhid.R

/** Fila del selector de TV: punto de estado + nombre + detalle. */
class TargetAdapter(context: Context) : ArrayAdapter<CastTarget>(context, R.layout.item_target) {

    override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
        val view = convertView ?: LayoutInflater.from(context).inflate(R.layout.item_target, parent, false)
        val item = getItem(position)
        view.findViewById<TextView>(R.id.targetName).text = item?.name
        view.findViewById<TextView>(R.id.targetDetail).text = item?.detail
        val color = ContextCompat.getColor(
            context, if (item?.connected == true) R.color.gc_secondary else R.color.gc_text_muted
        )
        ViewCompat.setBackgroundTintList(view.findViewById(R.id.targetDot), ColorStateList.valueOf(color))
        return view
    }
}
