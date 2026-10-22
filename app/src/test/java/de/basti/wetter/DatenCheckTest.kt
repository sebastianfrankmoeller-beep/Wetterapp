package de.basti.wetter

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs

/**
 * Datencheck: rechnet die Auswertung der App (ohne Oberfläche) durch.
 * 1. Feste Wetterlagen (Niesel, Schauer, Störecho, Sonne, Messwert-Abweichung) mit bekanntem Sollergebnis.
 * 2. Echte Live-Daten für mehrere Orte: innere Widerspruchsfreiheit (Jetzt-Karte, Lauf-Check, Regenkarte,
 *    Stundenstreifen) und plausible Werte. Ergebnis als Bericht in build/datencheck.md.
 */
class DatenCheckTest {

    // ================================================================ Bausteine

    @Test fun achtelUndStufen() {
        assertEquals(0, Sky.octas(0.0))
        assertEquals(0, Sky.octas(2.0))
        assertEquals(4, Sky.octas(50.0))
        assertEquals(7, Sky.octas(96.0))
        assertEquals(8, Sky.octas(100.0))
        assertEquals(1, Sky.stage(Sky.octas(20.0)))      // sonnig
        assertEquals(2, Sky.stage(Sky.octas(50.0)))      // wolkig
        assertEquals(3, Sky.stage(Sky.octas(80.0)))      // stark bewölkt
        assertEquals(4, Sky.stage(Sky.octas(100.0)))     // bedeckt
        // Nur hohe Schleierwolken zählen halb
        assertEquals(50.0, Sky.effective(100.0, 0.0, 0.0, 100.0), 0.5)
        assertEquals(100.0, Sky.effective(100.0, 100.0, 0.0, 0.0), 0.5)
    }

    @Test fun sonnenstand() {
        // Flensburg, Sommersonnenwende, wahrer Mittag ≈ 11:24 UTC: 90 − 54,8 + 23,44 ≈ 58,6°
        val june = utc("2026-06-21T11:24:00Z")
        assertEquals(58.6, Sun.elevation(54.8, 9.44, june), 1.0)
        // 22. Oktober, wahrer Mittag ≈ 11:07 UTC: Deklination ≈ −11,3° → ≈ 23,9°
        val oct = utc("2026-10-22T11:07:00Z")
        assertEquals(23.9, Sun.elevation(54.8, 9.44, oct), 1.0)
        // Nachts unter dem Horizont
        assertTrue(Sun.elevation(54.8, 9.44, utc("2026-10-22T23:00:00Z")) < 0)
        // Einstrahlung wolkenlos bei 24° etwa 380–480 W/m²
        assertTrue(Sun.clearSky(24.0) in 380.0..480.0)
    }

    // ================================================================ Feste Wetterlagen

    private val place = Place("Teststadt", "", 54.81, 9.47)

    @Test fun nieselWirdErkannt() {
        // Radar: nur schwache Echos an der Messgrenze, Vorhersage trocken; Modell: 0,2 mm/h
        val f = parse(Szenario(modelMm15 = 0.05, radarPast = 1, radarPastShare = 0.4, radarFuture = 0))
        assertTrue("Niesel-Lage erkannt", f.drizzle)
        assertTrue("Kurzfrist-Verlauf jetzt nass", nowMmh(f) >= 0.1)
        val c = f.current()!!
        assertTrue("Jetzt-Karte zeigt Niederschlag (Code ${c.code}, ${c.text})", c.code in 51..67)
        val r = RunCheck.evaluate(f, 0, null)!!
        assertEquals("Lauf-Check gelb bei Niesel", RunCheck.YELLOW, r.level)
        assertTrue("Niesel-Hinweis", r.hints.any { it.second.startsWith("Niesel") })
        val (head, _) = nowcastSummary(f, f.nowcast.take(13))
        assertTrue("Regenkarte nennt Niesel: $head", head.startsWith("Niesel"))
    }

