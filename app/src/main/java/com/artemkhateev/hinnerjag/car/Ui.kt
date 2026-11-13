package com.artemkhateev.hinnerjag.car

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.text.SpannableString
import android.text.Spanned
import androidx.annotation.DrawableRes
import androidx.car.app.CarContext
import androidx.car.app.CarToast
import androidx.car.app.model.Action
import androidx.car.app.model.CarColor
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Distance
import androidx.car.app.model.DistanceSpan
import androidx.car.app.model.ForegroundCarColorSpan
import androidx.car.app.versioning.CarAppApiLevels
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.artemkhateev.hinnerjag.graph
import com.artemkhateev.hinnerjag.model.LatLon
import com.artemkhateev.hinnerjag.model.Status
import com.artemkhateev.hinnerjag.model.Verdict

/** Text colours: hosts only accept the standard palette inside text. */
fun textColor(v: Verdict): CarColor = when (v.status) {
    Status.ON_TIME -> CarColor.BLUE
    Status.TIGHT -> CarColor.YELLOW
    Status.MISSED -> CarColor.SECONDARY
    Status.NONE_LEFT -> CarColor.DEFAULT
}

/** Map markers may use a custom colour: the coffee case gets the mockup's latte. */
fun markerColor(v: Verdict): CarColor = when (v.status) {
    Status.ON_TIME -> CarColor.BLUE
    Status.TIGHT -> CarColor.YELLOW
    Status.MISSED -> LATTE
    Status.NONE_LEFT -> CarColor.DEFAULT
}

private val LATTE = CarColor.createCustom(0xFF9A6B43.toInt(), 0xFFE8C9A6.toInt())

fun colored(text: String, color: CarColor): CharSequence =
    SpannableString(text).apply { setSpan(ForegroundCarColorSpan.create(color), 0, length, Spanned.SPAN_INCLUSIVE_EXCLUSIVE) }

/** "27 km · …": the host requires a distance on every place row and formats it itself. */
fun withDistance(meters: Int, rest: String): CharSequence {
    val km = meters / 1000.0
    val distance = if (km < 10) Distance.create(km, Distance.UNIT_KILOMETERS_P1) else Distance.create(km, Distance.UNIT_KILOMETERS)
    return SpannableString("\u00A0 · $rest").apply {
        setSpan(DistanceSpan.create(distance), 0, 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE)
    }
}

fun CarContext.icon(@DrawableRes res: Int, tint: CarColor? = null): CarIcon =
    CarIcon.Builder(IconCompat.createWithResource(this, res)).apply { tint?.let(::setTint) }.build()

fun CarContext.action(
    title: String,
    @DrawableRes iconRes: Int? = null,
    primary: Boolean = false,
    onClick: () -> Unit,
): Action = Action.Builder()
    .setTitle(title)
    .apply {
        iconRes?.let { setIcon(icon(it)) }
        if (primary) {
            setBackgroundColor(CarColor.BLUE)
            if (carAppApiLevel >= CarAppApiLevels.LEVEL_4) setFlags(Action.FLAG_PRIMARY)
        }
    }
    .setOnClickListener(onClick)
    .build()

/** Hands the route to the car's navigation app. Third-party apps cannot read its ETA back. */
fun CarContext.navigateTo(port: LatLon) =
    startNavigation(Uri.parse("geo:${port.lat},${port.lon}"))

fun CarContext.searchNearby(query: String) =
    startNavigation(Uri.parse("geo:0,0?q=" + Uri.encode(query)))

private fun CarContext.startNavigation(uri: Uri) {
    try {
        startCarApp(Intent(CarContext.ACTION_NAVIGATE, uri))
    } catch (e: Exception) {
        CarToast.makeText(this, "Ingen navigationsapp svarade – resan följs ändå", CarToast.LENGTH_LONG).show()
    }
}

/** Location (for the trip service) and notifications (for the heads-up), asked for when they matter. */
fun CarContext.withTripPermissions(then: () -> Unit) {
    val needed = buildList {
        if (!graph.position.hasPermission()) add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this@withTripPermissions, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) add(Manifest.permission.POST_NOTIFICATIONS)
    }
    if (needed.isEmpty()) then() else requestPermissions(needed) { _, _ -> then() }
}
