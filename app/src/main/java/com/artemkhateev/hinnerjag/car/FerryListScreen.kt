package com.artemkhateev.hinnerjag.car

import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarLocation
import androidx.car.app.model.ItemList
import androidx.car.app.model.Metadata
import androidx.car.app.model.Place
import androidx.car.app.model.PlaceListMapTemplate
import androidx.car.app.model.PlaceMarker
import androidx.car.app.model.Row
import androidx.car.app.model.Template
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.artemkhateev.hinnerjag.CarPosition
import com.artemkhateev.hinnerjag.PlansResult
import com.artemkhateev.hinnerjag.R
import com.artemkhateev.hinnerjag.RoutePlan
import com.artemkhateev.hinnerjag.eta.EtaSource
import com.artemkhateev.hinnerjag.graph
import com.artemkhateev.hinnerjag.model.Copy
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Screen 1: nearby ferries, one verdict each, on the host's map. */
class FerryListScreen(carContext: CarContext) : Screen(carContext) {

    private val graph = carContext.graph
    private var result: PlansResult? = null

    init {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { graph.trips.state.collect { invalidate() } }
                while (true) {
                    result = graph.plans.all()
                    invalidate()
                    delay(REFRESH_MS)
                }
            }
        }
    }

    override fun onGetTemplate(): Template {
        val builder = PlaceListMapTemplate.Builder()
            .setTitle(title())
            .setHeaderAction(Action.APP_ICON)
            .setActionStrip(actionStrip())

        when (val r = result) {
            null -> builder.setLoading(true)
            PlansResult.NoPosition -> builder.setItemList(
                ItemList.Builder()
                    .setNoItemsMessage("Väntar på bilens position. Tillåt plats eller slå på demoläget under Marginaler.")
                    .build(),
            )
            is PlansResult.Ready -> builder.setItemList(items(r.plans))
        }

        if (graph.settings.demoMode) {
            val start = CarPosition.DEMO_START
            builder.setAnchor(
                Place.Builder(CarLocation.create(start.lat, start.lon))
                    .setMarker(PlaceMarker.Builder().setColor(CarColor.BLUE).build())
                    .build(),
            )
        } else if (graph.position.hasPermission()) {
            builder.setCurrentLocationEnabled(true)
        }
        return builder.build()
    }

    private fun title(): String {
        val now = Copy.clockOfSecond(graph.clock.nowSecondOfDay())
        return if (graph.settings.demoMode) "Hinner jag? · $now · demo" else "Hinner jag? · $now"
    }

    private fun items(plans: List<RoutePlan>): ItemList {
        val list = ItemList.Builder()
        val trip = graph.trips.state.value
        val following = trip?.route?.id
        plans.forEachIndexed { index, plan ->
            // A followed trip knows more (the drive so far, a queue): show what the trip watcher sees.
            val v = if (plan.route.id == following) trip!!.verdict else plan.verdict
            val details = withDistance(
                plan.driveMeters,
                buildString {
                    if (plan.route.id == following) append("Följer resan · ")
                    append(Copy.listDetails(v))
                    if (plan.etaSource == EtaSource.ESTIMATE) append(" (uppskattad)")
                },
            )
            val place = Place.Builder(CarLocation.create(plan.route.port.lat, plan.route.port.lon))
                .setMarker(PlaceMarker.Builder().setLabel("${index + 1}").setColor(markerColor(v)).build())
                .build()
            list.addItem(
                Row.Builder()
                    .setTitle(Copy.title(plan.route))
                    .addText(details)
                    .addText(colored(Copy.listStatus(v), textColor(v)))
                    .setMetadata(Metadata.Builder().setPlace(place).build())
                    .setOnClickListener { screenManager.push(FerryDetailScreen(carContext, plan.route.id)) }
                    .build(),
            )
        }
        return list.build()
    }

    private fun actionStrip(): ActionStrip {
        val strip = ActionStrip.Builder()
            .addAction(carContext.action("Marginaler", R.drawable.ic_tune) { screenManager.push(MarginsScreen(carContext)) })
        if (graph.trips.state.value != null) {
            strip.addAction(carContext.action("Avsluta resa", R.drawable.ic_stop) { graph.trips.stop() })
        }
        return strip.build()
    }

    private companion object {
        const val REFRESH_MS = 60_000L
    }
}
