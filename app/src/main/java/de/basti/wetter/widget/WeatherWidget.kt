package de.basti.wetter.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.widget.RemoteViews
import de.basti.wetter.Locator
import de.basti.wetter.MainActivity
import de.basti.wetter.Place
import de.basti.wetter.Prefs
import de.basti.wetter.R
import de.basti.wetter.WeatherApi
import org.json.JSONObject

/** Startbildschirm-Widget. Ort, Inhalt, Deckkraft und Design sind je Widget einstellbar. */
class WeatherWidget : AppWidgetProvider() {

    override fun onUpdate(ctx: Context, mgr: AppWidgetManager, ids: IntArray) {
        de.basti.wetter.CrashLog.install(ctx)
        de.basti.wetter.Usage.init(ctx)
        val pending = goAsync()
        Thread {
            try {
                for (id in ids) runCatching { update(ctx, mgr, id, allowFetch = true) }
            } finally {
                pending.finish()
            }
        }.start()
    }

    override fun onAppWidgetOptionsChanged(ctx: Context, mgr: AppWidgetManager, id: Int, newOptions: Bundle?) {
        // Größe geändert → neu zeichnen (ohne Netz)
        val pending = goAsync()
        Thread {
            try { runCatching { update(ctx, mgr, id, allowFetch = false) } } finally { pending.finish() }
        }.start()
    }

    override fun onDeleted(ctx: Context, ids: IntArray) {
        for (id in ids) Prefs.deleteWidget(ctx, id)
    }

    companion object {

        /**
         * Ist die App im Vordergrund, zeichnet das Widget nicht parallel (App und Widget teilten sich dabei
         * Teile des Grafiksystems – Absturzgefahr). Es wird dann beim Verlassen der App nachgeholt.
         */
        @Volatile var appForeground = false
        @Volatile var pending = false

        /** Beim Verlassen der App: zurückgehaltenes Neuzeichnen nachholen. */
        fun flush(ctx: Context) {
            if (pending) { pending = false; updateAll(ctx) }
        }

        /** Alle Widgets neu zeichnen (nach dem Laden in der App, nur Zwischenspeicher). */
        fun updateAll(ctx: Context) {
            if (appForeground) { pending = true; return }
            val app = ctx.applicationContext
            val mgr = AppWidgetManager.getInstance(app)
            val ids = mgr.getAppWidgetIds(ComponentName(app, WeatherWidget::class.java))
            if (ids.isEmpty()) return
            Thread { for (id in ids) runCatching { update(app, mgr, id, allowFetch = true) } }.start()
        }

        private fun sizeDp(ctx: Context, mgr: AppWidgetManager, id: Int): Pair<Int, Int> {
            val o = mgr.getAppWidgetOptions(id)
            val portrait = ctx.resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
            // Hochformat: volle Breite (minWidth) und Höhe (maxHeight)
            val w = if (portrait) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH) else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_WIDTH)
            val h = if (portrait) o.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT) else o.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT)
            return (if (w > 0) w else 250) to (if (h > 0) h else 110)
        }

        /** Zeichnet ein Widget; lädt bei Bedarf frische Daten (Hintergrund-Thread!). */
        fun update(ctx: Context, mgr: AppWidgetManager, id: Int, allowFetch: Boolean) {
            if (appForeground) { pending = true; return }
            de.basti.wetter.CrashLog.note("Widget zeichnen")
            val placeId = Prefs.widgetPlace(ctx, id)
            val isLoc = placeId == Prefs.CURRENT

            var place: Place? = Prefs.resolve(ctx, placeId)
            if (isLoc) {
                // Wenn möglich die zuletzt bekannte Position des Handys nehmen
                val l = runCatching { Locator.lastKnown(ctx) }.getOrNull()
                if (l != null) {
                    val prev = Prefs.lastLocation(ctx)
                    val r = FloatArray(1)
                    if (prev != null) android.location.Location.distanceBetween(prev.lat, prev.lon, l.latitude, l.longitude, r)
                    if (prev == null || r[0] > 2000) {
                        place = Place(if (prev == null) "Mein Standort" else prev.name, prev?.region ?: "", l.latitude, l.longitude)
                        Prefs.setLastLocation(ctx, place)
                    }
                }
            }
            if (place == null && !isLoc) {
                // Favorit wurde gelöscht → auf Standort zurückfallen
                place = Prefs.lastLocation(ctx)
            }

            val key = if (isLoc) Prefs.CURRENT else placeId
            val pl: Place? = place
            val raw = Prefs.cache(ctx, key)
            render(ctx, mgr, id, pl, raw, isLoc)

            if (allowFetch && pl != null) {
                val age = raw?.let { r -> runCatching { System.currentTimeMillis() - JSONObject(r).optLong("at") }.getOrNull() }
                if (age == null || age > 25 * 60_000) {
                    val fresh = runCatching { WeatherApi.fetchRaw(pl) }.getOrNull()
                    if (fresh != null) {
                        Prefs.setCache(ctx, key, fresh)
                        render(ctx, mgr, id, pl, fresh, isLoc)
                    }
                }
            }
        }

        private fun render(ctx: Context, mgr: AppWidgetManager, id: Int, place: Place?, raw: String?, isLoc: Boolean) {
            val f = if (place != null && raw != null) runCatching { WeatherApi.parse(place, raw) }.getOrNull() else null
            val (w, h) = sizeDp(ctx, mgr, id)
            val bmp = WidgetRenderer.render(
                ctx, f, place?.name ?: "Mein Standort", isLoc, w, h,
                Prefs.widgetMode(ctx, id), Prefs.widgetAlpha(ctx, id), Prefs.widgetTheme(ctx, id)
            )
            val views = RemoteViews(ctx.packageName, R.layout.widget)
            views.setImageViewBitmap(R.id.widget_image, bmp)
            val open = Intent(ctx, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(MainActivity.EXTRA_PLACE, Prefs.widgetPlace(ctx, id))
            }
            views.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(ctx, id, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            )
            mgr.updateAppWidget(id, views)
        }
    }
}
