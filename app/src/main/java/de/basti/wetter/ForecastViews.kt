package de.basti.wetter

import android.annotation.SuppressLint
import kotlin.math.sqrt
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Farbe der Gewitterstufe (gering / mäßig / hoch). */
fun thunderColor(level: Int, p: Ui.Palette): Int = when (level) {
    1 -> p.sun
    2 -> 0xFFFF8A3D.toInt()
    else -> p.danger
}

/** Temperaturfarbe von kalt (blau) über mild (grün) zu heiß (orange/rot). */
fun tempColor(t: Double, p: Ui.Palette): Int {
    if (t.isNaN()) return p.muted
    val stops = listOf(
        -15.0 to 0xFF7A5CFF.toInt(),
        -5.0 to 0xFF4EA8FF.toInt(),
        5.0 to 0xFF46D2E0.toInt(),
        13.0 to 0xFF5BE3A8.toInt(),
        21.0 to 0xFFF5D547.toInt(),
        28.0 to 0xFFFF8A4C.toInt(),
        36.0 to 0xFFFF4F5E.toInt()
    )
    if (t <= stops.first().first) return stops.first().second
    for (i in 1 until stops.size) {
        val (t1, c1) = stops[i]
        val (t0, c0) = stops[i - 1]
        if (t <= t1) return Ui.mix(c0, c1, ((t - t0) / (t1 - t0)).toFloat())
    }
    return stops.last().second
}

/**
 * Stündliche Vorhersage als waagerechter Streifen:
 * Uhrzeit, Symbol, Temperaturkurve, Niederschlagsbalken, Wind.
 * Liegt in einer HorizontalScrollView; gezeichnet wird nur der sichtbare Teil.
 */
class HourlyView(ctx: Context, private val p: Ui.Palette) : View(ctx) {

    var forecast: Forecast? = null
        set(v) { field = v; requestLayout(); invalidate() }

