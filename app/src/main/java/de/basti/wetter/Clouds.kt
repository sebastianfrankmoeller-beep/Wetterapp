package de.basti.wetter

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Point
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import android.util.LruCache
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.Overlay
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.LinkedBlockingDeque
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * Wolkenbild vom Wettersatelliten Meteosat (MTG, EUMETSAT – EUMETView, kostenlos, Quellenangabe Pflicht).
 * Ebene "Geo Colour RGB": tagsüber natürliche Farben, nachts Infrarot-Wolken. Neues Bild alle 10 Minuten,
 * etwa 15–20 Minuten nach der Aufnahme. Kacheln als JPEG (klein), 4 Ladefäden.
 */
class CloudOverlay(private val map: MapView, private val onProgress: () -> Unit) : Overlay() {

    data class Key(val time: Long, val z: Int, val x: Int, val y: Int)

    /** Bildzeiten (Unix-Sekunden), älteste zuerst. */
    var frames: List<Long> = emptyList()
    var frame = 0
        set(v) { field = v; schedule(); map.postInvalidate() }

    /** Bei "Regen + Wolken": Zeit des Regenbildes – es wird das jüngste Satellitenbild davor gezeigt. */
    var followTime: Long? = null
        set(v) { field = v; schedule(); map.postInvalidate() }

    private val cache = object : LruCache<Key, Bitmap>(cacheBytes()) {
        override fun sizeOf(key: Key, value: Bitmap) = value.byteCount
    }
    private val failed = Collections.synchronizedSet(HashSet<Key>())
    private val loading = Collections.synchronizedSet(HashSet<Key>())
    private val queue = LinkedBlockingDeque<Key>()
    private val main = Handler(Looper.getMainLooper())
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
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

    private fun cacheBytes(): Int = min(Runtime.getRuntime().maxMemory() / 6, 64L * 1024 * 1024).toInt()

    fun destroy() {
        alive = false
        repeat(4) { queue.offer(Key(-1, 0, 0, 0)) }
        cache.evictAll()
    }

    /** Gezeigte Bildzeit oder null. */
    fun shownTime(): Long? {
        val fr = frames
        if (fr.isEmpty()) return null
        val f = followTime ?: return fr[frame.coerceIn(0, fr.size - 1)]
        return fr.lastOrNull { it <= f } ?: fr.first()
    }

    private fun zoomFor(): Int = floor(map.zoomLevelDouble).toInt().coerceIn(3, 8)

    private fun visible(time: Long): List<Key> {
        val bb = map.boundingBox
        val z = zoomFor()
        val n = 1 shl z
        val x0 = floor(Tiles.xOf(bb.lonWest, z)).toInt().coerceIn(0, n - 1)
        val x1 = floor(Tiles.xOf(bb.lonEast, z)).toInt().coerceIn(0, n - 1)
        val y0 = floor(Tiles.yOf(bb.latNorth, z)).toInt().coerceIn(0, n - 1)
        val y1 = floor(Tiles.yOf(bb.latSouth, z)).toInt().coerceIn(0, n - 1)
        val out = ArrayList<Key>()
        // Meteosat (0°) sieht Europa und Afrika; jenseits von ±75° Länge/Breite nichts Brauchbares
        for (x in min(x0, x1)..max(x0, x1)) for (y in y0..y1) {
            val lonW = Tiles.lonOf(x.toDouble(), z); val lonE = Tiles.lonOf(x + 1.0, z)
            val latN = Tiles.latOf(y.toDouble(), z); val latS = Tiles.latOf(y + 1.0, z)
            if (lonE < -75 || lonW > 75 || latS > 78 || latN < -78) continue
            out.add(Key(time, z, x, y))
        }
        return out
    }

    /** Lädt zuerst das gezeigte Bild, dann (nur im Wolken-Modus) die übrigen für die Zeitleiste. */
    fun schedule() {
        if (!isEnabled || frames.isEmpty()) return
        queue.clear()
        val shown = shownTime() ?: return
        val order = ArrayList<Long>()
        order.add(shown)
        if (followTime == null) {
            val i0 = frames.indexOf(shown)
            for (i in 1 until frames.size) for (j in listOf(i0 + i, i0 - i)) if (j in frames.indices && frames[j] !in order) order.add(frames[j])
        }
        for (t in order) for (k in visible(t)) if (cache.get(k) == null && k !in failed && k !in loading) queue.offer(k)
    }

    fun readyFrames(): Int = frames.count { t -> visible(t).all { cache.get(it) != null || it in failed } }

    private fun load(k: Key) {
        if (!loading.add(k)) return
        try {
            val url = "https://view.eumetsat.int/geoserver/wms?SERVICE=WMS&VERSION=1.3.0&REQUEST=GetMap" +
                "&LAYERS=$LAYER&STYLES=&FORMAT=image/jpeg&CRS=EPSG:3857&WIDTH=256&HEIGHT=256" +
                "&BBOX=${Tiles.bbox3857(k.x, k.y, k.z)}&TIME=${iso(k.time)}"
            val bytes = Net.getBytes(url, 20000)
            val bmp = bytes?.let { runCatching { BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
            if (bmp == null) failed.add(k) else cache.put(k, bmp)
        } finally {
            loading.remove(k)
        }
        main.post { map.invalidate(); onProgress() }
    }

    override fun draw(c: Canvas, mv: MapView, shadow: Boolean) {
        if (shadow) return
        val t = shownTime() ?: return
        val pj = mv.projection
        paint.alpha = 235
        for (k in visible(t)) {
            val bmp = cache.get(k) ?: continue
            pj.toPixels(GeoPoint(Tiles.latOf(k.y.toDouble(), k.z), Tiles.lonOf(k.x.toDouble(), k.z)), a)
            pj.toPixels(GeoPoint(Tiles.latOf(k.y + 1.0, k.z), Tiles.lonOf(k.x + 1.0, k.z)), b)
            c.drawBitmap(bmp, null, RectF(a.x - 0.5f, a.y - 0.5f, b.x + 0.5f, b.y + 0.5f), paint)
        }
    }

    companion object {
        const val LAYER = "mtg_fd:rgb_geocolour"
        private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        fun iso(t: Long): String = synchronized(ISO) { ISO.format(Date(t * 1000)) }

        /**
         * Jüngstes verfügbares Satellitenbild (Unix-Sekunden) aus den Angaben der Ebene; ohne Netz geschätzt
         * (letzte volle 10 Minuten minus 20 Minuten).
         */
        fun latest(): Long {
            val now = System.currentTimeMillis() / 1000
            val guess = now - now % 600 - 1200
            val xml = runCatching {
                Net.get("https://view.eumetsat.int/geoserver/mtg_fd/rgb_geocolour/ows?service=WMS&request=GetCapabilities&version=1.3.0", 12000)
            }.getOrNull() ?: return guess
            val def = Regex("<Dimension[^>]*name=\"time\"[^>]*default=\"([^\"]+)\"").find(xml)?.groupValues?.get(1) ?: return guess
            return RadarNowcast.parseIso(def.replace(".000", "")) ?: guess
        }

        /** Bildzeiten der letzten 3 Stunden im 20-Minuten-Abstand bis zum jüngsten Bild. */
        fun framesUntil(latest: Long): List<Long> = (9 downTo 0).map { latest - it * 1200L }
    }
}
