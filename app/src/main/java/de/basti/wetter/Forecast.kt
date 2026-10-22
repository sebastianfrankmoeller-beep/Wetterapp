package de.basti.wetter

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.roundToInt

/** Kopie einer Stunde mit anderer Niederschlags- und Schneemenge. */
fun Hour.withPrecip(mm: Double, snowCm: Double, newCode: Int = code) = Hour(
    t, temp, feels, humidity, mm, snowCm, newCode, cloud, wind, windDir, gust, isDay, pressure, prob, fromD2,
    cape, lpi, flashes, snowDepth, snowLine, cloudLow, cloudMid, cloudHigh
)

/**
 * Wettercode passend zu einer gemessenen/vorhergesagten Menge (mm/h): Stärke nach DWD
 * (leicht < 2,5 · mäßig < 10 · stark), Regen oder Schauer, Niesel, Schnee.
 * Gewitter und gefrierender Regen aus dem Modell bleiben erhalten.
 */
object RainCode {
    fun of(mmh: Double, modelCode: Int, snowy: Boolean, showery: Boolean, drizzle: Boolean): Int {
        if (modelCode >= 95 || modelCode in 56..57 || modelCode in 66..67) return modelCode
        val lvl = RainRate.level(mmh).coerceIn(1, 3) - 1          // 0 leicht, 1 mäßig, 2 stark
        return when {
            snowy -> if (showery) (if (lvl >= 1) 86 else 85) else listOf(71, 73, 75)[lvl]
            drizzle && mmh < 1.0 -> if (mmh < 0.5) 51 else 53
            showery -> listOf(80, 81, 82)[lvl]
            else -> listOf(61, 63, 65)[lvl]
        }
    }
}

/** Eine Stunde Vorhersage. Fehlende Werte sind NaN. */
class Hour(
    val t: Long,              // Unix-Sekunden
    val temp: Double,
    val feels: Double,
    val humidity: Double,
    val precip: Double,       // mm in dieser Stunde
    val snow: Double,         // cm
    val code: Int,            // WMO-Wettercode
    val cloud: Double,        // %
    val wind: Double,         // km/h
    val windDir: Double,      // Grad, woher der Wind kommt
    val gust: Double,         // km/h
    val isDay: Boolean,
    val pressure: Double,     // hPa
    val prob: Double,         // Regenwahrscheinlichkeit % (ECMWF-Ensemble)
    val fromD2: Boolean,      // true = ICON-D2, false = ECMWF IFS
    val cape: Double,         // Gewitterenergie J/kg
    val lpi: Double,          // Blitz-Potenzial (ICON-D2), J/kg
    val flashes: Double,      // Blitzdichte (ECMWF), Blitze je km² und Tag
    val snowDepth: Double,    // Schneehöhe in cm
    val snowLine: Double,     // Schneefallgrenze in m (nur ICON-D2)
    val cloudLow: Double = Double.NaN,   // Bewölkung tief %
    val cloudMid: Double = Double.NaN,   // Bewölkung mittel %
    val cloudHigh: Double = Double.NaN   // Bewölkung hoch % (oft dünne Schleierwolken)
) {
    /** Wirksame Bewölkung in %: hohe Schleierwolken zählen nur halb. */
    val cloudEff: Double by lazy { Sky.effective(cloud, cloudLow, cloudMid, cloudHigh) }
    /** true, wenn die Bewölkungsstufe nur durch hohe Wolken angehoben wird ("… mit Aufheiterungen"). */
    val thinHigh: Boolean by lazy { Sky.thinHigh(cloud, cloudLow, cloudMid, cloudHigh) }

    /** Gewitterrisiko: 0 = keins, 1 = gering, 2 = mäßig, 3 = hoch. */
    val thunder: Int by lazy { Thunder.level(this) }
}

/** Einstufung des Gewitterrisikos aus Blitz-Potenzial, Blitzdichte, CAPE und Wettercode. */
object Thunder {
    val NAMES = arrayOf("kein", "gering", "mäßig", "hoch")

    fun level(h: Hour): Int {
        var lvl = 0
        if (!h.lpi.isNaN()) {
            lvl = when {
                h.lpi >= 10 -> 3
                h.lpi >= 3 -> 2
                h.lpi >= 0.8 -> 1
                else -> 0
            }
        } else if (!h.flashes.isNaN()) {
            lvl = when {
                h.flashes >= 1.0 -> 3
                h.flashes >= 0.1 -> 2
                h.flashes >= 0.01 -> 1
                else -> 0
            }
        } else if (!h.cape.isNaN()) {
            lvl = when {
                h.cape >= 1500 && h.precip >= 0.3 -> 2
                h.cape >= 500 && h.precip >= 0.2 -> 1
                else -> 0
            }
        }
        if (h.code in 95..99) lvl = maxOf(lvl, if (h.code == 95) 2 else 3)
        return lvl
    }
}

