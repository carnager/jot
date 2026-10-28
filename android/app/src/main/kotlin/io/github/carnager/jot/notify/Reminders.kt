package io.github.carnager.jot.notify

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import io.github.carnager.jot.MainActivity
import io.github.carnager.jot.R
import io.github.carnager.jot.data.Repo
import io.github.carnager.jot.data.SyncWorker
import io.github.carnager.jot.model.Due
import io.github.carnager.jot.model.Item
import io.github.carnager.jot.ui.Fmt
import io.github.carnager.jot.widget.TodayWidget
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId

/** Local, exact reminders (SPEC §5). No push service involved. */
object Reminders {
    const val CHANNEL = "reminders"
    const val EXTRA_ID = "id"
    const val EXTRA_KEY = "key"
    private const val GROUP = "jot.reminders"
    /** Missed reminders older than this are skipped silently instead of popping up all at once. */
    private const val MISSED_WINDOW_HOURS = 12L

    fun createChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val ch = NotificationChannel(CHANNEL, context.getString(R.string.channel_reminders), NotificationManager.IMPORTANCE_HIGH)
        ch.description = context.getString(R.string.channel_reminders_desc)
        ch.enableVibration(true)
        nm.createNotificationChannel(ch)
    }

    /** When [item] should notify, or null. */
    fun fireTime(item: Item, allDay: java.time.LocalTime): LocalDateTime? {
        if (!item.remind || item.done || item.archived || !item.live || item.due.isEmpty()) return null
        Due.dateTime(item.snoozedUntil)?.let { return it }
        val date = item.dueDate ?: return null
        return LocalDateTime.of(date, item.dueTime ?: allDay)
    }

    private fun key(t: LocalDateTime) = t.toString()

    private fun pending(context: Context, id: String, key: String?): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setData(Uri.parse("jot://reminder/$id"))
            .putExtra(EXTRA_ID, id)
        if (key != null) intent.putExtra(EXTRA_KEY, key)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    @Synchronized
    fun reschedule(context: Context, items: List<Item>) {
        val am = context.getSystemService(AlarmManager::class.java)
        val settings = Repo.settings
        val allDay = settings.current.allDayTime
        val now = LocalDateTime.now()
        val zone = ZoneId.systemDefault()
        val scheduled = HashSet<String>()
        for (item in items) {
            val t = fireTime(item, allDay) ?: continue
            val k = key(t)
            if (settings.shownKey(item.id) == k) continue
            if (t <= now) {
                // Without permission nothing is marked shown, so it still appears once allowed.
                if (t <= now.minusHours(MISSED_WINDOW_HOURS) || show(context, item, k)) settings.setShown(item.id, k)
                continue
            }
            val at = t.atZone(zone).toInstant().toEpochMilli()
            val pi = pending(context, item.id, k)
            if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
            scheduled += item.id
        }
        for (old in settings.scheduledIds - scheduled) am.cancel(pending(context, old, null))
        settings.scheduledIds = scheduled
        // Notifications for items that were completed or rescheduled elsewhere go away.
        val nm = NotificationManagerCompat.from(context)
        val byId = items.associateBy { it.id }
        for (sbn in context.getSystemService(NotificationManager::class.java).activeNotifications) {
            val id = sbn.tag ?: continue
            val item = byId[id]
            if (item == null || fireTime(item, allDay).let { it == null || it > now }) nm.cancel(id, 1)
        }
    }

    /** Posts the reminder; false when notifications aren't allowed. */
    fun show(context: Context, item: Item, key: String): Boolean {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        val open = PendingIntent.getActivity(
            context, item.id.hashCode(),
            Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN)
                .setData(Uri.parse("jot://item/${item.id}"))
                .putExtra(EXTRA_ID, item.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        fun action(action: String) = PendingIntent.getBroadcast(
            context, 0,
            Intent(context, ActionReceiver::class.java).setAction(action)
                .setData(Uri.parse("jot://action/$action/${item.id}"))
                .putExtra(EXTRA_ID, item.id),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val snooze = Repo.settings.current.snoozeMinutes
        val preview = if (item.isList) item.checks.filter { !it.done }.joinToString(" · ") { it.text } else item.body
        val title = item.title.ifBlank { preview.lineSequence().firstOrNull().orEmpty() }.ifBlank { "Reminder" }
        val sub = listOfNotNull(Fmt.dueShort(item.due).ifEmpty { null }, item.repeat?.let { "↻" }).joinToString(" ")
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(preview.ifBlank { sub })
            .setSubText(sub.ifEmpty { null })
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview.ifBlank { sub }))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setGroup(GROUP)
            .addAction(0, context.getString(R.string.action_done), action(ActionReceiver.DONE))
            .addAction(0, Fmt.snoozeLabel(snooze), action(ActionReceiver.SNOOZE))
            .addAction(0, context.getString(R.string.action_tomorrow), action(ActionReceiver.TOMORROW))
            .build()
        NotificationManagerCompat.from(context).notify(item.id, 1, n)
        return true
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Repo.init(context)
        val id = intent.getStringExtra(Reminders.EXTRA_ID) ?: return
        val key = intent.getStringExtra(Reminders.EXTRA_KEY) ?: return
        val item = Repo.get(id) ?: return
        val t = Reminders.fireTime(item, Repo.settings.current.allDayTime) ?: return
        if (t.toString() != key) return
        if (Reminders.show(context, item, key)) Repo.settings.setShown(id, key)
        TodayWidget.refresh(context)
    }
}

/** Done / Snooze / Tomorrow from the notification, without opening the app. */
class ActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Repo.init(context)
        val id = intent.getStringExtra(Reminders.EXTRA_ID) ?: return
        NotificationManagerCompat.from(context).cancel(id, 1)
        when (intent.action) {
            DONE -> Repo.complete(listOf(id))
            SNOOZE -> Repo.snooze(id, Repo.settings.current.snoozeMinutes)
            TOMORROW -> Repo.moveToTomorrow(id)
            else -> return
        }
        val result = goAsync()
        Repo.scope.launch {
            try {
                Repo.flush()
                Reminders.reschedule(context, Repo.items.value)
                TodayWidget.refresh(context)
                if (Repo.settings.current.signedIn) SyncWorker.soon(context)
            } finally {
                result.finish()
            }
        }
    }

    companion object {
        const val DONE = "io.github.carnager.jot.DONE"
        const val SNOOZE = "io.github.carnager.jot.SNOOZE"
        const val TOMORROW = "io.github.carnager.jot.TOMORROW"
    }
}

/** Boot, clock and time-zone changes, app updates: alarms need to be set again. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Repo.init(context)
        Reminders.reschedule(context, Repo.items.value)
        TodayWidget.refresh(context)
        if (Repo.settings.current.signedIn) SyncWorker.schedule(context)
    }
}
