package io.github.carnager.jot.widget

import android.app.Activity
import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.RemoteViews
import android.widget.RemoteViewsService
import android.widget.Toast
import io.github.carnager.jot.CaptureActivity
import io.github.carnager.jot.MainActivity
import io.github.carnager.jot.R
import io.github.carnager.jot.data.Repo
import io.github.carnager.jot.model.Item
import io.github.carnager.jot.notify.Reminders
import io.github.carnager.jot.ui.Fmt
import io.github.carnager.jot.ui.Views
import io.github.carnager.jot.ui.displayTitle

/** Home-screen widget: today's and overdue tasks with tappable checkboxes, plus a + button. */
class TodayWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        Repo.init(context)
        update(context, manager, ids)
    }

    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        update(context, manager, intArrayOf(id))
    }

    companion object {
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, TodayWidget::class.java))
            if (ids.isEmpty()) return
            update(context, manager, ids)
            @Suppress("DEPRECATION")
            manager.notifyAppWidgetViewDataChanged(ids, R.id.widget_list)
        }

        private fun update(context: Context, manager: AppWidgetManager, ids: IntArray) {
            val count = Views.todayCount(Repo.items.value)
            for (id in ids) {
                val views = RemoteViews(context.packageName, R.layout.widget_today)
                views.setTextViewText(R.id.widget_count, if (count > 0) count.toString() else "")
                views.setRemoteAdapter(
                    R.id.widget_list,
                    Intent(context, TodayWidgetService::class.java).setData(Uri.parse("jot://widget/$id")),
                )
                views.setEmptyView(R.id.widget_list, R.id.widget_empty)
                views.setOnClickPendingIntent(
                    R.id.widget_add,
                    PendingIntent.getActivity(
                        context, 1,
                        Intent(context, CaptureActivity::class.java).putExtra(CaptureActivity.EXTRA_MODE, CaptureActivity.MODE_TODAY)
                            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
                views.setOnClickPendingIntent(
                    R.id.widget_title,
                    PendingIntent.getActivity(
                        context, 2, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE,
                    ),
                )
                views.setPendingIntentTemplate(
                    R.id.widget_list,
                    PendingIntent.getActivity(
                        context, 3,
                        Intent(context, WidgetActionActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                    ),
                )
                manager.updateAppWidget(id, views)
            }
        }
    }
}

class TodayWidgetService : RemoteViewsService() {
    override fun onGetViewFactory(intent: Intent): RemoteViewsFactory = Factory(applicationContext)

    private class Factory(val context: Context) : RemoteViewsFactory {
        private var rows: List<Item> = emptyList()
        override fun onCreate() { Repo.init(context) }
        override fun onDataSetChanged() {
            rows = Views.today(Repo.items.value).flatMap { it.items }
        }
        override fun onDestroy() {}
        override fun getCount() = rows.size
        override fun getViewAt(position: Int): RemoteViews {
            val item = rows.getOrNull(position) ?: return RemoteViews(context.packageName, R.layout.widget_row)
            val v = RemoteViews(context.packageName, R.layout.widget_row)
            v.setTextViewText(R.id.row_title, item.displayTitle().ifBlank { "Untitled" })
            val overdue = Fmt.isOverdue(item)
            val sub = when {
                item.dueDate?.let { it < java.time.LocalDate.now() } == true -> Fmt.dueShort(item.due)
                else -> item.dueTime?.let { Fmt.time(it) } ?: ""
            }
            v.setTextViewText(R.id.row_sub, sub)
            v.setTextColor(R.id.row_sub, context.getColor(if (overdue) R.color.widget_alert else R.color.widget_sub))
            v.setImageViewResource(R.id.row_check, if (overdue) R.drawable.widget_check_alert else R.drawable.widget_check)
            v.setOnClickFillInIntent(R.id.row_check, Intent().putExtra(WidgetActionActivity.EXTRA_ACTION, "complete").putExtra(Reminders.EXTRA_ID, item.id))
            v.setOnClickFillInIntent(R.id.row_root, Intent().putExtra(WidgetActionActivity.EXTRA_ACTION, "open").putExtra(Reminders.EXTRA_ID, item.id))
            return v
        }
        override fun getLoadingView(): RemoteViews? = null
        override fun getViewTypeCount() = 1
        override fun getItemId(position: Int) = rows.getOrNull(position)?.id?.hashCode()?.toLong() ?: position.toLong()
        override fun hasStableIds() = true
    }
}

/** Invisible trampoline for widget row taps (activities may always start from a widget tap). */
class WidgetActionActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Repo.init(this)
        val id = intent.getStringExtra(Reminders.EXTRA_ID)
        when (intent.getStringExtra(EXTRA_ACTION)) {
            "complete" -> if (id != null) {
                Repo.complete(listOf(id))
                val after = Repo.get(id)
                val msg = if (after != null && !after.done) "Next: " + Fmt.dueShort(after.due) else "Completed"
                Toast.makeText(this, msg, Toast.LENGTH_SHORT).show()
                TodayWidget.refresh(this)
            }
            "open" -> startActivity(
                Intent(this, MainActivity::class.java).putExtra(Reminders.EXTRA_ID, id)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            )
        }
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_ACTION = "action"
    }
}
