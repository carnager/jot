package io.github.carnager.jot.model

/**
 * Field-level change tracking (SPEC §2b), the Kotlin twin of the web's `fieldmerge.js`.
 * Every local write runs through [stamp], so no code path can forget to record what it changed.
 */
object FieldMerge {
    const val TOMBSTONE_TTL = 30L * 86_400_000

    /** Field groups and how to read their fields; `order` (check order) is handled separately. */
    val GROUPS: Map<String, (Item) -> Any?> = linkedMapOf(
        "title" to { it.title },
        "body" to { it.body },
        "kind" to { it.kind },
        "color" to { it.color },
        "labels" to { it.labels },
        "pinned" to { it.pinned },
        "archived" to { it.archived },
        "schedule" to { listOf(it.done, it.doneAt, it.due, it.remind, it.alerts, it.snoozedUntil, it.repeat) },
        "sort" to { it.sort },
        "images" to { it.images },
        "trash" to { it.trashedAt },
    )

    /**
     * A local write as the store does it: one timestamp `ts = max(now, prev.updatedAt + 1)` for updatedAt
     * and every stamp, so `updatedAt == max(fm ∪ checks.u)` holds (SPEC §2b).
     */
    fun write(prev: Item?, next: Item, now: Long): Item {
        val ts = maxOf(now, (prev?.updatedAt ?: 0) + 1)
        return stamp(prev, next, ts).copy(updatedAt = ts)
    }

    /**
     * Given the stored item [prev] (null for a new item) and the caller's [next], returns the item to store:
     *  - fm[group] = now for every group whose fields changed (never moving a stamp backwards),
     *  - every new or changed check gets u = now,
     *  - checks the caller dropped become tombstones; existing tombstones are kept, ones older than 30 days dropped,
     *  - adding, removing or reordering checks sets fm.order.
     * `updatedAt` is left to the caller, which uses the same [now].
     */
    fun stamp(prev: Item?, next: Item, now: Long): Item {
        val fm = HashMap(prev?.fm.orEmpty())
        for ((g, t) in next.fm) if ((fm[g] ?: Long.MIN_VALUE) < t) fm[g] = t
        if (prev != null) {
            for ((g, read) in GROUPS) if (read(prev) != read(next)) fm[g] = now
        }

        // ---- checks ----
        val prevAll = prev?.checks.orEmpty() + prev?.tombstones.orEmpty()
        val prevById = prevAll.associateBy { it.id }
        val seen = HashSet<String>()
        val live = ArrayList<Check>()
        for (c in next.checks) {
            if (!seen.add(c.id)) continue
            val p = prevById[c.id]
            val changed = p == null || p.deleted || p.text != c.text || p.done != c.done || p.indent != c.indent
            live += if (changed) c.copy(u = now, deleted = false) else c.copy(u = if (c.u > 0) c.u else p!!.u.takeIf { it > 0 } ?: now)
        }
        var removed = false
        val tombs = ArrayList<Check>()
        for (t in prev?.tombstones.orEmpty() + next.tombstones) {
            if (seen.add(t.id)) tombs += (prevById[t.id]?.takeIf { it.deleted } ?: t)
        }
        for (p in prev?.checks.orEmpty()) {
            if (seen.add(p.id)) {
                tombs += Check(p.id, "", u = now, deleted = true)
                removed = true
            }
        }
        val keptTombs = tombs.filter { it.u > now - TOMBSTONE_TTL }

        if (prev != null) {
            val added = live.any { c -> prevById[c.id]?.deleted != false }
            val reordered = prev.checks.map { it.id } != live.map { it.id }
            if (removed || added || reordered) fm["order"] = now
        }
        return next.copy(checks = live, tombstones = keptTombs, fm = fm)
    }
}