class Day(
    val start: Long,          // lokale Mitternacht als Unix-Sekunden
    val min: Double,
    val max: Double,
    val precip: Double,
    val snow: Double,
    val probMax: Double,
    val code: Int,
    val isDayIcon: Boolean,
    val windMax: Double,
    val gustMax: Double,
    val windDir: Double,
    val sunrise: Long,
    val sunset: Long,
    val fromD2: Boolean,
    val thunder: Int,         // höchstes Gewitterrisiko des Tages
    val snowDepth: Double     // größte Schneehöhe des Tages (cm)
)

class Forecast(
    val place: Place,
    val offsetSec: Int,       // Zeitzonen-Versatz des Ortes
    val hours: List<Hour>,    // ab der aktuellen Stunde
    val days: List<Day>,
    val nowcast: List<Pair<Long, Double>>, // 15-Minuten-Niederschlag (mm) der nächsten Stunden, nur ICON-D2-Gebiet
    val fetchedAt: Long,      // Millisekunden
    val hasD2: Boolean,
    val nowcastSnow: Boolean = false,  // Niederschlag in den nächsten Stunden überwiegend als Schnee
    val uv: Map<Long, Double> = emptyMap(),  // UV-Index je Stunde (nächste 24 h)
    val nowcastRadarUntil: Long = 0,   // bis hierhin (Unix-Sek.) stammt der Nowcast aus dem DWD-Radar, danach Modell
    val elevation: Double = Double.NaN, // Höhe des Ortes in m
    val obs: Observation.Obs? = null,  // Messwerte der nächsten DWD-Station (Bright Sky)
    val nowcastNear: Map<Long, Double> = emptyMap(), // stärkste Radarzelle im Umkreis von 3 km (mm je 15 Min.)
    val drizzle: Boolean = false,      // Niesel-Lage: Radar sieht kaum etwas, Modell rechnet mit Niederschlag
    val steadyRain: Boolean = false    // laut Radar seit ca. 1 Stunde durchgehend und flächig nass (kein Schauer)
) {
    /** Stunde, die "jetzt" am besten beschreibt. */
    val now: Hour? get() = hours.firstOrNull()

    /** Kalender in der Zeitzone des Ortes (für Uhrzeiten und Wochentage). */
    fun cal(t: Long): Calendar = Calendar.getInstance(tz(), Locale.GERMANY).apply { timeInMillis = t * 1000 }

    fun tz(): TimeZone = SimpleTz.of(offsetSec)

    fun hhmm(t: Long): String {
        val c = cal(t)
        return "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
    }

    fun hour(t: Long): Int = cal(t).get(Calendar.HOUR_OF_DAY)

    fun weekdayShort(t: Long): String = WEEKDAYS[cal(t).get(Calendar.DAY_OF_WEEK) - 1]

    fun dayLabel(d: Day, index: Int): String = when (index) {
        0 -> "Heute"
        1 -> "Morgen"
        else -> weekdayShort(d.start + 6 * 3600)
    }

    fun dateShort(t: Long): String {
        val c = cal(t)
        return "%d.%d.".format(c.get(Calendar.DAY_OF_MONTH), c.get(Calendar.MONTH) + 1)
    }

    companion object {
        val WEEKDAYS = arrayOf("So", "Mo", "Di", "Mi", "Do", "Fr", "Sa")
    }
}

/** Feste Zeitzone mit dem Versatz, den Open-Meteo für den Ort liefert. */
object SimpleTz {
    fun of(offsetSec: Int): TimeZone {
        val h = abs(offsetSec) / 3600
        val m = (abs(offsetSec) % 3600) / 60
        val sign = if (offsetSec >= 0) "+" else "-"
        return TimeZone.getTimeZone("GMT$sign%02d:%02d".format(h, m))
    }
}

/** Kleiner HTTP-Helfer (immer im Hintergrund-Thread aufrufen). */
object Net {
    const val UA = "WetterApp/1.0 (privat; Android)"

    fun get(url: String, timeoutMs: Int = 15000): String {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = timeoutMs
        con.readTimeout = timeoutMs
        con.setRequestProperty("User-Agent", UA)
        // Komprimiert anfordern (Bright Sky verlangt das für Radardaten) und selbst entpacken
        con.setRequestProperty("Accept-Encoding", "gzip")
        try {
            val code = con.responseCode
            val raw = if (code in 200..299) con.inputStream else con.errorStream
            val stream = if (raw != null && con.contentEncoding.equals("gzip", ignoreCase = true))
                java.util.zip.GZIPInputStream(raw) else raw
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val reason = runCatching { JSONObject(body).optString("reason") }.getOrNull()
                throw RuntimeException(if (!reason.isNullOrBlank()) reason else "HTTP $code")
            }
            return body
        } finally {
            con.disconnect()
        }
    }

    fun getBytes(url: String, timeoutMs: Int = 15000): ByteArray? {
        val con = URL(url).openConnection() as HttpURLConnection
        con.connectTimeout = timeoutMs
        con.readTimeout = timeoutMs
        con.setRequestProperty("User-Agent", UA)
        return try {
            if (con.responseCode !in 200..299) null
            else con.inputStream.use { it.readBytes() }
        } catch (e: Exception) {
            null
        } finally {
            con.disconnect()
        }
    }

    fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
}

