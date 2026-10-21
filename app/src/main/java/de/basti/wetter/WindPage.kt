package de.basti.wetter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Point
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.util.Calendar
import java.util.Locale
import java.util.Random
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Wetterfelder auf einem regelmäßigen Längen-/Breitengitter, stündlich. */
class WindGrid(
    val south: Double, val north: Double, val west: Double, val east: Double,
    val nx: Int, val ny: Int,
    val times: LongArray,
    val u: Array<FloatArray>,       // [Stunde][j * nx + i], km/h nach Osten
    val v: Array<FloatArray>,       // km/h nach Norden
    val gust: Array<FloatArray>,    // Böen km/h
    val thunder: Array<FloatArray>  // Gewitter-Potenzial (Blitz-Potenzial bzw. aus CAPE abgeleitet)
) {
    /** Bilinear interpolierter Wert eines Feldes; NaN außerhalb. */
    fun sample(field: Array<FloatArray>, h: Int, lat: Double, lon: Double): Float {
        val fx = (lon - west) / (east - west) * (nx - 1)
        val fy = (north - lat) / (north - south) * (ny - 1)
        if (fx < 0 || fy < 0 || fx > nx - 1 || fy > ny - 1) return Float.NaN
        val i = min(floor(fx).toInt(), nx - 2)
        val j = min(floor(fy).toInt(), ny - 2)
        val tx = (fx - i).toFloat()
        val ty = (fy - j).toFloat()
        val arr = field[h]
        val a = arr[j * nx + i] * (1 - tx) + arr[j * nx + i + 1] * tx
        val b = arr[(j + 1) * nx + i] * (1 - tx) + arr[(j + 1) * nx + i + 1] * tx
        return a * (1 - ty) + b * ty
    }

    fun at(h: Int, lat: Double, lon: Double, out: FloatArray): Boolean {
        val uu = sample(u, h, lat, lon)
        if (uu.isNaN()) return false
        out[0] = uu
        out[1] = sample(v, h, lat, lon)
        return true
    }

    fun covers(bb: BoundingBox): Boolean =
        bb.latSouth >= south && bb.latNorth <= north && bb.lonWest >= west && bb.lonEast <= east

    companion object {
        /** Lädt ein Gitter (nx × ny Punkte) für den Ausschnitt; 48 Stunden ab jetzt. */
        fun fetch(bb: BoundingBox): WindGrid {
            // Ausschnitt etwas größer laden, damit kleines Verschieben keinen Neuabruf braucht
            val padLat = (bb.latNorth - bb.latSouth) * 0.35
            val padLon = (bb.lonEast - bb.lonWest) * 0.35
            val s = (bb.latSouth - padLat).coerceAtLeast(-80.0)
            val n = (bb.latNorth + padLat).coerceAtMost(80.0)
            val w = (bb.lonWest - padLon).coerceAtLeast(-179.9)
            val e = (bb.lonEast + padLon).coerceAtMost(179.9)
            // 7 × 9 Punkte: reicht für die glatten Windfelder, schont das Abruf-Kontingent
            val nx = 7
            val ny = 9
            val lats = StringBuilder()
            val lons = StringBuilder()
            for (j in 0 until ny) for (i in 0 until nx) {
                val lat = n - (n - s) * j / (ny - 1)
                val lon = w + (e - w) * i / (nx - 1)
                if (lats.isNotEmpty()) { lats.append(','); lons.append(',') }
                lats.append("%.3f".format(Locale.US, lat))
                lons.append("%.3f".format(Locale.US, lon))
            }
            val url = "https://api.open-meteo.com/v1/forecast?latitude=$lats&longitude=$lons" +
                "&hourly=wind_speed_10m,wind_direction_10m,wind_gusts_10m,lightning_potential,cape&models=icon_seamless" +
                "&forecast_hours=48&timeformat=unixtime&timezone=GMT"
            val body = Net.get(url, 25000)
            val arr: JSONArray = if (body.trimStart().startsWith("[")) JSONArray(body) else JSONArray().put(JSONObject(body))
            val first = arr.getJSONObject(0).getJSONObject("hourly")
            val tArr = first.getJSONArray("time")
            val nt = tArr.length()
            val times = LongArray(nt) { tArr.getLong(it) }
            val u = Array(nt) { FloatArray(nx * ny) }
            val v = Array(nt) { FloatArray(nx * ny) }
            val g = Array(nt) { FloatArray(nx * ny) }
            val th = Array(nt) { FloatArray(nx * ny) }
            fun JSONArray?.f(t: Int): Double = if (this == null || t >= length() || isNull(t)) Double.NaN else optDouble(t, Double.NaN)
            for (k in 0 until min(arr.length(), nx * ny)) {
                val h = arr.getJSONObject(k).getJSONObject("hourly")
                val sp = h.optJSONArray("wind_speed_10m")
                val dir = h.optJSONArray("wind_direction_10m")
                val gu = h.optJSONArray("wind_gusts_10m")
                val lpi = h.optJSONArray("lightning_potential")
                val cape = h.optJSONArray("cape")
                for (t in 0 until nt) {
                    val speed = sp.f(t).let { if (it.isNaN()) 0.0 else it }
                    val from = Math.toRadians(dir.f(t).let { if (it.isNaN()) 0.0 else it })
                    // Richtung = woher der Wind kommt → Bewegung in Gegenrichtung
                    u[t][k] = (-speed * sin(from)).toFloat()
                    v[t][k] = (-speed * cos(from)).toFloat()
                    g[t][k] = gu.f(t).let { if (it.isNaN()) speed else it }.toFloat()
                    // Blitz-Potenzial direkt; wo es fehlt (außerhalb Mitteleuropas), grob aus CAPE
                    val l = lpi.f(t)
                    val c = cape.f(t)
                    th[t][k] = when {
                        !l.isNaN() -> l.toFloat()
                        !c.isNaN() -> ((c - 300) / 120).coerceAtLeast(0.0).toFloat()
                        else -> 0f
                    }
                }
            }
            return WindGrid(s, n, w, e, nx, ny, times, u, v, g, th)
        }
    }
}

