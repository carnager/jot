package io.github.carnager.jot.model

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.temporal.ChronoUnit
import java.time.temporal.TemporalAdjusters

/**
 * The capture line's natural-language parser (SPEC §3), English and German at once.
 * Pure Kotlin; the JVM tests run it against spec/quickadd.json.
 */
object QuickAdd {
    enum class Kind { LABEL, DATE, TIME, REPEAT, PIN }

    /** A recognised piece of the input. [text] is the exact source text, [start]/[end] its offsets. */
    data class Token(val start: Int, val end: Int, val text: String, val kind: Kind)

    data class Result(
        val title: String,
        val kind: String = "note",
        val checks: List<Check> = emptyList(),
        val labels: List<String> = emptyList(),
        val due: String = "",
        val repeat: Repeat? = null,
        val pinned: Boolean = false,
        val tokens: List<Token> = emptyList(),
    ) {
        /** Applies the parse to a fresh item. */
        fun toItem(now: Long = System.currentTimeMillis()): Item = Item.new(now).copy(
            kind = kind, title = title, checks = checks, labels = labels, due = due, repeat = repeat, pinned = pinned,
        )
    }

    private class Word(val start: Int, val end: Int, val raw: String) {
        val norm = raw.lowercase().trimEnd(',', ';')
        val bare = raw.lowercase().trimEnd(',', ';', '.', '!', '?', ':')
    }

    private class State(val now: LocalDateTime) {
        var date: LocalDate? = null
        var time: LocalTime? = null
        var impliedTime: LocalTime? = null
        var exact: LocalDateTime? = null
        var repeat: Repeat? = null
    }

    private class Match(val len: Int, val kind: Kind, val apply: (State) -> Unit)

    private val CONNECTORS = setOf("on", "am", "at", "um", "by", "bis")
    private val EVERY = setOf("every", "jeden", "jede", "jedes", "jeder", "alle")

    private val WEEKDAYS: Map<String, DayOfWeek> = buildMap {
        fun add(d: DayOfWeek, vararg names: String) = names.forEach { put(it, d) }
        add(DayOfWeek.MONDAY, "mon", "monday", "montag", "mondays", "montags")
        add(DayOfWeek.TUESDAY, "tue", "tues", "tuesday", "dienstag", "tuesdays", "dienstags")
        add(DayOfWeek.WEDNESDAY, "wed", "wednesday", "mittwoch", "wednesdays", "mittwochs")
        add(DayOfWeek.THURSDAY, "thu", "thur", "thurs", "thursday", "donnerstag", "thursdays", "donnerstags")
        add(DayOfWeek.FRIDAY, "fri", "friday", "freitag", "fridays", "freitags")
        add(DayOfWeek.SATURDAY, "sat", "saturday", "samstag", "sonnabend", "saturdays", "samstags")
        add(DayOfWeek.SUNDAY, "sun", "sunday", "sonntag", "sundays", "sonntags")
    }
    private val SHORT_WEEKDAYS = mapOf(
        "mo" to DayOfWeek.MONDAY, "di" to DayOfWeek.TUESDAY, "mi" to DayOfWeek.WEDNESDAY, "do" to DayOfWeek.THURSDAY,
        "fr" to DayOfWeek.FRIDAY, "sa" to DayOfWeek.SATURDAY, "so" to DayOfWeek.SUNDAY,
    )
    private val MONTHS: Map<String, Int> = buildMap {
        fun add(m: Int, vararg names: String) = names.forEach { put(it, m) }
        add(1, "jan", "january", "januar", "jänner")
        add(2, "feb", "february", "februar")
        add(3, "mar", "march", "märz", "maerz", "mär")
        add(4, "apr", "april")
        add(5, "may", "mai")
        add(6, "jun", "june", "juni")
        add(7, "jul", "july", "juli")
        add(8, "aug", "august")
        add(9, "sep", "sept", "september")
        add(10, "oct", "october", "okt", "oktober")
        add(11, "nov", "november")
        add(12, "dec", "december", "dez", "dezember")
    }

    private enum class TimeUnit { DAY, WEEK, MONTH, YEAR, HOUR, MINUTE }