/**
 * Wetterdaten von Open-Meteo.
 * Kurzfrist (bis 48 h): DWD ICON-D2 (2 km, für Deutschland und Nachbarländer).
 * Danach und außerhalb des ICON-D2-Gebiets: ECMWF IFS HRES (9 km) bis 15 Tage.
 */
object WeatherApi {
    private const val BASE = "https://api.open-meteo.com/v1/forecast"
    private const val VARS = "temperature_2m,apparent_temperature,relative_humidity_2m,precipitation,snowfall," +
        "weather_code,cloud_cover,cloud_cover_low,cloud_cover_mid,cloud_cover_high,wind_speed_10m,wind_direction_10m,wind_gusts_10m,is_day,pressure_msl,cape,snow_depth"

    private fun loc(p: Place) = "latitude=%.4f&longitude=%.4f".format(Locale.US, p.lat, p.lon)

    /** Lädt beide Modelle und gibt die Rohdaten (für den Zwischenspeicher) zurück. */
    fun fetchRaw(p: Place): String {
        val ecUrl = "$BASE?${loc(p)}&models=ecmwf_ifs&hourly=$VARS,precipitation_probability,lightning_density" +
            "&daily=sunrise,sunset&forecast_days=16&past_hours=24&timeformat=unixtime&timezone=auto"
        val d2Url = "$BASE?${loc(p)}&models=icon_d2&hourly=$VARS,lightning_potential,snowfall_height" +
            "&minutely_15=precipitation,snowfall&forecast_minutely_15=13" +
            "&forecast_hours=48&past_hours=24&timeformat=unixtime&timezone=auto"

        // UV-Index (Standardmodell, nur für die nächsten Stunden – optional)
        val uvUrl = "$BASE?${loc(p)}&hourly=uv_index&forecast_hours=24&past_hours=1&timeformat=unixtime&timezone=auto"

        // Alle Abfragen parallel.
        var d2: String? = null
        var uv: String? = null
        var radar: String? = null
        var obs: String? = null
        val t = Thread { d2 = runCatching { Net.get(d2Url) }.getOrNull() }
        val tu = Thread { uv = runCatching { Net.get(uvUrl, 8000) }.getOrNull() }
        val tr = Thread { radar = RadarNowcast.fetchRaw(p) }
        val to = Thread { obs = Observation.fetchRaw(p) }
        t.start(); tu.start(); tr.start(); to.start()
        val ec = Net.get(ecUrl)
        t.join(20000); tu.join(10000); tr.join(12000); to.join(10000)
        return JSONObject()
            .put("ec", JSONObject(ec))
            .put("d2", d2?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject.NULL)
            .put("uv", uv?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject.NULL)
            .put("radar", radar?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject.NULL)
            .put("obs", obs?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject.NULL)
            .put("at", System.currentTimeMillis())
            .toString()
    }

    fun fetch(p: Place): Pair<Forecast, String> {
        val raw = fetchRaw(p)
        return parse(p, raw) to raw
    }

    // ------------------------------------------------------------------ Auswertung

    private fun JSONArray?.d(i: Int): Double =
        if (this == null || i >= length() || isNull(i)) Double.NaN else optDouble(i, Double.NaN)

    private class Series(o: JSONObject?) {
        val h: JSONObject? = o?.optJSONObject("hourly")
        val time: JSONArray? = h?.optJSONArray("time")
        fun a(name: String): JSONArray? = h?.optJSONArray(name)
    }

