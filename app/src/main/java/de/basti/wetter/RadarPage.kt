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
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 200 }
    private val a = Point()
    private val b = Point()
    @Volatile private var alive = true

    /** Weltweites Radar (RainViewer) für alles außerhalb des DWD-Radarverbunds. */
    val world = WorldRadar(map) { main.post { map.invalidate(); onProgress() } }
    private val cut = Paint(Paint.ANTI_ALIAS_FLAG).apply { xfermode = android.graphics.PorterDuffXfermode(android.graphics.PorterDuff.Mode.DST_OUT) }
    private val c0 = Point()

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
        world.destroy()
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
        val measured = frames.filter { it <= (run ?: Long.MAX_VALUE) }
        if (measured.isNotEmpty()) world.schedule(measured, frame.coerceAtMost(measured.size - 1))
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
    fun readyFrames(): Int = frames.count { t -> visible(t).all { cache.get(it) != null || it in empty } && (t > (run ?: Long.MAX_VALUE) || world.ready(t)) }

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

        // 1) Weltradar in eigener Ebene, Gebiet des DWD-Radarverbunds ausgestanzt
        if (t <= (run ?: Long.MAX_VALUE) && world.hasFrame(t)) {
            val layer = c.saveLayer(null, null)
            world.draw(c, mv, t)
            for ((lat, lon) in DWD_SITES) {
                pj.toPixels(GeoPoint(lat, lon), c0)
                pj.toPixels(GeoPoint(lat + DWD_RANGE_KM / 111.2, lon), a)
                val r = abs(c0.y - a.y).toFloat()
                c.drawCircle(c0.x.toFloat(), c0.y.toFloat(), r, cut)
            }
            c.restoreToCount(layer)
        }

        // 2) DWD-Radar darüber
        for (k in visible(t)) {
            val bmp = cache.get(k) ?: continue
            pj.toPixels(GeoPoint(Tiles.latOf(k.y.toDouble(), k.z), Tiles.lonOf(k.x.toDouble(), k.z)), a)
            pj.toPixels(GeoPoint(Tiles.latOf(k.y + 1.0, k.z), Tiles.lonOf(k.x + 1.0, k.z)), b)
            c.drawBitmap(bmp, null, RectF(a.x - 0.5f, a.y - 0.5f, b.x + 0.5f, b.y + 0.5f), paint)
        }
    }

    companion object {
        /** Standorte des DWD-Radarverbunds; Reichweite im Komposit ca. 150 km. */
        val DWD_SITES = listOf(
            53.564 to 6.748, 53.339 to 7.025, 54.004 to 10.047, 51.125 to 13.769, 49.541 to 12.403,
            51.406 to 6.967, 47.874 to 8.004, 51.311 to 8.802, 52.460 to 9.695, 48.175 to 12.102,
            48.042 to 10.219, 50.500 to 11.135, 50.110 to 6.548, 49.985 to 8.712, 52.648 to 13.858,
            54.176 to 12.058, 48.586 to 9.783, 52.160 to 11.176
        )
        const val DWD_RANGE_KM = 150.0

        /** Liegt ein Punkt im Erfassungsbereich des DWD-Radarverbunds? */
        fun inDwd(lat: Double, lon: Double): Boolean = DWD_SITES.any { (la, lo) ->
            val dy = (lat - la) * 111.2
            val dx = (lon - lo) * 111.2 * kotlin.math.cos(Math.toRadians(lat))
            dx * dx + dy * dy < DWD_RANGE_KM * DWD_RANGE_KM
        }

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
    private val warnOverlay = WarnMapOverlay(map)
    private lateinit var warnToggle: TextView
    private lateinit var warnLegend: LinearLayout
    private lateinit var warnInfo: LinearLayout
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
        map.overlays.add(warnOverlay)
        map.overlays.add(overlay)
        map.overlays.add(marker)
        MapSupport.addLabels(map, p)          // Ortsnamen über dem Radar
        warnOverlay.isEnabled = false
        warnOverlay.onTap = { g -> showWarningsAt(g) }
        marker.point = center
        addView(map, LayoutParams(MATCH, MATCH))
        map.addMapListener(object : MapListener {
            override fun onScroll(event: ScrollEvent?): Boolean { overlay.schedule(); warnOverlay.schedule(); return false }
            override fun onZoom(event: ZoomEvent?): Boolean { overlay.schedule(); warnOverlay.schedule(); return false }
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

        // Titel und Schalter für die Warnkarte oben links
        warnToggle = Ui.tv(ctx, "Warnungen", 14f, p.text, true).apply {
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { setWarnLayer(!warnOverlay.isEnabled) }
        }
        val topBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.rect(Ui.withAlpha(p.surface, 240), dpf(22), p.border, 1)
                setPadding(dp(14), dp(4), dp(4), dp(4))
                addView(Ui.tv(ctx, "Regenradar", 15f, p.text, true), LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(10) })
                addView(warnToggle)
            }, LinearLayout.LayoutParams(WRAP, WRAP))
            // Legende der Warnstufen (nur bei eingeschalteter Warnkarte)
            warnLegend = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.rect(Ui.withAlpha(p.surface, 240), dpf(16), p.border, 1)
                setPadding(dp(10), dp(6), dp(12), dp(6))
                for (lvl in 1..4) {
                    addView(View(ctx).apply { background = Ui.rect(Warning.color(lvl), dpf(5)) },
                        LinearLayout.LayoutParams(dp(10), dp(10)).apply { leftMargin = if (lvl == 1) 0 else dp(10); rightMargin = dp(5) })
                    addView(Ui.tv(ctx, Warning.COLOR_NAMES[lvl], 12f, p.muted))
                }
                addView(Ui.tv(ctx, "  ·  antippen", 12f, p.muted))
            }
            addView(warnLegend, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })
            // Angetippte Warnungen
            warnInfo = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = Ui.rect(Ui.withAlpha(p.surface, 245), dpf(18), p.border, 1)
                setPadding(dp(14), dp(10), dp(14), dp(12))
                visibility = View.GONE
                setOnClickListener { visibility = View.GONE }
            }
            addView(warnInfo, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
        }
        addView(topBox, LayoutParams(MATCH, WRAP, Gravity.TOP or Gravity.START).apply { setMargins(dp(12), dp(12), dp(68), 0) })
        setWarnLayer(false)

        // Zurück zum Ort
        addView(FrameLayout(ctx).apply {
            background = Ui.pressable(p, Ui.withAlpha(p.surface, 240), dpf(22), p.border, 1)
            addView(GlyphView(ctx, GlyphView.Kind.LOCATE, p.text), LayoutParams(dp(22), dp(22), Gravity.CENTER))
            setOnClickListener { marker.point?.let { map.controller.animateTo(it, 8.0, 500L) } }
        }, LayoutParams(dp(44), dp(44), Gravity.TOP or Gravity.END).apply { setMargins(0, dp(12), dp(12), 0) })

        // Quellenangabe
        addView(Ui.tv(ctx, "Radar © DWD, Weather data by Rain Viewer  ·  Karte © OpenStreetMap", 9.5f, p.muted).apply {
            setShadowLayer(4f, 0f, 0f, p.bg)
            setOnClickListener {
                runCatching { context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse("https://www.rainviewer.com"))) }
            }
        }, LayoutParams(WRAP, WRAP, Gravity.BOTTOM or Gravity.END).apply { setMargins(0, 0, dp(18), dp(4)) })

        timeline.onChange = { setFrame(it); stop() }
        loadFrames()
    }

    private fun setWarnLayer(on: Boolean) {
        warnOverlay.isEnabled = on
        // Aus: nur Umriss, gedämpfte Schrift – An: Akzentfarbe. So ist der Zustand eindeutig.
        warnToggle.background = if (on) Ui.rect(p.accent, dpf(18)) else Ui.pressable(p, Ui.withAlpha(p.surface, 0), dpf(18), p.border, 1)
        warnToggle.setTextColor(if (on) p.onAccent else p.muted)
        warnLegend.visibility = if (on) View.VISIBLE else View.GONE
        if (!on) warnInfo.visibility = View.GONE
        if (on) warnOverlay.reload()
        map.invalidate()
    }

    /** Warnungen an der angetippten Stelle anzeigen. */
    private fun showWarningsAt(g: GeoPoint) {
        warnInfo.removeAllViews()
        warnInfo.addView(Ui.tv(context, "Lade Warnungen …", 13f, p.muted))
        warnInfo.visibility = View.VISIBLE
        Thread {
            val res = runCatching { Warnings.fetch(context, g.latitude, g.longitude) }
            main.post {
                warnInfo.removeAllViews()
                val list = res.getOrNull()
                when {
                    res.isFailure -> warnInfo.addView(Ui.tv(context, "Warnungen nicht abrufbar – keine Verbindung?", 13f, p.muted))
                    list == null -> warnInfo.addView(Ui.tv(context, "Keine DWD-Warnungen außerhalb Deutschlands", 13f, p.muted))
                    list.isEmpty() -> warnInfo.addView(Ui.tv(context, "Keine Warnung an dieser Stelle", 13.5f, p.text, true))
                    else -> for ((i, w) in list.take(4).withIndex()) {
                        warnInfo.addView(LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL
                            addView(View(context).apply { background = Ui.rect(Warning.color(w.level), dpf(5)) },
                                LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(8) })
                            addView(LinearLayout(context).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(Ui.tv(context, w.event.lowercase().replaceFirstChar { it.uppercase() }, 14f, p.text, true))
                                addView(Ui.tv(context, "${Warning.COLOR_NAMES[w.level]}  ·  ${w.timeRange()}", 12f, p.muted))
                            })
                        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { if (i > 0) topMargin = dp(8) })
                    }
                }
                warnInfo.addView(Ui.tv(context, "Antippen zum Schließen", 11f, p.muted).apply { setPadding(0, dp(6), 0, 0) })
            }
        }.start()
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
            overlay.world.refresh()
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
        val kind = if (diff > 0) "Vorhersage · nur DWD-Gebiet" else "Messung"
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
    fun destroy() { overlay.destroy(); warnOverlay.destroy(); map.onDetach() }
}

