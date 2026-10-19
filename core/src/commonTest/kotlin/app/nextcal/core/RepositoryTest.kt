package app.nextcal.core

import app.nextcal.core.model.AccountKind
import app.nextcal.core.model.CalendarAccount
import app.nextcal.core.model.CalendarInfo
import app.nextcal.core.model.CalendarRef
import app.nextcal.core.model.EventDraft
import app.nextcal.core.model.EventInstance
import app.nextcal.core.model.EventTime
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private class FakeSource(
    override val id: String,
    private val events: List<EventInstance> = emptyList(),
    private val fail: Boolean = false,
) : CalendarSource {
    val saved = mutableListOf<EventDraft>()

    override suspend fun calendars(): List<CalendarInfo> {
        if (fail) error("offline")
        return listOf(CalendarInfo(CalendarRef(id, "1"), CalendarAccount("acct-$id", AccountKind.CALDAV), "Cal $id", 0, true))
    }

    override suspend fun instances(calendars: Set<CalendarRef>, from: Instant, to: Instant): List<EventInstance> {
        if (fail) error("offline")
        return events.filter { it.calendar in calendars }
    }

    override suspend fun save(draft: EventDraft): String { saved += draft; return "new" }
    override suspend fun delete(calendar: CalendarRef, eventId: String) {}
}

class RepositoryTest {
    private val utc = TimeZone.UTC
    private fun timed(src: String, title: String, start: String) = EventInstance(
        CalendarRef(src, "1"), title.hashCode().toString(), title,
        EventTime.Timed(Instant.parse(start), Instant.parse(start).plus(kotlin.time.Duration.parse("1h")), utc),
    )

    @Test
    fun mergesSourcesInChronologicalOrder() = runTest {
        val a = FakeSource("a", listOf(timed("a", "Late", "2026-10-19T15:00:00Z")))
        val b = FakeSource("b", listOf(timed("b", "Early", "2026-10-19T08:00:00Z")))
        val repo = CalendarRepository(listOf(a, b))
        val res = repo.instances(listOf(CalendarRef("a", "1"), CalendarRef("b", "1")), Instant.DISTANT_PAST, Instant.DISTANT_FUTURE)
        assertEquals(listOf("Early", "Late"), res.value.map { it.title })
        assertTrue(res.errors.isEmpty())
    }

    @Test
    fun failingSourceIsReportedWithoutHidingOthers() = runTest {
        val good = FakeSource("good", listOf(timed("good", "Ok", "2026-10-19T08:00:00Z")))
        val bad = FakeSource("bad", fail = true)
        val repo = CalendarRepository(listOf(good, bad))
        val cals = repo.calendars()
        assertEquals(1, cals.value.size)
        assertEquals(listOf("bad"), cals.errors.map { it.sourceId })
    }

    @Test
    fun allDayEventsSortBeforeTimedOnSameDay() = runTest {
        val allDay = EventInstance(CalendarRef("a", "1"), "x", "Holiday", EventTime.AllDay(LocalDate(2026, 10, 19), LocalDate(2026, 10, 20)))
        val src = FakeSource("a", listOf(timed("a", "Standup", "2026-10-19T00:00:00Z"), allDay))
        val res = CalendarRepository(listOf(src)).instances(listOf(CalendarRef("a", "1")), Instant.DISTANT_PAST, Instant.DISTANT_FUTURE)
        assertEquals("Holiday", res.value.first().title)
    }

    @Test
    fun savesRouteToOwningSource() = runTest {
        val a = FakeSource("a"); val b = FakeSource("b")
        CalendarRepository(listOf(a, b)).save(EventDraft(CalendarRef("b", "1"), title = "t", time = EventTime.AllDay(LocalDate(2026, 1, 1), LocalDate(2026, 1, 2))))
        assertEquals(0, a.saved.size); assertEquals(1, b.saved.size)
    }
}