    fun parse(p: Place, raw: String): Forecast {
        val root = JSONObject(raw)
        val ec = root.getJSONObject("ec")
        val d2o = root.optJSONObject("d2")
        val offset = ec.optInt("utc_offset_seconds", 0)
        val fetchedAt = root.optLong("at", System.currentTimeMillis())

        val e = Series(ec)
        val d = Series(d2o)

        // ICON-D2-Stunden nach Zeit nachschlagen.
        val d2Index = HashMap<Long, Int>()
        d.time?.let { for (i in 0 until it.length()) d2Index[it.getLong(i)] = i }

        val nowSec = System.currentTimeMillis() / 1000
        val currentHour = nowSec - (nowSec % 3600)
        // Beginn des heutigen Tages (Ortszeit): für Tageshöchst- und Tiefstwerte des ganzen Tages
        val todayStart = (nowSec + offset).let { it - Math.floorMod(it, 86400L) } - offset

        val hours = ArrayList<Hour>()
        val et = e.time ?: JSONArray()
        val eTemp = e.a("temperature_2m"); val eFeels = e.a("apparent_temperature")
        val eHum = e.a("relative_humidity_2m"); val ePr = e.a("precipitation")
        val eSnow = e.a("snowfall"); val eCode = e.a("weather_code"); val eCloud = e.a("cloud_cover")
        val eWind = e.a("wind_speed_10m"); val eDir = e.a("wind_direction_10m"); val eGust = e.a("wind_gusts_10m")
        val eDay = e.a("is_day"); val ePres = e.a("pressure_msl"); val eProb = e.a("precipitation_probability")
        val eLow = e.a("cloud_cover_low"); val eMid = e.a("cloud_cover_mid"); val eHigh = e.a("cloud_cover_high")
        val eCape = e.a("cape"); val eSnowD = e.a("snow_depth"); val eFlash = e.a("lightning_density")

        val dTemp = d.a("temperature_2m"); val dFeels = d.a("apparent_temperature")
        val dHum = d.a("relative_humidity_2m"); val dPr = d.a("precipitation")
        val dSnow = d.a("snowfall"); val dCode = d.a("weather_code"); val dCloud = d.a("cloud_cover")
        val dWind = d.a("wind_speed_10m"); val dDir = d.a("wind_direction_10m"); val dGust = d.a("wind_gusts_10m")
        val dDay = d.a("is_day"); val dPres = d.a("pressure_msl")
        val dLow = d.a("cloud_cover_low"); val dMid = d.a("cloud_cover_mid"); val dHigh = d.a("cloud_cover_high")
        val dCape = d.a("cape"); val dSnowD = d.a("snow_depth"); val dLpi = d.a("lightning_potential"); val dLine = d.a("snowfall_height")

        var hasD2 = false
        for (i in 0 until et.length()) {
            val t = et.getLong(i)
            if (t < todayStart) continue
            val j = d2Index[t]
            val useD2 = j != null && !dTemp.d(j).isNaN() && !dCode.d(j).isNaN()
            if (useD2) hasD2 = true
            fun pick(da: JSONArray?, ea: JSONArray?): Double {
                if (useD2) {
                    val v = da.d(j!!)
                    if (!v.isNaN()) return v
                }
                return ea.d(i)
            }
            val temp = pick(dTemp, eTemp)
            if (temp.isNaN()) continue
            val rawCode = pick(dCode, eCode).let { if (it.isNaN()) 3 else it.toInt() }
            val cloud = pick(dCloud, eCloud)
            val cLow = pick(dLow, eLow); val cMid = pick(dMid, eMid); val cHigh = pick(dHigh, eHigh)
            // Ohne Niederschlag/Nebel: Zustand aus der Bewölkung in Achteln (Schleierwolken halb gewichtet)
            val code = if (rawCode in 0..3 && !cloud.isNaN())
                Sky.code(Sky.octas(Sky.effective(cloud, cLow, cMid, cHigh))) else rawCode
            hours.add(
                Hour(
                    t = t,
                    temp = temp,
                    feels = pick(dFeels, eFeels),
                    humidity = pick(dHum, eHum),
                    precip = pick(dPr, ePr).let { if (it.isNaN()) 0.0 else it },
                    snow = pick(dSnow, eSnow).let { if (it.isNaN()) 0.0 else it },
                    code = code,
                    cloud = cloud,
                    wind = pick(dWind, eWind),
                    windDir = pick(dDir, eDir),
                    gust = pick(dGust, eGust),
                    isDay = pick(dDay, eDay).let { if (it.isNaN()) true else it >= 0.5 },
                    pressure = pick(dPres, ePres),
                    prob = eProb.d(i),
                    fromD2 = useD2,
                    cape = pick(dCape, eCape),
                    lpi = if (useD2) dLpi.d(j!!) else Double.NaN,
                    flashes = if (useD2) Double.NaN else eFlash.d(i),
                    snowDepth = pick(dSnowD, eSnowD).let { if (it.isNaN()) Double.NaN else it * 100 },
                    snowLine = if (j != null) dLine.d(j) else Double.NaN,
                    cloudLow = cLow, cloudMid = cMid, cloudHigh = cHigh
                )
            )
        }

        // Nowcast (15 Minuten) aus ICON-D2
        val nowcast = ArrayList<Pair<Long, Double>>()
        var nowcastSnowy = false
        d2o?.optJSONObject("minutely_15")?.let { m ->
            val mt = m.optJSONArray("time")
            val mp = m.optJSONArray("precipitation")
            val ms = m.optJSONArray("snowfall")
            var rain = 0.0; var snow = 0.0
            if (mt != null) for (i in 0 until mt.length()) {
                val t = mt.getLong(i)
                val v = mp.d(i)
                if (t + 900 > nowSec && !v.isNaN()) {
                    nowcast.add(t to v)
                    // Schneefall in cm ≈ 0,7 × mm Wasser
                    val sn = ms.d(i).let { if (it.isNaN()) 0.0 else it / 0.7 }
                    snow += sn.coerceAtMost(v); rain += (v - sn).coerceAtLeast(0.0)
                }
            }
            nowcastSnowy = snow > rain && snow > 0.05
        }

        // Gemessener Regen + DWD-Radar-Nowcast ersetzt die Modellwerte, soweit vorhanden (genauer als ICON-D2)
        var radarUntil = 0L
        var near: Map<Long, Double> = emptyMap()
        var drizzle = false
        var steady = false
        val obs = root.optJSONObject("obs")?.let { Observation.parse(it, ec.optDouble("elevation", Double.NaN)) }
        root.optJSONObject("radar")?.let { r ->
            near = RadarNowcast.slots(r, nearMax = true).filter { it.first + 900 > nowSec }.toMap()
            val slots = RadarNowcast.slots(r).filter { it.first + 900 > nowSec }
            if (slots.size >= 4) {
                radarUntil = slots.last().first + 900
                val modelNc = nowcast.toMap()
                val model = nowcast.filter { it.first >= radarUntil }
                // Niesel aus tiefen Wolken sieht das Radar kaum (Strahl zu hoch): nur schwache Echos an der
                // Messgrenze oder eine Station bis 40 km meldet Regen – und das Modell rechnet mit Niederschlag.
                // Dann gilt der höhere Wert aus Radar und Modell, damit das Radar den Regen nicht "beendet".
                val stationWet = obs?.fresh(nowSec) == true && obs.farWet
                val modelWet = modelNc.filterKeys { it < radarUntil }.values.any { it * 4 >= 0.1 }
                drizzle = modelWet && (RadarNowcast.weakEcho(r, nowSec) || stationWet)
                steady = RadarNowcast.steady(r, nowSec)
                nowcast.clear()
                nowcast.addAll(if (drizzle) slots.map { (t, v) -> t to maxOf(v, modelNc[t] ?: 0.0) } else slots)
                nowcast.addAll(model)
            }
        }

        // Stunden, die der Kurzfrist-Verlauf (Radar + ICON-D2 15 Min.) ganz abdeckt, übernehmen dessen Menge –
        // so zeigen Stundenstreifen, Regenkarte und Lauf-Check dasselbe
        if (radarUntil > 0) {
            val nc = nowcast.toMap()
            val slotNow = nowSec - Math.floorMod(nowSec, 900L)
            for (i in hours.indices) {
                val h = hours[i]
                if (h.t + 3600 <= slotNow) continue
                val from = maxOf(h.t, slotNow)
                val keys = (from until h.t + 3600 step 900).toList()
                if (keys.isEmpty() || keys.first() >= radarUntil || keys.any { it !in nc }) continue
                val mm = keys.sumOf { nc[it]!! }
                // Symbol passend zur Menge: trocken → Bewölkung, nass → Regen/Schauer nach Stärke
                val mmh = mm * 3600.0 / (keys.size * 900)
                val code = when {
                    h.code >= 95 -> h.code
                    mmh < 0.1 -> if (h.code in 51..86) Sky.code(Sky.octas(h.cloudEff)) else h.code
                    h.code in 51..86 || h.code in 0..4 || h.code == 45 || h.code == 48 ->
                        RainCode.of(mmh, h.code, nowcastSnowy, showery = !steady && h.code in 80..86,
                            drizzle = drizzle)
                    else -> h.code
                }
                hours[i] = h.withPrecip(mm, if (nowcastSnowy) mm / 0.7 else 0.0, code)
            }
        }

        // Sonnenauf-/untergang je Tag
        val sun = HashMap<Long, Pair<Long, Long>>()
        ec.optJSONObject("daily")?.let { dd ->
            val tt = dd.optJSONArray("time"); val sr = dd.optJSONArray("sunrise"); val ss = dd.optJSONArray("sunset")
            if (tt != null) for (i in 0 until tt.length()) {
                sun[tt.getLong(i)] = (sr?.optLong(i) ?: 0L) to (ss?.optLong(i) ?: 0L)
            }
        }

        // Tage aus allen Stunden ab Mitternacht; angezeigt werden die Stunden ab jetzt
        val days = buildDays(hours, offset, sun)
        hours.removeAll { it.t < currentHour }
        Observation.adjust(hours, obs, nowSec)

        val uv = HashMap<Long, Double>()
        root.optJSONObject("uv")?.optJSONObject("hourly")?.let { u ->
            val tt = u.optJSONArray("time"); val vv = u.optJSONArray("uv_index")
            if (tt != null) for (i in 0 until tt.length()) { val v = vv.d(i); if (!v.isNaN()) uv[tt.getLong(i)] = v }
        }
        return Forecast(p, offset, hours, days, nowcast, fetchedAt, hasD2, nowcastSnowy, uv, radarUntil, ec.optDouble("elevation", Double.NaN),
            obs, near, drizzle, steady)
    }

