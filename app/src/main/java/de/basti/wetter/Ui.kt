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

    data class Palette(
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
        val warm: Int,
        val glass: Boolean = false   // Hintergrund ist Bild/Verlauf → Karten halbtransparent
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

    /** Wählt die Palette nach Einstellung (Automatisch / Dunkel / Hell) und eigenem Aussehen. Standard: Dunkel. */
    fun palette(ctx: Context): Palette {
        val night = (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val base = when (Prefs.theme(ctx)) {
            Prefs.THEME_LIGHT -> LIGHT
            Prefs.THEME_AUTO -> if (night) DARK else LIGHT
            else -> DARK
        }
        applyStyle(ctx)
        return customize(ctx, base)
    }

    // ------------------------------------------------------------ Eigenes Aussehen

    class Accent(val name: String, val dark: Int, val light: Int)

    val ACCENTS = listOf(
        Accent("Mint", 0xFF5BE3A8.toInt(), 0xFF0E9F6E.toInt()),
        Accent("Blau", 0xFF5AAEFF.toInt(), 0xFF1F74D6.toInt()),
        Accent("Türkis", 0xFF3DD6D0.toInt(), 0xFF0E8F8B.toInt()),
        Accent("Violett", 0xFFA98BFA.toInt(), 0xFF7346D6.toInt()),
        Accent("Rosa", 0xFFF47CBC.toInt(), 0xFFC93783.toInt()),
        Accent("Koralle", 0xFFFF8070.toInt(), 0xFFD9493A.toInt()),
        Accent("Orange", 0xFFFFAD52.toInt(), 0xFFC96A00.toInt()),
        Accent("Gelb", 0xFFF2D24B.toInt(), 0xFFA67C00.toInt())
    )

    /** Neutrale Hintergrundtöne: (Name, dunkel bg, dunkel Karte, hell bg, hell Karte). */
    class Tone(val name: String, val darkBg: Int, val darkSurface: Int, val lightBg: Int, val lightSurface: Int)

    val TONES = listOf(
        Tone("Schwarz", 0xFF0B0C0E.toInt(), 0xFF15171A.toInt(), 0xFFF3F4F6.toInt(), 0xFFFFFFFF.toInt()),
        Tone("Anthrazit", 0xFF1A1C20.toInt(), 0xFF25282D.toInt(), 0xFFE9EAEC.toInt(), 0xFFF8F8F9.toInt()),
        Tone("Nachtblau", 0xFF0A1120.toInt(), 0xFF131D31.toInt(), 0xFFEDF2F8.toInt(), 0xFFFAFCFF.toInt()),
        Tone("Waldgrün", 0xFF0A130F.toInt(), 0xFF14211A.toInt(), 0xFFEDF3EE.toInt(), 0xFFFAFDFA.toInt()),
        Tone("Aubergine", 0xFF130B16.toInt(), 0xFF211627.toInt(), 0xFFF3EFF6.toInt(), 0xFFFDFBFE.toInt())
    )

    /** Breite des neutralen Abschnitts am linken Ende des Hintergrund-Reglers. */
    const val TONE_NEUTRAL = 24
    const val TONE_MAX = TONE_NEUTRAL + 359

    fun hueOf(color: Int): Int {
        val hsv = FloatArray(3)
        android.graphics.Color.colorToHSV(color, hsv)
        return hsv[0].toInt().coerceIn(0, 359)
    }

    /** HSL → Farbe. */
    fun hsl(h: Float, s: Float, l: Float): Int {
        val c = (1 - kotlin.math.abs(2 * l - 1)) * s
        val hp = ((h % 360) + 360) % 360 / 60f
        val x = c * (1 - kotlin.math.abs(hp % 2 - 1))
        val (r, g, b) = when (hp.toInt()) {
            0 -> Triple(c, x, 0f); 1 -> Triple(x, c, 0f); 2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c); 4 -> Triple(x, 0f, c); else -> Triple(c, 0f, x)
        }
        val m = l - c / 2
        fun ch(v: Float) = ((v + m) * 255).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }

    /**
     * Akzentfarbe zu einem Farbton – Helligkeit so gewählt, dass sie lesbar bleibt:
     * im Dunkelmodus hell und leuchtend, im Hellmodus kräftig und dunkel genug für weißen Grund.
     */
    fun accentFor(hue: Int, dark: Boolean): Int {
        if (dark) {
            var l = 0.62f
            var c = hsl(hue.toFloat(), 0.85f, l)
            while (luminance(c) < 0.30 && l < 0.80f) { l += 0.02f; c = hsl(hue.toFloat(), 0.85f, l) }
            return c
        }
        var l = 0.46f
        var c = hsl(hue.toFloat(), 0.80f, l)
        while (luminance(c) > 0.20 && l > 0.22f) { l -= 0.02f; c = hsl(hue.toFloat(), 0.80f, l) }
        return c
    }

    /** Hintergrundton zum Reglerwert (neutral oder dunkel/hell getönt). */
    fun toneFor(v: Int): Tone {
        if (v < TONE_NEUTRAL) return TONES[0]
        val h = (v - TONE_NEUTRAL).toFloat()
        return Tone("", hsl(h, 0.50f, 0.065f), hsl(h, 0.38f, 0.115f), hsl(h, 0.35f, 0.94f), hsl(h, 0.60f, 0.985f))
    }

    /** Farbe für die Reglerleiste (zeigt den Farbton deutlich, auch wenn der echte Ton sehr dunkel ist). */
    fun toneSwatch(v: Int, dark: Boolean): Int =
        if (v < TONE_NEUTRAL) (if (dark) 0xFF3A3D42.toInt() else 0xFFCFD2D6.toInt())
        else hsl((v - TONE_NEUTRAL).toFloat(), 0.45f, if (dark) 0.32f else 0.80f)

    fun tone(ctx: Context): Tone = toneFor(Prefs.toneValue(ctx))

    const val BG_NEUTRAL = 0
    const val BG_GRADIENT = 1
    const val BG_IMAGE = 2

    /** Eckenrundung (Faktor auf alle Radien): rund, weich, eckig. */
    val CORNER_SCALES = floatArrayOf(1f, 0.55f, 0.18f)
    val TEXT_SCALES = floatArrayOf(0.92f, 1f, 1.1f)

    @Volatile var cornerScale = 1f
        private set
    @Volatile var textScale = 1f
        private set

    var TF_REGULAR: Typeface = Typeface.DEFAULT
        private set
    var TF_MEDIUM: Typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        private set
    var TF_LIGHT: Typeface = Typeface.create("sans-serif-light", Typeface.NORMAL)
        private set

    private var roundFonts: Triple<Typeface, Typeface, Typeface>? = null

    /** Runde Schrift (Nunito) aus den App-Daten; fehlt sie, gilt die Standardschrift. */
    private fun rounded(ctx: Context): Triple<Typeface, Typeface, Typeface>? {
        roundFonts?.let { return it }
        return try {
            fun w(weight: Int) = Typeface.Builder(ctx.assets, "fonts/nunito.ttf")
                .setFontVariationSettings("'wght' $weight").build()
            val t = Triple(w(400), w(650), w(300))
            roundFonts = t
            t
        } catch (e: Exception) { null }
    }

    /** Schrift, Schriftgröße und Ecken aus den Einstellungen übernehmen. */
    fun applyStyle(ctx: Context) {
        cornerScale = CORNER_SCALES[Prefs.corners(ctx).coerceIn(0, 2)]
        textScale = TEXT_SCALES[Prefs.fontSize(ctx).coerceIn(0, 2)]
        when (Prefs.fontStyle(ctx)) {
            1 -> {
                TF_REGULAR = Typeface.create("sans-serif-condensed", Typeface.NORMAL)
                TF_MEDIUM = Typeface.create("sans-serif-condensed", Typeface.BOLD)
                TF_LIGHT = Typeface.create("sans-serif-condensed-light", Typeface.NORMAL)
            }
            2 -> {
                val r = rounded(ctx)
                TF_REGULAR = r?.first ?: Typeface.DEFAULT
                TF_MEDIUM = r?.second ?: Typeface.create("sans-serif-medium", Typeface.NORMAL)
                TF_LIGHT = r?.third ?: Typeface.create("sans-serif-light", Typeface.NORMAL)
            }
            else -> {
                TF_REGULAR = Typeface.DEFAULT
                TF_MEDIUM = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                TF_LIGHT = Typeface.create("sans-serif-light", Typeface.NORMAL)
            }
        }
    }

    fun luminance(c: Int): Double {
        fun ch(v: Int): Double { val x = v / 255.0; return if (x <= 0.03928) x / 12.92 else Math.pow((x + 0.055) / 1.055, 2.4) }
        return 0.2126 * ch((c shr 16) and 0xFF) + 0.7152 * ch((c shr 8) and 0xFF) + 0.0722 * ch(c and 0xFF)
    }

    /** Akzentfarbe, Hintergrundton und Glas-Effekt auf die Grundpalette anwenden. */
    fun customize(ctx: Context, base: Palette): Palette {
        val tone = tone(ctx)
        val accent = accentFor(Prefs.accentHue(ctx), base.dark)
        val onAccent = if (luminance(accent) > 0.4) 0xFF0B0C0E.toInt() else 0xFFFFFFFF.toInt()
        val bg = if (base.dark) tone.darkBg else tone.lightBg
        val surface0 = if (base.dark) tone.darkSurface else tone.lightSurface
        val glass = Prefs.bgStyle(ctx) != BG_NEUTRAL
        val surface = if (glass) withAlpha(surface0, if (base.dark) 0xC8 else 0xD9) else surface0
        val surfaceHi = if (base.dark) mix(surface0, 0xFFFFFFFF.toInt(), 0.07f) else mix(surface0, 0xFF000000.toInt(), 0.06f)
        return base.copy(
            bg = bg, surface = surface,
            surfaceHi = if (glass) withAlpha(surfaceHi, 0xE0) else surfaceHi,
            accent = accent, onAccent = onAccent,
            border = if (glass) (if (base.dark) 0x26FFFFFF else 0x1F000000) else base.border,
            glass = glass
        )
    }

    /** Seitenhintergrund: neutraler Ton, Verlauf oder eigenes Bild (abgedunkelt bzw. aufgehellt). */
    fun background(ctx: Context, p: Palette): android.graphics.drawable.Drawable {
        when (Prefs.bgStyle(ctx)) {
            BG_GRADIENT -> {
                val far = mix(p.bg, p.accent, if (p.dark) 0.22f else 0.18f)
                return GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(far, p.bg, p.bg))
            }
            BG_IMAGE -> {
                val bmp = BgImage.load(ctx)
                if (bmp != null) {
                    val img = android.graphics.drawable.BitmapDrawable(ctx.resources, bmp)
                    img.gravity = android.view.Gravity.FILL
                    // Deckkraft der Überlagerung: Regler (0–100) und Bildhelligkeit; Bild bleibt immer gedämpft
                    val vis = Prefs.bgVisibility(ctx).coerceIn(0, 100) / 100f
                    val lum = BgImage.luminance(ctx)
                    val needed = if (p.dark) 0.45f + 0.35f * lum else 0.45f + 0.35f * (1 - lum)
                    val alpha = (1f - vis * 0.55f).coerceIn(needed, 0.95f)
                    val over = ColorDrawable(withAlpha(p.bg, (alpha * 255).toInt()))
                    return android.graphics.drawable.LayerDrawable(arrayOf(img, over))
                }
            }
        }
        return ColorDrawable(p.bg)
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
            cornerRadius = radiusPx * cornerScale
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

    /** Hintergrund der Seite und Fenster setzen (auch Bild/Verlauf). */
    fun applyBackground(a: Activity, root: View, p: Palette) {
        val d = background(a, p)
        root.background = d
        a.window.setBackgroundDrawable(ColorDrawable(p.bg))
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
            textSize = sizeSp * textScale
            setTextColor(color)
            typeface = if (bold) TF_MEDIUM else TF_REGULAR
            includeFontPadding = false
        }

}