/**
 * Weltweites Regenradar von Rain Viewer (kostenlos für private Nutzung, Quellenangabe Pflicht).
 * Freie Stufe: nur Messungen der letzten 2 Stunden (10-Minuten-Takt), Zoom bis 7, Farbschema "Universal Blue".
 * Höchstens ~90 Abrufe pro Minute (Grenze von Rain Viewer: 100).
 */
class WorldRadar(private val map: MapView, private val onLoaded: () -> Unit) {
    data class Key(val time: Long, val z: Int, val x: Int, val y: Int)

    @Volatile private var host = ""
    @Volatile private var frames: List<Pair<Long, String>> = emptyList()   // Zeit → Pfad
    private val cache = object : LruCache<Key, Bitmap>(min(Runtime.getRuntime().maxMemory() / 6, 48L * 1024 * 1024).toInt()) {
        override fun sizeOf(key: Key, value: Bitmap) = value.byteCount
    }
    private val empty = Collections.synchronizedSet(HashSet<Key>())
    private val loading = Collections.synchronizedSet(HashSet<Key>())
    private val queue = LinkedBlockingDeque<Key>()
    private val stamps = java.util.ArrayDeque<Long>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 200 }
    private val a = Point()
    private val b = Point()
    @Volatile private var alive = true

    init {
        repeat(2) {
            Thread {
                while (alive) {
                    val k = try { queue.take() } catch (e: InterruptedException) { break }
                    if (k.time < 0) break
                    throttle()
                    load(k)
                }
            }.apply { isDaemon = true; start() }
        }
    }

    fun destroy() {
        alive = false
        repeat(2) { queue.offer(Key(-1, 0, 0, 0)) }
        cache.evictAll()
    }

    /** Liste der verfügbaren Bilder holen (im Hintergrund aufrufen). */
    fun refresh() {
        runCatching {
            val o = org.json.JSONObject(Net.get("https://api.rainviewer.com/public/weather-maps.json", 10000))
            val past = o.getJSONObject("radar").getJSONArray("past")
            val list = ArrayList<Pair<Long, String>>()
            for (i in 0 until past.length()) {
                val f = past.getJSONObject(i)
                list.add(f.getLong("time") to f.getString("path"))
            }
            host = o.optString("host", "https://tilecache.rainviewer.com")
            frames = list.sortedBy { it.first }
        }
    }

    /** Passendes Rain-Viewer-Bild zur Zeit t (höchstens 10 Minuten daneben), sonst null. */
    private fun pathFor(t: Long): String? = frames.minByOrNull { abs(it.first - t) }?.takeIf { abs(it.first - t) <= 600 }?.second
    private fun timeFor(t: Long): Long? = frames.minByOrNull { abs(it.first - t) }?.takeIf { abs(it.first - t) <= 600 }?.first

    fun hasFrame(t: Long) = timeFor(t) != null

    private fun zoom() = (floor(map.zoomLevelDouble).toInt() - 1).coerceIn(1, 7)   // 512er-Kacheln

    private fun visible(rt: Long): List<Key> {
        val bb = map.boundingBox
        val z = zoom()
        val n = 1 shl z
        val x0 = floor(Tiles.xOf(bb.lonWest, z)).toInt()
        val x1 = floor(Tiles.xOf(bb.lonEast, z)).toInt()
        val y0 = floor(Tiles.yOf(bb.latNorth, z)).toInt().coerceIn(0, n - 1)
        val y1 = floor(Tiles.yOf(bb.latSouth, z)).toInt().coerceIn(0, n - 1)
        val out = ArrayList<Key>()
        val xs = if (x1 >= x0) (x0..x1) else (x0..x1 + n)
        for (xx in xs) for (y in y0..y1) {
            val x = Math.floorMod(xx, n)
            // Kachel liegt komplett im DWD-Gebiet? Dann nicht laden.
            val lonW = Tiles.lonOf(x.toDouble(), z); val lonE = Tiles.lonOf(x + 1.0, z)
            val latN = Tiles.latOf(y.toDouble(), z); val latS = Tiles.latOf(y + 1.0, z)
            if (RadarOverlay.inDwd(latN, lonW) && RadarOverlay.inDwd(latN, lonE) && RadarOverlay.inDwd(latS, lonW) &&
                RadarOverlay.inDwd(latS, lonE) && RadarOverlay.inDwd((latN + latS) / 2, (lonW + lonE) / 2)) continue
            out.add(Key(rt, z, x, y))
        }
        return out
    }

    fun schedule(dwdFrames: List<Long>, current: Int) {
        if (frames.isEmpty()) return
        queue.clear()
        val order = ArrayList<Long>()
        timeFor(dwdFrames[current.coerceIn(0, dwdFrames.size - 1)])?.let { order.add(it) }
        for (i in 1 until dwdFrames.size) for (j in listOf(current + i, current - i)) {
            if (j in dwdFrames.indices) timeFor(dwdFrames[j])?.let { if (it !in order) order.add(it) }
        }
        for (t in order) for (k in visible(t)) if (cache.get(k) == null && k !in empty && k !in loading) queue.offer(k)
    }

    fun ready(t: Long): Boolean { val rt = timeFor(t) ?: return true; return visible(rt).all { cache.get(it) != null || it in empty } }

    private fun throttle() {
        while (alive) {
            val wait = synchronized(stamps) {
                val now = System.currentTimeMillis()
                while (stamps.isNotEmpty() && now - stamps.first() > 60_000) stamps.removeFirst()
                if (stamps.size < 90) { stamps.addLast(now); return }
                max(200L, 60_000 - (now - stamps.first()))
            }
            try { Thread.sleep(wait) } catch (e: InterruptedException) { return }
        }
    }

    private fun load(k: Key) {
        if (!loading.add(k)) return
        try {
            val path = frames.firstOrNull { it.first == k.time }?.second ?: return
            val bytes = Net.getBytes("$host$path/512/${k.z}/${k.x}/${k.y}/2/1_1.png", 15000)
            val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bmp == null) empty.add(k) else cache.put(k, bmp)
        } finally {
            loading.remove(k)
        }
        onLoaded()
    }

    fun draw(c: Canvas, mv: MapView, t: Long) {
        val rt = timeFor(t) ?: return
        val pj = mv.projection
        for (k in visible(rt)) {
            val bmp = cache.get(k) ?: continue
            pj.toPixels(GeoPoint(Tiles.latOf(k.y.toDouble(), k.z), Tiles.lonOf(k.x.toDouble(), k.z)), a)
            pj.toPixels(GeoPoint(Tiles.latOf(k.y + 1.0, k.z), Tiles.lonOf(k.x + 1.0, k.z)), b)
            c.drawBitmap(bmp, null, RectF(a.x - 0.5f, a.y - 0.5f, b.x + 0.5f, b.y + 0.5f), paint)
        }
    }
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


