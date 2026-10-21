package de.basti.wetter

import android.app.Activity
import android.content.Intent
import android.location.Geocoder
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.Toast
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLDecoder
import java.util.Locale

/**
 * Empfängt ein in Google Maps geteiltes Ziel ("Teilen → Wetter"),
 * ermittelt dessen Koordinaten und übergibt es an den Fahrmodus.
 */
class ShareRouteActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val p = Ui.palette(this)
        setContentView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            background = Ui.rect(p.surface, Ui.dpf(this@ShareRouteActivity, 24))
            setPadding(Ui.dp(context, 28), Ui.dp(context, 24), Ui.dp(context, 28), Ui.dp(context, 24))
            addView(Ui.tv(context, "Fahrmodus", 20f, p.text, true))
            addView(Ui.tv(context, "Ziel wird ermittelt …", 15f, p.muted).apply { setPadding(0, Ui.dp(context, 8), 0, 0) })
        })

        val text = intent?.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val subject = intent?.getStringExtra(Intent.EXTRA_SUBJECT).orEmpty()
        if (text.isBlank()) { finishWith("Kein Ziel erhalten"); return }

        Thread {
            val res = runCatching { resolve(text, subject) }.getOrNull()
            runOnUiThread {
                if (res == null) {
                    finishWith("Ziel konnte nicht erkannt werden")
                } else {
                    if (!DriveService.hasLocation(this)) { finishWith("Bitte zuerst den Standort erlauben"); return@runOnUiThread }
                    val i = Intent(this, DriveService::class.java)
                        .setAction(DriveService.ACTION_ROUTE)
                        .putExtra(DriveService.EXTRA_LAT, res.second.first)
                        .putExtra(DriveService.EXTRA_LON, res.second.second)
                        .putExtra(DriveService.EXTRA_NAME, res.first)
                        .putExtra(DriveService.EXTRA_AUTO, false)
                    runCatching { startForegroundService(i) }
                    finishWith("Route nach ${res.first} übernommen – Wetter entlang der Strecke aktiv")
                }
            }
        }.start()
    }

    private fun finishWith(msg: String) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        finish()
    }

    /** Name und Koordinaten aus dem geteilten Text (Link auflösen, sonst Adresse suchen). */
    private fun resolve(text: String, subject: String): Pair<String, Pair<Double, Double>>? {
        val lines = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val url = Regex("https?://\\S+").find(text)?.value
        val nameLine = lines.firstOrNull { !it.startsWith("http") } ?: subject.ifBlank { "Ziel" }
        val name = nameLine.take(40)

        coordsIn(text)?.let { return name to it }
        if (url != null) {
            val finalUrl = follow(url)
            if (finalUrl != null) coordsIn(finalUrl)?.let { return name to it }
        }
        // Rückfall: Adresse über den Geocoder des Handys suchen
        if (Geocoder.isPresent()) {
            val candidates = lines.filter { !it.startsWith("http") }
            val queries = listOf(candidates.joinToString(", "), candidates.drop(1).joinToString(", "), nameLine).filter { it.isNotBlank() }
            for (q in queries) {
                @Suppress("DEPRECATION")
                val a = runCatching { Geocoder(this, Locale.GERMANY).getFromLocationName(q, 1)?.firstOrNull() }.getOrNull()
                if (a != null) return name to (a.latitude to a.longitude)
            }
        }
        return null
    }

    /** Erkennt Koordinaten in Google-Maps-Links und Texten. */
    private fun coordsIn(s0: String): Pair<Double, Double>? {
        val s = runCatching { URLDecoder.decode(s0, "UTF-8") }.getOrDefault(s0)
        val patterns = listOf(
            Regex("!3d(-?\\d+\\.\\d+)!4d(-?\\d+\\.\\d+)"),
            Regex("[?&](?:q|ll|daddr|destination|center|query)=(-?\\d+\\.\\d+),\\s*(-?\\d+\\.\\d+)"),
            Regex("/dir/[^/]*/(-?\\d+\\.\\d+),\\s*(-?\\d+\\.\\d+)"),
            Regex("@(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)"),
            Regex("geo:(-?\\d+\\.\\d+),(-?\\d+\\.\\d+)")
        )
        for (re in patterns) {
            val m = re.find(s) ?: continue
            val la = m.groupValues[1].toDoubleOrNull() ?: continue
            val lo = m.groupValues[2].toDoubleOrNull() ?: continue
            if (la in -90.0..90.0 && lo in -180.0..180.0 && !(la == 0.0 && lo == 0.0)) return la to lo
        }
        return null
    }

    /** Folgt Weiterleitungen (Kurzlinks) und liefert die letzte Adresse samt evtl. "continue"-Ziel. */
    private fun follow(start: String): String? {
        var url = start
        repeat(6) {
            val con = (URL(url).openConnection() as HttpURLConnection).apply {
                instanceFollowRedirects = false
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) WetterApp")
            }
            try {
                val code = con.responseCode
                val loc = con.getHeaderField("Location")
                if (code in 300..399 && loc != null) {
                    url = URL(URL(url), loc).toString()
                    // Einwilligungsseite: eigentliches Ziel steckt im Parameter "continue"
                    Regex("[?&]continue=([^&]+)").find(url)?.let { m ->
                        val cont = URLDecoder.decode(m.groupValues[1], "UTF-8")
                        if (coordsIn(cont) != null) return cont
                    }
                    if (coordsIn(url) != null) return url
                } else {
                    if (coordsIn(url) != null) return url
                    // Manche Seiten enthalten die Koordinaten im Inhalt
                    val body = runCatching { con.inputStream.bufferedReader().use { it.readText().take(200_000) } }.getOrNull()
                    return if (body != null && coordsIn(body) != null) body else url
                }
            } finally {
                con.disconnect()
            }
        }
        return url
    }
}