    private val UNITS: Map<String, TimeUnit> = buildMap {
        fun add(u: TimeUnit, vararg names: String) = names.forEach { put(it, u) }
        add(TimeUnit.DAY, "d", "day", "days", "tag", "tage", "tagen")
        add(TimeUnit.WEEK, "w", "week", "weeks", "woche", "wochen")
        add(TimeUnit.MONTH, "month", "months", "monat", "monate", "monaten")
        add(TimeUnit.YEAR, "year", "years", "jahr", "jahre", "jahren")
        add(TimeUnit.HOUR, "h", "hour", "hours", "std", "stunde", "stunden")
        add(TimeUnit.MINUTE, "min", "mins", "minute", "minutes", "minuten")
    }

    private val ISO = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")
    private val DOTTED = Regex("""^(\d{1,2})\.(\d{1,2})\.?(\d{4}|\d{2})?$""")
    private val SLASH = Regex("""^(\d{1,2})/(\d{1,2})$""")
    private val DAY_NUM = Regex("""^(\d{1,2})(?:\.|st|nd|rd|th)?,?$""")
    private val ORDINAL = Regex("""^(\d{1,2})(?:\.|st|nd|rd|th)$""")
    private val AMPM = Regex("""^(\d{1,2})(?::(\d{2}))?(am|pm)$""")
    private val HHMM = Regex("""^(\d{1,2}):(\d{2})$""")
    private val HOUR_H = Regex("""^(\d{1,2})h$""")
    private val NUM = Regex("""^(\d{1,2})$""")
    private val INT = Regex("""^\d{1,3}$""")
    private val LABEL = Regex("""^#([\p{L}\p{N}_-]+)$""")
    private val COMMA_LIST = Regex("""^(.+?):\s+(.+,.+)$""")

    fun parse(input: String, now: LocalDateTime, disabled: Set<String> = emptySet()): Result {
        val lines = input.split('\n')
        val line = lines[0]
        val checks = mutableListOf<Check>()
        for (l in lines.drop(1)) {
            var t = l.trim()
            if (t.isEmpty()) continue
            var done = false
            when {
                t.startsWith("[x]", ignoreCase = true) -> { done = true; t = t.substring(3) }
                t.startsWith("[ ]") -> t = t.substring(3)
                t.startsWith("- ") || t.startsWith("* ") -> t = t.substring(2)
            }
            t = t.trim()
            if (t.isNotEmpty()) checks += Check(Ids.short(), t, done)
        }
        var kind = if (checks.isNotEmpty()) "list" else "note"

        val words = Regex("""\S+""").findAll(line).map { Word(it.range.first, it.range.last + 1, it.value) }.toList()
        val consumed = BooleanArray(words.size)
        val tokens = mutableListOf<Token>()
        val labels = linkedSetOf<String>()
        var pinned = false

        fun take(from: Int, to: Int, kind: Kind): Boolean {
            val text = line.substring(words[from].start, words[to].end)
            if (text.lowercase() in disabled) return false
            for (k in from..to) consumed[k] = true
            tokens += Token(words[from].start, words[to].end, text, kind)
            return true
        }

        words.forEachIndexed { i, w ->
            LABEL.find(w.raw.trimEnd(',', '.', ';'))?.let { m ->
                if (take(i, i, Kind.LABEL)) labels += m.groupValues[1].lowercase()
            }
        }
        if (words.size > 1 && words.last().raw == "!") {
            if (take(words.size - 1, words.size - 1, Kind.PIN)) pinned = true
        }

        val state = State(now)
        var title: String

        val rest = words.indices.filter { !consumed[it] }.joinToString(" ") { words[it].raw }
        val commaList = if (kind == "note") COMMA_LIST.find(rest) else null
        if (commaList != null) {
            title = commaList.groupValues[1].trim()
            commaList.groupValues[2].split(',').map { it.trim() }.filter { it.isNotEmpty() }
                .forEach { checks += Check(Ids.short(), it) }
            kind = "list"
        } else {
            // Natural-language dates, times and repeats over the words that aren't labels.
            val idx = words.indices.filter { !consumed[it] }
            val ws = idx.map { words[it] }
            var i = 0
            while (i < ws.size) {
                var m: Match? = null
                var len = 0
                if (ws[i].bare in CONNECTORS && i + 1 < ws.size) {
                    m = matchAt(ws, i + 1, true, state)
                    if (m != null) len = m.len + 1
                }
                if (m == null) {
                    m = matchAt(ws, i, false, state)
                    if (m != null) len = m.len
                }
                if (m != null && take(idx[i], idx[i + len - 1], m.kind)) {
                    m.apply(state)
                    i += len
                } else {
                    i++
                }
            }
            title = words.indices.filter { !consumed[it] }.joinToString(" ") { words[it].raw }.trim()
            if (title.isEmpty()) {
                title = words.indices.filter { tokens.none { t -> t.kind == Kind.LABEL && t.start == words[it].start } }
                    .joinToString(" ") { words[it].raw }.removeSuffix(" !").trim()
                if (title == "!") title = ""
            }
        }

        return Result(
            title = title,
            kind = kind,
            checks = checks,
            labels = labels.toList(),
            due = resolveDue(state),
            repeat = state.repeat,
            pinned = pinned,
            tokens = tokens.sortedBy { it.start },
        )
    }

