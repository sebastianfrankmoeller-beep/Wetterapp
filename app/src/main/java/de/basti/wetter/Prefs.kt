package de.basti.wetter

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

/** Ein Ort mit Koordinaten. `id` ist eindeutig über die gerundeten Koordinaten. */
data class Place(
    val name: String,
    val region: String,
    val lat: Double,
    val lon: Double
) {
    val id: String get() = "%.3f,%.3f".format(java.util.Locale.US, lat, lon)

    fun toJson(): JSONObject = JSONObject()
        .put("name", name).put("region", region).put("lat", lat).put("lon", lon)

    companion object {
        fun fromJson(o: JSONObject) = Place(
            o.optString("name"), o.optString("region"), o.getDouble("lat"), o.getDouble("lon")
        )
    }
}

/** Gespeicherte Einstellungen, Favoriten und zwischengespeicherte Vorhersagen. */
object Prefs {
    const val THEME_DARK = 0
    const val THEME_AUTO = 1
    const val THEME_LIGHT = 2

    /** Besondere Orts-ID für "mein aktueller Standort". */
    const val CURRENT = "current"

    private fun sp(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences("wetter", Context.MODE_PRIVATE)

    // ---------------------------------------------------------------- Darstellung
    fun theme(ctx: Context) = sp(ctx).getInt("theme", THEME_DARK)
    fun setTheme(ctx: Context, v: Int) = sp(ctx).edit().putInt("theme", v).apply()

    // ------------------------------------------------------------ Eigenes Aussehen
    fun accent(ctx: Context) = sp(ctx).getInt("st_accent", 0)

    /** Akzent-Farbton 0–359°; ohne eigenen Wert der Ton der früher gewählten Vorgabe. */
    fun accentHue(ctx: Context): Int {
        val v = sp(ctx).getInt("st_hue", -1)
        if (v >= 0) return v
        val a = Ui.ACCENTS[accent(ctx).coerceIn(0, Ui.ACCENTS.size - 1)]
        return Ui.hueOf(a.dark)
    }

    /** Hintergrundton auf dem Regler: 0 … < Ui.TONE_NEUTRAL = neutral, sonst Farbton (Wert − TONE_NEUTRAL). */
    fun toneValue(ctx: Context): Int {
        val v = sp(ctx).getInt("st_tone_v", -1)
        if (v >= 0) return v
        return when (bgTone(ctx)) {          // frühere Vorgaben übernehmen
            2 -> Ui.TONE_NEUTRAL + Ui.hueOf(Ui.TONES[2].darkSurface)
            3 -> Ui.TONE_NEUTRAL + Ui.hueOf(Ui.TONES[3].darkSurface)
            4 -> Ui.TONE_NEUTRAL + Ui.hueOf(Ui.TONES[4].darkSurface)
            else -> 0
        }
    }
    fun bgStyle(ctx: Context) = sp(ctx).getInt("st_bg", 0)
    fun bgTone(ctx: Context) = sp(ctx).getInt("st_tone", 0)
    fun bgVisibility(ctx: Context) = sp(ctx).getInt("st_vis", 50)
    fun fontSize(ctx: Context) = sp(ctx).getInt("st_fsize", 1)
    fun fontStyle(ctx: Context) = sp(ctx).getInt("st_fstyle", 0)
    fun corners(ctx: Context) = sp(ctx).getInt("st_corners", 0)

    /** Setzt einen Aussehens-Wert und erhöht die Stil-Version (damit offene Seiten neu zeichnen). */
    fun setStyle(ctx: Context, key: String, v: Int) =
        sp(ctx).edit().putInt(key, v).putInt("st_version", styleVersion(ctx) + 1).apply()

    fun styleVersion(ctx: Context) = sp(ctx).getInt("st_version", 0)

    fun resetStyle(ctx: Context) {
        val e = sp(ctx).edit()
        for (k in listOf("st_accent", "st_hue", "st_tone_v", "st_bg", "st_tone", "st_vis", "st_fsize", "st_fstyle", "st_corners")) e.remove(k)
        e.putInt("st_version", styleVersion(ctx) + 1).apply()
    }

    fun bgLuminance(ctx: Context) = sp(ctx).getFloat("st_bglum", 0.5f)
    fun setBgLuminance(ctx: Context, v: Float) = sp(ctx).edit().putFloat("st_bglum", v).apply()

    /** Deckkraft des Regenradars in Prozent (15–100). */
    fun radarOpacity(ctx: Context) = sp(ctx).getInt("radar_opacity", 60)
    fun setRadarOpacity(ctx: Context, v: Int) = sp(ctx).edit().putInt("radar_opacity", v.coerceIn(15, 100)).apply()

    // ----------------------------------------------------------- Aktueller Standort
    /** Zuletzt bekannte eigene Position (auch fürs Widget). */
    fun lastLocation(ctx: Context): Place? =
        sp(ctx).getString("last_loc", null)?.let { runCatching { Place.fromJson(JSONObject(it)) }.getOrNull() }

    fun setLastLocation(ctx: Context, p: Place) =
        sp(ctx).edit().putString("last_loc", p.toJson().toString()).apply()

    // -------------------------------------------------------------------- Auswahl
    /** Ausgewählter Ort: CURRENT oder eine Favoriten-ID. */
    fun selected(ctx: Context): String = sp(ctx).getString("selected", CURRENT) ?: CURRENT
    fun setSelected(ctx: Context, id: String) = sp(ctx).edit().putString("selected", id).apply()

    /** Ein gesuchter, (noch) nicht gespeicherter Ort, der gerade angezeigt wird. */
    fun tempPlace(ctx: Context): Place? =
        sp(ctx).getString("temp_place", null)?.let { runCatching { Place.fromJson(JSONObject(it)) }.getOrNull() }

    fun setTempPlace(ctx: Context, p: Place?) =
        sp(ctx).edit().putString("temp_place", p?.toJson()?.toString()).apply()

    // ------------------------------------------------------------------ Favoriten
    fun favorites(ctx: Context): MutableList<Place> {
        val raw = sp(ctx).getString("favorites", "[]") ?: "[]"
        val arr = runCatching { JSONArray(raw) }.getOrDefault(JSONArray())
        return MutableList(arr.length()) { Place.fromJson(arr.getJSONObject(it)) }
    }

    fun setFavorites(ctx: Context, list: List<Place>) {
        val arr = JSONArray()
        list.forEach { arr.put(it.toJson()) }
        sp(ctx).edit().putString("favorites", arr.toString()).apply()
    }

    fun isFavorite(ctx: Context, p: Place) = favorites(ctx).any { it.id == p.id }

    fun addFavorite(ctx: Context, p: Place) {
        val l = favorites(ctx)
        if (l.none { it.id == p.id }) {
            l.add(p)
            setFavorites(ctx, l)
        }
    }

    fun removeFavorite(ctx: Context, id: String) = setFavorites(ctx, favorites(ctx).filter { it.id != id })

    fun moveFavorite(ctx: Context, id: String, delta: Int) {
        val l = favorites(ctx)
        val i = l.indexOfFirst { it.id == id }
        val j = i + delta
        if (i < 0 || j < 0 || j >= l.size) return
        val t = l[i]; l[i] = l[j]; l[j] = t
        setFavorites(ctx, l)
    }

    // ----------------------------------------------------- Vorhersage-Zwischenspeicher
    /** Rohdaten einer Vorhersage, damit App und Widget sofort etwas zeigen können. */
    fun cache(ctx: Context, placeId: String): String? = sp(ctx).getString("cache_$placeId", null)

    fun setCache(ctx: Context, placeId: String, json: String) {
        sp(ctx).edit().putString("cache_$placeId", json).apply()
    }

    // ------------------------------------------------------------------ Lauf-Check
    fun runnerType(ctx: Context) = sp(ctx).getInt("runner_type", RunCheck.TYPE_WARM)
    fun setRunnerType(ctx: Context, v: Int) = sp(ctx).edit().putInt("runner_type", v).apply()

    // ------------------------------------------------------------------- Fahrmodus
    fun driveAuto(ctx: Context) = sp(ctx).getBoolean("drive_auto", true)
    fun setDriveAuto(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("drive_auto", v).apply()
    fun driveVoice(ctx: Context) = sp(ctx).getBoolean("drive_voice", true)
    fun setDriveVoice(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("drive_voice", v).apply()

    // -------------------------------------------------------------- Unwetterwarnungen
    fun warnEnabled(ctx: Context) = sp(ctx).getBoolean("warn_on", true)
    fun setWarnEnabled(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("warn_on", v).apply()

    /** Mindeststufe für Benachrichtigungen: 1 Gelb, 2 Orange, 3 Rot. */
    fun warnMinLevel(ctx: Context) = sp(ctx).getInt("warn_min", 2)
    fun setWarnMinLevel(ctx: Context, v: Int) = sp(ctx).edit().putInt("warn_min", v).apply()

    fun warnLocation(ctx: Context) = sp(ctx).getBoolean("warn_loc", true)
    fun setWarnLocation(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("warn_loc", v).apply()

    /** Favoriten (IDs), für die ebenfalls gewarnt wird. */
    fun warnFavorites(ctx: Context): Set<String> = sp(ctx).getStringSet("warn_favs", emptySet()) ?: emptySet()
    fun setWarnFavorite(ctx: Context, id: String, on: Boolean) {
        val s = warnFavorites(ctx).toMutableSet()
        if (on) s.add(id) else s.remove(id)
        sp(ctx).edit().putStringSet("warn_favs", s).apply()
    }

    fun warnQuiet(ctx: Context) = sp(ctx).getBoolean("warn_quiet", false)
    fun setWarnQuiet(ctx: Context, v: Boolean) = sp(ctx).edit().putBoolean("warn_quiet", v).apply()

    /** Bereits gemeldete Warnungen: Schlüssel → "Stufe|Ablaufzeit". */
    fun warnSeen(ctx: Context): JSONObject =
        runCatching { JSONObject(sp(ctx).getString("warn_seen", "{}") ?: "{}") }.getOrDefault(JSONObject())
    fun setWarnSeen(ctx: Context, o: JSONObject) = sp(ctx).edit().putString("warn_seen", o.toString()).apply()

    fun askedNotifications(ctx: Context) = sp(ctx).getBoolean("asked_notif", false)
    fun setAskedNotifications(ctx: Context) = sp(ctx).edit().putBoolean("asked_notif", true).apply()

    // ---------------------------------------------------------------------- Widget
    fun widgetPlace(ctx: Context, id: Int): String = sp(ctx).getString("w_place_$id", CURRENT) ?: CURRENT
    fun widgetMode(ctx: Context, id: Int): Int = sp(ctx).getInt("w_mode_$id", 0)
    fun widgetAlpha(ctx: Context, id: Int): Int = sp(ctx).getInt("w_alpha_$id", 90)
    fun widgetTheme(ctx: Context, id: Int): Int = sp(ctx).getInt("w_theme_$id", THEME_DARK)

    fun setWidget(ctx: Context, id: Int, place: String, mode: Int, alpha: Int, theme: Int) {
        sp(ctx).edit()
            .putString("w_place_$id", place)
            .putInt("w_mode_$id", mode)
            .putInt("w_alpha_$id", alpha)
            .putInt("w_theme_$id", theme)
            .putBoolean("w_set_$id", true)
            .apply()
    }

    fun widgetConfigured(ctx: Context, id: Int) = sp(ctx).getBoolean("w_set_$id", false)

    fun deleteWidget(ctx: Context, id: Int) {
        sp(ctx).edit()
            .remove("w_place_$id").remove("w_mode_$id").remove("w_alpha_$id")
            .remove("w_theme_$id").remove("w_set_$id")
            .apply()
    }

    /** Ort zu einer ID (Standort, Favorit oder gerade gesuchter Ort). */
    fun resolve(ctx: Context, id: String): Place? = when (id) {
        CURRENT -> lastLocation(ctx)
        else -> favorites(ctx).firstOrNull { it.id == id }
            ?: tempPlace(ctx)?.takeIf { it.id == id }
    }
}
