package com.artemkhateev.hinnerjag

import com.artemkhateev.hinnerjag.eta.EtaProvider
import com.artemkhateev.hinnerjag.eta.EtaSource
import com.artemkhateev.hinnerjag.model.FerryRoute
import com.artemkhateev.hinnerjag.model.Timetable
import com.artemkhateev.hinnerjag.model.Verdict
import com.artemkhateev.hinnerjag.model.Verdicts

data class RoutePlan(
    val route: FerryRoute,
    val verdict: Verdict,
    val driveSeconds: Int,
    val driveMeters: Int,
    val etaSource: EtaSource,
    val nowSecond: Int,
)

sealed interface PlansResult {
    data class Ready(val plans: List<RoutePlan>) : PlansResult
    /** Real mode without a location yet (no permission, or no fix). */
    data object NoPosition : PlansResult
}

class Plans(
    private val timetable: Timetable,
    private val settings: Settings,
    private val clock: AppClock,
    private val position: CarPosition,
    private val eta: EtaProvider,
) {
    suspend fun all(): PlansResult {
        val from = position.current() ?: return PlansResult.NoPosition
        // Sequential on purpose: the public OSRM server asks for about one request a second.
        return PlansResult.Ready(timetable.routes.map { plan(it, from, pinned = null) })
    }

    suspend fun one(routeId: String, pinned: Int?): RoutePlan? {
        val from = position.current() ?: return null
        return plan(timetable.route(routeId), from, pinned)
    }

    private suspend fun plan(route: FerryRoute, from: com.artemkhateev.hinnerjag.model.LatLon, pinned: Int?): RoutePlan {
        val drive = eta.eta(from, route.port)
        val now = clock.nowSecondOfDay()
        val verdict = Verdicts.evaluate(route.departures, now, drive.seconds, settings.margin(route), pinned)
        return RoutePlan(route, verdict, drive.seconds, drive.meters, drive.source, now)
    }
}
