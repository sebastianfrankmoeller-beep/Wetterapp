package de.basti.wetter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import org.osmdroid.events.MapListener
import org.osmdroid.events.ScrollEvent
import org.osmdroid.events.ZoomEvent
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.LinkedBlockingDeque
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sinh
import kotlin.math.tan

/** Web-Mercator-Hilfen für Kacheln. */
object Tiles {
    const val O = 20037508.342789244

    fun lonOf(x: Double, z: Int) = x / (1 shl z) * 360.0 - 180.0
    fun latOf(y: Double, z: Int): Double {
        val n = PI * (1 - 2 * y / (1 shl z))
        return Math.toDegrees(atan(sinh(n)))
    }
    fun xOf(lon: Double, z: Int) = (lon + 180.0) / 360.0 * (1 shl z)
    fun yOf(lat: Double, z: Int): Double {
        val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
        return (1 - ln(tan(r) + 1 / kotlin.math.cos(r)) / PI) / 2 * (1 shl z)
    }

    /** BBOX in EPSG:3857 für die Kachel (x, y, z). */
    fun bbox3857(x: Int, y: Int, z: Int): String {
        val span = 2 * O / (1 shl z)
        val minx = -O + x * span
        val maxx = minx + span
        val maxy = O - y * span
        val miny = maxy - span
        return "%.2f,%.2f,%.2f,%.2f".format(Locale.US, minx, miny, maxx, maxy)
    }
}

/** Marker für den ausgewählten Ort. */
class PlaceMarker(private val p: Ui.Palette, private val density: Float) : Overlay() {
    var point: GeoPoint? = null
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val pt = Point()
    override fun draw(c: Canvas, map: MapView, shadow: Boolean) {
        if (shadow) return
        val g = point ?: return
        map.projection.toPixels(g, pt)
        paint.color = 0x55000000
        c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 11 * density, paint)
        paint.color = 0xFFFFFFFF.toInt()
        c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 8 * density, paint)
        paint.color = p.accent
        c.drawCircle(pt.x.toFloat(), pt.y.toFloat(), 5.5f * density, paint)
    }
}

/**
 * Regenradar des Deutschen Wetterdienstes (WMS, 1-km-Raster, 5-Minuten-Takt)
 * inklusive der DWD-Kurzfristvorhersage (Nowcast) für die nächsten 2 Stunden.
 */
class RadarOverlay(private val map: MapView, private val onProgress: () -> Unit) : Overlay() {

    data class Key(val time: Long, val run: Long?, val z: Int, val x: Int, val y: Int)

    var frames: List<Long> = emptyList()
    var run: Long? = null          // jüngster Nowcast-Lauf (= letztes gemessenes Bild)
    var frame = 0
        set(v) { field = v; schedule(); map.postInvalidate() }

