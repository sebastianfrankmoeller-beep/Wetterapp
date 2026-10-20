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
