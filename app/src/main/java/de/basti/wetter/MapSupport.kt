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
import org.osmdroid.tileprovider.MapTileProviderBasic
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.util.GeoPoint
import org.osmdroid.util.MapTileIndex
import org.osmdroid.views.overlay.TilesOverlay
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
    private fun darkFilter(bright: Boolean): ColorMatrixColorFilter {
        // bright: kräftigerer Kontrast (Land heller, Linien deutlicher) – für die Windkarte
        val k = if (bright) 0.66f else 0.52f
        val off = 255f * k + if (bright) 34f else 16f
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

    /**
     * CARTO-Kacheln in doppelter Auflösung (scharf auf hochauflösenden Displays).
     * Die Karte selbst kommt ohne Beschriftung; die Ortsnamen liegen als eigene Ebene
     * ganz oben, damit Radar, Windfarben und Standortpunkt sie nicht verdecken.
     */
    private class Carto(style: String) : OnlineTileSourceBase(
        "carto_$style", 1, 18, 256, ".png",
        arrayOf("https://a.basemaps.cartocdn.com/rastertiles/$style/", "https://b.basemaps.cartocdn.com/rastertiles/$style/",
            "https://c.basemaps.cartocdn.com/rastertiles/$style/"),
        "© OpenStreetMap, © CARTO"
    ) {
        override fun getTileURLString(index: Long): String =
            baseUrl + MapTileIndex.getZoom(index) + "/" + MapTileIndex.getX(index) + "/" + MapTileIndex.getY(index) + "@2x.png"
    }

    /** Ortsnamen als oberste Kartenebene (nach allen Wetterebenen hinzufügen). */
    fun addLabels(map: MapView, p: Ui.Palette, bright: Boolean = false) {
        val provider = MapTileProviderBasic(map.context.applicationContext, Carto("light_only_labels"))
        val labels = TilesOverlay(provider, map.context.applicationContext).apply {
            setColorFilter(if (p.dark) darkFilter(bright) else lightFilter())
            setLoadingBackgroundColor(android.graphics.Color.TRANSPARENT)
            setLoadingLineColor(android.graphics.Color.TRANSPARENT)
        }
        provider.tileRequestCompleteHandlers.add(map.tileRequestCompleteHandler)
        map.overlays.add(labels)
    }

    fun createMap(ctx: Context, p: Ui.Palette, center: GeoPoint, zoom: Double, bright: Boolean = false): MapView {
        configure(ctx)
        val map = MapView(ctx)
        map.setTileSource(Carto("light_nolabels"))
        map.isTilesScaledToDpi = true
        map.setMultiTouchControls(true)
        map.zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
        map.setMinZoomLevel(3.0)
        map.setMaxZoomLevel(12.0)
        map.isHorizontalMapRepetitionEnabled = true
        map.isVerticalMapRepetitionEnabled = false
        map.setScrollableAreaLimitLatitude(MapView.getTileSystem().maxLatitude, MapView.getTileSystem().minLatitude, 0)
        map.overlayManager.tilesOverlay.setColorFilter(if (p.dark) darkFilter(bright) else lightFilter())
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