    private val cache = object : LruCache<Key, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: Key, value: Bitmap) = value.byteCount
    }
    private val empty = Collections.synchronizedSet(HashSet<Key>())   // Kacheln ohne Inhalt
    private val loading = Collections.synchronizedSet(HashSet<Key>())
    private val queue = LinkedBlockingDeque<Key>()
    private val main = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 225 }
    private val a = Point()
    private val b = Point()
    @Volatile private var alive = true

    init {
        repeat(4) {
            Thread {
                while (alive) {
                    val k = try { queue.take() } catch (e: InterruptedException) { break }
                    if (k.time < 0) break
                    load(k)
                }
            }.apply { isDaemon = true; start() }
        }
    }

    private fun cacheBytes(): Int = min(Runtime.getRuntime().maxMemory() / 4, 96L * 1024 * 1024).toInt()

    fun destroy() {
        alive = false
        repeat(4) { queue.offer(Key(-1, null, 0, 0, 0)) }
        cache.evictAll()
    }

    private fun zoomFor(): Int = floor(map.zoomLevelDouble).toInt().coerceIn(4, 8)

    private fun visible(time: Long): List<Key> {
        val bb = map.boundingBox
        val z = zoomFor()
        val n = 1 shl z
        val x0 = floor(Tiles.xOf(bb.lonWest, z)).toInt().coerceIn(0, n - 1)
        val x1 = floor(Tiles.xOf(bb.lonEast, z)).toInt().coerceIn(0, n - 1)
        val y0 = floor(Tiles.yOf(bb.latNorth, z)).toInt().coerceIn(0, n - 1)
        val y1 = floor(Tiles.yOf(bb.latSouth, z)).toInt().coerceIn(0, n - 1)
        val r = if (time > (run ?: Long.MAX_VALUE)) run else null
        val out = ArrayList<Key>()
        // Radar deckt nur Deutschland und Umgebung ab (ca. 45–57° N, 1–19° O)
        for (x in min(x0, x1)..max(x0, x1)) for (y in y0..y1) {
            val lonW = Tiles.lonOf(x.toDouble(), z); val lonE = Tiles.lonOf(x + 1.0, z)
            val latN = Tiles.latOf(y.toDouble(), z); val latS = Tiles.latOf(y + 1.0, z)
            if (lonE < 1.0 || lonW > 19.5 || latN < 45.0 || latS > 57.5) continue
            out.add(Key(time, r, z, x, y))
        }
        return out
    }

    /** Lädt zuerst die Kacheln des aktuellen Bildes, danach alle übrigen Bilder vor. */
    fun schedule() {
        if (frames.isEmpty()) return
        queue.clear()
        val order = ArrayList<Long>()
        order.add(frames[frame.coerceIn(0, frames.size - 1)])
        for (i in 1 until frames.size) {
            listOf(frame + i, frame - i).forEach { j -> if (j in frames.indices && frames[j] !in order) order.add(frames[j]) }
        }
        for (t in order) for (k in visible(t)) {
            if (cache.get(k) == null && k !in empty && k !in loading) queue.offer(k)
        }
    }

    /** Anteil fertig geladener Bilder (für die Anzeige). */
    fun readyFrames(): Int = frames.count { t -> visible(t).all { cache.get(it) != null || it in empty } }

    private fun load(k: Key) {
        if (!loading.add(k)) return
        try {
            val iso = iso(k.time)
            var url = "https://maps.dwd.de/geoserver/dwd/wms?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap" +
                "&LAYERS=Radar_wn-product_1x1km_ger&STYLES=&FORMAT=image/png&TRANSPARENT=true" +
                "&CRS=EPSG:3857&WIDTH=256&HEIGHT=256&BBOX=${Tiles.bbox3857(k.x, k.y, k.z)}&TIME=$iso"
            if (k.run != null) url += "&DIM_REFERENCE_TIME=${iso(k.run)}"
            val bytes = Net.getBytes(url, 20000)
            val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bmp == null) {
                empty.add(k)
            } else {
                val clean = stripBackground(bmp)
                if (clean == null) empty.add(k) else cache.put(k, clean)
            }
        } finally {
            loading.remove(k)
        }
        main.post { map.invalidate(); onProgress() }
    }

    /** Entfernt die graue Hinterlegung und Umrisslinien des DWD; null, wenn kein Niederschlag. */
    private fun stripBackground(src: Bitmap): Bitmap? {
        val w = src.width
        val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        var any = false
        for (i in px.indices) {
            val c = px[i]
            val al = (c ushr 24) and 0xFF
            if (al == 0) continue
            val r = (c shr 16) and 0xFF
            val g = (c shr 8) and 0xFF
            val bl = c and 0xFF
            if (al < 200 || (abs(r - g) < 14 && abs(g - bl) < 14 && abs(r - bl) < 14)) px[i] = 0 else any = true
        }
        if (!any) return null
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        return out
    }

    override fun draw(c: Canvas, mv: MapView, shadow: Boolean) {
        if (shadow || frames.isEmpty()) return
        val t = frames[frame.coerceIn(0, frames.size - 1)]
        val pj = mv.projection
        for (k in visible(t)) {
            val bmp = cache.get(k) ?: continue
            pj.toPixels(GeoPoint(Tiles.latOf(k.y.toDouble(), k.z), Tiles.lonOf(k.x.toDouble(), k.z)), a)
            pj.toPixels(GeoPoint(Tiles.latOf(k.y + 1.0, k.z), Tiles.lonOf(k.x + 1.0, k.z)), b)
            c.drawBitmap(bmp, null, RectF(a.x - 0.5f, a.y - 0.5f, b.x + 0.5f, b.y + 0.5f), paint)
        }
    }

    companion object {
        private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun iso(sec: Long): String = synchronized(ISO) { ISO.format(Date(sec * 1000)) }

        /** Jüngster Nowcast-Lauf laut DWD (Unix-Sekunden) oder null. */
        fun latestRun(): Long? = try {
            val xml = Net.get("https://maps.dwd.de/geoserver/dwd/Radar_wn-product_1x1km_ger/ows?service=WMS&version=1.3.0&request=GetCapabilities", 12000)
            val m = Regex("<Dimension[^>]*name=\"REFERENCE_TIME\"[^>]*>([^<]*)<").find(xml)
            var latest: Long? = null
            m?.groupValues?.get(1)?.split(",")?.forEach { item ->
                val v = (if ("/" in item) item.split("/")[1] else item).trim()
                val t = runCatching {
                    SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
                        .parse(v.take(19))?.time?.div(1000)
                }.getOrNull()
                if (t != null && (latest == null || t > latest!!)) latest = t
            }
            latest
        } catch (e: Exception) { null }
    }
}

