package de.basti.wetter

import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Lauf-Check: Bewertung der nächsten 2 Stunden in 15-Minuten-Abschnitten,
 * Ampel, Hinweise, Kleidungsempfehlung nach Läufertyp und ggf. ein besseres Zeitfenster.
 */
object RunCheck {

    const val TYPE_WARM = 0
    const val TYPE_NORMAL = 1
    const val TYPE_COLD = 2
    val TYPE_NAMES = arrayOf("Läuft warm", "Normal", "Friert schnell")
    /** Verschiebung der Kleidungsgrenzen in °C je Läufertyp. */
    private val TYPE_SHIFT = doubleArrayOf(0.0, 4.0, 7.0)

    const val GREEN = 0
    const val YELLOW = 1
    const val RED = 2

    class Slot(
        val t: Long,
        val rain: Double,      // mm/h
        val snowy: Boolean,
        val temp: Double,
        val feels: Double,
        val wind: Double,
        val gust: Double,
        val windDir: Double,
        val thunder: Int,
        val isDay: Boolean,
        val level: Int,
        val reason: String?    // Hauptgrund für Gelb/Rot
    )

    class Result(
        val level: Int,
        val headline: String,
        val reason: String,
        val clothing: String,
        val clothingNote: String?,
        val slots: List<Slot>,
        val hints: List<Pair<GlyphView.Kind, String>>,
        val better: String?
    )

    /** Linear interpoliert einen Stundenwert zum Zeitpunkt t. */
    private fun interp(hs: List<Hour>, t: Long, sel: (Hour) -> Double): Double {
        if (hs.isEmpty()) return Double.NaN
        val i = hs.indexOfLast { it.t <= t }
        if (i < 0) return sel(hs.first())
        if (i >= hs.size - 1) return sel(hs[i])
        val a = hs[i]; val b = hs[i + 1]
        val va = sel(a); val vb = sel(b)
        if (va.isNaN()) return vb
        if (vb.isNaN()) return va
        val k = (t - a.t).toDouble() / (b.t - a.t)
        return va + (vb - va) * k
    }

    private fun hourAt(hs: List<Hour>, t: Long): Hour? = hs.lastOrNull { it.t <= t } ?: hs.firstOrNull()

    /** Bewertung eines einzelnen Zeitpunkts. */
    private fun rate(rain: Double, snowy: Boolean, temp: Double, feels: Double, gust: Double, thunder: Int, code: Int): Pair<Int, String?> {
        if (thunder >= 2 || code >= 95) return RED to "Gewitter"
        if (code in 56..57 || code in 66..67) return RED to "Glatteis"
        if (!gust.isNaN() && gust >= 65) return RED to "Sturmböen"
        if (!feels.isNaN() && feels >= 32) return RED to "Hitze"
        if (rain >= 4.0) return RED to if (snowy) "starker Schneefall" else "starker Regen"
        if (!temp.isNaN() && temp <= 1.0 && rain >= 0.1) return YELLOW to "Glätte möglich"
        if (rain >= 0.3) return YELLOW to if (snowy) "Schnee" else if (rain >= 2.5) "mäßiger Regen" else "leichter Regen"
        if (thunder == 1) return YELLOW to "Gewitterrisiko"
        if (!gust.isNaN() && gust >= 45) return YELLOW to "böiger Wind"
        if (!feels.isNaN() && feels >= 26) return YELLOW to "warm"
        if (!feels.isNaN() && feels <= -8) return YELLOW to "sehr kalt"
        return GREEN to null
    }

