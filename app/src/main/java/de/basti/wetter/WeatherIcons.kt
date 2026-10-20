package de.basti.wetter

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import kotlin.math.cos
import kotlin.math.sin

/**
 * Zeichnet eigene, flache Wettersymbole direkt auf eine Canvas –
 * dieselben Symbole in der App und im Widget.
 */
object WeatherIcons {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val tmp = Path()

    private enum class Kind { CLEAR, FEW, PARTLY, CLOUDY, FOG, DRIZZLE, RAIN, HEAVY_RAIN, FREEZING, SNOW, SLEET, SHOWER, SNOW_SHOWER, THUNDER }

    private fun kind(code: Int): Kind = when (code) {
        0 -> Kind.CLEAR
        1 -> Kind.FEW
        2 -> Kind.PARTLY
        3 -> Kind.CLOUDY
        45, 48 -> Kind.FOG
        51, 53, 55 -> Kind.DRIZZLE
        56, 57, 66, 67 -> Kind.FREEZING
        61, 63 -> Kind.RAIN
        65 -> Kind.HEAVY_RAIN
        71, 73, 75 -> Kind.SNOW
        77 -> Kind.SLEET
        80, 81, 82 -> Kind.SHOWER
        85, 86 -> Kind.SNOW_SHOWER
        95, 96, 99 -> Kind.THUNDER
        else -> Kind.CLOUDY
    }

    /** Zeichnet das Symbol zentriert in einem Quadrat der Kantenlänge [size]. */
    fun draw(c: Canvas, code: Int, isDay: Boolean, cx: Float, cy: Float, size: Float, p: Ui.Palette) {
        val s = size / 100f   // Symbole sind auf 100×100 entworfen
        val x0 = cx - 50 * s
        val y0 = cy - 50 * s
        fun X(v: Float) = x0 + v * s
        fun Y(v: Float) = y0 + v * s

        when (kind(code)) {
            Kind.CLEAR -> if (isDay) sun(c, X(50f), Y(50f), 21 * s, p) else moon(c, X(50f), Y(50f), 26 * s, p)
            Kind.FEW -> {
                if (isDay) sun(c, X(44f), Y(42f), 19 * s, p) else moon(c, X(44f), Y(42f), 22 * s, p)
                cloud(c, X(64f), Y(70f), 0.55f * s, p.cloud, p)
            }
            Kind.PARTLY -> {
                if (isDay) sun(c, X(38f), Y(36f), 17 * s, p) else moon(c, X(38f), Y(36f), 20 * s, p)
                cloud(c, X(57f), Y(64f), 0.78f * s, p.cloud, p)
            }
            Kind.CLOUDY -> {
                cloud(c, X(64f), Y(44f), 0.55f * s, p.cloudDark, p)
                cloud(c, X(48f), Y(60f), 0.85f * s, p.cloud, p)
            }
            Kind.FOG -> {
                cloud(c, X(50f), Y(44f), 0.75f * s, p.cloudDark, p)
                lines(c, X(20f), Y(66f), 60 * s, 3, 10 * s, 5 * s, p.cloud)
            }
            Kind.DRIZZLE -> {
                cloud(c, X(50f), Y(42f), 0.85f * s, p.cloud, p)
                drops(c, X(50f), Y(72f), s, 3, 6f, p.rain)
            }
            Kind.RAIN -> {
                cloud(c, X(50f), Y(42f), 0.85f * s, p.cloud, p)
                drops(c, X(50f), Y(72f), s, 3, 12f, p.rain)
            }
            Kind.HEAVY_RAIN -> {
                cloud(c, X(50f), Y(42f), 0.85f * s, p.cloudDark, p)
                drops(c, X(50f), Y(72f), s, 4, 15f, p.rain)
            }
            Kind.FREEZING -> {
                cloud(c, X(50f), Y(42f), 0.85f * s, p.cloud, p)
                drops(c, X(42f), Y(72f), s, 2, 12f, p.rain)
                flake(c, X(70f), Y(80f), 7 * s, p.snow)
            }
            Kind.SNOW -> {
                cloud(c, X(50f), Y(42f), 0.85f * s, p.cloud, p)
                flake(c, X(34f), Y(76f), 7 * s, p.snow)
                flake(c, X(52f), Y(84f), 7 * s, p.snow)
                flake(c, X(70f), Y(76f), 7 * s, p.snow)
            }
            Kind.SLEET -> {
                cloud(c, X(50f), Y(42f), 0.85f * s, p.cloud, p)
                dots(c, X(50f), Y(78f), s, p.snow)
            }
            Kind.SHOWER -> {
                if (isDay) sun(c, X(34f), Y(30f), 14 * s, p) else moon(c, X(34f), Y(30f), 17 * s, p)
                cloud(c, X(56f), Y(50f), 0.78f * s, p.cloud, p)
                drops(c, X(56f), Y(78f), s, 3, 11f, p.rain)
            }
            Kind.SNOW_SHOWER -> {
                if (isDay) sun(c, X(34f), Y(30f), 14 * s, p) else moon(c, X(34f), Y(30f), 17 * s, p)
                cloud(c, X(56f), Y(50f), 0.78f * s, p.cloud, p)
                flake(c, X(44f), Y(82f), 6.5f * s, p.snow)
                flake(c, X(66f), Y(82f), 6.5f * s, p.snow)
            }
            Kind.THUNDER -> {
                cloud(c, X(50f), Y(40f), 0.85f * s, p.cloudDark, p)
                bolt(c, X(50f), Y(60f), s, p.sun)
                drops(c, X(50f), Y(76f), s, 2, 9f, p.rain, gap = 34f)
            }
        }
    }

