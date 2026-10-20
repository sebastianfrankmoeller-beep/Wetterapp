package de.basti.wetter.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import de.basti.wetter.GlyphView
import de.basti.wetter.Place
import de.basti.wetter.Prefs
import de.basti.wetter.Ui
import de.basti.wetter.WeatherApi

/** Einstellungen eines Widgets: Ort, Inhalt, Deckkraft, Design – mit Live-Vorschau. */
class WidgetConfigActivity : Activity() {

    private var widgetId = AppWidgetManager.INVALID_APPWIDGET_ID
    private lateinit var p: Ui.Palette

    private var place = Prefs.CURRENT
    private var mode = WidgetRenderer.MODE_AUTO
    private var alpha = 90
    private var theme = Prefs.THEME_DARK

    private lateinit var preview: ImageView
    private lateinit var previewBox: FrameLayout
    private lateinit var placeRow: LinearLayout
    private lateinit var modeRow: LinearLayout
    private lateinit var alphaRow: LinearLayout
    private lateinit var themeRow: LinearLayout
    private val main = Handler(Looper.getMainLooper())

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun dp(v: Number) = Ui.dp(this, v)
    private fun dpf(v: Number) = Ui.dpf(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setResult(RESULT_CANCELED)
        widgetId = intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return }

        if (Prefs.widgetConfigured(this, widgetId)) {
            place = Prefs.widgetPlace(this, widgetId)
            mode = Prefs.widgetMode(this, widgetId)
            alpha = Prefs.widgetAlpha(this, widgetId)
            theme = Prefs.widgetTheme(this, widgetId)
        } else theme = Prefs.theme(this)