    @Test fun kraeftigerSchauerRadarEntscheidet() {
        // Radar: kräftiges Echo jetzt und noch 30 Min., danach trocken; Modell trocken → Radar gilt
        val f = parse(Szenario(modelMm15 = 0.0, radarPast = 25, radarPastShare = 1.0, radarFuture = 25, futureMin = 30))
        assertFalse("keine Niesel-Lage bei kräftigem Echo", f.drizzle)
        assertTrue("Regen jetzt laut Radar", nowMmh(f) >= 1.0)
        val (head, _) = nowcastSummary(f, f.nowcast.take(13))
        assertTrue("Regenkarte: Regen hört auf – $head", head.startsWith("Regen hört"))
    }

    @Test fun stoerechoOhneModellregenErzeugtKeinenNiesel() {
        // Schwache Echos (z. B. Störecho über dem Meer), Modell trocken, keine Station mit Regen
        val f = parse(Szenario(modelMm15 = 0.0, radarPast = 1, radarPastShare = 0.3, radarFuture = 0))
        assertFalse("keine Niesel-Lage ohne Modellregen", f.drizzle)
        val r = RunCheck.evaluate(f, 0, null)!!
        assertEquals("Lauf-Check bleibt grün", RunCheck.GREEN, r.level)
        val (head, _) = nowcastSummary(f, f.nowcast.take(13))
        assertFalse("kein 'Niesel'/'Regen hört' bei Störecho: $head", head.startsWith("Niesel") || head.startsWith("Regen"))
    }

    @Test fun stationMitRegenBestaetigtNiesel() {
        // Radar sieht gar nichts, Modell rechnet mit Niesel, Station in 32 km meldet Regen
        val f = parse(Szenario(modelMm15 = 0.05, radarPast = 0, radarFuture = 0,
            obs = Obs(condition = "rain", conditionKm = 32.0)))
        assertTrue("Niesel-Lage durch Stationsmeldung", f.drizzle)
    }

    @Test fun stationsregenAllein70kmReichtNicht() {
        val f = parse(Szenario(modelMm15 = 0.05, radarPast = 0, radarFuture = 0,
            obs = Obs(condition = "rain", conditionKm = 55.0)))
        assertFalse("Meldung aus 55 km zählt nicht", f.drizzle)
    }

    @Test fun sonneGemessenSchlaegtModellwolken() {
        // Modell: 6/8 tiefe Wolken; Station: 4/8, 30 von 30 Min. Sonne
        val f = parse(Szenario(modelMm15 = 0.0, radarPast = 0, radarFuture = 0, modelLow = 60.0, modelMid = 30.0,
            obs = Obs(cloud = 50.0, cloudKm = 32.0, sun30 = 30.0)))
        val c = f.current()!!
        assertEquals("Sonnig, teils wolkig", c.text)
        assertEquals(1, c.code)
        assertTrue(c.cloudSource, c.cloudSource.startsWith("gemessen"))
    }

    @Test fun messwertVerschiebtStundenUndLaufCheck() {
        // Modell 8°, Station 12° → "Jetzt", Stundenstreifen und Lauf-Check passen zusammen
        val f = parse(Szenario(modelMm15 = 0.0, radarPast = 0, radarFuture = 0, modelTemp = 8.0,
            obs = Obs(temp = 12.0)))
        val c = f.current()!!
        assertEquals(12.0, c.temp, 0.01)
        assertEquals(12.0, f.hours.first().temp, 0.01)
        val r = RunCheck.evaluate(f, 0, null)!!
        assertEquals("Lauf-Check jetzt", c.feels, r.slots.first().feels, 1.0)
        // nach 2 Stunden wieder Modell
        val later = f.hours.first { it.t >= System.currentTimeMillis() / 1000 + 2 * 3600 }
        assertEquals(8.0, later.temp, 0.6)
    }

