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
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.network.CacheStrategy
import coil3.network.NetworkRequest
import coil3.network.NetworkResponse
import coil3.request.Options
import coil3.gif.AnimatedImageDecoder
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import io.github.carnager.jot.data.Api
import io.github.carnager.jot.data.Repo
import io.github.carnager.jot.ui.Motion
import okio.Path.Companion.toOkioPath
import io.github.carnager.jot.data.SyncWorker
import io.github.carnager.jot.notify.Reminders
import io.github.carnager.jot.ui.JotApp
import io.github.carnager.jot.ui.Launch
import io.github.carnager.jot.ui.theme.JotTheme

/**
 * Coil's default strategy also caches error bodies, so a 404 (image not uploaded yet by another
 * device) would stick. Only successful responses are cached; anything else is fetched again.
 */
@OptIn(coil3.annotation.ExperimentalCoilApi::class)
private object SuccessOnlyCache : CacheStrategy {
    override suspend fun read(cacheResponse: NetworkResponse, networkRequest: NetworkRequest, options: Options): CacheStrategy.ReadResult =
        if (cacheResponse.code in 200..299) CacheStrategy.DEFAULT.read(cacheResponse, networkRequest, options)
        else CacheStrategy.ReadResult(networkRequest)

    override suspend fun write(cacheResponse: NetworkResponse?, networkRequest: NetworkRequest, networkResponse: NetworkResponse, options: Options): CacheStrategy.WriteResult =
        if (networkResponse.code in 200..299) CacheStrategy.DEFAULT.write(cacheResponse, networkRequest, networkResponse, options)
        else CacheStrategy.WriteResult.DISABLED
}

class JotApplication : Application(), SingletonImageLoader.Factory {
    /** Coil, on our OkHttp client: requests to the Jot server carry the bearer token. */
    override fun newImageLoader(context: PlatformContext): ImageLoader {
        val client = Api.client.newBuilder().addInterceptor { chain ->
            val req = chain.request()
            val p = Repo.settings.current
            val ours = p.signedIn && req.url.toString().startsWith(p.server + "/api/attachments/")
            chain.proceed(if (ours) req.newBuilder().header("Authorization", "Bearer ${p.token}").build() else req)
        }.build()
        return ImageLoader.Builder(context)
            .components {
                add(OkHttpNetworkFetcherFactory(callFactory = { client }, cacheStrategy = { SuccessOnlyCache }))
                add(AnimatedImageDecoder.Factory())
            }
            .crossfade(Motion.MEDIUM)
            .diskCache { DiskCache.Builder().directory(context.cacheDir.resolve("image-cache").toOkioPath()).maxSizeBytes(300L * 1024 * 1024).build() }
            .build()
    }

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
