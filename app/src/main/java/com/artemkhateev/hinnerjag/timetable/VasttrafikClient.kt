package com.artemkhateev.hinnerjag.timetable

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

/** A direct ferry departure as Västtrafik reports it. */
data class FerryTrip(
    val planned: OffsetDateTime,
    /** Real-time estimate when Västtrafik has one, otherwise the planned time. */
    val expected: OffsetDateTime,
    val line: String,
)

interface VasttrafikApi {
    /** Stop areas whose name matches [text]: (gid, name). */
    suspend fun stopAreas(text: String): List<Pair<String, String>>

    /** Direct ferry trips from [originGid] to [destinationGid] departing from [from] on. */
    suspend fun ferryTrips(originGid: String, destinationGid: String, from: OffsetDateTime): List<FerryTrip>
}

/**
 * Västtrafik "Planera Resa v4". Needs a client id and secret from developer.vasttrafik.se
 * (an application subscribed to Planera Resa v4); they come from local.properties.
 */
class VasttrafikClient(
    private val clientId: String,
    private val clientSecret: String,
    private val baseUrl: String = "https://ext-api.vasttrafik.se",
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : VasttrafikApi {

    private var token: String? = null
    private var tokenExpiresAt = 0L

    override suspend fun stopAreas(text: String): List<Pair<String, String>> =
        parseStopAreas(get("/pr/v4/locations/by-text?q=${enc(text)}&types=stoparea&limit=10"))

    override suspend fun ferryTrips(originGid: String, destinationGid: String, from: OffsetDateTime): List<FerryTrip> =
        parseFerryTrips(
            get(
                "/pr/v4/journeys?originGid=$originGid&destinationGid=$destinationGid" +
                    "&dateTime=${enc(from.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))}&dateTimeRelatesTo=departure" +
                    "&transportModes=ferry&onlyDirectConnections=true&limit=10",
            ),
        )

    private suspend fun get(path: String): String = withContext(Dispatchers.IO) {
        val conn = URL(baseUrl + path).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 5_000
            conn.readTimeout = 8_000
            conn.setRequestProperty("Authorization", "Bearer ${accessToken()}")
            conn.setRequestProperty("Accept", "application/json")
            val code = conn.responseCode
            if (code == 401) token = null // expired early: fetch a new one next time
            if (code != 200) throw IOException("Västtrafik HTTP $code for ${path.substringBefore('?')}")
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    @Synchronized
    private fun accessToken(): String {
        token?.let { if (nowMillis() < tokenExpiresAt) return it }
        val conn = URL("$baseUrl/token").openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 5_000
            conn.readTimeout = 8_000
            // As Västtrafik's OAuth guide says: Basic base64(client id:secret), body grant_type only.
            conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
            conn.setRequestProperty("Authorization", "Basic " + basicCredentials(clientId, clientSecret))
            conn.outputStream.use { it.write("grant_type=client_credentials".toByteArray()) }
            if (conn.responseCode != 200) throw IOException("Västtrafik token HTTP ${conn.responseCode}")
            val (value, expiresIn) = parseToken(conn.inputStream.bufferedReader().use { it.readText() })
            token = value
            tokenExpiresAt = nowMillis() + (expiresIn - 60).coerceAtLeast(30) * 1000L
            return value
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

        /** What the portal calls "Autentiseringsnyckel". */
        fun basicCredentials(clientId: String, clientSecret: String): String =
            java.util.Base64.getEncoder().encodeToString("$clientId:$clientSecret".toByteArray())

        fun parseToken(json: String): Pair<String, Int> {
            val o = JSONObject(json)
            return o.getString("access_token") to o.optInt("expires_in", 3600)
        }

        fun parseStopAreas(json: String): List<Pair<String, String>> {
            val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
            return (0 until results.length()).map { results.getJSONObject(it) }
                .filter { it.optString("locationType") == "stoparea" && it.has("gid") }
                .map { it.getString("gid") to it.getString("name") }
        }

        /** Direct, not cancelled ferry legs; anything else in the answer is ignored. */
        fun parseFerryTrips(json: String): List<FerryTrip> {
            val results = JSONObject(json).optJSONArray("results") ?: return emptyList()
            return (0 until results.length()).mapNotNull { i ->
                val legs = results.getJSONObject(i).optJSONArray("tripLegs") ?: return@mapNotNull null
                if (legs.length() != 1) return@mapNotNull null
                val leg = legs.getJSONObject(0)
                if (leg.optBoolean("isCancelled")) return@mapNotNull null
                val line = leg.optJSONObject("serviceJourney")?.optJSONObject("line")
                if (line != null && line.optString("transportMode", "ferry") != "ferry") return@mapNotNull null
                val planned = leg.text("plannedDepartureTime") ?: return@mapNotNull null
                val expected = leg.text("estimatedOtherwisePlannedDepartureTime")
                    ?: leg.text("estimatedDepartureTime")
                    ?: planned
                FerryTrip(
                    planned = OffsetDateTime.parse(planned),
                    expected = OffsetDateTime.parse(expected),
                    line = line?.optString("shortName")?.takeIf { it.isNotEmpty() } ?: line?.optString("name").orEmpty(),
                )
            }
        }

        /** org.json turns a JSON null into the string "null"; treat it, and "", as missing. */
        private fun JSONObject.text(key: String): String? =
            if (isNull(key)) null else optString(key).takeIf { it.isNotEmpty() }

        /** "Saltholmen, Göteborg" matches "Saltholmen"; "Saltholmens brygga" does not. */
        fun pickStopArea(candidates: List<Pair<String, String>>, wanted: String): String? =
            candidates.firstOrNull { (_, name) -> name.substringBefore(',').trim().equals(wanted.trim(), ignoreCase = true) }?.first
    }
}