/** Farbskala für Windgeschwindigkeit (km/h). */
object WindColors {
    private val stops = floatArrayOf(0f, 8f, 16f, 25f, 35f, 48f, 62f, 80f, 100f)
    private val colors = intArrayOf(
        0xFF2B3A67.toInt(), 0xFF2F6DA8.toInt(), 0xFF2FA5A0.toInt(), 0xFF46B861.toInt(),
        0xFFB9C23A.toInt(), 0xFFE39B2E.toInt(), 0xFFD9493A.toInt(), 0xFFB1306E.toInt(), 0xFF7B2C9C.toInt()
    )
    fun of(kmh: Float): Int {
        if (kmh <= stops[0]) return colors[0]
        for (i in 1 until stops.size) if (kmh <= stops[i]) {
            return Ui.mix(colors[i - 1], colors[i], (kmh - stops[i - 1]) / (stops[i] - stops[i - 1]))
        }
        return colors.last()
    }
    val legendStops get() = stops
    val legendColors get() = colors
}

/** Farbskala für das Gewitter-Potenzial: durchsichtig bei 0, dann gelb → orange → rot → violett. */
object ThunderColors {
    private val stops = floatArrayOf(0f, 0.8f, 3f, 10f, 20f)
    private val colors = intArrayOf(0x00000000, 0xB3F5D547.toInt(), 0xCCFF8A3D.toInt(), 0xE6FF4F4F.toInt(), 0xF2C86BFF.toInt())
    fun of(v: Float): Int {
        if (v.isNaN() || v <= 0.15f) return 0
        if (v <= stops[1]) return Ui.mix(0x00F5D547, colors[1], v / stops[1])
        for (i in 2 until stops.size) if (v <= stops[i]) {
            return Ui.mix(colors[i - 1], colors[i], (v - stops[i - 1]) / (stops[i] - stops[i - 1]))
        }
        return colors.last()
    }
    fun level(v: Float): Int = when {
        v.isNaN() -> 0
        v >= 10 -> 3
        v >= 3 -> 2
        v >= 0.8f -> 1
        else -> 0
    }
    val legendColors get() = intArrayOf(0x40F5D547, colors[1], colors[2], colors[3], colors[4])
}

/**
 * Animierte Windkarte: farbige Fläche (Wind, Böen oder Gewitter) plus fließende Partikel
 * mit Schweif; Antippen zeigt die Werte an dieser Stelle.
 */
class WindOverlay(private val map: MapView, private val p: Ui.Palette, private val density: Float) : Overlay() {

    enum class Layer { WIND, GUST, THUNDER }

