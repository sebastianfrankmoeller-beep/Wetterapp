package de.basti.wetter

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** Geometrie-Helfer (Kugelerde). */
object Geo {
    private const val R = 6371000.0

    fun dist(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * R * asin(sqrt(a.coerceIn(0.0, 1.0)))
    }

    fun bearing(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1); val p2 = Math.toRadians(lat2)
        val dl = Math.toRadians(lon2 - lon1)
        val y = sin(dl) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
        return (Math.toDegrees(atan2(y, x)) + 360) % 360
    }

    /** Punkt in Entfernung [m] und Richtung [Grad] von (lat, lon). */
    fun dest(lat: Double, lon: Double, bearingDeg: Double, meters: Double): Pair<Double, Double> {
        val d = meters / R
        val b = Math.toRadians(bearingDeg)
        val p1 = Math.toRadians(lat); val l1 = Math.toRadians(lon)
        val p2 = asin(sin(p1) * cos(d) + cos(p1) * sin(d) * cos(b))
        val l2 = l1 + atan2(sin(b) * sin(d) * cos(p1), cos(d) - sin(p1) * sin(p2))
        return Math.toDegrees(p2) to ((Math.toDegrees(l2) + 540) % 360 - 180)
    }
}

/** Berechnete Route (OSRM) mit kumulierten Entfernungen und Fahrzeiten. */
class DriveRoute(
    val destName: String,
    val destLat: Double,
    val destLon: Double,
    val lat: DoubleArray,
    val lon: DoubleArray,
    val cumDist: DoubleArray,  // m
    val cumDur: DoubleArray,   // s
    val createdAt: Long = System.currentTimeMillis()
) {
    val length get() = cumDist.last()

    /** Nächster Routenpunkt zu (la, lo) – sucht um [hint] herum, sonst überall. Rückgabe: Index und Abstand in m. */
    fun nearest(la: Double, lo: Double, hint: Int = -1): Pair<Int, Double> {
        var best = -1
        var bestD = Double.MAX_VALUE
        val range = if (hint >= 0) max(0, hint - 30)..min(lat.size - 1, hint + 200) else lat.indices
        for (i in range) {
            val d = Geo.dist(la, lo, lat[i], lon[i])
            if (d < bestD) { bestD = d; best = i }
        }
        if (hint >= 0 && bestD > 3000) return nearest(la, lo, -1)
        return best to bestD
    }

    companion object {
        fun fetch(fromLat: Double, fromLon: Double, toLat: Double, toLon: Double, name: String): DriveRoute {
            val url = "https://router.project-osrm.org/route/v1/driving/" +
                "%.6f,%.6f;%.6f,%.6f".format(Locale.US, fromLon, fromLat, toLon, toLat) +
                "?overview=full&geometries=geojson"
            val o = JSONObject(Net.get(url, 20000))
            val r = o.getJSONArray("routes").getJSONObject(0)
            val coords = r.getJSONObject("geometry").getJSONArray("coordinates")
            val totalDur = r.getDouble("duration")
            // Punkte ausdünnen (mind. 200 m Abstand)
            val la = ArrayList<Double>(); val lo = ArrayList<Double>(); val cd = ArrayList<Double>()
            var acc = 0.0
            for (i in 0 until coords.length()) {
                val c = coords.getJSONArray(i)
                val x = c.getDouble(0); val y = c.getDouble(1)
                if (la.isNotEmpty()) {
                    val d = Geo.dist(la.last(), lo.last(), y, x)
                    if (d < 200 && i != coords.length() - 1) continue
                    acc += d
                }
                la.add(y); lo.add(x); cd.add(acc)
            }
            val total = max(1.0, acc)
            val dur = DoubleArray(cd.size) { cd[it] / total * totalDur }
            return DriveRoute(name, toLat, toLon, la.toDoubleArray(), lo.toDoubleArray(), cd.toDoubleArray(), dur)
        }
    }
}