/** Radar-Seite: Karte, Zeitleiste mit Abspielen, Legende. */
@SuppressLint("ViewConstructor")
class RadarPage(ctx: Context, private val p: Ui.Palette, center: GeoPoint) : FrameLayout(ctx) {

    private fun dp(v: Number) = Ui.dp(context, v)
    private fun dpf(v: Number) = Ui.dpf(context, v)
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

    val map: MapView = MapSupport.createMap(ctx, p, center, 7.2)
    private val marker = PlaceMarker(p, resources.displayMetrics.density)
    private val overlay = RadarOverlay(map) { updateLabel() }
    private val timeline = TimelineView(ctx, p)
    private val timeLabel = Ui.tv(ctx, "", 17f, p.text, true)
    private val subLabel = Ui.tv(ctx, "", 12.5f, p.muted)
    private val playIcon = GlyphView(ctx, GlyphView.Kind.PLAY, p.onAccent)
    private val main = Handler(Looper.getMainLooper())
    private var playing = false
    private var nowIdx = 0
    private var loadedAt = 0L

    private val ticker = object : Runnable {
        override fun run() {
            if (!playing || overlay.frames.isEmpty()) return
            val next = (overlay.frame + 1) % overlay.frames.size
            setFrame(next)
            main.postDelayed(this, if (next == overlay.frames.size - 1) 1400 else 550)
        }
    }

