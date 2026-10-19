package app.nextcal.android.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.nextcal.android.CalendarViewModel
import app.nextcal.core.model.CalendarInfo
import app.nextcal.core.model.EventInstance
import app.nextcal.core.model.EventTime
import kotlinx.datetime.LocalDate
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(vm: CalendarViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var showCalendars by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<EditorTarget?>(null) }
    val writable = state.calendars.filter { it.writable }

    LaunchedEffect(state.error) {
        state.error?.let { snackbar.showSnackbar(it); vm.dismissError() }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("${Month(state.month).name.lowercase().replaceFirstChar { it.uppercase() }} ${state.year}") },
                actions = {
                    IconButton(onClick = { vm.shiftMonth(-1) }) { Icon(Icons.Default.ChevronLeft, "Previous month") }
                    TextButton(onClick = vm::goToToday) { Text("Today") }
                    IconButton(onClick = { vm.shiftMonth(1) }) { Icon(Icons.Default.ChevronRight, "Next month") }
                    TextButton(onClick = { showCalendars = true }) { Text("Calendars") }
                },
            )
        },
        floatingActionButton = {
            if (writable.isNotEmpty()) {
                FloatingActionButton(onClick = { editing = EditorTarget.New(state.selected) }) {
                    Icon(Icons.Default.Add, "New event")
                }
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            MonthGrid(
                weeks = state.weeks,
                month = state.month,
                selected = state.selected,
                eventsByDay = state.eventsByDay,
                colorOf = { inst -> state.calendars.firstOrNull { it.ref == inst.calendar }?.color ?: 0xFF888888.toInt() },
                onSelect = vm::select,
            )
            DayAgenda(
                date = state.selected,
                events = state.eventsByDay[state.selected].orEmpty(),
                calendars = state.calendars,
                onClick = { editing = EditorTarget.Existing(it) },
            )
        }
    }

    if (showCalendars) {
        CalendarsDialog(
            calendars = state.calendars,
            hidden = state.hidden,
            onToggle = vm::setVisible,
            onDismiss = { showCalendars = false },
        )
    }
    editing?.let { target ->
        EventEditor(
            target = target,
            calendars = writable,
            onSave = { vm.save(it); editing = null },
            onDelete = { vm.delete(it); editing = null },
            onDismiss = { editing = null },
        )
    }
}

@Composable
private fun MonthGrid(
    weeks: List<List<LocalDate>>,
    month: Int,
    selected: LocalDate,
    eventsByDay: Map<LocalDate, List<EventInstance>>,
    colorOf: (EventInstance) -> Int,
    onSelect: (LocalDate) -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth()) {
            weeks.first().forEach { d ->
                Text(
                    d.dayOfWeek.name.take(3),
                    Modifier.weight(1f),
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
        }
        weeks.forEach { week ->
            Row(Modifier.fillMaxWidth().height(56.dp)) {
                week.forEach { day ->
                    val inMonth = day.monthNumber == month
                    Column(
                        Modifier.weight(1f).fillMaxSize()
                            .background(if (day == selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                            .clickable { onSelect(day) }
                            .padding(2.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Text(
                            day.dayOfMonth.toString(),
                            style = MaterialTheme.typography.bodyMedium,
                            color = if (inMonth) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                            eventsByDay[day].orEmpty().take(4).forEach { e ->
                                Box(Modifier.size(6.dp).clip(CircleShape).background(Color(colorOf(e))))
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayAgenda(
    date: LocalDate,
    events: List<EventInstance>,
    calendars: List<CalendarInfo>,
    onClick: (EventInstance) -> Unit,
) {
    if (events.isEmpty()) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { Text("No events on $date") }
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp)) {
        items(events, key = { it.calendar.calendarId + ":" + it.eventId + ":" + it.time.hashCode() }) { e ->
            val cal = calendars.firstOrNull { it.ref == e.calendar }
            Row(Modifier.fillMaxWidth().clickable { onClick(e) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(Color(cal?.color ?: 0xFF888888.toInt())))
                Column(Modifier.padding(start = 12.dp)) {
                    Text(e.title.ifBlank { "(No title)" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(timeLabel(e.time), cal?.account?.name).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

private fun timeLabel(t: EventTime): String = when (t) {
    is EventTime.AllDay -> "All day"
    is EventTime.Timed -> {
        val s = t.start.toLocalDateTime(t.zone).time
        val e = t.end.toLocalDateTime(t.zone).time
        "%02d:%02d–%02d:%02d".format(s.hour, s.minute, e.hour, e.minute)
    }
}

@Composable
private fun CalendarsDialog(
    calendars: List<CalendarInfo>,
    hidden: Set<app.nextcal.core.model.CalendarRef>,
    onToggle: (app.nextcal.core.model.CalendarRef, Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = onDismiss) { Text("Done") } },
        title = { Text("Calendars") },
        text = {
            LazyColumn {
                calendars.groupBy { it.account }.forEach { (account, cals) ->
                    item { Text("${account.name} (${account.kind.name.lowercase()})", style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
                    items(cals, key = { it.ref.calendarId }) { c ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = c.ref !in hidden, onCheckedChange = { onToggle(c.ref, it) })
                            Box(Modifier.size(10.dp).clip(CircleShape).background(Color(c.color)))
                            Text(c.displayName, Modifier.padding(start = 8.dp))
                        }
                    }
                }
            }
        },
    )
}
