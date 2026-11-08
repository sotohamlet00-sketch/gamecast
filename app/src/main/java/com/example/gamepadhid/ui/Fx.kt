package com.example.gamepadhid.ui

import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.res.ColorStateList
import android.view.MotionEvent
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.DecelerateInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.example.gamepadhid.R

/** Microinteracciones compartidas: pulsación con resorte, puntos de estado, entrada escalonada. */
object Fx {

    private const val PRESS_SCALE = 0.92f

    /** Al tocar: la vista se hunde (escala 0.92 y baja su sombra). */
    fun pressDown(v: View) {
        v.animate().cancel()
        v.animate()
            .scaleX(PRESS_SCALE).scaleY(PRESS_SCALE)
            .translationZ(-2f * v.resources.displayMetrics.density)
            .setDuration(70)
            .setInterpolator(DecelerateInterpolator())
            .start()
    }

    /** Al soltar: vuelve con un pequeño rebote. */
    fun pressUp(v: View) {
        v.animate().cancel()
        v.animate()
            .scaleX(1f).scaleY(1f).translationZ(0f)
            .setDuration(240)
            .setInterpolator(OvershootInterpolator(2.5f))
            .start()
    }

    /** Animación de pulsación para botones normales (no consume el toque: el clic sigue funcionando). */
    @SuppressLint("ClickableViewAccessibility")
    fun attachPress(v: View) {
        v.setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> pressDown(view)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> pressUp(view)
            }
            false
        }
    }

    fun setEnabled(v: View, enabled: Boolean) {
        v.isEnabled = enabled
        v.animate().alpha(if (enabled) 1f else 0.45f).setDuration(150).start()
    }

    /** Punto de estado de color; con pulsing=true late suavemente. */
    fun setDot(dot: View, color: Int, pulsing: Boolean) {
        ViewCompat.setBackgroundTintList(dot, ColorStateList.valueOf(color))
        val running = dot.getTag(R.id.tag_pulse) as? ObjectAnimator
        if (!pulsing) {
            running?.cancel()
            dot.setTag(R.id.tag_pulse, null)
            dot.alpha = 1f
            return
        }
        if (running != null) return
        val animator = ObjectAnimator.ofFloat(dot, View.ALPHA, 1f, 0.3f).apply {
            duration = 900
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
        dot.setTag(R.id.tag_pulse, animator)
    }

    /** Las tarjetas entran una tras otra (fundido + subida). */
    fun enter(views: List<View>) {
        views.forEachIndexed { index, view ->
            view.alpha = 0f
            view.translationY = 24f * view.resources.displayMetrics.density
            view.animate()
                .alpha(1f).translationY(0f)
                .setStartDelay(index * 90L)
                .setDuration(420)
                .setInterpolator(DecelerateInterpolator(1.6f))
                .start()
        }
    }
}

/** Edge-to-edge (obligatorio con targetSdk 35+): deja margen para barras del sistema y notch. */
fun View.applySystemBarsPadding(extraDp: Int = 0) {
    val extra = (extraDp * resources.displayMetrics.density).toInt()
    ViewCompat.setOnApplyWindowInsetsListener(this) { v, insets ->
        val bars = insets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
        )
        v.setPadding(bars.left + extra, bars.top + extra, bars.right + extra, bars.bottom + extra)
        insets
    }
}