    var grid: WindGrid? = null
        set(v) { field = v; dirty = true }
    var hour = 0
        set(v) { field = v; dirty = true }
    var layer = Layer.WIND
        set(v) { field = v; dirty = true }
    /** Angetippte Stelle (Info-Fähnchen) oder null. */
    var tapped: GeoPoint? = null
    var onTap: (() -> Unit)? = null

    // Bildschirm-Feld (Geschwindigkeit in Pixel pro Bild)
    private val cell = (14 * density).toInt().coerceAtLeast(8)
    private var cols = 0
    private var rows = 0
    private var fu = FloatArray(0)
    private var fv = FloatArray(0)
    private var fs = FloatArray(0)
    private var colorBmp: Bitmap? = null
    private var trails: Bitmap? = null
    private var trailCanvas: Canvas? = null
    private var dirty = true

    private val rnd = Random()
    private var px = FloatArray(0)
    private var py = FloatArray(0)
    private var age = IntArray(0)

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.15f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val bubble = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bubbleText = Paint(Paint.ANTI_ALIAS_FLAG)
    private val tmp = FloatArray(2)
    private val src = Rect()
    private val dst = RectF()
    private val pt = Point()

    var running = false

    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            map.invalidate()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    fun start() {
        if (running) return
        running = true
        Choreographer.getInstance().postFrameCallback(frameCb)
    }

    fun stop() { running = false }

    /** Karte wurde bewegt: Schweife verwerfen, Feld neu berechnen. */
    fun moved() {
        dirty = true
        trailCanvas?.drawColor(0, PorterDuff.Mode.CLEAR)
    }

    private fun rebuild(w: Int, h: Int) {
        val g = grid ?: return
        if (w <= 0 || h <= 0) return
        cols = w / cell + 2
        rows = h / cell + 2
        val n = cols * rows
        if (fu.size != n) { fu = FloatArray(n); fv = FloatArray(n); fs = FloatArray(n) }
        val pj = map.projection
        val hr = hour.coerceIn(0, g.times.size - 1)
        // Pixel pro Bild je km/h – konstant über alle Zoomstufen
        val k = 0.028f * density
        val colorsArr = IntArray(n)
        val gp = GeoPoint(0.0, 0.0)
        for (r in 0 until rows) for (c in 0 until cols) {
            val i = r * cols + c
            val geo = pj.fromPixels(c * cell, r * cell, gp)
            if (g.at(hr, geo.latitude, geo.longitude, tmp)) {
                fu[i] = tmp[0] * k
                fv[i] = -tmp[1] * k
                val s = sqrt(tmp[0] * tmp[0] + tmp[1] * tmp[1])
                fs[i] = s
                colorsArr[i] = when (layer) {
                    Layer.WIND -> Ui.withAlpha(WindColors.of(s), 78)
                    Layer.GUST -> Ui.withAlpha(WindColors.of(g.sample(g.gust, hr, geo.latitude, geo.longitude)), 78)
                    Layer.THUNDER -> ThunderColors.of(g.sample(g.thunder, hr, geo.latitude, geo.longitude))
                }
            } else {
                fu[i] = 0f; fv[i] = 0f; fs[i] = Float.NaN
                colorsArr[i] = 0
            }
        }
        val cb = colorBmp
        colorBmp = if (cb != null && cb.width == cols && cb.height == rows) cb.also { it.setPixels(colorsArr, 0, cols, 0, 0, cols, rows) }
        else Bitmap.createBitmap(cols, rows, Bitmap.Config.ARGB_8888).also { it.setPixels(colorsArr, 0, cols, 0, 0, cols, rows) }

        if (trails == null || trails!!.width != w || trails!!.height != h) {
            trails = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            trailCanvas = Canvas(trails!!)
        } else trailCanvas?.drawColor(0, PorterDuff.Mode.CLEAR)

        val count = ((w.toLong() * h) / (90 * 90 * density * density / 4)).toInt().coerceIn(420, 1200)
        if (px.size != count) { px = FloatArray(count); py = FloatArray(count); age = IntArray(count) }
        for (i in 0 until count) respawn(i, w, h, randomAge = true)
        dirty = false
    }

    private fun respawn(i: Int, w: Int, h: Int, randomAge: Boolean = false) {
        px[i] = rnd.nextFloat() * w
        py[i] = rnd.nextFloat() * h
        age[i] = if (randomAge) rnd.nextInt(90) else 60 + rnd.nextInt(40)
    }

