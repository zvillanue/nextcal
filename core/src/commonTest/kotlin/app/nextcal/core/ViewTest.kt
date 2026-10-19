package app.nextcal.core

import app.nextcal.core.model.CalendarRef
import app.nextcal.core.model.EventInstance
import app.nextcal.core.model.EventTime
import app.nextcal.core.model.lastDate
import app.nextcal.core.view.groupByDay
import app.nextcal.core.view.monthGrid
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals

class ViewTest {
    private val ref = CalendarRef("s", "1")
    private val utc = TimeZone.UTC

    @Test
    fun monthGridStartsOnConfiguredWeekday() {
        // October 2026 starts on a Thursday.
        val mon = monthGrid(2026, 10, DayOfWeek.MONDAY)
        assertEquals(LocalDate(2026, 9, 28), mon.first().first())
        assertEquals(5, mon.size)
        val sun = monthGrid(2026, 10, DayOfWeek.SUNDAY)
        assertEquals(LocalDate(2026, 9, 27), sun.first().first())
        assertEquals(LocalDate(2026, 10, 31), sun.last().last())
    }

    @Test
    fun monthGridUsesSixRowsWhenNeeded() {
        // March 2026 starts on Sunday; with a Monday start it spans 6 weeks.
        assertEquals(6, monthGrid(2026, 3, DayOfWeek.MONDAY).size)
        // February 2027 (28 days, starts Monday) fits in exactly 4 rows.
        assertEquals(4, monthGrid(2027, 2, DayOfWeek.MONDAY).size)
    }

    @Test
    fun eventEndingAtMidnightDoesNotSpillIntoNextDay() {
        val t = EventTime.Timed(Instant.parse("2026-10-19T22:00:00Z"), Instant.parse("2026-10-20T00:00:00Z"), utc)
        assertEquals(LocalDate(2026, 10, 19), t.lastDate(utc))
    }

    @Test
    fun multiDayAndAllDayEventsAppearOnEachDay() {
        val trip = EventInstance(ref, "1", "Trip", EventTime.AllDay(LocalDate(2026, 10, 20), LocalDate(2026, 10, 23)))
        val grouped = groupByDay(listOf(trip), utc, LocalDate(2026, 10, 1)..LocalDate(2026, 10, 31))
        assertEquals(
            listOf(LocalDate(2026, 10, 20), LocalDate(2026, 10, 21), LocalDate(2026, 10, 22)),
            grouped.keys.sorted(),
        )
    }

    @Test
    fun groupingClampsToRange() {
        val long = EventInstance(ref, "2", "Long", EventTime.AllDay(LocalDate(2026, 9, 1), LocalDate(2026, 12, 1)))
        val grouped = groupByDay(listOf(long), utc, LocalDate(2026, 10, 5)..LocalDate(2026, 10, 7))
        assertEquals(3, grouped.size)
    }
}
