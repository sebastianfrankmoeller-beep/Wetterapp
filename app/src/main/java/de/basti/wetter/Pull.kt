package de.basti.wetter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.ScrollView
import kotlin.math.abs
import kotlin.math.min

/**
 * ScrollView mit "Zum Aktualisieren nach unten ziehen": Ist die Seite ganz oben und zieht man
 * senkrecht nach unten, meldet sie den Zugweg ([onPull], 0 … >1) und beim Loslassen über der
 * Schwelle [onRelease] mit true. Waagerechte Gesten (Stundenstreifen, Favoriten) bleiben unberührt.
 */
class PullScrollView(ctx: Context) : ScrollView(ctx) {

    var onPull: ((Float) -> Unit)? = null
    var onRelease: ((Boolean) -> Unit)? = null
    var enabledPull = true

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val threshold = 80 * resources.displayMetrics.density
    private var downX = 0f
    private var downY = 0f
    private var startY = 0f
    private var pulling = false
    private var blocked = false      // ein Kind (z. B. Regen-Grafik beim Ziehen) will die Geste selbst

    override fun requestDisallowInterceptTouchEvent(disallow: Boolean) {
        if (disallow) blocked = true
        super.requestDisallowInterceptTouchEvent(disallow)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun dispatchTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = e.x; downY = e.y; pulling = false; blocked = false
            }
            MotionEvent.ACTION_MOVE -> {
                if (!pulling && enabledPull && !blocked && scrollY == 0) {
                    val dy = e.y - downY
                    val dx = e.x - downX
                    if (dy > slop && dy > abs(dx) * 1.5f) {
                        pulling = true
                        startY = e.y
                        // Kinder und eigenes Scrollen sauber abbrechen
                        val cancel = MotionEvent.obtain(e).apply { action = MotionEvent.ACTION_CANCEL }
                        super.dispatchTouchEvent(cancel)
                        cancel.recycle()
                    }
                }
                if (pulling) {
                    val dist = (e.y - startY).coerceAtLeast(0f) * 0.55f
                    onPull?.invoke(dist / threshold)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (pulling) {
                    pulling = false
                    val dist = (e.y - startY).coerceAtLeast(0f) * 0.55f
                    onRelease?.invoke(e.actionMasked == MotionEvent.ACTION_UP && dist >= threshold)
                    return true
                }
            }
        }
        return super.dispatchTouchEvent(e)
    }
}

/** Runder Lade-Kreis in Akzentfarbe: folgt dem Finger, dreht sich während des Ladens. */
@SuppressLint("ViewConstructor")
class RefreshIndicator(ctx: Context, private val p: Ui.Palette) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private var progress = 0f
    private var spinning = false
    private var angle = 0f
    val size = (44 * d).toInt()

    init { visibility = GONE; elevation = 6 * d }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(size, size)

    /** Ziehen: 0 … 1 (Schwelle) … darüber. */
    fun pull(v: Float) {
        if (spinning) return
        progress = v
        visibility = if (v > 0.02f) VISIBLE else GONE
        translationY = min(v, 1.4f) * 90 * d - size
        alpha = min(1f, v * 1.6f)
        rotation = v * 200f
        invalidate()
    }

    fun spin(on: Boolean) {
        spinning = on
        if (on) {
            visibility = VISIBLE; alpha = 1f; progress = 1f
            translationY = 90 * d - size
            postInvalidateOnAnimation()
        } else {
            animate().alpha(0f).translationY(-size.toFloat()).setDuration(220)
                .withEndAction { visibility = GONE; progress = 0f; rotation = 0f }.start()
        }
    }

    val isSpinning get() = spinning

    override fun onDraw(c: Canvas) {
        val r = width / 2f
        fill.color = p.surfaceHi.let { if ((it ushr 24) < 0xF0) Ui.withAlpha(it, 0xFF) else it }
        c.drawCircle(r, r, r - 1 * d, fill)
        arc.color = p.accent
        arc.strokeWidth = 3 * d
        val inset = 12 * d
        rect.set(inset, inset, width - inset, height - inset)
        if (spinning) {
            angle = (angle + 9f) % 360f
            c.drawArc(rect, angle, 270f, false, arc)
            postInvalidateOnAnimation()
        } else {
            val sweep = min(1f, progress) * 300f
            arc.alpha = if (progress >= 1f) 255 else 150
            c.drawArc(rect, -90f, sweep, false, arc)
            arc.alpha = 255
        }
    }
}