    /** Wert des Feldes an Bildschirmposition (bilinear). */
    private fun sample(x: Float, y: Float, out: FloatArray): Boolean {
        val fx = x / cell
        val fy = y / cell
        val c = fx.toInt()
        val r = fy.toInt()
        if (c < 0 || r < 0 || c >= cols - 1 || r >= rows - 1) return false
        val tx = fx - c
        val ty = fy - r
        val i = r * cols + c
        if (fs[i].isNaN()) return false
        out[0] = (fu[i] * (1 - tx) + fu[i + 1] * tx) * (1 - ty) + (fu[i + cols] * (1 - tx) + fu[i + cols + 1] * tx) * ty
        out[1] = (fv[i] * (1 - tx) + fv[i + 1] * tx) * (1 - ty) + (fv[i + cols] * (1 - tx) + fv[i + cols + 1] * tx) * ty
        return true
    }

    /** Werte an einer Position: Wind km/h, Richtung (woher), Böen km/h, Gewitter-Potenzial. */
    class Values(val speed: Float, val fromDeg: Float, val gust: Float, val thunder: Float)

    /** Exakte Werte für den angetippten Punkt (eigene Abfrage), sobald geladen. */
    class Exact(val lat: Double, val lon: Double, val times: LongArray, val speed: FloatArray, val dir: FloatArray, val gust: FloatArray, val thunder: FloatArray)
    @Volatile var exact: Exact? = null

    fun valuesAt(geo: GeoPoint): Values? {
        val g = grid ?: return null
        val h = hour.coerceIn(0, g.times.size - 1)
        val ex = exact
        if (ex != null && abs(ex.lat - geo.latitude) < 1e-6 && abs(ex.lon - geo.longitude) < 1e-6) {
            val i = ex.times.indexOfLast { it <= g.times[h] }
            if (i >= 0) return Values(ex.speed[i], ex.dir[i], ex.gust[i], ex.thunder[i])
        }
        if (!g.at(h, geo.latitude, geo.longitude, tmp)) return null
        val s = sqrt(tmp[0] * tmp[0] + tmp[1] * tmp[1])
        var deg = Math.toDegrees(kotlin.math.atan2(-tmp[0].toDouble(), -tmp[1].toDouble())).toFloat()
        if (deg < 0) deg += 360f
        return Values(s, deg, g.sample(g.gust, h, geo.latitude, geo.longitude), g.sample(g.thunder, h, geo.latitude, geo.longitude))
    }

