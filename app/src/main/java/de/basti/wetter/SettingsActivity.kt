package de.basti.wetter

import android.Manifest
import android.animation.ArgbEvaluator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.DecelerateInterpolator
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/** Ein/Aus-Schalter im Tempowarner-Stil (Pillenform mit gleitendem Knopf). */
@SuppressLint("ViewConstructor")
class ToggleView(context: Context, private val onColor: Int, private val offColor: Int) : View(context) {
    var isOn = false
        private set
    var onChange: ((Boolean) -> Unit)? = null
    private val d = resources.displayMetrics.density
    private val trackW = 52 * d
    private val trackH = 32 * d
    private var pos = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val argb = ArgbEvaluator()
    private var anim: ValueAnimator? = null

    init {
        isClickable = true
        setOnClickListener { setState(!isOn, animate = true, notify = true) }
    }

    fun setState(value: Boolean, animate: Boolean, notify: Boolean = false) {
        isOn = value
        anim?.cancel()
        val target = if (value) 1f else 0f
        if (animate) {
            anim = ValueAnimator.ofFloat(pos, target).apply {
                duration = 160
                interpolator = DecelerateInterpolator()
                addUpdateListener { pos = it.animatedValue as Float; invalidate() }
                start()
            }
        } else { pos = target; invalidate() }
        if (notify) onChange?.invoke(value)
    }

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(trackW.toInt(), trackH.toInt())

    override fun onDraw(canvas: Canvas) {
        paint.color = argb.evaluate(pos, offColor, onColor) as Int
        canvas.drawRoundRect(0f, 0f, trackW, trackH, trackH / 2, trackH / 2, paint)
        paint.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(trackH / 2 + pos * (trackW - trackH), trackH / 2, trackH / 2 - 4 * d, paint)
    }
}

/** Einstellungen: Darstellung, Unwetterwarnungen, Berechtigungen. */
class SettingsActivity : Activity() {

