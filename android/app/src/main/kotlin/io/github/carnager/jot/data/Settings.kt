package io.github.carnager.jot.data

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalTime

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class Prefs(
    val server: String = "",
    val username: String = "",
    val token: String = "",
    val offline: Boolean = false,
    val allDayMinutes: Int = 9 * 60,
    val snoozeMinutes: Int = 60,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = false,
    val notesColumns: Int = 2,
    val lastSync: Long = 0,
    val syncError: String = "",
) {
    val signedIn get() = token.isNotEmpty() && server.isNotEmpty()
    val ready get() = signedIn || offline
    val allDayTime: LocalTime get() = LocalTime.of(allDayMinutes / 60, allDayMinutes % 60)
}

/** App-private preferences, exposed as a flow so Compose recomposes on changes. */
class Settings(context: Context) {
    private val sp: SharedPreferences = context.getSharedPreferences("jot", Context.MODE_PRIVATE)
    private val _prefs = MutableStateFlow(read())
    val prefs: StateFlow<Prefs> = _prefs
    val current: Prefs get() = _prefs.value

    var since: Long
        get() = sp.getLong("since", 0)
        set(v) { sp.edit().putLong("since", v).apply() }

    /** The server's settings object (SPEC §2c), cached for offline use. */
    var serverSettings: String
        get() = sp.getString("serverSettings", "") ?: ""
        set(v) { sp.edit().putString("serverSettings", v).apply() }

    /** The all-day time was changed here while offline and still has to be PUT. */
    var allDayDirty: Boolean
        get() = sp.getBoolean("allDayDirty", false)
        set(v) { sp.edit().putBoolean("allDayDirty", v).apply() }

    var lastView: String
        get() = sp.getString("lastView", "notes") ?: "notes"
        set(v) { sp.edit().putString("lastView", v).apply() }

    var askedNotifications: Boolean
        get() = sp.getBoolean("askedNotifications", false)
        set(v) { sp.edit().putBoolean("askedNotifications", v).apply() }

    private fun read() = Prefs(
        server = sp.getString("server", "") ?: "",
        username = sp.getString("username", "") ?: "",
        token = sp.getString("token", "") ?: "",
        offline = sp.getBoolean("offline", false),
        allDayMinutes = sp.getInt("allDayMinutes", 9 * 60),
        snoozeMinutes = sp.getInt("snoozeMinutes", 60),
        theme = runCatching { ThemeMode.valueOf(sp.getString("theme", "SYSTEM")!!) }.getOrDefault(ThemeMode.SYSTEM),
        dynamicColor = sp.getBoolean("dynamicColor", false),
        notesColumns = sp.getInt("notesColumns", 2),
        lastSync = sp.getLong("lastSync", 0),
        syncError = sp.getString("syncError", "") ?: "",
    )

    fun update(f: (Prefs) -> Prefs) {
        val p = f(_prefs.value)
        sp.edit()
            .putString("server", p.server)
            .putString("username", p.username)
            .putString("token", p.token)
            .putBoolean("offline", p.offline)
            .putInt("allDayMinutes", p.allDayMinutes)
            .putInt("snoozeMinutes", p.snoozeMinutes)
            .putString("theme", p.theme.name)
            .putBoolean("dynamicColor", p.dynamicColor)
            .putInt("notesColumns", p.notesColumns)
            .putLong("lastSync", p.lastSync)
            .putString("syncError", p.syncError)
            .apply()
        _prefs.value = p
    }

    /** Fire times already notified, so each (item, fire time) shows once. */
    private val shownPrefs: SharedPreferences = context.getSharedPreferences("jot-shown", Context.MODE_PRIVATE)
    fun isShown(id: String, key: String): Boolean = shownPrefs.contains("$id|$key")
    fun setShown(id: String, key: String) { shownPrefs.edit().putLong("$id|$key", System.currentTimeMillis()).apply() }

    /** Forgets shown marks older than two weeks. */
    fun pruneShown() {
        val cutoff = System.currentTimeMillis() - 14L * 86_400_000
        val old = shownPrefs.all.filter { (it.value as? Long ?: 0L) < cutoff }.keys
        if (old.isNotEmpty()) shownPrefs.edit().apply { old.forEach { remove(it) } }.apply()
    }

    var scheduledIds: Set<String>
        get() = sp.getStringSet("scheduled", emptySet()) ?: emptySet()
        set(v) { sp.edit().putStringSet("scheduled", v).apply() }
}