/** Amtliche DWD-Warnkarte (Gemeinden, eingefärbt nach Warnstufe) als halbtransparente Ebene. */
class WarnMapOverlay(private val map: MapView) : Overlay() {
    data class Key(val z: Int, val x: Int, val y: Int, val gen: Int)

    private val cache = object : LruCache<Key, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Bitmap) = value.byteCount
    }
    private val loading = Collections.synchronizedSet(HashSet<Key>())
    private val queue = LinkedBlockingDeque<Key>()
    private val main = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { alpha = 150 }
    private val a = Point()
    private val b = Point()
    @Volatile private var alive = true
    private var gen = 0          // neue Generation = Warnungen neu laden
    var onTap: ((GeoPoint) -> Unit)? = null

    override fun onSingleTapConfirmed(e: android.view.MotionEvent, mapView: MapView): Boolean {
        if (!isEnabled) return false
        val g = mapView.projection.fromPixels(e.x.toInt(), e.y.toInt()) as GeoPoint
        onTap?.invoke(g)
        return true
    }

    init {
        repeat(2) {
            Thread {
                while (alive) {
                    val k = try { queue.take() } catch (e: InterruptedException) { break }
                    if (k.z < 0) break
                    load(k)
                }
            }.apply { isDaemon = true; start() }
        }
    }

    fun reload() { gen++; cache.evictAll(); schedule() }

    fun destroy() {
        alive = false
        repeat(2) { queue.offer(Key(-1, 0, 0, 0)) }
        cache.evictAll()
    }

    private fun visible(): List<Key> {
        val bb = map.boundingBox
        val z = floor(map.zoomLevelDouble).toInt().coerceIn(5, 9)
        val n = 1 shl z
        val x0 = floor(Tiles.xOf(bb.lonWest, z)).toInt().coerceIn(0, n - 1)
        val x1 = floor(Tiles.xOf(bb.lonEast, z)).toInt().coerceIn(0, n - 1)
        val y0 = floor(Tiles.yOf(bb.latNorth, z)).toInt().coerceIn(0, n - 1)
        val y1 = floor(Tiles.yOf(bb.latSouth, z)).toInt().coerceIn(0, n - 1)
        val out = ArrayList<Key>()
        for (x in min(x0, x1)..max(x0, x1)) for (y in y0..y1) {
            val lonW = Tiles.lonOf(x.toDouble(), z); val lonE = Tiles.lonOf(x + 1.0, z)
            val latN = Tiles.latOf(y.toDouble(), z); val latS = Tiles.latOf(y + 1.0, z)
            if (lonE < 5.0 || lonW > 15.5 || latN < 47.0 || latS > 55.5) continue
            out.add(Key(z, x, y, gen))
        }
        return out
    }

    fun schedule() {
        if (!isEnabled) return
        queue.clear()
        for (k in visible()) if (cache.get(k) == null && k !in loading) queue.offer(k)
    }

    private fun load(k: Key) {
        if (!loading.add(k)) return
        try {
            val url = "https://maps.dwd.de/geoserver/dwd/wms?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap" +
                "&LAYERS=Warnungen_Gemeinden&STYLES=&FORMAT=image/png&TRANSPARENT=true" +
                "&CRS=EPSG:3857&WIDTH=256&HEIGHT=256&BBOX=${Tiles.bbox3857(k.x, k.y, k.z)}"
            val bytes = Net.getBytes(url, 20000)
            val bmp = bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
            if (bmp != null) cache.put(k, bmp)
        } finally {
            loading.remove(k)
        }
        main.post { map.invalidate() }
    }

    override fun draw(c: Canvas, mv: MapView, shadow: Boolean) {
        if (shadow) return
        val pj = mv.projection
        for (k in visible()) {
            val bmp = cache.get(k) ?: continue
            pj.toPixels(GeoPoint(Tiles.latOf(k.y.toDouble(), k.z), Tiles.lonOf(k.x.toDouble(), k.z)), a)
            pj.toPixels(GeoPoint(Tiles.latOf(k.y + 1.0, k.z), Tiles.lonOf(k.x + 1.0, k.z)), b)
            c.drawBitmap(bmp, null, RectF(a.x - 0.5f, a.y - 0.5f, b.x + 0.5f, b.y + 0.5f), paint)
        }
    }
}