    fun evaluate(f: Forecast, type: Int, warnings: List<Warning>?): Result? {
        val hs = f.hours
        if (hs.size < 3) return null
        val now = System.currentTimeMillis() / 1000
        val start = now - now % 900
        val slotsN = 8
        val nc = f.nowcast.associate { it.first to it.second }

        val slots = (0 until slotsN).map { k ->
            val t = start + k * 900L
            val h = hourAt(hs, t)
            // Regen: 15-Minuten-Wert (ICON-D2) bevorzugt, sonst Stundenwert
            val ncKey = nc.keys.lastOrNull { it <= t + 450 && it > t - 900 }
            val rain = if (ncKey != null) nc[ncKey]!! * 4 else (h?.precip ?: 0.0)
            val snowy = if (ncKey != null) f.nowcastSnow else (h != null && h.snow > 0.05 && h.snow * 0.7 >= h.precip * 0.5)
            val temp = interp(hs, t) { it.temp }
            val feels = interp(hs, t) { it.feels }
            val gust = interp(hs, t) { it.gust }
            val (lvl, why) = rate(rain, snowy, temp, feels, gust, h?.thunder ?: 0, h?.code ?: 0)
            Slot(t, rain, snowy, temp, feels, interp(hs, t) { it.wind }, gust, h?.windDir ?: Double.NaN,
                h?.thunder ?: 0, h?.isDay ?: true, lvl, why)
        }

        // Amtliche Warnungen, die in die 2 Stunden fallen
        val end = start + slotsN * 900L
        val relevant = warnings.orEmpty().filter { w -> w.onset < end && (w.expires == 0L || w.expires > start) && w.level >= 2 }
        val warnRed = relevant.firstOrNull { it.level >= 3 || it.isThunder }

        var level = slots.maxOf { it.level }
        if (warnRed != null) level = RED
        else if (relevant.isNotEmpty()) level = max(level, YELLOW)

        // Hauptgrund und Überschrift
        val worst = slots.filter { it.level == level && it.reason != null }
        val first = worst.firstOrNull()
        fun minutesFrom(t: Long) = ((t - now) / 60).toInt().coerceAtLeast(0)
        val reason: String = when {
            warnRed != null -> "Amtliche Warnung: " + titleCase(warnRed.event)
            first == null && relevant.isNotEmpty() -> "Warnung: " + titleCase(relevant.first().event)
            first == null -> "Trocken und angenehm"
            first === slots.first() -> {
                val endIdx = slots.indexOfFirst { it.level < level }
                if (endIdx < 0) "${cap(first.reason!!)} die ganzen 2 Stunden"
                else "${cap(first.reason!!)} bis ca. ${f.hhmm(slots[endIdx].t)}"
            }
            else -> "${cap(first.reason!!)} ab ca. ${f.hhmm(first.t)} (in ${minutesFrom(first.t)} Min.)"
        }
        val headline = when (level) {
            GREEN -> "Gute Bedingungen"
            YELLOW -> "Läuft, aber …"
            else -> "Lieber verschieben"
        }

        // Kleidung: richtet sich nach der wärmeren Phase
        val feelsList = slots.map { it.feels }.filter { !it.isNaN() }
        val warm = feelsList.maxOrNull() ?: Double.NaN
        val cold = feelsList.minOrNull() ?: Double.NaN
        val maxGust = slots.map { it.gust }.filter { !it.isNaN() }.maxOrNull() ?: 0.0
        val anyRain = slots.any { it.rain >= 0.3 }
        val clothing = clothing(warm, type, anyRain, maxGust)
        val clothingNote = if (!warm.isNaN() && !cold.isNaN() && warm - cold >= 3)
            (if (slots.first().feels < warm) "Zu Beginn kühler – lieber kurz frösteln" else "Wird kühler – ggf. Schicht zum Überziehen")
        else null

        // Hinweise
        val hints = ArrayList<Pair<GlyphView.Kind, String>>()
        val wind = slots.map { it.wind }.filter { !it.isNaN() }.average().let { if (it.isNaN()) 0.0 else it }
        val dir = slots[slotsN / 2].windDir
        if (wind >= 15 && !dir.isNaN()) {
            hints.add(GlyphView.Kind.WIND to "Wind ${wind.roundToInt()} km/h aus ${Fmt.dir(dir)} – starte gegen den Wind, zurück mit Rückenwind")
        }
        if (slots.any { it.thunder >= 1 } && level < RED) hints.add(GlyphView.Kind.BOLT to "Gewitterrisiko – Himmel im Blick behalten")
        if (slots.any { !it.temp.isNaN() && it.temp <= 1.0 } && (slots.any { it.rain >= 0.05 } || hs.take(6).any { it.precip >= 0.1 }))
            hints.add(GlyphView.Kind.SNOW to "Um 0 °C und nass – rutschige Wege möglich")
        // Dunkelheit
        val today = f.days.firstOrNull { now in it.start until it.start + 86400 } ?: f.days.firstOrNull()
        if (today != null && today.sunset > 0) {
            val sunset = today.sunset
            val sunrise = today.sunrise
            if (sunset in now until end) hints.add(GlyphView.Kind.MOON to "Ab ${f.hhmm(sunset)} dunkel – Stirnlampe/Reflektoren")
            else if (now < sunrise && sunrise < end) hints.add(GlyphView.Kind.MOON to "Hell erst ab ${f.hhmm(sunrise)} – Stirnlampe/Reflektoren")
            else if (now >= sunset || now < sunrise) hints.add(GlyphView.Kind.MOON to "Dunkel – Stirnlampe/Reflektoren")
        }
        if (!warm.isNaN() && warm >= 20) hints.add(GlyphView.Kind.SUN to "Warm – vorher trinken, helle Kleidung, Kappe")
        val uvMax = f.uv.filterKeys { it in (start - 3600) until end }.values.maxOrNull()
        if (uvMax != null && uvMax >= 5) hints.add(GlyphView.Kind.SUN to "UV-Index ${uvMax.roundToInt()} – Sonnenschutz")
        for (w in relevant.take(2)) if (w !== warnRed) hints.add(GlyphView.Kind.WARNING to "Warnung: ${titleCase(w.event)} (${w.timeRange()})")

        val better = if (level == GREEN) null else betterWindow(f, now, level)

        return Result(level, headline, reason, clothing, clothingNote, slots, hints, better)
    }

