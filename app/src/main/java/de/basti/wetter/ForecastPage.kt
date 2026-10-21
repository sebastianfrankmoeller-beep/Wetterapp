package de.basti.wetter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.roundToInt

/** Großes Wettersymbol als eigene View. */
@SuppressLint("ViewConstructor")
class IconView(ctx: Context, private val p: Ui.Palette, private val sizeDp: Int) : View(ctx) {
    var code = 3
    var isDay = true
    fun set(code: Int, isDay: Boolean) { this.code = code; this.isDay = isDay; invalidate() }
    override fun onMeasure(w: Int, h: Int) {
        val s = Ui.dp(context, sizeDp)
        setMeasuredDimension(s, s)
    }
    override fun onDraw(c: Canvas) = WeatherIcons.draw(c, code, isDay, width / 2f, height / 2f, width.toFloat(), p)
}

/**
 * Vorhersage-Seite: Ortsleiste mit Favoriten, aktuelle Werte,
 * Regen der nächsten Stunden, stündlicher Verlauf und 15 Tage.
 */
@SuppressLint("ViewConstructor")
class ForecastPage(ctx: Context, private val p: Ui.Palette, private val cb: Callbacks) : FrameLayout(ctx) {

    interface Callbacks {
        fun onSearch()
        fun onSettings()
        fun onToggleFavorite()
        fun onRefresh()
        fun onSelectPlace(id: String)
        fun onLongPressPlace(id: String)
        fun onWarningClick(w: Warning)
    }

    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private fun dp(v: Number) = Ui.dp(context, v)
    private fun dpf(v: Number) = Ui.dpf(context, v)

