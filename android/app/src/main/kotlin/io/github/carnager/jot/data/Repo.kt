package io.github.carnager.jot.data

import android.content.Context
import android.os.Build
import android.util.Log
import io.github.carnager.jot.model.Due
import io.github.carnager.jot.model.FieldMerge
import io.github.carnager.jot.model.Item
import io.github.carnager.jot.model.Repeats
import io.github.carnager.jot.notify.Reminders
import io.github.carnager.jot.widget.TodayWidget
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/**
 * The single source of truth: every item in memory (personal scale), persisted to SQLite and
 * synced with the server. Mutations apply to memory at once, then persist and sync behind.
 */
object Repo {
    private const val TAG = "JotRepo"
    private const val TRASH_DAYS = 30L

    lateinit var settings: Settings
        private set
    lateinit var app: Context
        private set
    private lateinit var db: Db

    private val lock = Any()
    private val map = HashMap<String, Item>()
    private val dirty = HashMap<String, Long>()
    private var seq = 0L

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    /** All items including trash and tombstones; views filter. */
    val items: StateFlow<List<Item>> = _items

    private val _syncing = MutableStateFlow(false)
    val syncing: StateFlow<Boolean> = _syncing

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private sealed interface Write
    private class Rows(val rows: List<Pair<Item, Long>>) : Write
    private class Flush(val done: CompletableDeferred<Unit>) : Write
    private data object Clear : Write
    private val writes = Channel<Write>(Channel.UNLIMITED)

    private var initialized = false

    fun init(context: Context) {
        synchronized(lock) {
            if (initialized) return
            initialized = true
            app = context.applicationContext
            settings = Settings(app)
            db = Db(app)
            for (row in db.loadAll()) {
                map[row.item.id] = row.item
                if (row.dirtySeq > 0) dirty[row.item.id] = row.dirtySeq
                if (row.dirtySeq > seq) seq = row.dirtySeq
            }
            emit()
        }
        scope.launch(Dispatchers.IO) {
            for (w in writes) when (w) {
                is Rows -> runCatching { db.write(w.rows) }.onFailure { Log.e(TAG, "write failed", it) }
                is Flush -> w.done.complete(Unit)
                Clear -> runCatching { db.clear() }
            }
        }
        purgeTrash()
        afterChange(sync = false)
    }

    private fun emit() {
        _items.value = map.values.toList()
    }

    fun get(id: String): Item? = synchronized(lock) { map[id] }

    val nowMs get() = System.currentTimeMillis()

    /** Stores changed items (stamping updatedAt) and schedules persistence, reminders and sync. */
    fun save(vararg items: Item) = saveAll(items.toList())

    fun saveAll(items: List<Item>) {
        if (items.isEmpty()) return
        val now = nowMs
        val rows = ArrayList<Pair<Item, Long>>()
        synchronized(lock) {
            for (i in items) {
                val prev = map[i.id]
                // SPEC §2b: field groups and checks are stamped with the same time as updatedAt.
                val stamped = FieldMerge.write(prev, i, now)
                map[i.id] = stamped
                val s = ++seq
                dirty[i.id] = s
                rows += stamped to s
            }
            emit()
        }
        writes.trySend(Rows(rows))
        afterChange(sync = true)
        if (settings.current.signedIn && items.any { it.images.isNotEmpty() } && Images.pendingFiles(app).isNotEmpty()) UploadWorker.enqueue(app)
    }

    fun update(ids: Collection<String>, f: (Item) -> Item): List<Item> {
        val before = synchronized(lock) { ids.mapNotNull { map[it] } }
        saveAll(before.map(f))
        return before
    }

    fun update(id: String, f: (Item) -> Item): Item? = update(listOf(id), f).firstOrNull()

    /** Puts previous versions back (undo). */
    fun restore(previous: List<Item>) = saveAll(previous)

    suspend fun flush() {
        val d = CompletableDeferred<Unit>()
        writes.send(Flush(d))
        d.await()
    }

    // ---- common actions ---------------------------------------------------------------

    fun complete(ids: Collection<String>): List<Item> {
        val now = LocalDateTime.now()
        val ms = nowMs
        return update(ids) { if (it.done) it else Repeats.completeItem(it, now, ms) }
    }

    fun uncomplete(id: String) = update(id) { it.copy(done = false, doneAt = 0) }

