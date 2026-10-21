package de.basti.wetter

import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Rect
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import kotlin.math.abs

/**
 * Container für Vorhersage / Radar / Wind mit Wischen zwischen den Seiten.
 * - Auf der Vorhersage: Wischen überall, außer auf seitlich scrollbaren Elementen (Stundenstreifen, Diagramme).
 * - Auf Radar und Wind: nur vom Bildschirmrand aus oder auf den Bedienelementen – auf der Karte wird die Karte verschoben.
 */
@SuppressLint("ViewConstructor")
class SwipeFrame(ctx: Context) : FrameLayout(ctx) {

    var pageCount = 3
    /** Aktuelle Seite. */
    var current: () -> Int = { 0 }
    /** Liefert (und erzeugt bei Bedarf) die Seite i. */
    var page: (Int) -> View? = { null }
    /** Seitenwechsel abgeschlossen. */
    var onSwitch: (Int) -> Unit = {}

    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val minFling = ViewConfiguration.get(ctx).scaledMinimumFlingVelocity * 4
    private val edge = 26 * resources.displayMetrics.density

    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var allowed = false
    private var dragging = false
    private var vt: VelocityTracker? = null
    private var anim: ValueAnimator? = null

    override fun onInterceptTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (anim?.isRunning == true) return false
                downX = e.x; downY = e.y; lastX = e.x
                dragging = false
                allowed = mayStart(e.x, e.y)
                vt?.recycle(); vt = VelocityTracker.obtain(); vt?.addMovement(e)
            }
            MotionEvent.ACTION_MOVE -> {
                vt?.addMovement(e)
                if (!allowed || dragging) return dragging
                val dx = e.x - downX
                val dy = e.y - downY
                if (abs(dx) > slop * 1.5f && abs(dx) > abs(dy) * 1.6f) {
                    val target = current() + if (dx < 0) 1 else -1
                    if (target !in 0 until pageCount) return false
                    // Element unter dem Finger, das selbst seitlich scrollen kann → nicht übernehmen
                    if (current() == 0 && canChildScroll(this, downX.toInt(), downY.toInt(), if (dx < 0) 1 else -1)) {
                        allowed = false
                        return false
                    }
                    dragging = true
                    lastX = e.x
                    parent?.requestDisallowInterceptTouchEvent(true)
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { dragging = false }
        }
        return false
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(e)
        vt?.addMovement(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_MOVE -> layoutDrag(e.x - downX)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                vt?.computeCurrentVelocity(1000)
                val v = vt?.xVelocity ?: 0f
                val dx = e.x - downX
                val w = width.toFloat()
                val go = e.actionMasked == MotionEvent.ACTION_UP &&
                    (abs(dx) > w * 0.33f || (abs(v) > minFling && (v < 0) == (dx < 0)))
                settle(dx, go)
                dragging = false
            }
        }
        return true
    }

    /** Darf an dieser Stelle ein Seitenwechsel beginnen? */
    private fun mayStart(x: Float, y: Float): Boolean {
        val cur = current()
        if (cur == 0) return true
        if (x < edge || x > width - edge) return true
        // Auf Kartenseiten: nur wenn der Finger nicht auf der Karte liegt (Bedienfelder, Kopfleiste)
        val pg = page(cur) as? ViewGroup ?: return true
        val r = Rect()
        for (i in pg.childCount - 1 downTo 0) {
            val c = pg.getChildAt(i)
            if (c.visibility != View.VISIBLE) continue
            c.getHitRect(r)
            if (r.contains(x.toInt(), y.toInt())) return c !is org.osmdroid.views.MapView
        }
        return false
    }

    /** Prüft rekursiv, ob ein Kind an (x, y) in Richtung [dir] seitlich scrollen kann oder Gesten selbst braucht. */
    private fun canChildScroll(v: View, x: Int, y: Int, dir: Int): Boolean {
        if (v !== this && v.visibility == View.VISIBLE) {
            if (v.canScrollHorizontally(dir)) return true
            if (v is NowcastView || v is RunStripView || v is TimelineView) return true
        }
        if (v is ViewGroup) {
            val r = Rect()
            for (i in v.childCount - 1 downTo 0) {
                val c = v.getChildAt(i)
                if (c.visibility != View.VISIBLE) continue
                c.getHitRect(r)
                if (r.contains(x, y) && canChildScroll(c, x - c.left + c.scrollX, y - c.top + c.scrollY, dir)) return true
            }
        }
        return false
    }

    private fun layoutDrag(dx: Float) {
        val cur = current()
        val w = width.toFloat()
        val target = cur + if (dx < 0) 1 else -1
        val clamped = if (target !in 0 until pageCount) dx * 0.25f else dx
        page(cur)?.translationX = clamped
        for (i in 0 until pageCount) if (i != cur) {
            val pv = page(i) ?: continue
            if (i == target && target in 0 until pageCount) {
                pv.visibility = View.VISIBLE
                pv.translationX = clamped + if (dx < 0) w else -w
            } else if (pv.visibility == View.VISIBLE) {
                pv.visibility = View.GONE
            }
        }
    }

    private fun settle(dx: Float, go: Boolean) {
        val cur = current()
        val target = cur + if (dx < 0) 1 else -1
        val switch = go && target in 0 until pageCount
        val w = width.toFloat()
        val end = if (!switch) 0f else if (dx < 0) -w else w
        val from = page(cur)?.translationX ?: 0f
        anim?.cancel()
        anim = ValueAnimator.ofFloat(from, end).apply {
            duration = 220
            interpolator = DecelerateInterpolator()
            addUpdateListener { layoutDrag(it.animatedValue as Float) }
            addListener(object : android.animation.AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: android.animation.Animator) {
                    for (i in 0 until pageCount) page(i)?.translationX = 0f
                    if (switch) onSwitch(target) else onSwitch(cur)
                }
            })
            start()
        }
    }
}
