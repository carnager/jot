package io.github.carnager.jot.ui

import io.github.carnager.jot.model.Item
import io.github.carnager.jot.model.Text
import java.time.LocalDate
import java.time.LocalDateTime

/** The views of SPEC §1, as pure functions over the item list. */
object Views {
    const val TODAY = "today"
    const val UPCOMING = "upcoming"
    const val NOTES = "notes"
    const val LABEL = "label"
    const val DONE = "done"
    const val ARCHIVE = "archive"
    const val TRASH = "trash"

    data class Section(val title: String?, val items: List<Item>, val alert: Boolean = false, val key: String = title ?: "")

    private val active: (Item) -> Boolean = { it.live && !it.archived && !it.done }

    /** All-day first, then by time, then manual order. */
    private val withinDay = compareBy<Item>({ it.dueTime != null }, { it.dueTime }, { -it.sort })

    fun today(items: List<Item>, now: LocalDateTime = LocalDateTime.now()): List<Section> {
        val today = now.toLocalDate()
        val due = items.filter { active(it) && it.dueDate?.let { d -> d <= today } == true }
        val overdue = due.filter { it.dueDate!! < today }.sortedWith(compareBy<Item>({ it.dueDate }).then(withinDay))
        val rest = due.filter { it.dueDate == today }.sortedWith(withinDay)
        val allDay = rest.filter { it.dueTime == null }
        fun part(from: Int, to: Int) = rest.filter { t -> t.dueTime?.hour?.let { it in from until to } == true }
        return listOfNotNull(
            Section("Overdue", overdue, alert = true, key = "overdue").takeIf { overdue.isNotEmpty() },
            Section("All day", allDay, key = "allday").takeIf { allDay.isNotEmpty() },
            Section("Morning", part(0, 12), key = "morning").takeIf { part(0, 12).isNotEmpty() },
            Section("Afternoon", part(12, 17), key = "afternoon").takeIf { part(12, 17).isNotEmpty() },
            Section("Evening", part(17, 24), key = "evening").takeIf { part(17, 24).isNotEmpty() },
        )
    }

    /** (done today, total for today) for the progress ring. Repeats count when they were completed today. */
    fun todayProgress(items: List<Item>, today: LocalDate = LocalDate.now()): Pair<Int, Int> {
        val zone = java.time.ZoneId.systemDefault()
        val open = todayCount(items, today)
        val done = items.count {
            it.live && !it.archived && it.doneAt > 0 &&
                java.time.Instant.ofEpochMilli(it.doneAt).atZone(zone).toLocalDate() == today &&
                (it.done || it.repeat != null)
        }
        return done to (open + done)
    }

    fun todayCount(items: List<Item>, today: LocalDate = LocalDate.now()) =
        items.count { active(it) && it.dueDate?.let { d -> d <= today } == true }

    fun upcoming(items: List<Item>, today: LocalDate = LocalDate.now()): List<Section> =
        items.filter { active(it) && it.dueDate?.let { d -> d > today } == true }
            .groupBy { it.dueDate!! }
            .toSortedMap()
            .map { (d, list) -> Section(Fmt.dayHeader(d, today), list.sortedWith(withinDay), key = d.toString()) }

    fun upcomingCounts(items: List<Item>): Map<LocalDate, Int> =
        items.filter { active(it) && it.dueDate != null }.groupingBy { it.dueDate!! }.eachCount()

    private val manual = compareByDescending<Item> { it.pinned }.thenByDescending { it.sort }

    fun notes(items: List<Item>, label: String? = null): List<Section> {
        val list = items.filter { active(it) && (label == null || label in it.labels) }.sortedWith(manual)
        val pinned = list.filter { it.pinned }
        val others = list.filter { !it.pinned }
        if (pinned.isEmpty()) return listOf(Section(null, others, key = "all"))
        return listOfNotNull(Section("Pinned", pinned), if (others.isNotEmpty()) Section("Others", others) else null)
    }

    fun done(items: List<Item>) =
        listOf(Section(null, items.filter { it.live && it.done }.sortedByDescending { it.doneAt }, key = "done"))

    fun archive(items: List<Item>) =
        listOf(Section(null, items.filter { it.live && it.archived }.sortedByDescending { it.updatedAt }, key = "archive"))

    fun trash(items: List<Item>) =
        listOf(Section(null, items.filter { it.trashed && !it.deleted }.sortedByDescending { it.trashedAt }, key = "trash"))

    fun search(items: List<Item>, query: String): List<Section> {
        val terms = Text.fold(query).split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()
        val hits = items.filter { it.live && terms.all { t -> t in it.haystack } }
            .sortedWith(compareBy<Item>({ it.archived }, { it.done }).thenByDescending { it.updatedAt })
        return listOf(Section(null, hits, key = "search"))
    }

    fun sections(view: String, label: String?, items: List<Item>): List<Section> = when (view) {
        TODAY -> today(items)
        UPCOMING -> upcoming(items)
        LABEL -> notes(items, label)
        DONE -> done(items)
        ARCHIVE -> archive(items)
        TRASH -> trash(items)
        else -> notes(items)
    }

    /** Task views show rows; the others a card grid. */
    fun isTaskView(view: String) = view == TODAY || view == UPCOMING || view == DONE
}
