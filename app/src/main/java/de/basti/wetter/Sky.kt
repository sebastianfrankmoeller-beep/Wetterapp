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

/** Sonnenstand (vereinfachte NOAA-Formel, auf etwa 1° genau). */
object Sun {
    /** Sonnenhöhe in Grad über dem Horizont zum Zeitpunkt t (Unix-Sekunden). */
    fun elevation(lat: Double, lon: Double, t: Long): Double {
        val days = t / 86400.0 - 10957.5                     // Tage seit 1.1.2000 12:00 UTC
        val g = Math.toRadians((357.529 + 0.98560028 * days) % 360)
        val q = (280.459 + 0.98564736 * days) % 360
        val lam = Math.toRadians(q + 1.915 * Math.sin(g) + 0.020 * Math.sin(2 * g))
        val eps = Math.toRadians(23.439 - 0.00000036 * days)
        val dec = Math.asin(Math.sin(eps) * Math.sin(lam))
        val ra = Math.atan2(Math.cos(eps) * Math.sin(lam), Math.cos(lam))
        val gmst = (18.697374558 + 24.06570982441908 * days) % 24
        val ha = Math.toRadians(gmst * 15 + lon) - ra
        val la = Math.toRadians(lat)
        return Math.toDegrees(Math.asin(Math.sin(la) * Math.sin(dec) + Math.cos(la) * Math.cos(dec) * Math.cos(ha)))
    }

    /** Einstrahlung bei wolkenlosem Himmel in W/m² (Haurwitz). */
    fun clearSky(elevDeg: Double): Double {
        if (elevDeg <= 0) return 0.0
        val s = Math.sin(Math.toRadians(elevDeg))
        return 1098 * s * Math.exp(-0.057 / s)
    }
}

/**
 * Aktuelle Messwerte der DWD-Stationen über Bright Sky (kostenlos, ohne Schlüssel,
 * belastet das Open-Meteo-Kontingent nicht).
 * Temperatur, Wind, Feuchte, Druck und Wetterzustand nur von Stationen bis 25 km;
 * Bewölkung und Sonne (räumlich viel gleichmäßiger) von Stationen bis 60 km.
 */
object Observation {
    const val NEAR_KM = 25.0
    const val FAR_KM = 60.0
    const val MAX_AGE_SEC = 90 * 60L

    class Obs(
        val station: String,      // Station für Temperatur und Wind
        val distKm: Double,
        val t: Long,              // Unix-Sekunden der Messung
        val temp: Double,
        val wind: Double,         // km/h, 10-Minuten-Mittel
        val windDir: Double,
        val gust: Double,         // km/h
        val humidity: Double,
        val pressure: Double,
        val condition: String,    // dry, fog, rain, sleet, snow, hail, thunderstorm (oder leer)
        val precip60: Double,     // mm in der letzten Stunde
        val cloud: Double,        // %
        val cloudStation: String,
        val cloudDistKm: Double,
        val solarWm2: Double,     // mittlere Einstrahlung der letzten 10 (sonst 30) Minuten
        val sun30: Double,        // Sonnenscheindauer der letzten 30 Minuten
        val sunStation: String,
        val sunDistKm: Double
    ) {
        fun fresh(nowSec: Long = System.currentTimeMillis() / 1000) = nowSec - t in -600..MAX_AGE_SEC
    }

    fun fetchRaw(p: Place): String? {
        if (!RadarNowcast.covers(p.lat, p.lon)) return null
        val url = "https://api.brightsky.dev/current_weather?lat=%.4f&lon=%.4f&max_dist=%d"
            .format(Locale.US, p.lat, p.lon, (FAR_KM * 1000).toInt())
        return runCatching { Net.get(url, 8000) }.getOrNull()
    }

    private fun JSONObject.num(k: String): Double =
        if (!has(k) || isNull(k)) Double.NaN else optDouble(k, Double.NaN)

