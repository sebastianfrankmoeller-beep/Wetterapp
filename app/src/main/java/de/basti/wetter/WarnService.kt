package de.basti.wetter

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import org.json.JSONObject
import java.util.Calendar

/**
 * Prüft im Hintergrund die DWD-Warnungen und meldet neue (oder hochgestufte) Warnungen.
 * Adaptiv: normalerweise stündlich, bei Gewitterlage alle 15 Minuten.
 */
object WarnCheck {

    const val CHANNEL = "warnungen"

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) != null) return
        val ch = NotificationChannel(CHANNEL, "Unwetterwarnungen", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Amtliche Warnungen des Deutschen Wetterdienstes"
            enableVibration(true)
        }
        nm.createNotificationChannel(ch)
    }

    fun canNotify(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    /** Orte, für die gewarnt wird (Standort + ausgewählte Favoriten). */
    private fun places(ctx: Context): List<Pair<String, Place>> {
        val out = ArrayList<Pair<String, Place>>()
        if (Prefs.warnLocation(ctx)) {
            var loc = Prefs.lastLocation(ctx)
            val l = runCatching { Locator.lastKnown(ctx) }.getOrNull()
            if (l != null) {
                val r = FloatArray(1)
                if (loc != null) android.location.Location.distanceBetween(loc.lat, loc.lon, l.latitude, l.longitude, r)
                if (loc == null || r[0] > 2000) {
                    loc = Place(if (loc == null) "Mein Standort" else loc.name, loc?.region ?: "", l.latitude, l.longitude)
                    Prefs.setLastLocation(ctx, loc)
                }
            }
            if (loc != null) out.add(Prefs.CURRENT to loc)
        }
        val ids = Prefs.warnFavorites(ctx)
        for (f in Prefs.favorites(ctx)) if (f.id in ids) out.add(f.id to f)
        return out
    }

    /** Führt eine Prüfung aus und gibt die Minuten bis zur nächsten Prüfung zurück. */
    fun run(ctx: Context): Int {
        if (!Prefs.warnEnabled(ctx)) return -1
        var storm = false
        val seen = Prefs.warnSeen(ctx)
        pruneSeen(seen)
        for ((id, place) in places(ctx)) {
            val list = runCatching { Warnings.fetch(ctx, place.lat, place.lon) }.getOrNull()
            if (list != null) {
                for (w in list) {
                    if (w.isThunder) storm = true
                    if (w.level >= Prefs.warnMinLevel(ctx)) maybeNotify(ctx, id, place, w, seen)
                }
            }
            // Gewitterlage laut Vorhersage? Zwischenspeicher nutzen, nach 3 h neu laden.
            var raw = Prefs.cache(ctx, id)
            val age = raw?.let { r -> runCatching { System.currentTimeMillis() - JSONObject(r).optLong("at") }.getOrNull() }
            if (raw == null || age == null || age > 3 * 3600_000) {
                runCatching { WeatherApi.fetchRaw(place) }.getOrNull()?.let { raw = it; Prefs.setCache(ctx, id, it) }
            }
            raw?.let { r ->
                val f = runCatching { WeatherApi.parse(place, r) }.getOrNull()
                if (f != null && f.hours.take(6).any { it.thunder >= 2 }) storm = true
            }
        }
        Prefs.setWarnSeen(ctx, seen)
        return if (storm) 15 else 60
    }

    private fun pruneSeen(seen: JSONObject) {
        val now = System.currentTimeMillis() / 1000
        val it = seen.keys()
        val remove = ArrayList<String>()
        while (it.hasNext()) {
            val k = it.next()
            val exp = seen.optString(k).split("|").getOrNull(1)?.toLongOrNull() ?: 0L
            if (exp in 1 until now - 3600) remove.add(k)
        }
        remove.forEach { seen.remove(it) }
    }

    private fun quietNow(): Boolean {
        val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        return h >= 22 || h < 7
    }

    private fun maybeNotify(ctx: Context, placeId: String, place: Place, w: Warning, seen: JSONObject) {
        val key = "$placeId#${w.key}"
        val prevLevel = seen.optString(key).split("|").getOrNull(0)?.toIntOrNull() ?: 0
        if (prevLevel >= w.level) return
        // Nachtruhe: nur Rot und Violett; die übrigen kommen morgens, falls dann noch gültig
        if (Prefs.warnQuiet(ctx) && quietNow() && w.level < 3) return
        if (!canNotify(ctx)) return
        notify(ctx, placeId, place, w, upgraded = prevLevel > 0)
        seen.put(key, "${w.level}|${w.expires}")
    }

    fun notify(ctx: Context, placeId: String, place: Place, w: Warning, upgraded: Boolean = false) {
        ensureChannel(ctx)
        val event = w.event.split(" ").joinToString(" ") { wd -> wd.lowercase().replaceFirstChar { it.uppercase() } }
        val kind = if (w.future) "Vorabinformation" else Warning.LEVEL_NAMES[w.level]
        val title = (if (upgraded) "Hochgestuft – " else "") + "$kind: $event"
        val line = "${place.name} · ${w.timeRange()}"
        val body = buildString {
            append(line)
            if (w.description.isNotBlank()) append("\n\n").append(w.description)
            if (w.instruction.isNotBlank()) append("\n\n").append(w.instruction)
        }
        val open = Intent(ctx, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_PLACE, placeId)
        }
        val id = key(placeId, w.key)
        val pi = PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_warn)
            .setColor(Warning.color(w.level))
            .setContentTitle(title)
            .setContentText(line)
            .setStyle(Notification.BigTextStyle().bigText(body))
            .setCategory(Notification.CATEGORY_EVENT)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .apply { if (w.expires > 0) setTimeoutAfter(maxOf(60_000L, w.expires * 1000 - System.currentTimeMillis())) }
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(id, n)
    }

    private fun key(placeId: String, wKey: String) = ("$placeId#$wKey").hashCode()

    /** Test-Benachrichtigung zum Einrichten (z. B. Garmin-Uhr). */
    fun test(ctx: Context) {
        val now = System.currentTimeMillis() / 1000
        val place = Prefs.lastLocation(ctx) ?: Place("Mein Standort", "", 0.0, 0.0)
        val w = Warning(
            key = "test_$now", event = "TEST", headline = "Testmeldung",
            description = "Dies ist eine Test-Benachrichtigung der Wetter-App. Echte Warnungen sehen genauso aus.",
            instruction = "", level = 2, onset = now, expires = now + 3600, future = false
        )
        notify(ctx, "test", place, w)
    }
}