        p = Ui.palette(this)
        Ui.applySystemBars(this, p)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(24), dp(20), dp(28))
        }
        fun add(v: View, bottom: Int) = root.addView(v, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(bottom) })

        add(Ui.tv(this, "Widget einrichten", 30f, p.text, true).apply { setPadding(dp(4), 0, 0, 0) }, 4)
        add(Ui.tv(this, "Größe änderst du direkt auf dem Startbildschirm", 14f, p.muted).apply { setPadding(dp(4), 0, 0, 0) }, 18)

        // Vorschau auf "Hintergrundbild"-Fläche
        previewBox = FrameLayout(this).apply {
            background = Ui.rect(if (p.dark) 0xFF2A3346.toInt() else 0xFFB8C7DD.toInt(), dpf(24))
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        preview = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_CENTER }
        previewBox.addView(preview, FrameLayout.LayoutParams(MATCH, dp(170)))
        add(previewBox, 22)

        add(section("Ort"), 8)
        placeRow = pillRow(); add(scrollRow(placeRow), 20)
        add(section("Inhalt"), 8)
        modeRow = pillRow(); add(scrollRow(modeRow), 6)
        add(Ui.tv(this, "Automatisch: klein = jetzt, mittel = + Stunden, groß = + Tage", 12.5f, p.muted).apply { setPadding(dp(6), 0, 0, 0) }, 20)
        add(section("Hintergrund"), 8)
        alphaRow = pillRow(); add(scrollRow(alphaRow), 20)
        add(section("Design"), 8)
        themeRow = pillRow(); add(scrollRow(themeRow), 28)

        val save = Ui.tv(this, "Fertig", 17f, p.onAccent, true).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(17), 0, dp(17))
            background = Ui.pressable(p, p.accent, dpf(28))
            setOnClickListener { save() }
        }
        add(save, 0)

        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.bg)
            isVerticalScrollBarEnabled = false
            addView(root)
        })
        refresh()
        ensureData()
    }

    private fun section(t: String) = Ui.tv(this, t.uppercase(), 12.5f, p.muted, true).apply {
        letterSpacing = 0.08f
        setPadding(dp(6), 0, 0, 0)
    }

    private fun pillRow() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }

    private fun scrollRow(row: LinearLayout) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(row)
    }

    private fun fill(row: LinearLayout, items: List<Pair<String, Boolean>>, icon: (Int) -> GlyphView.Kind? = { null }, onPick: (Int) -> Unit) {
        row.removeAllViews()
        for ((i, it) in items.withIndex()) {
            val sel = it.second
            val fg = if (sel) p.onAccent else p.text
            val pill = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(16), dp(10))
                background = if (sel) Ui.pressable(p, p.accent, dpf(20)) else Ui.pressable(p, p.surface, dpf(20), p.border, 1)
                icon(i)?.let { k -> addView(GlyphView(this@WidgetConfigActivity, k, fg, 16), LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(6) }) }
                addView(Ui.tv(this@WidgetConfigActivity, it.first, 14f, fg, true))
                setOnClickListener { onPick(i); refresh(); ensureData() }
            }
            row.addView(pill, LinearLayout.LayoutParams(WRAP, WRAP).apply { if (i > 0) leftMargin = dp(8) })
        }
    }

    private fun refresh() {
        val favs = Prefs.favorites(this)
        val ids = listOf(Prefs.CURRENT) + favs.map { it.id }
        val names = listOf(Prefs.lastLocation(this)?.name ?: "Standort") + favs.map { it.name }
        if (place !in ids) place = Prefs.CURRENT
        fill(placeRow, names.mapIndexed { i, n -> n to (ids[i] == place) }, { i -> if (i == 0) GlyphView.Kind.PIN else null }) { place = ids[it] }

        fill(modeRow, WidgetRenderer.MODE_NAMES.mapIndexed { i, n -> n to (i == mode) }) { mode = it }

        val alphas = listOf(100, 90, 70, 40, 0)
        fill(alphaRow, alphas.map { (if (it == 0) "Ohne" else "$it %") to (it == alpha) }) { alpha = alphas[it] }

        val themes = listOf(Prefs.THEME_DARK to "Dunkel", Prefs.THEME_AUTO to "Wie System", Prefs.THEME_LIGHT to "Hell")
        fill(themeRow, themes.map { it.second to (it.first == theme) }) { theme = themes[it].first }

        renderPreview()
    }

    private fun currentPlace(): Place? = Prefs.resolve(this, place)

    private fun renderPreview() {
        val pl = currentPlace()
        val raw = Prefs.cache(this, place)
        val f = if (pl != null && raw != null) runCatching { WeatherApi.parse(pl, raw) }.getOrNull() else null
        // Vorschau in typischer Größe je nach Inhalt
        val h = when (mode) {
            WidgetRenderer.MODE_NOW -> 90
            WidgetRenderer.MODE_DAYS, WidgetRenderer.MODE_ALL -> 260
            else -> 170
        }
        val bmp = WidgetRenderer.render(this, f, pl?.name ?: "Mein Standort", place == Prefs.CURRENT, 320, h, mode, alpha, theme)
        preview.layoutParams = preview.layoutParams.apply { height = dp(minOf(h, 260)) }
        preview.setImageBitmap(bmp)
    }

    /** Fehlen Daten für den gewählten Ort, im Hintergrund laden und Vorschau erneuern. */
    private fun ensureData() {
        val pl = currentPlace() ?: return
        if (Prefs.cache(this, place) != null) return
        val key = place
        Thread {
            runCatching { WeatherApi.fetchRaw(pl) }.onSuccess {
                Prefs.setCache(this, key, it)
                main.post { if (!isFinishing) renderPreview() }
            }
        }.start()
    }

    private fun save() {
        Prefs.setWidget(this, widgetId, place, mode, alpha, theme)
        val mgr = AppWidgetManager.getInstance(this)
        val id = widgetId
        val app = applicationContext
        Thread { runCatching { WeatherWidget.update(app, mgr, id, allowFetch = true) } }.start()
        setResult(RESULT_OK, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId))
        finish()
    }
}