    /** DWD-Namen ("GLUECKSBURG-MEIERWIK") lesbar machen; Umlaute nur für bekannte Namen. */
    private val UMLAUTS = listOf(
        "Gluecksburg" to "Glücksburg", "Schaeferh" to "Schäferhaus", "Luebeck" to "Lübeck",
        "Neumuenster" to "Neumünster", "Eckernfoerde" to "Eckernförde", "Toenning" to "Tönning",
        "Schoenhagen" to "Schönhagen", "Buesum" to "Büsum", "Luebeck-Blankensee" to "Lübeck-Blankensee",
        "Goettingen" to "Göttingen", "Muenchen" to "München", "Koeln" to "Köln", "Duesseldorf" to "Düsseldorf",
        "Nuernberg" to "Nürnberg", "Wuerzburg" to "Würzburg", "Muenster" to "Münster", "Luedenscheid" to "Lüdenscheid"
    )

    fun prettyName(raw: String): String {
        var n = raw.ifBlank { "DWD" }.lowercase()
            .replace(Regex("(^|[ \\-(/])(\\p{L})")) { it.groupValues[1] + it.groupValues[2].uppercase() }
        for ((a, b) in UMLAUTS) n = n.replace(a, b)
        return n.replace(" (Schäferhaus)", "-Schäferhaus").replace("(Schäferhaus", "Schäferhaus")
    }

    fun parse(root: JSONObject): Obs? = runCatching {
        val w = root.optJSONObject("weather") ?: return null
        val arr = root.optJSONArray("sources") ?: return null
        val src = HashMap<Int, Pair<String, Double>>()
        for (i in 0 until arr.length()) {
            val s = arr.optJSONObject(i) ?: continue
            src[s.optInt("id")] = prettyName(s.optString("station_name")) to s.num("distance") / 1000
        }
        val primary = w.optInt("source_id", -1)
        val fb = w.optJSONObject("fallback_source_ids")
        fun srcOf(k: String): Pair<String, Double>? = src[fb?.optInt(k, primary) ?: primary]
        fun within(k: String, km: Double): Double {
            val d = srcOf(k)?.second ?: return Double.NaN
            return if (d.isNaN() || d > km) Double.NaN else w.num(k)
        }
        fun near(k: String) = within(k, NEAR_KM)
        fun far(k: String) = within(k, FAR_KM)
        val t = RadarNowcast.parseIso(w.optString("timestamp")) ?: return null
        val main = src[primary] ?: return null

        var solar = far("solar_10") * 6000                 // kWh/m² je 10 Min. → W/m²
        var solarKey = "solar_10"
        if (solar.isNaN()) { solar = far("solar_30") * 2000; solarKey = "solar_30" }
        val sun30 = far("sunshine_30")
        val sunSrc = srcOf(if (!solar.isNaN()) solarKey else "sunshine_30")
        val cloudSrc = srcOf("cloud_cover")
        val condSrc = srcOf("condition")
        Obs(
            station = main.first,
            distKm = main.second,
            t = t,
            temp = near("temperature"),
            wind = near("wind_speed_10"),
            windDir = near("wind_direction_10"),
            gust = near("wind_gust_speed_10"),
            humidity = near("relative_humidity"),
            pressure = near("pressure_msl"),
            condition = if (w.isNull("condition") || (condSrc?.second ?: 99.0) > NEAR_KM) "" else w.optString("condition"),
            precip60 = near("precipitation_60"),
            cloud = far("cloud_cover"),
            cloudStation = cloudSrc?.first ?: "",
            cloudDistKm = cloudSrc?.second ?: Double.NaN,
            solarWm2 = solar,
            sun30 = sun30,
            sunStation = sunSrc?.first ?: "",
            sunDistKm = sunSrc?.second ?: Double.NaN
        )
    }.getOrNull()