/** Plant die nächste Prüfung als einmaligen Auftrag (übersteht Neustarts). */
object WarnScheduler {
    private const val JOB_ID = 4711

    fun schedule(ctx: Context, minutes: Int) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (minutes < 0) { js.cancel(JOB_ID); return }
        val info = JobInfo.Builder(JOB_ID, ComponentName(ctx, WarnJobService::class.java))
            .setMinimumLatency(minutes * 60_000L)
            .setOverrideDeadline((minutes + 10) * 60_000L)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY)
            .setPersisted(true)
            .build()
        js.schedule(info)
    }

    /** Beim App-Start: Prüfung einplanen, falls aktiv und noch keine geplant ist. */
    fun ensure(ctx: Context) {
        val js = ctx.getSystemService(JobScheduler::class.java)
        if (!Prefs.warnEnabled(ctx)) { js.cancel(JOB_ID); return }
        if (js.getPendingJob(JOB_ID) == null) schedule(ctx, 1)
    }

    fun cancel(ctx: Context) = ctx.getSystemService(JobScheduler::class.java).cancel(JOB_ID)
}

class WarnJobService : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        Usage.init(this)
        val app = applicationContext
        Thread {
            var next = 60
            try {
                next = WarnCheck.run(app)
            } catch (_: Exception) {
            } finally {
                // Erst beenden, dann neu planen (gleiche Auftrags-ID würde den laufenden Auftrag abbrechen)
                jobFinished(params, false)
                WarnScheduler.schedule(app, next)
            }
        }.start()
        return true
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        WarnScheduler.schedule(applicationContext, 15)
        return false
    }
}
