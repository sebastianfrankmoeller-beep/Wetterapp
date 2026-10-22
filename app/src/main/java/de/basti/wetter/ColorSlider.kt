package de.basti.wetter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

/**
 * Stufenloser Farbregler: Leiste mit Farbverlauf, runder Griff in der gewählten Farbe.
 * [swatch] liefert die Farbe der Leiste zum Wert, [thumb] die echte Farbe für den Griff.
 * Während des Ziehens meldet [onChange] laufend, beim Loslassen [onCommit] den Endwert.
 */
@SuppressLint("ViewConstructor")
class ColorSlider(
    ctx: Context,
    private val p: Ui.Palette,
    private val max: Int,
    initial: Int,
    private val swatch: (Int) -> Int,
    private val thumb: (Int) -> Int = swatch
) : View(ctx) {

    var value = initial.coerceIn(0, max)
        private set
    var onChange: ((Int) -> Unit)? = null
    var onCommit: ((Int) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val trackH = 14 * d
    private val thumbR = 13 * d
    private val pad = thumbR + 2 * d
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private var shader: LinearGradient? = null
    private var shaderW = -1

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (thumbR * 2 + 8 * d).toInt())
    }

    private fun xOf(v: Int) = pad + (width - 2 * pad) * v / max.toFloat()
    private fun valueAt(x: Float) = (((x - pad) / (width - 2 * pad)) * max).toInt().coerceIn(0, max)

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        if (shader == null || shaderW != width) {
            val n = 48
            val colors = IntArray(n + 1) { swatch((it * max / n.toFloat()).toInt()) }
            shader = LinearGradient(pad, 0f, width - pad, 0f, colors, null, Shader.TileMode.CLAMP)
            shaderW = width
        }
        rect.set(pad - trackH / 2, cy - trackH / 2, width - pad + trackH / 2, cy + trackH / 2)
        fill.shader = shader
        c.drawRoundRect(rect, trackH / 2, trackH / 2, fill)
        fill.shader = null
        stroke.color = p.border; stroke.strokeWidth = 1 * d
        c.drawRoundRect(rect, trackH / 2, trackH / 2, stroke)

        val x = xOf(value)
        fill.color = thumb(value)
        c.drawCircle(x, cy, thumbR, fill)
        stroke.color = p.text; stroke.strokeWidth = 2.5f * d
        c.drawCircle(x, cy, thumbR - 1.25f * d, stroke)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)   // nicht die Seite scrollen
                set(valueAt(e.x))
            }
            MotionEvent.ACTION_UP -> {
                set(valueAt(e.x))
                parent?.requestDisallowInterceptTouchEvent(false)
                onCommit?.invoke(value)
            }
            MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                onCommit?.invoke(value)
            }
        }
        return true
    }

    private fun set(v: Int) {
        if (v == value) return
        value = v
        invalidate()
        onChange?.invoke(v)
    }
}
