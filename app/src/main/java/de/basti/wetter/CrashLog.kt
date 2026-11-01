package de.basti.wetter

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Fehlerberichte: eigene Abstürze und abgefangene Fehler (mit den letzten Aktionen) sowie –
 * ab Android 11 – die Gründe, aus denen Android die App beendet hat (Speicher, Hängen, Grafikabsturz).
 * Die letzten 5 Berichte bleiben gespeichert (Einstellungen → Fehlerberichte).
 */
object CrashLog {
    private const val DIR = "berichte"
    private const val KEEP = 5
    private const val MAX_STEPS = 20

    @Volatile private var installed = false
    private var appCtx: Context? = null
    private val steps = ArrayDeque<String>()
    private var previousSteps: List<String> = emptyList()
    private val fmt = SimpleDateFormat("HH:mm:ss", Locale.GERMANY)

    class Report(val file: File, val time: Long, val kind: String, val text: String)

    fun install(ctx: Context) {
        if (installed) return
        installed = true
        appCtx = ctx.applicationContext
        // Aktionen der vorigen Sitzung für Berichte über von Android beendete Läufe merken
        previousSteps = sp()?.getString("cl_steps", "")?.split('\n')?.filter { it.isNotBlank() } ?: emptyList()
        sp()?.edit()?.putString("cl_steps", "")?.apply()
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            val main = t === android.os.Looper.getMainLooper().thread
            runCatching { writeCrash(t, e, background = !main) }
            // Fehler in Hintergrund-Abläufen (Laden, Widget …) beenden nicht die ganze App:
            // nur dieser Ablauf bricht ab, der Bericht wird gespeichert. Fehler im Hauptablauf lassen sich nicht abfangen.
            if (main) previous?.uncaughtException(t, e)
        }
    }

    private fun sp() = appCtx?.getSharedPreferences("wetter", Context.MODE_PRIVATE)

    /** Merkt sich eine Aktion ("Aktualisieren", "Ort gewählt: Flensburg" …) für den Bericht. */
    fun note(step: String) {
        val joined = synchronized(steps) {
            steps.addLast("${synchronized(fmt) { fmt.format(Date()) }}  $step")
            while (steps.size > MAX_STEPS) steps.removeFirst()
            steps.joinToString("\n")
        }
        // sofort schreiben (commit statt apply): muss auch einen nativen Absturz direkt danach überstehen
        sp()?.edit()?.putString("cl_steps", joined)?.commit()
    }

    private fun dir(ctx: Context) = File(ctx.filesDir, DIR).also { it.mkdirs() }

    private fun header(ctx: Context, title: String): String {
        val version = runCatching {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${pi.versionName} (${if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()})"
        }.getOrDefault("?")
        return buildString {
            append(title).append('\n')
            append("Version: ").append(version).append('\n')
            append("Gerät: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(", Android ").append(Build.VERSION.RELEASE).append(" (API ").append(Build.VERSION.SDK_INT).append(")\n")
        }
    }

    private fun save(ctx: Context, time: Long, kind: String, text: String) {
        val name = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(time)) + "_" + kind.replace(Regex("[^A-Za-z]"), "") + ".txt"
        val f = File(dir(ctx), name)
        f.writeText("$kind\n$text")
        sp()?.edit()?.putString("cl_unseen", f.name)?.commit()
        // nur die letzten 5 behalten
        dir(ctx).listFiles()?.sortedByDescending { it.name }?.drop(KEEP)?.forEach { it.delete() }
    }

    private fun writeCrash(t: Thread, e: Throwable, background: Boolean) {
        val ctx = appCtx ?: return
        val sw = StringWriter()
        e.printStackTrace(PrintWriter(sw))
        val now = System.currentTimeMillis()
        val kind = if (background) "Fehler abgefangen" else "Absturz"
        val text = buildString {
            append(header(ctx, if (background) "Fehlerbericht Wetter-App (im Hintergrund abgefangen, App lief weiter)" else "Absturzbericht Wetter-App"))
            append("Zeit: ").append(SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).format(Date(now))).append('\n')
            append("Thread: ").append(t.name).append("\n\n")
            append("Letzte Aktionen:\n")
            synchronized(steps) { if (steps.isEmpty()) append("  –\n") else steps.forEach { append("  ").append(it).append('\n') } }
            append("\nFehler:\n")
            append(sw.toString().lines().take(80).joinToString("\n"))
        }
        save(ctx, now, kind, text)
    }

    /** Abgefangenen Fehler melden (App läuft weiter). */
    fun report(e: Throwable, where: String) {
        note("Fehler abgefangen: $where")
        runCatching { writeCrash(Thread.currentThread(), e, background = true) }
    }

    /**
     * Ab Android 11: Warum hat Android die App zuletzt beendet? Neue, auffällige Gründe werden als Bericht
     * gespeichert (eigene Java-Abstürze haben schon einen genaueren Bericht).
     */
    fun checkExitReasons(ctx: Context) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = ctx.getSystemService(ActivityManager::class.java) ?: return
            val last = sp()?.getLong("cl_exit_seen", 0L) ?: 0L
            val list = am.getHistoricalProcessExitReasons(ctx.packageName, 0, 10)
            var newest = last
            for (info in list.sortedBy { it.timestamp }) {
                if (info.timestamp <= last) continue
                newest = maxOf(newest, info.timestamp)
                val visible = info.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_VISIBLE
                val (reason, relevant) = when (info.reason) {
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> "Absturz im Grafik-/Systemteil (nativ)" to true
                    ApplicationExitInfo.REASON_ANR -> "App reagierte nicht (hing)" to true
                    ApplicationExitInfo.REASON_LOW_MEMORY -> "Zu wenig Speicher – von Android beendet" to visible
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> "Zu hoher Verbrauch – von Android beendet" to true
                    ApplicationExitInfo.REASON_INITIALIZATION_FAILURE -> "Start fehlgeschlagen" to true
                    ApplicationExitInfo.REASON_SIGNALED -> "Von Android beendet (Signal ${info.status})" to visible
                    ApplicationExitInfo.REASON_DEPENDENCY_DIED -> "Abhängiger Systemdienst beendet" to visible
                    else -> "" to false
                }
                if (!relevant) continue
                val text = buildString {
                    append(header(ctx, "Von Android gemeldet: $reason"))
                    append("Zeit: ").append(SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.GERMANY).format(Date(info.timestamp))).append('\n')
                    append("Zustand: ").append(if (visible) "im Vordergrund" else "im Hintergrund").append('\n')
                    append("Speicher: ").append(info.pss / 1024).append(" MB (PSS), ").append(info.rss / 1024).append(" MB (RSS)\n")
                    info.description?.let { append("Beschreibung: ").append(it).append('\n') }
                    append("\nLetzte Aktionen vor dem Ende:\n")
                    if (previousSteps.isEmpty()) append("  –\n") else previousSteps.forEach { append("  ").append(it).append('\n') }
                    // Bei nativem Absturz: Funktionsliste aus dem Android-Absturzprotokoll (Tombstone)
                    if (info.reason == ApplicationExitInfo.REASON_CRASH_NATIVE) runCatching {
                        info.traceInputStream?.use { ins ->
                            val lines = tombstoneStrings(ins.readBytes())
                            if (lines.isNotEmpty()) {
                                append("\nAbsturzstelle (Auszug aus dem Android-Protokoll):\n")
                                lines.forEach { append("  ").append(it).append('\n') }
                            }
                        }
                    }
                    // Bei "hing": Ausschnitt des Hauptablaufs aus dem System-Protokoll
                    if (info.reason == ApplicationExitInfo.REASON_ANR) runCatching {
                        info.traceInputStream?.bufferedReader()?.use { r ->
                            val lines = r.readLines()
                            val i = lines.indexOfFirst { it.startsWith("\"main\"") }
                            if (i >= 0) {
                                append("\nHauptablauf:\n")
                                append(lines.drop(i).take(40).joinToString("\n"))
                            }
                        }
                    }
                }
                save(ctx, info.timestamp, if (info.reason == ApplicationExitInfo.REASON_ANR) "Hing" else "Von Android beendet", text)
            }
            sp()?.edit()?.putLong("cl_exit_seen", newest)?.apply()
        }
    }

    /**
     * Das Absturzprotokoll ist binär (Protobuf); lesbare Texte darin sind Signal, Abbruchmeldung,
     * Thread-Namen sowie Bibliotheken und Funktionen des Absturzstapels.
     */
    private fun tombstoneStrings(b: ByteArray): List<String> {
        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() {
            val s = sb.toString().trim()
            sb.setLength(0)
            if (s.length < 5) return
            val keep = s.contains(".so") || s.contains("::") || s.contains("SIG") || s.contains("abort", true) ||
                s.contains("de.basti") || s.contains("Fatal") || s.contains("hwui") || s.contains("skia", true) ||
                s.contains("RenderThread") || s.contains("main") && s.length < 40
            if (keep && (out.isEmpty() || out.last() != s)) out.add(s.take(160))
        }
        for (x in b) {
            val c = x.toInt() and 0xFF
            if (c in 32..126) sb.append(c.toChar()) else flush()
        }
        flush()
        return out.take(60)
    }

    /** Alle gespeicherten Berichte, neueste zuerst. */
    fun all(ctx: Context): List<Report> = runCatching {
        dir(ctx).listFiles()?.filter { it.name.endsWith(".txt") }?.sortedByDescending { it.name }?.mapNotNull { f ->
            val raw = runCatching { f.readText() }.getOrNull() ?: return@mapNotNull null
            val kind = raw.substringBefore('\n')
            val time = runCatching { SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).parse(f.name.take(15))?.time }.getOrNull() ?: f.lastModified()
            Report(f, time, kind, raw.substringAfter('\n'))
        } ?: emptyList()
    }.getOrDefault(emptyList())

    /** Noch nicht angezeigter Bericht (nach einem Absturz einmal beim Start zeigen). */
    fun unseen(ctx: Context): Report? {
        val name = sp()?.getString("cl_unseen", null) ?: return null
        return all(ctx).firstOrNull { it.file.name == name }
    }

    fun markSeen() { sp()?.edit()?.remove("cl_unseen")?.apply() }

    fun clearAll(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
        markSeen()
    }

    /** Alte Einzeldatei (frühere Version) übernehmen. */
    fun migrate(ctx: Context) {
        val old = File(ctx.filesDir, "absturz.txt")
        if (!old.exists()) return
        runCatching { save(ctx, old.lastModified(), "Absturz", old.readText()) }
        old.delete()
    }
}