    @Test fun trockenUndKlar() {
        val f = parse(Szenario(modelMm15 = 0.0, radarPast = 0, radarFuture = 0, modelLow = 0.0, modelMid = 0.0, modelHigh = 0.0, modelTotal = 0.0))
        val (head, _) = nowcastSummary(f, f.nowcast.take(13))
        assertTrue(head, head.startsWith("Kein Regen"))
        assertEquals(RunCheck.GREEN, RunCheck.evaluate(f, 0, null)!!.level)
        assertEquals(0, f.current()!!.code)
    }

    // ================================================================ Live-Daten

    private val orte = listOf(
        Place("Flensburg", "Mürwik", 54.80, 9.47),
        Place("List (Sylt)", "", 55.02, 8.43),
        Place("Hamburg", "", 53.55, 9.99),
        Place("Friedrichroda", "Thüringen", 50.86, 10.56),
        Place("München", "", 48.14, 11.58),
        Place("Oberstdorf", "Allgäu", 47.41, 10.28),
        Place("Wien", "außerhalb DWD-Radar", 48.21, 16.37),
        Place("London", "außerhalb Deutschland", 51.51, -0.13)
    )

    @Test fun liveDaten() {
        val report = StringBuilder("# Datencheck ${ts(System.currentTimeMillis() / 1000)} UTC\n\n")
        val problems = ArrayList<String>()
        var reached = 0
        radarInfo.clear()
        for (p in orte) {
            val raw = runCatching { WeatherApi.fetchRaw(p) }.getOrElse {
                report.append("## ${p.name}\nnicht erreichbar: ${it.message}\n\n"); null
            } ?: continue
            reached++
            // Radar-Abruf getrennt messen (Dauer, Größe, Fehler)
            if (RadarNowcast.covers(p.lat, p.lon)) {
                val t0 = System.currentTimeMillis()
                val res = runCatching { Net.get(RadarNowcast.url(p), 20000) }
                val ms = System.currentTimeMillis() - t0
                val info = res.fold({ body ->
                    val slots = runCatching { RadarNowcast.slots(JSONObject(body)).size }.getOrElse { -1 }
                    "${body.length} Zeichen, $slots Viertelstunden"
                }, { e -> "FEHLER ${e.javaClass.simpleName}: ${e.message}" })
                radarInfo[p.name] = "Radar-Abruf: $ms ms, $info"
                if (ms > 10000) problems.add("${p.name}: Radar-Abruf dauert $ms ms (App wartet höchstens 10 s)")
            }
            val f = runCatching { WeatherApi.parse(p, raw) }.getOrElse {
                problems.add("${p.name}: Auswertung fehlgeschlagen – ${it}"); null
            } ?: continue
            check(p, f, report, problems)
        }
        report.append("\n## Ergebnis\n")
        if (problems.isEmpty()) report.append("Keine Widersprüche gefunden.\n")
        else problems.forEach { report.append("- ❌ ").append(it).append('\n') }
        write(report.toString())
        println(report)
        org.junit.Assume.assumeTrue("kein Ort erreichbar (Netz)", reached > 0)
        assertTrue("Widersprüche:\n" + problems.joinToString("\n"), problems.isEmpty())
    }

    private val radarInfo = HashMap<String, String>()

