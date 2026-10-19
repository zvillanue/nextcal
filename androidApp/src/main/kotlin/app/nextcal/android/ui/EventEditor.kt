package app.nextcal.android.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.nextcal.core.model.CalendarInfo
import app.nextcal.core.model.EventDraft
import app.nextcal.core.model.EventInstance
import app.nextcal.core.model.EventTime
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime

sealed interface EditorTarget {
    data class New(val date: LocalDate) : EditorTarget
    data class Existing(val event: EventInstance) : EditorTarget
}

/**
 * Deliberately text-field based for now (ISO date / HH:mm) to keep the first milestone small;
 * swap in Material date/time pickers next.
 */
@Composable
fun EventEditor(
    target: EditorTarget,
    calendars: List<CalendarInfo>,
    onSave: (EventDraft) -> Unit,
    onDelete: (EventInstance) -> Unit,
    onDismiss: () -> Unit,
) {
    val zone = remember { TimeZone.currentSystemDefault() }
    val existing = (target as? EditorTarget.Existing)?.event
    val recurring = existing?.isRecurring == true

    var title by remember { mutableStateOf(existing?.title.orEmpty()) }
    var location by remember { mutableStateOf(existing?.location.orEmpty()) }
    var description by remember { mutableStateOf(existing?.description.orEmpty()) }
    var calendar by remember { mutableStateOf(calendars.firstOrNull { it.ref == existing?.calendar } ?: calendars.firstOrNull()) }

    val initialTime = existing?.time ?: when (target) {
        is EditorTarget.New -> EventTime.Timed(
            target.date.atTime(9, 0).toInstant(zone), target.date.atTime(10, 0).toInstant(zone), zone,
        )
        is EditorTarget.Existing -> error("unreachable")
    }
    var allDay by remember { mutableStateOf(initialTime is EventTime.AllDay) }
    val startLdt = when (initialTime) {
        is EventTime.Timed -> initialTime.start.toLocalDateTime(zone)
        is EventTime.AllDay -> initialTime.startDate.atTime(9, 0)
    }
    val endLdt = when (initialTime) {
        is EventTime.Timed -> initialTime.end.toLocalDateTime(zone)
        is EventTime.AllDay -> initialTime.endDateExclusive.plus(-1, DateTimeUnit.DAY).atTime(10, 0)
    }
    var startDate by remember { mutableStateOf(startLdt.date.toString()) }
    var startTime by remember { mutableStateOf("%02d:%02d".format(startLdt.hour, startLdt.minute)) }
    var endDate by remember { mutableStateOf(endLdt.date.toString()) }
    var endTime by remember { mutableStateOf("%02d:%02d".format(endLdt.hour, endLdt.minute)) }

    val parsed: EventTime? = runCatching {
        if (allDay) {
            val s = LocalDate.parse(startDate)
            val e = LocalDate.parse(endDate)
            require(e >= s)
            EventTime.AllDay(s, e.plus(1, DateTimeUnit.DAY))
        } else {
            val s = LocalDateTime(LocalDate.parse(startDate), LocalTime.parse(startTime)).toInstant(zone)
            val e = LocalDateTime(LocalDate.parse(endDate), LocalTime.parse(endTime)).toInstant(zone)
            require(e >= s)
            EventTime.Timed(s, e, zone)
        }
    }.getOrNull()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "New event" else "Edit event") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                if (existing == null && calendars.size > 1) {
                    // Tap to cycle through writable calendars; a proper dropdown comes with the polish pass.
                    TextButton(onClick = {
                        val i = calendars.indexOf(calendar)
                        calendar = calendars[(i + 1) % calendars.size]
                    }) { Text("Calendar: ${calendar?.displayName} (${calendar?.account?.name})") }
                }
                if (recurring) {
                    Text("Recurring event: times and repeat rule can't be edited yet.", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(allDay, { allDay = it }); Text("All day")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(startDate, { startDate = it }, label = { Text("Start date") }, singleLine = true, modifier = Modifier.weight(1f))
                        if (!allDay) OutlinedTextField(startTime, { startTime = it }, label = { Text("Time") }, singleLine = true, modifier = Modifier.weight(0.6f))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(endDate, { endDate = it }, label = { Text("End date") }, singleLine = true, modifier = Modifier.weight(1f))
                        if (!allDay) OutlinedTextField(endTime, { endTime = it }, label = { Text("Time") }, singleLine = true, modifier = Modifier.weight(0.6f))
                    }
                    if (parsed == null) Text("Enter dates as YYYY-MM-DD, times as HH:mm, end not before start.", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                }
                OutlinedTextField(location, { location = it }, label = { Text("Location") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(description, { description = it }, label = { Text("Description") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            val time = if (recurring) existing?.time else parsed
            TextButton(
                enabled = time != null && calendar != null,
                onClick = {
                    onSave(
                        EventDraft(
                            calendar = calendar!!.ref,
                            eventId = existing?.eventId,
                            title = title.trim(),
                            time = time!!,
                            location = location.trim().ifEmpty { null },
                            description = description.trim().ifEmpty { null },
                            rrule = existing?.rrule,
                        ),
                    )
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (existing != null) TextButton(onClick = { onDelete(existing) }) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