    override fun onSingleTapConfirmed(e: MotionEvent, mapView: MapView): Boolean {
        val t = tapped
        if (t != null) {
            mapView.projection.toPixels(t, pt)
            // Antippen auf das Fähnchen bzw. den Punkt blendet es aus
            if (abs(pt.x - e.x) < 70 * density && pt.y - e.y in -20 * density..90 * density) {
                tapped = null
                onTap?.invoke()
                return true
            }
        }
        val g = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt())
        tapped = GeoPoint(g.latitude, g.longitude)
        onTap?.invoke()
        return true
    }

    override fun draw(c: Canvas, mv: MapView, shadow: Boolean) {
        if (shadow || grid == null) return
        val w = mv.width
        val h = mv.height
        if (dirty || trails == null) rebuild(w, h)
        val cb = colorBmp ?: return

        // 1) Farbfläche
        src.set(0, 0, cols, rows)
        dst.set(0f, 0f, cols * cell.toFloat(), rows * cell.toFloat())
        c.drawBitmap(cb, src, dst, bmpPaint)

        // 2) Partikel weiterbewegen und Schweife zeichnen
        val tc = trailCanvas ?: return
        tc.drawColor(Color.argb(222, 0, 0, 0), PorterDuff.Mode.DST_IN)
        for (i in px.indices) {
            val x = px[i]
            val y = py[i]
            if (age[i]-- <= 0 || !sample(x, y, tmp)) { respawn(i, w, h); continue }
            val nx = x + tmp[0]
            val ny = y + tmp[1]
            val sp = sqrt(tmp[0] * tmp[0] + tmp[1] * tmp[1])
            linePaint.alpha = (80 + min(120f, sp * 50f)).toInt()
            tc.drawLine(x, y, nx, ny, linePaint)
            px[i] = nx
            py[i] = ny
            if (nx < 0 || ny < 0 || nx > w || ny > h) respawn(i, w, h)
        }
        c.drawBitmap(trails!!, 0f, 0f, null)

        // 3) Info-Fähnchen an der angetippten Stelle
        tapped?.let { drawBubble(c, mv, it) }
    }

    private fun drawBubble(c: Canvas, mv: MapView, geo: GeoPoint) {
        val v = valuesAt(geo)
        mv.projection.toPixels(geo, pt)
        val x = pt.x.toFloat()
        val y = pt.y.toFloat()
        val d = density

        // Punkt
        bubble.color = 0x66000000
        c.drawCircle(x, y, 8 * d, bubble)
        bubble.color = Color.WHITE
        c.drawCircle(x, y, 6 * d, bubble)
        bubble.color = p.accent
        c.drawCircle(x, y, 4 * d, bubble)

        val line1: String
        val line2: String
        val line3: String?
        if (v == null) {
            line1 = "Keine Daten"; line2 = "an dieser Stelle"; line3 = null
        } else {
            line1 = "${v.speed.roundToInt()} km/h aus ${Fmt.dir(v.fromDeg.toDouble())}"
            line2 = "Böen ${if (v.gust.isNaN()) "–" else v.gust.roundToInt().toString()} km/h"
            val tl = ThunderColors.level(v.thunder)
            line3 = if (layer == Layer.THUNDER || tl >= 1) "Gewitterrisiko ${Thunder.NAMES[tl]}" else null
        }
        bubbleText.typeface = Ui.TF_MEDIUM
        bubbleText.textSize = 15 * d
        val w1 = bubbleText.measureText(line1)
        bubbleText.typeface = Ui.TF_REGULAR
        bubbleText.textSize = 12.5f * d
        val w2 = max(bubbleText.measureText(line2), line3?.let { bubbleText.measureText(it) } ?: 0f)
        val bw = max(w1, w2) + 28 * d
        val bh = (if (line3 != null) 74 else 56) * d
        var left = x - bw / 2
        left = left.coerceIn(8 * d, mv.width - bw - 8 * d)
        val bottom = y - 14 * d
        val top = bottom - bh
        bubble.color = Ui.withAlpha(p.surface, 245)
        c.drawRoundRect(RectF(left, top, left + bw, bottom), 16 * d, 16 * d, bubble)
        bubble.style = Paint.Style.STROKE
        bubble.strokeWidth = 1f
        bubble.color = p.border
        c.drawRoundRect(RectF(left, top, left + bw, bottom), 16 * d, 16 * d, bubble)
        bubble.style = Paint.Style.FILL

        bubbleText.typeface = Ui.TF_MEDIUM
        bubbleText.textSize = 15 * d
        bubbleText.color = if (v != null && max(v.speed, v.gust.takeIf { !it.isNaN() } ?: 0f) >= 62) p.warn else p.text
        c.drawText(line1, left + 14 * d, top + 24 * d, bubbleText)
        bubbleText.typeface = Ui.TF_REGULAR
        bubbleText.textSize = 12.5f * d
        bubbleText.color = if (v != null && !v.gust.isNaN() && v.gust >= 62) p.warn else p.muted
        c.drawText(line2, left + 14 * d, top + 43 * d, bubbleText)
        if (line3 != null && v != null) {
            val tl = ThunderColors.level(v.thunder)
            bubbleText.color = if (tl >= 1) thunderColor(tl, p) else p.muted
            c.drawText(line3, left + 14 * d, top + 61 * d, bubbleText)
        }
    }

    override fun onDetach(mapView: MapView?) {
        stop()
        trails?.recycle(); trails = null
        colorBmp?.recycle(); colorBmp = null
    }
}

/** Wind-Seite: animierte Karte, Ebenen Wind/Böen/Gewitter, Zeitleiste (48 h), Werte per Antippen. */
@SuppressLint("ViewConstructor")
class WindPage(ctx: Context, private val p: Ui.Palette, center: GeoPoint) : FrameLayout(ctx) {

    private fun dp(v: Number) = Ui.dp(context, v)
    private fun dpf(v: Number) = Ui.dpf(context, v)
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

    val map: MapView = MapSupport.createMap(ctx, p, center, 6.0, bright = true)
    private val d = resources.displayMetrics.density
    private val overlay = WindOverlay(map, p, d)
    private val marker = PlaceMarker(p, d)
    private val timeline = TimelineView(ctx, p)
    private val timeLabel = Ui.tv(ctx, "", 17f, p.text, true)
    private val subLabel = Ui.tv(ctx, "", 12.5f, p.muted).apply {
        maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
    }
    private val legend = WindLegend(ctx, p)
    private val layerButtons = ArrayList<TextView>()
    private val main = Handler(Looper.getMainLooper())
    private var fetching = false
    private var lastBox: BoundingBox? = null

