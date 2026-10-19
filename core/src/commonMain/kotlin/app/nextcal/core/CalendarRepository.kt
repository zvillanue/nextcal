package app.nextcal.core

import app.nextcal.core.model.CalendarInfo
import app.nextcal.core.model.CalendarRef
import app.nextcal.core.model.EventDraft
import app.nextcal.core.model.EventInstance
import app.nextcal.core.model.EventTime
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn

data class SourceError(val sourceId: String, val cause: Throwable)

data class Loaded<T>(val value: T, val errors: List<SourceError> = emptyList())

/** Merges any number of [CalendarSource]s; one failing source never hides the others. */
class CalendarRepository(private val sources: List<CalendarSource>) {

    private val byId = sources.associateBy { it.id }

    init {
        require(byId.size == sources.size) { "Duplicate source ids" }
    }

    suspend fun calendars(): Loaded<List<CalendarInfo>> = gather { it.calendars() }.let { (items, errors) ->
        Loaded(items.sortedWith(compareBy({ it.account.name }, { it.displayName.lowercase() })), errors)
    }

    suspend fun instances(
        calendars: Collection<CalendarRef>,
        from: Instant,
        to: Instant,
    ): Loaded<List<EventInstance>> {
        val bySource = calendars.groupBy({ it.sourceId }, { it }).mapValues { it.value.toSet() }
        val (items, errors) = gather(bySource.keys) { src -> src.instances(bySource.getValue(src.id), from, to) }
        return Loaded(items.sortedWith(INSTANCE_ORDER), errors)
    }

    suspend fun save(draft: EventDraft): String = source(draft.calendar).save(draft)

    suspend fun delete(calendar: CalendarRef, eventId: String) = source(calendar).delete(calendar, eventId)

    private fun source(ref: CalendarRef) =
        byId[ref.sourceId] ?: error("Unknown calendar source: ${ref.sourceId}")

    private suspend fun <T> gather(
        only: Set<String> = byId.keys,
        block: suspend (CalendarSource) -> List<T>,
    ): Pair<List<T>, List<SourceError>> = coroutineScope {
        val results = sources.filter { it.id in only }.map { src ->
            async { src.id to runCatching { block(src) } }
        }.awaitAll()
        val items = results.flatMap { it.second.getOrNull().orEmpty() }
        val errors = results.mapNotNull { (id, r) -> r.exceptionOrNull()?.let { SourceError(id, it) } }
        items to errors
    }

    companion object {
        /** All-day first within a day, then by start time, then title. */
        val INSTANCE_ORDER: Comparator<EventInstance> = compareBy<EventInstance>(
            { sortKey(it.time) },
            { it.time !is EventTime.AllDay },
            { it.title.lowercase() },
        )

        private fun sortKey(t: EventTime): Instant = when (t) {
            is EventTime.Timed -> t.start
            is EventTime.AllDay -> t.startDate.atStartOfDayIn(TimeZone.UTC)
        }
    }
}
