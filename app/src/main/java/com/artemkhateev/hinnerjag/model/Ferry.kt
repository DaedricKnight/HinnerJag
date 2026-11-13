package com.artemkhateev.hinnerjag.model

import org.json.JSONObject

data class LatLon(val lat: Double, val lon: Double)

/** How you board, which decides what the boarding margin is spent on. */
enum class Boarding { CHECK_IN, QUEUE, WALK }

/** Swedish nouns: "nästa färja", "färjan 14:00". */
enum class Vessel(val indefinite: String, val definite: String) {
    FERRY("färja", "färjan"),
    BOAT("båt", "båten"),
}

/** Stop area names in Västtrafik for a route they run; the destination may have several piers. */
data class VasttrafikStops(val origin: String, val destinations: List<String>)

data class FerryRoute(
    val id: String,
    val from: String,
    val to: String,
    val description: String,
    val portName: String,
    val port: LatLon,
    val vessel: Vessel,
    val boarding: Boarding,
    val defaultMarginMinutes: Int,
    /** Invented departures as minutes after midnight, ascending; the fallback for live routes. */
    val departures: List<Int>,
    /** Set when Västtrafik runs the route and can give real times. */
    val vasttrafik: VasttrafikStops? = null,
)

data class Timetable(val note: String, val routes: List<FerryRoute>) {
    fun route(id: String): FerryRoute = routes.first { it.id == id }
}

object TimetableParser {

    fun parse(json: String): Timetable {
        val root = JSONObject(json)
        val routes = root.getJSONArray("routes")
        return Timetable(
            note = root.optString("note"),
            routes = (0 until routes.length()).map { parseRoute(routes.getJSONObject(it)) },
        )
    }

    private fun parseRoute(o: JSONObject): FerryRoute {
        val departures = when {
            o.has("departures") -> {
                val list = o.getJSONArray("departures")
                (0 until list.length()).map { parseClock(list.getString(it)) }
            }
            o.has("every") -> {
                val every = o.getJSONObject("every")
                val step = every.getInt("minutes")
                require(step > 0) { "every.minutes must be positive in ${o.getString("id")}" }
                (parseClock(every.getString("first"))..parseClock(every.getString("last")) step step).toList()
            }
            else -> error("Route ${o.getString("id")} has neither departures nor every")
        }
        return FerryRoute(
            id = o.getString("id"),
            from = o.getString("from"),
            to = o.getString("to"),
            description = o.getString("description"),
            portName = o.getString("portName"),
            port = LatLon(o.getDouble("lat"), o.getDouble("lon")),
            vessel = Vessel.valueOf(o.getString("vessel")),
            boarding = Boarding.valueOf(o.getString("boarding")),
            defaultMarginMinutes = o.getInt("marginMinutes"),
            departures = departures.sorted(),
            vasttrafik = o.optJSONObject("vasttrafik")?.let { vt ->
                val to = vt.getJSONArray("destinations")
                VasttrafikStops(vt.getString("origin"), (0 until to.length()).map { to.getString(it) })
            },
        )
    }

    /** "14:05" → 845. */
    fun parseClock(text: String): Int {
        val (h, m) = text.split(":").map { it.trim().toInt() }
        require(h in 0..23 && m in 0..59) { "Not a time of day: $text" }
        return h * 60 + m
    }
}
