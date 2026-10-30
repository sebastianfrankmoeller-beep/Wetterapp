package de.basti.wetter

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.widget.ScrollView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Anzeige eines Fehlerberichts mit Speichern (optional), Teilen und Kopieren. */
object ReportDialog {

    fun title(r: CrashLog.Report): String = when (r.kind) {
        "Absturz" -> "Die App wurde unerwartet beendet"
        "Fehler abgefangen" -> "Ein Fehler wurde abgefangen"
        "Hing" -> "Die App reagierte nicht"
        else -> "Von Android beendet"
    }

    fun label(r: CrashLog.Report): String =
        SimpleDateFormat("dd.MM. HH:mm", Locale.GERMANY).format(Date(r.time)) + " · " + r.kind

    fun fileName(r: CrashLog.Report): String =
        "Wetter-Fehlerbericht-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date(r.time)) + ".txt"

    fun copy(ctx: Context, r: CrashLog.Report) {
        val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("Fehlerbericht", r.text))
        Toast.makeText(ctx, "Kopiert – bitte im Chat einfügen", Toast.LENGTH_LONG).show()
    }

    fun share(a: Activity, r: CrashLog.Report) {
        val i = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_SUBJECT, "Wetter-App: " + label(r))
            .putExtra(Intent.EXTRA_TEXT, r.text)
        runCatching { a.startActivity(Intent.createChooser(i, "Fehlerbericht teilen")) }
    }

    /** [onSave] null → kein Speichern-Knopf (dann "Schließen"). */
    fun show(a: Activity, p: Ui.Palette, r: CrashLog.Report, onSave: (() -> Unit)?) {
        if (a.isFinishing) return
        val pad = Ui.dp(a, 20)
        val tv = Ui.tv(a, r.text, 11.5f, p.text).apply {
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(pad, Ui.dp(a, 8), pad, Ui.dp(a, 8))
        }
        val theme = if (p.dark) android.R.style.Theme_DeviceDefault_Dialog_Alert else android.R.style.Theme_DeviceDefault_Light_Dialog_Alert
        val b = AlertDialog.Builder(a, theme)
            .setTitle(title(r))
            .setView(ScrollView(a).apply { addView(tv) })
            .setPositiveButton("Kopieren") { _, _ -> copy(a, r) }
            .setNeutralButton("Teilen") { _, _ -> share(a, r) }
        if (onSave != null) b.setNegativeButton("Speichern") { _, _ -> onSave() } else b.setNegativeButton("Schließen", null)
        b.show()
    }
}