    private val refetch = Runnable { ensureData() }

    init {
        setBackgroundColor(p.bg)
        map.overlays.add(overlay)
        map.overlays.add(marker)
        MapSupport.addLabels(map, p, bright = true)   // Ortsnamen über Farbfläche und Standortpunkt
        marker.point = center
        map.setMaxZoomLevel(10.0)
        addView(map, LayoutParams(MATCH, MATCH))
        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean { changed(); return false }
            override fun onZoom(event: ZoomEvent?): Boolean { changed(); return false }
        })
        overlay.onTap = {
            map.invalidate()
            overlay.tapped?.let { loadExact(it) }
        }

        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rect(Ui.withAlpha(p.surface, 240), dpf(24), p.border, 1)
            setPadding(dp(16), dp(12), dp(14), dp(10))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(timeLabel)
                    addView(subLabel, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(legend, LinearLayout.LayoutParams(dp(132), dp(30)))
            })
            addView(timeline, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        }
        addView(panel, LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(dp(12), 0, dp(12), dp(12)) })

        // Ebenen-Umschalter oben links: Wind | Böen | Gewitter
        val seg = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = Ui.rect(Ui.withAlpha(p.surface, 240), dpf(22), p.border, 1)
            setPadding(dp(4), dp(4), dp(4), dp(4))
        }
        for ((i, name) in listOf("Wind", "Böen", "Gewitter").withIndex()) {
            val b = Ui.tv(ctx, name, 14f, p.text, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(8), dp(14), dp(8))
                setOnClickListener { setLayer(WindOverlay.Layer.values()[i]) }
            }
            layerButtons.add(b)
            seg.addView(b, LinearLayout.LayoutParams(WRAP, WRAP))
        }
        addView(seg, LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), 0, 0) })

        addView(FrameLayout(ctx).apply {
            background = Ui.pressable(p, Ui.withAlpha(p.surface, 240), dpf(22), p.border, 1)
            addView(GlyphView(ctx, GlyphView.Kind.LOCATE, p.text), LayoutParams(dp(22), dp(22), Gravity.CENTER))
            setOnClickListener { marker.point?.let { map.controller.animateTo(it, 7.0, 500L) } }
        }, LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(12), dp(12), 0) })

        addView(Ui.tv(ctx, "Daten: DWD ICON via Open-Meteo  ·  Karte © OpenStreetMap, CARTO", 9.5f, p.muted).apply {
            setShadowLayer(4f, 0f, 0f, p.bg)
        }, LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(18), dp(4)) })

        timeline.onChange = { setHour(it) }
        setLayer(WindOverlay.Layer.WIND)
        map.post { ensureData() }
    }

    private fun setLayer(l: WindOverlay.Layer) {
        overlay.layer = l
        overlay.moved()
        legend.layer = l
        for ((i, b) in layerButtons.withIndex()) {
            val on = i == l.ordinal
            b.background = if (on) Ui.rect(p.accent, dpf(18)) else Ui.pressable(p, Ui.withAlpha(p.surface, 0), dpf(18))
            b.setTextColor(if (on) p.onAccent else p.text)
        }
        updateSub()
        map.invalidate()
    }

    private fun changed() {
        overlay.moved()
        if (overlay.layer == WindOverlay.Layer.THUNDER) updateSub()
        main.removeCallbacks(refetch)
        main.postDelayed(refetch, 1500)
    }

    fun setCenter(g: GeoPoint) {
        marker.point = g
        map.controller.setCenter(g)
        changed()
    }

    private var lastFetchAt = 0L
    private val cache = ArrayList<Pair<BoundingBox, WindGrid>>()   // zuletzt geladene Ausschnitte

    /** Passt ein Gitter zum aktuellen Ausschnitt (deckt ihn ab, nicht viel zu grob, nicht veraltet)? */
    private fun fits(box: BoundingBox, g: WindGrid, bb: BoundingBox): Boolean {
        val tooCoarse = (bb.latNorth - bb.latSouth) < (box.latNorth - box.latSouth) / 3.5
        val stale = System.currentTimeMillis() / 1000 - g.times[0] > 3 * 3600
        return g.covers(bb) && !tooCoarse && !stale
    }

    private fun ensureData() {
        if (fetching || map.width == 0) return
        val bb = map.boundingBox
        val g = overlay.grid
        val last = lastBox
        if (g != null && last != null && fits(last, g, bb)) return
        // Bereits geladenen Ausschnitt wiederverwenden
        cache.lastOrNull { fits(it.first, it.second, bb) }?.let { (box, grid) ->
            applyGrid(box, grid)
            return
        }
        // Höchstens alle 30 s neu laden (Abruf-Kontingent schonen)
        val wait = 30_000 - (System.currentTimeMillis() - lastFetchAt)
        if (g != null && wait > 0) {
            main.removeCallbacks(refetch)
            main.postDelayed(refetch, wait)
            return
        }
        fetching = true
        lastFetchAt = System.currentTimeMillis()
        subLabel.text = "Lade Daten …"
        Thread {
            val res = runCatching { WindGrid.fetch(bb) }
            main.post {
                fetching = false
                res.onSuccess { grid ->
                    cache.add(bb to grid)
                    while (cache.size > 6) cache.removeAt(0)
                    applyGrid(bb, grid)
                }.onFailure { e ->
                    val limit = (e.message ?: "").contains("limit", ignoreCase = true)
                    subLabel.text = when {
                        overlay.grid != null && limit -> "Abruflimit erreicht – zeige vorhandene Daten"
                        overlay.grid != null -> "Aktualisierung fehlgeschlagen – zeige vorhandene Daten"
                        limit -> "Abruflimit erreicht – bitte kurz warten"
                        else -> "Daten nicht verfügbar – keine Verbindung?"
                    }
                    // später automatisch erneut versuchen
                    main.removeCallbacks(refetch)
                    main.postDelayed(refetch, if (limit) 90_000 else 30_000)
                }
            }
        }.start()
    }

    private fun applyGrid(bb: BoundingBox, grid: WindGrid) {
        run {
            run {
                run {
                    lastBox = bb
                    val prevT = overlay.grid?.times?.getOrNull(overlay.hour)
                    overlay.grid = grid
                    timeline.steps = grid.times.size
                    val now = System.currentTimeMillis() / 1000
                    val nowIdx = grid.times.indexOfLast { it <= now }.coerceAtLeast(0)
                    timeline.nowIndex = nowIdx
                    val keep = prevT?.let { t -> grid.times.indexOfFirst { it == t } } ?: -1
                    setHour(if (keep >= 0) keep else nowIdx)
                    map.invalidate()
                }
            }
        }
    }

    private val exactCache = HashMap<String, WindOverlay.Exact>()

    /** Genaue Werte für den angetippten Punkt laden (eine einzelne, günstige Abfrage). */
    private fun loadExact(g: GeoPoint) {
        val key = "%.2f,%.2f".format(Locale.US, g.latitude, g.longitude)
        exactCache[key]?.let { overlay.exact = WindOverlay.Exact(g.latitude, g.longitude, it.times, it.speed, it.dir, it.gust, it.thunder); map.invalidate(); return }
        Thread {
            val ex = runCatching {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f".format(Locale.US, g.latitude, g.longitude) +
                    "&hourly=wind_speed_10m,wind_direction_10m,wind_gusts_10m,lightning_potential,cape&models=icon_seamless" +
                    "&forecast_hours=48&past_hours=1&timeformat=unixtime&timezone=GMT"
                val h = JSONObject(Net.get(url, 15000)).getJSONObject("hourly")
                val t = h.getJSONArray("time")
                val n = t.length()
                fun arr(name: String, conv: (Double) -> Double = { it }): FloatArray {
                    val a = h.optJSONArray(name)
                    return FloatArray(n) { i -> if (a == null || a.isNull(i)) Float.NaN else conv(a.getDouble(i)).toFloat() }
                }
                val lpi = arr("lightning_potential")
                val cape = arr("cape")
                WindOverlay.Exact(g.latitude, g.longitude, LongArray(n) { t.getLong(it) },
                    arr("wind_speed_10m"), arr("wind_direction_10m"), arr("wind_gusts_10m"),
                    FloatArray(n) { i -> if (!lpi[i].isNaN()) lpi[i] else if (!cape[i].isNaN()) ((cape[i] - 300) / 120).coerceAtLeast(0f) else 0f })
            }.getOrNull()
            main.post {
                if (ex != null) {
                    exactCache[key] = ex
                    if (overlay.tapped?.let { abs(it.latitude - g.latitude) < 1e-6 } == true) { overlay.exact = ex; map.invalidate() }
                }
            }
        }.start()
    }

    private fun setHour(i: Int) {
        val g = overlay.grid ?: return
        overlay.hour = i
        overlay.moved()
        timeline.setIndex(i, false)
        val t = g.times[i.coerceIn(0, g.times.size - 1)]
        val cal = Calendar.getInstance().apply { timeInMillis = t * 1000 }
        val day = Forecast.WEEKDAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]
        val now = System.currentTimeMillis() / 1000
        val rel = when {
            t <= now && now - t < 3600 -> "Jetzt"
            else -> "in ${((t - now) / 3600.0).roundToInt()} h"
        }
        timeLabel.text = "$day %02d:00  ·  $rel".format(cal.get(Calendar.HOUR_OF_DAY))
        updateSub()
    }

    private fun updateSub() {
        if (fetching) return
        subLabel.text = when (overlay.layer) {
            WindOverlay.Layer.WIND -> "Mittelwind  ·  antippen für Werte"
            WindOverlay.Layer.GUST -> "Böen  ·  antippen für Werte"
            WindOverlay.Layer.THUNDER ->
                if (thunderInView() < 1) "Kein Gewitterpotenzial im Ausschnitt" else "Gewitter-Potenzial  ·  antippen für Werte"
        }
    }

    /** Höchste Gewitterstufe (0–3) im sichtbaren Kartenausschnitt zur gewählten Stunde. */
    private fun thunderInView(): Int {
        val g = overlay.grid ?: return 0
        val h = overlay.hour.coerceIn(0, g.times.size - 1)
        val bb = map.boundingBox
        var best = 0f
        for (j in 0 until g.ny) {
            val lat = g.north - (g.north - g.south) * j / (g.ny - 1).coerceAtLeast(1)
            if (lat > bb.latNorth || lat < bb.latSouth) continue
            for (i in 0 until g.nx) {
                val lon = g.west + (g.east - g.west) * i / (g.nx - 1).coerceAtLeast(1)
                if (lon < bb.lonWest || lon > bb.lonEast) continue
                val v = g.thunder[h][j * g.nx + i]
                if (!v.isNaN() && v > best) best = v
            }
        }
        return ThunderColors.level(best)
    }

    fun onResume() {
        map.onResume()
        overlay.start()
        ensureData()
    }

    fun onPause() {
        overlay.stop()
        map.onPause()
    }

    fun destroy() { overlay.stop(); map.onDetach() }
}

