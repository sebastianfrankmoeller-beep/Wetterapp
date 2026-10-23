package de.basti.wetter

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Fahrmodus: verfolgt Position und Richtung, prüft das Wetter voraus
 * (Korridor 60 km oder geteilte Route bis zum Ziel) und meldet Gefahren per Sprache und Benachrichtigung.
 */
class DriveService : Service(), LocationListener {

    companion object {
        const val ACTION_START = "de.basti.wetter.drive.START"
        const val ACTION_STOP = "de.basti.wetter.drive.STOP"
        const val ACTION_STOP_AUTO = "de.basti.wetter.drive.STOP_AUTO"
        const val ACTION_ROUTE = "de.basti.wetter.drive.ROUTE"
        const val EXTRA_AUTO = "auto"
        const val EXTRA_LAT = "lat"
        const val EXTRA_LON = "lon"
        const val EXTRA_NAME = "name"

        private const val CHANNEL = "fahrmodus"
        private const val CHANNEL_ALERT = "fahrmodus_alarm"
        private const val NOTIF_ID = 5150
        private const val ALERT_ID = 5151
        private const val CORRIDOR_KM = 60.0
        private const val STEP_KM = 5.0

        @Volatile var running = false
            private set

        fun start(ctx: Context, auto: Boolean): Boolean = try {
            ctx.startForegroundService(Intent(ctx, DriveService::class.java).setAction(ACTION_START).putExtra(EXTRA_AUTO, auto))
            true
        } catch (e: Exception) { false }

        fun stop(ctx: Context) {
            if (running) ctx.startService(Intent(ctx, DriveService::class.java).setAction(ACTION_STOP))
        }

        fun ensureChannels(ctx: Context) {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL, "Fahrmodus", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Laufende Anzeige: Wetter auf der Strecke voraus"
                    setShowBadge(false)
                })
            }
            if (nm.getNotificationChannel(CHANNEL_ALERT) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_ALERT, "Fahrmodus – Gefahren", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Starkregen, Nebel, Glätte usw. auf der Strecke"
                    setSound(null, null)   // die Sprachansage reicht
                    enableVibration(true)
                })
            }
        }

        /** Berechtigungen, die der Fahrmodus braucht. */
        fun hasLocation(ctx: Context) =
            ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private var lm: LocationManager? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var focus: AudioFocusRequest? = null

    private var autoStarted = false
    private val track = ArrayList<Location>()      // Positionen der letzten Kilometer
    private var last: Location? = null
    private var speedKmh = 0.0

    private var route: DriveRoute? = null
    private var routeIdx = -1
    private var offRouteCount = 0
    private var lastReroute = 0L
    private var destAnnounced = false

    private var lastCheckAt = 0L
    private var lastCheckLoc: Location? = null
    private var checking = false
    private var cycle = 0
    private var lastWarnCheck = 0L

    /** Bereits angesagte Gefahren: Art → (Stärke, zuletzt gesehen in Durchlauf). */
    private val announced = HashMap<Hazard, Pair<Int, Int>>()
    private val announcedWarnings = HashSet<String>()
    private var statusTitle = "Fahrmodus aktiv"
    private var statusText = "Warte auf GPS …"

    private val ticker = object : Runnable {
        override fun run() {
            maybeCheck(force = false)
            main.postDelayed(this, 30_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        CrashLog.install(this)
        CrashLog.note("Fahrmodus gestartet")
        Usage.init(this)
        super.onCreate()
        ensureChannels(this)
        tts = TextToSpeech(this) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale.GERMANY)
                tts?.setAudioAttributes(navAttrs())
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) { releaseFocus() }
                    @Deprecated("Deprecated in Java") override fun onError(id: String?) { releaseFocus() }
                })
                ttsReady = true
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> { stopAll(); return START_NOT_STICKY }
            ACTION_STOP_AUTO -> {
                if (!running || autoStarted) { stopAll(); return START_NOT_STICKY }
                return START_STICKY
            }
        }
        goForeground()
        if (!running) {
            running = true
            autoStarted = intent?.getBooleanExtra(EXTRA_AUTO, false) ?: false
            startLocation()
            main.postDelayed(ticker, 30_000)
        } else if (intent?.getBooleanExtra(EXTRA_AUTO, false) == false) {
            autoStarted = false   // manuell gestartet → nicht automatisch beenden
        }
        if (intent?.action == ACTION_ROUTE) {
            val lat = intent.getDoubleExtra(EXTRA_LAT, Double.NaN)
            val lon = intent.getDoubleExtra(EXTRA_LON, Double.NaN)
            val name = intent.getStringExtra(EXTRA_NAME) ?: "Ziel"
            if (!lat.isNaN() && !lon.isNaN()) setDestination(lat, lon, name)
        }
        return START_STICKY
    }

    private fun goForeground() {
        val n = buildStatus()
        try {
            if (Build.VERSION.SDK_INT >= 29) startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            else startForeground(NOTIF_ID, n)
        } catch (e: Exception) {
            // z. B. fehlende Standort-Berechtigung: still beenden statt abstürzen
            stopSelf()
        }
    }

    private fun startLocation() {
        if (!hasLocation(this)) {
            statusText = "Standort-Berechtigung fehlt"
            updateStatus()
            return
        }
        val m = getSystemService(LocationManager::class.java)
        lm = m
        try {
            m.requestLocationUpdates(LocationManager.GPS_PROVIDER, 3000L, 25f, this, Looper.getMainLooper())
            if (m.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                m.requestLocationUpdates(LocationManager.NETWORK_PROVIDER, 20000L, 100f, this, Looper.getMainLooper())
        } catch (e: SecurityException) {
            statusText = "Standort-Berechtigung fehlt"
            updateStatus()
        }
    }

    private fun stopAll() {
        running = false
        main.removeCallbacksAndMessages(null)
        try { lm?.removeUpdates(this) } catch (_: Exception) {}
        tts?.stop()
        releaseFocus()
        getSystemService(NotificationManager::class.java).cancel(ALERT_ID)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        running = false
        main.removeCallbacksAndMessages(null)
        try { lm?.removeUpdates(this) } catch (_: Exception) {}
        tts?.shutdown()
        io.shutdownNow()
        super.onDestroy()
    }

    // ---------------------------------------------------------------- Position

    override fun onLocationChanged(loc: Location) {
        // Netzwerkposition nur, solange kein GPS da ist
        if (loc.provider != LocationManager.GPS_PROVIDER && last?.provider == LocationManager.GPS_PROVIDER &&
            loc.time - (last?.time ?: 0) < 60_000) return
        val prev = last
        last = loc
        if (loc.hasSpeed() && loc.speed > 0) speedKmh = speedKmh * 0.8 + loc.speed * 3.6 * 0.2
        else if (prev != null) {
            val dt = (loc.time - prev.time) / 1000.0
            if (dt > 0) speedKmh = speedKmh * 0.8 + min(200.0, prev.distanceTo(loc) / dt * 3.6) * 0.2
        }
        track.add(loc)
        // nur die letzten ~4 km behalten
        while (track.size > 2 && track.first().distanceTo(loc) > 4000) track.removeAt(0)

        route?.let { r ->
            val (idx, d) = r.nearest(loc.latitude, loc.longitude, routeIdx)
            routeIdx = idx
            offRouteCount = if (d > 1000) offRouteCount + 1 else 0
            if (offRouteCount >= 3 && System.currentTimeMillis() - lastReroute > 120_000) {
                lastReroute = System.currentTimeMillis()
                reroute(loc, r)
            }
            if (Geo.dist(loc.latitude, loc.longitude, r.destLat, r.destLon) < 500) {
                speak("Ziel erreicht.")
                route = null
                statusText = "Ziel erreicht"
                updateStatus()
            }
        }
        maybeCheck(force = lastCheckAt == 0L)
    }

    @Deprecated("Deprecated in Java")
    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
    override fun onProviderEnabled(provider: String) {}
    override fun onProviderDisabled(provider: String) {}

    /** Fahrtrichtung aus der Strecke der letzten Kilometer (stabiler als der GPS-Kurs). */
    private fun heading(): Double? {
        val cur = last ?: return null
        val ref = track.firstOrNull { it.distanceTo(cur) in 800f..3500f }
        if (ref != null) return Geo.bearing(ref.latitude, ref.longitude, cur.latitude, cur.longitude)
        if (cur.hasBearing() && cur.hasSpeed() && cur.speed > 6) return cur.bearing.toDouble()
        return null
    }

    // ---------------------------------------------------------------- Ziel / Route

    private fun setDestination(lat: Double, lon: Double, name: String) {
        statusTitle = "Fahrmodus · Ziel $name"
        statusText = "Route wird berechnet …"
        updateStatus()
        val from = last ?: LocationManagerCompat.lastKnown(this)
        if (from == null) {
            pendingDest = Triple(lat, lon, name)
            statusText = "Warte auf GPS für die Route …"
            updateStatus()
            return
        }
        io.execute {
            val r = runCatching { DriveRoute.fetch(from.latitude, from.longitude, lat, lon, name) }.getOrNull()
            main.post {
                if (r == null) {
                    statusText = "Route nicht verfügbar – prüfe Korridor voraus"
                    updateStatus()
                } else {
                    route = r
                    routeIdx = -1
                    destAnnounced = false
                    announced.clear()
                    maybeCheck(force = true)
                }
            }
        }
    }

    private var pendingDest: Triple<Double, Double, String>? = null

    private fun reroute(loc: Location, r: DriveRoute) {
        io.execute {
            val nr = runCatching { DriveRoute.fetch(loc.latitude, loc.longitude, r.destLat, r.destLon, r.destName) }.getOrNull()
            main.post {
                if (nr != null) { route = nr; routeIdx = -1; offRouteCount = 0; maybeCheck(force = true) }
            }
        }
    }

    // ---------------------------------------------------------------- Prüfung

    private fun maybeCheck(force: Boolean) {
        val loc = last ?: return
        pendingDest?.let { pendingDest = null; setDestination(it.first, it.second, it.third); return }
        if (checking) return
        val now = System.currentTimeMillis()
        val movedKm = lastCheckLoc?.distanceTo(loc)?.div(1000.0) ?: Double.MAX_VALUE
        val interval = if (route != null) 6 * 60_000 else 3 * 60_000
        if (!force && now - lastCheckAt < interval && movedKm < 8) return
        val pts = aheadPoints(loc) ?: run {
            statusText = if (speedKmh < 10) "Warte auf Fahrt …" else "Bestimme Fahrtrichtung …"
            updateStatus()
            return
        }
        checking = true
        lastCheckAt = now
        lastCheckLoc = loc
        cycle++
        val doWarnings = now - lastWarnCheck > 10 * 60_000
        if (doWarnings) lastWarnCheck = now
        val isRoute = route != null
        val step = if (isRoute) stepKmRoute() else STEP_KM
        io.execute {
            val samples = runCatching { DriveWeather.fetch(pts) }.getOrNull()
            val warns = if (doWarnings) warningsAhead(pts) else null
            main.post {
                checking = false
                if (!running) return@post
                if (samples == null) {
                    statusText = "Keine Verbindung – letzte Prüfung ${hhmm(lastOkAt)}"
                    updateStatus()
                    return@post
                }
                lastOkAt = System.currentTimeMillis()
                evaluate(pts, samples, step, isRoute)
                if (warns != null) announceWarnings(warns)
            }
        }
    }

    private var lastOkAt = 0L

    private fun stepKmRoute(): Double {
        val r = route ?: return STEP_KM
        val remainingKm = (r.length - r.cumDist[max(0, routeIdx)]) / 1000.0
        return max(STEP_KM, remainingKm / 40)
    }

    /** Prüfpunkte voraus: entlang der Route bis zum Ziel bzw. im Korridor 60 km in Fahrtrichtung. */
    private fun aheadPoints(loc: Location): List<AheadPoint>? {
        val now = System.currentTimeMillis() / 1000
        val r = route
        if (r != null) {
            val (idx, _) = r.nearest(loc.latitude, loc.longitude, routeIdx)
            routeIdx = idx
            val step = stepKmRoute() * 1000
            val pts = ArrayList<AheadPoint>()
            var target = r.cumDist[idx] + step / 2
            var i = idx
            while (i < r.lat.size) {
                if (r.cumDist[i] >= target || i == r.lat.size - 1) {
                    val km = (r.cumDist[i] - r.cumDist[idx]) / 1000
                    val eta = now + (r.cumDur[i] - r.cumDur[idx]).toLong()
                    pts.add(AheadPoint(r.lat[i], r.lon[i], km, eta))
                    target += step
                    if (i == r.lat.size - 1) break
                }
                i++
            }
            return pts.take(45)
        }
        if (speedKmh < 15) return null
        val h = heading() ?: return null
        val v = (if (speedKmh.isNaN()) 80.0 else speedKmh).coerceIn(50.0, 130.0)
        val pts = ArrayList<AheadPoint>()
        var km = STEP_KM / 2
        while (km <= CORRIDOR_KM) {
            val (la, lo) = Geo.dest(loc.latitude, loc.longitude, h, km * 1000)
            pts.add(AheadPoint(la, lo, km, now + (km / v * 3600).toLong()))
            km += STEP_KM
        }
        return pts
    }

    private fun evaluate(pts: List<AheadPoint>, samples: List<DriveWeather.Sample?>, step: Double, isRoute: Boolean) {
        val segs = DriveWeather.segments(pts, samples, step)
        val horizon = if (isRoute) "bis zum Ziel" else "bis ${CORRIDOR_KM.roundToInt()} km"

        // Ansagen: jede Gefahr einmal, erneut bei Verschärfung
        val seen = HashSet<Hazard>()
        val toSpeak = ArrayList<String>()
        for (s in segs) {
            if (s.hazard in seen) continue
            seen.add(s.hazard)
            // Bei Routen nur die nächsten 80 km ansagen, Weiteres steht in der Benachrichtigung
            if (isRoute && s.startKm > 80) continue
            val prev = announced[s.hazard]
            if (prev == null || s.severity > prev.first) {
                toSpeak.add(sentence(s))
                announced[s.hazard] = s.severity to cycle
            } else announced[s.hazard] = prev.first to cycle
        }
        // Verschwundene Gefahren nach 2 Durchläufen vergessen (damit sie später neu angesagt werden)
        announced.entries.removeAll { cycle - it.value.second >= 2 }

        // Zielwetter (einmal je Route)
        val r = route
        var destLine: String? = null
        if (isRoute && r != null) {
            val ds = samples.lastOrNull()
            val de = pts.lastOrNull()
            if (ds != null && de != null) {
                val what = destWeather(ds)
                destLine = "Am Ziel gegen ${hhmm(de.eta * 1000)}: $what"
                if (!destAnnounced) {
                    destAnnounced = true
                    toSpeak.add(0, "Wetter-Fahrmodus mit Route nach ${r.destName}. Am Ziel gegen ${spokenTime(de.eta)}: $what.")
                }
            }
        }

        // Benachrichtigung
        if (segs.isEmpty()) {
            statusTitle = if (isRoute) "Strecke frei bis zum Ziel" else "Strecke voraus frei"
            statusText = "Kein Starkregen, Nebel, Schnee oder Glätte $horizon" + (destLine?.let { "\n$it" } ?: "")
        } else {
            val first = segs.first()
            statusTitle = "${first.name()} ${inKm(first.startKm)}"
            statusText = segs.take(4).joinToString("\n") { s ->
                "${s.name()}: ${inKm(s.startKm)}, ${lengthText(s)}"
            } + (destLine?.let { "\n$it" } ?: "")
        }
        updateStatus()

        if (toSpeak.isNotEmpty()) {
            speak(toSpeak.joinToString(" "))
            if (segs.isNotEmpty()) alert(statusTitle, statusText)
        }
    }

    private fun inKm(km: Double) = if (km < 2.5) "jetzt" else "in ${DriveWeather.kmText(km)} km"

    private fun lengthText(s: Segment): String {
        val len = s.endKm - s.startKm
        val l = DriveWeather.kmText(len)
        return if (s.openEnd) "mind. $l km" else "ca. $l km"
    }

    private fun sentence(s: Segment): String {
        val len = s.endKm - s.startKm
        val lenSp = DriveWeather.kmText(len)
        val lenTxt = if (s.openEnd) "auf mindestens $lenSp Kilometern" else "etwa $lenSp Kilometer lang"
        return if (s.startKm < 2.5) "Achtung, jetzt ${s.spokenName()}, $lenTxt."
        else "In ${DriveWeather.kmText(s.startKm)} Kilometern ${s.spokenName()}, $lenTxt."
    }

    private fun destWeather(s: DriveWeather.Sample): String {
        val code = Fmt.code(s.code)
        val t = if (s.temp.isNaN()) "" else ", ${s.temp.roundToInt()} °C"
        return "$code$t"
    }

    // ---------------------------------------------------------------- Amtliche Warnungen

    private fun warningsAhead(pts: List<AheadPoint>): List<Pair<Double, Warning>> {
        val pick = if (pts.size <= 3) pts else listOf(pts[1], pts[pts.size / 2], pts.last()).distinct()
        val out = ArrayList<Pair<Double, Warning>>()
        val keys = HashSet<String>()
        for (p in pick.take(4)) {
            val list = runCatching { Warnings.fetch(this, p.lat, p.lon) }.getOrNull() ?: continue
            for (w in list) {
                if (w.level < 2) continue
                if (w.onset > p.eta + 1800 || (w.expires in 1 until p.eta)) continue
                if (keys.add(w.key)) out.add(p.km to w)
            }
        }
        return out
    }

    private fun announceWarnings(list: List<Pair<Double, Warning>>) {
        val fresh = list.filter { announcedWarnings.add(it.second.key) }
        if (fresh.isEmpty()) return
        val parts = fresh.take(2).map { (km, w) ->
            val ev = RunCheck.titleCase(w.event)
            val where = if (km < 5) "auf deiner Strecke" else "in etwa ${DriveWeather.kmText(km)} Kilometern"
            "Amtliche ${if (w.level >= 3) "Unwetterwarnung" else "Warnung"} $where: $ev."
        }
        speak(parts.joinToString(" "))
        alert("Amtliche Warnung voraus", fresh.joinToString("\n") { (km, w) -> "${RunCheck.titleCase(w.event)} · ${inKm(km)} · ${w.timeRange()}" })
    }

    // ---------------------------------------------------------------- Ausgabe

    private fun navAttrs() = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private fun speak(text: String) {
        if (!Prefs.driveVoice(this) || !ttsReady) return
        val am = getSystemService(AudioManager::class.java)
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(navAttrs())
            .build()
        focus = req
        am.requestAudioFocus(req)
        tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "drive_${System.currentTimeMillis()}")
    }

    private fun releaseFocus() {
        val f = focus ?: return
        getSystemService(AudioManager::class.java).abandonAudioFocusRequest(f)
        focus = null
    }

    private fun openIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java).setAction(MainActivity.ACTION_DRIVE_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildStatus(): Notification {
        val stop = PendingIntent.getService(
            this, 1, Intent(this, DriveService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_car)
            .setContentTitle(statusTitle)
            .setContentText(statusText.lineSequence().first())
            .setStyle(Notification.BigTextStyle().bigText(statusText))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setColor(0xFF5BE3A8.toInt())
            .setContentIntent(openIntent())
            .addAction(Notification.Action.Builder(null, "Beenden", stop).build())
            .build()
    }

    private fun updateStatus() {
        if (!running) return
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, buildStatus())
    }

    private fun alert(title: String, text: String) {
        val n = Notification.Builder(this, CHANNEL_ALERT)
            .setSmallIcon(R.drawable.ic_stat_warn)
            .setContentTitle(title)
            .setContentText(text.lineSequence().first())
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setColor(0xFFFF9A3C.toInt())
            .setCategory(Notification.CATEGORY_NAVIGATION)
            .setContentIntent(openIntent())
            .setAutoCancel(true)
            .setTimeoutAfter(10 * 60_000L)
            .build()
        getSystemService(NotificationManager::class.java).notify(ALERT_ID, n)
    }

    private fun hhmm(ms: Long): String {
        if (ms <= 0) return "–"
        val c = java.util.Calendar.getInstance().apply { timeInMillis = ms }
        return "%02d:%02d".format(c.get(java.util.Calendar.HOUR_OF_DAY), c.get(java.util.Calendar.MINUTE))
    }

    private fun spokenTime(sec: Long): String {
        val c = java.util.Calendar.getInstance().apply { timeInMillis = sec * 1000 }
        val m = c.get(java.util.Calendar.MINUTE)
        return "${c.get(java.util.Calendar.HOUR_OF_DAY)} Uhr" + if (m > 0) " $m" else ""
    }
}

/** Kleine Hilfe: letzte bekannte Position ohne Wartezeit. */
object LocationManagerCompat {
    fun lastKnown(ctx: Context): Location? = Locator.lastKnown(ctx)
}
