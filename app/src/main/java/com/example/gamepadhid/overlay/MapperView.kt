package com.example.gamepadhid.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import com.example.gamepadhid.R
import com.example.gamepadhid.mapping.ControlIds
import com.example.gamepadhid.mapping.ControlSpec
import com.example.gamepadhid.mapping.ControlType
import com.example.gamepadhid.mapping.GameMapping
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Capa transparente sobre la vista previa de la pantalla virtual.
 *  - Modo PLACE: dibuja un marcador por control y se arrastra hasta el botón/stick real del juego.
 *  - Modo PLAY: no dibuja nada y reenvía los toques al juego (para llegar a la pantalla donde se juega).
 * Las posiciones son fracciones (0..1) del ancho/alto de esta vista, que coincide con la pantalla virtual.
 */
class MapperView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { PLACE, PLAY }

    var mapping: GameMapping? = null
        set(value) { field = value; invalidate() }
    var mode: Mode = Mode.PLACE
        set(value) { field = value; invalidate() }
    var selectedId: String? = null
        set(value) { field = value; invalidate() }

    var onSelectionChanged: ((String?) -> Unit)? = null
    var onEdited: (() -> Unit)? = null

    /** Modo PLAY: acción de MotionEvent y posición como fracción (0..1). */
    var onPlayTouch: ((action: Int, fx: Float, fy: Float) -> Unit)? = null

    private val density = resources.displayMetrics.density
    private fun dp(v: Float) = v * density

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = dp(2f) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        textSize = dp(11f)
    }

    private var dragging: ControlSpec? = null
    private var grabDx = 0f
    private var grabDy = 0f

    private fun radiusOf(c: ControlSpec): Float =
        if (c.type == ControlType.STICK) max(c.radius * min(width, height), dp(16f)) else dp(20f)

    override fun onDraw(canvas: Canvas) {
        if (mode == Mode.PLAY) return
        val m = mapping ?: return
        val accent = ContextCompat.getColor(this.context, R.color.pad_accent2)
        for (c in m.controls) {
            val cx = c.tx * width
            val cy = c.ty * height
            val r = radiusOf(c)
            fill.color = ColorUtils.setAlphaComponent(accent, 110)
            canvas.drawCircle(cx, cy, r, fill)
            stroke.color = if (c.id == selectedId) 0xFFFFFFFF.toInt() else ColorUtils.setAlphaComponent(accent, 255)
            canvas.drawCircle(cx, cy, r, stroke)
            canvas.drawLine(cx - dp(6f), cy, cx + dp(6f), cy, stroke)
            canvas.drawLine(cx, cy - dp(6f), cx, cy + dp(6f), stroke)
            canvas.drawText(ControlIds.label(c.id), cx, cy - r - dp(4f), text)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (width == 0 || height == 0) return false
        if (mode == Mode.PLAY) {
            val fx = (event.x / width).coerceIn(0f, 1f)
            val fy = (event.y / height).coerceIn(0f, 1f)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP ->
                    onPlayTouch?.invoke(event.actionMasked, fx, fy)
                MotionEvent.ACTION_CANCEL -> onPlayTouch?.invoke(MotionEvent.ACTION_UP, fx, fy)
            }
            return true
        }
        val m = mapping ?: return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                val hit = m.controls.asReversed().firstOrNull {
                    hypot(event.x - it.tx * width, event.y - it.ty * height) <= max(radiusOf(it), dp(28f))
                }
                dragging = hit
                if (hit != null) {
                    grabDx = hit.tx * width - event.x
                    grabDy = hit.ty * height - event.y
                }
                selectedId = hit?.id
                onSelectionChanged?.invoke(hit?.id)
            }
            MotionEvent.ACTION_MOVE -> dragging?.let { c ->
                c.tx = ((event.x + grabDx) / width).coerceIn(0f, 1f)
                c.ty = ((event.y + grabDy) / height).coerceIn(0f, 1f)
                onEdited?.invoke()
                invalidate()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> dragging = null
        }
        return true
    }
}
