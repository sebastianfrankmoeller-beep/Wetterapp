package de.basti.wetter.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.text.TextPaint
import android.text.TextUtils
import de.basti.wetter.Fmt
import de.basti.wetter.Forecast
import de.basti.wetter.RunCheck
import de.basti.wetter.GlyphView
import de.basti.wetter.Prefs
import de.basti.wetter.Ui
import de.basti.wetter.WeatherIcons
import de.basti.wetter.tempColor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Zeichnet den Widget-Inhalt als Bild – dadurch sieht er exakt aus wie die App. */
object WidgetRenderer {

    const val MODE_AUTO = 0
    const val MODE_NOW = 1
    const val MODE_HOURS = 2
    const val MODE_DAYS = 3
    const val MODE_ALL = 4
    const val MODE_RUN = 5

    val MODE_NAMES = arrayOf("Automatisch", "Jetzt", "Stunden", "Tage", "Alles", "Lauf")

    fun palette(ctx: Context, theme: Int): Ui.Palette {
        val night = (ctx.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
            android.content.res.Configuration.UI_MODE_NIGHT_YES
        return when (theme) {
            Prefs.THEME_LIGHT -> Ui.LIGHT
            Prefs.THEME_AUTO -> if (night) Ui.DARK else Ui.LIGHT
            else -> Ui.DARK
        }
    }

    /**
     * @param wDp, hDp  Größe des Widgets in dp
     * @param alphaPct  Deckkraft des Hintergrunds 0–100
     */
    fun render(
        ctx: Context, f: Forecast?, placeName: String, isLocation: Boolean,
        wDp: Int, hDp: Int, mode: Int, alphaPct: Int, theme: Int
    ): Bitmap {
        val p = palette(ctx, theme)
        var s = ctx.resources.displayMetrics.density
        // Bildgröße begrenzen (RemoteViews haben ein Speicherlimit)
        val maxPx = 1_400_000f
        val px = wDp * hDp * s * s
        if (px > maxPx) s *= sqrt(maxPx / px)
        val w = max(1, (wDp * s).toInt())
        val h = max(1, (hDp * s).toInt())
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val d = s

        val bg = Paint(Paint.ANTI_ALIAS_FLAG)
        bg.color = Ui.withAlpha(p.surface, (alphaPct.coerceIn(0, 100) * 255 / 100))
        val radius = 22 * d
        c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), radius, radius, bg)
        if (alphaPct >= 30) {
            bg.style = Paint.Style.STROKE
            bg.strokeWidth = 1f
            bg.color = p.border
            c.drawRoundRect(RectF(0.5f, 0.5f, w - 0.5f, h - 0.5f), radius, radius, bg)
        }

        val text = TextPaint(Paint.ANTI_ALIAS_FLAG)
        // Ohne Hintergrund dezenter Schatten, damit Text auf jedem Hintergrundbild lesbar bleibt
        if (alphaPct < 40) text.setShadowLayer(3 * d, 0f, 1 * d, 0x99000000.toInt())
        val iconP = if (alphaPct < 40) p else p

        if (f == null || f.now == null) {
            text.color = p.text; text.textSize = 14 * d
            text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            c.drawText(placeName, 16 * d, h / 2f - 4 * d, text)
            text.color = p.muted; text.textSize = 12 * d
            text.typeface = Typeface.DEFAULT
            c.drawText("Noch keine Daten – tippen zum Öffnen", 16 * d, h / 2f + 14 * d, text)
            return bmp
        }

        val effMode = if (mode == MODE_AUTO) when {
            hDp < 100 -> MODE_NOW
            hDp < 190 -> MODE_HOURS
            else -> MODE_ALL
        } else mode