    private val d = resources.displayMetrics.density
    val colW = 58 * d
    private val h = 230 * d

    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dash = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        pathEffect = DashPathEffect(floatArrayOf(4 * d, 4 * d), 0f)
    }
    private val clip = Rect()
    private val curve = Path()

    // Zeilen (y-Positionen)
    private val yDay = 12 * d
    private val yHour = 34 * d
    private val yIcon = 62 * d
    private val iconSize = 36 * d
    private val tempTop = 104 * d
    private val tempBottom = 142 * d
    private val barBottom = 182 * d
    private val barMax = 26 * d
    private val yProb = 197 * d
    private val yWind = 220 * d

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val n = forecast?.hours?.size ?: 0
        setMeasuredDimension(max(1, (n * colW).toInt()), h.toInt())
    }

    /** x-Position (linker Rand) der Spalte zum Zeitpunkt t. */
    fun xOf(t: Long): Int {
        val hs = forecast?.hours ?: return 0
        val i = hs.indexOfFirst { it.t >= t }.let { if (it < 0) hs.size - 1 else it }
        return (i * colW).toInt()
    }

    override fun onDraw(c: Canvas) {
        val f = forecast ?: return
        val hs = f.hours
        if (hs.isEmpty()) return
        c.getClipBounds(clip)
        val first = max(0, (clip.left / colW).toInt() - 1)
        val last = min(hs.size - 1, (clip.right / colW).toInt() + 1)

        val minT = hs.minOf { it.temp }
        val maxT = hs.maxOf { it.temp }
        val span = max(4.0, maxT - minT)
        fun ty(t: Double) = (tempBottom - (t - minT) / span * (tempBottom - tempTop)).toFloat()

        // Tagestrenner und Modellwechsel
        for (i in first..last) {
            val hr = hs[i]
            val x = i * colW
            val localHour = f.hour(hr.t)
            if (i == 0 || localHour == 0) {
                if (i > 0) {
                    dash.color = p.border; dash.strokeWidth = 1 * d
                    c.drawLine(x, 4 * d, x, h - 4 * d, dash)
                }
                text.textAlign = Paint.Align.LEFT
                text.textSize = 11 * d
                text.color = p.muted
                text.typeface = Ui.TF_MEDIUM
                val label = if (i == 0) "Heute" else "${f.weekdayShort(hr.t)} ${f.dateShort(hr.t)}"
                c.drawText(label, x + 6 * d, yDay + 4 * d, text)
                text.textAlign = Paint.Align.CENTER
            }
            if (i > 0 && hs[i - 1].fromD2 && !hr.fromD2) {
                dash.color = Ui.withAlpha(p.accent, 120); dash.strokeWidth = 1.2f * d
                c.drawLine(x, 22 * d, x, h - 4 * d, dash)
                text.textAlign = Paint.Align.LEFT
                text.textSize = 9.5f * d
                text.color = p.accent
                c.drawText("ECMWF", x + 4 * d, barBottom - barMax - 6 * d, text)
                text.textAlign = Paint.Align.CENTER
            }
        }

        // Temperaturkurve mit Farbverlauf
        curve.reset()
        for (i in first..last) {
            val x = i * colW + colW / 2
            val y = ty(hs[i].temp)
            if (i == first) curve.moveTo(x, y) else {
                val px = (i - 1) * colW + colW / 2
                val py = ty(hs[i - 1].temp)
                val mx = (px + x) / 2
                curve.cubicTo(mx, py, mx, y, x, y)
            }
        }
        line.strokeWidth = 2.6f * d
        line.shader = LinearGradient(0f, tempTop, 0f, tempBottom, tempColor(maxT, p), tempColor(minT, p), Shader.TileMode.CLAMP)
        c.drawPath(curve, line)
        line.shader = null

        for (i in first..last) {
            val hr = hs[i]
            val cx = i * colW + colW / 2

            // Uhrzeit
            text.textSize = 13 * d
            text.typeface = Ui.TF_MEDIUM
            text.color = if (i == 0) p.accent else p.text
            c.drawText(if (i == 0) "Jetzt" else "%02d".format(f.hour(hr.t)), cx, yHour, text)

            // Symbol (+ Blitz-Abzeichen bei Gewitterrisiko)
            WeatherIcons.draw(c, hr.code, hr.isDay, cx, yIcon, iconSize, p)
            if (hr.thunder >= 1) WeatherIcons.boltBadge(c, cx + 15 * d, yIcon - 12 * d, 13 * d, thunderColor(hr.thunder, p), p.surface)

            // Temperaturpunkt + Wert
            val y = ty(hr.temp)
            fill.color = tempColor(hr.temp, p)
            c.drawCircle(cx, y, 3.2f * d, fill)
            text.textSize = 13.5f * d
            text.color = p.text
            c.drawText(Fmt.temp(hr.temp), cx, y - 8 * d, text)

            // Niederschlag: Balken (voll bei 4 mm/h) + Menge in mm darüber
            if (hr.precip >= 0.05) {
                val bh = max(3 * d, (min(hr.precip, 4.0) / 4.0 * barMax).toFloat())
                fill.color = if (hr.snow > 0.05) p.snow else p.rain
                c.drawRoundRect(RectF(cx - 11 * d, barBottom - bh, cx + 11 * d, barBottom), 3 * d, 3 * d, fill)
                text.textSize = 10.5f * d
                text.typeface = Ui.TF_MEDIUM
                text.color = if (hr.snow > 0.05) p.snow else p.rain
                c.drawText(Fmt.mm(hr.precip), cx, barBottom - bh - 4 * d, text)
            }
            fill.color = p.border
            c.drawRect(cx - colW / 2, barBottom, cx + colW / 2, barBottom + 1 * d, fill)
            // Wahrscheinlichkeit immer in eigener Zeile unter der Linie
            if (!hr.prob.isNaN() && hr.prob >= 10) {
                text.textSize = 10 * d
                text.typeface = Ui.TF_REGULAR
                text.color = p.muted
                c.drawText("${hr.prob.roundToInt()} %", cx, yProb, text)
            }

            // Wind
            val strong = !hr.gust.isNaN() && hr.gust >= 50
            WeatherIcons.windArrow(c, cx - 11 * d, yWind - 4 * d, 11 * d, hr.windDir, if (strong) p.warn else p.muted)
            text.textSize = 11.5f * d
            text.typeface = Ui.TF_MEDIUM
            text.color = if (strong) p.warn else p.text
            text.textAlign = Paint.Align.LEFT
            c.drawText(Fmt.kmh(hr.wind), cx - 3 * d, yWind, text)
            text.textAlign = Paint.Align.CENTER
        }
    }
}

/** Tagesliste (bis 15 Tage) als eine gezeichnete Fläche mit Temperatur-Spannbalken. */
@SuppressLint("ViewConstructor")
class DaysView(ctx: Context, private val p: Ui.Palette) : View(ctx) {

