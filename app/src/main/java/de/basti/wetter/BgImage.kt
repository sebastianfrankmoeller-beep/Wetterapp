package de.basti.wetter

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/** Eigenes Hintergrundbild: wird beim Übernehmen verkleinert, weichgezeichnet und gespeichert. */
object BgImage {
    private const val FILE = "hintergrund.jpg"
    @Volatile private var cached: Bitmap? = null
    @Volatile private var cachedStamp = 0L

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    fun exists(ctx: Context) = file(ctx).exists()

    fun load(ctx: Context): Bitmap? {
        val f = file(ctx)
        if (!f.exists()) return null
        if (cached != null && cachedStamp == f.lastModified()) return cached
        val b = BitmapFactory.decodeFile(f.absolutePath) ?: return null
        cached = b
        cachedStamp = f.lastModified()
        return b
    }

    fun luminance(ctx: Context): Float = Prefs.bgLuminance(ctx)

    /** Übernimmt ein Bild aus der Galerie (Hintergrund-Thread). */
    fun import(ctx: Context, uri: Uri): Boolean {
        val cr = ctx.contentResolver
        // Größe ermitteln und grob verkleinert laden
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        cr.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) } ?: return false
        var sample = 1
        while (max(o.outWidth, o.outHeight) / (sample * 2) >= 1200) sample *= 2
        val src = cr.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return false

        // Auf Bildschirmgröße zuschneiden (Hochformat) und weichzeichnen
        val dm = ctx.resources.displayMetrics
        val tw = 720
        val th = (tw * dm.heightPixels.toFloat() / dm.widthPixels).roundToInt().coerceIn(900, 1800)
        val scale = max(tw.toFloat() / src.width, th.toFloat() / src.height)
        val cropped = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
        Canvas(cropped).apply {
            translate((tw - src.width * scale) / 2, (th - src.height * scale) / 2)
            scale(scale, scale)
            drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        src.recycle()
        val blurred = blur(cropped)
        cropped.recycle()

        // Mittlere Helligkeit für die automatische Abdunkelung
        val small = Bitmap.createScaledBitmap(blurred, 24, 40, true)
        var sum = 0.0
        for (y in 0 until small.height) for (x in 0 until small.width) {
            val c = small.getPixel(x, y)
            sum += (0.2126 * ((c shr 16) and 0xFF) + 0.7152 * ((c shr 8) and 0xFF) + 0.0722 * (c and 0xFF)) / 255.0
        }
        Prefs.setBgLuminance(ctx, (sum / (small.width * small.height)).toFloat())
        small.recycle()

        file(ctx).outputStream().use { blurred.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        cached = null
        blurred.recycle()
        return true
    }

    fun delete(ctx: Context) {
        file(ctx).delete()
        cached = null
    }

    /** Weichzeichnen durch mehrfaches Verkleinern und Vergrößern mit Glättung. */
    private fun blur(b: Bitmap): Bitmap {
        var cur = b
        val w = b.width; val h = b.height
        val tiny = Bitmap.createScaledBitmap(cur, max(1, w / 14), max(1, h / 14), true)
        val mid = Bitmap.createScaledBitmap(tiny, max(1, w / 5), max(1, h / 5), true)
        cur = Bitmap.createScaledBitmap(mid, w, h, true)
        tiny.recycle(); mid.recycle()
        return cur
    }
}
