package io.github.carnager.jot

import io.github.carnager.jot.model.Alerts
import io.github.carnager.jot.model.Check
import io.github.carnager.jot.model.FieldMerge
import io.github.carnager.jot.model.ImageRef
import io.github.carnager.jot.model.Item
import io.github.carnager.jot.model.Repeat
import io.github.carnager.jot.model.Repeats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.LocalTime

/** Port of server/web/test/fieldmerge.mjs (SPEC §2b, §5). */
class FieldMergeTest {
    private val t0 = 1_000_000_000_000L
    private val t1 = t0 + 5000
    private val base = Item(
        id = "a", kind = "list", title = "Trip", due = "2026-10-28", sort = 1.0,
        updatedAt = t0, fm = mapOf("title" to t0 - 1),
        checks = listOf(
            Check("c1", "Passport", u = t0),
            Check("c2", "Charger", u = t0),
            Check("c3", "Socks", done = true, u = t0),
        ),
    )

    private fun groupsSet(r: Item, at: Long = t1) = r.fm.filterValues { it == at }.keys.sorted()
    private fun stamp(next: Item, prev: Item? = base, now: Long = t1) = FieldMerge.stamp(prev, next, now)

    @Test fun titleStampsOnlyTitle() {
        val r = stamp(base.copy(title = "Lisbon"))
        assertEquals(listOf("title"), groupsSet(r))
        assertEquals(t1, r.fm["title"])
    }

    @Test fun scheduleGroup() {
        assertEquals(listOf("schedule"), groupsSet(stamp(base.copy(due = "2026-10-29"))))
        assertEquals(listOf("schedule"), groupsSet(stamp(base.copy(alerts = listOf(0, 30)))))
    }

    @Test fun severalGroupsAtOnce() {
        val r = stamp(base.copy(labels = listOf("x"), pinned = true, color = "red", images = listOf(ImageRef("i", 1, 1)), trashedAt = 5))
        assertEquals(listOf("color", "images", "labels", "pinned", "trash"), groupsSet(r))
    }

    @Test fun noChangeNoStamps() = assertEquals(emptyList<String>(), groupsSet(stamp(base.copy())))

    @Test fun checkEditStampsThatCheckOnly() {
        val r = stamp(base.copy(checks = base.checks.map { if (it.id == "c2") it.copy(text = "Charger + cable") else it }))
        assertEquals(listOf(t0, t1, t0), r.checks.map { it.u })
        assertEquals(emptyList<String>(), groupsSet(r))
    }

    @Test fun doneToggleStampsU() {
        val r = stamp(base.copy(checks = base.checks.map { if (it.id == "c1") it.copy(done = true) else it }))
        assertEquals(t1, r.checks[0].u)
    }

    @Test fun removingLeavesTombstoneAndSetsOrder() {
        val r = stamp(base.copy(checks = base.checks.filter { it.id != "c2" }))
        assertEquals(Check("c2", "", u = t1, deleted = true), r.tombstones.single())
        assertEquals(listOf("order"), groupsSet(r))
        assertEquals(listOf("c1", "c3"), r.checks.map { it.id })
        // Callers pass live checks only; the tombstone must survive, and that's no order change.
        val t2 = t1 + 1000
        val r2 = FieldMerge.stamp(r, r.copy(checks = r.checks.map { if (it.id == "c1") it.copy(text = "Passport!") else it }, tombstones = emptyList()), t2)
        assertEquals(listOf("c2" to t1), r2.tombstones.map { it.id to it.u })
        assertEquals(t1, r2.fm["order"])
    }

    @Test fun reorderSetsOrderKeepsU() {
        val r = stamp(base.copy(checks = listOf(base.checks[1], base.checks[0], base.checks[2])))
        assertEquals(listOf("order"), groupsSet(r))
        assertEquals(listOf(t0, t0, t0), r.checks.map { it.u })
    }

    @Test fun addSetsOrderAndU() {
        val r = stamp(base.copy(checks = base.checks + Check("c4", "Hat")))
        assertEquals(listOf("order"), groupsSet(r))
        assertEquals(t1, r.checks[3].u)
    }

    @Test fun oldTombstonesAreDropped() {
        val old = base.copy(tombstones = listOf(Check("cx", "", u = t0 - FieldMerge.TOMBSTONE_TTL - 1, deleted = true)))
        val r = FieldMerge.stamp(old, old.copy(title = "x"), t1)
        assertFalse(r.tombstones.any { it.id == "cx" })
    }

    @Test fun newItemChecksGetUNoGroupStamps() {
        val r = FieldMerge.stamp(null, base.copy(fm = emptyMap(), checks = listOf(Check("n1", "New"))), t1)
        assertEquals(t1, r.checks[0].u)
        assertTrue(r.fm.isEmpty())
    }

