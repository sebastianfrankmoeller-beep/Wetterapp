package de.basti.wetter

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Erkennt eine laufende Google-Maps-Navigation an deren Benachrichtigung
 * und startet bzw. beendet den Fahrmodus automatisch.
 */
class MapsNavListener : NotificationListenerService() {

    companion object {
        private const val MAPS = "com.google.android.apps.maps"
        private const val OFFER_ID = 5152

        fun hasAccess(ctx: Context): Boolean {
            val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
            val me = ComponentName(ctx, MapsNavListener::class.java).flattenToString()
            return flat.split(":").any { it == me || it.startsWith(ctx.packageName + "/") }
        }

        fun isNavigation(sbn: StatusBarNotification): Boolean {
            if (sbn.packageName != MAPS) return false
            val n = sbn.notification
            if (n.category == Notification.CATEGORY_NAVIGATION) return true
            val ch = n.channelId?.lowercase() ?: ""
            return sbn.isOngoing && (ch.contains("navigation") || ch.contains("nav"))
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        val active = runCatching { activeNotifications }.getOrNull() ?: return
        if (active.any { isNavigation(it) }) onNavStarted()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (isNavigation(sbn)) onNavStarted()
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (!isNavigation(sbn)) return
        // Navigation beendet – nur stoppen, wenn keine andere Navigations-Benachrichtigung mehr da ist
        val still = runCatching { activeNotifications }.getOrNull()?.any { isNavigation(it) } ?: false
        if (still) return
        getSystemService(NotificationManager::class.java).cancel(OFFER_ID)
        if (DriveService.running) {
            runCatching { startService(Intent(this, DriveService::class.java).setAction(DriveService.ACTION_STOP_AUTO)) }
        }
    }

    private fun onNavStarted() {
        if (!Prefs.driveAuto(this) || DriveService.running) return
        if (!DriveService.hasLocation(this)) return
        if (!DriveService.start(this, auto = true)) offerStart()
    }

    /** Falls Android den automatischen Start verweigert: Benachrichtigung zum Antippen. */
    private fun offerStart() {
        DriveService.ensureChannels(this)
        val pi = PendingIntent.getForegroundService(
            this, 3, Intent(this, DriveService::class.java).setAction(DriveService.ACTION_START).putExtra(DriveService.EXTRA_AUTO, true),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(this, "fahrmodus_alarm")
            .setSmallIcon(R.drawable.ic_stat_car)
            .setContentTitle("Wetter-Fahrmodus starten?")
            .setContentText("Antippen, um das Wetter auf der Strecke zu prüfen")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(OFFER_ID, n)
    }
}
