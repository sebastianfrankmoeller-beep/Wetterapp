package de.basti.wetter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.view.MotionEvent
import android.view.View

/**
 * Stufenloser Farbwähler: Farbfeld (links blass → rechts kräftig, oben hell → unten dunkel)
 * und darunter eine Farbton-Leiste durch alle Farben. Werte als HSV, ohne Raster.
 * [vMax]/[vMin] begrenzen die Helligkeit (z. B. nur dunkle Töne für den Hintergrund).
 * Während des Ziehens meldet [onChange] laufend, beim Loslassen [onCommit].
 */
@SuppressLint("ViewConstructor")
class ColorPicker(
    ctx: Context,
    private val p: Ui.Palette,
    initial: FloatArray,
    private val vMin: Float = 0f,
    private val vMax: Float = 1f
) : View(ctx) {

    val hsv = floatArrayOf(
        ((initial[0] % 360f) + 360f) % 360f,
        initial[1].coerceIn(0f, 1f),
        initial[2].coerceIn(vMin, vMax)
    )
    var onChange: ((FloatArray) -> Unit)? = null
    var onCommit: ((FloatArray) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val fieldH = 150 * d
    private val gap = 16 * d
    private val hueH = 16 * d
    private val thumbR = 12 * d
    private val pad = thumbR + 2 * d

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val field = RectF()
    private val hue = RectF()
    private val clip = Path()
    private var hueShader: LinearGradient? = null
    private var hueW = -1f
    private var dragging = 0          // 1 = Feld, 2 = Farbton

    val color: Int get() = Color.HSVToColor(hsv)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (pad + fieldH + gap + hueH + pad).toInt())
    }

    private fun layoutRects() {
        field.set(pad, pad, width - pad, pad + fieldH)
        hue.set(pad, field.bottom + gap, width - pad, field.bottom + gap + hueH)
    }

    override fun onDraw(c: Canvas) {
        layoutRects()
        val r = 12 * d * Ui.cornerScale.coerceAtLeast(0.3f)

        // Farbfeld: waagerecht Sättigung (bei größter erlaubter Helligkeit), senkrecht abgedunkelt bis vMin
        c.save()
        clip.reset(); clip.addRoundRect(field, r, r, Path.Direction.CW)
        c.clipPath(clip)
        val top = Color.HSVToColor(floatArrayOf(hsv[0], 0f, vMax))
        val full = Color.HSVToColor(floatArrayOf(hsv[0], 1f, vMax))
        fill.shader = LinearGradient(field.left, 0f, field.right, 0f, top, full, Shader.TileMode.CLAMP)
        c.drawRect(field, fill)
        val bottomAlpha = ((1f - vMin / vMax.coerceAtLeast(0.001f)) * 255).toInt().coerceIn(0, 255)
        fill.shader = LinearGradient(0f, field.top, 0f, field.bottom, 0x00000000, Color.argb(bottomAlpha, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawRect(field, fill)
        fill.shader = null
        c.restore()
        stroke.color = p.border; stroke.strokeWidth = 1 * d
        c.drawRoundRect(field, r, r, stroke)

        // Farbton-Leiste (immer kräftig, damit der Ton erkennbar ist)
        if (hueShader == null || hueW != hue.width()) {
            val n = 36
            val cols = IntArray(n + 1) { Color.HSVToColor(floatArrayOf(it * 360f / n, 0.85f, 0.95f)) }
            hueShader = LinearGradient(hue.left, 0f, hue.right, 0f, cols, null, Shader.TileMode.CLAMP)
            hueW = hue.width()
        }
        fill.shader = hueShader
        c.drawRoundRect(hue, hueH / 2, hueH / 2, fill)
        fill.shader = null
        c.drawRoundRect(hue, hueH / 2, hueH / 2, stroke)

        // Griffe
        val fx = field.left + hsv[1] * field.width()
        val span = (vMax - vMin).coerceAtLeast(0.001f)
        val fy = field.top + (1f - (hsv[2] - vMin) / span) * field.height()
        thumb(c, fx, fy, color)
        val hx = hue.left + hsv[0] / 360f * hue.width()
        thumb(c, hx, hue.centerY(), Color.HSVToColor(floatArrayOf(hsv[0], 0.85f, 0.95f)))
    }

    private fun thumb(c: Canvas, x: Float, y: Float, col: Int) {
        fill.color = col
        c.drawCircle(x, y, thumbR, fill)
        stroke.color = 0xFFFFFFFF.toInt(); stroke.strokeWidth = 3 * d
        c.drawCircle(x, y, thumbR - 1.5f * d, stroke)
        stroke.color = 0x55000000; stroke.strokeWidth = 1 * d
        c.drawCircle(x, y, thumbR, stroke)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        layoutRects()
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragging = if (e.y < field.bottom + gap / 2) 1 else 2
                parent?.requestDisallowInterceptTouchEvent(true)   // Seite nicht scrollen
                update(e.x, e.y)
            }
            MotionEvent.ACTION_MOVE -> update(e.x, e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (e.actionMasked == MotionEvent.ACTION_UP) update(e.x, e.y)
                dragging = 0
                parent?.requestDisallowInterceptTouchEvent(false)
                onCommit?.invoke(hsv.copyOf())
            }
        }
        return true
    }

    private fun update(x: Float, y: Float) {
        when (dragging) {
            1 -> {
                hsv[1] = ((x - field.left) / field.width()).coerceIn(0f, 1f)
                val t = ((y - field.top) / field.height()).coerceIn(0f, 1f)
                hsv[2] = vMin + (1f - t) * (vMax - vMin)
            }
            2 -> hsv[0] = ((x - hue.left) / hue.width()).coerceIn(0f, 1f) * 360f
            else -> return
        }
        invalidate()
        onChange?.invoke(hsv.copyOf())
    }
}