    @Test fun repeatAdvanceResetsChecksWithFreshU() {
        val rep = base.copy(repeat = Repeat("day"), tombstones = listOf(Check("cz", "", u = t0, deleted = true)))
        val adv = FieldMerge.stamp(rep, Repeats.completeItem(rep, LocalDateTime.parse("2026-10-28T14:00"), t1), t1)
        assertEquals(listOf("schedule"), groupsSet(adv))
        assertTrue(adv.checks.all { !it.done })
        assertEquals(t1, adv.checks.first { it.id == "c3" }.u)
        assertEquals(Check("cz", "", u = t0, deleted = true), adv.tombstones.single())
    }

    @Test fun writeKeepsUpdatedAtEqualToNewestStamp() {
        val w = FieldMerge.write(base, base.copy(title = "Renamed", checks = base.checks.drop(1)), t1)
        val stamps = w.fm.values + w.checks.map { it.u } + w.tombstones.map { it.u }
        assertEquals(stamps.max(), w.updatedAt)
        // Two writes in the same millisecond still move forward.
        val w2 = FieldMerge.write(w, w.copy(pinned = true), t1)
        assertEquals(t1 + 1, w2.updatedAt)
        assertEquals(t1 + 1, w2.fm["pinned"])
    }

    @Test fun undoSnapshotCountsAsChangeAndNeverMovesStampsBack() {
        val renamed = FieldMerge.write(base, base.copy(title = "Renamed"), t1)
        val snap = renamed
        val again = FieldMerge.write(renamed, renamed.copy(title = "Again"), t1 + 10)
        val restored = FieldMerge.write(again, snap, t1 + 20)
        assertEquals("Renamed", restored.title)
        assertTrue(restored.fm["title"]!! > again.fm["title"]!!)
    }

    @Test fun tombstonesRoundTripThroughJson() {
        val r = stamp(base.copy(checks = base.checks.filter { it.id != "c2" }))
        val back = Item.fromJson(r.toJson())
        assertEquals(listOf("c1", "c3"), back.checks.map { it.id })
        assertEquals(listOf("c2"), back.tombstones.map { it.id })
        assertEquals(r.fm, back.fm)
        assertNull(r.toJson().getJSONArray("checks").getJSONObject(2).optString("text", null))
    }

    // ---- §5 alerts ----

    private fun hm(f: Alerts.Fire) = "${f.at.dayOfMonth} %02d:%02d".format(f.at.hour, f.at.minute)

    @Test fun alerts() {
        val nine = LocalTime.of(9, 0)
        val it = Item("x", due = "2026-10-29T14:00", alerts = listOf(30, 0, 1440, 30))
        assertEquals(listOf("29 14:00", "29 13:30", "28 14:00"), Alerts.fireTimes(it, nine).map(::hm))
        assertEquals(listOf("29 08:00", "28 08:00"), Alerts.fireTimes(Item("x", due = "2026-10-29", alerts = listOf(0, 1440)), LocalTime.of(8, 0)).map(::hm))
        assertEquals(listOf(0), Alerts.fireTimes(Item("x", due = "2026-10-29T09:00"), nine).map { it.minutesBefore })
        assertEquals(listOf("29 10:15"), Alerts.fireTimes(Item("x", due = "2026-10-29T09:00", alerts = listOf(0, 60), snoozedUntil = "2026-10-29T10:15"), nine).map(::hm))
        assertEquals(0, Alerts.fireTimes(Item("x", due = "2026-10-29", done = true), nine).size)
        assertEquals(0, Alerts.fireTimes(Item("x", due = "2026-10-29", remind = false), nine).size)
        assertEquals("On time · 30 min before", Alerts.describe(listOf(30, 0)))
        assertEquals("On the day · 1 day before", Alerts.describe(listOf(1440, 0), true))
        assertEquals(listOf("1 h before", "2 h before", "2 days before", "1 week before"), listOf(60, 120, 2880, 10080).map { Alerts.label(it) })
        assertEquals(listOf(0), Alerts.norm(emptyList()))
        assertTrue(Alerts.isDefault(emptyList()))
        assertFalse(Alerts.isDefault(listOf(0, 5)))
    }

    @Test fun leadText() {
        val now = LocalDateTime.parse("2026-10-28T14:00")
        assertEquals("in 30 min", Alerts.leadText(LocalDateTime.parse("2026-10-28T14:30"), now))
        assertEquals("in 2 h", Alerts.leadText(LocalDateTime.parse("2026-10-28T16:00"), now))
        assertEquals("today 21:00", Alerts.leadText(LocalDateTime.parse("2026-10-28T21:00"), now))
        assertEquals("tomorrow 09:00", Alerts.leadText(LocalDateTime.parse("2026-10-29T09:00"), now))
        assertEquals("Fri 09:00", Alerts.leadText(LocalDateTime.parse("2026-10-30T09:00"), now))
    }
}
