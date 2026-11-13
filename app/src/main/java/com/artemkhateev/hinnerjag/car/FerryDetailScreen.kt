package com.artemkhateev.hinnerjag.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.Pane
import androidx.car.app.model.PaneTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.car.app.versioning.CarAppApiLevels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.artemkhateev.hinnerjag.R
import com.artemkhateev.hinnerjag.RoutePlan
import com.artemkhateev.hinnerjag.graph
import com.artemkhateev.hinnerjag.model.Copy
import com.artemkhateev.hinnerjag.model.Status
import com.artemkhateev.hinnerjag.model.Verdict
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Screens 2–4: one ferry. Same template, the verdict decides the words and the two buttons. */
class FerryDetailScreen(carContext: CarContext, private val routeId: String) : Screen(carContext) {

    private val graph = carContext.graph
    private val route = graph.timetable.route(routeId)
    private var plan: RoutePlan? = null

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { graph.trips.state.collect { invalidate() } }
                while (true) {
                    plan = graph.plans.one(routeId, pinned = null)
                    invalidate()
                    delay(REFRESH_MS)
                }
            }
        }
    }

    /** While a trip to this port runs, the screen shows what the trip watcher sees. */
    private fun trip() = graph.trips.state.value?.takeIf { it.route.id == routeId }

    override fun onGetTemplate(): Template {
        val trip = trip()
        val verdict = trip?.verdict ?: plan?.verdict
        val pane = Pane.Builder()
        if (verdict == null) {
            pane.setLoading(true)
        } else {
            rows(verdict).forEach(pane::addRow)
            actions(verdict, following = trip != null).forEach(pane::addAction)
            if (carContext.carAppApiLevel >= CarAppApiLevels.LEVEL_4) pane.setImage(image(verdict))
        }
        return PaneTemplate.Builder(pane.build())
            .setTitle(Copy.title(route))
            .setHeaderAction(Action.BACK)
            .build()
    }

    private fun rows(v: Verdict): List<Row> {
        val headline = Row.Builder().setTitle(Copy.headline(v))
        return when (v.status) {
            Status.ON_TIME, Status.TIGHT -> {
                headline.addText(colored(Copy.marginLine(v.slackMinutes!!), textColor(v)))
                Copy.body(route, v)?.let { headline.addText(it) }
                listOf(
                    headline.build(),
                    step(v.arrival, Copy.arrivalStep(route, v)),
                    step(v.boardBy!!, Copy.boardingStep(route, v.marginMinutes)),
                    Row.Builder().setTitle(Copy.clock(v.departure!!)).addText("Avgång").addText(route.description).build(),
                )
            }
            Status.MISSED -> buildList {
                add(headline.addText(Copy.body(route, v)!!).build())
                v.next?.let { add(Row.Builder().setTitle(Copy.nextTitle(route, it)).addText(Copy.nextDetails(v)).build()) }
                add(Row.Builder().setTitle(route.description).build())
            }
            Status.NONE_LEFT -> listOf(
                headline.apply { Copy.body(route, v)?.let(::addText) }.build(),
                Row.Builder().setTitle(route.description).build(),
            )
        }
    }

    private fun step(minute: Int, text: String) =
        Row.Builder().setTitle(Copy.clock(minute)).addText(text).build()

    private fun actions(v: Verdict, following: Boolean): List<Action> {
        val c = carContext
        val back = c.action("Andra avgångar") { screenManager.pop() }
        val stop = c.action("Avsluta resa", R.drawable.ic_stop) { graph.trips.stop() }
        val drive = c.action("Kör", R.drawable.ic_navigate, primary = true) {
            if (following) c.navigateTo(route.port) else startTrip(v.departure!!)
        }
        return when (v.status) {
            Status.ON_TIME -> listOf(drive, if (following) stop else back)
            Status.TIGHT -> listOfNotNull(
                drive,
                v.next?.let { next -> c.action(Copy.takeInstead(next)) { take(next, following) } } ?: back,
            )
            Status.MISSED -> {
                val next = v.next ?: return listOf(if (following) stop else back)
                listOf(
                    c.action(Copy.takeDeparture(route, next), R.drawable.ic_navigate, primary = true) { take(next, following) },
                    if (v.coffee) c.action("Kafé vid hamnen", R.drawable.ic_coffee) { c.searchNearby("kafé ${route.portName}") }
                    else if (following) stop else back,
                )
            }
            Status.NONE_LEFT -> listOf(back)
        }
    }

    /** Aim for another departure: re-pin a running trip, or start one. */
    private fun take(departure: Int, following: Boolean) {
        if (following) graph.trips.repin(departure) else startTrip(departure)
    }

    private fun startTrip(departure: Int) {
        val drive = plan?.driveSeconds ?: return
        carContext.withTripPermissions {
            graph.trips.start(route, drive, departure)
            carContext.navigateTo(route.port)
        }
    }

    private fun image(v: Verdict) = when (v.status) {
        Status.ON_TIME -> carContext.icon(R.drawable.ic_check, textColor(v))
        Status.TIGHT -> carContext.icon(R.drawable.ic_clock, textColor(v))
        Status.MISSED -> carContext.icon(if (v.coffee) R.drawable.ic_coffee else R.drawable.ic_ferry, markerColor(v))
        Status.NONE_LEFT -> carContext.icon(R.drawable.ic_ferry)
    }

    private companion object {
        const val REFRESH_MS = 30_000L
    }
}