    private lateinit var p: Ui.Palette
    private lateinit var root: LinearLayout
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun dp(v: Number) = Ui.dp(this, v)
    private fun dpf(v: Number) = Ui.dpf(this, v)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        p = Ui.palette(this)
        Ui.applySystemBars(this, p)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(16), dp(20), dp(32))
        }
        setContentView(ScrollView(this).apply {
            setBackgroundColor(p.bg)
            isVerticalScrollBarEnabled = false
            addView(root)
        })
    }

    override fun onResume() {
        super.onResume()
        build()   // Berechtigungen können sich in den Systemeinstellungen geändert haben
    }

    private fun build() {
        root.removeAllViews()
        fun add(v: View, bottom: Int) = root.addView(v, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(bottom) })

        add(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(FrameLayout(this@SettingsActivity).apply {
                background = Ui.pressable(p, p.surface, dpf(22), p.border, 1)
                addView(GlyphView(this@SettingsActivity, GlyphView.Kind.BACK, p.text), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
                setOnClickListener { finish() }
            }, LinearLayout.LayoutParams(dp(44), dp(44)).apply { rightMargin = dp(14) })
            addView(Ui.tv(this@SettingsActivity, "Einstellungen", 28f, p.text, true))
        }, 24)

        // ------------------------------------------------------------ Darstellung
        add(section("Darstellung"), 8)
        add(card(pillRow(
            listOf("Dunkel" to Prefs.THEME_DARK, "Automatisch" to Prefs.THEME_AUTO, "Hell" to Prefs.THEME_LIGHT),
            Prefs.theme(this)
        ) { v ->
            Prefs.setTheme(this, v)
            recreate()
        }), 24)

        // ------------------------------------------------------------ Lauf-Check
        add(section("Lauf-Check"), 8)
        add(card(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(14), 0, dp(4))
            addView(Ui.tv(this@SettingsActivity, "Läufertyp für die Kleidungsempfehlung", 16f, p.text, true))
            addView(pillRow(listOf("Läuft warm" to RunCheck.TYPE_WARM, "Normal" to RunCheck.TYPE_NORMAL, "Friert schnell" to RunCheck.TYPE_COLD),
                Prefs.runnerType(this@SettingsActivity)) { v -> Prefs.setRunnerType(this@SettingsActivity, v); build() })
            addView(Ui.tv(this@SettingsActivity, when (Prefs.runnerType(this@SettingsActivity)) {
                RunCheck.TYPE_WARM -> "Schwitzt schnell, wird beim Laufen schnell warm – leichteste Empfehlung."
                RunCheck.TYPE_NORMAL -> "Übliche Läuferfaustregel."
                else -> "Friert leicht – eine Schicht mehr als üblich."
            }, 13f, p.muted), LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })
        }), 24)

        // ------------------------------------------------------------- Fahrmodus
        add(section("Fahrmodus"), 8)
        val drive = ArrayList<View>()
        val access = MapsNavListener.hasAccess(this)
        drive.add(toggleRow("Automatisch mit Google Maps", "Startet, sobald Google Maps navigiert, und endet mit der Navigation", Prefs.driveAuto(this)) {
            Prefs.setDriveAuto(this, it)
            build()
        })
        if (Prefs.driveAuto(this)) {
            drive.add(statusRow("Benachrichtigungszugriff",
                if (access) "Erlaubt – Navigation wird erkannt" else "Nötig, um die Google-Maps-Navigation zu erkennen", access) {
                startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
            })
        }
        drive.add(toggleRow("Sprachansagen", "z. B. „In 12 Kilometern Starkregen“ – Google Maps wird kurz leiser", Prefs.driveVoice(this)) {
            Prefs.setDriveVoice(this, it)
        })
        drive.add(actionRow(if (DriveService.running) "Fahrmodus läuft" else "Fahrmodus manuell starten",
            if (DriveService.running) "Prüft das Wetter auf der Strecke voraus" else "Ohne Google-Maps-Navigation",
            if (DriveService.running) "Beenden" else "Starten") {
            if (DriveService.running) DriveService.stop(this)
            else if (!Locator.hasPermission(this)) requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
            else DriveService.start(this, auto = false)
            root.postDelayed({ build() }, 600)
        })
        add(card(*drive.toTypedArray()), 10)
        add(note("Routengenau: In Google Maps das Ziel antippen → Teilen → „Wetter-Fahrmodus“. " +
            "Dann prüft die App die ganze Strecke bis zum Ziel." +
            if (!access && Prefs.driveAuto(this)) "\nIst der Schalter beim Benachrichtigungszugriff ausgegraut: Einstellungen → Apps → Wetter → ⋮ → " +
                "„Eingeschränkte Einstellungen zulassen“." else ""), 24)

        // ------------------------------------------------------ Unwetterwarnungen
        add(section("Unwetterwarnungen"), 8)
        val on = Prefs.warnEnabled(this)
        val rows = ArrayList<View>()
        rows.add(toggleRow("Benachrichtigungen", "Amtliche DWD-Warnungen – auch auf der Garmin-Uhr", on) {
            Prefs.setWarnEnabled(this, it)
            if (it) {
                requestNotifications()
                WarnScheduler.schedule(this, 1)
            } else WarnScheduler.cancel(this)
            build()
        })
        if (on) {
            rows.add(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, dp(14), 0, dp(14))
                addView(Ui.tv(this@SettingsActivity, "Melden ab Stufe", 16f, p.text, true))
                addView(pillRow(listOf("Gelb" to 1, "Orange" to 2, "Rot" to 3), Prefs.warnMinLevel(this@SettingsActivity), colored = true) { v ->
                    Prefs.setWarnMinLevel(this@SettingsActivity, v); build()
                }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
                addView(Ui.tv(this@SettingsActivity, levelHint(Prefs.warnMinLevel(this@SettingsActivity)), 13f, p.muted).apply { setLineSpacing(0f, 1.2f) },
                    LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
            })
            rows.add(toggleRow("Aktueller Standort", "Letzte bekannte Position des Handys", Prefs.warnLocation(this)) {
                Prefs.setWarnLocation(this, it)
            })
            val favIds = Prefs.warnFavorites(this)
            for (f in Prefs.favorites(this)) {
                rows.add(toggleRow(f.name, f.region.ifEmpty { "Favorit" }, f.id in favIds) { Prefs.setWarnFavorite(this, f.id, it) })
            }
            rows.add(toggleRow("Nachtruhe 22–7 Uhr", "Rot und Violett kommen immer durch", Prefs.warnQuiet(this)) {
                Prefs.setWarnQuiet(this, it)
            })
            rows.add(actionRow("Test-Benachrichtigung senden", "Zum Einrichten der Garmin-Uhr", "Senden") {
                if (!WarnCheck.canNotify(this)) requestNotifications()
                else {
                    WarnCheck.test(this)
                    Toast.makeText(this, "Test gesendet", Toast.LENGTH_SHORT).show()
                }
            })
        }
        add(card(*rows.toTypedArray()), 10)
        if (on) add(note("Garmin: In der Garmin-Connect-App die Benachrichtigungen für „Wetter“ freigeben. " +
            "Die App prüft stündlich, bei Gewitterlage alle 15 Minuten."), 24)
        else add(View(this), 14)

        // ---------------------------------------------------------- Berechtigungen
        add(section("Berechtigungen"), 8)
        val perm = ArrayList<View>()
        if (Build.VERSION.SDK_INT >= 33) {
            val ok = WarnCheck.canNotify(this)
            perm.add(statusRow("Benachrichtigungen", if (ok) "Erlaubt" else "Nicht erlaubt – nötig für Warnungen", ok) { requestNotifications() })
        }
        val fg = Locator.hasPermission(this)
        perm.add(statusRow("Standort", if (fg) "Erlaubt" else "Nicht erlaubt", fg) {
            requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)
        })
        if (Build.VERSION.SDK_INT >= 29) {
            val bg = checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED
            perm.add(statusRow("Standort im Hintergrund",
                if (bg) "Erlaubt – Warnungen folgen dir unterwegs" else "Für Warnungen unterwegs: „Immer erlauben“ wählen", bg) {
                if (!fg) requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), 1)
                else requestPermissions(arrayOf(Manifest.permission.ACCESS_BACKGROUND_LOCATION), 2)
            })
        }
        add(card(*perm.toTypedArray()), 24)

        add(note("Daten: Open-Meteo (DWD ICON-D2, ECMWF IFS), Deutscher Wetterdienst (Radar, Warnungen). " +
            "Karten © OpenStreetMap-Mitwirkende."), 0)
    }

    private fun levelHint(l: Int) = when (l) {
        1 -> "Alle Warnungen, auch leichter Frost oder Wind – kann häufig sein."
        2 -> "Markantes Wetter: z. B. Gewitter, Sturmböen, Glätte, starker Regen."
        else -> "Nur Unwetter: z. B. schwere Gewitter, Hagel, Orkanböen."
    }

    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && !WarnCheck.canNotify(this)) {
            if (Prefs.askedNotifications(this) && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) {
                // Schon abgelehnt → Systemeinstellungen öffnen
                startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
            } else {
                Prefs.setAskedNotifications(this)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 3)
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.none { it == PackageManager.PERMISSION_GRANTED } && requestCode == 2) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)))
        }
        build()
    }

    // ----------------------------------------------------------------- Bausteine

    private fun section(t: String) = Ui.tv(this, t.uppercase(), 12.5f, p.muted, true).apply {
        letterSpacing = 0.08f
        setPadding(dp(6), 0, 0, 0)
    }

    private fun note(t: String) = Ui.tv(this, t, 12.5f, p.muted).apply {
        setLineSpacing(0f, 1.25f)
        setPadding(dp(6), 0, dp(6), 0)
    }

    private fun card(vararg rows: View) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rect(p.surface, dpf(24), p.border, 1)
        setPadding(dp(18), dp(4), dp(18), dp(4))
        for ((i, r) in rows.withIndex()) {
            if (i > 0) addView(View(this@SettingsActivity).apply { setBackgroundColor(p.border) }, LinearLayout.LayoutParams(MATCH, 1))
            addView(r)
        }
    }

    private fun textCol(title: String, sub: String) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        addView(Ui.tv(this@SettingsActivity, title, 16f, p.text, true))
        if (sub.isNotEmpty()) addView(Ui.tv(this@SettingsActivity, sub, 13f, p.muted).apply { setLineSpacing(0f, 1.15f) },
            LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
    }

    private fun row(left: View, right: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(14), 0, dp(14))
        addView(left, LinearLayout.LayoutParams(0, WRAP, 1f).apply { rightMargin = dp(12) })
        addView(right)
    }

    private fun toggleRow(title: String, sub: String, value: Boolean, onChange: (Boolean) -> Unit): View {
        val t = ToggleView(this, p.accent, p.trackOff)
        t.setState(value, animate = false)
        t.onChange = onChange
        return row(textCol(title, sub), t).apply { setOnClickListener { t.setState(!t.isOn, animate = true, notify = true) } }
    }

    private fun pill(text: String, bg: Int, fg: Int, onClick: () -> Unit): TextView = Ui.tv(this, text, 14f, fg, true).apply {
        gravity = Gravity.CENTER
        setPadding(dp(16), dp(9), dp(16), dp(9))
        background = Ui.pressable(p, bg, dpf(20))
        setOnClickListener { onClick() }
    }

    private fun actionRow(title: String, sub: String, button: String, onClick: () -> Unit): View =
        row(textCol(title, sub), pill(button, p.surfaceHi, p.text, onClick))

    private fun statusRow(title: String, sub: String, ok: Boolean, onFix: () -> Unit): View {
        val right: View = if (ok) Ui.tv(this, "✓", 20f, p.accent, true)
        else pill("Erlauben", p.accent, p.onAccent, onFix)
        return row(textCol(title, sub), right)
    }

    private fun pillRow(items: List<Pair<String, Int>>, selected: Int, colored: Boolean = false, onPick: (Int) -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(12), 0, dp(12))
            for ((i, it) in items.withIndex()) {
                val sel = it.second == selected
                val base = if (colored) Warning.color(it.second) else p.accent
                val bg = if (sel) base else p.surfaceHi
                val fg = if (sel) (if (colored) 0xFF15171A.toInt() else p.onAccent) else p.text
                addView(pill(it.first, bg, fg) { onPick(it.second) },
                    LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) leftMargin = dp(8) })
            }
        }
}
