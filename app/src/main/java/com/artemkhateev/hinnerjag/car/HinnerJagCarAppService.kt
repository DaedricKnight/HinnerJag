package com.artemkhateev.hinnerjag.car

import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.car.app.CarAppService
import androidx.car.app.Screen
import androidx.car.app.ScreenManager
import androidx.car.app.Session
import androidx.car.app.SessionInfo
import androidx.car.app.validation.HostValidator
import com.artemkhateev.hinnerjag.trip.TripAlerts

class HinnerJagCarAppService : CarAppService() {

    override fun createHostValidator(): HostValidator =
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            HostValidator.ALLOW_ALL_HOSTS_VALIDATOR
        } else {
            HostValidator.Builder(applicationContext)
                .addAllowedHosts(androidx.car.app.R.array.hosts_allowlist_sample)
                .build()
        }

    override fun onCreateSession(sessionInfo: SessionInfo): Session = HinnerJagSession()
}

class HinnerJagSession : Session() {

    private val screens get() = carContext.getCarService(ScreenManager::class.java)

    override fun onCreateScreen(intent: Intent): Screen {
        val list = FerryListScreen(carContext)
        val routeId = TripAlerts.routeIdOf(intent) ?: return list
        screens.push(list)
        return FerryDetailScreen(carContext, routeId)
    }

    /** A tap on the heads-up ("Öppna") while the app is already running. */
    override fun onNewIntent(intent: Intent) {
        val routeId = TripAlerts.routeIdOf(intent) ?: return
        screens.popToRoot()
        screens.push(FerryDetailScreen(carContext, routeId))
    }
}
