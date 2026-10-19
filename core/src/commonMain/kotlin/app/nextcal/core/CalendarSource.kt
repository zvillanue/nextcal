package app.nextcal.core

import app.nextcal.core.model.CalendarInfo
import app.nextcal.core.model.CalendarRef
import app.nextcal.core.model.EventDraft
import app.nextcal.core.model.EventInstance
import kotlinx.datetime.Instant

/**
 * A backend that can list calendars and read/write their events.
 *
 * Implementations: the Android Calendar Provider (DAVx5, Google and Exchange accounts all sync
 * into it), and later direct CalDAV / Google / Microsoft Graph clients for platforms without one.
 */
interface CalendarSource {
    val id: String

    suspend fun calendars(): List<CalendarInfo>

    /** Instances overlapping [from] (inclusive) .. [to] (exclusive), recurrences expanded. */
    suspend fun instances(calendars: Set<CalendarRef>, from: Instant, to: Instant): List<EventInstance>

    /** Creates or updates the event; returns its event id. */
    suspend fun save(draft: EventDraft): String

    suspend fun delete(calendar: CalendarRef, eventId: String)
}
