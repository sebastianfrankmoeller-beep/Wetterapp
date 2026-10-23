package de.basti.wetter

import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Nebel und Sichtweite über Bright Sky (/weather, kostenlos, ohne Schlüssel):
 * gemessene Sicht der nächsten DWD-Station (stündlich) und die DWD-Stationsvorhersage MOSMIX
 * (statistisch nachbearbeitet, erkennt Nebel besser als das reine Modell). Nur in Deutschland, Station bis 25 km.
 */
object Fog {
    private const val MAX_KM = 25.0

    class Pt(
        val t: Long,              // Unix-Sekunden (volle Stunde)
        val vis: Double,          // Sichtweite in m
        val observed: Boolean,    // true = Messung, false = MOSMIX-Vorhersage
        val rh: Double,           // relative Feuchte %
        val dew: Double,          // Taupunkt °C
        val temp: Double,         // °C
        val wind: Double          // km/h
    )

    private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun fetchRaw(p: Place): String? {
        if (!RadarNowcast.covers(p.lat, p.lon)) return null
        val now = System.currentTimeMillis() / 1000
        val from = synchronized(ISO) { ISO.format(Date((now - 5 * 3600) * 1000)) }
        val to = synchronized(ISO) { ISO.format(Date((now + 30 * 3600) * 1000)) }
        val url = "https://api.brightsky.dev/weather?lat=%.4f&lon=%.4f&max_dist=%d".format(Locale.US, p.lat, p.lon, (MAX_KM * 1000).toInt()) +
            "&date=${Net.enc(from)}&last_date=${Net.enc(to)}"
        return runCatching { Net.get(url, 8000) }.getOrNull()
    }

    private fun JSONObject.num(k: String): Double =
        if (!has(k) || isNull(k)) Double.NaN else optDouble(k, Double.NaN)

    fun parse(root: JSONObject): List<Pt> = runCatching {
        val src = HashMap<Int, Pair<Boolean, Double>>()     // id → (Messung?, Entfernung km)
        root.optJSONArray("sources")?.let { a ->
            for (i in 0 until a.length()) {
                val s = a.optJSONObject(i) ?: continue
                src[s.optInt("id")] = (s.optString("observation_type") != "forecast") to s.num("distance") / 1000
            }
        }
        val arr = root.optJSONArray("weather") ?: return emptyList()
        val out = ArrayList<Pt>()
        for (i in 0 until arr.length()) {
            val w = arr.optJSONObject(i) ?: continue
            val s = src[w.optInt("source_id", -1)] ?: continue
            if (s.second.isNaN() || s.second > MAX_KM) continue
            val t = RadarNowcast.parseIso(w.optString("timestamp")) ?: continue
            val vis = w.num("visibility")
            if (vis.isNaN()) continue
            out.add(Pt(t, vis, s.first, w.num("relative_humidity"), w.num("dew_point"), w.num("temperature"), w.num("wind_speed")))
        }
        out.sortedBy { it.t }
    }.getOrDefault(emptyList())

    /** Zustand für die Jetzt-Karte und den Lauf-Check. */
    class State(
        val visM: Double,          // zuletzt gemessene Sicht
        val visT: Long,            // Zeit der Messung
        val label: String,         // "Dichter Nebel", "Nebel", "Dunst" oder ""
        val likely: Boolean,       // örtlich (dichter) Nebel möglich, obwohl die Station mehr Sicht misst
        val forecast: String?      // "Nebel möglich ca. 06–10 Uhr (Sicht bis 600 m)" bzw. "Dunst bis ca. 11 Uhr"
    )

    fun label(vis: Double, rh: Double): String = when {
        vis < 200 -> "Dichter Nebel"
        vis < 1000 -> "Nebel"
        vis < 5000 && (rh.isNaN() || rh >= 80) -> "Dunst"
        else -> ""
    }

    /** "700 m", "3,6 km", "12 km" */
    fun dist(vis: Double): String = when {
        vis < 1000 -> "${((vis / 50).roundToInt() * 50).coerceAtLeast(50)} m"
        vis < 10000 -> String.format(Locale.GERMANY, "%.1f km", vis / 1000).replace(",0 km", " km")
        else -> "${(vis / 1000).roundToInt()} km"
    }

    fun state(f: Forecast, nowSec: Long = System.currentTimeMillis() / 1000): State? {
        val obs = f.vis.filter { it.observed && it.t <= nowSec + 600 && nowSec - it.t <= 150 * 60 }
        val last = obs.maxByOrNull { it.t } ?: return null
        val ahead = f.vis.filter { !it.observed && it.t > nowSec - 1800 && it.t <= nowSec + 12 * 3600 }

        // Örtlicher Nebel: sehr feucht, schwacher Wind, Sicht nimmt ab oder MOSMIX erwartet Dunst/Nebel,
        // nachts oder bis zum Vormittag (nach der Abkühlung)
        val humid = (!last.rh.isNaN() && last.rh >= 95) || (!last.temp.isNaN() && !last.dew.isNaN() && last.temp - last.dew <= 1.0)
        val calm = last.wind.isNaN() || last.wind < 10
        val before = f.vis.filter { it.observed && it.t in (last.t - 3 * 3600)..(last.t - 3600) }.maxOfOrNull { it.vis }
        val falling = before != null && before >= last.vis * 1.5
        val mosmixLow = ahead.take(3).any { it.vis < 5000 }
        val hour = f.hour(nowSec)
        val time = hour >= 18 || hour <= 11
        val likely = last.vis >= 1000 && humid && calm && (falling || mosmixLow) && time

        // Vorhersage: Nebel (< 1 km) in den nächsten 12 Std., sonst anhaltender Dunst
        val fogH = ahead.filter { it.vis < 1000 }
        val fc = if (fogH.isNotEmpty()) {
            val a = fogH.first().t; val b = fogH.last().t + 3600
            "Nebel möglich ca. ${f.hour(a)}–${f.hour(b)} Uhr (Sicht bis ${dist(fogH.minOf { it.vis })})"
        } else if (last.vis < 5000) {
            val endPt = ahead.firstOrNull { it.t > nowSec && it.vis >= 5000 }
            val minV = (ahead.filter { it.t > nowSec && (endPt == null || it.t < endPt.t) }.map { it.vis } + last.vis).minOrNull() ?: last.vis
            if (endPt != null) "Dunst bis ca. ${f.hour(endPt.t)} Uhr, Sicht um ${dist(minV)}" else null
        } else null

        return State(last.vis, last.t, label(last.vis, last.rh), likely, fc)
    }

    /** MOSMIX-Sicht für eine Stunde (Unix-Sekunden, volle Stunde) oder NaN. */
    fun forecastAt(f: Forecast, t: Long): Double =
        f.vis.firstOrNull { !it.observed && it.t == t }?.vis ?: Double.NaN
}
