package de.basti.wetter

import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Absturzbericht: Stürzt die App ab, wird die Fehlermeldung samt der letzten Aktionen in eine Datei
 * geschrieben. Beim nächsten Start zeigt die App den Bericht einmal an (zum Kopieren).
 */
object CrashLog {
    private const val FILE = "absturz.txt"
    private const val MAX_STEPS = 20

    @Volatile private var installed = false
    private var appCtx: Context? = null
    private val steps = ArrayDeque<String>()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.GERMANY)

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        appCtx = ctx.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val main = t === android.os.Looper.getMainLooper().thread
            runCatching { write(t, e, background = !main) }
            // Fehler in Hintergrund-Abläufen (Laden, Widget …) beenden nicht die ganze App:
            // nur dieser Ablauf bricht ab, der Bericht wird gespeichert. Fehler im Hauptablauf lassen sich nicht abfangen.
            if (main) previous?.uncaughtException(t, e)
        }
    }

    /** Merkt sich eine Aktion ("Aktualisieren", "Ort gewählt: Flensburg" …) für den Bericht. */
    fun note(step: String) {
        synchronized(steps) {
            steps.addLast("${synchronized(fmt) { fmt.format(Date()) }}  $step")
            while (steps.size > MAX_STEPS) steps.removeFirst()
        }
    }

    private fun file(ctx: Context) = File(ctx.filesDir, FILE)

    private fun write(t: Thread, e: Throwable, background: Boolean) {
        val ctx = appCtx ?: return
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val version = runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${pi.versionName} (${if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()})"
        }.getOrDefault("?")
        val text = buildString {
            append(if (background) "Fehlerbericht Wetter-App (im Hintergrund abgefangen, App lief weiter)\n" else "Absturzbericht Wetter-App\n")
            append("Zeit: ").append(SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).format(Date())).append('\n')
            append("Version: ").append(version).append('\n')
            append("Gerät: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
            append("Thread: ").append(t.name).append("\n\n")
            append("Letzte Aktionen:\n")
            synchronized(steps) { if (steps.isEmpty()) append("  –\n") else steps.forEach { append("  ").append(it).append('\n') } }
            append("\nFehler:\n")
            // Stapel kürzen, damit der Bericht in den Chat passt
            append(sw.toString().lines().take(80).joinToString("\n"))
        }
        file(ctx).writeText(text)
    }

    /** Abgefangenen Fehler melden (App läuft weiter); erscheint beim nächsten Start als Bericht. */
    fun report(e: Throwable, where: String) {
        note("Fehler abgefangen: $where")
        runCatching { write(Thread.currentThread(), e, background = true) }
    }

    /** Bericht vom letzten Absturz oder null. */
    fun pending(ctx: Context): String? = runCatching { file(ctx).takeIf { it.exists() }?.readText() }.getOrNull()

    fun clear(ctx: Context) { runCatching { file(ctx).delete() } }
}
