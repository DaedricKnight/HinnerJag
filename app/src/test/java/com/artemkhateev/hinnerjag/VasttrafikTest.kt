package com.artemkhateev.hinnerjag

import com.artemkhateev.hinnerjag.model.TimetableParser
import com.artemkhateev.hinnerjag.model.Verdicts
import com.artemkhateev.hinnerjag.timetable.FerryTrip
import com.artemkhateev.hinnerjag.timetable.InventedDepartures
import com.artemkhateev.hinnerjag.timetable.TimetableOrigin
import com.artemkhateev.hinnerjag.timetable.VasttrafikApi
import com.artemkhateev.hinnerjag.timetable.VasttrafikClient
import com.artemkhateev.hinnerjag.timetable.VasttrafikDepartures
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * The JSON follows the Planera Resa v4 models (GetJourneysResponse → JourneyApiModel →
 * TripLegApiModel). It is written from the API documentation, not captured from a live call.
 */
class VasttrafikTest {

    private fun t(text: String) = TimetableParser.parseClock(text)
    private val styrso = TimetableParser.parse(File("src/main/assets/ferries.json").readText()).route("styrso")

    private val journeys = """
        {"results":[
          {"tripLegs":[{"isCancelled":false,"plannedDepartureTime":"2026-11-13T14:25:00.000+01:00",
            "estimatedOtherwisePlannedDepartureTime":"2026-11-13T14:27:00.000+01:00",
            "serviceJourney":{"direction":"Vrångö","line":{"name":"Båt 281","shortName":"281","transportMode":"ferry"}}}]},
          {"tripLegs":[{"isCancelled":true,"plannedDepartureTime":"2026-11-13T14:55:00.000+01:00",
            "serviceJourney":{"line":{"shortName":"281","transportMode":"ferry"}}}]},
          {"tripLegs":[{"plannedDepartureTime":"2026-11-13T15:40:00.000+01:00",
            "serviceJourney":{"line":{"shortName":"282","transportMode":"ferry"}}}]},
          {"tripLegs":[{"plannedDepartureTime":"2026-11-13T15:50:00.000+01:00","serviceJourney":{"line":{"transportMode":"bus"}}}]},
          {"tripLegs":[{"plannedDepartureTime":"2026-11-13T16:00:00.000+01:00"},{"plannedDepartureTime":"2026-11-13T16:30:00.000+01:00"}]}
        ]}
    """.trimIndent()

    @Test fun `journeys keep direct ferry legs with the best known time`() {
        val trips = VasttrafikClient.parseFerryTrips(journeys)
        assertEquals(2, trips.size)
        assertEquals(OffsetDateTime.parse("2026-11-13T14:27:00+01:00"), trips[0].expected)
        assertEquals(OffsetDateTime.parse("2026-11-13T14:25:00+01:00"), trips[0].planned)
        assertEquals("281", trips[0].line)
        assertEquals(trips[1].planned, trips[1].expected) // no estimate: planned is the best we know
    }

    @Test fun `a real Planera Resa v4 answer parses`() {
        // Trimmed from a live call on 2026-11-13: Saltholmen → Styrsö Skäret from 13:30.
        val json = File("src/test/resources/vasttrafik/journeys_saltholmen_skaret.json").readText()
        val trips = VasttrafikClient.parseFerryTrips(json)
        assertEquals(listOf("14:23", "15:28", "16:30"), trips.map { it.expected.toLocalTime().toString() })
        assertEquals(listOf("281", "281", "281"), trips.map { it.line })
    }

    @Test fun `a JSON null estimate falls back to the planned time`() {
        val trips = VasttrafikClient.parseFerryTrips(
            """{"results":[{"tripLegs":[{"plannedDepartureTime":"2026-11-13T14:30:00.0000000+01:00",
                "estimatedOtherwisePlannedDepartureTime":null,"estimatedDepartureTime":null}]}]}""",
        )
        assertEquals(trips[0].planned, trips[0].expected)
    }

    @Test fun `basic credentials match the portal's authentication key`() {
        // The illustrative application on developer.vasttrafik.se/guides/get-started.
        assertEquals(
            "RXFrWXB0azVuNm43Slg5VmdmbGhkSnl3cnVrYTpNU0NqbEswaFNfa0dJd1RjYmlKSHRaVDNxRXNh",
            VasttrafikClient.basicCredentials("EqkYptk5n6n7JX9VgflhdJywruka", "MSCjlK0hS_kGIwTcbiJHtZT3qEsa"),
        )
    }