    var forecast: Forecast? = null
        set(v) { field = v; requestLayout(); invalidate() }
    var onDayClick: ((Int) -> Unit)? = null
    var selected = -1
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val rowH = 58 * d
    private val topPad = 20 * d      // Zeile für die Modellangabe über dem ersten Tag
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val bar = RectF()

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onSingleTapUp(e: MotionEvent): Boolean {
            val i = ((e.y - topPad) / rowH).toInt()
            val n = forecast?.days?.size ?: 0
            if (e.y < topPad) return true
            if (i in 0 until n) {
                playSoundEffect(android.view.SoundEffectConstants.CLICK)
                onDayClick?.invoke(i)
            }
            return true
        }
    })

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean = gestures.onTouchEvent(event)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val n = forecast?.days?.size ?: 0
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (topPad + n * rowH).toInt())
    }

    /** Kleine Beschriftung (z. B. Modellwechsel) auf einer Trennlinie bzw. ganz oben. */
    private fun tag(c: Canvas, label: String, y: Float, color: Int) {
        text.textAlign = Paint.Align.LEFT
        text.typeface = Ui.TF_MEDIUM
        text.textSize = 10.5f * d
        val tw = text.measureText(label)
        val r = RectF(14 * d, y - 8 * d, 14 * d + tw + 14 * d, y + 8 * d)
        fill.shader = null
        fill.color = p.surface
        c.drawRoundRect(r, 8 * d, 8 * d, fill)
        fill.color = Ui.withAlpha(color, 40)
        c.drawRoundRect(r, 8 * d, 8 * d, fill)
        text.color = color
        c.drawText(label, r.left + 7 * d, y + 3.7f * d, text)
    }

    override fun onDraw(c: Canvas) {
        val f = forecast ?: return
        val days = f.days
        if (days.isEmpty()) return
        val w = width.toFloat()
        val lo = days.minOf { it.min }
        val hi = days.maxOf { it.max }
        val span = max(1.0, hi - lo)

        val xIcon = 104 * d
        val xPrecip = 134 * d
        val xMin = w - 176 * d
        val barL = w - 140 * d
        val barR = w - 52 * d
        val xMax = w - 10 * d

        c.save()
        c.translate(0f, topPad)
        val tags = ArrayList<Triple<String, Float, Int>>()
        tags.add(Triple(if (days[0].fromD2) "ICON-D2 · 2 km" else "ECMWF · 9 km", -topPad / 2, p.muted))
        for ((i, day) in days.withIndex()) {
            val top = i * rowH
            val cy = top + rowH / 2
            if (i > 0 && days[i - 1].fromD2 && !day.fromD2) tags.add(Triple("ab hier ECMWF · 9 km", top, p.accent))
            if (i == 10) tags.add(Triple("ab hier unsicher", top, p.warn))
            if (i == selected) {
                fill.color = p.surfaceHi
                c.drawRoundRect(RectF(4 * d, top + 3 * d, w - 4 * d, top + rowH - 3 * d), 14 * d, 14 * d, fill)
            }
            if (i > 0) {
                fill.color = p.border
                c.drawRect(16 * d, top, w - 16 * d, top + 1, fill)
            }
            // Tag
            text.textAlign = Paint.Align.LEFT
            text.typeface = Ui.TF_MEDIUM
            text.textSize = 15.5f * d
            text.color = p.text
            c.drawText(f.dayLabel(day, i), 16 * d, cy - 2 * d, text)
            text.typeface = Ui.TF_REGULAR
            text.textSize = 11.5f * d
            text.color = p.muted
            c.drawText(f.dateShort(day.start + 3600), 16 * d, cy + 14 * d, text)

            // Symbol
            WeatherIcons.draw(c, day.code, true, xIcon, cy, 34 * d, p)
            if (day.thunder >= 1) WeatherIcons.boltBadge(c, xIcon + 15 * d, cy - 12 * d, 13 * d, thunderColor(day.thunder, p), p.surface)

            // Niederschlag
            if (day.precip >= 0.3 || (!day.probMax.isNaN() && day.probMax >= 30)) {
                text.textSize = 12 * d
                text.typeface = Ui.TF_MEDIUM
                val snowy = day.snow > 0.3
                text.color = if (snowy) p.snow else p.rain
                c.drawText(if (snowy) "${Fmt.mm(day.snow)} cm" else "${Fmt.mm(day.precip)} mm", xPrecip, cy - 2 * d, text)
                if (!day.probMax.isNaN()) {
                    text.typeface = Ui.TF_REGULAR
                    text.textSize = 11 * d
                    text.color = p.muted
                    c.drawText("${day.probMax.roundToInt()} %", xPrecip, cy + 13 * d, text)
                }
            }

            // Min / Balken / Max
            text.typeface = Ui.TF_MEDIUM
            text.textSize = 15 * d
            text.textAlign = Paint.Align.RIGHT
            text.color = p.muted
            c.drawText(Fmt.temp(day.min), xMin + 22 * d, cy + 5 * d, text)
            text.color = p.text
            c.drawText(Fmt.temp(day.max), xMax, cy + 5 * d, text)

            val bh = 6 * d
            fill.shader = null
            fill.color = p.surfaceHi
            bar.set(barL, cy - bh / 2, barR, cy + bh / 2)
            c.drawRoundRect(bar, bh / 2, bh / 2, fill)
            val l = barL + ((day.min - lo) / span * (barR - barL)).toFloat()
            val r = barL + ((day.max - lo) / span * (barR - barL)).toFloat()
            bar.set(l, cy - bh / 2, max(r, l + bh), cy + bh / 2)
            fill.shader = LinearGradient(l, 0f, r, 0f, tempColor(day.min, p), tempColor(day.max, p), Shader.TileMode.CLAMP)
            c.drawRoundRect(bar, bh / 2, bh / 2, fill)
            fill.shader = null
        }
        for ((label, y, color) in tags) tag(c, label, y, color)
        c.restore()
    }
}

