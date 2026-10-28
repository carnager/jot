package io.github.carnager.jot.model

import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit

/** Alerts and fire times (SPEC §5), the twin of the web's `alerts.js`. Local wall-clock time. */
object Alerts {
    val PRESETS_TIMED = listOf(0, 5, 15, 30, 60, 120, 1440, 2880, 10080)
    val PRESETS_ALLDAY = listOf(0, 1440, 2880, 10080)

    /** Missing or empty means [0]. Sorted, unique, non-negative. */
    fun norm(a: List<Int>): List<Int> = a.map { maxOf(0, it) }.distinct().sorted().ifEmpty { listOf(0) }

    fun isDefault(a: List<Int>) = norm(a) == listOf(0)

    fun label(m: Int, allDay: Boolean = false, short: Boolean = false): String {
        if (m == 0) return if (allDay) "On the day" else "On time"
        val suffix = if (short) "" else " before"
        return when {
            m % 10080 == 0 -> (m / 10080).let { "$it week${if (it > 1) "s" else ""}$suffix" }
            m % 1440 == 0 -> (m / 1440).let { "$it day${if (it > 1) "s" else ""}$suffix" }
            m % 60 == 0 -> "${m / 60} h$suffix"
            else -> "$m min$suffix"
        }
    }

    /** "On time · 30 min before" */
    fun describe(a: List<Int>, allDay: Boolean = false) = norm(a).joinToString(" · ") { label(it, allDay) }

    data class Fire(val at: LocalDateTime, val minutesBefore: Int) {
        /** Identifies this fire time for "shown once" bookkeeping and alarm request codes. */
        val key: String get() = at.toString()
    }

    /** Every fire time of [item]. Nothing if reminders are off, or the item is done, archived, trashed or has no due. */
    fun fireTimes(item: Item, allDay: LocalTime): List<Fire> {
        if (!item.remind || item.done || item.archived || item.trashed || item.deleted || item.due.isEmpty()) return emptyList()
        Due.dateTime(item.snoozedUntil)?.let { return listOf(Fire(it, 0)) }
        val d = item.dueDate ?: return emptyList()
        val base = LocalDateTime.of(d, item.dueTime ?: allDay)
        return norm(item.alerts).map { Fire(base.minusMinutes(it.toLong()), it) }
    }

    /** Text for an early alert's notification: "in 30 min", "in 2 h", "tomorrow 09:00", "Fri 09:00". */
    fun leadText(due: LocalDateTime, now: LocalDateTime): String {
        val min = Math.round(ChronoUnit.SECONDS.between(now, due) / 60.0).toInt()
        if (min <= 0) return "now"
        if (min < 60) return "in $min min"
        if (min < 6 * 60) return "in ${Math.round(min / 60.0)} h"
        val hm = "%02d:%02d".format(due.hour, due.minute)
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), due.toLocalDate())
        return when {
            days == 0L -> "today $hm"
            days == 1L -> "tomorrow $hm"
            days < 7 -> listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")[due.dayOfWeek.value - 1] + " $hm"
            else -> "${due.dayOfMonth}.${due.monthValue}. $hm"
        }
    }
}
