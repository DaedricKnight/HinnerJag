package com.artemkhateev.hinnerjag.trip

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.car.app.notification.CarAppExtender
import androidx.car.app.notification.CarNotificationManager
import androidx.car.app.notification.CarPendingIntent
import androidx.core.app.NotificationChannelCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.artemkhateev.hinnerjag.R
import com.artemkhateev.hinnerjag.car.HinnerJagCarAppService
import com.artemkhateev.hinnerjag.model.Copy
import com.artemkhateev.hinnerjag.model.FerryRoute
import com.artemkhateev.hinnerjag.model.Verdict

class TripAlerts(private val context: Context) {

    private val manager = NotificationManagerCompat.from(context)

    init {
        manager.createNotificationChannelsCompat(
            listOf(
                NotificationChannelCompat.Builder(CHANNEL_WARNINGS, NotificationManagerCompat.IMPORTANCE_HIGH)
                    .setName("Färjevarningar")
                    .setDescription("När det blir ont om tid eller du inte hinner till färjan")
                    .build(),
                NotificationChannelCompat.Builder(CHANNEL_TRIP, NotificationManagerCompat.IMPORTANCE_LOW)
                    .setName("Pågående resa")
                    .build(),
            ),
        )
    }

    fun canNotify(): Boolean =
        manager.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED)

    fun ongoing(text: String): Notification =
        NotificationCompat.Builder(context, CHANNEL_TRIP)
            .setSmallIcon(R.drawable.ic_ferry)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setContentIntent(openApp(null))
            .build()

    @SuppressLint("MissingPermission") // checked in canNotify()
    fun updateOngoing(route: FerryRoute, verdict: Verdict) {
        if (canNotify()) manager.notify(ONGOING_ID, ongoing(Copy.ongoing(route, verdict)))
    }

    fun cancelOngoing() = manager.cancel(ONGOING_ID)

    fun cancelWarning() = manager.cancel(WARNING_ID)

    /** The heads-up shown over the navigation app. */
    @SuppressLint("MissingPermission") // checked in canNotify()
    fun warn(route: FerryRoute, alert: Copy.Alert) {
        if (!canNotify()) return
        val open = openApp(route.id)
        val dismiss = PendingIntent.getBroadcast(
            context, 0, Intent(context, DismissReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val builder = NotificationCompat.Builder(context, CHANNEL_WARNINGS)
            .setSmallIcon(R.drawable.ic_ferry)
            .setContentTitle(alert.title)
            .setContentText(alert.text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(alert.text))
            // AAOS shows heads-ups only for some categories (navigation, call, messaging). With
            // CATEGORY_REMINDER the warning went silently to the notification centre, whatever
            // heads_up_notifications_enabled said. It is about the drive, so navigation fits.
            .setCategory(NotificationCompat.CATEGORY_NAVIGATION)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(open)
            .addAction(R.drawable.ic_check, "Okej", dismiss)
            .addAction(R.drawable.ic_ferry, "Öppna", open)
            // Android Auto reads the extender; AAOS uses the regular fields above.
            .extend(
                CarAppExtender.Builder()
                    .setContentTitle(alert.title)
                    .setContentText(alert.text)
                    .setSmallIcon(R.drawable.ic_ferry)
                    .setContentIntent(open)
                    .addAction(R.drawable.ic_check, "Okej", dismiss)
                    .addAction(R.drawable.ic_ferry, "Öppna", open)
                    .setImportance(NotificationManagerCompat.IMPORTANCE_HIGH)
                    .build(),
            )
        CarNotificationManager.from(context).notify(WARNING_ID, builder)
    }

    /** Opens the car app; with a route it lands on that route's detail screen. */
    private fun openApp(routeId: String?): PendingIntent {
        val intent = Intent(Intent.ACTION_VIEW)
            .setComponent(ComponentName(context, HinnerJagCarAppService::class.java))
            .setData(Uri.parse("$SCHEME://route/${routeId.orEmpty()}"))
        return CarPendingIntent.getCarApp(context, routeId.hashCode(), intent, 0)
    }

    companion object {
        const val SCHEME = "hinnerjag"
        const val CHANNEL_WARNINGS = "warnings"
        const val CHANNEL_TRIP = "trip"
        const val WARNING_ID = 1
        const val ONGOING_ID = 2

        fun routeIdOf(intent: Intent): String? =
            intent.data?.takeIf { it.scheme == SCHEME }?.lastPathSegment?.takeIf { it.isNotEmpty() }
    }
}

/** "Okej" on the heads-up. */
class DismissReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        NotificationManagerCompat.from(context).cancel(TripAlerts.WARNING_ID)
    }
}