    fun snooze(id: String, minutes: Int) = update(id) {
        val t = LocalDateTime.now().plusMinutes(minutes.toLong()).withSecond(0).withNano(0)
        it.copy(snoozedUntil = Due.of(t))
    }

    /** "Tomorrow" from a notification: move the due date, keeping the time of day. */
    fun moveToTomorrow(id: String) = update(id) {
        val tomorrow = LocalDate.now().plusDays(1)
        it.copy(due = Due.of(tomorrow, it.dueTime), snoozedUntil = "")
    }

    fun setDue(ids: Collection<String>, due: String) =
        update(ids) { it.copy(due = due, snoozedUntil = "", done = if (due.isNotEmpty()) false else it.done) }

    fun trash(ids: Collection<String>) = update(ids) { it.copy(trashedAt = nowMs, pinned = false) }
    fun untrash(ids: Collection<String>) = update(ids) { it.copy(trashedAt = 0) }
    fun deleteForever(ids: Collection<String>) = update(ids) { it.copy(deleted = true) }

    fun emptyTrash() {
        val ids = synchronized(lock) { map.values.filter { it.trashed && !it.deleted }.map { it.id } }
        deleteForever(ids)
    }

    private fun purgeTrash() {
        val cutoff = nowMs - TRASH_DAYS * 24 * 3600 * 1000
        val old = synchronized(lock) { map.values.filter { it.trashed && !it.deleted && it.trashedAt < cutoff }.map { it.id } }
        if (old.isNotEmpty()) deleteForever(old)
    }

    fun labels(): List<String> = synchronized(lock) {
        map.values.filter { it.live }.flatMap { it.labels }.distinct().sorted()
    }

    // ---- side effects -------------------------------------------------------------------

    private var sideJob: Job? = null
    private var syncJob: Job? = null

    private fun afterChange(sync: Boolean) {
        sideJob?.cancel()
        sideJob = scope.launch {
            delay(250)
            Reminders.reschedule(app, items.value)
            TodayWidget.refresh(app)
        }
        if (sync) requestSync()
    }

    fun requestSync(delayMs: Long = 1200) {
        if (!settings.current.signedIn) return
        syncJob?.cancel()
        syncJob = scope.launch {
            delay(delayMs)
            runCatching { sync() }
        }
    }

    private val syncMutex = Mutex()

    /** One sync round (SPEC §2). Returns false when it failed. */
    suspend fun sync(): Boolean = syncMutex.withLock {
        val p = settings.current
        if (!p.signedIn) return false
        // Items whose images aren't uploaded yet wait (SPEC §2a: upload first, then push the item).
        val (changes, mark) = synchronized(lock) {
            dirty.keys.mapNotNull { map[it] }.filter { i -> i.images.none { Images.isPending(app, it.id) } } to seq
        }
        if (Images.pendingFiles(app).isNotEmpty()) UploadWorker.enqueue(app)
        _syncing.value = true
        try {
            val resp = Api.sync(p.server, p.token, settings.since, changes)
            val rows = ArrayList<Pair<Item, Long>>()
            synchronized(lock) {
                for (c in changes) {
                    if ((dirty[c.id] ?: 0) <= mark) dirty.remove(c.id)
                }
                for (item in resp.items) {
                    // Edited while the request was in flight: keep the local copy and push it next time.
                    if ((dirty[item.id] ?: 0) > mark) continue
                    dirty.remove(item.id)
                    map[item.id] = item
                    rows += item to 0L
                }
                // Items we pushed that weren't echoed are clean now too.
                for (c in changes) if (c.id !in dirty) rows += (map[c.id] ?: c) to 0L
                emit()
            }
            writes.send(Rows(rows))
            settings.since = resp.rev
            settings.update { it.copy(lastSync = nowMs, syncError = "") }
            runCatching { syncSettings() }
            afterChange(sync = false)
            val more = synchronized(lock) { dirty.keys.any { id -> map[id]?.images?.none { Images.isPending(app, it.id) } == true } }
            if (more) requestSync(500)
            return true
        } catch (e: ApiException) {
            Log.w(TAG, "sync failed: ${e.status} ${e.message}")
            if (e.status == 401) signOut(keepData = true)
            settings.update { it.copy(syncError = e.message ?: "Sync failed") }
            return false
        } catch (e: Exception) {
            Log.w(TAG, "sync failed", e)
            settings.update { it.copy(syncError = e.message ?: e.javaClass.simpleName) }
            return false
        } finally {
            _syncing.value = false
        }
    }