    private fun dayStart(t: Long, offset: Int): Long {
        val local = t + offset
        return local - Math.floorMod(local, 86400L) - offset
    }

    private fun buildDays(hours: List<Hour>, offset: Int, sun: Map<Long, Pair<Long, Long>>): List<Day> {
        val groups = LinkedHashMap<Long, MutableList<Hour>>()
        for (h in hours) groups.getOrPut(dayStart(h.t, offset)) { ArrayList() }.add(h)
        val out = ArrayList<Day>()
        for ((start, hs) in groups) {
            // Ein Tag mit nur wenigen Reststunden (spät abends) zählt trotzdem als "Heute".
            if (hs.size < 2 && out.isNotEmpty()) continue
            val temps = hs.map { it.temp }
            val precip = hs.sumOf { it.precip }
            val snow = hs.sumOf { it.snow }
            val probs = hs.map { it.prob }.filter { !it.isNaN() }
            val winds = hs.map { it.wind }.filter { !it.isNaN() }
            val gusts = hs.map { it.gust }.filter { !it.isNaN() }
            val daytime = hs.filter { val lh = Math.floorMod(it.t + offset, 86400L) / 3600; lh in 7L..20L }
                .ifEmpty { hs }
            val code = dayCode(daytime, precip)
            val maxWindHour = hs.filter { !it.wind.isNaN() }.maxByOrNull { it.wind }
            val sr = sun[start] ?: sun.entries.firstOrNull { abs(it.key - start) < 7200 }?.value
            out.add(
                Day(
                    start = start,
                    min = temps.minOrNull() ?: Double.NaN,
                    max = temps.maxOrNull() ?: Double.NaN,
                    precip = precip,
                    snow = snow,
                    probMax = probs.maxOrNull() ?: Double.NaN,
                    code = code,
                    isDayIcon = true,
                    windMax = winds.maxOrNull() ?: Double.NaN,
                    gustMax = gusts.maxOrNull() ?: Double.NaN,
                    windDir = maxWindHour?.windDir ?: Double.NaN,
                    sunrise = sr?.first ?: 0L,
                    sunset = sr?.second ?: 0L,
                    fromD2 = hs.count { it.fromD2 } * 2 > hs.size,
                    thunder = hs.maxOf { it.thunder },
                    snowDepth = hs.map { it.snowDepth }.filter { !it.isNaN() }.maxOrNull() ?: Double.NaN
                )
            )
        }
        return out
    }

