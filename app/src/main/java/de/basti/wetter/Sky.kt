package de.basti.wetter

import org.json.JSONObject
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Bewölkung in Achteln und Zustand nach echten Stufen:
 * 0/8 klar · 1–2 sonnig · 3–5 wolkig · 6–7 stark bewölkt · 8/8 bedeckt.
 * Hohe Wolken (meist dünne Schleierwolken, durch die die Sonne scheint) zählen nur halb.
 */
object Sky {

    private fun valid(v: Double) = !v.isNaN()

    /** Anteil des Himmels, den tiefe und mittlere Wolken zusammen bedecken (zufällige Überlappung), in %. */
    private fun lowMid(low: Double, mid: Double): Double {
        val l = (low / 100).coerceIn(0.0, 1.0)
        val m = (mid / 100).coerceIn(0.0, 1.0)
        return 100 * (1 - (1 - l) * (1 - m))
    }

    /** Wirksame Bewölkung in %: Gesamtbewölkung, wobei der nur von hohen Wolken bedeckte Teil halb zählt. */
    fun effective(total: Double, low: Double, mid: Double, high: Double): Double {
        if (!valid(total)) return Double.NaN
        if (!valid(low) || !valid(mid) || !valid(high)) return total
        val lm = lowMid(low, mid)
        val highOnly = (total - lm).coerceIn(0.0, high.coerceAtLeast(0.0))
        return (total - 0.5 * highOnly).coerceIn(0.0, 100.0)
    }

    /** true, wenn die Stufe nur durch hohe Wolken über die der tiefen/mittleren Wolken angehoben wird. */
    fun thinHigh(total: Double, low: Double, mid: Double, high: Double): Boolean {
        if (!valid(total) || !valid(low) || !valid(mid) || !valid(high)) return false
        val eff = effective(total, low, mid, high)
        return stage(octas(eff)) > stage(octas(lowMid(low, mid).coerceAtMost(total)))
    }

    /** Prozent → Achtel. 0/8 nur bei praktisch wolkenlos, 8/8 nur bei vollständig bedeckt. */
    fun octas(pct: Double): Int = when {
        pct.isNaN() -> -1
        pct < 3 -> 0
        pct > 97 -> 8
        else -> (pct / 12.5).roundToInt().coerceIn(1, 7)
    }

    /** 0 klar, 1 sonnig, 2 wolkig, 3 stark bewölkt, 4 bedeckt. */
    fun stage(octas: Int): Int = when {
        octas <= 0 -> 0
        octas <= 2 -> 1
        octas <= 5 -> 2
        octas <= 7 -> 3
        else -> 4
    }

    /** Wettercode für die Stufe (4 = eigener Code "Stark bewölkt"). */
    fun code(octas: Int): Int = when (stage(octas)) {
        0 -> 0
        1 -> 1
        2 -> 2
        3 -> 4
        else -> 3
    }

    /** "5/8 (63 %)" */
    fun label(pct: Double): String =
        if (pct.isNaN()) "–" else "${octas(pct)}/8 (${pct.roundToInt()} %)"

    /** "tief 10 % · mittel 0 % · hoch 90 %" */
    fun layers(low: Double, mid: Double, high: Double): String? {
        if (low.isNaN() || mid.isNaN() || high.isNaN()) return null
        return "tief ${low.roundToInt()} % · mittel ${mid.roundToInt()} % · hoch ${high.roundToInt()} %"
    }

    /** Zustandstext; bei bewölktem Himmel, der nur durch Schleierwolken so wirkt, "… mit Aufheiterungen". */
    fun describe(code: Int, isDay: Boolean, thinHigh: Boolean): String {
        val base = Fmt.codeAt(code, isDay)
        return if (thinHigh && code in 2..4) "$base mit Aufheiterungen" else base
    }
}

/**
 * Aktuelle Messwerte der nächsten DWD-Station über Bright Sky (kostenlos, ohne Schlüssel,
 * belastet das Open-Meteo-Kontingent nicht). Nur in Deutschland und nur bis 25 km Entfernung.
 */
object Observation {
    const val MAX_DIST_KM = 25.0
    const val MAX_AGE_SEC = 90 * 60L

    class Obs(
        val station: String,
        val distKm: Double,
        val t: Long,              // Unix-Sekunden der Messung
        val temp: Double,
        val wind: Double,         // km/h, 10-Minuten-Mittel
        val windDir: Double,
        val gust: Double,         // km/h
        val cloud: Double,        // %
        val humidity: Double,
        val pressure: Double,
        val condition: String,    // dry, fog, rain, sleet, snow, hail, thunderstorm (oder leer)
        val precip60: Double      // mm in der letzten Stunde
    ) {
        fun fresh(nowSec: Long = System.currentTimeMillis() / 1000) = nowSec - t in -600..MAX_AGE_SEC
    }

    fun fetchRaw(p: Place): String? {
        if (!RadarNowcast.covers(p.lat, p.lon)) return null
        val url = "https://api.brightsky.dev/current_weather?lat=%.4f&lon=%.4f&max_dist=%d"
            .format(Locale.US, p.lat, p.lon, (MAX_DIST_KM * 1000).toInt())
        return runCatching { Net.get(url, 8000) }.getOrNull()
    }