/** Ein Prüfpunkt voraus: Position, Entfernung ab jetzt (m), voraussichtliche Ankunft (Unix-s). */
class AheadPoint(val lat: Double, val lon: Double, val km: Double, val eta: Long)

/** Gefahrenarten in Reihenfolge der Wichtigkeit. */
enum class Hazard(val label: String, val spoken: String) {
    THUNDER("Gewitter", "Gewitter"),
    ICE("Glätte", "Glättegefahr"),
    SNOW("Schnee", "Schneefall"),
    RAIN("Starkregen", "Starkregen"),
    FOG("Nebel", "dichter Nebel"),
    GUST("Sturmböen", "Sturmböen")
}

/** Zusammenhängender Abschnitt mit einer Gefahr. */
class Segment(val hazard: Hazard, val severity: Int, val startKm: Double, val endKm: Double, val openEnd: Boolean, val startEta: Long) {
    /** Zusatzbezeichnung je Stärke (z. B. Glatteis statt Glätte). */
    fun name(): String = when (hazard) {
        Hazard.ICE -> if (severity >= 3) "Glatteis" else "Glätte"
        Hazard.RAIN -> if (severity >= 3) "Unwetterartiger Starkregen" else "Starkregen"
        Hazard.SNOW -> if (severity >= 2) "Starker Schneefall" else "Schneefall"
        else -> hazard.label
    }

    fun spokenName(): String = when (hazard) {
        Hazard.ICE -> if (severity >= 3) "Glatteis durch gefrierenden Regen" else "Glättegefahr"
        Hazard.RAIN -> if (severity >= 3) "sehr starker Regen" else "Starkregen"
        Hazard.SNOW -> if (severity >= 2) "starker Schneefall" else "Schneefall"
        else -> hazard.spoken
    }
}

/** Wetter an Prüfpunkten abrufen und Gefahren bestimmen. */
object DriveWeather {

    class Sample(val precip: Double, val code: Int, val vis: Double, val temp: Double, val snow: Double, val gust: Double, val lpi: Double)

    private fun JSONArray?.d(i: Int): Double =
        if (this == null || i < 0 || i >= length() || isNull(i)) Double.NaN else optDouble(i, Double.NaN)

    /** Werte zur jeweiligen Ankunftszeit; Liste gleich lang wie [pts] (null = keine Daten). */
    fun fetch(pts: List<AheadPoint>): List<Sample?> {
        if (pts.isEmpty()) return emptyList()
        val now = System.currentTimeMillis() / 1000
        val hours = ((pts.maxOf { it.eta } - now) / 3600 + 2).toInt().coerceIn(2, 48)
        val lats = pts.joinToString(",") { "%.3f".format(Locale.US, it.lat) }
        val lons = pts.joinToString(",") { "%.3f".format(Locale.US, it.lon) }
        val url = "https://api.open-meteo.com/v1/forecast?latitude=$lats&longitude=$lons" +
            "&hourly=precipitation,weather_code,visibility,temperature_2m,snowfall,wind_gusts_10m,lightning_potential" +
            "&minutely_15=precipitation&forecast_minutely_15=12&models=icon_seamless" +
            "&forecast_hours=$hours&past_hours=1&timeformat=unixtime&timezone=GMT"
        val body = Net.get(url, 25000)
        val arr = if (body.trimStart().startsWith("[")) JSONArray(body) else JSONArray().put(JSONObject(body))
        return pts.mapIndexed { k, pt ->
            val o = arr.optJSONObject(k) ?: return@mapIndexed null
            val h = o.optJSONObject("hourly") ?: return@mapIndexed null
            val tt = h.optJSONArray("time") ?: return@mapIndexed null
            var hi = -1
            for (i in 0 until tt.length()) if (tt.getLong(i) <= pt.eta) hi = i
            if (hi < 0) hi = 0
            var precip = h.optJSONArray("precipitation").d(hi)
            // Genauer: 15-Minuten-Wert, falls die Ankunft in den nächsten 3 Stunden liegt
            o.optJSONObject("minutely_15")?.let { m ->
                val mt = m.optJSONArray("time"); val mp = m.optJSONArray("precipitation")
                if (mt != null) for (i in 0 until mt.length()) {
                    val t = mt.getLong(i)
                    if (pt.eta >= t && pt.eta < t + 900) { val v = mp.d(i); if (!v.isNaN()) precip = v * 4 }
                }
            }
            Sample(
                precip = if (precip.isNaN()) 0.0 else precip,
                code = h.optJSONArray("weather_code").d(hi).let { if (it.isNaN()) 0 else it.toInt() },
                vis = h.optJSONArray("visibility").d(hi),
                temp = h.optJSONArray("temperature_2m").d(hi),
                snow = h.optJSONArray("snowfall").d(hi).let { if (it.isNaN()) 0.0 else it },
                gust = h.optJSONArray("wind_gusts_10m").d(hi),
                lpi = h.optJSONArray("lightning_potential").d(hi)
            )
        }
    }

