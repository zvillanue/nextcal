package app.nextcal.core.model

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlinx.datetime.DatePeriod

/** Where an account's events ultimately come from. Used for icons/grouping only. */
enum class AccountKind { GOOGLE, CALDAV, EXCHANGE, LOCAL, OTHER }

data class CalendarAccount(
    val name: String,
    val kind: AccountKind,
)

/** Stable identity of a calendar across sources. */
data class CalendarRef(val sourceId: String, val calendarId: String)

data class CalendarInfo(
    val ref: CalendarRef,
    val account: CalendarAccount,
    val displayName: String,
    /** ARGB. */
    val color: Int,
    val writable: Boolean,
)

sealed interface EventTime {
    /** A point-in-time event. [end] is exclusive. */
    data class Timed(val start: Instant, val end: Instant, val zone: TimeZone) : EventTime

    /** A floating all-day event covering [startDate] until [endDateExclusive]. */
    data class AllDay(val startDate: LocalDate, val endDateExclusive: LocalDate) : EventTime {
        init {
            require(endDateExclusive > startDate) { "All-day event must span at least one day" }
        }

        companion object {
            fun singleDay(date: LocalDate) = AllDay(date, date.plus(DatePeriod(days = 1)))
        }
    }
}

/** Last day (inclusive) on which the event is visible in [zone]. */
fun EventTime.lastDate(zone: TimeZone): LocalDate = when (this) {
    is EventTime.AllDay -> endDateExclusive.minus(DatePeriod(days = 1))
    is EventTime.Timed -> {
        val startDay = start.toLocalDateTime(zone).date
        val endLocal = end.toLocalDateTime(zone)
        // An event ending exactly at midnight does not occupy the next day.
        val endDay = if (endLocal.time.toNanosecondOfDay() == 0L && end > start) {
            endLocal.date.minus(DatePeriod(days = 1))
        } else endLocal.date
        maxOf(startDay, endDay)
    }
}

fun EventTime.firstDate(zone: TimeZone): LocalDate = when (this) {
    is EventTime.AllDay -> startDate
    is EventTime.Timed -> start.toLocalDateTime(zone).date
}

/** One occurrence of an event (recurring events yield one instance per occurrence). */
data class EventInstance(
    val calendar: CalendarRef,
    val eventId: String,
    val title: String,
    val time: EventTime,
    val location: String? = null,
    val description: String? = null,
    /** Raw RRULE of the series, if recurring. */
    val rrule: String? = null,
) {
    val isRecurring: Boolean get() = rrule != null
}

/** Input for creating or updating an event. [eventId] is null for new events. */
data class EventDraft(
    val calendar: CalendarRef,
    val eventId: String? = null,
    val title: String,
    val time: EventTime,
    val location: String? = null,
    val description: String? = null,
    /** Preserved untouched on update; recurrence editing is not supported yet. */
    val rrule: String? = null,
)

