package de.basti.wetter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Verlaufsstreifen des Lauf-Checks: 8 Abschnitte à 15 Minuten.
 * Je Abschnitt: Ampelfarbe, Regen-Tropfen, gefühlte Temperatur, Wind mit Pfeil.
 * Antippen zeigt die genauen Werte.
 */
@SuppressLint("ViewConstructor")
class RunStripView(ctx: Context, private val p: Ui.Palette) : View(ctx) {

    var result: RunCheck.Result? = null
        set(v) { field = v; sel = -1; invalidate() }
    var forecast: Forecast? = null

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val drop = Path()
    private var sel = -1

    private val yInfo = 14 * d
    private val yBar = 24 * d
    private val barH = 8 * d
    private val yDrop = 52 * d
    private val yTemp = 84 * d
    private val yWind = 106 * d
    private val yGust = 124 * d
    private val yTime = 146 * d

    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (154 * d).toInt())

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        val n = result?.slots?.size ?: return false
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val i = (e.x / (width.toFloat() / n)).toInt().coerceIn(0, n - 1)
                if (i != sel) { sel = i; invalidate() }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                postDelayed({ sel = -1; invalidate() }, 3000)
            }
        }
        return true
    }

    override fun onDraw(c: Canvas) {
        val r = result ?: return
        val f = forecast
        val n = r.slots.size
        val cw = width.toFloat() / n

        for ((i, s) in r.slots.withIndex()) {
            val x0 = i * cw
            val cx = x0 + cw / 2
            // Ampelbalken (abgerundet nur an den Enden)
            paint.color = RunCheck.color(s.level, p)
            paint.alpha = if (sel < 0 || sel == i) 255 else 110
            val left = x0 + if (i == 0) 0f else 1.5f * d
            val right = x0 + cw - if (i == n - 1) 0f else 1.5f * d
            c.drawRoundRect(RectF(left, yBar, right, yBar + barH), barH / 2, barH / 2, paint)
            paint.alpha = 255

            if (sel == i) {
                paint.color = p.surfaceHi
                c.drawRoundRect(RectF(x0 + 2 * d, yBar + barH + 4 * d, x0 + cw - 2 * d, yGust + 6 * d), 10 * d, 10 * d, paint)
            }

            // Regen: Tropfen, Füllung nach Stärke
            drawDrop(c, cx, yDrop, 9 * d, s.rain, s.snowy, s.near)

            // Gefühlte Temperatur
            text.textSize = 14 * d
            text.typeface = Ui.TF_MEDIUM
            text.color = p.text
            c.drawText(Fmt.temp(s.feels), cx, yTemp, text)

            // Wind
            // Mittelwind mit Richtung, darunter die Böen (ab 45 km/h orange – danach richtet sich die Ampel)
            val strong = !s.gust.isNaN() && s.gust >= 45
            WeatherIcons.windArrow(c, cx - 10 * d, yWind - 4 * d, 10 * d, s.windDir, p.muted)
            text.textSize = 11 * d
            text.color = p.muted
            text.textAlign = Paint.Align.LEFT
            c.drawText(Fmt.kmh(s.wind), cx - 3 * d, yWind, text)
            text.textAlign = Paint.Align.CENTER
            text.textSize = 10.5f * d
            text.color = if (strong) p.warn else Ui.withAlpha(p.muted, 200)
            c.drawText("B ${Fmt.kmh(s.gust)}", cx, yGust, text)
        }

        // Zeitachse alle 30 Minuten
        text.typeface = Ui.TF_REGULAR
        text.textSize = 10.5f * d
        text.color = p.muted
        for (i in 0..n step 2) {
            val x = (i * cw).coerceIn(0f, width.toFloat())
            text.textAlign = when (i) { 0 -> Paint.Align.LEFT; n -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
            val t = r.slots.first().t + i * 900L
            c.drawText(if (i == 0) "jetzt" else f?.hhmm(t) ?: "", x, yTime, text)
        }
        text.textAlign = Paint.Align.CENTER

        // Detail des angetippten Abschnitts
        if (sel in 0 until n) {
            val s = r.slots[sel]
            val rain = if (s.rain < 0.1) (if (s.near) "Schauer in der Nähe" else if (s.rain >= 0.02) "einzelne Tropfen" else "trocken") else "${Fmt.mm(s.rain)} mm/h ${if (s.snowy) "Schnee" else "Regen"}"
            val gust = if (s.gust.isNaN()) "" else " · Böen ${s.gust.roundToInt()}"
            val t = "${f?.hhmm(s.t) ?: ""} · $rain · ${Fmt.temp(s.temp)} (gef. ${Fmt.temp(s.feels)})$gust"
            text.textSize = 12 * d
            text.typeface = Ui.TF_MEDIUM
            text.color = p.text
            val tw = text.measureText(t)
            val cx = (sel * cw + cw / 2).coerceIn(tw / 2, width - tw / 2)
            c.drawText(t, cx, yInfo, text)
            text.typeface = Ui.TF_REGULAR
        }
    }

    private fun drawDrop(c: Canvas, cx: Float, cy: Float, r: Float, mmh: Double, snowy: Boolean, near: Boolean = false) {
        drop.reset()
        drop.moveTo(cx, cy - r * 1.55f)
        drop.cubicTo(cx + r * 0.35f, cy - r * 0.85f, cx + r, cy - r * 0.35f, cx + r, cy + r * 0.15f)
        drop.arcTo(RectF(cx - r, cy - r * 0.85f, cx + r, cy + r * 1.15f), 0f, 180f)
        drop.cubicTo(cx - r, cy - r * 0.35f, cx - r * 0.35f, cy - r * 0.85f, cx, cy - r * 1.55f)
        drop.close()
        val col = if (snowy) p.snow else p.rain
        // Umriss immer, Füllung je nach Stärke
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.4f * d
        paint.color = if (mmh < 0.1 && !near) Ui.withAlpha(p.muted, 110) else col
        c.drawPath(drop, paint)
        paint.style = Paint.Style.FILL
        if (mmh < 0.1 && near) {
            // Schauer in der Nähe: halb gefüllt, blasser
            c.save()
            c.clipRect(cx - r - 2, cy - r * 0.2f, cx + r + 2, cy + r * 1.2f)
            paint.color = Ui.withAlpha(col, 120)
            c.drawPath(drop, paint)
            c.restore()
        }
        if (mmh >= 0.1) {
            val k = min(1.0, RainRate.height(mmh).toDouble() * 1.4)
            val top = cy + r * 1.15f - (r * 2.7f * k.toFloat())
            c.save()
            c.clipRect(cx - r - 2, top, cx + r + 2, cy + r * 1.2f)
            paint.color = col
            c.drawPath(drop, paint)
            c.restore()
        }
    }
}