    val scroll = ScrollView(ctx)
    private val root = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(14), dp(16), dp(28)) }

    private val title = Ui.tv(ctx, "", 28f, p.text, true).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val subtitle = Ui.tv(ctx, "", 13.5f, p.muted)
    private val star = GlyphView(ctx, GlyphView.Kind.STAR, p.muted)
    private val pills = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

    private val bigTemp = Ui.tv(ctx, "–", 76f, p.text).apply { typeface = Ui.TF_LIGHT }
    private val bigIcon = IconView(ctx, p, 92)
    private val desc = Ui.tv(ctx, "", 18f, p.text, true)
    private val feels = Ui.tv(ctx, "", 14f, p.muted)
    private val hints = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val statusActions = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
    private val warnBox = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
    private val stats = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }

    private val nowcastCard: LinearLayout
    private val runCard = RunCard(ctx, p) { rebindRun() }
    private val runSection = section("Lauf-Check · nächste 2 Std.")
    private var lastForecast: Forecast? = null
    private var lastWarnings: List<Warning>? = null
    private val nowcastText = Ui.tv(ctx, "", 19f, p.text, true)
    private val nowcastSub = Ui.tv(ctx, "", 13.5f, p.muted)
    private val nowcastView = NowcastView(ctx, p)

    private val hourly = HourlyView(ctx, p)
    private val hourlyScroll = HorizontalScrollView(ctx).apply {
        isHorizontalScrollBarEnabled = false
        overScrollMode = View.OVER_SCROLL_NEVER
        addView(hourly)
    }
    private val days = DaysView(ctx, p)
    private val footer = Ui.tv(ctx, "", 11.5f, p.muted).apply { setLineSpacing(0f, 1.25f) }
    val status = Ui.tv(ctx, "", 14f, p.muted).apply { gravity = Gravity.CENTER; setPadding(dp(8), dp(10), dp(8), dp(10)) }

    init {
        // Hintergrund kommt vom Fenster (neutral, Verlauf oder eigenes Bild)
        scroll.isVerticalScrollBarEnabled = false
        scroll.addView(root)
        addView(scroll, LayoutParams(MATCH, MATCH))

        fun add(v: View, bottom: Int) = root.addView(v, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(bottom) })

        // ------------------------------------------------------------- Kopfzeile
        val head = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(4), 0, 0, 0)
                addView(title)
                addView(subtitle, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
            }
            addView(col, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(roundButton(star) { cb.onToggleFavorite() })
            addView(roundButton(GlyphView(ctx, GlyphView.Kind.SEARCH, p.text)) { cb.onSearch() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
            addView(roundButton(GlyphView(ctx, GlyphView.Kind.GEAR, p.text)) { cb.onSettings() }, LinearLayout.LayoutParams(WRAP, WRAP).apply { leftMargin = dp(8) })
        }
        add(head, 14)

        // ---------------------------------------------------------- Favoritenleiste
        add(HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            clipToPadding = false
            addView(pills)
        }, 16)

        add(status.apply { visibility = View.GONE }, 8)
        add(statusActions.apply { visibility = View.GONE }, 12)
        add(warnBox.apply { visibility = View.GONE }, 0)

        // ----------------------------------------------------------- Aktuell
        val hero = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rect(p.surface, dpf(24), p.border, 1)
            setPadding(dp(20), dp(16), dp(20), dp(18))
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    addView(bigTemp)
                    addView(desc, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
                    addView(feels, LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(6) })
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(bigIcon)
            })
            addView(stats, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(18) })
            addView(hints, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        add(hero, 14)

        // ------------------------------------------------------------ Lauf-Check
        add(runSection, 8)
        add(runCard, 14)

        // ------------------------------------------------------- Regen-Nowcast
        nowcastCard = card().apply {
            addView(nowcastText)
            addView(nowcastSub, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(5) })
            addView(nowcastView, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        }
        add(nowcastCard, 14)

        // ------------------------------------------------------------ Stündlich
        add(section("Stündlich", "Regen in mm  ·  Wind in km/h"), 8)
        add(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rect(p.surface, dpf(24), p.border, 1)
            setPadding(0, dp(10), 0, dp(6))
            clipToOutline = true
            addView(hourlyScroll)
        }, 22)

        // -------------------------------------------------------------- Tage
        add(section("15 Tage"), 8)
        add(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = Ui.rect(p.surface, dpf(24), p.border, 1)
            setPadding(0, dp(4), 0, dp(4))
            addView(days)
        }, 18)
        days.onDayClick = { i ->
            val f = days.forecast
            if (f != null) {
                days.selected = i
                val x = hourly.xOf(f.days[i].start + if (i == 0) 0 else 7 * 3600)
                hourlyScroll.smoothScrollTo(x, 0)
                // Stundenstreifen in Sicht bringen
                scroll.smoothScrollTo(0, (hourlyScroll.parent as View).top - dp(40))
            }
        }

        add(footer.apply { setPadding(dp(6), 0, dp(6), 0) }, 0)
    }

    private fun section(t: String) = Ui.tv(context, t.uppercase(), 12.5f, p.muted, true).apply {
        letterSpacing = 0.08f
        setPadding(dp(6), 0, 0, 0)
    }

    /** Abschnittstitel mit kleinem Einheiten-Hinweis dahinter. */
    private fun section(t: String, hint: String) = section(t).apply {
        val title = t.uppercase()
        val sp = android.text.SpannableString("$title    $hint")
        val start = title.length + 4
        sp.setSpan(android.text.style.RelativeSizeSpan(0.88f), start, sp.length, 0)
        sp.setSpan(android.text.style.TypefaceSpan("sans-serif"), start, sp.length, 0)
        text = sp
    }

    private fun card() = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        background = Ui.rect(p.surface, dpf(24), p.border, 1)
        setPadding(dp(18), dp(16), dp(18), dp(12))
    }

    private fun roundButton(g: GlyphView, onClick: () -> Unit): View = FrameLayout(context).apply {
        background = Ui.pressable(p, p.surface, dpf(22), p.border, 1)
        val s = dp(44)
        layoutParams = LinearLayout.LayoutParams(s, s)
        addView(g, LayoutParams(dp(22), dp(22), Gravity.CENTER))
        setOnClickListener { onClick() }
    }

    private fun statTile(label: String, value: String, sub: String = ""): View = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        addView(Ui.tv(context, label, 11.5f, p.muted))
        addView(Ui.tv(context, value, 16f, p.text, true), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(5) })
        if (sub.isNotEmpty()) addView(Ui.tv(context, sub, 11.5f, p.muted), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(3) })
    }

    // ------------------------------------------------------------- Ortsleiste

    class PillItem(val id: String, val label: String, val selected: Boolean, val isLocation: Boolean)

    fun setPills(items: List<PillItem>) {
        pills.removeAllViews()
        for ((i, it) in items.withIndex()) {
            val fg = if (it.selected) p.onAccent else p.text
            val pill = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(if (it.isLocation) 12 else 16), dp(9), dp(16), dp(9))
                background = if (it.selected) Ui.pressable(p, p.accent, dpf(20))
                else Ui.pressable(p, p.surface, dpf(20), p.border, 1)
                if (it.isLocation) addView(GlyphView(context, GlyphView.Kind.PIN, fg, 16), LinearLayout.LayoutParams(dp(16), dp(16)).apply { rightMargin = dp(6) })
                addView(Ui.tv(context, it.label, 14f, fg, true).apply { maxLines = 1 })
                setOnClickListener { _ -> cb.onSelectPlace(it.id) }
                if (!it.isLocation) setOnLongClickListener { _ -> cb.onLongPressPlace(it.id); true }
            }
            pills.addView(pill, LinearLayout.LayoutParams(WRAP, WRAP).apply { if (i > 0) leftMargin = dp(8) })
        }
    }

    // ------------------------------------------------------------- Kopfzeile

    fun setHeader(name: String, region: String, isFavorite: Boolean, canFavorite: Boolean) {
        title.text = name
        subtitle.text = region
        subtitle.visibility = if (region.isEmpty()) View.GONE else View.VISIBLE
        star.set(if (isFavorite) GlyphView.Kind.STAR_FILLED else GlyphView.Kind.STAR, if (isFavorite) p.warn else p.text)
        (star.parent as View).visibility = if (canFavorite) View.VISIBLE else View.GONE
    }

    fun showStatus(msg: String?) = showStatus(msg, emptyList())

    /** Hinweis oben mit optionalen Knöpfen (z. B. "Erneut versuchen", "Ort suchen"). */
    fun showStatus(msg: String?, actions: List<Pair<String, () -> Unit>>) {
        status.text = msg ?: ""
        status.visibility = if (msg.isNullOrEmpty()) View.GONE else View.VISIBLE
        statusActions.removeAllViews()
        for ((i, a) in actions.withIndex()) {
            statusActions.addView(Ui.tv(context, a.first, 14f, if (i == 0) p.onAccent else p.text, true).apply {
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(10), dp(16), dp(10))
                background = Ui.pressable(p, if (i == 0) p.accent else p.surfaceHi, dpf(20))
                setOnClickListener { a.second() }
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (i > 0) leftMargin = dp(8) })
        }
        statusActions.visibility = if (actions.isEmpty() || msg.isNullOrEmpty()) View.GONE else View.VISIBLE
    }

    // ------------------------------------------------------------- Inhalt

    fun bind(f: Forecast) {
        val now = f.now ?: return
        bigTemp.text = Fmt.temp(now.temp)
        bigIcon.set(now.code, now.isDay)
        desc.text = Fmt.codeAt(now.code, now.isDay)
        val today = f.days.firstOrNull()
        val hiLo = if (today != null) "  ·  ↑ ${Fmt.temp(today.max)}  ↓ ${Fmt.temp(today.min)}" else ""
        feels.text = "Gefühlt ${Fmt.temp(now.feels)}$hiLo"

        stats.removeAllViews()
        // Liegt Schnee, ersetzt die Schneehöhe den Luftdruck
        val lastTile = if (!now.snowDepth.isNaN() && now.snowDepth >= 1)
            statTile("Schneehöhe", "${now.snowDepth.roundToInt()} cm")
        else statTile("Luftdruck", if (now.pressure.isNaN()) "–" else "${now.pressure.roundToInt()}", "hPa")
        val tiles = listOf(
            statTile("Wind", "${Fmt.kmh(now.wind)} km/h", Fmt.dir(now.windDir)),
            statTile("Böen", "${Fmt.kmh(now.gust)} km/h"),
            statTile("Feuchte", if (now.humidity.isNaN()) "–" else "${now.humidity.roundToInt()} %"),
            lastTile
        )
        for (t in tiles) stats.addView(t, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))

        // Hinweise: Gewitterrisiko und Schneefallgrenze in den nächsten 24 Stunden
        hints.removeAllViews()
        val next = f.hours.take(24)
        val maxThunder = next.maxOfOrNull { it.thunder } ?: 0
        if (maxThunder >= 1) {
            val first = next.first { it.thunder == maxThunder }
            val start = next.first { it.thunder >= 1 }
            val whenTxt = if (start === f.hours.first()) "jetzt" else "ab ${f.hhmm(start.t)} Uhr"
            val peak = if (first !== start) "  ·  am stärksten ${f.hhmm(first.t)} Uhr" else ""
            hints.addView(hint(GlyphView.Kind.BOLT, thunderColor(maxThunder, p),
                "Gewitterrisiko ${Thunder.NAMES[maxThunder]} $whenTxt$peak"))
        }
        val wetCold = next.filter { it.precip >= 0.1 && !it.snowLine.isNaN() && it.snowLine < 1500 }
        if (wetCold.isNotEmpty()) {
            val line = (wetCold.map { it.snowLine }.average() / 50).roundToInt() * 50
            hints.addView(hint(GlyphView.Kind.SNOW, p.snow, "Schneefallgrenze um $line m"))
        } else if (next.sumOf { it.snow } >= 0.5) {
            hints.addView(hint(GlyphView.Kind.SNOW, p.snow, "Neuschnee bis ${Fmt.mm(next.sumOf { it.snow })} cm in 24 h"))
        }
        hints.visibility = if (hints.childCount > 0) View.VISIBLE else View.GONE

        // Regen der nächsten Stunden (DWD-Radar + Nowcast, danach ICON-D2; nur im D2-/Radargebiet)
        val nc = f.nowcast.take(13)
        if (nc.size >= 4) {
            nowcastCard.visibility = View.VISIBLE
            nowcastView.forecast = f
            nowcastView.snowy = f.nowcastSnow
            nowcastView.radarUntil = f.nowcastRadarUntil
            nowcastView.data = nc
            val (head, sub) = nowcastSummary(f, nc)
            nowcastText.text = head
            nowcastSub.text = sub
            nowcastSub.visibility = if (sub.isEmpty()) View.GONE else View.VISIBLE
        } else nowcastCard.visibility = View.GONE

        lastForecast = f
        rebindRun()

        hourly.forecast = f
        days.selected = -1
        days.forecast = f
        hourlyScroll.scrollTo(0, 0)

        val models = if (f.hasD2) "DWD ICON-D2 (2 km) bis 48 h, danach ECMWF IFS (9 km)" else "ECMWF IFS (9 km)"
        footer.text = "Modelle: $models\nDaten: Open-Meteo · Stand ${f.hhmmLocal(f.fetchedAt)}"
    }

    /** Lauf-Check neu berechnen (neue Daten, Warnungen oder Läufertyp). */
    fun rebindRun() {
        val f = lastForecast ?: return
        val r = RunCheck.evaluate(f, Prefs.runnerType(context), lastWarnings)
        runCard.bind(f, r)
        runSection.visibility = runCard.visibility
    }

    /** Zum Lauf-Check scrollen (App-Verknüpfung). */
    fun scrollToRun() {
        scroll.post { scroll.smoothScrollTo(0, (runSection.top - dp(8)).coerceAtLeast(0)) }
    }

    private fun hint(kind: GlyphView.Kind, color: Int, txt: String): View = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(14), 0, 0)
        addView(GlyphView(context, kind, color, 18), LinearLayout.LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(10) })
        addView(Ui.tv(context, txt, 14f, p.text, true), LinearLayout.LayoutParams(0, WRAP, 1f))
    }

    // ------------------------------------------------------------ Warnungen

    /** Amtliche DWD-Warnungen als farbige Karten oben; null/leer blendet sie aus. */
    fun setWarnings(list: List<Warning>?) {
        lastWarnings = list
        rebindRun()
        warnBox.removeAllViews()
        if (list.isNullOrEmpty()) { warnBox.visibility = View.GONE; return }
        warnBox.visibility = View.VISIBLE
        for (w in list.take(4)) {
            val col = Warning.color(w.level)
            val card = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = Ui.pressable(p, Ui.mix(p.surface, col, if (p.dark) 0.16f else 0.12f), dpf(20), Ui.withAlpha(col, 150), dp(1))
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(GlyphView(context, GlyphView.Kind.WARNING, col, 24), LinearLayout.LayoutParams(dp(24), dp(24)).apply { rightMargin = dp(12) })
                addView(LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    val kind = if (w.future) "VORABINFORMATION" else Warning.LEVEL_NAMES[w.level].uppercase()
                    addView(Ui.tv(context, "$kind · ${Warning.COLOR_NAMES[w.level].uppercase()}", 11f, col, true).apply { letterSpacing = 0.06f })
                    addView(Ui.tv(context, w.event.split(" ").joinToString(" ") { wd -> wd.lowercase().replaceFirstChar { it.uppercase() } }, 16f, p.text, true),
                        LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
                    addView(Ui.tv(context, w.timeRange(), 13f, p.muted), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                setOnClickListener { cb.onWarningClick(w) }
            }
            warnBox.addView(card, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(10) })
        }
        if (list.size > 4) {
            warnBox.addView(Ui.tv(context, "+ ${list.size - 4} weitere Warnungen", 13f, p.muted).apply { setPadding(dp(8), 0, 0, dp(10)) })
        }
    }

}

