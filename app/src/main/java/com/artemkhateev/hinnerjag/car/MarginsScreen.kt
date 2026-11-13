package com.artemkhateev.hinnerjag.car

import android.Manifest
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.model.Action
import androidx.car.app.model.ItemList
import androidx.car.app.model.ListTemplate
import androidx.car.app.model.Row
import androidx.car.app.model.SectionedItemList
import androidx.car.app.model.Template
import androidx.car.app.model.Toggle
import com.artemkhateev.hinnerjag.CarPosition
import com.artemkhateev.hinnerjag.Settings
import com.artemkhateev.hinnerjag.graph
import com.artemkhateev.hinnerjag.model.Copy

/**
 * Boarding margins per route (check-in, queue, walk to the pier differ a lot) and the demo
 * switches. A tap moves a value to the next option; no typing in the car.
 */
class MarginsScreen(carContext: CarContext) : Screen(carContext) {

    private val graph = carContext.graph
    private val settings = graph.settings

    override fun onGetTemplate(): Template {
        val margins = ItemList.Builder()
        graph.timetable.routes.forEach { route ->
            val minutes = settings.margin(route)
            margins.addItem(
                Row.Builder()
                    .setTitle(Copy.title(route))
                    .addText("$minutes min före avgång · ${Copy.boardingLabel(route.boarding)}")
                    .setOnClickListener {
                        settings.setMargin(route, nextOf(Settings.MARGIN_OPTIONS, minutes))
                        invalidate()
                    }
                    .build(),
            )
        }

        val demo = ItemList.Builder().addItem(
            Row.Builder()
                .setTitle("Demoläge")
                .addText("Klockan börjar på 13:30, bilen står i ${CarPosition.DEMO_START_NAME}")
                .setToggle(Toggle.Builder { on -> setDemo(on) }.setChecked(settings.demoMode).build())
                .build(),
        )
        if (settings.demoMode) {
            val queue = settings.simulatedQueueMinutes
            demo.addItem(
                Row.Builder()
                    .setTitle("Simulerad kö under resan")
                    .addText(if (queue == 0) "Ingen" else "+$queue min, en halv minut efter att du tryckt Kör")
                    .setOnClickListener {
                        settings.simulatedQueueMinutes = nextOf(Settings.QUEUE_OPTIONS, queue)
                        invalidate()
                    }
                    .build(),
            )
        }

        return ListTemplate.Builder()
            .setTitle("Marginaler")
            .setHeaderAction(Action.BACK)
            .addSectionedList(SectionedItemList.create(margins.build(), "Tid före avgång – tryck för att ändra"))
            .addSectionedList(SectionedItemList.create(demo.build(), "Demo"))
            .build()
    }

    private fun setDemo(on: Boolean) {
        settings.demoMode = on
        graph.clock.restartDemo()
        graph.trips.stop()
        if (!on && !graph.position.hasPermission()) {
            carContext.requestPermissions(listOf(Manifest.permission.ACCESS_FINE_LOCATION)) { _, _ -> invalidate() }
        }
        invalidate()
    }

    private fun nextOf(options: List<Int>, current: Int) = options.firstOrNull { it > current } ?: options.first()
}
