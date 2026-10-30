package de.basti.wetter

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import de.basti.wetter.widget.WeatherWidget
import org.osmdroid.util.GeoPoint
import java.util.concurrent.Executors

class MainActivity : Activity(), ForecastPage.Callbacks, SearchPanel.Callbacks {

    private lateinit var p: Ui.Palette
    private lateinit var content: SwipeFrame
    private lateinit var forecastPage: ForecastPage
    private lateinit var search: SearchPanel
    private var radarPage: RadarPage? = null
    private var windPage: WindPage? = null
    private val navItems = ArrayList<Triple<View, GlyphView, android.widget.TextView>>()

    private var tab = 0
    private var themeAtStart = 0
    private var firstResume = true
    private var shown: Forecast? = null
    private var loadSeq = 0
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun dp(v: Number) = Ui.dp(this, v)
    private fun dpf(v: Number) = Ui.dpf(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        CrashLog.install(this)
        CrashLog.note("App geöffnet")
        Usage.init(this)
        super.onCreate(savedInstanceState)
        p = Ui.palette(this)
        Ui.applySystemBars(this, p, if (p.glass) p.bg else p.surface)

        val root = FrameLayout(this)
        Ui.applyBackground(this, root, p)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content = SwipeFrame(this).apply {
            current = { tab }
            page = { i -> ensurePage(i) }
            onSwitch = { i -> selectTab(i) }
        }
        column.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        column.addView(buildNav(), LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        forecastPage = ForecastPage(this, p, this)
        content.addView(forecastPage, FrameLayout.LayoutParams(MATCH, MATCH))

        search = SearchPanel(this, p, this).apply { visibility = View.GONE }
        root.addView(search, FrameLayout.LayoutParams(MATCH, MATCH))

        setContentView(root)
        themeAtStart = Prefs.theme(this) * 100000 + Prefs.styleVersion(this)
        WarnCheck.ensureChannel(this)
        WarnScheduler.ensure(this)
        applyIntentPlace(intent)
        if (intent?.action == ACTION_RUN) { Prefs.setSelected(this, Prefs.CURRENT) }
        selectTab(0)
        showSelected(refreshLocation = true)
        handleAction(intent)
        showCrashReport()
    }

    /** Nach einem Absturz: neuen Bericht einmal anzeigen (bleibt unter Einstellungen → Fehlerberichte). */
    private fun showCrashReport() {
        CrashLog.migrate(this)
        CrashLog.checkExitReasons(this)
        val r = CrashLog.unseen(this) ?: return
        CrashLog.markSeen()
        main.post { ReportDialog.show(this, p, r, null) }
    }
    }

