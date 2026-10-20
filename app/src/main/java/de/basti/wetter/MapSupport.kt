package de.basti.wetter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Gemeinsame Kartengrundlage für Radar und Wind: OpenStreetMap-Karte.
 * Im Dunkelmodus wird sie in der App selbst dunkel eingefärbt.
 */
object MapSupport {

    private var configured = false

    fun configure(ctx: Context) {
        if (configured) return
        configured = true
        val conf = Configuration.getInstance()
        // OpenStreetMap verlangt eine eindeutige App-Kennung
        conf.userAgentValue = "WetterApp-${ctx.packageName}/1.0"
        conf.osmdroidBasePath = File(ctx.cacheDir, "osmdroid")
        conf.osmdroidTileCache = File(ctx.cacheDir, "osmdroid/tiles")
        conf.cacheMapTileCount = 40.toShort()
        conf.tileDownloadThreads = 2.toShort()
    }

    /** Graustufen, invertiert und abgedunkelt, mit leichtem Blaustich – passt zum App-Design. */
    private fun darkFilter(): ColorMatrixColorFilter {
        val k = 0.52f
        val off = 255f * k + 16f
        val r = 0.30f * k; val g = 0.59f * k; val b = 0.11f * k
        return ColorMatrixColorFilter(ColorMatrix(floatArrayOf(
            -r, -g, -b, 0f, off,
            -r, -g, -b, 0f, off + 3f,
            -r, -g, -b, 0f, off + 10f,
            0f, 0f, 0f, 1f, 0f
        )))
    }

    /** Helle Karte etwas entsättigen, damit Radar- und Windfarben hervorstechen. */
    private fun lightFilter(): ColorMatrixColorFilter {
        val cm = ColorMatrix()
        cm.setSaturation(0.35f)
        return ColorMatrixColorFilter(cm)
    }

    fun createMap(ctx: Context, p: Ui.Palette, center: GeoPoint, zoom: Double): MapView {
        configure(ctx)
        val map = MapView(ctx)
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.isTilesScaledToDpi = true
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.setMinZoomLevel(3.0)
        map.setMaxZoomLevel(12.0)
        map.isHorizontalMapRepetitionEnabled = true
        map.isVerticalMapRepetitionEnabled = false
        map.setScrollableAreaLimitLatitude(MapView.getTileSystem().maxLatitude, MapView.getTileSystem().minLatitude, 0)
        map.overlayManager.tilesOverlay.setColorFilter(if (p.dark) darkFilter() else lightFilter())
        map.overlayManager.tilesOverlay.setLoadingBackgroundColor(p.bg)
        map.overlayManager.tilesOverlay.setLoadingLineColor(p.bg)
        map.setBackgroundColor(p.bg)
        map.controller.setZoom(zoom)
        map.controller.setCenter(center)
        return map
    }
}

/**
 * Zeitleiste mit Raststufen (Radar-Bilder bzw. Wind-Stunden), per Finger ziehbar.
 * [nowIndex] markiert "jetzt"; Stufen danach sind Vorhersage.
 */
@SuppressLint("ViewConstructor")
class TimelineView(ctx: Context, private val p: Ui.Palette) : View(ctx) {

    var steps = 1
        set(v) { field = max(1, v); index = min(index, field - 1); invalidate() }
    var index = 0
        private set
    var nowIndex = 0
        set(v) { field = v; invalidate() }
    var onChange: ((Int) -> Unit)? = null

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pad = 14 * d

    fun setIndex(i: Int, notify: Boolean) {
        val n = i.coerceIn(0, steps - 1)
        if (n == index && !notify) return
        index = n
        invalidate()
        if (notify) onChange?.invoke(n)
    }

    override fun onMeasure(w: Int, h: Int) {
        setMeasuredDimension(MeasureSpec.getSize(w), (36 * d).toInt())
    }

    private fun xOf(i: Int): Float {
        val w = width - 2 * pad
        return pad + if (steps <= 1) 0f else w * i / (steps - 1)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val w = width - 2 * pad
                val i = if (steps <= 1) 0 else (((e.x - pad) / w) * (steps - 1)).roundToInt()
                if (i.coerceIn(0, steps - 1) != index) setIndex(i, true)
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val cy = height / 2f
        val th = 4 * d
        // Vergangenheit und Zukunft unterschiedlich einfärben
        paint.color = p.surfaceHi
        c.drawRoundRect(RectF(pad, cy - th / 2, width - pad, cy + th / 2), th, th, paint)
        paint.color = Ui.withAlpha(p.accent, 90)
        c.drawRoundRect(RectF(pad, cy - th / 2, xOf(index), cy + th / 2), th, th, paint)
        // Raststufen
        for (i in 0 until steps) {
            paint.color = if (i == nowIndex) p.text else p.muted
            val r = if (i == nowIndex) 3.2f * d else 1.6f * d
            c.drawCircle(xOf(i), cy, r, paint)
        }
        // Knopf
        paint.color = p.accent
        c.drawCircle(xOf(index), cy, 9 * d, paint)
        paint.color = p.onAccent
        c.drawCircle(xOf(index), cy, 3 * d, paint)
    }
}
