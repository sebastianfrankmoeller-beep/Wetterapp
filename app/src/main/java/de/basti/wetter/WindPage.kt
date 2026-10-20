package de.basti.wetter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
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
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Windfeld auf einem regelmäßigen Längen-/Breitengitter, stündlich. */
class WindGrid(
    val south: Double, val north: Double, val west: Double, val east: Double,
    val nx: Int, val ny: Int,
    val times: LongArray,
    val u: Array<FloatArray>,   // [Stunde][j * nx + i], km/h nach Osten
    val v: Array<FloatArray>    // km/h nach Norden
) {
    /** Bilinear interpolierter Wind (u, v) an einer Position; null außerhalb. */
    fun at(h: Int, lat: Double, lon: Double, out: FloatArray): Boolean {
        val fx = (lon - west) / (east - west) * (nx - 1)
        val fy = (north - lat) / (north - south) * (ny - 1)
        if (fx < 0 || fy < 0 || fx > nx - 1 || fy > ny - 1) return false
        val i = min(floor(fx).toInt(), nx - 2)
        val j = min(floor(fy).toInt(), ny - 2)
        val tx = (fx - i).toFloat()
        val ty = (fy - j).toFloat()
        val uu = u[h]; val vv = v[h]
        fun lerp(arr: FloatArray): Float {
            val a = arr[j * nx + i] * (1 - tx) + arr[j * nx + i + 1] * tx
            val b = arr[(j + 1) * nx + i] * (1 - tx) + arr[(j + 1) * nx + i + 1] * tx
            return a * (1 - ty) + b * ty
        }
        out[0] = lerp(uu)
        out[1] = lerp(vv)
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
            val nx = 9
            val ny = 12
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
                "&hourly=wind_speed_10m,wind_direction_10m&models=icon_seamless" +
                "&forecast_hours=48&timeformat=unixtime&timezone=GMT"
            val body = Net.get(url, 25000)
            val arr: JSONArray = if (body.trimStart().startsWith("[")) JSONArray(body) else JSONArray().put(JSONObject(body))
            val first = arr.getJSONObject(0).getJSONObject("hourly")
            val tArr = first.getJSONArray("time")
            val nt = tArr.length()
            val times = LongArray(nt) { tArr.getLong(it) }
            val u = Array(nt) { FloatArray(nx * ny) }
            val v = Array(nt) { FloatArray(nx * ny) }
            for (k in 0 until min(arr.length(), nx * ny)) {
                val h = arr.getJSONObject(k).getJSONObject("hourly")
                val sp = h.getJSONArray("wind_speed_10m")
                val dir = h.getJSONArray("wind_direction_10m")
                for (t in 0 until nt) {
                    val speed = if (sp.isNull(t)) 0.0 else sp.getDouble(t)
                    val from = Math.toRadians(if (dir.isNull(t)) 0.0 else dir.getDouble(t))
                    // Richtung = woher der Wind kommt → Bewegung in Gegenrichtung
                    u[t][k] = (-speed * sin(from)).toFloat()
                    v[t][k] = (-speed * cos(from)).toFloat()
                }
            }
            return WindGrid(s, n, w, e, nx, ny, times, u, v)
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

/**
 * Animierte Windkarte: farbige Windstärke-Fläche plus fließende Partikel
 * mit Schweif, die der Strömung folgen.
 */
class WindOverlay(private val map: MapView, private val density: Float) : Overlay() {

    var grid: WindGrid? = null
        set(v) { field = v; dirty = true }
    var hour = 0
        set(v) { field = v; dirty = true }

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

    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 150 }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = 1.5f * density
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val tmp = FloatArray(2)
    private val src = Rect()
    private val dst = RectF()

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
        // Pixel pro Bild je km/h – konstant über alle Zoomstufen (wie bei bekannten Wetterkarten)
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
                colorsArr[i] = WindColors.of(s)
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

        val count = ((w.toLong() * h) / (90 * 90 * density * density / 4)).toInt().coerceIn(600, 2600)
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

    /** Wind in km/h an einer Bildschirmposition (für die Anzeige in der Mitte). */
    fun speedAt(x: Float, y: Float): Pair<Float, Float>? {
        val g = grid ?: return null
        val geo = map.projection.fromPixels(x.toInt(), y.toInt())
        if (!g.at(hour.coerceIn(0, g.times.size - 1), geo.latitude, geo.longitude, tmp)) return null
        val s = sqrt(tmp[0] * tmp[0] + tmp[1] * tmp[1])
        // Richtung, aus der der Wind kommt
        var deg = Math.toDegrees(kotlin.math.atan2(-tmp[0].toDouble(), -tmp[1].toDouble())).toFloat()
        if (deg < 0) deg += 360f
        return s to deg
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
        tc.drawColor(Color.argb(232, 0, 0, 0), PorterDuff.Mode.DST_IN)
        for (i in px.indices) {
            val x = px[i]
            val y = py[i]
            if (age[i]-- <= 0 || !sample(x, y, tmp)) { respawn(i, w, h); continue }
            val nx = x + tmp[0]
            val ny = y + tmp[1]
            val sp = sqrt(tmp[0] * tmp[0] + tmp[1] * tmp[1])
            linePaint.alpha = (110 + min(145f, sp * 60f)).toInt()
            tc.drawLine(x, y, nx, ny, linePaint)
            px[i] = nx
            py[i] = ny
            if (nx < 0 || ny < 0 || nx > w || ny > h) respawn(i, w, h)
        }
        c.drawBitmap(trails!!, 0f, 0f, null)
    }

    override fun onDetach(mapView: MapView?) {
        stop()
        trails?.recycle(); trails = null
        colorBmp?.recycle(); colorBmp = null
    }
}

/** Wind-Seite: animierte Karte, Zeitleiste (48 h), Wert in der Kartenmitte. */
@SuppressLint("ViewConstructor")
class WindPage(ctx: Context, private val p: Ui.Palette, center: GeoPoint) : FrameLayout(ctx) {

    private fun dp(v: Number) = Ui.dp(context, v)
    private fun dpf(v: Number) = Ui.dpf(context, v)
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

    val map: MapView = MapSupport.createMap(ctx, p, center, 6.0)
    private val d = resources.displayMetrics.density
    private val overlay = WindOverlay(map, d)
    private val marker = PlaceMarker(p, d)
    private val timeline = TimelineView(ctx, p)
    private val timeLabel = Ui.tv(ctx, "", 17f, p.text, true)
    private val subLabel = Ui.tv(ctx, "", 12.5f, p.muted)
    private val readout = Ui.tv(ctx, "", 14f, p.text, true)
    private val main = Handler(Looper.getMainLooper())
    private var fetching = false
    private var lastBox: BoundingBox? = null
    private var visibleNow = false

    private val refetch = Runnable { ensureData() }

    init {
        setBackgroundColor(p.bg)
        map.overlays.add(overlay)
        map.overlays.add(MapSupport.labelsOverlay(ctx, p))
        map.overlays.add(marker)
        marker.point = center
        map.setMaxZoomLevel(10.0)
        addView(map, LayoutParams(MATCH, MATCH))
        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean { changed(); return false }
            override fun onZoom(event: ZoomEvent?): Boolean { changed(); return false }
        })

        // Fadenkreuz in der Mitte
        addView(object : View(ctx) {
            private val pt = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = 2 * d; color = p.text }
            override fun onDraw(c: Canvas) {
                val cx = width / 2f; val cy = height / 2f
                c.drawCircle(cx, cy, 6 * d, pt)
                c.drawLine(cx - 14 * d, cy, cx - 9 * d, cy, pt); c.drawLine(cx + 9 * d, cy, cx + 14 * d, cy, pt)
                c.drawLine(cx, cy - 14 * d, cx, cy - 9 * d, pt); c.drawLine(cx, cy + 9 * d, cx, cy + 14 * d, pt)
            }
        }, LayoutParams(dp(32), dp(32), Gravity.CENTER))

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
                addView(WindLegend(ctx, p), LinearLayout.LayoutParams(dp(132), dp(30)))
            })
            addView(timeline, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        }
        addView(panel, LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(dp(12), 0, dp(12), dp(12)) })

        addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rect(Ui.withAlpha(p.surface, 235), dpf(18), p.border, 1)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            addView(Ui.tv(ctx, "Wind (10 m)", 15f, p.text, true))
            addView(readout, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
        }, LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), 0, 0) })

        addView(FrameLayout(ctx).apply {
            background = Ui.pressable(p, Ui.withAlpha(p.surface, 240), dpf(22), p.border, 1)
            addView(GlyphView(ctx, GlyphView.Kind.LOCATE, p.text), LayoutParams(dp(22), dp(22), Gravity.CENTER))
            setOnClickListener { marker.point?.let { map.controller.animateTo(it, 7.0, 500L) } }
        }, LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(12), dp(12), 0) })

        addView(Ui.tv(ctx, "Wind: DWD ICON via Open-Meteo  ·  Karte © OpenStreetMap, CARTO", 9.5f, p.muted).apply {
            setShadowLayer(4f, 0f, 0f, p.bg)
        }, LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(18), dp(4)) })

        timeline.onChange = { setHour(it) }
        map.post { ensureData() }
    }

    private fun changed() {
        overlay.moved()
        main.removeCallbacks(refetch)
        main.postDelayed(refetch, 700)
        updateReadout()
    }

    fun setCenter(g: GeoPoint) {
        marker.point = g
        map.controller.setCenter(g)
        changed()
    }

    private fun ensureData() {
        if (fetching || map.width == 0) return
        val bb = map.boundingBox
        val g = overlay.grid
        val last = lastBox
        val zoomedIn = last != null && (bb.latNorth - bb.latSouth) < (last.latNorth - last.latSouth) / 3.5
        val stale = g != null && System.currentTimeMillis() / 1000 - g.times[0] > 3 * 3600
        if (g != null && g.covers(bb) && !zoomedIn && !stale) return
        fetching = true
        subLabel.text = "Lade Winddaten …"
        Thread {
            val res = runCatching { WindGrid.fetch(bb) }
            main.post {
                fetching = false
                res.onSuccess { grid ->
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
                }.onFailure {
                    subLabel.text = "Winddaten nicht verfügbar – Internet?"
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
        subLabel.text = "Modell: DWD ICON (D2/EU)"
        updateReadout()
    }

    private fun updateReadout() {
        val r = overlay.speedAt(map.width / 2f, map.height / 2f)
        readout.text = if (r == null) "–" else "${r.first.roundToInt()} km/h aus ${Fmt.dir(r.second.toDouble())}"
        readout.setTextColor(if (r != null && r.first >= 50) p.warn else p.text)
    }

    fun onResume() {
        visibleNow = true
        map.onResume()
        overlay.start()
        ensureData()
    }

    fun onPause() {
        visibleNow = false
        overlay.stop()
        map.onPause()
    }

    fun destroy() { overlay.stop(); map.onDetach() }
}

/** Legende der Windfarben. */
@SuppressLint("ViewConstructor")
class WindLegend(ctx: Context, private val p: Ui.Palette) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.5f * resources.displayMetrics.density; textAlign = Paint.Align.CENTER }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        val stops = WindColors.legendStops
        val max = stops.last()
        paint.shader = LinearGradient(0f, 0f, w, 0f, WindColors.legendColors, FloatArray(stops.size) { stops[it] / max }, Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(0f, 4 * d, w, 10 * d), 3 * d, 3 * d, paint)
        paint.shader = null
        text.color = p.muted
        for (v in listOf(0, 25, 50, 75, 100)) {
            val x = (v / max * w).coerceIn(8 * d, w - 10 * d)
            c.drawText(if (v == 100) "100 km/h" else "$v", if (v == 100) x - 12 * d else x, 24 * d, text)
        }
    }
}
