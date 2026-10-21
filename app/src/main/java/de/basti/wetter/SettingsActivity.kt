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
        scrollView = ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(root)
        }
        Ui.applyBackground(this, scrollView, p)
        setContentView(scrollView)
    }

    private lateinit var scrollView: ScrollView
    private var styleOpen = false

    /** Aussehen geändert: Palette neu bestimmen und alles neu zeichnen (ohne Neustart der Seite). */
    private fun restyle() {
        p = Ui.palette(this)
        Ui.applySystemBars(this, p)
        Ui.applyBackground(this, scrollView, p)
        val y = scrollView.scrollY
        build()
        scrollView.post { scrollView.scrollTo(0, y) }
    }

    private val REQ_IMAGE = 41

    private fun pickImage() {
        val i = if (Build.VERSION.SDK_INT >= 33) Intent("android.provider.action.PICK_IMAGES").setType("image/*")
        else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        runCatching { startActivityForResult(i, REQ_IMAGE) }.onFailure {
            Toast.makeText(this, "Keine Bildauswahl verfügbar", Toast.LENGTH_SHORT).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_IMAGE || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        Toast.makeText(this, "Bild wird übernommen …", Toast.LENGTH_SHORT).show()
        Thread {
            val ok = runCatching { BgImage.import(this, uri) }.getOrDefault(false)
            runOnUiThread {
                if (ok) { Prefs.setStyle(this, "st_bg", Ui.BG_IMAGE); restyle() }
                else Toast.makeText(this, "Bild konnte nicht geladen werden", Toast.LENGTH_SHORT).show()
            }
        }.start()
    }

    /** Aufklappbarer Bereich "Anpassen": Vorschau, Akzentfarbe, Hintergrund, Schrift, Ecken. */
    private fun styleRows(): List<View> {
        val rows = ArrayList<View>()
        rows.add(row(textCol("Anpassen", "Farbe, Hintergrund, Schrift, Ecken"),
            Ui.tv(this, if (styleOpen) "⌃" else "›", 22f, p.muted, true)).apply {
            setOnClickListener { styleOpen = !styleOpen; build() }
        })
        if (!styleOpen) return rows

        // Mini-Vorschau
        rows.add(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(14), dp(14), dp(14))
            background = Ui.background(this@SettingsActivity, p).let { d ->
                if (d is android.graphics.drawable.ColorDrawable) Ui.rect(p.bg, dpf(18), p.border, 1) else d
            }
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) =
                    outline.setRoundRect(0, 0, view.width, view.height, dpf(18) * Ui.cornerScale)
            }
            addView(Ui.tv(this@SettingsActivity, "Vorschau", 20f, p.text, true))
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.rect(p.surface, dpf(20), p.border, 1)
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(Ui.tv(this@SettingsActivity, "14°", 34f, p.text).apply { typeface = Ui.TF_LIGHT })
                    addView(Ui.tv(this@SettingsActivity, "Teils bewölkt", 13f, p.muted))
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(pill("Aktiv", p.accent, p.onAccent) {})
                addView(pill("Knopf", p.surfaceHi, p.text) {}, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4); bottomMargin = dp(12) }
        })

        // Akzentfarbe: Farbtupfer
        rows.add(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            addView(Ui.tv(this@SettingsActivity, "Akzentfarbe", 16f, p.text, true))
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(0, dp(12), 0, 0)
                val sel = Prefs.accent(this@SettingsActivity)
                for ((i, a) in Ui.ACCENTS.withIndex()) {
                    val c = if (p.dark) a.dark else a.light
                    addView(View(this@SettingsActivity).apply {
                        background = android.graphics.drawable.GradientDrawable().apply {
                            shape = android.graphics.drawable.GradientDrawable.OVAL
                            setColor(c)
                            if (i == sel) setStroke(dp(3), p.text)
                        }
                        contentDescription = a.name
                        setOnClickListener { Prefs.setStyle(this@SettingsActivity, "st_accent", i); restyle() }
                    }, LinearLayout.LayoutParams(0, dp(30), 1f).apply { if (i > 0) leftMargin = dp(8) })
                }
            })
        })

        // Hintergrund
        val bgStyle = Prefs.bgStyle(this)
        rows.add(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(12))
            addView(Ui.tv(this@SettingsActivity, "Hintergrund", 16f, p.text, true))
            addView(pillRow(listOf("Neutral" to Ui.BG_NEUTRAL, "Verlauf" to Ui.BG_GRADIENT, "Eigenes Bild" to Ui.BG_IMAGE), bgStyle) { v ->
                if (v == Ui.BG_IMAGE && !BgImage.exists(this@SettingsActivity)) pickImage()
                else { Prefs.setStyle(this@SettingsActivity, "st_bg", v); restyle() }
            })
            // Ton (für Neutral und Verlauf; bei Bild die Abdunkelungsfarbe)
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                val sel = Prefs.bgTone(this@SettingsActivity)
                for ((i, t) in Ui.TONES.withIndex()) {
                    val c = if (p.dark) t.darkSurface else t.lightBg
                    addView(LinearLayout(this@SettingsActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        gravity = Gravity.CENTER_HORIZONTAL
                        addView(View(this@SettingsActivity).apply {
                            background = Ui.rect(c, dpf(10), if (i == sel) p.accent else p.border, if (i == sel) dp(3) else 1)
                        }, LinearLayout.LayoutParams(MATCH, dp(30)))
                        addView(Ui.tv(this@SettingsActivity, t.name, 10.5f, if (i == sel) p.text else p.muted).apply {
                            gravity = Gravity.CENTER; maxLines = 1
                        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
                        setOnClickListener { Prefs.setStyle(this@SettingsActivity, "st_tone", i); restyle() }
                    }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) leftMargin = dp(6) })
                }
            })
            if (bgStyle == Ui.BG_IMAGE) {
                addView(Ui.tv(this@SettingsActivity, "Bild sichtbar", 14f, p.text, true), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(14) })
                addView(android.widget.SeekBar(this@SettingsActivity).apply {
                    max = 100
                    progress = Prefs.bgVisibility(this@SettingsActivity)
                    progressTintList = android.content.res.ColorStateList.valueOf(p.accent)
                    thumbTintList = android.content.res.ColorStateList.valueOf(p.accent)
                    setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                        override fun onProgressChanged(s: android.widget.SeekBar?, v: Int, fromUser: Boolean) {}
                        override fun onStartTrackingTouch(s: android.widget.SeekBar?) {}
                        override fun onStopTrackingTouch(s: android.widget.SeekBar?) {
                            Prefs.setStyle(this@SettingsActivity, "st_vis", s?.progress ?: 50); restyle()
                        }
                    })
                }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
                addView(LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    addView(pill("Anderes Bild", p.surfaceHi, p.text) { pickImage() })
                    addView(pill("Bild entfernen", p.surfaceHi, p.text) {
                        BgImage.delete(this@SettingsActivity)
                        Prefs.setStyle(this@SettingsActivity, "st_bg", Ui.BG_NEUTRAL); restyle()
                    }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
                }, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(8) })
                addView(Ui.tv(this@SettingsActivity, "Das Bild wird weichgezeichnet und automatisch so abgedunkelt, dass alles lesbar bleibt.", 12.5f, p.muted),
                    LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })
            }
        })

        // Schrift
        rows.add(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(4))
            addView(Ui.tv(this@SettingsActivity, "Schrift", 16f, p.text, true))
            addView(pillRow(listOf("Modern" to 0, "Kompakt" to 1, "Rund" to 2), Prefs.fontStyle(this@SettingsActivity)) { v ->
                Prefs.setStyle(this@SettingsActivity, "st_fstyle", v); restyle()
            })
            addView(pillRow(listOf("Klein" to 0, "Normal" to 1, "Groß" to 2), Prefs.fontSize(this@SettingsActivity)) { v ->
                Prefs.setStyle(this@SettingsActivity, "st_fsize", v); restyle()
            })
        })

        // Ecken
        rows.add(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(12), 0, dp(4))
            addView(Ui.tv(this@SettingsActivity, "Ecken von Karten und Knöpfen", 16f, p.text, true))
            addView(pillRow(listOf("Rund" to 0, "Weich" to 1, "Eckig" to 2), Prefs.corners(this@SettingsActivity)) { v ->
                Prefs.setStyle(this@SettingsActivity, "st_corners", v); restyle()
            })
        })

        rows.add(actionRow("Zurücksetzen", "Ursprüngliches Aussehen wiederherstellen", "Zurücksetzen") {
            Prefs.resetStyle(this); restyle()
        })
        return rows
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
            restyle()
        }, *styleRows().toTypedArray()), 24)

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