    /** Gemessene Abweichung (Station − Modell) zum Zeitpunkt der Messung, für Lauf-Check und Stundenstreifen. */
    fun adjust(hours: MutableList<Hour>, o: Obs?, nowSec: Long) {
        if (o == null || !o.fresh(nowSec) || hours.isEmpty()) return
        val h0 = hours.first()
        fun diff(m: Double, model: Double) = if (m.isNaN() || model.isNaN()) 0.0 else m - model
        val dT = diff(o.temp, h0.temp)
        val dW = diff(o.wind, h0.wind)
        val dG = diff(o.gust, h0.gust)
        if (dT == 0.0 && dW == 0.0 && dG == 0.0) return
        for (i in hours.indices) {
            val h = hours[i]
            // voll bis jetzt, dann bis +2 Stunden auf das Modell auslaufend
            val wgt = (1.0 - (h.t - nowSec).coerceAtLeast(0L) / 7200.0).coerceIn(0.0, 1.0)
            if (wgt <= 0.0) break
            hours[i] = h.shifted(dT * wgt, dW * wgt, dG * wgt)
        }
    }
}

/** Kopie einer Stunde mit verschobener Temperatur (und gefühlter), Wind und Böen. */
fun Hour.shifted(dT: Double, dW: Double, dG: Double) = Hour(
    t, temp + dT, feels + dT, humidity, precip, snow, code, cloud,
    (wind + dW).coerceAtLeast(0.0), windDir, (gust + dG).coerceAtLeast((wind + dW).coerceAtLeast(0.0)),
    isDay, pressure, prob, fromD2, cape, lpi, flashes, snowDepth, snowLine, cloudLow, cloudMid, cloudHigh
)

/** Werte für die "Jetzt"-Anzeige: Messung der Stationen, wo vorhanden, sonst Modell. */
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
    val cloudSource: String,     // "gemessen", "gemessen Schleswig, 32 km" oder "Modell"
    val sunInfo: String?,        // "Sonne: 30 von 30 Min. (Schleswig)"
    val layers: String?,         // Schichten aus dem Modell
    val obs: Observation.Obs?,   // gesetzt, wenn Messwerte verwendet werden
    val source: String           // "gemessen 14:00 · Glücksburg-Meierwik (3 km)" bzw. "Modell DWD ICON-D2"
)

/**
 * Zustand aus Bewölkung (Achtel) und – tagsüber – gemessener Sonne:
 * mit Sonne wird z. B. aus "Wolkig" "Sonnig, teils wolkig".
 */
private fun skyState(octas: Int, sunny: Boolean?, isDay: Boolean): Pair<Int, String> {
    val sun = isDay && sunny == true
    return when {
        octas < 0 -> if (sun) 1 to "Sonnig" else -1 to ""
        octas == 0 -> 0 to "Klar"
        octas <= 2 -> 1 to Fmt.codeAt(1, isDay)
        octas <= 5 -> if (sun) 1 to "Sonnig, teils wolkig" else 2 to "Wolkig"
        octas <= 7 -> if (sun) 2 to "Wolkig mit Sonne" else 4 to "Stark bewölkt"
        else -> if (sun) 2 to "Wolkig mit Sonne" else 3 to "Bedeckt"
    }
}

private fun km(v: Double) = if (v.isNaN()) "" else "${v.roundToInt()} km"