    private fun resolveDue(s: State): String {
        val now = s.now
        s.exact?.let { return Due.of(it) }
        val time = s.time ?: s.impliedTime
        val date = s.date
        val r = s.repeat
        if (date != null) return Due.of(date, time)
        if (r != null) return Repeats.firstFrom(now, r, time)
        if (time != null) {
            val day = if (time > now.toLocalTime()) now.toLocalDate() else now.toLocalDate().plusDays(1)
            return Due.of(day, time)
        }
        return ""
    }

    // ---- matchers ------------------------------------------------------------------------

    private fun matchAt(ws: List<Word>, i: Int, connector: Boolean, s: State): Match? =
        repeatAt(ws, i, s) ?: dateAt(ws, i, connector, s) ?: timeAt(ws, i, connector)?.let { (len, t) ->
            Match(len, Kind.TIME) { it.time = t }
        }

    private fun weekdayAt(ws: List<Word>, i: Int, allowShort: Boolean): DayOfWeek? {
        val w = ws.getOrNull(i) ?: return null
        WEEKDAYS[w.bare]?.let { return it }
        if (!allowShort) return null
        return SHORT_WEEKDAYS[w.bare]
    }

    /** Two-letter German weekdays only count before a time ("mo 10:00") or after am/on. */
    private fun shortWeekdayOk(ws: List<Word>, i: Int, connector: Boolean): Boolean {
        if (connector) return true
        if (timeAt(ws, i + 1, false) != null) return true
        val next = ws.getOrNull(i + 1) ?: return false
        return next.bare in CONNECTORS && timeAt(ws, i + 2, true) != null
    }

