package de.basti.wetter

import android.content.Context
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone

/** Eine amtliche Warnung des Deutschen Wetterdienstes. */
class Warning(
    val key: String,          // eindeutig je Warnung (für "schon gemeldet")
    val event: String,        // z. B. "STARKES GEWITTER"
    val headline: String,     // z. B. "Amtliche UNWETTERWARNUNG vor SCHWEREM GEWITTER"
    val description: String,
    val instruction: String,
    val level: Int,           // 1 Gelb, 2 Orange, 3 Rot, 4 Violett
    val onset: Long,          // Unix-Sekunden
    val expires: Long,        // Unix-Sekunden, 0 = offen
    val future: Boolean       // Vorabinformation (noch nicht in Kraft)
) {
    val isThunder: Boolean get() = event.contains("GEWITTER", ignoreCase = true)

    /** "heute 18:00 – 22:00" bzw. mit Wochentag, in Ortszeit des Handys. */
    fun timeRange(): String {
        fun fmt(t: Long, withDay: Boolean): String {
            val c = Calendar.getInstance().apply { timeInMillis = t * 1000 }
            val today = Calendar.getInstance()
            val dayDiff = dayIndex(c) - dayIndex(today)
            val day = when (dayDiff) {
                0 -> "heute"
                1 -> "morgen"
                -1 -> "gestern"
                else -> Forecast.WEEKDAYS[c.get(Calendar.DAY_OF_WEEK) - 1] + " " + c.get(Calendar.DAY_OF_MONTH) + "."
            }
            val hm = "%02d:%02d".format(c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
            return if (withDay) "$day $hm" else hm
        }
        val from = fmt(onset, true)
        if (expires <= 0) return "ab $from"
        val sameDay = dayIndex(cal(onset)) == dayIndex(cal(expires))
        return "$from – ${fmt(expires, !sameDay)}"
    }

    private fun cal(t: Long) = Calendar.getInstance().apply { timeInMillis = t * 1000 }
    private fun dayIndex(c: Calendar) = c.get(Calendar.YEAR) * 400 + c.get(Calendar.DAY_OF_YEAR)

    companion object {
        val LEVEL_NAMES = arrayOf("", "Wetterwarnung", "Markantes Wetter", "Unwetterwarnung", "Extremes Unwetter")
        val COLOR_NAMES = arrayOf("", "Gelb", "Orange", "Rot", "Violett")

        fun color(level: Int): Int = when (level) {
            1 -> 0xFFF2D43D.toInt()
            2 -> 0xFFFF9A3C.toInt()
            3 -> 0xFFFF4F4F.toInt()
            4 -> 0xFFC86BFF.toInt()
            else -> 0xFF8B9098.toInt()
        }
    }
}

/**
 * Abruf der DWD-Warnungen über den GeoServer (WFS).
 * Erst wird die Gemeinde zur Position bestimmt (wird gespeichert), dann deren Warnungen abgefragt.
 */
object Warnings {
    private const val WFS = "https://maps.dwd.de/geoserver/dwd/ows?service=WFS&version=2.0.0&request=GetFeature&OutputFormat=application/json"

    /** Ergebnis: null = Ort liegt außerhalb Deutschlands (keine DWD-Warnungen). */
    fun fetch(ctx: Context, lat: Double, lon: Double): List<Warning>? {
        val cell = cellFor(ctx, lat, lon) ?: return null
        val filter = if (cell.second) "GC_WARNCELLID='${cell.first}'" else "WARNCELLID='${cell.first}'"
        val type = if (cell.second) "dwd:Warnungen_Landkreise" else "dwd:Warnungen_Gemeinden"
        val json = JSONObject(Net.get("$WFS&typeName=$type&CQL_FILTER=${Net.enc(filter)}", 15000))
        val feats = json.optJSONArray("features") ?: return emptyList()
        val out = ArrayList<Warning>()
        val seen = HashSet<String>()
        for (i in 0 until feats.length()) {
            val pr = feats.getJSONObject(i).optJSONObject("properties") ?: continue
            val level = when (pr.optString("SEVERITY").lowercase()) {
                "minor" -> 1
                "moderate" -> 2
                "severe" -> 3
                "extreme" -> 4
                else -> 0
            }
            if (level == 0) continue
            val onset = parseTime(pr.optString("ONSET"))
            val expires = parseTime(pr.optString("EXPIRES"))
            val event = pr.optString("EVENT")
            val id = pr.optString("IDENTIFIER").ifBlank { "${pr.optString("EC_II")}_${onset}" }
            if (!seen.add(id)) continue
            out.add(
                Warning(
                    key = id,
                    event = event,
                    headline = pr.optString("HEADLINE").ifBlank { event },
                    description = pr.optString("DESCRIPTION"),
                    instruction = pr.optString("INSTRUCTION"),
                    level = level,
                    onset = onset,
                    expires = expires,
                    future = pr.optString("URGENCY").equals("Future", ignoreCase = true)
                )
            )
        }
        val now = System.currentTimeMillis() / 1000
        return out.filter { it.expires == 0L || it.expires > now }
            .sortedWith(compareByDescending<Warning> { it.level }.thenBy { it.onset })
    }

    /** Warnzelle (ID, ist Landkreis?) zu einer Position – gespeichert, damit nur einmal gesucht wird. */
    private fun cellFor(ctx: Context, lat: Double, lon: Double): Pair<String, Boolean>? {
        val k = "%.2f,%.2f".format(Locale.US, lat, lon)
        val sp = ctx.applicationContext.getSharedPreferences("warncells", Context.MODE_PRIVATE)
        sp.getString(k, null)?.let { v ->
            if (v == "-") return null
            val parts = v.split("|")
            return parts[0] to (parts.getOrNull(1) == "K")
        }
        val point = Net.enc("CONTAINS(SHAPE, Point(%.5f %.5f))".format(Locale.US, lat, lon))
        for ((type, isKreis) in listOf("dwd:Warngebiete_Gemeinden" to false, "dwd:Warngebiete_Kreise" to true)) {
            val json = JSONObject(Net.get("$WFS&typeName=$type&CQL_FILTER=$point&propertyName=WARNCELLID,NAME", 15000))
            val f = json.optJSONArray("features")
            if (f != null && f.length() > 0) {
                val id = f.getJSONObject(0).getJSONObject("properties").optString("WARNCELLID")
                if (id.isNotBlank()) {
                    sp.edit().putString(k, id + "|" + (if (isKreis) "K" else "G")).apply()
                    return id to isKreis
                }
            }
        }
        sp.edit().putString(k, "-").apply()
        return null
    }

    private fun parseTime(s: String): Long {
        if (s.isBlank() || s == "null") return 0
        val v = s.replace("Z", "+00:00")
        for (pattern in listOf("yyyy-MM-dd'T'HH:mm:ssXXX", "yyyy-MM-dd'T'HH:mm:ss.SSSXXX")) {
            val r = runCatching { SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }.parse(v)?.time }.getOrNull()
            if (r != null) return r / 1000
        }
        return 0
    }
}
