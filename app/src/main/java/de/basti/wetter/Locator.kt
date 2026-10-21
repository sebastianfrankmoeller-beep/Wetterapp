package de.basti.wetter

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.CancellationSignal
import android.os.Handler
import android.os.Looper
import java.util.Locale
import java.util.concurrent.Executors

/** Ermittelt die eigene Position und einen lesbaren Ortsnamen dazu. */
object Locator {

    private val io = Executors.newSingleThreadExecutor()

    fun hasPermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Letzte bekannte Position ohne Warten (oder null). */
    fun lastKnown(ctx: Context): Location? {
        if (!hasPermission(ctx)) return null
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        var best: Location? = null
        for (prov in listOf("fused", LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER)) {
            val l = try { lm.getLastKnownLocation(prov) } catch (e: Exception) { null } ?: continue
            if (best == null || l.time > best.time) best = l
        }
        return best
    }

    /** Ist die Ortung am Handy überhaupt eingeschaltet? */
    fun locationEnabled(ctx: Context): Boolean {
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        return if (Build.VERSION.SDK_INT >= 28) lm.isLocationEnabled
        else lm.isProviderEnabled(LocationManager.GPS_PROVIDER) || lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }

    /**
     * Holt eine frische Position: fragt alle verfügbaren Quellen gleichzeitig (Fused, Netz, GPS)
     * und nimmt die erste Antwort. Nach spätestens 20 s gilt die letzte bekannte Position.
     * Ruft [done] im Hauptthread auf; null, wenn gar keine Position verfügbar ist.
     */
    @Suppress("DEPRECATION")
    fun current(ctx: Context, done: (Location?) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        if (!hasPermission(ctx)) { done(null); return }
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val last = lastKnown(ctx)
        // Eine weniger als 10 Minuten alte Position reicht fürs Wetter völlig.
        if (last != null && System.currentTimeMillis() - last.time < 10 * 60_000) { done(last); return }

        val providers = buildList {
            for (pr in listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
                if (runCatching { lm.allProviders.contains(pr) && lm.isProviderEnabled(pr) }.getOrDefault(false)) add(pr)
            }
        }
        if (providers.isEmpty()) { done(last); return }

        var finished = false
        val signals = ArrayList<CancellationSignal>()
        lateinit var listener: android.location.LocationListener
        fun finish(l: Location?) {
            if (finished) return
            finished = true
            signals.forEach { it.cancel() }
            runCatching { lm.removeUpdates(listener) }
            done(l ?: last)
        }
        listener = object : android.location.LocationListener {
            override fun onLocationChanged(l: Location) = finish(l)
            @Deprecated("") override fun onStatusChanged(p: String?, s: Int, e: android.os.Bundle?) {}
            override fun onProviderEnabled(p: String) {}
            override fun onProviderDisabled(p: String) {}
        }
        try {
            for (pr in providers) {
                // Laufende Updates: liefern auch dann, wenn eine Einzelabfrage leer zurückkommt
                lm.requestLocationUpdates(pr, 0L, 0f, listener, Looper.getMainLooper())
                if (Build.VERSION.SDK_INT >= 30) {
                    val sig = CancellationSignal()
                    signals.add(sig)
                    lm.getCurrentLocation(pr, sig, ctx.mainExecutor) { l -> if (l != null) finish(l) }
                }
            }
        } catch (e: SecurityException) { finish(null); return }
        main.postDelayed({ finish(null) }, 20000)
    }

    /** Ortsname zu Koordinaten (Hintergrund), Ergebnis im Hauptthread. */
    fun name(ctx: Context, lat: Double, lon: Double, done: (String, String) -> Unit) {
        val main = Handler(Looper.getMainLooper())
        io.execute {
            var name = "Mein Standort"
            var region = ""
            try {
                if (Geocoder.isPresent()) {
                    @Suppress("DEPRECATION")
                    val a = Geocoder(ctx, Locale.GERMANY).getFromLocation(lat, lon, 1)?.firstOrNull()
                    if (a != null) {
                        name = a.locality ?: a.subAdminArea ?: a.adminArea ?: name
                        region = listOfNotNull(a.subLocality?.takeIf { it != name }, a.adminArea?.takeIf { it != name })
                            .firstOrNull() ?: ""
                    }
                }
            } catch (_: Exception) {
            }
            main.post { done(name, region) }
        }
    }
}
