package com.example.gamepadhid.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.example.gamepadhid.R
import kotlin.math.hypot
import kotlin.math.min

/**
 * Joystick analógico táctil con acabado "de producto":
 *  - base con degradado radial y aro,
 *  - stick con degradado, brillo y sombra suave,
 *  - aro luminoso mientras está en uso,
 *  - vuelta al centro con efecto resorte.
 *
 * Cada instancia sigue SOLO su propio dedo (pointerId): se pueden usar los dos sticks y varios
 * botones a la vez. Posiciones normalizadas -1..1 en círculo; Y negativo = arriba (como HID).
 */
class JoystickView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    /** Se llama en cada movimiento y al soltar (0, 0) de inmediato, sin esperar a la animación. */
    var onMove: ((x: Float, y: Float) -> Unit)? = null

    /** true al poner el dedo, false al soltarlo (después del último onMove(0, 0)). Sirve para holdDown/release. */
    var onEngage: ((down: Boolean) -> Unit)? = null

    private companion object {
        const val INVALID_POINTER = -1
        const val DEAD_ZONE = 0.08f
    }

    private val density = resources.displayMetrics.density
    private fun color(id: Int) = ContextCompat.getColor(context, id)

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 2f
        color = color(R.color.pad_key_stroke)
    }
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = density * 3f
        color = ColorUtils.setAlphaComponent(color(R.color.pad_accent), 200)
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ColorUtils.setAlphaComponent(0xFFFFFFFF.toInt(), 70)
    }

    private var activePointer = INVALID_POINTER
    private var thumbX = 0f // -1..1 (posición visual, sin zona muerta)
    private var thumbY = 0f
    private var engaged = false
    private var returnAnimator: ValueAnimator? = null

    private fun baseRadius() = min(width, height) / 2f - ringPaint.strokeWidth - density * 2f
    private fun thumbRadius() = baseRadius() * 0.38f
    private fun maxTravel() = baseRadius() - thumbRadius()

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val r = baseRadius()
        if (r <= 0f) return
        basePaint.shader = RadialGradient(
            w / 2f, h / 2f, r,
            intArrayOf(color(R.color.joystick_base_inner), color(R.color.joystick_base_outer)),
            floatArrayOf(0f, 1f), Shader.TileMode.CLAMP
        )
        // El degradado del stick se define centrado en (0,0) y se dibuja con translate()
        val t = thumbRadius()
        thumbPaint.shader = RadialGradient(
            -t * 0.3f, -t * 0.35f, t * 1.5f,
            intArrayOf(color(R.color.pad_accent_light), color(R.color.pad_accent), color(R.color.pad_accent_dark)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = baseRadius()
        val t = thumbRadius()

        canvas.drawCircle(cx, cy, r, basePaint)
        canvas.drawCircle(cx, cy, r, ringPaint)
        if (engaged) canvas.drawCircle(cx, cy, r, glowPaint)

        canvas.save()
        canvas.translate(cx + thumbX * maxTravel(), cy + thumbY * maxTravel())
        // Sombra suave: dos círculos translúcidos desplazados hacia abajo
        shadowPaint.color = 0x22000000
        canvas.drawCircle(0f, density * 5f, t + density * 3f, shadowPaint)
        shadowPaint.color = 0x33000000
        canvas.drawCircle(0f, density * 3f, t + density * 1.5f, shadowPaint)
        canvas.drawCircle(0f, 0f, t, thumbPaint)
        canvas.drawCircle(-t * 0.3f, -t * 0.38f, t * 0.28f, highlightPaint)
        canvas.restore()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                returnAnimator?.cancel()
                activePointer = event.getPointerId(0)
                engaged = true
                onEngage?.invoke(true)
                parent?.requestDisallowInterceptTouchEvent(true)
                moveTo(event, 0)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(activePointer)
                if (index >= 0) moveTo(event, index)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == activePointer) release()
            }
            MotionEvent.ACTION_UP -> {
                release()
                performClick()
            }
            MotionEvent.ACTION_CANCEL -> release()
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()

    private fun moveTo(event: MotionEvent, index: Int) {
        val travel = maxTravel()
        if (travel <= 0f) return
        var dx = (event.getX(index) - width / 2f) / travel
        var dy = (event.getY(index) - height / 2f) / travel
        val length = hypot(dx, dy)
        if (length > 1f) { // limitar al círculo
            dx /= length
            dy /= length
        }
        thumbX = dx
        thumbY = dy
        invalidate()
        if (hypot(dx, dy) < DEAD_ZONE) onMove?.invoke(0f, 0f) else onMove?.invoke(dx, dy)
    }

    private fun release() {
        activePointer = INVALID_POINTER
        engaged = false
        onMove?.invoke(0f, 0f) // el juego recibe "centro" al instante
        onEngage?.invoke(false)
        // Solo visual: el stick vuelve al centro con un pequeño rebote
        val startX = thumbX
        val startY = thumbY
        returnAnimator?.cancel()
        returnAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 220
            interpolator = OvershootInterpolator(1.8f)
            addUpdateListener {
                val f = it.animatedValue as Float
                thumbX = startX * (1f - f)
                thumbY = startY * (1f - f)
                invalidate()
            }
            start()
        }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        returnAnimator?.cancel()
        super.onDetachedFromWindow()
    }
}