fun Forecast.current(): Current? {
    val h = now ?: return null
    val nowSec = System.currentTimeMillis() / 1000
    val o = obs?.takeIf { it.fresh(nowSec) }
    fun m(v: Double?, fallback: Double) = if (v == null || v.isNaN()) fallback else v

    // Stundenwerte sind bereits um die Messung verschoben (Observation.adjust)
    val temp = m(o?.temp, h.temp)
    val feels = if (!h.feels.isNaN() && !h.temp.isNaN()) h.feels + (temp - h.temp) else h.feels

    // Bewölkung: gemessen (bis 60 km) oder wirksam aus dem Modell
    val cloudMeasured = o != null && !o.cloud.isNaN()
    val cloud = if (cloudMeasured) o!!.cloud else h.cloudEff
    val cloudSource = when {
        !cloudMeasured -> "Modell"
        o!!.cloudStation == o.station || o.cloudDistKm <= Observation.NEAR_KM -> "gemessen"
        else -> "gemessen ${o.cloudStation}, ${km(o.cloudDistKm)}"
    }

    // Sonne jetzt? Einstrahlung im Vergleich zu wolkenlos, sonst Sonnenscheindauer der letzten 30 Minuten
    var sunny: Boolean? = null
    var sunInfo: String? = null
    if (o != null && h.isDay) {
        val elev = Sun.elevation(place.lat, place.lon, o.t - 300)
        val where = if (o.sunStation.isNotEmpty() && o.sunStation != o.station) " (${o.sunStation})" else ""
        if (!o.solarWm2.isNaN() && elev >= 8) {
            val ratio = o.solarWm2 / Sun.clearSky(elev)
            sunny = ratio >= 0.7
            sunInfo = "Sonne: ${(ratio * 100).roundToInt().coerceAtMost(100)} % der möglichen Einstrahlung$where"
        } else if (!o.sun30.isNaN()) {
            sunny = o.sun30 >= 27
            sunInfo = "Sonne: ${o.sun30.roundToInt()} von 30 Min.$where"
        }
    }

    // Regnet es laut Radar gerade nicht, gilt der Himmel statt eines Modell-Regencodes
    val slotNow = nowcast.firstOrNull { it.first <= nowSec && nowSec < it.first + 900 }
    val radarDry = slotNow != null && slotNow.first < nowcastRadarUntil && slotNow.second < 0.03

    val octas = Sky.octas(cloud)
    val (skyCode, skyText) = skyState(if (cloudMeasured || sunny != null) octas else -1, sunny, h.isDay)
    val p60 = o?.precip60?.takeIf { !it.isNaN() } ?: 0.0
    val code: Int
    val text: String
    when (o?.condition ?: "") {
        "thunderstorm" -> { code = if (h.code >= 95) h.code else 95; text = Fmt.codeAt(code, h.isDay) }
        "hail" -> { code = 96; text = Fmt.codeAt(code, h.isDay) }
        "fog" -> { code = 45; text = Fmt.codeAt(code, h.isDay) }
        "snow" -> { code = if (h.code in 71..77 || h.code in 85..86) h.code else when { p60 < 1 -> 71; p60 < 5 -> 73; else -> 75 }; text = Fmt.codeAt(code, h.isDay) }
        "sleet" -> { code = 68; text = Fmt.codeAt(code, h.isDay) }
        "rain" -> { code = if (h.code in 51..67 || h.code in 80..82) h.code else when { p60 < 2.5 -> 61; p60 < 10 -> 63; else -> 65 }; text = Fmt.codeAt(code, h.isDay) }
        else -> {
            val dry = o?.condition == "dry" || radarDry || h.code in 0..4
            if (!dry || h.code == 45 || h.code == 48) { code = h.code; text = Fmt.codeAt(code, h.isDay) }
            else if (skyCode >= 0) { code = skyCode; text = skyText }
            else {
                code = if (h.code in 0..4) h.code else Sky.code(Sky.octas(h.cloudEff))
                text = Sky.describe(code, h.isDay, h.thinHigh)
            }
        }
    }

    val source = if (o != null && !o.temp.isNaN()) "gemessen ${hhmm(o.t)} · ${o.station} (${km(o.distKm)})"
    else "Modell ${if (h.fromD2) "DWD ICON-D2" else "ECMWF IFS"}"
    return Current(
        temp = temp, feels = feels, code = code, isDay = h.isDay, text = text,
        wind = m(o?.wind, h.wind), windDir = m(o?.windDir, h.windDir), gust = m(o?.gust, h.gust),
        humidity = m(o?.humidity, h.humidity), pressure = m(o?.pressure, h.pressure),
        cloud = cloud, cloudMeasured = cloudMeasured, cloudSource = cloudSource, sunInfo = sunInfo,
        layers = Sky.layers(h.cloudLow, h.cloudMid, h.cloudHigh),
        obs = o, source = source
    )
}