    /** Gefahren eines Punktes mit Stärke 1–3. */
    fun hazards(s: Sample): Map<Hazard, Int> {
        val out = HashMap<Hazard, Int>()
        if (s.code >= 95 || (!s.lpi.isNaN() && s.lpi >= 3)) out[Hazard.THUNDER] = if (s.code >= 96) 3 else 2
        when {
            s.code in 56..57 || s.code in 66..67 -> out[Hazard.ICE] = 3
            !s.temp.isNaN() && s.temp <= 1.0 && s.precip >= 0.1 && s.snow < 0.1 -> out[Hazard.ICE] = 2
        }
        when {
            s.snow >= 1.0 || s.code == 75 || s.code == 86 -> out[Hazard.SNOW] = 2
            s.snow >= 0.1 || s.code in 71..77 || s.code == 85 -> out[Hazard.SNOW] = 1
        }
        if (s.snow < 0.1) when {
            s.precip >= 10 -> out[Hazard.RAIN] = 3
            s.precip >= 4 -> out[Hazard.RAIN] = 2
        }
        when {
            !s.vis.isNaN() && s.vis < 150 -> out[Hazard.FOG] = 3
            !s.vis.isNaN() && s.vis < 300 -> out[Hazard.FOG] = 2
            s.vis.isNaN() && (s.code == 45 || s.code == 48) -> out[Hazard.FOG] = 2
        }
        when {
            !s.gust.isNaN() && s.gust >= 90 -> out[Hazard.GUST] = 3
            !s.gust.isNaN() && s.gust >= 65 -> out[Hazard.GUST] = 2
        }
        return out
    }

    /** Fasst aufeinanderfolgende Punkte mit gleicher Gefahr zu Abschnitten zusammen (vorderster je Art zuerst). */
    fun segments(pts: List<AheadPoint>, samples: List<Sample?>, stepKm: Double): List<Segment> {
        val haz = samples.map { s -> s?.let { hazards(it) } ?: emptyMap() }
        val out = ArrayList<Segment>()
        for (h in Hazard.values()) {
            var i = 0
            while (i < pts.size) {
                val sev: Int = haz[i][h] ?: run { i++; null } ?: continue
                var j = i
                var maxSev: Int = sev
                while (j + 1 < pts.size && haz[j + 1][h] != null) { j++; maxSev = max(maxSev, haz[j][h]!!) }
                val start = max(0.0, pts[i].km - stepKm / 2)
                val end = pts[j].km + stepKm / 2
                out.add(Segment(h, maxSev, start, end, j == pts.size - 1, pts[i].eta))
                i = j + 1
            }
        }
        return out.sortedWith(compareBy<Segment> { it.startKm }.thenBy { it.hazard.ordinal })
    }

    fun kmText(km: Double): String = if (km < 10) "%.0f".format(Locale.GERMANY, km).let { if (it == "0") "1" else it } else "${(km / 5).roundToInt() * 5}"
}
