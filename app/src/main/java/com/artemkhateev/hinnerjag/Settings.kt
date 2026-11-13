package com.artemkhateev.hinnerjag

import android.content.Context
import android.os.SystemClock
import com.artemkhateev.hinnerjag.model.FerryRoute
import java.time.LocalTime

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("hinnerjag", Context.MODE_PRIVATE)

    fun margin(route: FerryRoute): Int = prefs.getInt("margin_${route.id}", route.defaultMarginMinutes)

    fun setMargin(route: FerryRoute, minutes: Int) = prefs.edit().putInt("margin_${route.id}", minutes).apply()

    /** Demo: the clock starts at 13:30 and the car starts in Kungälv, so the mockup's three cases show up. */
    var demoMode: Boolean
        get() = prefs.getBoolean("demo", true)
        set(value) = prefs.edit().putBoolean("demo", value).apply()

    /** Demo only: a queue that appears half a minute into a trip, to trigger the heads-up. */
    var simulatedQueueMinutes: Int
        get() = prefs.getInt("queue", 0)
        set(value) = prefs.edit().putInt("queue", value).apply()

    companion object {
        val MARGIN_OPTIONS = listOf(5, 10, 15, 20, 30, 45)
        val QUEUE_OPTIONS = listOf(0, 12, 20)
    }
}

/** Real time, or in demo mode 13:30 at process start (or when demo is switched on), then ticking normally. */
class AppClock(private val settings: Settings) {
    private var demoAnchor = SystemClock.elapsedRealtime()

    fun restartDemo() {
        demoAnchor = SystemClock.elapsedRealtime()
    }

    fun nowSecondOfDay(): Int =
        if (settings.demoMode) {
            (DEMO_START_SECOND + (SystemClock.elapsedRealtime() - demoAnchor) / 1000).toInt() % SECONDS_PER_DAY
        } else {
            LocalTime.now().toSecondOfDay()
        }

    companion object {
        const val DEMO_START_SECOND = (13 * 60 + 30) * 60
        private const val SECONDS_PER_DAY = 24 * 3600
    }
}