/** Legende: Windfarben (km/h) bzw. Gewitterstufen. */
@SuppressLint("ViewConstructor")
class WindLegend(ctx: Context, private val p: Ui.Palette) : View(ctx) {
    var layer = WindOverlay.Layer.WIND
        set(v) { field = v; invalidate() }
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.5f * resources.displayMetrics.density }

    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        text.color = p.muted
        if (layer == WindOverlay.Layer.THUNDER) {
            paint.shader = LinearGradient(0f, 0f, w, 0f, ThunderColors.legendColors, null, Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(0f, 4 * d, w, 10 * d), 3 * d, 3 * d, paint)
            paint.shader = null
            text.textAlign = Paint.Align.LEFT
            c.drawText("gering", 0f, 24 * d, text)
            text.textAlign = Paint.Align.CENTER
            c.drawText("mäßig", w * 0.5f, 24 * d, text)
            text.textAlign = Paint.Align.RIGHT
            c.drawText("hoch", w, 24 * d, text)
            return
        }
        val stops = WindColors.legendStops
        val max = stops.last()
        paint.shader = LinearGradient(0f, 0f, w, 0f, WindColors.legendColors, FloatArray(stops.size) { stops[it] / max }, Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(0f, 4 * d, w, 10 * d), 3 * d, 3 * d, paint)
        paint.shader = null
        // Zahlen über der Skala verteilt, Einheit ganz rechts – nichts überlappt
        text.textAlign = Paint.Align.LEFT
        c.drawText("0", 0f, 24 * d, text)
        text.textAlign = Paint.Align.CENTER
        c.drawText("25", 25 / max * w, 24 * d, text)
        c.drawText("50", 50 / max * w, 24 * d, text)
        text.textAlign = Paint.Align.RIGHT
        c.drawText("100 km/h", w, 24 * d, text)
    }
}
