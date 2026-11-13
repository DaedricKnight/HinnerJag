package com.artemkhateev.hinnerjag.eta

import com.artemkhateev.hinnerjag.model.LatLon
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

enum class EtaSource { OSRM, ESTIMATE }

data class Eta(val seconds: Int, val meters: Int, val source: EtaSource)

fun interface EtaProvider {
    suspend fun eta(from: LatLon, to: LatLon): Eta
}

/**
 * Drive time from OSRM. The public demo server has no traffic data and a fair-use
 * policy (about one request a second): fine for a prototype, not for a product.
 * A product would use a routing API with live traffic.
 */
class OsrmEtaProvider(
    private val baseUrl: String = "https://router.project-osrm.org",
) : EtaProvider {

    override suspend fun eta(from: LatLon, to: LatLon): Eta = withContext(Dispatchers.IO) {
        val url = URL(
            "$baseUrl/route/v1/driving/${from.lon},${from.lat};${to.lon},${to.lat}" +
                "?overview=false&alternatives=false&steps=false",
        )
        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 4_000
            conn.readTimeout = 4_000
            conn.setRequestProperty("User-Agent", "HinnerJag-prototype/0.1")
            if (conn.responseCode != 200) throw IOException("OSRM HTTP ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val (seconds, meters) = parseRoute(body)
            Eta(seconds, meters, EtaSource.OSRM)
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        /** Seconds and metres of the fastest route. */
        fun parseRoute(json: String): Pair<Int, Int> {
            val root = JSONObject(json)
            if (root.optString("code") != "Ok") throw IOException("OSRM: ${root.optString("code")}")
            val route = root.getJSONArray("routes").getJSONObject(0)
            return route.getDouble("duration").toInt() to route.getDouble("distance").toInt()
        }
    }
}

/** Straight line × detour factor at town-and-motorway speed. Only for when OSRM is unreachable. */
class StraightLineEtaProvider(
    private val kmh: Double = 45.0,
    private val detour: Double = 1.35,
) : EtaProvider {
    override suspend fun eta(from: LatLon, to: LatLon): Eta {
        val km = distanceKm(from, to) * detour
        return Eta((km / kmh * 3600).toInt(), (km * 1000).toInt(), EtaSource.ESTIMATE)
    }
}

class FallbackEtaProvider(
    private val primary: EtaProvider,
    private val fallback: EtaProvider,
) : EtaProvider {
    override suspend fun eta(from: LatLon, to: LatLon): Eta = try {
        primary.eta(from, to)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fallback.eta(from, to)
    }
}

/** Screens refresh every minute; the route only needs asking once per minute and position. */
class CachingEtaProvider(
    private val delegate: EtaProvider,
    private val ttlMillis: Long = 60_000,
    private val now: () -> Long = System::currentTimeMillis,
) : EtaProvider {
    private data class Key(val from: String, val to: String)
    private val cache = HashMap<Key, Pair<Long, Eta>>()

    override suspend fun eta(from: LatLon, to: LatLon): Eta {
        val key = Key(round(from), round(to))
        synchronized(cache) {
            cache[key]?.let { (at, eta) -> if (now() - at < ttlMillis) return eta }
        }
        val fresh = delegate.eta(from, to)
        // An estimate is not worth keeping: try OSRM again next time.
        if (fresh.source == EtaSource.OSRM) synchronized(cache) { cache[key] = now() to fresh }
        return fresh
    }

    private fun round(p: LatLon) = "%.4f,%.4f".format(p.lat, p.lon)
}

fun distanceKm(a: LatLon, b: LatLon): Double {
    val r = 6371.0
    val dLat = Math.toRadians(b.lat - a.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val h = sin(dLat / 2).pow(2) + cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2).pow(2)
    return 2 * r * asin(sqrt(h))
}