/** Niederschlagsstärke aus mm je Stunde (DWD-Einteilung). */
object RainRate {
    val NAMES = arrayOf("trocken", "leicht", "mäßig", "stark")
    fun level(mmh: Double): Int = when {
        mmh < 0.1 -> 0
        mmh < 2.5 -> 1
        mmh < 10.0 -> 2
        else -> 3
    }
    /** Höhe 0..1 – wurzelförmig, damit auch leichter Regen gut sichtbar ist. */
    fun height(mmh: Double): Float = if (mmh <= 0.0) 0f else sqrt(min(mmh, 16.0) / 16.0).toFloat()
}

/**
 * Regen der nächsten 3 Stunden (15-Minuten-Werte, ICON-D2) als weiche Kurve.
 * Hilfslinien für leicht / mäßig / stark; Antippen oder Ziehen zeigt den Wert.
 */
class NowcastView(ctx: Context, private val p: Ui.Palette) : View(ctx) {
    var data: List<Pair<Long, Double>> = emptyList()
        set(v) { field = v; sel = -1; invalidate() }
    var forecast: Forecast? = null
    var snowy = false
        set(v) { field = v; invalidate() }
    /** Bis hier Radar (Messung + DWD-Nowcast), danach Modell; 0 = nur Modell. */
    var radarUntil = 0L
        set(v) { field = v; invalidate() }

    private val d = resources.displayMetrics.density
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val grid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; pathEffect = DashPathEffect(floatArrayOf(3 * resources.displayMetrics.density, 4 * resources.displayMetrics.density), 0f) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private var sel = -1

