package com.example.gamepadhid.virtual

import android.content.Context
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.util.DisplayMetrics
import android.view.Display

object ScreenInfo {
    /** Tamaño real de la pantalla principal en píxeles, con la rotación actual. */
    @Suppress("DEPRECATION")
    fun realSize(context: Context): Point {
        val point = Point()
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        manager.getDisplay(Display.DEFAULT_DISPLAY).getRealSize(point)
        return point
    }

    @Suppress("DEPRECATION")
    fun realMetrics(context: Context): DisplayMetrics {
        val metrics = DisplayMetrics()
        val manager = context.getSystemService(Context.DISPLAY_SERVICE) as DisplayManager
        manager.getDisplay(Display.DEFAULT_DISPLAY).getRealMetrics(metrics)
        return metrics
    }
}
