package com.artemkhateev.hinnerjag.model

enum class Status { ON_TIME, TIGHT, MISSED, NONE_LEFT }

/**
 * What the app tells the driver about one route. Times are minutes after midnight.
 *
 * The rule the whole app is built around: there is no state that says "you will
 * make it if you hurry". Under [Verdicts.TIGHT_BELOW_MINUTES] of slack the answer is
 * TIGHT, and TIGHT always comes with the next departure as a calm way out.
 */
data class Verdict(
    val status: Status,
    /** The departure this verdict is about; null only when nothing is left today. */
    val departure: Int?,
    /** When you are at the port, rounded up to the minute. */
    val arrival: Int,
    val driveMinutes: Int,
    val marginMinutes: Int,
    /** Minutes between arriving and the boarding cut-off; negative when missed. */
    val slackMinutes: Int?,
    /** ON_TIME/TIGHT: the departure after this one. MISSED: the first one you can make. */
    val next: Int?,
    /** MISSED only: minutes from arriving to the next departure's cut-off. */
    val waitMinutes: Int?,
) {
    val boardBy: Int? get() = departure?.minus(marginMinutes)

    /** Missing it costs enough time to sit down with a coffee. */
    val coffee: Boolean get() = status == Status.MISSED && (waitMinutes ?: 0) >= Verdicts.COFFEE_MIN_WAIT_MINUTES
}

object Verdicts {
    /** Less slack than this is "ont om tid". */
    const val TIGHT_BELOW_MINUTES = 5

    /** A missed departure is only worth mentioning when the next one is this far away. */
    const val COFFEE_MIN_WAIT_MINUTES = 20

    /**
     * @param departures today's departures, ascending, minutes after midnight
     * @param nowSecond seconds after midnight
     * @param driveSeconds expected drive to the port
     * @param marginMinutes boarding margin before departure (check-in, queue, walk)
     * @param pinned a departure the driver chose; null lets the app pick
     *
     * Without [pinned], the target is the next departure whose cut-off has not passed.
     * If that one cannot be made but the following one leaves soon (a frequent ferry),
     * the app talks about the following one instead: nobody plans a specific Hönöleden
     * crossing, and "you missed it, next one in 3 minutes" helps no one.
     */
    fun evaluate(
        departures: List<Int>,
        nowSecond: Int,
        driveSeconds: Int,
        marginMinutes: Int,
        pinned: Int? = null,
    ): Verdict {
        val nowMinute = nowSecond / 60
        val arrival = ceilDiv(nowSecond + driveSeconds.coerceAtLeast(0), 60)
        val driveMinutes = ceilDiv(driveSeconds.coerceAtLeast(0), 60)
        fun cutoff(d: Int) = d - marginMinutes
        fun reachable(d: Int) = cutoff(d) >= arrival

        fun onTimeOrTight(d: Int): Verdict {
            val slack = cutoff(d) - arrival
            return Verdict(
                status = if (slack >= TIGHT_BELOW_MINUTES) Status.ON_TIME else Status.TIGHT,
                departure = d,
                arrival = arrival,
                driveMinutes = driveMinutes,
                marginMinutes = marginMinutes,
                slackMinutes = slack,
                next = departures.firstOrNull { it > d },
                waitMinutes = null,
            )
        }

        val target = pinned ?: departures.firstOrNull { cutoff(it) >= nowMinute }
            ?: return Verdict(Status.NONE_LEFT, null, arrival, driveMinutes, marginMinutes, null, null, null)

        if (reachable(target)) return onTimeOrTight(target)

        val next = departures.firstOrNull { it > target && reachable(it) }
        val wait = next?.let { cutoff(it) - arrival }
        if (pinned == null && next != null && wait!! < COFFEE_MIN_WAIT_MINUTES) return onTimeOrTight(next)

        return Verdict(
            status = Status.MISSED,
            departure = target,
            arrival = arrival,
            driveMinutes = driveMinutes,
            marginMinutes = marginMinutes,
            slackMinutes = cutoff(target) - arrival,
            next = next,
            waitMinutes = wait,
        )
    }

    /** Higher is worse; used to decide when a trip deserves a heads-up. */
    fun severity(status: Status): Int = when (status) {
        Status.ON_TIME -> 0
        Status.TIGHT -> 1
        Status.MISSED, Status.NONE_LEFT -> 2
    }

    private fun ceilDiv(a: Int, b: Int) = (a + b - 1) / b
}
