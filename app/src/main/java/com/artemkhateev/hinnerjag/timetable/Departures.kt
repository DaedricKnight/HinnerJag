package com.artemkhateev.hinnerjag.timetable

import android.content.SharedPreferences
import android.util.Log
import com.artemkhateev.hinnerjag.model.FerryRoute
import kotlinx.coroutines.CancellationException
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** Where the departure times on screen come from. */
enum class TimetableOrigin { VASTTRAFIK, INVENTED }

data class TodayDepartures(
    /** Minutes after midnight, ascending. */
    val minutes: List<Int>,
    val origin: TimetableOrigin,
    /** First departure tomorrow, when known. */
    val tomorrowFirst: Int?,
)

fun interface DepartureSource {
    suspend fun today(route: FerryRoute, fromSecondOfDay: Int): TodayDepartures
}

/** The invented times from ferries.json. */
object InventedDepartures : DepartureSource {
    override suspend fun today(route: FerryRoute, fromSecondOfDay: Int) =
        TodayDepartures(route.departures, TimetableOrigin.INVENTED, route.departures.firstOrNull())
}

/**
 * Live times for routes that Västtrafik runs (they have a "vasttrafik" block in ferries.json).
 * Everything else, and any failure, falls back to [fallback], which marks the times invented.
 */
class VasttrafikDepartures(
    private val api: VasttrafikApi,
    private val fallback: DepartureSource,
    private val gidCache: SharedPreferences?,
    private val zone: ZoneId = ZoneId.of("Europe/Stockholm"),
    private val today: () -> LocalDate = { LocalDate.now(ZoneId.of("Europe/Stockholm")) },
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val ttlMillis: Long = 120_000,
) : DepartureSource {

    private class Cached(val fromSecond: Int, val atMillis: Long, val value: TodayDepartures)
    private val cache = HashMap<String, Cached>()

    override suspend fun today(route: FerryRoute, fromSecondOfDay: Int): TodayDepartures {
        val stops = route.vasttrafik ?: return fallback.today(route, fromSecondOfDay)
        cache[route.id]?.let {
            if (nowMillis() - it.atMillis < ttlMillis && fromSecondOfDay >= it.fromSecond) return it.value
        }
        return try {
            val origin = gid(stops.origin)
            val date = today()
            val from = ZonedDateTime.of(date, LocalTime.ofSecondOfDay(fromSecondOfDay.toLong()), zone).toOffsetDateTime()
            val minutes = stops.destinations
                .flatMap { api.ferryTrips(origin, gid(it), from) }
                .map { it.expected.atZoneSameInstant(zone) }
                .filter { it.toLocalDate() == date }
                .map { it.hour * 60 + it.minute }
                .distinct()
                .sorted()
            TodayDepartures(minutes, TimetableOrigin.VASTTRAFIK, tomorrowFirst = null).also {
                cache[route.id] = Cached(fromSecondOfDay, nowMillis(), it)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("HinnerJag", "Västtrafik failed for ${route.id}, using invented times", e)
            fallback.today(route, fromSecondOfDay)
        }
    }

    /** Stop area gids are stable: look each name up once and keep it. */
    private suspend fun gid(name: String): String {
        val key = "vt_gid_$name"
        gidCache?.getString(key, null)?.let { return it }
        val gid = VasttrafikClient.pickStopArea(api.stopAreas(name), name)
            ?: throw IllegalStateException("Västtrafik has no stop area called $name")
        gidCache?.edit()?.putString(key, gid)?.apply()
        return gid
    }
}