    /**
     * Ein Symbol für den ganzen Tag: Gewitter > Schnee > Regen > Bewölkung.
     * Niederschlag zählt erst ab 0,3 mm am Tag, sonst entscheidet die mittlere Bewölkung.
     */
    private fun dayCode(hs: List<Hour>, precipSum: Double): Int {
        val codes = hs.map { it.code }
        if (codes.any { it >= 95 }) return codes.filter { it >= 95 }.max()
        if (precipSum >= 0.3) {
            val wet = codes.filter { it in 51..86 }
            if (wet.isNotEmpty()) {
                // Schauer, wenn die nassen Stunden überwiegend Schauer sind
                val snowy = wet.count { it in 71..77 || it in 85..86 }
                val showers = wet.count { it in 80..86 }
                val strongest = wet.maxOf { intensity(it) }
                return when {
                    snowy * 2 >= wet.size -> if (showers * 2 >= wet.size) 85 else listOf(71, 73, 75)[strongest]
                    showers * 2 >= wet.size -> listOf(80, 81, 82)[strongest]
                    wet.all { it in 51..57 } -> 53
                    else -> listOf(61, 63, 65)[strongest]
                }
            }
        }
        val cloud = hs.map { it.cloudEff }.filter { !it.isNaN() }.average().let { if (it.isNaN()) 50.0 else it }
        if (codes.count { it == 45 || it == 48 } * 2 > hs.size) return 45
        return Sky.code(Sky.octas(cloud))
    }

    private fun intensity(code: Int): Int = when (code) {
        51, 56, 61, 66, 71, 80, 85 -> 0
        53, 63, 73, 81 -> 1
        55, 57, 65, 67, 75, 82, 86, 77 -> 2
        else -> 0
    }

    // ----------------------------------------------------------------- Ortssuche

    fun search(query: String): List<Place> {
        val url = "https://geocoding-api.open-meteo.com/v1/search?name=${Net.enc(query)}&count=12&language=de&format=json"
        val o = JSONObject(Net.get(url))
        val arr = o.optJSONArray("results") ?: return emptyList()
        val out = ArrayList<Place>()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            val parts = listOf(r.optString("admin1"), r.optString("country"))
                .filter { it.isNotBlank() && it != r.optString("name") }
            out.add(Place(r.optString("name"), parts.joinToString(", "), r.getDouble("latitude"), r.getDouble("longitude")))
        }
        return out
    }
}

