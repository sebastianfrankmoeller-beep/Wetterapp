package de.basti.wetter

import android.content.Context
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max

/**
 * Zählt den geschätzten Open-Meteo-Verbrauch des Tages (kostenlos: 10.000 je Tag).
 * Open-Meteo gewichtet: jeder Ort zählt einzeln, mehr als 10 Variablen bzw. 2 Wochen zählen anteilig mehr.
 * Ab [SAVE_FROM] lädt die Windkarte keine feinen Ausschnitte mehr, damit Vorhersage, Warnungen
 * und Fahrmodus sicher weiterlaufen.
 */
object Usage {
    const val DAILY = 10_000
    const val SAVE_FROM = 7_000

    @Volatile private var app: Context? = null
    private var day = ""
    private var used = 0.0
    private val dayFmt = SimpleDateFormat("yyyyMMdd", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }

    fun init(ctx: Context) {
        if (app != null) return
        app = ctx.applicationContext
        synchronized(this) {
            val sp = sp() ?: return
            day = sp.getString("om_day", "") ?: ""
            used = sp.getFloat("om_used", 0f).toDouble()
        }
    }

    private fun sp() = app?.getSharedPreferences("wetter", Context.MODE_PRIVATE)

    private fun today() = synchronized(dayFmt) { dayFmt.format(Date()) }

    /** Geschätzter Verbrauch heute. */
    fun todayUsed(): Int = synchronized(this) { if (day == today()) used.toInt() else 0 }

    fun saving(): Boolean = todayUsed() >= SAVE_FROM

    /** Von [Net.get] für jede Open-Meteo-Anfrage aufgerufen. */
    fun count(url: String) {
        val host = runCatching { URL(url).host }.getOrNull() ?: return
        if (host != "api.open-meteo.com") return
        val w = weight(url)
        synchronized(this) {
            val t = today()
            if (day != t) { day = t; used = 0.0 }
            used += w
            sp()?.edit()?.putString("om_day", day)?.putFloat("om_used", used.toFloat())?.apply()
        }
    }

    /** Gewicht einer Anfrage: Orte × max(1, Variablen/10) × max(1, Tage/14). */
    fun weight(url: String): Double {
        val q = url.substringAfter('?', "").split('&').associate { it.substringBefore('=') to it.substringAfter('=', "") }
        val locs = (q["latitude"]?.count { it == ',' } ?: 0) + 1
        val vars = listOf("hourly", "daily", "minutely_15", "current").sumOf { k ->
            q[k]?.split(',')?.count { it.isNotBlank() } ?: 0
        }.coerceAtLeast(1)
        val days = q["forecast_days"]?.toIntOrNull()
            ?: q["forecast_hours"]?.toIntOrNull()?.let { (it + 23) / 24 } ?: 7
        return locs * max(1.0, vars / 10.0) * max(1.0, days / 14.0)
    }
}
