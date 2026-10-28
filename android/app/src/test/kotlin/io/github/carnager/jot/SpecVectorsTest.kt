package io.github.carnager.jot

import io.github.carnager.jot.model.Check
import io.github.carnager.jot.model.Item
import io.github.carnager.jot.model.QuickAdd
import io.github.carnager.jot.model.Repeat
import io.github.carnager.jot.model.Repeats
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

/** Runs the shared test vectors from ../spec so this client stays in line with the web app. */
class SpecVectorsTest {
    private fun spec(name: String): JSONObject {
        val candidates = listOf("../../spec/$name", "../spec/$name", "spec/$name")
        val f = candidates.map(::File).firstOrNull { it.isFile } ?: error("spec/$name not found from ${File(".").absolutePath}")
        return JSONObject(f.readText())
    }

    private fun repeatOf(o: JSONObject?): Repeat? = Repeat.fromJson(o)

    @Test
    fun quickAdd() {
        val root = spec("quickadd.json")
        val now = LocalDateTime.parse(root.getString("now"))
        val cases = root.getJSONArray("cases")
        val failures = mutableListOf<String>()
        for (k in 0 until cases.length()) {
            val c = cases.getJSONObject(k)
            val input = c.getString("input")
            val r = QuickAdd.parse(input, now)
            val labels = c.optJSONArray("labels")?.let { a -> List(a.length()) { a.getString(it) } } ?: emptyList()
            val checks = c.optJSONArray("checks")?.let { a ->
                List(a.length()) { a.getJSONObject(it).let { o -> o.getString("text") to o.optBoolean("done", false) } }
            } ?: emptyList()
            val expected = listOf(
                c.getString("title"), c.optString("kind", "note"), c.optString("due", ""), labels,
                repeatOf(c.optJSONObject("repeat")), c.optBoolean("pinned", false), checks,
            )
            val actual = listOf(
                r.title, r.kind, r.due, r.labels, r.repeat, r.pinned, r.checks.map { it.text to it.done },
            )
            if (expected != actual) failures += "\"$input\"\n  expected $expected\n  actual   $actual"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun repeats() {
        val cases = spec("repeat.json").getJSONArray("cases")
        val failures = mutableListOf<String>()
        for (k in 0 until cases.length()) {
            val c = cases.getJSONObject(k)
            val r = Repeat.fromJson(c.getJSONObject("repeat"))!!
            val now = LocalDateTime.parse(c.getString("now"))
            val exp = c.getJSONObject("expect")
            val out = Repeats.complete(c.getString("due"), r, now)
            val ok = if (exp.optBoolean("done", false)) out == Repeats.Finished else out == Repeats.Next(exp.getString("due"))
            if (!ok) failures += "${c.getString("name")}: expected $exp, got $out"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun completingResetsChecks() {
        val item = Item.new(0).copy(
            due = "2026-10-28", repeat = Repeat("day"), checks = listOf(Check("a", "x", done = true)),
        )
        val next = Repeats.completeItem(item, LocalDateTime.parse("2026-10-28T10:00"), 5)
        assertEquals("2026-10-29", next.due)
        assertEquals(false, next.done)
        assertEquals(false, next.checks[0].done)
    }

    @Test
    fun unknownFieldsSurvive() {
        val item = Item.parse("""{"id":"x","title":"a","future":{"k":1},"checks":[]}""")
        val back = item.copy(title = "b").toJson()
        assertEquals(1, back.getJSONObject("future").getInt("k"))
        assertEquals("b", back.getString("title"))
    }

    @Test
    fun revertingAToken() {
        val now = LocalDateTime.parse("2026-10-28T14:00")
        val r = QuickAdd.parse("Read book tomorrow", now, disabled = setOf("tomorrow"))
        assertEquals("Read book tomorrow", r.title)
        assertEquals("", r.due)
    }

    @Test
    fun imagesRoundTrip() {
        val item = Item.parse("""{"id":"x","images":[{"id":"a1","w":1600,"h":1200,"mime":"image/jpeg"}]}""")
        assertEquals(1, item.images.size)
        assertEquals(4f / 3f, item.images[0].ratio, 0.001f)
        val back = item.copy(images = item.images + io.github.carnager.jot.model.ImageRef("b2", 10, 20, "image/gif")).toJson()
        assertEquals("b2", back.getJSONArray("images").getJSONObject(1).getString("id"))
        assertEquals(false, item.isEmpty())
        // Items without images don't grow an empty field.
        assertEquals(false, Item.parse("""{"id":"y"}""").toJson().has("images"))
    }

    @Test
    fun downscaleKeepsLongEdgeAndRatio() {
        val images = io.github.carnager.jot.data.Images
        assertEquals(2560 to 1920, images.targetSize(4032, 3024))
        assertEquals(1440 to 2560, images.targetSize(3024, 5376))
        assertEquals(800 to 600, images.targetSize(800, 600))
    }
}
