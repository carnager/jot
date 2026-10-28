package io.github.carnager.jot.ui

import io.github.carnager.jot.model.Due
import io.github.carnager.jot.model.Item
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Short, friendly date and time text. */
object Fmt {
    private val time get() = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
    private val weekday get() = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())
    private val dayMonth get() = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
    private val dayMonthYear get() = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.getDefault())
    private val longDay get() = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())

    fun day(d: LocalDate, today: LocalDate = LocalDate.now()): String {
        val diff = ChronoUnit.DAYS.between(today, d)
        return when {
            diff == 0L -> "Today"
            diff == 1L -> "Tomorrow"
            diff == -1L -> "Yesterday"
            diff in 2..6 -> d.format(weekday)
            d.year == today.year -> d.format(dayMonth)
            else -> d.format(dayMonthYear)
        }
    }

    /** "Tomorrow · Thu 29", "Friday · 30 Oct", "Mon 9 Nov". */
    fun dayHeader(d: LocalDate, today: LocalDate = LocalDate.now()): String {
        val diff = ChronoUnit.DAYS.between(today, d)
        val short = d.format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault()))
        return when {
            diff == 0L -> "Today · $short"
            diff == 1L -> "Tomorrow · $short"
            diff in 2..6 -> d.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault())) + " · " + d.format(DateTimeFormatter.ofPattern("d MMM", Locale.getDefault()))
            d.year == today.year -> d.format(dayMonth)
            else -> d.format(dayMonthYear)
        }
    }

    fun longDate(d: LocalDate): String = d.format(longDay)

    fun time(t: java.time.LocalTime): String = t.format(time)

    /** "Tomorrow 09:30", "Fri", "12 Nov". */
    fun dueShort(due: String, today: LocalDate = LocalDate.now()): String {
        val d = Due.date(due) ?: return ""
        val t = Due.time(due)
        return day(d, today) + (if (t != null) " " + time(t) else "")
    }

    fun isOverdue(item: Item, now: LocalDateTime = LocalDateTime.now()): Boolean {
        if (item.done) return false
        val d = item.dueDate ?: return false
        val t = item.dueTime
        return if (t != null) LocalDateTime.of(d, t) < now else d < now.toLocalDate()
    }

    fun snoozeLabel(minutes: Int): String = when {
        minutes % 60 == 0 -> "Snooze ${minutes / 60} h"
        else -> "Snooze $minutes min"
    }

    fun edited(ms: Long): String {
        if (ms <= 0) return ""
        val dt = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(ms), java.time.ZoneId.systemDefault())
        val today = LocalDate.now()
        return "Edited " + if (dt.toLocalDate() == today) time(dt.toLocalTime()) else day(dt.toLocalDate(), today)
    }
}