        val pad = 14 * d
        if (effMode == MODE_RUN) {
            drawRun(ctx, c, f, placeName, pad, pad, w - 2 * pad, h - 2 * pad, d, p, text)
            return bmp
        }
        var y = pad
        // Kopfbereich: kompakt, wenn danach noch Stunden/Tage folgen
        val headH = if (effMode == MODE_NOW) h - 2 * pad else min(68 * d, h * 0.45f)
        drawNow(c, f, placeName, isLocation, pad, y, w - 2 * pad, headH, d, p, iconP, text, big = effMode == MODE_NOW)
        y += headH + 6 * d

        val rest = h - y - pad
        when (effMode) {
            MODE_HOURS -> if (rest > 50 * d) drawHours(c, f, pad, y, w - 2 * pad, rest, d, p, text)
            MODE_DAYS -> if (rest > 26 * d) drawDays(c, f, pad, y, w - 2 * pad, rest, d, p, text)
            MODE_ALL -> {
                if (rest > 50 * d) {
                    val hh = min(86 * d, rest)
                    drawHours(c, f, pad, y, w - 2 * pad, hh, d, p, text)
                    val y2 = y + hh + 6 * d
                    val rest2 = h - y2 - pad
                    if (rest2 > 26 * d) drawDays(c, f, pad, y2, w - 2 * pad, rest2, d, p, text)
                }
            }
        }
        return bmp
    }

    /** Widget-Inhalt "Lauf": Ampel, Grund, Kleidung, 2-Stunden-Verlauf. */
    private fun drawRun(ctx: Context, c: Canvas, f: Forecast, name: String, x: Float, y: Float, w: Float, h: Float, d: Float, p: Ui.Palette, text: TextPaint) {
        val r = RunCheck.evaluate(f, Prefs.runnerType(ctx), null) ?: return
        val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val col = RunCheck.color(r.level, p)

        // Kopf: Ampelpunkt + Urteil, rechts Ort und Temperatur
        paint.color = col
        c.drawCircle(x + 7 * d, y + 11 * d, 7 * d, paint)
        text.textAlign = Paint.Align.LEFT
        text.typeface = medium
        text.textSize = 16 * d
        text.color = if (r.level == RunCheck.GREEN) p.text else col
        c.drawText(r.headline, x + 22 * d, y + 17 * d, text)
        val headW = text.measureText(r.headline)
        text.typeface = Typeface.DEFAULT
        text.textSize = 12 * d
        text.color = p.muted
        text.textAlign = Paint.Align.RIGHT
        val right = "${name} · ${Fmt.temp(f.now?.temp ?: Double.NaN)}"
        val avail = w - headW - 34 * d
        if (avail > 40 * d) c.drawText(TextUtils.ellipsize(right, text, avail, TextUtils.TruncateAt.END).toString(), x + w, y + 16 * d, text)
        text.textAlign = Paint.Align.LEFT

        var yy = y + 38 * d
        text.textSize = 12.5f * d
        text.color = p.muted
        if (yy < y + h) c.drawText(TextUtils.ellipsize(r.reason, text, w, TextUtils.TruncateAt.END).toString(), x, yy, text)

        // Kleidung
        yy += 22 * d
        if (yy < y + h - 20 * d) {
            GlyphView.draw(c, GlyphView.Kind.SHIRT, x + 8 * d, yy - 4 * d, 16 * d, p.accent, paint, android.graphics.Path())
            text.typeface = medium
            text.textSize = 13 * d
            text.color = p.text
            c.drawText(TextUtils.ellipsize(r.clothing, text, w - 24 * d, TextUtils.TruncateAt.END).toString(), x + 24 * d, yy, text)
            text.typeface = Typeface.DEFAULT
        }

        // 2-Stunden-Verlauf als Ampelbalken mit Zeit
        val barY = y + h - 24 * d
        if (barY > yy + 8 * d) {
            val n = r.slots.size
            val cw = w / n
            paint.style = Paint.Style.FILL
            for ((i, sl) in r.slots.withIndex()) {
                paint.color = RunCheck.color(sl.level, p)
                val l = x + i * cw + if (i == 0) 0f else 1.5f * d
                val rr = x + (i + 1) * cw - if (i == n - 1) 0f else 1.5f * d
                c.drawRoundRect(RectF(l, barY, rr, barY + 7 * d), 3.5f * d, 3.5f * d, paint)
            }
            text.textSize = 10.5f * d
            text.color = p.muted
            val t0 = r.slots.first().t
            for ((k, i) in listOf(0, n / 2, n).withIndex()) {
                text.textAlign = when (k) { 0 -> Paint.Align.LEFT; 2 -> Paint.Align.RIGHT; else -> Paint.Align.CENTER }
                c.drawText(if (i == 0) "jetzt" else f.hhmm(t0 + i * 900L), x + i * cw, barY + 21 * d, text)
            }
            text.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawNow(
        c: Canvas, f: Forecast, name: String, isLoc: Boolean, x: Float, y: Float, w: Float, h: Float,
        d: Float, p: Ui.Palette, iconP: Ui.Palette, text: TextPaint, big: Boolean
    ) {
        val now = f.now ?: return
        val today = f.days.firstOrNull()
        val iconSize = min(h, if (big) 64 * d else 48 * d)
        val cy = y + h / 2
        WeatherIcons.draw(c, now.code, now.isDay, x + iconSize / 2, cy, iconSize, iconP)

        val tx = x + iconSize + 10 * d
        text.typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        text.textSize = min(if (big) 46 * d else 36 * d, h * 0.8f)
        text.color = p.text
        text.textAlign = Paint.Align.LEFT
        val tempStr = Fmt.temp(now.temp)
        val tempW = text.measureText(tempStr)
        val baseline = cy + text.textSize * 0.36f
        c.drawText(tempStr, tx, baseline, text)

        // Rechts daneben: Ort und Beschreibung
        val ix = tx + tempW + 12 * d
        val avail = x + w - ix
        if (avail > 50 * d) {
            text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            text.textSize = 14 * d
            text.color = p.text
            val label = (if (isLoc) "⌖ " else "") + name
            c.drawText(TextUtils.ellipsize(label, text, avail, TextUtils.TruncateAt.END).toString(), ix, cy - 4 * d, text)
            text.typeface = Typeface.DEFAULT
            text.textSize = 12 * d
            text.color = p.muted
            val hl = if (today != null) "  ↑${Fmt.temp(today.max)} ↓${Fmt.temp(today.min)}" else ""
            val line2 = Fmt.codeAt(now.code, now.isDay) + hl
            c.drawText(TextUtils.ellipsize(line2, text, avail, TextUtils.TruncateAt.END).toString(), ix, cy + 13 * d, text)
            if (big && h > 90 * d) {
                val line3 = "Wind ${Fmt.kmh(now.wind)} km/h ${Fmt.dir(now.windDir)}  ·  Gefühlt ${Fmt.temp(now.feels)}"
                c.drawText(TextUtils.ellipsize(line3, text, avail, TextUtils.TruncateAt.END).toString(), ix, cy + 30 * d, text)
            }
        }
        // Alter der Daten anzeigen, wenn älter als 3 Stunden
        val ageMin = (System.currentTimeMillis() - f.fetchedAt) / 60000
        if (ageMin > 180) {
            text.textSize = 10 * d
            text.color = p.warn
            text.textAlign = Paint.Align.RIGHT
            c.drawText("Stand ${f.hhmm(f.fetchedAt / 1000)}", x + w, y + 10 * d, text)
            text.textAlign = Paint.Align.LEFT
        }
    }

    private fun drawHours(c: Canvas, f: Forecast, x: Float, y: Float, w: Float, h: Float, d: Float, p: Ui.Palette, text: TextPaint) {
        val colW = 50 * d
        val n = min(f.hours.size - 1, max(1, (w / colW).toInt()))
        if (n <= 0) return
        val cw = w / n
        val icon = min(28 * d, h * 0.34f)
        text.textAlign = Paint.Align.CENTER
        for (k in 0 until n) {
            val hr = f.hours[k + 1]
            val cx = x + k * cw + cw / 2
            text.typeface = Typeface.DEFAULT
            text.textSize = 11.5f * d
            text.color = p.muted
            c.drawText("%02d".format(f.hour(hr.t)), cx, y + 12 * d, text)
            WeatherIcons.draw(c, hr.code, hr.isDay, cx, y + 16 * d + icon / 2, icon, p)
            text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            text.textSize = 14 * d
            text.color = p.text
            val ty = y + 16 * d + icon + 15 * d
            c.drawText(Fmt.temp(hr.temp), cx, ty, text)
            if (hr.precip >= 0.1 && ty + 13 * d < y + h) {
                text.textSize = 10.5f * d
                text.color = if (hr.snow > 0.05) p.snow else p.rain
                c.drawText("${Fmt.mm(hr.precip)}", cx, ty + 13 * d, text)
            }
        }
        text.textAlign = Paint.Align.LEFT
    }

    private fun drawDays(c: Canvas, f: Forecast, x: Float, y: Float, w: Float, h: Float, d: Float, p: Ui.Palette, text: TextPaint) {
        val rowH = 28 * d
        val n = min(f.days.size, (h / rowH).toInt())
        if (n <= 0) return
        val medium = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        for (i in 0 until n) {
            val day = f.days[i]
            val cy = y + i * rowH + rowH / 2
            val base = cy + 4.5f * d

            // Tag und Symbol
            text.textAlign = Paint.Align.LEFT
            text.typeface = medium
            text.textSize = 13 * d
            text.color = p.text
            c.drawText(f.dayLabel(day, i), x, base, text)
            WeatherIcons.draw(c, day.code, true, x + 80 * d, cy, 22 * d, p)

            // Rechts: "Tief / Hoch" – Hoch hell und leicht in Temperaturfarbe getönt
            text.textAlign = Paint.Align.RIGHT
            text.textSize = 14 * d
            text.color = Ui.mix(p.text, tempColor(day.max, p), 0.45f)
            val maxStr = Fmt.temp(day.max)
            c.drawText(maxStr, x + w, base, text)
            val maxW = text.measureText(maxStr)
            text.typeface = Typeface.DEFAULT
            text.color = p.muted
            val sep = " / "
            c.drawText(sep, x + w - maxW, base, text)
            val sepW = text.measureText(sep)
            val minStr = Fmt.temp(day.min)
            c.drawText(minStr, x + w - maxW - sepW, base, text)
            val tempLeft = x + w - maxW - sepW - text.measureText(minStr)

            // Mitte: Niederschlag (Regen in mm bzw. Schnee in cm) mit Wahrscheinlichkeit
            val px = x + 100 * d
            val room = tempLeft - px - 10 * d
            if (room > 34 * d && (day.precip >= 0.2 || (!day.probMax.isNaN() && day.probMax >= 30))) {
                val snowy = day.snow > 0.3
                text.textAlign = Paint.Align.LEFT
                text.textSize = 12 * d
                text.typeface = medium
                text.color = if (snowy) p.snow else p.rain
                val amount = if (snowy) "${Fmt.mm(day.snow)} cm" else "${Fmt.mm(day.precip)} mm"
                c.drawText(amount, px, base, text)
                val aw = text.measureText(amount)
                if (!day.probMax.isNaN() && room - aw > 40 * d) {
                    text.typeface = Typeface.DEFAULT
                    text.textSize = 11 * d
                    text.color = p.muted
                    c.drawText("  ${day.probMax.roundToInt()} %", px + aw, base, text)
                }
            }
        }
        text.textAlign = Paint.Align.LEFT
        text.typeface = Typeface.DEFAULT
    }
}
