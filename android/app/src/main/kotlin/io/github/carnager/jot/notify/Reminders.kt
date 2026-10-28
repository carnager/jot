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
import io.github.carnager.jot.model.Alerts
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
    const val EXTRA_MINUTES = "minutes"
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

    private fun pending(context: Context, id: String, minutes: Int, key: String?): PendingIntent {
        val intent = Intent(context, AlarmReceiver::class.java)
            .setData(Uri.parse("jot://reminder/$id/$minutes"))
            .putExtra(EXTRA_ID, id)
            .putExtra(EXTRA_MINUTES, minutes)
        if (key != null) intent.putExtra(EXTRA_KEY, key)
        return PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** One exact alarm per fire time (SPEC §5); missed ones within 12 h show right away, each at most once. */
    @Synchronized
    fun reschedule(context: Context, items: List<Item>) {
        val am = context.getSystemService(AlarmManager::class.java)
        val settings = Repo.settings
        val allDay = settings.current.allDayTime
        val now = LocalDateTime.now()
        val zone = ZoneId.systemDefault()
        val scheduled = HashSet<String>()
        for (item in items) {
            val fires = Alerts.fireTimes(item, allDay)
            // Past fire times not shown yet: notify once for the latest of them.
            val missed = fires.filter { it.at <= now && !settings.isShown(item.id, it.key) }
            missed.maxByOrNull { it.at }?.let { latest ->
                val shown = latest.at > now.minusHours(MISSED_WINDOW_HOURS) && show(context, item, latest)
                if (shown || latest.at <= now.minusHours(MISSED_WINDOW_HOURS)) missed.forEach { settings.setShown(item.id, it.key) }
            }
            for (f in fires) {
                if (f.at <= now) continue
                val pi = pending(context, item.id, f.minutesBefore, f.key)
                val at = f.at.atZone(zone).toInstant().toEpochMilli()
                if (am.canScheduleExactAlarms()) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
                scheduled += "${item.id}|${f.minutesBefore}"
            }
        }
        for (old in settings.scheduledIds - scheduled) {
            val id = old.substringBefore('|')
            val m = old.substringAfter('|', "0").toIntOrNull() ?: 0
            am.cancel(pending(context, id, m, null))
        }
        settings.scheduledIds = scheduled
        settings.pruneShown()
        // Notifications whose item no longer has a fire time that has passed (completed, rescheduled, snoozed) go away.
        val nm = NotificationManagerCompat.from(context)
        val byId = items.associateBy { it.id }
        for (sbn in context.getSystemService(NotificationManager::class.java).activeNotifications) {
            val id = sbn.tag ?: continue
            val item = byId[id]
            if (item == null || Alerts.fireTimes(item, allDay).none { it.at <= now }) nm.cancel(id, 1)
        }
    }

    fun show(context: Context, item: Item, fire: Alerts.Fire): Boolean {
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
        // An early alert says how long until it's due: "in 30 min · 14:30", "tomorrow 09:00".
        val dueAt = Due.dateTime(item.due) ?: item.dueDate?.atTime(Repo.settings.current.allDayTime)
        val whenText = if (fire.minutesBefore > 0 && dueAt != null) {
            Alerts.leadText(dueAt, LocalDateTime.now()).let { lead -> if (item.dueTime != null && lead.startsWith("in ")) "$lead · " + Fmt.time(item.dueTime!!) else lead }
        } else Fmt.dueShort(item.due)
        val sub = listOfNotNull(whenText.ifEmpty { null }, item.repeat?.let { "↻" }).joinToString(" ")
        val n = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(if (fire.minutesBefore > 0) sub + (if (preview.isNotBlank()) " — $preview" else "") else preview.ifBlank { sub })
            .setSubText(if (fire.minutesBefore > 0) null else sub.ifEmpty { null })
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
        // Only if this is still one of the item's fire times, and it hasn't been shown yet.
        val fire = Alerts.fireTimes(item, Repo.settings.current.allDayTime).firstOrNull { it.key == key } ?: return
        if (Repo.settings.isShown(id, key)) return
        if (Reminders.show(context, item, fire)) Repo.settings.setShown(id, key)
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