    private fun repeatAt(ws: List<Word>, i: Int, s: State): Match? {
        val w = ws[i].bare
        fun rep(len: Int, r: Repeat) = Match(len, Kind.REPEAT) { it.repeat = r }
        when (w) {
            "daily", "täglich", "taeglich" -> return rep(1, Repeat("day"))
            "weekdays", "werktags", "weekday" -> return rep(1, Repeat("week", byDay = listOf("mo", "tu", "we", "th", "fr")))
            "weekly", "wöchentlich", "woechentlich" -> return rep(1, Repeat("week"))
            "monthly", "monatlich" -> return rep(1, Repeat("month"))
            "yearly", "annually", "jährlich", "jaehrlich" -> return rep(1, Repeat("year"))
            "after", "nach" -> {
                val next = ws.getOrNull(i + 1)?.bare
                if (s.repeat != null && ((w == "after" && next in setOf("done", "completion", "completed")) || (w == "nach" && next == "erledigung"))) {
                    return Match(2, Kind.REPEAT) { st -> st.repeat = st.repeat?.copy(fromCompletion = true) }
                }
                return null
            }
        }
        if (w !in EVERY) return null
        val n1 = ws.getOrNull(i + 1) ?: return null
        when (n1.bare) {
            "day", "tag" -> return rep(2, Repeat("day"))
            "weekday", "werktag", "weekdays", "werktage" -> return rep(2, Repeat("week", byDay = listOf("mo", "tu", "we", "th", "fr")))
            "week", "woche" -> return rep(2, Repeat("week"))
            "month", "monat" -> return rep(2, Repeat("month"))
            "year", "jahr" -> return rep(2, Repeat("year"))
        }
        if (INT.matches(n1.bare)) {
            val unit = ws.getOrNull(i + 2)?.bare?.let { UNITS[it] }
            val n = n1.bare.toInt().coerceAtLeast(1)
            when (unit) {
                TimeUnit.DAY -> return rep(3, Repeat("day", interval = n))
                TimeUnit.WEEK -> return rep(3, Repeat("week", interval = n))
                TimeUnit.MONTH -> return rep(3, Repeat("month", interval = n))
                TimeUnit.YEAR -> return rep(3, Repeat("year", interval = n))
                else -> {}
            }
        }
        ORDINAL.find(n1.norm)?.let { m ->
            val d = m.groupValues[1].toInt()
            if (d in 1..31) return rep(2, Repeat("month", monthDay = d))
        }
        // every monday[, thursday][ and friday]
        val first = weekdayAt(ws, i + 1, true) ?: return null
        val days = mutableListOf(first)
        var j = i + 2
        while (j < ws.size) {
            val prevComma = ws[j - 1].raw.endsWith(",")
            if (prevComma) {
                val d = weekdayAt(ws, j, true) ?: break
                days += d; j++
            } else if (ws[j].bare in setOf("and", "und", "&")) {
                val d = weekdayAt(ws, j + 1, true) ?: break
                days += d; j += 2
            } else break
        }
        val codes = days.distinct().sortedBy { it.value }.map { Repeats.code(it) }
        return rep(j - i, Repeat("week", byDay = codes))
    }

