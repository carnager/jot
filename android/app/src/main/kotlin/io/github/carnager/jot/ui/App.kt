package io.github.carnager.jot.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import io.github.carnager.jot.data.Repo
import io.github.carnager.jot.model.Item
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** Requests from outside the composition: notifications, shortcuts, widget. */
object Launch {
    val openId = MutableStateFlow<String?>(null)
    val newList = MutableStateFlow(false)
}

/** Actions with an Undo snackbar, shared by every screen. */
class AppActions(
    val snackbar: SnackbarHostState,
    private val scope: CoroutineScope,
    val open: (String) -> Unit,
) {
    fun message(text: String, action: String? = null, onAction: () -> Unit = {}) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val r = snackbar.showSnackbar(text, action, withDismissAction = false, duration = SnackbarDuration.Short)
            if (r == SnackbarResult.ActionPerformed) onAction()
        }
    }

    fun undoable(text: String, previous: List<Item>) {
        if (previous.isEmpty()) return
        message(text, "Undo") { Repo.restore(previous) }
    }

    fun complete(ids: Collection<String>) {
        val prev = Repo.complete(ids)
        val after = ids.mapNotNull { Repo.get(it) }
        val text = when {
            after.size == 1 && !after[0].done -> "Next: " + Fmt.dueShort(after[0].due)
            after.size > 1 -> "${after.size} completed"
            else -> "Completed"
        }
        undoable(text, prev)
    }

    fun archive(ids: Collection<String>, archive: Boolean = true) {
        val prev = Repo.update(ids) { it.copy(archived = archive, pinned = if (archive) false else it.pinned) }
        undoable(if (archive) (if (ids.size > 1) "${ids.size} archived" else "Archived") else "Unarchived", prev)
    }

    fun trash(ids: Collection<String>) {
        val prev = Repo.trash(ids)
        undoable(if (ids.size > 1) "${ids.size} moved to trash" else "Moved to trash", prev)
    }

    fun setDue(ids: Collection<String>, due: String) {
        val prev = Repo.setDue(ids, due)
        undoable(if (due.isEmpty()) "Date removed" else "Scheduled · " + Fmt.dueShort(due), prev)
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun JotApp(requestNotifications: () -> Unit) {
    val prefs by Repo.settings.prefs.collectAsState()
    if (!prefs.ready) {
        LoginScreen()
        return
    }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var editor by rememberSaveable { mutableStateOf<String?>(null) }
    var editorFocus by rememberSaveable { mutableStateOf(false) }
    var settingsOpen by rememberSaveable { mutableStateOf(false) }
    val actions = remember { AppActions(snackbar, scope) { id -> editorFocus = false; editor = id } }
    fun edit(id: String, focus: Boolean) { editorFocus = focus; editor = id }

    val openId by Launch.openId.collectAsState()
    LaunchedEffect(openId) {
        val id = openId ?: return@LaunchedEffect
        Launch.openId.value = null
        if (Repo.get(id) != null) edit(id, false)
    }
    val newList by Launch.newList.collectAsState()
    LaunchedEffect(newList) {
        if (!newList) return@LaunchedEffect
        Launch.newList.value = false
        val item = Item.new().copy(kind = "list")
        Repo.save(item)
        edit(item.id, true)
    }

    // Ask for notification permission the first time something has a reminder.
    val items by Repo.items.collectAsState()
    LaunchedEffect(items.any { it.due.isNotEmpty() && it.remind && !it.done && it.live }) {
        if (items.any { it.due.isNotEmpty() && it.remind && !it.done && it.live }) requestNotifications()
    }

    val holder = rememberSaveableStateHolder()
    val viewer = remember { ImageViewerState() }
    Box(Modifier.fillMaxSize()) {
        CompositionLocalProvider(LocalImageViewer provides viewer) {
        SharedTransitionLayout {
            AnimatedContent(
                targetState = editor,
                transitionSpec = {
                    (fadeIn(Motion.medium()) togetherWith fadeOut(Motion.fast())).using(SizeTransform(clip = false))
                },
                label = "editor",
            ) { id ->
                CompositionLocalProvider(LocalSharedScope provides this@SharedTransitionLayout, LocalAnimScope provides this) {
                    if (id == null) {
                        holder.SaveableStateProvider("main") {
                            MainScreen(
                                actions = actions,
                                onEdit = { i, focus -> edit(i, focus) },
                                onSettings = { settingsOpen = true },
                            )
                        }
                    } else {
                        Editor(id, actions, editorFocus) { editor = null }
                    }
                }
            }
            CompositionLocalProvider(LocalSharedScope provides this@SharedTransitionLayout) {
                ImageViewer(viewer)
            }
        }
        }
        AnimatedVisibility(
            visible = settingsOpen,
            enter = fadeIn() + slideInHorizontally { it / 6 },
            exit = fadeOut() + slideOutHorizontally { it / 6 },
        ) {
            SettingsScreen(onBack = { settingsOpen = false }, requestNotifications = requestNotifications)
        }
    }
}