/** Karte "Lauf-Check" für die Vorhersage-Seite. */
@SuppressLint("ViewConstructor")
class RunCard(ctx: Context, private val p: Ui.Palette, private val onTypeChange: () -> Unit) : LinearLayout(ctx) {

    private fun dp(v: Number) = Ui.dp(context, v)
    private fun dpf(v: Number) = Ui.dpf(context, v)
    private val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    private val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

    private val dot = View(ctx)
    private val headline = Ui.tv(ctx, "", 19f, p.text, true)
    private val reason = Ui.tv(ctx, "", 14f, p.muted).apply { setLineSpacing(0f, 1.15f) }
    private val typePill = Ui.tv(ctx, "", 12.5f, p.text, true)
    private val strip = RunStripView(ctx, p)
    private val clothing = Ui.tv(ctx, "", 15f, p.text, true).apply { setLineSpacing(0f, 1.15f) }
    private val clothingNote = Ui.tv(ctx, "", 13f, p.muted)
    private val hints = LinearLayout(ctx).apply { orientation = VERTICAL }
    private val better = Ui.tv(ctx, "", 14f, p.accent, true)

    init {
        orientation = VERTICAL
        background = Ui.rect(p.surface, dpf(24), p.border, 1)
        setPadding(dp(18), dp(16), dp(18), dp(14))

        addView(LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(dot, LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(12) })
            addView(LinearLayout(ctx).apply {
                orientation = VERTICAL
                addView(headline)
                addView(reason, LayoutParams(WRAP, WRAP).apply { topMargin = dp(4) })
            }, LayoutParams(0, WRAP, 1f))
            addView(typePill.apply {
                setPadding(dp(12), dp(7), dp(12), dp(7))
                background = Ui.pressable(p, p.surfaceHi, dpf(16))
                setOnClickListener {
                    Prefs.setRunnerType(context, (Prefs.runnerType(context) + 1) % 3)
                    onTypeChange()
                }
            })
        })
        addView(strip, LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })

        addView(LinearLayout(ctx).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(GlyphView(ctx, GlyphView.Kind.SHIRT, p.accent, 20), LayoutParams(dp(20), dp(20)).apply { rightMargin = dp(12) })
            addView(LinearLayout(ctx).apply {
                orientation = VERTICAL
                addView(clothing)
                addView(clothingNote, LayoutParams(WRAP, WRAP).apply { topMargin = dp(3) })
            }, LayoutParams(0, WRAP, 1f))
        }, LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })

        addView(hints, LayoutParams(MATCH, WRAP))
        addView(better, LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
    }

    fun bind(f: Forecast, r: RunCheck.Result?) {
        if (r == null) { visibility = View.GONE; return }
        visibility = View.VISIBLE
        val col = RunCheck.color(r.level, p)
        dot.background = Ui.oval(col)
        headline.text = r.headline
        headline.setTextColor(if (r.level == RunCheck.GREEN) p.text else col)
        reason.text = r.reason
        typePill.text = RunCheck.TYPE_NAMES[Prefs.runnerType(context)]
        strip.forecast = f
        strip.result = r
        clothing.text = r.clothing
        clothingNote.text = r.clothingNote ?: ""
        clothingNote.visibility = if (r.clothingNote == null) View.GONE else View.VISIBLE

        hints.removeAllViews()
        for ((kind, txt) in r.hints) {
            val color = when (kind) {
                GlyphView.Kind.BOLT -> p.warn
                GlyphView.Kind.WARNING -> p.warn
                GlyphView.Kind.SUN -> p.sun
                GlyphView.Kind.SNOW -> p.snow
                GlyphView.Kind.RAIN -> p.rain
                else -> p.muted
            }
            hints.addView(LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(GlyphView(context, kind, color, 18), LayoutParams(dp(18), dp(18)).apply { rightMargin = dp(13) })
                addView(TextView(context).apply {
                    text = txt
                    textSize = 13.5f
                    setTextColor(p.text)
                    setLineSpacing(0f, 1.15f)
                }, LayoutParams(0, WRAP, 1f))
            }, LayoutParams(MATCH, WRAP).apply { topMargin = dp(10) })
        }
        better.text = r.better ?: ""
        better.visibility = if (r.better == null) View.GONE else View.VISIBLE
    }
}
