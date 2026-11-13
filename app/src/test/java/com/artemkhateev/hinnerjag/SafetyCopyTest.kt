package com.artemkhateev.hinnerjag

import com.artemkhateev.hinnerjag.model.Copy
import com.artemkhateev.hinnerjag.model.TimetableParser
import com.artemkhateev.hinnerjag.model.Verdicts
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Sweeps every route, every minute of the day and a range of drive times, and checks
 * that nothing the app can say urges the driver on.
 */
class SafetyCopyTest {

    private val timetable = TimetableParser.parse(File("src/main/assets/ferries.json").readText())

    @Test fun `no generated sentence urges the driver to hurry`() {
        var checked = 0
        for (route in timetable.routes) {
            for (nowMinute in 0 until 24 * 60 step 7) {
                for (drive in listOf(5, 18, 33, 47, 90)) {
                    for (pinned in listOf<Int?>(null) + route.departures.take(3)) {
                        val v = Verdicts.evaluate(route.departures, nowMinute * 60, drive * 60, route.defaultMarginMinutes, pinned)
                        val sentences = listOfNotNull(
                            Copy.listStatus(v), Copy.listDetails(v), Copy.headline(v), Copy.body(route, v),
                            Copy.alert(route, v, true)?.title, Copy.alert(route, v, true)?.text,
                            Copy.alert(route, v, false)?.text, Copy.ongoing(route, v),
                        )
                        for (s in sentences) {
                            val lower = s.lowercase()
                            assertFalse("Urges to hurry: \"$s\"", "skynd" in lower)
                            assertFalse("Bargains with speed: \"$s\"", "om du kör" in lower)
                            if ("fortare" in lower) assertTrue("Only 'kör inte fortare' is allowed: \"$s\"", "kör inte fortare" in lower)
                            checked++
                        }
                    }
                }
            }
        }
        assertTrue(checked > 10_000)
    }
}
