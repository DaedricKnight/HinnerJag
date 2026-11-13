package com.artemkhateev.hinnerjag.trip

import android.os.SystemClock
import com.artemkhateev.hinnerjag.AppGraph
import com.artemkhateev.hinnerjag.eta.distanceKm
import com.artemkhateev.hinnerjag.model.Copy
import com.artemkhateev.hinnerjag.model.FerryRoute
import com.artemkhateev.hinnerjag.model.Verdict
import com.artemkhateev.hinnerjag.model.Verdicts
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class TripState(
    val route: FerryRoute,
    val departure: Int,
    val verdict: Verdict,
)

/**
 * Keeps re-checking a trip the driver started with "Kör" while the navigation app is on
 * screen, and raises a heads-up only when the answer gets worse: on time → tight → missed.
 */
class TripWatcher(private val graph: AppGraph) {

    private val mutableState = MutableStateFlow<TripState?>(null)
    val state: StateFlow<TripState?> = mutableState

    private var job: Job? = null

    /** What the demo needs to fake the drive: when it started and how long OSRM said it takes. */
    private var startedAt = 0L
    private var initialDriveSeconds = 0

    /** Latest known drive time, so a re-pinned trip starts from where the car is, not from the start. */
    private var lastDriveSeconds = 0

    fun start(route: FerryRoute, driveSeconds: Int, departure: Int) {
        stop()
        startedAt = SystemClock.elapsedRealtime()
        initialDriveSeconds = driveSeconds
        lastDriveSeconds = driveSeconds
        TripWatchService.start(graph.app)
        follow(route, departure)
    }

    /** Switch the trip to another departure of the same route, keeping the drive as it is. */
    fun repin(departure: Int) {
        val current = state.value ?: return
        job?.cancel()
        graph.alerts.cancelWarning()
        follow(current.route, departure)
    }

    fun stop() {
        job?.cancel()
        job = null
        mutableState.value = null
        TripWatchService.stop(graph.app)
        graph.alerts.cancelOngoing()
    }

    private fun follow(route: FerryRoute, departure: Int) {
        val margin = graph.settings.margin(route)
        val drive = if (graph.settings.demoMode) demoDrive(elapsedSeconds()).first else lastDriveSeconds
        val first = Verdicts.evaluate(route.departures, graph.clock.nowSecondOfDay(), drive, margin, departure)
        mutableState.value = TripState(route, departure, first)
        graph.alerts.updateOngoing(route, first)

        job = graph.scope.launch {
            var severity = Verdicts.severity(first.status)
            while (isActive) {
                delay(if (graph.settings.demoMode) DEMO_TICK_MS else TICK_MS)
                val step = check(route, departure) ?: continue
                mutableState.value = TripState(route, departure, step.verdict)
                graph.alerts.updateOngoing(route, step.verdict)

                val now = Verdicts.severity(step.verdict.status)
                if (now > severity) {
                    Copy.alert(route, step.verdict, step.queueSimulated)?.let { graph.alerts.warn(route, it) }
                }
                severity = now

                val departed = graph.clock.nowSecondOfDay() / 60 > departure
                if (step.arrived || departed) {
                    stop()
                    break
                }
            }
        }
    }

    private class Step(val verdict: Verdict, val queueSimulated: Boolean, val arrived: Boolean)

    private suspend fun check(route: FerryRoute, departure: Int): Step? {
        val elapsed = elapsedSeconds()
        val margin = graph.settings.margin(route)
        val now = graph.clock.nowSecondOfDay()

        if (graph.settings.demoMode) {
            val (remaining, queued) = demoDrive(elapsed)
            return Step(Verdicts.evaluate(route.departures, now, remaining, margin, departure), queued, remaining <= 0)
        }

        val here = graph.position.current() ?: return null
        if (distanceKm(here, route.port) < ARRIVED_KM) {
            return Step(Verdicts.evaluate(route.departures, now, 0, margin, departure), false, true)
        }
        val drive = graph.eta.eta(here, route.port).seconds
        lastDriveSeconds = drive
        return Step(Verdicts.evaluate(route.departures, now, drive, margin, departure), false, false)
    }

    private fun elapsedSeconds() = ((SystemClock.elapsedRealtime() - startedAt) / 1000).toInt()

    /**
     * The demo car drives at exactly the pace OSRM predicted, plus the simulated queue once the
     * trip is half a minute old. Returns the remaining drive and whether a queue is in it.
     */
    private fun demoDrive(elapsedSeconds: Int): Pair<Int, Boolean> {
        val queue = if (elapsedSeconds >= QUEUE_AFTER_SECONDS) graph.settings.simulatedQueueMinutes * 60 else 0
        return (initialDriveSeconds + queue - elapsedSeconds).coerceAtLeast(0) to (queue > 0)
    }

    private companion object {
        const val TICK_MS = 45_000L
        const val DEMO_TICK_MS = 15_000L
        const val QUEUE_AFTER_SECONDS = 30
        const val ARRIVED_KM = 0.3
    }
}
