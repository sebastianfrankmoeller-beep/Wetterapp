package de.basti.wetter

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/** Farben und kleine Zeichenhelfer – gleiche Designsprache wie der Tempowarner. */
object Ui {

    class Palette(
        val dark: Boolean,
        val bg: Int,          // Bildschirmhintergrund
        val surface: Int,     // Karten
        val surfaceHi: Int,   // erhöhte Flächen (Buttons, Eingabefelder)
        val border: Int,      // feine Linien
        val text: Int,
        val muted: Int,
        val accent: Int,
        val onAccent: Int,
        val danger: Int,
        val warn: Int,
        val trackOff: Int,
        // Wetterfarben
        val sun: Int,
        val cloud: Int,
        val cloudDark: Int,
        val rain: Int,
        val snow: Int,
        val cold: Int,
        val warm: Int
    )

    private fun c(v: Long) = v.toInt()

    val DARK = Palette(
        dark = true,
        bg = c(0xFF0B0C0E),
        surface = c(0xFF15171A),
        surfaceHi = c(0xFF212429),
        border = c(0x1AFFFFFF),
        text = c(0xFFF2F3F5),
        muted = c(0xFF8B9098),
        accent = c(0xFF5BE3A8),
        onAccent = c(0xFF06251A),
        danger = c(0xFFFF5A4F),
        warn = c(0xFFF5B544),
        trackOff = c(0xFF3A3F46),
        sun = c(0xFFF5B544),
        cloud = c(0xFFD5DAE1),
        cloudDark = c(0xFF8E96A1),
        rain = c(0xFF4EA8FF),
        snow = c(0xFFE8F1FF),
        cold = c(0xFF4EA8FF),
        warm = c(0xFFFF8A4C)
    )

    val LIGHT = Palette(
        dark = false,
        bg = c(0xFFF3F4F6),
        surface = c(0xFFFFFFFF),
        surfaceHi = c(0xFFECEEF1),
        border = c(0x14000000),
        text = c(0xFF121316),
        muted = c(0xFF6B7078),
        accent = c(0xFF0E9F6E),
        onAccent = c(0xFFFFFFFF),
        danger = c(0xFFE5392F),
        warn = c(0xFFC77700),
        trackOff = c(0xFFC9CDD3),
        sun = c(0xFFF2A516),
        cloud = c(0xFFB9C1CC),
        cloudDark = c(0xFF7D8794),
        rain = c(0xFF1F7FE0),
        snow = c(0xFF9DB7DA),
        cold = c(0xFF1F7FE0),
        warm = c(0xFFE8642A)
    )

    /** Wählt die Palette nach Einstellung (Automatisch / Dunkel / Hell). Standard: Dunkel. */
    fun palette(ctx: Context): Palette {
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        return when (Prefs.theme(ctx)) {
            Prefs.THEME_LIGHT -> LIGHT
            Prefs.THEME_AUTO -> if (night) DARK else LIGHT
            else -> DARK
        }
    }

    fun withAlpha(color: Int, alpha: Int): Int = (color and 0x00FFFFFF) or (alpha shl 24)

    /** Mischt zwei Farben (t = 0 → a, t = 1 → b). */
    fun mix(a: Int, b: Int, t: Float): Int {
        val k = t.coerceIn(0f, 1f)
        fun ch(shift: Int) = (((a shr shift) and 0xFF) * (1 - k) + ((b shr shift) and 0xFF) * k).toInt()
        return (ch(24) shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }

    fun rect(color: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = radiusPx
            if (strokePx > 0) setStroke(strokePx, strokeColor)
        }

    fun oval(color: Int): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    /** Fläche mit Berührungs-Welle, die genau der abgerundeten Form folgt. */
    fun pressable(p: Palette, color: Int, radiusPx: Float, strokeColor: Int = 0, strokePx: Int = 0): RippleDrawable {
        val ripple = if (p.dark) 0x33FFFFFF else 0x1F000000
        return RippleDrawable(
            ColorStateList.valueOf(ripple),
            rect(color, radiusPx, strokeColor, strokePx),
            rect(0xFFFFFFFF.toInt(), radiusPx)
        )
    }

    /** Färbt Statusleiste, Navigationsleiste und Fensterhintergrund passend zum Design. */
    @Suppress("DEPRECATION")
    fun applySystemBars(a: Activity, p: Palette, navColor: Int = p.bg) {
        val w = a.window
        w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
        w.statusBarColor = p.bg
        w.navigationBarColor = navColor
        w.setBackgroundDrawable(ColorDrawable(p.bg))
        var flags = w.decorView.systemUiVisibility
        val light = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
        flags = if (p.dark) flags and light.inv() else flags or light
        w.decorView.systemUiVisibility = flags
    }

    fun dp(ctx: Context, v: Number): Int = (v.toFloat() * ctx.resources.displayMetrics.density).toInt()
    fun dpf(ctx: Context, v: Number): Float = v.toFloat() * ctx.resources.displayMetrics.density

    /** Text im App-Stil. */
    fun tv(ctx: Context, text: CharSequence, sizeSp: Float, color: Int, bold: Boolean = false): TextView =
        TextView(ctx).apply {
            this.text = text
            textSize = sizeSp
            setTextColor(color)
            typeface = if (bold) Typeface.create("sans-serif-medium", Typeface.NORMAL) else Typeface.DEFAULT
            includeFontPadding = false
        }

    val TF_LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
    val TF_MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
}
