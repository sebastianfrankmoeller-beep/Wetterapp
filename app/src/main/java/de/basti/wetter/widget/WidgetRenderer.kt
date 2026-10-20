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
import de.basti.wetter.Prefs
import de.basti.wetter.Ui
import de.basti.wetter.WeatherIcons
import de.basti.wetter.tempColor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Zeichnet den Widget-Inhalt als Bild – dadurch sieht er exakt aus wie die App. */
object WidgetRenderer {

    const val MODE_AUTO = 0
    const val MODE_NOW = 1
    const val MODE_HOURS = 2
    const val MODE_DAYS = 3
    const val MODE_ALL = 4

    val MODE_NAMES = arrayOf("Automatisch", "Jetzt", "Stunden", "Tage", "Alles")

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
        val lo = f.days.take(n).minOf { it.min }
        val hi = f.days.take(n).maxOf { it.max }
        val span = max(1.0, hi - lo)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val barL = x + w * 0.55f
        val barR = x + w - 40 * d
        for (i in 0 until n) {
            val day = f.days[i]
            val cy = y + i * rowH + rowH / 2
            text.textAlign = Paint.Align.LEFT
            text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            text.textSize = 13 * d
            text.color = p.text
            c.drawText(f.dayLabel(day, i), x, cy + 4.5f * d, text)
            WeatherIcons.draw(c, day.code, true, x + 80 * d, cy, 22 * d, p)
            if (day.precip >= 0.3 && barL - (x + 96 * d) > 36 * d) {
                text.typeface = Typeface.DEFAULT
                text.textSize = 11 * d
                text.color = if (day.snow > 0.3) p.snow else p.rain
                c.drawText("${Fmt.mm(day.precip)} mm", x + 96 * d, cy + 4 * d, text)
            }
            text.typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            text.textSize = 13 * d
            text.textAlign = Paint.Align.RIGHT
            text.color = p.muted
            c.drawText(Fmt.temp(day.min), barL - 6 * d, cy + 4.5f * d, text)
            text.color = p.text
            c.drawText(Fmt.temp(day.max), x + w, cy + 4.5f * d, text)
            val bh = 5 * d
            paint.shader = null
            paint.color = p.surfaceHi
            c.drawRoundRect(RectF(barL, cy - bh / 2, barR, cy + bh / 2), bh, bh, paint)
            val l = barL + ((day.min - lo) / span * (barR - barL)).toFloat()
            val r = barL + ((day.max - lo) / span * (barR - barL)).toFloat()
            paint.shader = LinearGradient(l, 0f, r, 0f, tempColor(day.min, p), tempColor(day.max, p), Shader.TileMode.CLAMP)
            c.drawRoundRect(RectF(l, cy - bh / 2, max(r, l + bh), cy + bh / 2), bh, bh, paint)
        }
        text.textAlign = Paint.Align.LEFT
    }
}
