package app.nextcal.android

import android.app.Application
import android.content.Context
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import app.nextcal.core.CalendarRepository
import app.nextcal.core.model.CalendarInfo
import app.nextcal.core.model.CalendarRef
import app.nextcal.core.model.EventDraft
import app.nextcal.core.model.EventInstance
import app.nextcal.core.view.groupByDay
import app.nextcal.core.view.monthGrid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.plus
import kotlinx.datetime.todayIn

data class CalendarUiState(
    val year: Int,
    val month: Int,
    val selected: LocalDate,
    val weeks: List<List<LocalDate>>,
    val calendars: List<CalendarInfo> = emptyList(),
    val hidden: Set<CalendarRef> = emptySet(),
    val eventsByDay: Map<LocalDate, List<EventInstance>> = emptyMap(),
    val loading: Boolean = false,
    val error: String? = null,
)

class CalendarViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = CalendarRepository(listOf(ProviderCalendarSource(app.contentResolver)))
    private val prefs = app.getSharedPreferences("nextcal", Context.MODE_PRIVATE)
    private val zone = TimeZone.currentSystemDefault()
    private val firstDayOfWeek = DayOfWeek.MONDAY

    private val today = Clock.System.todayIn(zone)
    private val _state = MutableStateFlow(
        CalendarUiState(today.year, today.monthNumber, today, monthGrid(today.year, today.monthNumber, firstDayOfWeek)),
    )
    val state: StateFlow<CalendarUiState> = _state

    private var calendars: List<CalendarInfo> = emptyList()

    /** Call once calendar permission has been granted, and again after returning to the app. */
    fun refresh() = viewModelScope.launch {
        _state.update { it.copy(loading = true, error = null) }
        val cals = repo.calendars()
        calendars = cals.value
        val hidden = prefs.getStringSet("hidden", emptySet()).orEmpty()
            .mapNotNull { it.split('|').takeIf { p -> p.size == 2 }?.let { p -> CalendarRef(p[0], p[1]) } }.toSet()
        _state.update { it.copy(calendars = cals.value, hidden = hidden) }
        loadEvents(cals.errors.firstOrNull()?.cause?.message)
    }

    fun showMonth(year: Int, month: Int) {
        _state.update { it.copy(year = year, month = month, weeks = monthGrid(year, month, firstDayOfWeek)) }
        viewModelScope.launch { loadEvents() }
    }

    fun shiftMonth(delta: Int) {
        val d = LocalDate(_state.value.year, _state.value.month, 1).plus(delta, DateTimeUnit.MONTH)
        showMonth(d.year, d.monthNumber)
    }

    fun goToToday() {
        showMonth(today.year, today.monthNumber)
        select(today)
    }

    fun select(date: LocalDate) {
        _state.update { it.copy(selected = date) }
        if (date.year != _state.value.year || date.monthNumber != _state.value.month) showMonth(date.year, date.monthNumber)
    }

    fun setVisible(ref: CalendarRef, visible: Boolean) {
        val hidden = _state.value.hidden.let { if (visible) it - ref else it + ref }
        prefs.edit().putStringSet("hidden", hidden.mapTo(HashSet()) { "${it.sourceId}|${it.calendarId}" }).apply()
        _state.update { it.copy(hidden = hidden) }
        viewModelScope.launch { loadEvents() }
    }

    fun save(draft: EventDraft) = viewModelScope.launch {
        runCatching { repo.save(draft) }
            .onFailure { e -> _state.update { it.copy(error = e.message ?: "Could not save event") } }
        loadEvents()
    }

    fun delete(event: EventInstance) = viewModelScope.launch {
        runCatching { repo.delete(event.calendar, event.eventId) }
            .onFailure { e -> _state.update { it.copy(error = e.message ?: "Could not delete event") } }
        loadEvents()
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    private suspend fun loadEvents(carriedError: String? = null) {
        val s = _state.value
        val gridStart = s.weeks.first().first()
        val gridEnd = s.weeks.last().last()
        val refs = calendars.map { it.ref }.filter { it !in s.hidden }
        val res = repo.instances(
            refs,
            gridStart.atStartOfDayIn(zone),
            gridEnd.plus(1, DateTimeUnit.DAY).atStartOfDayIn(zone),
        )
        val byDay = groupByDay(res.value, zone, gridStart..gridEnd)
        _state.update {
            // Ignore the result if the user navigated away while loading.
            if (it.weeks.first().first() != gridStart) it
            else it.copy(
                eventsByDay = byDay,
                loading = false,
                error = res.errors.firstOrNull()?.cause?.message ?: carriedError ?: it.error,
            )
        }
    }
}
