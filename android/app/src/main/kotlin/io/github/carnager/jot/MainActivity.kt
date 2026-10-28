package io.github.carnager.jot

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import io.github.carnager.jot.data.Repo
import io.github.carnager.jot.data.SyncWorker
import io.github.carnager.jot.notify.Reminders
import io.github.carnager.jot.ui.JotApp
import io.github.carnager.jot.ui.Launch
import io.github.carnager.jot.ui.theme.JotTheme

class JotApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Repo.init(this)
        Reminders.createChannel(this)
        if (Repo.settings.current.signedIn) SyncWorker.schedule(this)
        publishShortcuts()
    }

    private fun publishShortcuts() {
        val sm = getSystemService(ShortcutManager::class.java) ?: return
        fun capture(id: String, label: String, icon: Int, mode: String) = ShortcutInfo.Builder(this, id)
            .setShortLabel(label)
            .setIcon(Icon.createWithResource(this, icon))
            .setIntent(Intent(this, CaptureActivity::class.java).setAction(Intent.ACTION_VIEW).putExtra(CaptureActivity.EXTRA_MODE, mode))
            .build()
        runCatching {
            sm.dynamicShortcuts = listOf(
                capture("new_note", getString(R.string.shortcut_note), R.drawable.ic_shortcut_note, CaptureActivity.MODE_NOTE),
                capture("new_list", getString(R.string.shortcut_list), R.drawable.ic_shortcut_list, CaptureActivity.MODE_LIST),
                capture("new_task", getString(R.string.shortcut_today), R.drawable.ic_shortcut_today, CaptureActivity.MODE_TODAY),
            )
        }
    }
}

class MainActivity : ComponentActivity() {
    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        Reminders.reschedule(this, Repo.items.value)
    }

    private fun requestNotifications() {
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) return
        if (Repo.settings.askedNotifications && !shouldShowRequestPermissionRationale(Manifest.permission.POST_NOTIFICATIONS)) return
        Repo.settings.askedNotifications = true
        notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        handle(intent)
        setContent {
            val prefs by Repo.settings.prefs.collectAsState()
            JotTheme(prefs.theme, prefs.dynamicColor) {
                JotApp(requestNotifications = ::requestNotifications)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        intent ?: return
        intent.getStringExtra(Reminders.EXTRA_ID)?.let { Launch.openId.value = it }
        if (intent.action == ACTION_NEW_LIST) Launch.newList.value = true
    }

    override fun onStart() {
        super.onStart()
        Repo.startLive()
    }

    override fun onStop() {
        super.onStop()
        Repo.stopLive()
    }

    companion object {
        const val ACTION_OPEN = "io.github.carnager.jot.OPEN"
        const val ACTION_NEW_LIST = "io.github.carnager.jot.NEW_LIST"
    }
}
