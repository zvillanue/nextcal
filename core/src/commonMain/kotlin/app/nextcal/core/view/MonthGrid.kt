package app.nextcal.core.view

import app.nextcal.core.model.EventInstance
import app.nextcal.core.model.firstDate
import app.nextcal.core.model.lastDate
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.daysUntil
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus

/** Weeks (rows of 7 dates) needed to display [year]-[month], padded with adjacent-month days. */
fun monthGrid(year: Int, month: Int, firstDayOfWeek: DayOfWeek): List<List<LocalDate>> {
    val first = LocalDate(year, month, 1)
    val lead = (first.dayOfWeek.isoDayNumber - firstDayOfWeek.isoDayNumber + 7) % 7
    val gridStart = first.plus(-lead, DateTimeUnit.DAY)
    val daysInMonth = first.daysUntil(first.plus(DatePeriod(months = 1)))
    val rows = (lead + daysInMonth + 6) / 7
    return List(rows) { row -> List(7) { col -> gridStart.plus(row * 7 + col, DateTimeUnit.DAY) } }
}

/**
 * Buckets instances by every day they occupy in [zone], restricted to [range]. A multi-day event
 * appears under each of its days.
 */
fun groupByDay(
    instances: List<EventInstance>,
    zone: TimeZone,
    range: ClosedRange<LocalDate>,
): Map<LocalDate, List<EventInstance>> {
    val out = HashMap<LocalDate, MutableList<EventInstance>>()
    for (inst in instances) {
        val first = maxOf(inst.time.firstDate(zone), range.start)
        val last = minOf(inst.time.lastDate(zone), range.endInclusive)
        var d = first
        while (d <= last) {
            out.getOrPut(d) { ArrayList() }.add(inst)
            d = d.plus(1, DateTimeUnit.DAY)
        }
    }
    return out
}