/** Texte und Formatierung. */
object Fmt {
    fun temp(v: Double): String = if (v.isNaN()) "–" else "${v.roundToInt()}°"
    fun kmh(v: Double): String = if (v.isNaN()) "–" else "${v.roundToInt()}"
    fun mm(v: Double): String = when {
        v.isNaN() -> "–"
        v < 0.05 -> "0"
        v < 10 -> String.format(Locale.GERMANY, "%.1f", v)
        else -> "${v.roundToInt()}"
    }

    fun dir(deg: Double): String {
        if (deg.isNaN()) return ""
        val names = arrayOf("N", "NO", "O", "SO", "S", "SW", "W", "NW")
        return names[(((deg % 360) + 360 + 22.5) / 45).toInt() % 8]
    }

    fun code(code: Int): String = when (code) {
        0 -> "Klar"
        1 -> "Sonnig"
        2 -> "Wolkig"
        3 -> "Bedeckt"
        4 -> "Stark bewölkt"
        45 -> "Nebel"
        48 -> "Nebel mit Reif"
        51 -> "Leichter Nieselregen"
        53 -> "Mäßiger Nieselregen"
        55 -> "Starker Nieselregen"
        56, 57 -> "Gefrierender Niesel"
        61 -> "Leichter Regen"
        63 -> "Mäßiger Regen"
        65 -> "Starker Regen"
        66, 67 -> "Gefrierender Regen"
        68 -> "Schneeregen"
        71 -> "Leichter Schneefall"
        73 -> "Mäßiger Schneefall"
        75 -> "Starker Schneefall"
        77 -> "Schneegriesel"
        80 -> "Leichte Schauer"
        81 -> "Mäßige Schauer"
        82 -> "Starke Schauer"
        85 -> "Schneeschauer"
        86 -> "Kräftige Schneeschauer"
        95 -> "Gewitter"
        96, 99 -> "Gewitter mit Hagel"
        else -> "–"
    }

    /** "Klar" nachts statt "Überwiegend sonnig". */
    fun codeAt(code: Int, isDay: Boolean): String = if (!isDay) when (code) {
        0 -> "Klar"
        1 -> "Überwiegend klar"
        else -> code(code)
    } else code(code)
}


/**
 * Regen-Nowcast aus dem DWD-Radar (RADOLAN RV: Messung + 2 Stunden Vorhersage, 1 km, 5 Minuten),
 * abgerufen über Bright Sky (kostenlos, ohne Schlüssel). Nur im Radargebiet um Deutschland.
 */
object RadarNowcast {
    private val ISO = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    /** Liegt der Ort sicher im Erfassungsbereich des DWD-Radarverbunds? */
    fun covers(lat: Double, lon: Double) = lat in 47.0..55.3 && lon in 5.6..15.4

    fun fetchRaw(p: Place): String? {
        if (!covers(p.lat, p.lon)) return null
        return runCatching { Net.get(url(p), 10000) }.getOrNull()
    }

    /** Abfrage-Adresse (auch für den Datencheck). */
    fun url(p: Place): String {
        val now = System.currentTimeMillis() / 1000
        // 65 Minuten Vergangenheit: für "seit einer Stunde durchgehend Regen" (Regen statt Schauer)
        val from = synchronized(ISO) { ISO.format(java.util.Date((now - 65 * 60) * 1000)) }
        val to = synchronized(ISO) { ISO.format(java.util.Date((now + 135 * 60) * 1000)) }
        val url = "https://api.brightsky.dev/radar?lat=%.4f&lon=%.4f&distance=4000&format=plain".format(Locale.US, p.lat, p.lon) +
            "&date=${Net.enc(from)}&last_date=${Net.enc(to)}"
        return url
    }

