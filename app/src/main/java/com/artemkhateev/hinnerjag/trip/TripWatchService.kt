package com.artemkhateev.hinnerjag.trip

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.artemkhateev.hinnerjag.graph
import com.artemkhateev.hinnerjag.model.Copy

/**
 * Keeps the process alive while the navigation app is in front, so [TripWatcher] keeps
 * checking. Needs location permission (foreground service type "location"); without it
 * the watcher still runs for as long as the system keeps the process.
 */
class TripWatchService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val trip = graph.trips.state.value
        val text = trip?.let { Copy.ongoing(it.route, it.verdict) } ?: "Följer resan"
        ServiceCompat.startForeground(
            this, TripAlerts.ONGOING_ID, graph.alerts.ongoing(text),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION,
        )
        return START_NOT_STICKY
    }

    companion object {
        fun start(context: Context) {
            if (!context.graph.position.hasPermission()) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, TripWatchService::class.java))
            } catch (e: Exception) {
                Log.w("HinnerJag", "Could not start the trip service", e)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, TripWatchService::class.java))
        }
    }
}