    // ---- settings (SPEC §2c) ------------------------------------------------------------------

    private val settingsMutex = Mutex()

    /** Pushes a pending local all-day time, then adopts the server's settings. */
    suspend fun syncSettings() = settingsMutex.withLock {
        val p = settings.current
        if (!p.signedIn) return@withLock
        var remote = Api.getSettings(p.server, p.token)
        if (settings.allDayDirty) {
            remote.put("allDayTime", "%02d:%02d".format(p.allDayMinutes / 60, p.allDayMinutes % 60))
            remote = Api.putSettings(p.server, p.token, remote)
            settings.allDayDirty = false
        }
        settings.serverSettings = remote.toString()
        val hm = remote.optString("allDayTime", "")
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(hm)
        if (m != null) {
            val minutes = m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
            if (minutes != p.allDayMinutes && minutes in 0 until 24 * 60) {
                settings.update { it.copy(allDayMinutes = minutes) }
                afterChange(sync = false)
            }
        }
    }

    /** The all-day reminder time is shared by every client: change it here and on the server. */
    fun setAllDayTime(minutes: Int) {
        settings.update { it.copy(allDayMinutes = minutes) }
        settings.allDayDirty = true
        afterChange(sync = false)
        if (settings.current.signedIn) scope.launch { runCatching { syncSettings() } }
    }

    // ---- illustrations (SPEC §2d) ----------------------------------------------------------------

    /** Whether the server can paint pictures (it has an OpenAI key), from the cached settings. */
    fun artAvailable(): Boolean =
        runCatching { org.json.JSONObject(settings.serverSettings).optBoolean("artAvailable") }.getOrDefault(false)

    /** Pushes the item, then asks the server to paint it a picture. Returns an error message, or null. */
    suspend fun illustrate(id: String): String? {
        val p = settings.current
        if (!p.signedIn) return "Not connected to a server"
        if (!sync()) return settings.current.syncError.ifEmpty { "Sync failed" }
        return try {
            Api.illustrate(p.server, p.token, id)
            null
        } catch (e: ApiException) {
            e.message
        } catch (e: Exception) {
            "Server not reachable"
        }
    }

    // ---- live updates while the app is in the foreground ----------------------------------

    private var liveJob: Job? = null

    fun startLive() {
        if (liveJob?.isActive == true) return
        liveJob = scope.launch {
            var backoff = 2000L
            while (isActive) {
                val p = settings.current
                if (!p.signedIn) { delay(5000); continue }
                try {
                    sync()
                    Api.events(p.server, p.token) { rev ->
                        if (rev > settings.since) sync()
                    }
                    backoff = 2000
                } catch (e: Exception) {
                    if (e is kotlinx.coroutines.CancellationException) throw e
                    Log.d(TAG, "events: ${e.message}")
                }
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(60_000)
            }
        }
    }

    fun stopLive() {
        liveJob?.cancel()
        liveJob = null
    }

    // ---- account ------------------------------------------------------------------------------

    suspend fun signIn(server: String, username: String, password: String) {
        val s = Api.normalize(server)
        val token = Api.login(s, username.trim(), password, Build.MODEL ?: "Android")
        val switching = settings.current.username.isNotEmpty() && settings.current.username != username.trim() && !settings.current.offline
        if (switching) wipeLocal()
        // Coming from offline mode (or the same account): local items get pushed as changes.
        synchronized(lock) { map.values.forEach { dirty[it.id] = ++seq } }
        settings.since = 0
        settings.update { it.copy(server = s, username = username.trim(), token = token, offline = false, syncError = "") }
        SyncWorker.schedule(app)
        UploadWorker.enqueue(app)
        runCatching { syncSettings() }
        sync()
    }

    fun useOffline() = settings.update { it.copy(offline = true) }

    fun signOut(keepData: Boolean) {
        val p = settings.current
        if (!keepData) scope.launch { Api.logout(p.server, p.token) }
        settings.update { it.copy(token = "") }
        if (!keepData) {
            wipeLocal()
            settings.update { it.copy(server = "", username = "", offline = false) }
        }
        SyncWorker.cancel(app)
    }

    private fun wipeLocal() {
        synchronized(lock) {
            map.clear(); dirty.clear(); emit()
        }
        writes.trySend(Clear)
        settings.since = 0
        afterChange(sync = false)
    }

    /** All-day reminder time as a LocalTime, for the views and reminders. */
    val allDayTime: LocalTime get() = settings.current.allDayTime
}
