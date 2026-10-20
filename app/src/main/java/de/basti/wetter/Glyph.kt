package de.basti.wetter

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.View
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Kleine, selbst gezeichnete Bedien-Symbole (Lupe, Stern, Zahnrad …). */
@SuppressLint("ViewConstructor")
class GlyphView(ctx: Context, var kind: Kind, var color: Int, private val sizeDp: Int = 22) : View(ctx) {

    enum class Kind { BOLT, SNOW, WARNING, BELL, SEARCH, STAR, STAR_FILLED, GEAR, LOCATE, REFRESH, CLOSE, PLAY, PAUSE, FORECAST, RADAR, WIND, PIN, BACK }

    private val d = resources.displayMetrics.density
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val path = Path()

    fun set(kind: Kind, color: Int = this.color) {
        this.kind = kind
        this.color = color
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val s = (sizeDp * d).toInt()
        setMeasuredDimension(resolveSize(s, widthMeasureSpec), resolveSize(s, heightMeasureSpec))
    }

    override fun onDraw(c: Canvas) {
        val s = min(width, height).toFloat()
        val cx = width / 2f
        val cy = height / 2f
        draw(c, kind, cx, cy, s * 0.9f, color, paint, path)
    }

    companion object {
        /** Zeichnet ein Symbol in ein Quadrat der Kantenlänge [s] (24er-Raster). */
        fun draw(c: Canvas, kind: Kind, cx: Float, cy: Float, s: Float, color: Int, paint: Paint, path: Path) {
            val u = s / 24f
            fun X(v: Float) = cx + (v - 12) * u
            fun Y(v: Float) = cy + (v - 12) * u
            paint.color = color
            paint.strokeWidth = 2f * u
            paint.style = Paint.Style.STROKE
            path.reset()
            when (kind) {
                Kind.SEARCH -> {
                    c.drawCircle(X(10.5f), Y(10.5f), 6.5f * u, paint)
                    c.drawLine(X(15.5f), Y(15.5f), X(20.5f), Y(20.5f), paint)
                }
                Kind.STAR, Kind.STAR_FILLED -> {
                    for (i in 0 until 10) {
                        val r = (if (i % 2 == 0) 9.5f else 4.2f) * u
                        val a = Math.toRadians(-90.0 + i * 36)
                        val x = cx + (cos(a) * r).toFloat()
                        val y = cy + 0.6f * u + (sin(a) * r).toFloat()
                        if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
                    }
                    path.close()
                    if (kind == Kind.STAR_FILLED) paint.style = Paint.Style.FILL_AND_STROKE
                    c.drawPath(path, paint)
                }
                Kind.GEAR -> {
                    for (i in 0 until 8) {
                        val a = Math.toRadians(i * 45.0)
                        c.drawLine(
                            cx + (cos(a) * 6.5f * u).toFloat(), cy + (sin(a) * 6.5f * u).toFloat(),
                            cx + (cos(a) * 9.5f * u).toFloat(), cy + (sin(a) * 9.5f * u).toFloat(), paint.apply { strokeWidth = 2.6f * u }
                        )
                    }
                    paint.strokeWidth = 2f * u
                    c.drawCircle(cx, cy, 6.5f * u, paint)
                    c.drawCircle(cx, cy, 2.6f * u, paint)
                }
                Kind.LOCATE -> {
                    c.drawCircle(cx, cy, 6.5f * u, paint)
                    c.drawLine(X(12f), Y(2f), X(12f), Y(5.5f), paint)
                    c.drawLine(X(12f), Y(18.5f), X(12f), Y(22f), paint)
                    c.drawLine(X(2f), Y(12f), X(5.5f), Y(12f), paint)
                    c.drawLine(X(18.5f), Y(12f), X(22f), Y(12f), paint)
                    paint.style = Paint.Style.FILL
                    c.drawCircle(cx, cy, 2.4f * u, paint)
                }
                Kind.PIN -> {
                    path.moveTo(X(12f), Y(21.5f))
                    path.cubicTo(X(5f), Y(14f), X(5f), Y(12f), X(5f), Y(9.5f))
                    path.arcTo(RectF(X(5f), Y(2.5f), X(19f), Y(16.5f)), 180f, 180f)
                    path.cubicTo(X(19f), Y(12f), X(19f), Y(14f), X(12f), Y(21.5f))
                    path.close()
                    paint.style = Paint.Style.FILL
                    c.drawPath(path, paint)
                    paint.color = 0x55000000
                    c.drawCircle(X(12f), Y(9.5f), 2.6f * u, paint)
                }
                Kind.REFRESH -> {
                    c.drawArc(RectF(X(4.5f), Y(4.5f), X(19.5f), Y(19.5f)), -60f, 300f, false, paint)
                    path.moveTo(X(15.8f), Y(2.6f))
                    path.lineTo(X(16.6f), Y(6.8f))
                    path.lineTo(X(12.4f), Y(7.6f))
                    c.drawPath(path, paint)
                }
                Kind.BOLT -> {
                    path.moveTo(X(13.5f), Y(2f))
                    path.lineTo(X(5f), Y(13.5f))
                    path.lineTo(X(11f), Y(13.5f))
                    path.lineTo(X(9.5f), Y(22f))
                    path.lineTo(X(19f), Y(9.5f))
                    path.lineTo(X(13f), Y(9.5f))
                    path.close()
                    paint.style = Paint.Style.FILL_AND_STROKE
                    paint.strokeWidth = 1.2f * u
                    c.drawPath(path, paint)
                }
                Kind.SNOW -> {
                    for (i in 0 until 3) {
                        val a = Math.toRadians(i * 60.0 + 90)
                        val dx = (cos(a) * 9.5f * u).toFloat()
                        val dy = (sin(a) * 9.5f * u).toFloat()
                        c.drawLine(cx - dx, cy - dy, cx + dx, cy + dy, paint)
                    }
                    paint.style = Paint.Style.FILL
                    c.drawCircle(cx, cy, 2.2f * u, paint)
                }
                Kind.WARNING -> {
                    path.moveTo(X(12f), Y(3f))
                    path.lineTo(X(22f), Y(20.5f))
                    path.lineTo(X(2f), Y(20.5f))
                    path.close()
                    paint.style = Paint.Style.FILL_AND_STROKE
                    paint.strokeWidth = 1.6f * u
                    c.drawPath(path, paint)
                    paint.color = 0xFF15171A.toInt()
                    paint.strokeWidth = 2.2f * u
                    paint.style = Paint.Style.STROKE
                    c.drawLine(X(12f), Y(9.5f), X(12f), Y(14.5f), paint)
                    paint.style = Paint.Style.FILL
                    c.drawCircle(X(12f), Y(17.6f), 1.3f * u, paint)
                }
                Kind.BELL -> {
                    path.moveTo(X(5f), Y(17f))
                    path.lineTo(X(19f), Y(17f))
                    path.lineTo(X(17.5f), Y(14.5f))
                    path.lineTo(X(17.5f), Y(10.5f))
                    path.cubicTo(X(17.5f), Y(4f), X(6.5f), Y(4f), X(6.5f), Y(10.5f))
                    path.lineTo(X(6.5f), Y(14.5f))
                    path.close()
                    c.drawPath(path, paint)
                    c.drawLine(X(10f), Y(20.5f), X(14f), Y(20.5f), paint)
                }
                Kind.CLOSE -> {
                    c.drawLine(X(6f), Y(6f), X(18f), Y(18f), paint)
                    c.drawLine(X(18f), Y(6f), X(6f), Y(18f), paint)
                }
                Kind.BACK -> {
                    c.drawLine(X(19f), Y(12f), X(5f), Y(12f), paint)
                    path.moveTo(X(11f), Y(6f)); path.lineTo(X(5f), Y(12f)); path.lineTo(X(11f), Y(18f))
                    c.drawPath(path, paint)
                }
                Kind.PLAY -> {
                    path.moveTo(X(7.5f), Y(4.5f)); path.lineTo(X(19.5f), Y(12f)); path.lineTo(X(7.5f), Y(19.5f)); path.close()
                    paint.style = Paint.Style.FILL_AND_STROKE
                    c.drawPath(path, paint)
                }
                Kind.PAUSE -> {
                    paint.style = Paint.Style.FILL
                    c.drawRoundRect(RectF(X(6f), Y(4.5f), X(10f), Y(19.5f)), u, u, paint)
                    c.drawRoundRect(RectF(X(14f), Y(4.5f), X(18f), Y(19.5f)), u, u, paint)
                }
                Kind.FORECAST -> {
                    // kleine Sonne mit Wolke
                    c.drawCircle(X(9f), Y(9f), 4f * u, paint)
                    path.moveTo(X(8f), Y(20f))
                    path.lineTo(X(18f), Y(20f))
                    path.arcTo(RectF(X(14.5f), Y(13f), X(21.5f), Y(20f)), 90f, -180f)
                    path.arcTo(RectF(X(8.5f), Y(10f), X(17.5f), Y(19f)), -20f, -130f)
                    path.arcTo(RectF(X(5f), Y(14f), X(11f), Y(20f)), -90f, -180f)
                    c.drawPath(path, paint)
                }
                Kind.RADAR -> {
                    c.drawCircle(cx, cy, 9.5f * u, paint)
                    c.drawCircle(cx, cy, 5f * u, paint)
                    c.drawLine(cx, cy, X(19f), Y(5f), paint)
                    paint.style = Paint.Style.FILL
                    c.drawCircle(cx, cy, 1.6f * u, paint)
                }
                Kind.WIND -> {
                    path.moveTo(X(3f), Y(9f)); path.lineTo(X(14f), Y(9f))
                    path.cubicTo(X(17.5f), Y(9f), X(17.5f), Y(4f), X(14.5f), Y(4f))
                    c.drawPath(path, paint)
                    path.reset()
                    path.moveTo(X(3f), Y(14f)); path.lineTo(X(18f), Y(14f))
                    path.cubicTo(X(22f), Y(14f), X(22f), Y(20f), X(18f), Y(20f))
                    c.drawPath(path, paint)
                }
            }
            paint.style = Paint.Style.STROKE
        }
    }
}
