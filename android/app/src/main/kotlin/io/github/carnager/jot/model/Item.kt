package io.github.carnager.jot.model

import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter

data class Check(val id: String, val text: String, val done: Boolean = false, val indent: Int = 0)

data class Repeat(
    val freq: String,                    // day | week | month | year
    val interval: Int = 1,
    val byDay: List<String> = emptyList(), // mo tu we th fr sa su
    val monthDay: Int = 0,
    val fromCompletion: Boolean = false,
    val until: String = "",
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("freq", freq)
        put("interval", interval)
        if (byDay.isNotEmpty()) put("byDay", JSONArray(byDay))
        if (monthDay > 0) put("monthDay", monthDay)
        put("fromCompletion", fromCompletion)
        put("until", until)
    }

    companion object {
        fun fromJson(o: JSONObject?): Repeat? {
            if (o == null) return null
            val freq = o.optString("freq", "")
            if (freq !in listOf("day", "week", "month", "year")) return null
            val days = o.optJSONArray("byDay")
            return Repeat(
                freq = freq,
                interval = o.optInt("interval", 1).coerceAtLeast(1),
                byDay = if (days == null) emptyList() else List(days.length()) { days.optString(it) }.filter { it in WEEKDAY_CODES },
                monthDay = o.optInt("monthDay", 0),
                fromCompletion = o.optBoolean("fromCompletion", false),
                until = o.optString("until", ""),
            )
        }
    }
}

val WEEKDAY_CODES = listOf("mo", "tu", "we", "th", "fr", "sa", "su")

/**
 * One note or task. [raw] is the JSON the item was read from; [toJson] writes the typed
 * fields over it, so fields this client doesn't know about survive a round trip.
 */
data class Item(
    val id: String,
    val kind: String = "note",
    val title: String = "",
    val body: String = "",
    val checks: List<Check> = emptyList(),
    val color: String = "",
    val labels: List<String> = emptyList(),
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val done: Boolean = false,
    val doneAt: Long = 0,
    val due: String = "",
    val remind: Boolean = true,
    val snoozedUntil: String = "",
    val repeat: Repeat? = null,
    val sort: Double = 0.0,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val trashedAt: Long = 0,
    val deleted: Boolean = false,
    val rev: Long = 0,
    val raw: String = "{}",
) {
    val isList get() = kind == "list"
    val isTask get() = due.isNotEmpty() || done
    val trashed get() = trashedAt > 0
    val live get() = !deleted && !trashed

    val dueDate: LocalDate? get() = Due.date(due)
    val dueTime: LocalTime? get() = Due.time(due)

    /** Text for search: lowercased, diacritics folded. */
    val haystack: String by lazy {
        Text.fold(buildString {
            append(title).append('\n').append(body).append('\n')
            checks.forEach { append(it.text).append('\n') }
            labels.forEach { append(it).append(' ') }
        })
    }

    fun isEmpty() = title.isBlank() && body.isBlank() && checks.all { it.text.isBlank() }

    fun toJson(): JSONObject {
        val o = try { JSONObject(raw) } catch (_: Exception) { JSONObject() }
        o.put("id", id)
        o.put("kind", kind)
        o.put("title", title)
        o.put("body", body)
        o.put("checks", JSONArray().apply {
            checks.forEach { c ->
                put(JSONObject().put("id", c.id).put("text", c.text).put("done", c.done).put("indent", c.indent))
            }
        })
        o.put("color", color)
        o.put("labels", JSONArray(labels))
        o.put("pinned", pinned)
        o.put("archived", archived)
        o.put("done", done)
        o.put("doneAt", doneAt)
        o.put("due", due)
        o.put("remind", remind)
        o.put("snoozedUntil", snoozedUntil)
        o.put("repeat", repeat?.toJson() ?: JSONObject.NULL)
        o.put("sort", sort)
        o.put("createdAt", createdAt)
        o.put("updatedAt", updatedAt)
        o.put("trashedAt", trashedAt)
        o.put("deleted", deleted)
        o.put("rev", rev)
        return o
    }

    companion object {
        fun fromJson(o: JSONObject): Item {
            val checks = o.optJSONArray("checks")
            val labels = o.optJSONArray("labels")
            return Item(
                id = o.getString("id"),
                kind = if (o.optString("kind") == "list") "list" else "note",
                title = o.optString("title", ""),
                body = o.optString("body", ""),
                checks = if (checks == null) emptyList() else List(checks.length()) {
                    val c = checks.optJSONObject(it) ?: JSONObject()
                    Check(
                        id = c.optString("id").ifEmpty { Ids.short() },
                        text = c.optString("text", ""),
                        done = c.optBoolean("done", false),
                        indent = c.optInt("indent", 0).coerceIn(0, 1),
                    )
                },
                color = o.optString("color", ""),
                labels = if (labels == null) emptyList() else List(labels.length()) { labels.optString(it) }.filter { it.isNotEmpty() },
                pinned = o.optBoolean("pinned", false),
                archived = o.optBoolean("archived", false),
                done = o.optBoolean("done", false),
                doneAt = o.optLong("doneAt", 0),
                due = o.optString("due", "").let { if (Due.date(it) == null) "" else it },
                remind = o.optBoolean("remind", true),
                snoozedUntil = o.optString("snoozedUntil", ""),
                repeat = Repeat.fromJson(o.optJSONObject("repeat")),
                sort = o.optDouble("sort", 0.0).let { if (it.isNaN()) 0.0 else it },
                createdAt = o.optLong("createdAt", 0),
                updatedAt = o.optLong("updatedAt", 0),
                trashedAt = o.optLong("trashedAt", 0),
                deleted = o.optBoolean("deleted", false),
                rev = o.optLong("rev", 0),
                raw = o.toString(),
            )
        }

        fun parse(json: String): Item = fromJson(JSONObject(json))

        fun new(now: Long = System.currentTimeMillis()) =
            Item(id = Ids.ulid(now), sort = now.toDouble(), createdAt = now, updatedAt = now)
    }
}