    init {
        setBackgroundColor(p.bg)
        map.overlays.add(overlay)
        map.overlays.add(MapSupport.labelsOverlay(ctx, p))
        map.overlays.add(marker)
        marker.point = center
        addView(map, LayoutParams(MATCH, MATCH))
        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean { overlay.schedule(); return false }
            override fun onZoom(event: ZoomEvent?): Boolean { overlay.schedule(); return false }
        })

        // ------------------------------------------------- Steuerung unten
        val play = FrameLayout(ctx).apply {
            background = Ui.pressable(p, p.accent, dpf(24))
            addView(playIcon, LayoutParams(dp(20), dp(20), Gravity.CENTER))
            setOnClickListener { togglePlay() }
        }
        val panel = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rect(Ui.withAlpha(p.surface, 240), dpf(24), p.border, 1)
            setPadding(dp(14), dp(12), dp(14), dp(10))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(play, LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), 0, 0, 0)
                    addView(timeLabel)
                    addView(subLabel, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(LegendView(ctx, p), LinearLayout.LayoutParams(dp(96), dp(30)))
            })
            addView(timeline, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        }
        addView(panel, LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { setMargins(dp(12), 0, dp(12), dp(12)) })

        // Titel oben links
        addView(Ui.tv(ctx, "Regenradar", 15f, p.text, true).apply {
            background = Ui.rect(Ui.withAlpha(p.surface, 235), dpf(18), p.border, 1)
            setPadding(dp(14), dp(9), dp(14), dp(9))
        }, LayoutParams(WRAP, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), 0, 0) })

        // Zurück zum Ort
        addView(FrameLayout(ctx).apply {
            background = Ui.pressable(p, Ui.withAlpha(p.surface, 240), dpf(22), p.border, 1)
            addView(GlyphView(ctx, GlyphView.Kind.LOCATE, p.text), LayoutParams(dp(22), dp(22), Gravity.CENTER))
            setOnClickListener { marker.point?.let { map.controller.animateTo(it, 8.0, 500L) } }
        }, LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(12), dp(12), 0) })

        // Quellenangabe
        addView(Ui.tv(ctx, "Radar © DWD  ·  Karte © OpenStreetMap, CARTO", 9.5f, p.muted).apply {
            setShadowLayer(4f, 0f, 0f, p.bg)
        }, LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(18), dp(4)) })

        timeline.onChange = { setFrame(it); stop() }
        loadFrames()
    }

    fun setCenter(g: GeoPoint) {
        marker.point = g
        map.controller.setCenter(g)
        overlay.schedule()
    }

    /** Bildzeiten neu bestimmen (beim Öffnen und nach > 5 Minuten). */
    fun loadFrames() {
        if (System.currentTimeMillis() - loadedAt < 5 * 60_000 && overlay.frames.isNotEmpty()) return
        loadedAt = System.currentTimeMillis()
        subLabel.text = "Lade Radar …"
        Thread {
            val run = RadarOverlay.latestRun()
            val now = System.currentTimeMillis() / 1000
            val base = run ?: (now - now % 300 - 600)
            val step = 15 * 60L
            val list = ArrayList<Long>()
            for (k in 6 downTo 1) list.add(base - k * step)
            list.add(base)
            for (k in 1..8) list.add(base + k * step)
            main.post {
                overlay.run = base
                overlay.frames = list
                nowIdx = 6
                timeline.steps = list.size
                timeline.nowIndex = nowIdx
                setFrame(nowIdx)
            }
        }.start()
    }

    private fun setFrame(i: Int) {
        overlay.frame = i
        timeline.setIndex(i, false)
        updateLabel()
    }

    private fun updateLabel() {
        val fr = overlay.frames
        if (fr.isEmpty()) return
        val t = fr[overlay.frame]
        val cal = Calendar.getInstance().apply { timeInMillis = t * 1000 }
        val clock = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
        val diff = ((t - fr[nowIdx]) / 60).toInt()
        timeLabel.text = when {
            diff == 0 -> "$clock  ·  Jetzt"
            diff < 0 -> "$clock  ·  vor ${-diff} min"
            else -> "$clock  ·  in $diff min"
        }
        val ready = overlay.readyFrames()
        val kind = if (diff > 0) "Vorhersage (DWD-Nowcast)" else "Messung"
        subLabel.text = if (ready < fr.size) "$kind  ·  lade $ready/${fr.size}" else kind
    }

    private fun togglePlay() = if (playing) stop() else startPlay()

    private fun startPlay() {
        playing = true
        playIcon.set(GlyphView.Kind.PAUSE)
        main.removeCallbacks(ticker)
        main.post(ticker)
    }

    fun stop() {
        playing = false
        playIcon.set(GlyphView.Kind.PLAY)
        main.removeCallbacks(ticker)
    }

    fun onResume() { map.onResume(); loadFrames() }
    fun onPause() { stop(); map.onPause() }
    fun destroy() { overlay.destroy(); map.onDetach() }
}

/** Kleine Farblegende für Niederschlagsstärke. */
@SuppressLint("ViewConstructor")
class LegendView(ctx: Context, private val p: Ui.Palette) : View(ctx) {
    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 9.5f * resources.displayMetrics.density }
    override fun onDraw(c: Canvas) {
        val w = width.toFloat()
        paint.shader = LinearGradient(0f, 0f, w, 0f,
            intArrayOf(0xFF9BE1FF.toInt(), 0xFF3C8CFF.toInt(), 0xFF35C759.toInt(), 0xFFFFE14D.toInt(), 0xFFFF6B3D.toInt(), 0xFFD13BD8.toInt()),
            null, Shader.TileMode.CLAMP)
        c.drawRoundRect(RectF(0f, 4 * d, w, 10 * d), 3 * d, 3 * d, paint)
        paint.shader = null
        text.color = p.muted
        text.textAlign = Paint.Align.LEFT
        c.drawText("schwach", 0f, 24 * d, text)
        text.textAlign = Paint.Align.RIGHT
        c.drawText("stark", w, 24 * d, text)
    }
}