    private fun sun(c: Canvas, cx: Float, cy: Float, r: Float, p: Ui.Palette) {
        paint.style = Paint.Style.FILL
        paint.color = p.sun
        c.drawCircle(cx, cy, r, paint)
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = r * 0.22f
        paint.strokeCap = Paint.Cap.ROUND
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val r1 = r * 1.38f
            val r2 = r * 1.72f
            c.drawLine(
                cx + (cos(a) * r1).toFloat(), cy + (sin(a) * r1).toFloat(),
                cx + (cos(a) * r2).toFloat(), cy + (sin(a) * r2).toFloat(), paint
            )
        }
        paint.style = Paint.Style.FILL
    }

    private fun moon(c: Canvas, cx: Float, cy: Float, r: Float, p: Ui.Palette) {
        path.reset(); tmp.reset()
        path.addCircle(cx, cy, r, Path.Direction.CW)
        tmp.addCircle(cx + r * 0.55f, cy - r * 0.38f, r * 0.85f, Path.Direction.CW)
        path.op(tmp, Path.Op.DIFFERENCE)
        paint.style = Paint.Style.FILL
        paint.color = if (p.dark) 0xFFE9E4C9.toInt() else 0xFF9AA3B5.toInt()
        c.drawPath(path, paint)
    }

    /** Wolke mit Mittelpunkt (cx, cy); Grundbreite 80 Einheiten × k. */
    private fun cloud(c: Canvas, cx: Float, cy: Float, k: Float, color: Int, p: Ui.Palette) {
        path.reset(); tmp.reset()
        val w = 40f * k
        // Grundkörper
        path.addRoundRect(RectF(cx - w, cy - 2 * k, cx + w, cy + 22 * k), 12 * k, 12 * k, Path.Direction.CW)
        tmp.addCircle(cx - 14 * k, cy - 2 * k, 17 * k, Path.Direction.CW); path.op(tmp, Path.Op.UNION); tmp.reset()
        tmp.addCircle(cx + 10 * k, cy - 8 * k, 23 * k, Path.Direction.CW); path.op(tmp, Path.Op.UNION); tmp.reset()
        // Feiner Rand trennt die Wolke vom Hintergrund und von Sonne/Mond
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 5 * k
        paint.color = p.surface
        c.drawPath(path, paint)
        paint.style = Paint.Style.FILL
        paint.color = color
        c.drawPath(path, paint)
    }

    private fun drops(c: Canvas, cx: Float, cy: Float, s: Float, n: Int, len: Float, color: Int, gap: Float = 0f) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = 5.5f * s
        paint.color = color
        val spacing = if (gap > 0) gap else 15f
        val start = cx - (n - 1) * spacing * s / 2
        for (i in 0 until n) {
            val x = start + i * spacing * s
            val y = cy + (if (i % 2 == 1) 5 * s else 0f) - len * s / 2
            c.drawLine(x + 2.5f * s, y, x - 2.5f * s, y + len * s, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun dots(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        paint.style = Paint.Style.FILL
        paint.color = color
        for ((dx, dy) in listOf(-16f to -4f, 0f to 6f, 16f to -4f, -8f to 14f, 8f to 14f)) c.drawCircle(cx + dx * s, cy + dy * s, 3.6f * s, paint)
    }

    private fun flake(c: Canvas, cx: Float, cy: Float, r: Float, color: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = r * 0.42f
        paint.color = color
        for (i in 0 until 3) {
            val a = Math.toRadians(i * 60.0 + 90)
            val dx = (cos(a) * r).toFloat()
            val dy = (sin(a) * r).toFloat()
            c.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun lines(c: Canvas, x: Float, y: Float, w: Float, n: Int, step: Float, th: Float, color: Int) {
        paint.style = Paint.Style.STROKE
        paint.strokeCap = Paint.Cap.ROUND
        paint.strokeWidth = th
        paint.color = color
        for (i in 0 until n) {
            val inset = if (i % 2 == 1) w * 0.12f else 0f
            c.drawLine(x + inset, y + i * step, x + w - inset, y + i * step, paint)
        }
        paint.style = Paint.Style.FILL
    }

    private fun bolt(c: Canvas, cx: Float, cy: Float, s: Float, color: Int) {
        path.reset()
        path.moveTo(cx + 2 * s, cy - 8 * s)
        path.lineTo(cx - 9 * s, cy + 9 * s)
        path.lineTo(cx - 1 * s, cy + 9 * s)
        path.lineTo(cx - 5 * s, cy + 26 * s)
        path.lineTo(cx + 10 * s, cy + 4 * s)
        path.lineTo(cx + 2 * s, cy + 4 * s)
        path.lineTo(cx + 7 * s, cy - 8 * s)
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = color
        c.drawPath(path, paint)
    }

    /** Kleines Blitz-Abzeichen (Gewitterrisiko), zentriert bei (cx, cy). */
    fun boltBadge(c: Canvas, cx: Float, cy: Float, size: Float, color: Int, bg: Int) {
        paint.style = Paint.Style.FILL
        paint.color = bg
        c.drawCircle(cx, cy, size * 0.62f, paint)
        val u = size * 0.78f / 34f
        bolt(c, cx - 0.5f * u, cy - 9f * u, u, color)
    }

    /** Windpfeil: zeigt, wohin der Wind weht (Richtungsangabe = woher). */
    fun windArrow(c: Canvas, cx: Float, cy: Float, size: Float, fromDeg: Double, color: Int) {
        if (fromDeg.isNaN()) return
        c.save()
        c.rotate((fromDeg + 180).toFloat(), cx, cy)
        path.reset()
        val h = size / 2
        path.moveTo(cx, cy - h)
        path.lineTo(cx + h * 0.62f, cy + h * 0.85f)
        path.lineTo(cx, cy + h * 0.45f)
        path.lineTo(cx - h * 0.62f, cy + h * 0.85f)
        path.close()
        paint.style = Paint.Style.FILL
        paint.color = color
        c.drawPath(path, paint)
        c.restore()
    }
}