    private fun check(p: Place, f: Forecast, out: StringBuilder, problems: MutableList<String>) {
        val nowSec = System.currentTimeMillis() / 1000
        fun bad(msg: String) = problems.add("${p.name}: $msg")
        val c = f.current()
        val r = RunCheck.evaluate(f, 0, null)
        val nc = f.nowcast.filter { it.first + 900 > nowSec }.take(13)
        val summary = if (nc.size >= 4) nowcastSummary(f, nc) else null
        val o = f.obs?.takeIf { it.fresh(nowSec) }

        out.append("## ${p.name}${if (p.region.isNotEmpty()) " (${p.region})" else ""}\n")
        if (c == null) { bad("keine Jetzt-Werte"); out.append("keine Jetzt-Werte\n\n"); return }
        out.append("- Jetzt: ${Fmt.temp(c.temp)} (gefühlt ${Fmt.temp(c.feels)}), ${c.text} [Code ${c.code}], ")
            .append("Wind ${Fmt.kmh(c.wind)}/${Fmt.kmh(c.gust)} km/h\n")
        out.append("- Bewölkung: ${Sky.label(c.cloud)} · ${c.cloudSource}${c.sunInfo?.let { " · $it" } ?: ""}\n")
        out.append("- Quelle: ${c.source}\n")
        out.append("- Modell-Schichten: ${c.layers ?: "–"} · Modell-Code Stunde: ${f.hours.first().code}\n")
        out.append("- Kurzfrist: ${nc.joinToString(" ") { "%.2f".format(Locale.US, it.second * 4) }} mm/h" +
            " · Radar bis ${if (f.nowcastRadarUntil > 0) f.hhmm(f.nowcastRadarUntil) else "–"} · Niesel-Lage: ${f.drizzle}\n")
        radarInfo[p.name]?.let { out.append("- $it\n") }
        if (RadarNowcast.covers(p.lat, p.lon) && f.nowcastRadarUntil == 0L) bad("kein Radar-Nowcast in der Auswertung")
        summary?.let { out.append("- Regenkarte: ${it.first}${if (it.second.isNotEmpty()) " · ${it.second}" else ""}\n") }
        if (r != null) {
            out.append("- Lauf-Check: ${r.headline} – ${r.reason}\n")
            out.append("  - Verlauf: ${r.slots.joinToString(" ") { "${listOf("G", "Y", "R")[it.level]}:${Fmt.temp(it.feels)}/${"%.1f".format(Locale.US, it.rain)}" }}\n")
            r.hints.forEach { out.append("  - Hinweis: ${it.second}\n") }
        }
        out.append('\n')

        // --- Plausibilität
        if (f.hours.size < 48) bad("nur ${f.hours.size} Stunden")
        if (f.days.size < 10) bad("nur ${f.days.size} Tage")
        if (c.temp.isNaN() || c.temp !in -45.0..48.0) bad("Temperatur unplausibel: ${c.temp}")
        if (!c.cloud.isNaN() && c.cloud !in 0.0..100.0) bad("Bewölkung außerhalb 0–100: ${c.cloud}")
        if (o != null) {
            if (!o.temp.isNaN() && o.distKm > Observation.NEAR_KM) bad("Temperatur von Station in ${o.distKm} km")
            if (!o.cloud.isNaN() && o.cloudDistKm > Observation.FAR_KM) bad("Bewölkung von Station in ${o.cloudDistKm} km")
        }
        val inDwd = RadarNowcast.covers(p.lat, p.lon)
        if (!inDwd && f.obs != null) bad("Stationsdaten außerhalb des DWD-Gebiets")

        // --- Widerspruchsfreiheit
        val h0 = f.hours.first()
        if (o != null && !o.temp.isNaN() && abs(h0.temp - c.temp) > 0.05)
            bad("Stundenstreifen 'Jetzt' ${h0.temp} ≠ Jetzt-Karte ${c.temp}")
        if (r != null && !c.feels.isNaN() && abs(r.slots.first().feels - c.feels) > 2.0)
            bad("Lauf-Check jetzt gefühlt ${r.slots.first().feels} ≠ Jetzt-Karte ${c.feels}")
        val slotNow = f.nowcast.firstOrNull { it.first <= nowSec && nowSec < it.first + 900 }
        val wetNow = (slotNow?.second ?: 0.0) * 4 >= 0.1
        if (wetNow && c.code < 45) bad("Kurzfrist nass (${slotNow!!.second * 4} mm/h), Jetzt-Karte '${c.text}'")
        if (summary != null) {
            val anyWet = nc.any { it.second * 4 >= 0.1 }
            if (anyWet && summary.first.startsWith("Kein")) bad("Regenkarte '${summary.first}' trotz Regen im Verlauf")
            if (!anyWet && (summary.first.startsWith("Regen") || summary.first.startsWith("Niesel")))
                bad("Regenkarte '${summary.first}' ohne Regen im Verlauf")
        }
        if (r != null) {
            val runWet = r.slots.any { it.rain >= 0.3 }
            if (runWet && summary != null && summary.first.startsWith("Kein")) bad("Lauf-Check nass, Regenkarte '${summary.first}'")
            if (r.level == RunCheck.GREEN && r.slots.any { it.rain >= 0.3 }) bad("Lauf-Check grün trotz Regen ≥ 0,3 mm/h")
        }
        // Stunden, die der Kurzfrist-Verlauf ganz abdeckt, müssen dessen Menge zeigen
        if (f.nowcastRadarUntil > 0) {
            val m = f.nowcast.toMap()
            val slot0 = nowSec - Math.floorMod(nowSec, 900L)
            for (h in f.hours.take(4)) {
                val keys = (maxOf(h.t, slot0) until h.t + 3600 step 900).toList()
                if (keys.isEmpty() || keys.first() >= f.nowcastRadarUntil || keys.any { it !in m }) continue
                val sum = keys.sumOf { m[it]!! }
                if (abs(sum - h.precip) > 0.01) bad("Stunde ${f.hhmm(h.t)}: Streifen ${h.precip} mm ≠ Kurzfrist ${"%.2f".format(Locale.US, sum)} mm")
            }
        }
    }