    private fun JSONObject.num(k: String): Double =
        if (!has(k) || isNull(k)) Double.NaN else optDouble(k, Double.NaN)

    fun parse(root: JSONObject): Obs? = runCatching {
        val w = root.optJSONObject("weather") ?: return null
        val sources = root.optJSONArray("sources") ?: return null
        val sid = w.optInt("source_id", -1)
        var src: JSONObject? = null
        for (i in 0 until sources.length()) {
            val s = sources.optJSONObject(i) ?: continue
            if (s.optInt("id") == sid) { src = s; break }
        }
        if (src == null) src = sources.optJSONObject(0)
        if (src == null) return null
        val dist = src.num("distance") / 1000
        if (dist.isNaN() || dist > MAX_DIST_KM) return null
        val t = RadarNowcast.parseIso(w.optString("timestamp")) ?: return null
        val name = src.optString("station_name").ifBlank { "DWD" }.lowercase()
            .replace(Regex("(^|[ \\-(/])(\\p{L})")) { it.groupValues[1] + it.groupValues[2].uppercase() }
        Obs(
            station = name,
            distKm = dist,
            t = t,
            temp = w.num("temperature"),
            wind = w.num("wind_speed_10"),
            windDir = w.num("wind_direction_10"),
            gust = w.num("wind_gust_speed_10"),
            cloud = w.num("cloud_cover"),
            humidity = w.num("relative_humidity"),
            pressure = w.num("pressure_msl"),
            condition = if (w.isNull("condition")) "" else w.optString("condition"),
            precip60 = w.num("precipitation_60")
        )
    }.getOrNull()
}

/** Werte für die "Jetzt"-Anzeige: Messung der Station, wo vorhanden, sonst Modell. */
class Current(
    val temp: Double,
    val feels: Double,
    val code: Int,
    val isDay: Boolean,
    val text: String,
    val wind: Double,
    val windDir: Double,
    val gust: Double,
    val humidity: Double,
    val pressure: Double,
    val cloud: Double,           // % (gemessen oder wirksam aus dem Modell)
    val cloudMeasured: Boolean,
    val layers: String?,         // Schichten aus dem Modell
    val obs: Observation.Obs?,   // gesetzt, wenn Messwerte verwendet werden
    val source: String           // "gemessen · Station Flensburg 11:20" bzw. "Modell ICON-D2"
)

fun Forecast.current(): Current? {
    val h = now ?: return null
    val o = obs?.takeIf { it.fresh() && it.distKm <= Observation.MAX_DIST_KM }
    fun m(v: Double?, fallback: Double) = if (v == null || v.isNaN()) fallback else v

    val temp = m(o?.temp, h.temp)
    // Gefühlte Temperatur: Modellwert um die gemessene Abweichung verschoben
    val feels = if (!h.feels.isNaN() && !h.temp.isNaN()) h.feels + (temp - h.temp) else h.feels
    val cloudMeasured = o != null && !o.cloud.isNaN()
    val cloud = if (cloudMeasured) o!!.cloud else h.cloudEff
    val cloudCode = if (!cloud.isNaN()) Sky.code(Sky.octas(cloud)) else h.code

    val code = if (o == null) h.code else {
        val p60 = if (o.precip60.isNaN()) 0.0 else o.precip60
        when (o.condition) {
            "thunderstorm" -> if (h.code >= 95) h.code else 95
            "hail" -> 96
            "fog" -> 45
            "snow" -> if (h.code in 71..77 || h.code in 85..86) h.code else when { p60 < 1 -> 71; p60 < 5 -> 73; else -> 75 }
            "sleet" -> 68
            "rain" -> if (h.code in 51..67 || h.code in 80..82) h.code else when { p60 < 2.5 -> 61; p60 < 10 -> 63; else -> 65 }
            // trocken: Zustand aus der (gemessenen) Bewölkung – auch wenn das Modell Regen hätte
            else -> if (h.code in 0..4 || h.code >= 51) cloudCode else h.code
        }
    }
    val text = if (code in 0..4) Sky.describe(code, h.isDay, !cloudMeasured && h.thinHigh) else Fmt.codeAt(code, h.isDay)
    val source = if (o != null) "gemessen · Station ${o.station} ${hhmm(o.t)}"
    else "Modell ${if (h.fromD2) "DWD ICON-D2" else "ECMWF IFS"}"
    return Current(
        temp = temp, feels = feels, code = code, isDay = h.isDay, text = text,
        wind = m(o?.wind, h.wind), windDir = m(o?.windDir, h.windDir), gust = m(o?.gust, h.gust),
        humidity = m(o?.humidity, h.humidity), pressure = m(o?.pressure, h.pressure),
        cloud = cloud, cloudMeasured = cloudMeasured,
        layers = Sky.layers(h.cloudLow, h.cloudMid, h.cloudHigh),
        obs = o, source = source
    )
}
