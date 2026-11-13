package com.artemkhateev.hinnerjag

import android.app.Application
import android.content.Context
import com.artemkhateev.hinnerjag.eta.CachingEtaProvider
import com.artemkhateev.hinnerjag.eta.FallbackEtaProvider
import com.artemkhateev.hinnerjag.eta.OsrmEtaProvider
import com.artemkhateev.hinnerjag.eta.StraightLineEtaProvider
import com.artemkhateev.hinnerjag.model.TimetableParser
import com.artemkhateev.hinnerjag.trip.TripAlerts
import com.artemkhateev.hinnerjag.trip.TripWatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class HinnerJagApp : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}

/** Hand-wired dependencies; small enough not to need a DI framework. */
class AppGraph(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val timetable = TimetableParser.parse(app.assets.open("ferries.json").bufferedReader().use { it.readText() })
    val settings = Settings(app)
    val clock = AppClock(settings)
    val position = CarPosition(app, settings)
    val eta = CachingEtaProvider(FallbackEtaProvider(OsrmEtaProvider(), StraightLineEtaProvider()))
    val plans = Plans(timetable, settings, clock, position, eta)
    val alerts = TripAlerts(app)
    val trips = TripWatcher(this)
}

val Context.graph: AppGraph get() = (applicationContext as HinnerJagApp).graph