    /** App-Verknüpfungen: Lauf-Check anzeigen bzw. Fahrmodus starten. */
    private fun handleAction(i: Intent?) {
        when (i?.action) {
            ACTION_RUN -> forecastPage.scrollToRun()
            ACTION_DRIVE -> {
                i.action = null
                if (!Locator.hasPermission(this)) {
                    requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC)
                    return
                }
                val ok = DriveService.running || DriveService.start(this, auto = false)
                android.widget.Toast.makeText(this,
                    if (ok) "Fahrmodus aktiv – Wetter auf der Strecke wird geprüft" else "Fahrmodus konnte nicht starten",
                    android.widget.Toast.LENGTH_LONG).show()
            }
        }
    }

    /** Vom Widget geöffnet: den Ort des Widgets anzeigen. */
    private fun applyIntentPlace(i: Intent?): Boolean {
        val id = i?.getStringExtra(EXTRA_PLACE) ?: return false
        i.removeExtra(EXTRA_PLACE)
        if (id != Prefs.CURRENT && Prefs.resolve(this, id) == null) return false
        if (id == Prefs.selected(this)) return false
        Prefs.setSelected(this, id)
        shown = null
        return true
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        if (intent?.action == ACTION_RUN && Prefs.selected(this) != Prefs.CURRENT) {
            Prefs.setSelected(this, Prefs.CURRENT)
            shown = null
            search.visibility = View.GONE
            selectTab(0)
            showSelected(refreshLocation = true)
        } else if (applyIntentPlace(intent)) {
            search.visibility = View.GONE
            selectTab(0)
            forecastPage.scroll.scrollTo(0, 0)
            showSelected(refreshLocation = Prefs.selected(this) == Prefs.CURRENT)
        } else if (tab != 0) selectTab(0)
        handleAction(intent)
    }

    // ------------------------------------------------------------- Navigation unten

    private fun buildNav(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundColor(p.surface)
            setPadding(dp(10), dp(8), dp(10), dp(10))
        }
        val items = listOf(
            Pair("Vorhersage", GlyphView.Kind.FORECAST),
            Pair("Radar", GlyphView.Kind.RADAR),
            Pair("Wind", GlyphView.Kind.WIND)
        )
        for ((i, it) in items.withIndex()) {
            val glyph = GlyphView(this, it.second, p.muted, 24)
            val label = Ui.tv(this, it.first, 12f, p.muted, true)
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(0, dp(6), 0, dp(6))
                addView(glyph, LinearLayout.LayoutParams(dp(24), dp(24)))
                addView(label, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
                setOnClickListener { selectTab(i) }
            }
            bar.addView(item, LinearLayout.LayoutParams(0, WRAP, 1f).apply { setMargins(dp(4), 0, dp(4), 0) })
            navItems.add(Triple(item, glyph, label))
        }
        return bar
    }

    private fun selectTab(i: Int) {
        CrashLog.note("Seite ${listOf("Vorhersage", "Radar", "Wind").getOrElse(i) { "$i" }}")
        tab = i
        for ((j, t) in navItems.withIndex()) {
            val on = j == i
            t.first.background = if (on) Ui.rect(Ui.withAlpha(p.accent, if (p.dark) 38 else 30), dpf(18)) else Ui.pressable(p, p.surface, dpf(18))
            t.second.set(t.second.kind, if (on) p.accent else p.muted)
            t.third.setTextColor(if (on) p.accent else p.muted)
        }
        forecastPage.visibility = if (i == 0) View.VISIBLE else View.GONE
        ensurePage(i)
        radarPage?.let { if (i == 1) { it.visibility = View.VISIBLE; it.onResume() } else { it.visibility = View.GONE; it.onPause() } }
        windPage?.let { if (i == 2) { it.visibility = View.VISIBLE; it.onResume() } else { it.visibility = View.GONE; it.onPause() } }
    }

    /** Seite i liefern; Radar und Wind werden erst bei Bedarf erzeugt. */
    private fun ensurePage(i: Int): View? {
        val center = currentPlace()?.let { GeoPoint(it.lat, it.lon) } ?: GeoPoint(51.0, 10.3)
        return when (i) {
            0 -> forecastPage
            1 -> radarPage ?: RadarPage(this, p, center).also {
                it.visibility = View.GONE
                content.addView(it, FrameLayout.LayoutParams(MATCH, MATCH))
                radarPage = it
            }
            2 -> windPage ?: WindPage(this, p, center).also {
                it.visibility = View.GONE
                content.addView(it, FrameLayout.LayoutParams(MATCH, MATCH))
                windPage = it
            }
            else -> null
        }
    }

    // ------------------------------------------------------------- Orte

    private fun currentPlace(): Place? = Prefs.resolve(this, Prefs.selected(this))

    private fun refreshPills() {
        val sel = Prefs.selected(this)
        val items = ArrayList<ForecastPage.PillItem>()
        val loc = Prefs.lastLocation(this)
        items.add(ForecastPage.PillItem(Prefs.CURRENT, loc?.name ?: "Standort", sel == Prefs.CURRENT, true))
        val favs = Prefs.favorites(this)
        for (f in favs) items.add(ForecastPage.PillItem(f.id, f.name, sel == f.id, false))
        val temp = Prefs.tempPlace(this)
        if (temp != null && sel == temp.id && favs.none { it.id == temp.id }) {
            items.add(ForecastPage.PillItem(temp.id, temp.name, true, false))
        }
        forecastPage.setPills(items)
    }

    private fun updateHeader(place: Place?) {
        val sel = Prefs.selected(this)
        if (place == null) {
            forecastPage.setHeader("Mein Standort", "", isFavorite = false, canFavorite = false)
            return
        }
        val isLoc = sel == Prefs.CURRENT
        val region = if (isLoc) listOf("Aktueller Standort", place.region).filter { it.isNotEmpty() }.joinToString("  ·  ") else place.region
        forecastPage.setHeader(place.name, region, Prefs.isFavorite(this, place), canFavorite = !isLoc)
    }

    /** Zeigt den gewählten Ort: sofort aus dem Zwischenspeicher, dann frisch geladen. */
    private fun showSelected(refreshLocation: Boolean = false, force: Boolean = false) {
        refreshPills()
        val sel = Prefs.selected(this)
        val place = currentPlace()
        updateHeader(place)

        if (place != null) {
            val cached = Prefs.cache(this, cacheKey(sel, place))
            if (cached != null) {
                runCatching { WeatherApi.parse(place, cached) }.getOrNull()?.let { bind(it) }
            }
            recenterMaps(place)
        }

        if (sel == Prefs.CURRENT && (refreshLocation || place == null)) {
            locateAndLoad(force)
        } else if (place != null) {
            load(place, sel, force)
        }
    }

    private fun cacheKey(sel: String, place: Place) = if (sel == Prefs.CURRENT) Prefs.CURRENT else place.id

    private fun locateAndLoad(force: Boolean = false) {
        if (Locator.hasPermission(this)) askNotificationsOnce()
        if (!Locator.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC)
            return
        }
        if (!Locator.locationEnabled(this) && Prefs.lastLocation(this) == null) {
            forecastPage.showStatus("Die Ortung (GPS) ist am Handy ausgeschaltet.", listOf(
                "Ortung einschalten" to { startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) },
                "Ort suchen" to { onSearch() }
            ))
            return
        }
        if (shown == null) forecastPage.showStatus("Standort wird bestimmt …")
        Locator.current(this) { loc ->
            if (loc == null) {
                val last = Prefs.lastLocation(this)
                if (last != null) load(last, Prefs.CURRENT, force)
                else forecastPage.showStatus("Kein Standort gefunden. Am besten kurz im Freien oder mit WLAN erneut versuchen.", listOf(
                    "Erneut versuchen" to { locateAndLoad() },
                    "Ort suchen" to { onSearch() }
                ))
                return@current
            }
            val prev = Prefs.lastLocation(this)
            val moved = prev == null || distKm(prev.lat, prev.lon, loc.latitude, loc.longitude) > 1.0
            val provisional = Place(prev?.name ?: "Mein Standort", prev?.region ?: "", loc.latitude, loc.longitude)
            Prefs.setLastLocation(this, provisional)
            if (Prefs.selected(this) == Prefs.CURRENT) {
                updateHeader(provisional)
                load(provisional, Prefs.CURRENT, moved || force)
            }
            if (moved || prev?.name == "Mein Standort") {
                Locator.name(this, loc.latitude, loc.longitude) { name, region ->
                    val named = provisional.copy(name = name, region = region)
                    Prefs.setLastLocation(this, named)
                    if (Prefs.selected(this) == Prefs.CURRENT) {
                        refreshPills()
                        updateHeader(named)
                    }
                }
            }
        }
    }

    /** Einmalig nach der Benachrichtigungs-Erlaubnis fragen (für Unwetterwarnungen, Android 13+). */
    private fun askNotificationsOnce() {
        if (android.os.Build.VERSION.SDK_INT < 33 || !Prefs.warnEnabled(this) || Prefs.askedNotifications(this)) return
        if (WarnCheck.canNotify(this)) return
        Prefs.setAskedNotifications(this)
        requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIF)
    }

    private fun distKm(a1: Double, o1: Double, a2: Double, o2: Double): Double {
        val r = FloatArray(1)
        android.location.Location.distanceBetween(a1, o1, a2, o2, r)
        return r[0] / 1000.0
    }

    private fun load(place: Place, sel: String, force: Boolean) {
        val key = cacheKey(sel, place)
        val cached = Prefs.cache(this, key)
        val age = cached?.let { runCatching { org.json.JSONObject(it).optLong("at") }.getOrNull() }?.let { System.currentTimeMillis() - it }
        // Daten jünger als 15 Minuten nicht neu laden (außer erzwungen)
        if (!force && age != null && age < 15 * 60_000 && shown?.place?.id == place.id) return
        val my = ++loadSeq
        if (shown == null) forecastPage.showStatus("Lade Vorhersage …")
        io.execute {
            val res = runCatching { WeatherApi.fetch(place) }
            main.post {
                if (my != loadSeq) return@post
                res.onSuccess { (f, raw) ->
                    obsAt = System.currentTimeMillis()   // Messwerte sind in der Vorhersage schon enthalten
                    Prefs.setCache(this, key, raw)
                    bind(f)
                    forecastPage.showStatus(null)
                    WeatherWidget.updateAll(this)
                    finishRefresh(refreshNote(f))
                }.onFailure { e ->
                    finishRefresh("Keine Verbindung – zeige gespeicherte Daten")
                    forecastPage.showStatus(
                        if (shown != null) "Keine Verbindung – zeige gespeicherte Daten"
                        else "Vorhersage konnte nicht geladen werden (${e.message ?: "Netzwerk"})",
                        listOf("Erneut versuchen" to { load(place, sel, true) })
                    )
                }
            }
        }
    }

    // ------------------------------------------------------------ Messwerte

    private var obsAt = 0L
    private val obsTick = object : Runnable {
        override fun run() {
            refreshObs()
            main.postDelayed(this, OBS_EVERY)
        }
    }

    /**
     * Radar und Stationsmessung neu holen (Bright Sky, kein Open-Meteo-Kontingent) und in die
     * gespeicherten Daten einsetzen – höchstens alle 5 Minuten, ohne die Vorhersage neu zu laden.
     * Das Radar ist alle 5 Minuten neu, die Stationen melden etwa stündlich.
     */
    private fun refreshObs(force: Boolean = false) {
        CrashLog.note("Messwerte nachladen")
        val f = shown ?: return
        val sel = currentPlace() ?: return
        // Gezeigter Ort muss noch der gewählte sein (Standort darf sich leicht verschoben haben)
        if (distKm(f.place.lat, f.place.lon, sel.lat, sel.lon) > 2.0) return
        val place = f.place
        if (!RadarNowcast.covers(place.lat, place.lon)) return
        if (!force && System.currentTimeMillis() - obsAt < OBS_EVERY - 5_000) return
        obsAt = System.currentTimeMillis()
        val key = cacheKey(Prefs.selected(this), place)
        io.execute {
            var radarRaw: String? = null
            var visRaw: String? = null
            val tr = Thread { radarRaw = RadarNowcast.fetchRaw(place) }
            val tv = Thread { visRaw = Fog.fetchRaw(place) }
            tr.start(); tv.start()
            val obsJson = Observation.fetchRaw(place)?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
            tr.join(12000); tv.join(10000)
            val radarJson = radarRaw?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
            val visJson = visRaw?.let { runCatching { org.json.JSONObject(it) }.getOrNull() }
            if (obsJson == null && radarJson == null && visJson == null) return@execute
            main.post {
                if (shown?.place?.id != place.id) return@post
                val cached = Prefs.cache(this, key) ?: return@post
                // Zwischenspeicher muss zu genau diesem Ort gehören
                if (runCatching { org.json.JSONObject(cached).optLong("at") }.getOrNull() != shown?.fetchedAt) return@post
                val merged = runCatching {
                    org.json.JSONObject(cached).apply {
                        if (obsJson != null) put("obs", obsJson)
                        if (radarJson != null) put("radar", radarJson)
                        if (visJson != null) put("vis", visJson)
                    }.toString()
                }.getOrNull() ?: return@post
                val nf = runCatching { WeatherApi.parse(place, merged) }.getOrNull() ?: return@post
                Prefs.setCache(this, key, merged)
                shown = nf
                showForecast(nf)
            }
        }
    }

    /** Vorhersage anzeigen – ein Fehler in einzelnen Daten soll die App nicht beenden. */
    private fun showForecast(f: Forecast): Boolean = try {
        forecastPage.bind(f)
        true
    } catch (e: Exception) {
        CrashLog.report(e, "Anzeige ${f.place.name}")
        forecastPage.showStatus("Ein Teil der Anzeige konnte nicht erstellt werden – Fehlerbericht gespeichert")
        false
    }

    private fun bind(f: Forecast) {
        CrashLog.note("Anzeige: ${f.place.name}")
        shown = f
        if (showForecast(f)) forecastPage.showStatus(null)
        loadWarnings(f.place)
    }

    private fun recenterMaps(place: Place) {
        val g = GeoPoint(place.lat, place.lon)
        radarPage?.setCenter(g)
        windPage?.setCenter(g)
    }

    // ------------------------------------------------------- ForecastPage.Callbacks

    override fun onSearch() = search.open()

    override fun onSelectPlace(id: String) {
        CrashLog.note("Ort gewählt: ${if (id == Prefs.CURRENT) "Standort" else Prefs.resolve(this, id)?.name ?: id}")
        val same = id == Prefs.selected(this)
        Prefs.setSelected(this, id)
        if (!same) {
            shown = null
            forecastPage.scroll.scrollTo(0, 0)
        }
        // Erneutes Antippen des gewählten Ortes lädt neu
        showSelected(refreshLocation = id == Prefs.CURRENT, force = same)
    }

    override fun onLongPressPlace(id: String) {
        val fav = Prefs.favorites(this).firstOrNull { it.id == id } ?: return
        val options = arrayOf("Nach links schieben", "Nach rechts schieben", "Aus Favoriten entfernen")
        AlertDialog.Builder(this, dialogTheme())
            .setTitle(fav.name)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> Prefs.moveFavorite(this, id, -1)
                    1 -> Prefs.moveFavorite(this, id, +1)
                    2 -> {
                        Prefs.removeFavorite(this, id)
                        if (Prefs.selected(this) == id) {
                            Prefs.setTempPlace(this, fav)
                        }
                    }
                }
                refreshPills()
                updateHeader(currentPlace())
            }
            .show()
    }

    override fun onToggleFavorite() {
        val place = currentPlace() ?: return
        if (Prefs.selected(this) == Prefs.CURRENT) return
        if (Prefs.isFavorite(this, place)) {
            Prefs.removeFavorite(this, place.id)
            Prefs.setTempPlace(this, place)
        } else {
            Prefs.addFavorite(this, place)
        }
        refreshPills()
        updateHeader(place)
        WeatherWidget.updateAll(this)
    }

    // ------------------------------------------------------- Aktualisieren (nach unten ziehen)

    private var refreshAt = 0L
    private var refreshPending = false
    private val refreshTimeout = Runnable { finishRefresh("Aktualisieren dauert zu lange – später erneut versuchen") }

    override fun onRefresh() {
        CrashLog.note("Aktualisieren (ziehen)")
        val now = System.currentTimeMillis()
        forecastPage.setRefreshing(true)
        // Höchstens alle 30 Sekunden wirklich neu laden (schont das Open-Meteo-Kontingent)
        if (shown != null && now - refreshAt < 30_000) {
            refreshPending = true
            main.postDelayed({ finishRefresh(refreshNote(shown, "Gerade aktualisiert")) }, 500)
            return
        }
        refreshAt = now
        refreshPending = true
        main.removeCallbacks(refreshTimeout)
        main.postDelayed(refreshTimeout, 25_000)
        showSelected(refreshLocation = true, force = true)
    }

    private fun finishRefresh(note: String) {
        if (!refreshPending) return
        refreshPending = false
        main.removeCallbacks(refreshTimeout)
        forecastPage.refreshDone(note)
    }

    /** "Aktualisiert 21:20 · Station 21:00, nächste ca. 22:15" – die Stationen melden etwa stündlich. */
    private fun refreshNote(f: Forecast?, prefix: String = "Aktualisiert"): String {
        if (f == null) return prefix
        val nowSec = System.currentTimeMillis() / 1000
        val o = f.obs?.takeIf { it.fresh(nowSec) }
        val station = if (o != null) " · Station ${f.hhmm(o.t)}, nächste ca. ${f.hhmm(o.t + 3600 + 15 * 60)}" else ""
        return "$prefix ${f.hhmm(nowSec)}$station"
    }

    override fun onSettings() {
        startActivity(Intent(this, SettingsActivity::class.java))
    }

    override fun onInfo(title: String, msg: String) {
        AlertDialog.Builder(this, dialogTheme())
            .setTitle(title)
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    override fun onWarningClick(w: Warning) {
        val msg = buildString {
            append(w.timeRange())
            if (w.description.isNotBlank()) append("\n\n").append(w.description)
            if (w.instruction.isNotBlank()) append("\n\n").append(w.instruction)
            append("\n\nQuelle: Deutscher Wetterdienst")
        }
        AlertDialog.Builder(this, dialogTheme())
            .setTitle(w.headline)
            .setMessage(msg)
            .setPositiveButton("OK", null)
            .show()
    }

    // ------------------------------------------------------------ Warnungen

    private var warnPlaceId: String? = null
    private var warnAt = 0L

    /** DWD-Warnungen für den gezeigten Ort laden (höchstens alle 10 Minuten). */
    private fun loadWarnings(place: Place, force: Boolean = false) {
        if (!force && warnPlaceId == place.id && System.currentTimeMillis() - warnAt < 10 * 60_000) return
        if (warnPlaceId != place.id) forecastPage.setWarnings(null)
        warnPlaceId = place.id
        warnAt = System.currentTimeMillis()
        val app = applicationContext
        io.execute {
            val res = runCatching { Warnings.fetch(app, place.lat, place.lon) }
            main.post {
                if (warnPlaceId != place.id) return@post
                res.onSuccess { forecastPage.setWarnings(it) }
                    .onFailure { warnAt = 0L }
            }
        }
    }

    private fun dialogTheme() =
        if (p.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    // -------------------------------------------------------- SearchPanel.Callbacks

    override fun onPick(place: Place) {
        CrashLog.note("Ort gesucht: ${place.name}")
        search.close()
        val fav = Prefs.favorites(this).firstOrNull { it.id == place.id }
        if (fav == null) Prefs.setTempPlace(this, place)
        Prefs.setSelected(this, place.id)
        shown = null
        selectTab(0)
        forecastPage.scroll.scrollTo(0, 0)
        showSelected()
    }

    override fun onPickLocation() {
        search.close()
        Prefs.setSelected(this, Prefs.CURRENT)
        shown = null
        selectTab(0)
        showSelected(refreshLocation = true)
    }

    override fun onClose() {}

    // ------------------------------------------------------------ Lebenszyklus

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_NOTIF) return
        if (requestCode != REQ_LOC) return
        askNotificationsOnce()
        if (grantResults.any { it == PackageManager.PERMISSION_GRANTED }) {
            locateAndLoad()
        } else {
            forecastPage.showStatus("Ohne Standortfreigabe zeigt die App nur gesuchte Orte. Tippe hier, um die Freigabe zu erteilen.")
            forecastPage.status.setOnClickListener {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
            }
        }
    }

    override fun onResume() {
        super.onResume()
        CrashLog.note("App im Vordergrund")
        if (Prefs.theme(this) * 100000 + Prefs.styleVersion(this) != themeAtStart) { recreate(); return }
        // Noch nichts angezeigt (z. B. Standort erst in den Einstellungen erlaubt/eingeschaltet) → erneut versuchen
        if (shown == null && Prefs.selected(this) == Prefs.CURRENT && Locator.hasPermission(this) && !firstResume) locateAndLoad()
        firstResume = false
        if (tab == 1) radarPage?.onResume()
        if (tab == 2) windPage?.onResume()
        // Nach längerer Pause automatisch aktualisieren
        val f = shown
        if (f != null && System.currentTimeMillis() - f.fetchedAt > 15 * 60_000) {
            showSelected(refreshLocation = Prefs.selected(this) == Prefs.CURRENT)
        }
        // Messwerte: beim Öffnen (wenn älter als 5 Min.) und danach alle 5 Minuten
        main.removeCallbacks(obsTick)
        main.post(obsTick)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(obsTick)
        radarPage?.onPause()
        windPage?.onPause()
    }

    override fun onDestroy() {
        radarPage?.destroy()
        windPage?.destroy()
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            search.visibility == View.VISIBLE -> search.close()
            tab != 0 -> selectTab(0)
            else -> super.onBackPressed()
        }
    }

    companion object {
        private const val OBS_EVERY = 5 * 60_000L
        private const val REQ_LOC = 7
        private const val REQ_NOTIF = 8
        const val EXTRA_PLACE = "place"
        const val ACTION_RUN = "de.basti.wetter.RUN"
        const val ACTION_DRIVE = "de.basti.wetter.DRIVE"
        const val ACTION_DRIVE_VIEW = "de.basti.wetter.DRIVE_VIEW"
    }
}