    private fun dateAt(ws: List<Word>, i: Int, connector: Boolean, s: State): Match? {
        val today = s.now.toLocalDate()
        val w = ws[i].bare
        fun date(len: Int, d: LocalDate, implied: LocalTime? = null) = Match(len, Kind.DATE) {
            it.date = d; it.exact = null
            if (implied != null) it.impliedTime = implied
        }
        val next = ws.getOrNull(i + 1)?.bare
        when (w) {
            "heute" -> return if (next == "abend") date(2, today, LocalTime.of(19, 0)) else date(1, today)
            "today" -> return date(1, today)
            "tonight" -> return date(1, today, LocalTime.of(19, 0))
            "tomorrow", "tmrw", "tmr", "morgen" -> return date(1, today.plusDays(1))
            "übermorgen", "uebermorgen" -> return date(1, today.plusDays(2))
            "weekend", "wochenende" -> return date(1, today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)))
            "next", "nächste", "nächsten", "nächster", "naechste", "naechsten", "naechster" -> {
                when (next) {
                    "week", "woche" -> return date(2, today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)))
                    "month", "monat" -> return date(2, today.withDayOfMonth(1).plusMonths(1))
                    "year", "jahr" -> return date(2, today.withDayOfYear(1).plusYears(1))
                }
                val d = weekdayAt(ws, i + 1, true) ?: return null
                return date(2, today.with(TemporalAdjusters.next(d)))
            }
            "in" -> {
                val nw = ws.getOrNull(i + 1)?.bare ?: return null
                if (!INT.matches(nw)) return null
                val n = nw.toLong()
                return when (ws.getOrNull(i + 2)?.bare?.let { UNITS[it] }) {
                    TimeUnit.DAY -> date(3, today.plusDays(n))
                    TimeUnit.WEEK -> date(3, today.plusWeeks(n))
                    TimeUnit.MONTH -> date(3, today.plusMonths(n))
                    TimeUnit.YEAR -> date(3, today.plusYears(n))
                    TimeUnit.HOUR -> exact(3, s.now.plusHours(n))
                    TimeUnit.MINUTE -> exact(3, s.now.plusMinutes(n))
                    null -> null
                }
            }
        }
        WEEKDAYS[w]?.let { return date(1, today.with(TemporalAdjusters.next(it))) }
        SHORT_WEEKDAYS[w]?.let { if (shortWeekdayOk(ws, i, connector)) return date(1, today.with(TemporalAdjusters.next(it))) }

        val raw = ws[i].norm
        ISO.find(raw)?.let { m ->
            val d = safeDate(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt()) ?: return null
            return date(1, d)
        }
        DOTTED.find(raw)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val y = m.groupValues[3]
            val d = if (y.isEmpty()) upcoming(today, month, day) else safeDate(if (y.length == 2) 2000 + y.toInt() else y.toInt(), month, day)
            return d?.let { date(1, it) }
        }
        SLASH.find(raw)?.let { m ->
            return upcoming(today, m.groupValues[1].toInt(), m.groupValues[2].toInt())?.let { date(1, it) }
        }
        // 29 oct / 29. oktober
        DAY_NUM.find(raw)?.let { m ->
            val month = ws.getOrNull(i + 1)?.bare?.let { MONTHS[it] } ?: return@let
            return upcoming(today, month, m.groupValues[1].toInt())?.let { date(2, it) }
        }
        // oct 29 / october 29th
        MONTHS[w]?.let { month ->
            val m = DAY_NUM.find(ws.getOrNull(i + 1)?.norm ?: "") ?: return null
            return upcoming(today, month, m.groupValues[1].toInt())?.let { date(2, it) }
        }
        return null
    }

    private fun exact(len: Int, dt: LocalDateTime): Match {
        // Round up to the next 5 minutes.
        var t = dt.truncatedTo(ChronoUnit.MINUTES)
        if (dt.second > 0 || dt.nano > 0) t = t.plusMinutes(1)
        val rem = t.minute % 5
        if (rem != 0) t = t.plusMinutes((5 - rem).toLong())
        return Match(len, Kind.DATE) { it.exact = t; it.date = null }
    }

    private fun safeDate(y: Int, m: Int, d: Int): LocalDate? = try { LocalDate.of(y, m, d) } catch (_: Exception) { null }

    /** The next occurrence of month/day on or after today. */
    private fun upcoming(today: LocalDate, month: Int, day: Int): LocalDate? {
        if (month !in 1..12 || day !in 1..31) return null
        val d = safeDate(today.year, month, day) ?: safeDate(today.year + 1, month, day) ?: return null
        return if (d < today) safeDate(today.year + 1, month, day) else d
    }

    /** Returns (words consumed, time). Bare numbers need a connector ("um 9", "at 9"). */
    private fun timeAt(ws: List<Word>, i: Int, connector: Boolean): Pair<Int, LocalTime>? {
        val w = ws.getOrNull(i) ?: return null
        val b = w.bare
        when (b) {
            "noon", "mittag", "mittags" -> return 1 to LocalTime.NOON
            "midnight", "mitternacht" -> return 1 to LocalTime.MIDNIGHT
        }
        AMPM.find(b)?.let { m -> return hm12(m.groupValues[1], m.groupValues[2], m.groupValues[3])?.let { 1 to it } }
        HHMM.find(b)?.let { m -> return hm(m.groupValues[1].toInt(), m.groupValues[2].toInt())?.let { 1 to it } }
        HOUR_H.find(b)?.let { m -> return hm(m.groupValues[1].toInt(), 0)?.let { 1 to it } }
        val num = Regex("""^(\d{1,2})(?::(\d{2}))?$""").find(b) ?: return null
        val suffix = ws.getOrNull(i + 1)?.bare
        when (suffix) {
            "am", "pm" -> return hm12(num.groupValues[1], num.groupValues[2], suffix)?.let { 2 to it }
            "uhr", "o'clock" -> return hm(num.groupValues[1].toInt(), num.groupValues[2].ifEmpty { "0" }.toInt())?.let { 2 to it }
        }
        if (connector && NUM.matches(b)) return hm(b.toInt(), 0)?.let { 1 to it }
        return null
    }

    private fun hm(h: Int, m: Int): LocalTime? = if (h in 0..23 && m in 0..59) LocalTime.of(h, m) else null

    private fun hm12(h: String, m: String, ap: String): LocalTime? {
        var hour = h.toInt()
        if (hour !in 1..12) return null
        if (ap == "pm" && hour != 12) hour += 12
        if (ap == "am" && hour == 12) hour = 0
        return hm(hour, m.ifEmpty { "0" }.toInt())
    }
}
