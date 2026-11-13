package com.artemkhateev.hinnerjag.model

/**
 * Every sentence the driver sees, in one place, so the tone can be reviewed and tested.
 * Never "skynda", never "du hinner om du ...": the app does not negotiate with speed.
 */
object Copy {

    fun clock(minuteOfDay: Int): String = "%02d:%02d".format((minuteOfDay / 60) % 24, minuteOfDay % 60)

    fun clockOfSecond(secondOfDay: Int): String = clock(secondOfDay / 60)

    fun title(route: FerryRoute) = "${route.from} → ${route.to}"

    /** 45 → "45 min", 65 → "1 tim 5 min", 180 → "3 tim". */
    fun duration(minutes: Int): String {
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m min"
            m == 0 -> "$h tim"
            else -> "$h tim $m min"
        }
    }

    /** A rough "how long for a coffee": 177 → "~3 tim", 95 → "~1 tim 30 min", 29 → "~30 min". */
    fun roughDuration(minutes: Int): String {
        val step = if (minutes >= 60) 15 else 5
        val rounded = ((minutes + step / 2) / step * step).coerceAtLeast(step)
        return "~" + duration(rounded)
    }

    // --- List rows ---------------------------------------------------------------

    fun listDetails(v: Verdict): String =
        if (v.departure == null) "Restid ${duration(v.driveMinutes)}"
        else "Avgång ${clock(v.departure)} · restid ${duration(v.driveMinutes)}"

    fun listStatus(v: Verdict): String = when (v.status) {
        Status.ON_TIME -> "Du hinner · ${duration(v.slackMinutes!!)} marginal"
        Status.TIGHT -> "Ont om tid · ${v.slackMinutes} min marginal"
        Status.MISSED -> v.next?.let { "Du hinner inte · nästa ${clock(it)}" } ?: "Du hinner inte · sista avgången i dag"
        Status.NONE_LEFT -> "Inga fler avgångar i dag"
    }

    // --- Detail screen -----------------------------------------------------------

    fun headline(v: Verdict): String = when (v.status) {
        Status.ON_TIME -> "Du hinner"
        Status.TIGHT -> "Ont om tid"
        Status.MISSED -> if (v.coffee) "Ingen stress. Ta en fika." else "Ingen stress"
        Status.NONE_LEFT -> "Inga fler avgångar i dag"
    }

    fun marginLine(slackMinutes: Int): String = when {
        slackMinutes >= 60 -> "${duration(slackMinutes)} marginal"
        slackMinutes == 1 -> "1 minuts marginal"
        else -> "$slackMinutes minuters marginal"
    }

    fun body(route: FerryRoute, v: Verdict, tomorrowFirst: Int? = route.departures.firstOrNull()): String? = when (v.status) {
        Status.ON_TIME -> null
        Status.TIGHT -> v.next
            ?.let { "Kör inte fortare. Missar du den går nästa ${clock(it)} – det är helt okej." }
            ?: "Kör inte fortare. Det är dagens sista avgång – blir det för sent planerar vi om när du står still."
        Status.MISSED -> {
            val missed = "${route.vessel.definite.replaceFirstChar { it.uppercase() }} ${clock(v.departure!!)} " +
                "går innan du hinner ${boardingVerb(route.boarding)}."
            missed + (
                v.next?.let { " Det gör inget – nästa går ${clock(it)}." }
                    ?: " Det var dagens sista avgång. Planera om i lugn och ro när du står still."
                )
        }
        Status.NONE_LEFT -> tomorrowFirst?.let { "Första avgången i morgon: ${clock(it)}." }
    }

    private fun boardingVerb(b: Boarding) = when (b) {
        Boarding.CHECK_IN -> "checka in"
        Boarding.QUEUE -> "ställa dig i kön"
        Boarding.WALK -> "gå ombord"
    }

    fun arrivalStep(route: FerryRoute, v: Verdict) =
        "Du är vid ${route.portName} – om ${duration(v.driveMinutes)}"

    fun boardingStep(route: FerryRoute, marginMinutes: Int) = when (route.boarding) {
        Boarding.CHECK_IN -> "Incheckningen stänger: $marginMinutes min före avgång"
        Boarding.QUEUE -> "Stå i kön: $marginMinutes min före avgång"
        Boarding.WALK -> "Parkera och gå till bryggan: $marginMinutes min före avgång"
    }

    fun nextTitle(route: FerryRoute, next: Int) = "Nästa ${route.vessel.indefinite} ${clock(next)}"

    fun nextDetails(v: Verdict): String {
        val wait = v.waitMinutes ?: 0
        return "I hamnen ~${clock(v.arrival)} · " +
            if (v.coffee) "tid för fika ${roughDuration(wait)}" else "${duration(wait)} att vänta"
    }

    /** "Ta 17:15-färjan", "Ta 15:00-båten". */
    fun takeDeparture(route: FerryRoute, departure: Int) = "Ta ${clock(departure)}-${route.vessel.definite}"

    fun takeInstead(departure: Int) = "Ta ${clock(departure)} i stället"

    fun boardingLabel(b: Boarding) = when (b) {
        Boarding.CHECK_IN -> "incheckning"
        Boarding.QUEUE -> "kö till färjan"
        Boarding.WALK -> "parkera och gå till bryggan"
    }

    // --- Heads-up during the drive -------------------------------------------------

    data class Alert(val title: String, val text: String)

    /** Only for a change for the worse; null when there is nothing worth interrupting for. */
    fun alert(route: FerryRoute, v: Verdict, queueSimulated: Boolean): Alert? {
        val dep = v.departure ?: return null
        return when (v.status) {
            Status.ON_TIME, Status.NONE_LEFT -> null
            Status.TIGHT -> Alert(
                title = "Det blir ont om tid till ${clock(dep)}",
                text = v.next
                    ?.let { "Kör inte fortare. Missar du den går nästa ${route.vessel.indefinite} ${clock(it)} – det är helt okej." }
                    ?: "Kör inte fortare. Det är dagens sista avgång – vi planerar om när du står still.",
            )
            Status.MISSED -> {
                val cause = if (queueSimulated) "Kö på vägen" else "Restiden har ökat"
                val calm = v.next
                    ?.let { " Ingen stress – nästa ${route.vessel.indefinite} går ${clock(it)}." + if (v.coffee) " Ta en fika så länge." else "" }
                    ?: " Ingen stress – planera om när du står still."
                Alert(
                    title = "Du hinner inte till ${clock(dep)}",
                    text = "$cause: du är vid ${route.portName} ~${clock(v.arrival)}.$calm",
                )
            }
        }
    }

    fun ongoing(route: FerryRoute, v: Verdict): String {
        val dep = v.departure?.let { " · avgång ${clock(it)}" } ?: ""
        return "Följer resan till ${route.portName}$dep · framme ~${clock(v.arrival)}"
    }

    const val ARRIVED = "Du är framme. Trevlig resa!"

    fun timetableLabel(origin: com.artemkhateev.hinnerjag.timetable.TimetableOrigin) = when (origin) {
        com.artemkhateev.hinnerjag.timetable.TimetableOrigin.VASTTRAFIK -> "Tider från Västtrafik"
        com.artemkhateev.hinnerjag.timetable.TimetableOrigin.INVENTED -> "Påhittade tider"
    }
}