    // ================================================================ Testdaten bauen

    private class Obs(
        val temp: Double = Double.NaN,
        val condition: String? = null, val conditionKm: Double = 3.0,
        val cloud: Double = Double.NaN, val cloudKm: Double = 3.0,
        val sun30: Double = Double.NaN
    )

    private class Szenario(
        val modelMm15: Double,          // Modell: mm je 15 Min. für die nächsten 3 Std.
        val radarPast: Int,             // Radarwert (0,01 mm/5 Min.) in den letzten 20 Min.
        val radarPastShare: Double = 1.0, // Anteil der Pixel mit Echo
        val radarFuture: Int,           // Radarwert in der Vorhersage …
        val futureMin: Int = 120,       // … für so viele Minuten ab jetzt, danach trocken
        val modelTemp: Double = 10.0,
        val modelLow: Double = 80.0, val modelMid: Double = 20.0, val modelHigh: Double = 0.0,
        val modelTotal: Double = 85.0,
        val obs: Obs? = null
    )

    private fun parse(s: Szenario): Forecast = WeatherApi.parse(place, rawFor(s))

    private fun nowMmh(f: Forecast): Double {
        val n = System.currentTimeMillis() / 1000
        return (f.nowcast.firstOrNull { it.first <= n && n < it.first + 900 }?.second ?: 0.0) * 4
    }