    /** Sucht in den nächsten 12 Stunden ein 2-Stunden-Fenster mit besserer Bewertung (tagsüber bevorzugt). */
    private fun betterWindow(f: Forecast, now: Long, current: Int): String? {
        val hs = f.hours
        var best: Pair<Long, Int>? = null
        var s = now - now % 1800 + 1800
        while (s < now + 12 * 3600) {
            var lvl = GREEN
            var t = s
            while (t < s + 7200) {
                val h = hourAt(hs, t) ?: break
                lvl = max(lvl, rate(h.precip, h.snow > 0.05, h.temp, h.feels, h.gust, h.thunder, h.code).first)
                t += 1800
            }
            val night = hourAt(hs, s)?.isDay == false
            if (lvl < current && !night) {
                if (best == null || lvl < best.second) best = s to lvl
                if (lvl == GREEN) break
            }
            s += 1800
        }
        val b = best ?: return null
        val kind = if (b.second == GREEN) "gute Bedingungen" else "etwas besser"
        val day = if (f.cal(b.first).get(java.util.Calendar.DAY_OF_YEAR) != f.cal(now).get(java.util.Calendar.DAY_OF_YEAR)) "morgen " else ""
        return "Besser: $day${f.hhmm(b.first)}–${f.hhmm(b.first + 7200)} · $kind"
    }

    /**
     * Kleidung nach gefühlter Temperatur. Grundlage: "läuft warm";
     * die anderen Typen verschieben alle Grenzen nach oben.
     */
    fun clothing(feels: Double, type: Int, rain: Boolean, gust: Double): String {
        if (feels.isNaN()) return "–"
        val e = feels - TYPE_SHIFT[type.coerceIn(0, 2)]
        val base = when {
            e >= 18 -> "Singlet/kurz, kurze Hose, Kappe"
            e >= 12 -> "kurz/kurz"
            e >= 7 -> "T-Shirt, kurze Hose"
            e >= 3 -> "dünnes Langarm, kurze Hose"
            e >= -1 -> "Langarm, lange Tight, dünne Handschuhe"
            e >= -6 -> "Langarm + Windweste, Tight, Handschuhe, Stirnband"
            else -> "Langarm, winddichte Jacke, Tight, Handschuhe, Mütze"
        }
        val extras = ArrayList<String>()
        if (rain) extras.add(if (e >= 10) "Kappe gegen Regen" else "dünne Regenjacke")
        if (gust >= 30 && e < 10 && e >= -6 && !base.contains("Weste")) extras.add("Windweste")
        return if (extras.isEmpty()) base else base + " + " + extras.joinToString(" + ")
    }

    private fun cap(s: String) = s.replaceFirstChar { it.uppercase() }
    fun titleCase(s: String) = s.split(" ").joinToString(" ") { w -> w.lowercase().replaceFirstChar { it.uppercase() } }

    fun color(level: Int, p: Ui.Palette): Int = when (level) {
        GREEN -> p.accent
        YELLOW -> p.warn
        else -> p.danger
    }
}