    private val left = 46 * d       // Platz für die Stufen-Beschriftung
    private val top = 22 * d        // Platz für den Antipp-Wert
    private val chartH = 92 * d
    private val bottomPad = 22 * d

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), (top + chartH + bottomPad).toInt())
    }

    private fun rate(i: Int) = data[i].second * 4.0   // 15-Minuten-Menge → mm/h

    private fun xOf(i: Int): Float {
        val w = width - left - 6 * d
        return left + if (data.size <= 1) 0f else w * i / (data.size - 1)
    }

    private fun yOf(h: Float) = top + chartH * (1 - h)

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (data.size < 2) return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val w = width - left - 6 * d
                val i = (((e.x - left) / w) * (data.size - 1)).roundToInt().coerceIn(0, data.size - 1)
                if (i != sel) { sel = i; invalidate() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                postDelayed({ sel = -1; invalidate() }, 2500)
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val n = data.size
        if (n < 2) return
        val base = yOf(0f)
        val main = if (snowy) p.snow else p.rain

        // Hilfslinien mit Stufen
        text.textSize = 10.5f * d
        text.textAlign = Paint.Align.LEFT
        grid.strokeWidth = 1f
        for ((lvl, mmh) in listOf(1 to 0.5, 2 to 2.5, 3 to 10.0)) {
            val y = yOf(RainRate.height(mmh))
            grid.color = p.border
            c.drawLine(left, y, width.toFloat(), y, grid)
            text.color = p.muted
            c.drawText(RainRate.NAMES[lvl], 0f, y + 4 * d, text)
        }
        fill.shader = null
        fill.color = p.border
        c.drawRect(left, base, width.toFloat(), base + 1, fill)

        // Weiche Kurve (monoton, schießt nicht über)
        path.reset()
        path.moveTo(xOf(0), base)
        path.lineTo(xOf(0), yOf(RainRate.height(rate(0))))
        for (i in 1 until n) {
            val x0 = xOf(i - 1); val y0 = yOf(RainRate.height(rate(i - 1)))
            val x1 = xOf(i); val y1 = yOf(RainRate.height(rate(i)))
            val mx = (x0 + x1) / 2
            path.cubicTo(mx, y0, mx, y1, x1, y1)
        }
        path.lineTo(xOf(n - 1), base)
        path.close()

        // Füllung: nach oben (stärker) kräftiger bzw. violett
        val strong = if (snowy) p.snow else 0xFF8E5BFF.toInt()
        fill.shader = LinearGradient(0f, top, 0f, base,
            intArrayOf(Ui.withAlpha(strong, 235), Ui.withAlpha(main, 200), Ui.withAlpha(main, 70)),
            floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(path, fill)
        fill.shader = null
        // Oberkante
        path.reset()
        path.moveTo(xOf(0), yOf(RainRate.height(rate(0))))
        for (i in 1 until n) {
            val x0 = xOf(i - 1); val y0 = yOf(RainRate.height(rate(i - 1)))
            val x1 = xOf(i); val y1 = yOf(RainRate.height(rate(i)))
            val mx = (x0 + x1) / 2
            path.cubicTo(mx, y0, mx, y1, x1, y1)
        }
        stroke.color = main
        stroke.strokeWidth = 2.2f * d
        c.drawPath(path, stroke)

        // Wechsel Radar → Modell
        val sw = data.indexOfFirst { it.first >= radarUntil }
        if (radarUntil > 0 && sw > 0) {
            val x = xOf(sw) - (xOf(1) - xOf(0)) / 2
            grid.color = Ui.withAlpha(p.accent, 140)
            c.drawLine(x, top, x, base, grid)
            text.textSize = 9.5f * d
            text.textAlign = Paint.Align.LEFT
            text.color = p.accent
            c.drawText("Modell", x + 4 * d, top + 9 * d, text)
            text.textAlign = Paint.Align.RIGHT
            c.drawText("Radar", x - 4 * d, top + 9 * d, text)
        }

        // Zeitachse alle 30 Minuten
        text.textSize = 10.5f * d
        text.color = p.muted
        val f = forecast
        for (i in 0 until n step 2) {
            text.textAlign = when (i) { 0 -> Paint.Align.LEFT; n - 1 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            val label = if (i == 0) "jetzt" else f?.hhmm(data[i].first) ?: ""
            c.drawText(label, xOf(i), base + 16 * d, text)
        }

        // Antippen: Markierung + Wert
        if (sel in 0 until n) {
            val x = xOf(sel)
            val r = rate(sel)
            stroke.color = p.text
            stroke.strokeWidth = 1.2f * d
            c.drawLine(x, top, x, base, stroke)
            fill.color = p.surface
            c.drawCircle(x, yOf(RainRate.height(r)), 6 * d, fill)
            fill.color = main
            c.drawCircle(x, yOf(RainRate.height(r)), 4 * d, fill)
            val t = (if (sel == 0) "jetzt" else f?.hhmm(data[sel].first) ?: "") +
                "  ·  " + (if (r < 0.1) "trocken" else "${Fmt.mm(r)} mm/h · ${RainRate.NAMES[RainRate.level(r)]}")
            text.textSize = 12.5f * d
            text.typeface = Ui.TF_MEDIUM
            text.color = p.text
            val tw = text.measureText(t)
            text.textAlign = Paint.Align.LEFT
            val tx = (x - tw / 2).coerceIn(left, width - tw)
            c.drawText(t, tx, top - 8 * d, text)
            text.typeface = Ui.TF_REGULAR
        }
    }
}