    fun parseIso(s: String): Long? {
        val v = s.replace("Z", "+00:00")
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")) {
            val r = runCatching { SimpleDateFormat(pattern, Locale.US).parse(v)?.time }.getOrNull()
            if (r != null) return r / 1000
        }
        return null
    }

    /**
     * Landregen statt Schauer: In der letzten Stunde war es am Ort (3 × 3 km) fast durchgehend nass
     * (höchstens ein trockenes 5-Minuten-Bild) und im Mittel der Großteil des Ausschnitts (≥ 60 %).
     */
    fun steady(root: JSONObject, nowSec: Long): Boolean {
        val frames = root.optJSONArray("radar") ?: return false
        val pos = root.opt("latlon_position")
        var px = -1; var py = -1
        when (pos) {
            is JSONObject -> { px = pos.optDouble("x", -1.0).toInt(); py = pos.optDouble("y", -1.0).toInt() }
            is JSONArray -> if (pos.length() >= 2) { px = pos.optDouble(0, -1.0).toInt(); py = pos.optDouble(1, -1.0).toInt() }
        }
        var n = 0; var wet = 0; var cover = 0.0
        for (i in 0 until frames.length()) {
            val f = frames.optJSONObject(i) ?: continue
            val t = parseIso(f.optString("timestamp")) ?: continue
            if (t > nowSec || t < nowSec - 3600) continue
            val grid = f.optJSONArray("precipitation_5") ?: continue
            val h = grid.length(); if (h == 0) continue
            val w = grid.optJSONArray(0)?.length() ?: 0
            val cx = if (px in 0 until w) px else w / 2
            val cy = if (py in 0 until h) py else h / 2
            var sum = 0.0; var cnt = 0; var all = 0; var wetPx = 0
            for (y in 0 until h) {
                val row = grid.optJSONArray(y) ?: continue
                for (x in 0 until row.length()) {
                    val v = row.optDouble(x, 0.0)
                    all++; if (v > 0) wetPx++
                    if (kotlin.math.abs(x - cx) <= 1 && kotlin.math.abs(y - cy) <= 1) { sum += v; cnt++ }
                }
            }
            if (cnt == 0 || all == 0) continue
            n++
            if (sum / cnt >= 1.0) wet++                       // ≥ 0,01 mm je 5 Min. (≈ 0,1 mm/h)
            cover += wetPx.toDouble() / all
        }
        return n >= 9 && wet >= n - 1 && cover / n >= 0.6
    }

    /**
     * Niesel-Verdacht: In den letzten 30 Minuten gemessen nur schwache Echos an der Messgrenze
     * (höchstens 0,03 mm je 5 Min. in einem Pixel), aber überhaupt welche im Ausschnitt.
     */
    fun weakEcho(root: JSONObject, nowSec: Long): Boolean {
        val frames = root.optJSONArray("radar") ?: return false
        var any = false
        var mx = 0.0
        for (i in 0 until frames.length()) {
            val f = frames.optJSONObject(i) ?: continue
            val t = parseIso(f.optString("timestamp")) ?: continue
            if (t > nowSec || t < nowSec - 1800) continue
            val grid = f.optJSONArray("precipitation_5") ?: continue
            for (y in 0 until grid.length()) {
                val row = grid.optJSONArray(y) ?: continue
                for (x in 0 until row.length()) {
                    val v = row.optDouble(x, 0.0)
                    if (v > 0) any = true
                    if (v > mx) mx = v
                }
            }
        }
        return any && mx <= 3
    }

    /**
     * 15-Minuten-Mengen (Start der Viertelstunde, mm) – Mittel über 3 × 3 km um den Ort,
     * oder mit [nearMax] die stärkste Zelle im Umkreis von 3 km (Schauer in der Nähe).
     */
    fun slots(root: JSONObject, nearMax: Boolean = false): List<Pair<Long, Double>> {
        val frames = root.optJSONArray("radar") ?: return emptyList()
        val pos = root.opt("latlon_position")
        var px = -1; var py = -1
        when (pos) {
            is JSONObject -> { px = pos.optDouble("x", -1.0).toInt(); py = pos.optDouble("y", -1.0).toInt() }
            is JSONArray -> if (pos.length() >= 2) { px = pos.optDouble(0, -1.0).toInt(); py = pos.optDouble(1, -1.0).toInt() }
        }
        val sums = java.util.TreeMap<Long, Pair<Double, Int>>()
        for (i in 0 until frames.length()) {
            val f = frames.optJSONObject(i) ?: continue
            val t = parseIso(f.optString("timestamp")) ?: continue
            val grid = f.optJSONArray("precipitation_5") ?: continue
            val h = grid.length()
            if (h == 0) continue
            val w = grid.optJSONArray(0)?.length() ?: 0
            val cx = if (px in 0 until w) px else w / 2
            val cy = if (py in 0 until h) py else h / 2
            var sum = 0.0; var n = 0; var mx = 0.0
            val rad = if (nearMax) 3 else 1
            for (y in cy - rad..cy + rad) for (x in cx - rad..cx + rad) {
                if (nearMax && (x - cx) * (x - cx) + (y - cy) * (y - cy) > rad * rad) continue
                val row = grid.optJSONArray(y) ?: continue
                if (x < 0 || x >= row.length() || row.isNull(x)) continue
                val v = row.optDouble(x, 0.0)
                sum += v; n++; if (v > mx) mx = v
            }
            if (n == 0) continue
            val mm = (if (nearMax) mx else sum / n) * 0.01   // 0,01 mm je 5 Minuten
            val start = t - 300                         // Wert gilt für die 5 Minuten bis t
            val slot = start - Math.floorMod(start, 900L)
            val prev = sums[slot] ?: (0.0 to 0)
            sums[slot] = (prev.first + mm) to (prev.second + 1)
        }
        // nur vollständige Viertelstunden
        return sums.filter { it.value.second >= 3 }.map { it.key to it.value.first }
    }
}
