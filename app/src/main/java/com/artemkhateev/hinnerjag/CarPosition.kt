package com.artemkhateev.hinnerjag

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.artemkhateev.hinnerjag.model.LatLon

/** Where the car is: Kungälv in demo mode, otherwise the vehicle's location provider. */
class CarPosition(private val context: Context, private val settings: Settings) {

    private val manager = context.getSystemService(LocationManager::class.java)
    private var latest: Location? = null
    private var listening = false

    private val listener = LocationListener { latest = it }

    fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun current(): LatLon? {
        if (settings.demoMode) return DEMO_START
        if (!hasPermission()) return null
        startListening()
        val best = latest ?: lastKnown()
        return best?.let { LatLon(it.latitude, it.longitude) }
    }

    @SuppressLint("MissingPermission")
    private fun lastKnown(): Location? =
        manager.getProviders(true).mapNotNull { manager.getLastKnownLocation(it) }.maxByOrNull { it.time }

    @SuppressLint("MissingPermission")
    private fun startListening() {
        if (listening) return
        val provider = when {
            manager.isProviderEnabled(LocationManager.FUSED_PROVIDER) -> LocationManager.FUSED_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> return
        }
        manager.requestLocationUpdates(provider, 10_000L, 25f, listener, Looper.getMainLooper())
        listening = true
    }

    companion object {
        /** Norgevägen, Kungälv – north of Gothenburg, about half an hour from all three ports. */
        val DEMO_START = LatLon(57.8620, 11.9790)
        const val DEMO_START_NAME = "Kungälv"
    }
}