    private fun rawFor(s: Szenario): String {
        val nowSec = System.currentTimeMillis() / 1000
        val hour0 = nowSec - nowSec % 3600
        val times = (-24..72).map { hour0 + it * 3600L }
        val rainHour = (s.modelMm15 * 4)
        fun arr(f: (Long) -> Any) = JSONArray(times.map(f))
        val wet = { t: Long -> t >= hour0 && t < hour0 + 4 * 3600 }
        val hourly = JSONObject()
            .put("time", arr { it })
            .put("temperature_2m", arr { s.modelTemp })
            .put("apparent_temperature", arr { s.modelTemp - 3 })
            .put("relative_humidity_2m", arr { 85 })
            .put("precipitation", arr { if (wet(it)) rainHour else 0.0 })
            .put("snowfall", arr { 0.0 })
            .put("weather_code", arr { if (wet(it) && rainHour >= 0.1) 51 else 3 })
            .put("cloud_cover", arr { s.modelTotal })
            .put("cloud_cover_low", arr { s.modelLow })
            .put("cloud_cover_mid", arr { s.modelMid })
            .put("cloud_cover_high", arr { s.modelHigh })
            .put("wind_speed_10m", arr { 15 }).put("wind_direction_10m", arr { 200 }).put("wind_gusts_10m", arr { 25 })
            .put("is_day", arr { 1 }).put("pressure_msl", arr { 1010 }).put("cape", arr { 0 })
        val ec = JSONObject().put("utc_offset_seconds", 7200).put("elevation", 10)
            .put("hourly", JSONObject(hourly.toString()).put("precipitation_probability", arr { 50 }).put("lightning_density", arr { 0 }))
            .put("daily", JSONObject().put("time", JSONArray()).put("sunrise", JSONArray()).put("sunset", JSONArray()))
        val slot0 = nowSec - nowSec % 900
        val mTimes = (0 until 13).map { slot0 + it * 900L }
        val d2 = JSONObject().put("utc_offset_seconds", 7200)
            .put("hourly", JSONObject(hourly.toString()).put("lightning_potential", arr { 0 }))
            .put("minutely_15", JSONObject()
                .put("time", JSONArray(mTimes))
                .put("precipitation", JSONArray(mTimes.map { s.modelMm15 }))
                .put("snowfall", JSONArray(mTimes.map { 0.0 })))

        // Radar: 9×9 Pixel, Ort in der Mitte, alle 5 Minuten von −20 bis +120 Min.
        val frames = JSONArray()
        val f0 = nowSec - nowSec % 300
        for (k in -4..24) {
            val t = f0 + k * 300L
            val v = if (t <= nowSec) s.radarPast else if (t <= nowSec + s.futureMin * 60) s.radarFuture else 0
            val share = if (t <= nowSec) s.radarPastShare else 1.0
            var idx = 0
            val grid = JSONArray((0 until 9).map { _ ->
                JSONArray((0 until 9).map { _ -> idx++; if (v > 0 && (idx * 0.618034 % 1.0) < share) v else 0 })
            })
            frames.put(JSONObject().put("timestamp", ts(t) + "+00:00").put("precipitation_5", grid))
        }
        val radar = JSONObject().put("radar", frames).put("latlon_position", JSONObject().put("x", 4).put("y", 4))

        val root = JSONObject().put("ec", ec).put("d2", d2).put("uv", JSONObject.NULL).put("radar", radar)
            .put("at", System.currentTimeMillis())
        s.obs?.let { o ->
            val w = JSONObject().put("source_id", 1).put("timestamp", ts(nowSec - 600) + "+00:00")
                .put("temperature", if (o.temp.isNaN()) JSONObject.NULL else o.temp)
                .put("wind_speed_10", 15).put("wind_direction_10", 200).put("wind_gust_speed_10", 25)
                .put("condition", o.condition ?: JSONObject.NULL)
                .put("cloud_cover", if (o.cloud.isNaN()) JSONObject.NULL else o.cloud)
                .put("sunshine_30", if (o.sun30.isNaN()) JSONObject.NULL else o.sun30)
            val fb = JSONObject().put("condition", 2).put("cloud_cover", 3).put("sunshine_30", 3)
            w.put("fallback_source_ids", fb)
            val sources = JSONArray()
                .put(JSONObject().put("id", 1).put("station_name", "TESTSTATION").put("distance", 3000))
                .put(JSONObject().put("id", 2).put("station_name", "REGENSTATION").put("distance", o.conditionKm * 1000))
                .put(JSONObject().put("id", 3).put("station_name", "WOLKENSTATION").put("distance", o.cloudKm * 1000))
            root.put("obs", JSONObject().put("weather", w).put("sources", sources))
        }
        return root.toString()
    }

    // ================================================================ Hilfen

    private fun ts(sec: Long): String = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.format(Date(sec * 1000))

    private fun utc(s: String): Long = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        .apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(s)!!.time / 1000

    private fun write(text: String) {
        val dir = File(System.getProperty("user.dir") ?: ".", "build")
        dir.mkdirs()
        File(dir, "datencheck.md").writeText(text)
    }
}