/** Uhrzeit (lokal am Ort) aus Millisekunden. */
fun Forecast.hhmmLocal(ms: Long): String = hhmm(ms / 1000)

/** Überschrift und Unterzeile der Regenkarte. */
fun nowcastSummary(f: Forecast, nc: List<Pair<Long, Double>>): Pair<String, String> {
    val what = if (f.nowcastSnow) "Schnee" else "Regen"
    val wet = { v: Double -> v * 4 >= 0.1 }        // ab 0,1 mm/h
    val hours = ((nc.last().first - nc.first().first) / 3600.0 + 0.25).roundToInt()
    val firstWet = nc.indexOfFirst { wet(it.second) }
    if (firstWet < 0) return "Kein $what in den nächsten $hours Std." to ""
    val now = System.currentTimeMillis() / 1000
    fun mins(t: Long) = ((t - now) / 60).toInt().coerceAtLeast(0)
    fun rel(t: Long): String {
        val m = mins(t)
        return if (m < 60) "$m Min." else {
            val h = m / 60; val r = (m % 60 + 7) / 15 * 15
            if (r == 0 || r == 60) "${if (r == 60) h + 1 else h} Std." else "$h Std. $r Min."
        }
    }
    // Zusammenhängende nasse Phase ab dem ersten nassen Wert
    var lastWet = firstWet
    while (lastWet + 1 < nc.size && wet(nc[lastWet + 1].second)) lastWet++
    val phase = nc.subList(firstWet, lastWet + 1)
    val sum = phase.sumOf { it.second }
    val strength = RainRate.NAMES[RainRate.level(phase.maxOf { it.second } * 4)]
    val endTxt = if (lastWet + 1 < nc.size) "bis ca. ${f.hhmm(nc[lastWet + 1].first)}" else "hält an"
    val amount = if (f.nowcastSnow) "${Fmt.mm(sum / 0.7)} cm" else "${Fmt.mm(sum)} mm"
    return if (firstWet == 0) {
        val head = if (lastWet + 1 < nc.size) "$what hört in ${rel(nc[lastWet + 1].first)} auf" else "$what hält an"
        head to "$strength  ·  $endTxt  ·  $amount"
    } else {
        "$what in ${rel(nc[firstWet].first)}" to "$strength  ·  ab ${f.hhmm(nc[firstWet].first)}  ·  $endTxt  ·  $amount"
    }
}