    @Test fun `token and stop areas`() {
        assertEquals("abc" to 86400, VasttrafikClient.parseToken("""{"access_token":"abc","expires_in":86400,"token_type":"Bearer"}"""))
        val areas = VasttrafikClient.parseStopAreas(
            """{"results":[{"gid":"1","name":"Saltholmens brygga, Göteborg","locationType":"stoparea"},
                {"gid":"2","name":"Saltholmen, Göteborg","locationType":"stoparea"},
                {"name":"Saltholmsgatan 60, Göteborg","locationType":"address"}]}""",
        )
        assertEquals(2, areas.size)
        assertEquals("2", VasttrafikClient.pickStopArea(areas, "Saltholmen"))
        assertNull(VasttrafikClient.pickStopArea(areas, "Styrsö Bratten"))
    }

    private class FakeApi(val fail: Boolean = false) : VasttrafikApi {
        val tripCalls = mutableListOf<Pair<String, String>>()
        override suspend fun stopAreas(text: String) = listOf(
            "9021014006480000" to "Saltholmen, Göteborg",
            "9021014006590000" to "Styrsö Bratten, Göteborg",
            "9021014006600000" to "Styrsö Tången, Göteborg",
            "9021014006610000" to "Styrsö Skäret, Göteborg",
        ).filter { it.second.startsWith(text) }

        override suspend fun ferryTrips(originGid: String, destinationGid: String, from: OffsetDateTime): List<FerryTrip> {
            if (fail) throw IOException("offline")
            tripCalls += originGid to destinationGid
            fun at(s: String) = OffsetDateTime.parse("2026-11-13T$s:00+01:00")
            return when (destinationGid) {
                "9021014006610000" -> emptyList() // Skäret: nothing in this window
                // Both piers are served by the 14:27 boat; Tången also gets one at 15:10.
                "9021014006590000" -> listOf(FerryTrip(at("14:25"), at("14:27"), "281"), FerryTrip(at("15:40"), at("15:40"), "282"))
                else -> listOf(FerryTrip(at("14:25"), at("14:27"), "281"), FerryTrip(at("15:10"), at("15:10"), "281"),
                    FerryTrip(OffsetDateTime.parse("2026-11-14T06:00:00+01:00"), OffsetDateTime.parse("2026-11-14T06:00:00+01:00"), "281"))
            }
        }
    }

    @Test fun `live departures merge all Styrsö piers, drop tomorrow and feed the verdict`() = runBlocking {
        val api = FakeApi()
        val source = VasttrafikDepartures(api, InventedDepartures, gidCache = null, today = { LocalDate.of(2026, 11, 13) })
        val today = source.today(styrso, t("13:30") * 60)
        assertEquals(TimetableOrigin.VASTTRAFIK, today.origin)
        assertEquals(listOf(t("14:27"), t("15:10"), t("15:40")), today.minutes)
        assertEquals(
            setOf("9021014006590000", "9021014006600000", "9021014006610000").map { "9021014006480000" to it }.toSet(),
            api.tripCalls.toSet(),
        )

        // 35 min drive from 13:30 → at the pier 14:05, ten minutes' margin → 12 minutes to spare for the 14:27 boat.
        val v = Verdicts.evaluate(today.minutes, t("13:30") * 60, 35 * 60, 10)
        assertEquals(t("14:27"), v.departure)
        assertEquals(12, v.slackMinutes)
    }

    @Test fun `a failing Västtrafik falls back to the invented times and says so`() = runBlocking {
        val source = VasttrafikDepartures(FakeApi(fail = true), InventedDepartures, gidCache = null)
        val today = source.today(styrso, t("13:30") * 60)
        assertEquals(TimetableOrigin.INVENTED, today.origin)
        assertEquals(styrso.departures, today.minutes)
    }

    @Test fun `routes Västtrafik does not run never call it`() = runBlocking {
        val api = FakeApi()
        val honoleden = TimetableParser.parse(File("src/main/assets/ferries.json").readText()).route("honoleden")
        val today = VasttrafikDepartures(api, InventedDepartures, gidCache = null).today(honoleden, 0)
        assertEquals(TimetableOrigin.INVENTED, today.origin)
        assertEquals(0, api.tripCalls.size)
    }
}
