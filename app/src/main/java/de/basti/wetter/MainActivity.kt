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
    private lateinit var content: FrameLayout
    private lateinit var forecastPage: ForecastPage
    private lateinit var search: SearchPanel
    private var radarPage: RadarPage? = null
    private var windPage: WindPage? = null
    private val navItems = ArrayList<Triple<View, GlyphView, android.widget.TextView>>()

    private var tab = 0
    private var shown: Forecast? = null
    private var loadSeq = 0
    private val io = Executors.newFixedThreadPool(2)
    private val main = Handler(Looper.getMainLooper())

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun dp(v: Number) = Ui.dp(this, v)
    private fun dpf(v: Number) = Ui.dpf(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Ui.palette(this)
        Ui.applySystemBars(this, p, p.surface)

        val root = FrameLayout(this).apply { setBackgroundColor(p.bg) }
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content = FrameLayout(this)
        column.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f))
        column.addView(buildNav(), LinearLayout.LayoutParams(MATCH, WRAP))
        root.addView(column, FrameLayout.LayoutParams(MATCH, MATCH))

        forecastPage = ForecastPage(this, p, this)
        content.addView(forecastPage, FrameLayout.LayoutParams(MATCH, MATCH))

        search = SearchPanel(this, p, this).apply { visibility = View.GONE }
        root.addView(search, FrameLayout.LayoutParams(MATCH, MATCH))

        setContentView(root)
        applyIntentPlace(intent)
        selectTab(0)
        showSelected(refreshLocation = true)
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
        if (applyIntentPlace(intent)) {
            search.visibility = View.GONE
            selectTab(0)
            forecastPage.scroll.scrollTo(0, 0)
            showSelected(refreshLocation = Prefs.selected(this) == Prefs.CURRENT)
        } else if (tab != 0) selectTab(0)
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
        tab = i
        for ((j, t) in navItems.withIndex()) {
            val on = j == i
            t.first.background = if (on) Ui.rect(Ui.withAlpha(p.accent, if (p.dark) 38 else 30), dpf(18)) else Ui.pressable(p, p.surface, dpf(18))
            t.second.set(t.second.kind, if (on) p.accent else p.muted)
            t.third.setTextColor(if (on) p.accent else p.muted)
        }
        val center = currentPlace()?.let { GeoPoint(it.lat, it.lon) } ?: GeoPoint(51.0, 10.3)
        forecastPage.visibility = if (i == 0) View.VISIBLE else View.GONE

        if (i == 1 && radarPage == null) {
            radarPage = RadarPage(this, p, center).also { content.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }
        }
        if (i == 2 && windPage == null) {
            windPage = WindPage(this, p, center).also { content.addView(it, FrameLayout.LayoutParams(MATCH, MATCH)) }
        }
        radarPage?.let { if (i == 1) { it.visibility = View.VISIBLE; it.onResume() } else { it.visibility = View.GONE; it.onPause() } }
        windPage?.let { if (i == 2) { it.visibility = View.VISIBLE; it.onResume() } else { it.visibility = View.GONE; it.onPause() } }
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
            locateAndLoad()
        } else if (place != null) {
            load(place, sel, force)
        }
    }

    private fun cacheKey(sel: String, place: Place) = if (sel == Prefs.CURRENT) Prefs.CURRENT else place.id

    private fun locateAndLoad() {
        if (!Locator.hasPermission(this)) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_LOC)
            return
        }
        if (shown == null) forecastPage.showStatus("Standort wird bestimmt …")
        Locator.current(this) { loc ->
            if (loc == null) {
                val last = Prefs.lastLocation(this)
                if (last != null) load(last, Prefs.CURRENT, false)
                else forecastPage.showStatus("Kein Standort verfügbar – ist die Ortung eingeschaltet? Alternativ oben einen Ort suchen.")
                return@current
            }
            val prev = Prefs.lastLocation(this)
            val moved = prev == null || distKm(prev.lat, prev.lon, loc.latitude, loc.longitude) > 1.0
            val provisional = Place(prev?.name ?: "Mein Standort", prev?.region ?: "", loc.latitude, loc.longitude)
            Prefs.setLastLocation(this, provisional)
            if (Prefs.selected(this) == Prefs.CURRENT) {
                updateHeader(provisional)
                load(provisional, Prefs.CURRENT, moved)
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
                    Prefs.setCache(this, key, raw)
                    bind(f)
                    forecastPage.showStatus(null)
                    WeatherWidget.updateAll(this)
                }.onFailure { e ->
                    forecastPage.showStatus(
                        if (shown != null) "Keine Verbindung – zeige gespeicherte Daten"
                        else "Vorhersage konnte nicht geladen werden (${e.message ?: "Netzwerk"})"
                    )
                }
            }
        }
    }

    private fun bind(f: Forecast) {
        shown = f
        forecastPage.bind(f)
        forecastPage.showStatus(null)
    }

    private fun recenterMaps(place: Place) {
        val g = GeoPoint(place.lat, place.lon)
        radarPage?.setCenter(g)
        windPage?.setCenter(g)
    }

    // ------------------------------------------------------- ForecastPage.Callbacks

    override fun onSearch() = search.open()

    override fun onSelectPlace(id: String) {
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

    override fun onRefresh() = showSelected(refreshLocation = true, force = true)

    override fun onSettings() {
        val names = arrayOf("Dunkel", "Automatisch (wie System)", "Hell")
        val values = intArrayOf(Prefs.THEME_DARK, Prefs.THEME_AUTO, Prefs.THEME_LIGHT)
        val current = values.indexOf(Prefs.theme(this))
        AlertDialog.Builder(this, dialogTheme())
            .setTitle("Darstellung")
            .setSingleChoiceItems(names, current) { dlg, which ->
                dlg.dismiss()
                if (values[which] != Prefs.theme(this)) {
                    Prefs.setTheme(this, values[which])
                    recreate()
                }
            }
            .setNeutralButton("Jetzt aktualisieren") { _, _ -> onRefresh() }
            .setNegativeButton("Schließen", null)
            .show()
    }

    private fun dialogTheme() =
        if (p.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert

    // -------------------------------------------------------- SearchPanel.Callbacks

    override fun onPick(place: Place) {
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
        if (requestCode != REQ_LOC) return
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
        if (tab == 1) radarPage?.onResume()
        if (tab == 2) windPage?.onResume()
        // Nach längerer Pause automatisch aktualisieren
        val f = shown
        if (f != null && System.currentTimeMillis() - f.fetchedAt > 15 * 60_000) {
            showSelected(refreshLocation = Prefs.selected(this) == Prefs.CURRENT)
        }
    }

    override fun onPause() {
        super.onPause()
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
        private const val REQ_LOC = 7
        const val EXTRA_PLACE = "place"
    }
}