/** The floating `due` / `snoozedUntil` strings: "" | "yyyy-MM-dd" | "yyyy-MM-ddTHH:mm". */
object Due {
    private val TIME = DateTimeFormatter.ofPattern("HH:mm")

    fun date(s: String): LocalDate? = if (s.length < 10) null else try { LocalDate.parse(s.substring(0, 10)) } catch (_: Exception) { null }

    fun time(s: String): LocalTime? = if (s.length < 16) null else try { LocalTime.parse(s.substring(11, 16)) } catch (_: Exception) { null }

    fun dateTime(s: String): LocalDateTime? {
        val d = date(s) ?: return null
        return LocalDateTime.of(d, time(s) ?: return null)
    }

    fun of(date: LocalDate, time: LocalTime?): String =
        if (time == null) date.toString() else "$date" + "T" + time.format(TIME)

    fun of(dt: LocalDateTime): String = of(dt.toLocalDate(), dt.toLocalTime())
}

object Text {
    private val marks = Regex("\\p{Mn}+")
    fun fold(s: String): String =
        marks.replace(java.text.Normalizer.normalize(s.lowercase(), java.text.Normalizer.Form.NFD), "")
}

object Ids {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = java.security.SecureRandom()

    fun ulid(now: Long = System.currentTimeMillis()): String {
        val sb = StringBuilder(26)
        var t = now
        val time = CharArray(10)
        for (i in 9 downTo 0) { time[i] = ALPHABET[(t and 31).toInt()]; t = t shr 5 }
        sb.append(time)
        repeat(16) { sb.append(ALPHABET[random.nextInt(32)]) }
        return sb.toString()
    }

    fun short(): String = buildString { repeat(8) { append(ALPHABET[random.nextInt(32)]) } }.lowercase()
}
