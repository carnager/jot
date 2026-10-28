package io.github.carnager.jot.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** Repeat rules (SPEC §4). Pure functions so the JVM tests can check them against spec/repeat.json. */
object Repeats {
    fun dayOfWeek(code: String): DayOfWeek = DayOfWeek.of(WEEKDAY_CODES.indexOf(code) + 1)
    fun code(day: DayOfWeek): String = WEEKDAY_CODES[day.value - 1]

    /** The first occurrence strictly after [date]. */
    fun nextAfter(date: LocalDate, r: Repeat): LocalDate {
        val n = r.interval.coerceAtLeast(1).toLong()
        return when (r.freq) {
            "day" -> date.plusDays(n)
            "week" -> {
                val days = r.byDay.map { dayOfWeek(it).value }.distinct().sorted()
                if (days.isEmpty()) return date.plusWeeks(n)
                val later = days.firstOrNull { it > date.dayOfWeek.value }
                if (later != null) date.plusDays((later - date.dayOfWeek.value).toLong())
                else date.minusDays((date.dayOfWeek.value - 1).toLong()).plusWeeks(n).plusDays((days.first() - 1).toLong())
            }
            "month" -> {
                val target = date.withDayOfMonth(1).plusMonths(n)
                val anchor = if (r.monthDay in 1..31) r.monthDay else date.dayOfMonth
                target.withDayOfMonth(minOf(anchor, target.lengthOfMonth()))
            }
            "year" -> date.plusYears(n)
            else -> date.plusDays(n)
        }
    }

    /** Whether [date] itself is an occurrence of [r] (used to find the first due of a new repeat). */
    fun matches(date: LocalDate, r: Repeat): Boolean = when (r.freq) {
        "week" -> r.byDay.isEmpty() || code(date.dayOfWeek) in r.byDay
        "month" -> r.monthDay !in 1..31 || date.dayOfMonth == minOf(r.monthDay, date.lengthOfMonth())
        else -> true
    }

    /** First occurrence that isn't in the past: all-day → date ≥ today, timed → datetime > now. */
    fun firstFrom(now: LocalDateTime, r: Repeat, time: LocalTime?): String {
        var d = now.toLocalDate()
        fun ok(day: LocalDate) = matches(day, r) && (time == null || LocalDateTime.of(day, time) > now)
        var guard = 0
        while (!ok(d) && guard++ < 800) d = d.plusDays(1)
        return Due.of(d, time)
    }

    sealed interface Outcome
    data class Next(val due: String) : Outcome
    data object Finished : Outcome

    /** What completing a repeating item with [due] at [now] does. */
    fun complete(due: String, r: Repeat, now: LocalDateTime): Outcome {
        val dueDate = Due.date(due) ?: now.toLocalDate()
        val time = Due.time(due)
        val today = now.toLocalDate()
        var next = nextAfter(if (r.fromCompletion) today else dueDate, r)
        if (!r.fromCompletion) {
            fun past(d: LocalDate) = if (time != null) LocalDateTime.of(d, time) <= now else d <= today
            var guard = 0
            while (past(next) && guard++ < 10000) next = nextAfter(next, r)
        }
        val until = Due.date(r.until)
        if (until != null && next > until) return Finished
        return Next(Due.of(next, time))
    }

    /** Applies completion to [item]: advances repeats, else marks done. */
    fun completeItem(item: Item, now: LocalDateTime, nowMs: Long): Item {
        val r = item.repeat
        if (r != null && item.due.isNotEmpty()) {
            when (val o = complete(item.due, r, now)) {
                is Next -> return item.copy(
                    due = o.due, snoozedUntil = "", doneAt = nowMs,
                    checks = item.checks.map { it.copy(done = false) },
                )
                Finished -> {}
            }
        }
        return item.copy(done = true, doneAt = nowMs, snoozedUntil = "")
    }

    fun describe(r: Repeat): String {
        val n = r.interval
        val base = when (r.freq) {
            "day" -> if (n == 1) "Daily" else "Every $n days"
            "week" -> {
                val days = if (r.byDay.isEmpty()) "" else when (r.byDay.sorted()) {
                    listOf("fr", "mo", "th", "tu", "we") -> " on weekdays"
                    else -> " on " + r.byDay.sortedBy { WEEKDAY_CODES.indexOf(it) }.joinToString(", ") {
                        dayOfWeek(it).getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
                    }
                }
                (if (n == 1) "Weekly" else "Every $n weeks") + days
            }
            "month" -> (if (n == 1) "Monthly" else "Every $n months") + if (r.monthDay > 0) " on the ${ordinal(r.monthDay)}" else ""
            "year" -> if (n == 1) "Yearly" else "Every $n years"
            else -> "Repeats"
        }
        return base + (if (r.fromCompletion) " after done" else "") + (if (r.until.isNotEmpty()) " until ${r.until}" else "")
    }

    private fun ordinal(n: Int) = n.toString() + when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }
}
