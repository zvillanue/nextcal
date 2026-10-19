package app.nextcal.android

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.database.Cursor
import android.provider.CalendarContract.Calendars
import android.provider.CalendarContract.Events
import android.provider.CalendarContract.Instances
import app.nextcal.core.CalendarSource
import app.nextcal.core.model.AccountKind
import app.nextcal.core.model.CalendarAccount
import app.nextcal.core.model.CalendarInfo
import app.nextcal.core.model.CalendarRef
import app.nextcal.core.model.EventDraft
import app.nextcal.core.model.EventInstance
import app.nextcal.core.model.EventTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime

/**
 * Reads and writes the system Calendar Provider. DAVx5, Google and Exchange/Outlook accounts all
 * sync into it, so this one source covers all three. Writes are made as an ordinary app (not a sync
 * adapter), which marks rows dirty so each account's own sync adapter uploads them.
 */
class ProviderCalendarSource(private val resolver: ContentResolver) : CalendarSource {

    override val id = "android-provider"

    override suspend fun calendars(): List<CalendarInfo> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            Calendars._ID, Calendars.ACCOUNT_NAME, Calendars.ACCOUNT_TYPE,
            Calendars.CALENDAR_DISPLAY_NAME, Calendars.CALENDAR_COLOR, Calendars.CALENDAR_ACCESS_LEVEL,
        )
        val out = ArrayList<CalendarInfo>()
        resolver.query(Calendars.CONTENT_URI, projection, "${Calendars.SYNC_EVENTS} = 1", null, null)?.use { c ->
            while (c.moveToNext()) {
                out += CalendarInfo(
                    ref = CalendarRef(id, c.getLong(0).toString()),
                    account = CalendarAccount(c.getString(1).orEmpty(), kindOf(c.getString(2))),
                    displayName = c.getString(3).orEmpty(),
                    color = c.getInt(4),
                    writable = c.getInt(5) >= Calendars.CAL_ACCESS_CONTRIBUTOR,
                )
            }
        }
        out
    }

    override suspend fun instances(
        calendars: Set<CalendarRef>,
        from: Instant,
        to: Instant,
    ): List<EventInstance> = withContext(Dispatchers.IO) {
        if (calendars.isEmpty()) return@withContext emptyList()
        val wanted = calendars.mapTo(HashSet()) { it.calendarId }
        // All-day instances are stored at UTC midnight, so widen the window by a day on each side
        // and let the caller's day bucketing decide what is visible.
        val dayMs = 24 * 60 * 60 * 1000L
        val uri = Instances.CONTENT_URI.buildUpon().also {
            ContentUris.appendId(it, from.toEpochMilliseconds() - dayMs)
            ContentUris.appendId(it, to.toEpochMilliseconds() + dayMs)
        }.build()
        val projection = arrayOf(
            Instances.EVENT_ID, Instances.CALENDAR_ID, Instances.TITLE, Instances.BEGIN, Instances.END,
            Instances.ALL_DAY, Instances.EVENT_TIMEZONE, Instances.EVENT_LOCATION, Instances.DESCRIPTION,
            Instances.RRULE,
        )
        val out = ArrayList<EventInstance>()
        resolver.query(uri, projection, null, null, "${Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                val calId = c.getLong(1).toString()
                if (calId !in wanted) continue
                out += c.toInstance(calId)
            }
        }
        out
    }

    private fun Cursor.toInstance(calendarId: String): EventInstance {
        val begin = getLong(3)
        val end = getLong(4)
        val allDay = getInt(5) == 1
        val time = if (allDay) {
            // All-day times are UTC midnights; End is exclusive.
            val start = Instant.fromEpochMilliseconds(begin).toLocalDateTime(TimeZone.UTC).date
            val endEx = Instant.fromEpochMilliseconds(end).toLocalDateTime(TimeZone.UTC).date
            EventTime.AllDay(start, if (endEx > start) endEx else start.plus(1, DateTimeUnit.DAY))
        } else {
            val zone = getString(6)?.let { runCatching { TimeZone.of(it) }.getOrNull() } ?: TimeZone.currentSystemDefault()
            EventTime.Timed(Instant.fromEpochMilliseconds(begin), Instant.fromEpochMilliseconds(end), zone)
        }
        return EventInstance(
            calendar = CalendarRef(id, calendarId),
            eventId = getLong(0).toString(),
            title = getString(2).orEmpty(),
            time = time,
            location = getString(7)?.ifBlank { null },
            description = getString(8)?.ifBlank { null },
            rrule = getString(9)?.ifBlank { null },
        )
    }

    override suspend fun save(draft: EventDraft): String = withContext(Dispatchers.IO) {
        val values = ContentValues().apply {
            put(Events.TITLE, draft.title)
            put(Events.EVENT_LOCATION, draft.location.orEmpty())
            put(Events.DESCRIPTION, draft.description.orEmpty())
        }
        // Recurring series keep their original DTSTART/DURATION; changing the time of one instance
        // would corrupt the series, so only descriptive fields are updated for them.
        if (draft.rrule == null) {
            when (val t = draft.time) {
                is EventTime.AllDay -> {
                    values.put(Events.ALL_DAY, 1)
                    values.put(Events.EVENT_TIMEZONE, "UTC")
                    values.put(Events.DTSTART, t.startDate.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds())
                    values.put(Events.DTEND, t.endDateExclusive.atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds())
                }
                is EventTime.Timed -> {
                    values.put(Events.ALL_DAY, 0)
                    values.put(Events.EVENT_TIMEZONE, t.zone.id)
                    values.put(Events.DTSTART, t.start.toEpochMilliseconds())
                    values.put(Events.DTEND, t.end.toEpochMilliseconds())
                }
            }
        }
        val existing = draft.eventId
        if (existing == null) {
            values.put(Events.CALENDAR_ID, draft.calendar.calendarId.toLong())
            val uri = resolver.insert(Events.CONTENT_URI, values) ?: error("Calendar provider rejected the insert")
            ContentUris.parseId(uri).toString()
        } else {
            val rows = resolver.update(ContentUris.withAppendedId(Events.CONTENT_URI, existing.toLong()), values, null, null)
            check(rows == 1) { "Event $existing not found" }
            existing
        }
    }

    override suspend fun delete(calendar: CalendarRef, eventId: String) {
        withContext(Dispatchers.IO) {
            resolver.delete(ContentUris.withAppendedId(Events.CONTENT_URI, eventId.toLong()), null, null)
        }
    }

    private fun kindOf(accountType: String?) = when (accountType) {
        "com.google" -> AccountKind.GOOGLE
        "bitfire.at.davdroid" -> AccountKind.CALDAV
        "com.microsoft.office.outlook.USER_ACCOUNT", "com.android.exchange", "com.google.android.gm.exchange" -> AccountKind.EXCHANGE
        "LOCAL" -> AccountKind.LOCAL
        else -> AccountKind.OTHER
    }
}
