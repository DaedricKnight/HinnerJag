package com.artemkhateev.hinnerjag

import com.artemkhateev.hinnerjag.model.Copy
import com.artemkhateev.hinnerjag.model.Status
import com.artemkhateev.hinnerjag.model.TimetableParser
import com.artemkhateev.hinnerjag.model.Verdicts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class VerdictsTest {

    private fun t(text: String) = TimetableParser.parseClock(text)
    private fun sec(text: String) = t(text) * 60
    private fun min(m: Double) = (m * 60).toInt()

    private val timetable = TimetableParser.parse(File("src/main/assets/ferries.json").readText())
    private val frederikshavn = timetable.route("frederikshavn")
    private val honoleden = timetable.route("honoleden")
    private val styrso = timetable.route("styrso")

    // The demo: 13:30, starting in Kungälv, OSRM drive times 27.8 / 39.5 / 34.9 min.

    @Test fun `frederikshavn at 13_30 is missed with time for a coffee`() {
        val v = Verdicts.evaluate(frederikshavn.departures, sec("13:30"), min(27.8), 20)
        assertEquals(Status.MISSED, v.status)
        assertEquals(t("14:00"), v.departure)
        assertEquals(t("13:58"), v.arrival)
        assertEquals(t("17:15"), v.next)
        assertEquals(177, v.waitMinutes)
        assertTrue(v.coffee)
        assertEquals("Du hinner inte · nästa 17:15", Copy.listStatus(v))
        assertEquals("Ingen stress. Ta en fika.", Copy.headline(v))
        assertEquals("I hamnen ~13:58 · tid för fika ~3 tim", Copy.nextDetails(v))
        assertEquals(
            "Färjan 14:00 går innan du hinner checka in. Det gör inget – nästa går 17:15.",
            Copy.body(frederikshavn, v),
        )
    }

    @Test fun `honoleden skips the crossing you cannot make and is tight on the next`() {
        val v = Verdicts.evaluate(honoleden.departures, sec("13:30"), min(39.5), 5)
        assertEquals(Status.TIGHT, v.status)
        assertEquals(t("14:18"), v.departure)
        assertEquals(3, v.slackMinutes)
        assertEquals(t("14:33"), v.next)
        assertEquals("Ont om tid · 3 min marginal", Copy.listStatus(v))
        assertEquals("Kör inte fortare. Missar du den går nästa 14:33 – det är helt okej.", Copy.body(honoleden, v))
    }

    @Test fun `styrso is on time for the 14_30 boat`() {
        val v = Verdicts.evaluate(styrso.departures, sec("13:30"), min(34.9), 10)
        assertEquals(Status.ON_TIME, v.status)
        assertEquals(t("14:30"), v.departure)
        assertEquals(t("14:05"), v.arrival)
        assertEquals(15, v.slackMinutes)
        assertEquals("Du hinner · 15 min marginal", Copy.listStatus(v))
    }

    @Test fun `a pinned departure that cannot be made is missed even when the next is soon`() {
        val v = Verdicts.evaluate(honoleden.departures, sec("13:30"), min(39.5), 5, pinned = t("14:03"))
        assertEquals(Status.MISSED, v.status)
        assertEquals(t("14:18"), v.next)
        assertEquals(3, v.waitMinutes)
        assertFalse(v.coffee)
        assertEquals("Ingen stress", Copy.headline(v))
        assertEquals("I hamnen ~14:10 · 3 min att vänta", Copy.nextDetails(v))
    }

    @Test fun `exactly at the tight threshold counts as on time`() {
        val v = Verdicts.evaluate(listOf(t("14:30")), sec("14:00"), sec("00:15"), 10)
        assertEquals(5, v.slackMinutes)
        assertEquals(Status.ON_TIME, v.status)
    }

    @Test fun `arriving on the cut-off minute is tight with zero slack`() {
        val v = Verdicts.evaluate(listOf(t("14:30"), t("15:00")), sec("14:00"), sec("00:20"), 10)
        assertEquals(Status.TIGHT, v.status)
        assertEquals(0, v.slackMinutes)
    }

    @Test fun `arrival is rounded up so a fraction of a minute never buys a departure`() {
        // 14:00 + 20 min 1 s = 14:20:01 → 14:21, one minute after the 14:20 cut-off.
        val v = Verdicts.evaluate(listOf(t("14:30"), t("16:30")), sec("14:00"), sec("00:20") + 1, 10, pinned = t("14:30"))
        assertEquals(Status.MISSED, v.status)
        assertEquals(t("14:21"), v.arrival)
    }

    @Test fun `a departure whose cut-off has passed is not the target`() {
        // 13:45: the 14:00 check-in closed at 13:40, so the app talks about 17:15.
        val v = Verdicts.evaluate(frederikshavn.departures, sec("13:45"), min(27.8), 20)
        assertEquals(Status.ON_TIME, v.status)
        assertEquals(t("17:15"), v.departure)
        assertEquals("Du hinner · 2 tim 42 min marginal", Copy.listStatus(v))
    }

    @Test fun `nothing left after the last departure`() {
        val v = Verdicts.evaluate(frederikshavn.departures, sec("22:20"), min(27.8), 20)
        assertEquals(Status.NONE_LEFT, v.status)
        assertNull(v.departure)
        assertEquals("Inga fler avgångar i dag", Copy.listStatus(v))
        assertEquals("Första avgången i morgon: 08:00.", Copy.body(frederikshavn, v))
    }

    @Test fun `missing the last departure of the day says so calmly`() {
        val v = Verdicts.evaluate(frederikshavn.departures, sec("22:00"), min(27.8), 20)
        assertEquals(Status.MISSED, v.status)
        assertNull(v.next)
        assertEquals("Du hinner inte · sista avgången i dag", Copy.listStatus(v))
    }

    @Test fun `heads-up while driving matches the mockup`() {
        // Trip pinned to the 14:30 boat, a 20-minute queue on the way: at Saltholmen 14:25.
        val v = Verdicts.evaluate(styrso.departures, sec("13:31"), sec("00:54"), 10, pinned = t("14:30"))
        val alert = Copy.alert(styrso, v, queueSimulated = true)!!
        assertEquals("Du hinner inte till 14:30", alert.title)
        assertEquals(
            "Kö på vägen: du är vid Saltholmen ~14:25. Ingen stress – nästa båt går 15:00. Ta en fika så länge.",
            alert.text,
        )
    }

    @Test fun `no heads-up while on time`() {
        val v = Verdicts.evaluate(styrso.departures, sec("13:30"), min(34.9), 10)
        assertNull(Copy.alert(styrso, v, queueSimulated = false))
    }

    @Test fun `the timetable expands repeating departures`() {
        assertEquals(t("05:03"), honoleden.departures.first())
        assertEquals(t("23:48"), honoleden.departures.last())
        assertEquals(76, honoleden.departures.size)
        assertEquals(listOf(t("08:00"), t("14:00"), t("17:15"), t("22:30")), frederikshavn.departures)
    }

    @Test fun `durations read naturally`() {
        assertEquals("45 min", Copy.duration(45))
        assertEquals("1 tim 5 min", Copy.duration(65))
        assertEquals("3 tim", Copy.duration(180))
        assertEquals("~3 tim", Copy.roughDuration(177))
        assertEquals("~1 tim 30 min", Copy.roughDuration(95))
        assertEquals("~30 min", Copy.roughDuration(29))
        assertEquals("1 minuts marginal", Copy.marginLine(1))
        assertEquals("15 minuters marginal", Copy.marginLine(15))
    }
}
