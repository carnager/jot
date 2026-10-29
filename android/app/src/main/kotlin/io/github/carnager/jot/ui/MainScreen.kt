package io.github.carnager.jot.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.runtime.key
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.zIndex
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridItemSpan
import androidx.compose.foundation.lazy.staggeredgrid.rememberLazyStaggeredGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DeleteForever
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.RestoreFromTrash
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.TaskAlt
import androidx.compose.material.icons.rounded.Today
import androidx.compose.material.icons.rounded.Unarchive
import androidx.compose.material.icons.outlined.ViewAgenda
import androidx.compose.material.icons.rounded.WbSunny
import androidx.compose.material.icons.rounded.WbTwilight
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDrawerState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.carnager.jot.data.Repo
import io.github.carnager.jot.model.Item
import io.github.carnager.jot.ui.theme.LocalDark
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val MAIN_VIEWS = listOf(Views.TODAY, Views.UPCOMING, Views.NOTES)
private const val OTHER_PAGE = "other"
private const val FADE_OUT = 90f
private const val FADE_IN = 165f
private const val FADE_TOTAL = FADE_OUT + FADE_IN

private data class ContentKey(val view: String, val label: String?, val search: Boolean, val empty: Boolean)

private data class Dest(val view: String, val title: String, val icon: ImageVector)

private val DESTS = listOf(
    Dest(Views.TODAY, "Today", Icons.Rounded.WbSunny),
    Dest(Views.UPCOMING, "Upcoming", Icons.Rounded.CalendarMonth),
    Dest(Views.NOTES, "Notes", Icons.Rounded.Lightbulb),
)

private fun titleFor(view: String, label: String?) = when (view) {
    Views.TODAY -> "Today"
    Views.UPCOMING -> "Upcoming"
    Views.LABEL -> "#${label ?: ""}"
    Views.DONE -> "Done"
    Views.ARCHIVE -> "Archive"
    Views.TRASH -> "Trash"
    else -> "Notes"
}

/** Everything the content composables need to act on items. */
private class ListCallbacks(
    val selection: Set<String>,
    val open: (Item) -> Unit,
    val select: (String) -> Unit,
    val toggle: (Item) -> Unit,
    val toggleCheck: (Item, String) -> Unit,
    val schedule: (Item) -> Unit,
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MainScreen(actions: AppActions, onEdit: (String, Boolean) -> Unit, onSettings: () -> Unit) {
    val items by Repo.items.collectAsState()
    val prefs by Repo.settings.prefs.collectAsState()
    val syncing by Repo.syncing.collectAsState()
    var view by rememberSaveable { mutableStateOf(Repo.settings.lastView) }
    var label by rememberSaveable { mutableStateOf<String?>(null) }
    var query by rememberSaveable { mutableStateOf<String?>(null) }
    var selection by remember { mutableStateOf(setOf<String>()) }
    var scheduleFor by remember { mutableStateOf<List<String>?>(null) }
    var labelsFor by remember { mutableStateOf<List<String>?>(null) }
    var colorFor by remember { mutableStateOf<List<String>?>(null) }
    var collapsed by remember { mutableStateOf(false) }
    var dayKey by rememberSaveable { mutableStateOf<String?>(null) }
    val selectedDay = dayKey?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
    var bottomPx by remember { mutableIntStateOf(0) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val captureFocus = remember { FocusRequester() }
    val density = LocalDensity.current
    val dark = LocalDark.current
    val scheme = MaterialTheme.colorScheme

    fun go(v: String, l: String? = null) {
        view = v; label = l; selection = emptySet(); query = null; collapsed = false; dayKey = null
        if (v in listOf(Views.TODAY, Views.UPCOMING, Views.NOTES)) Repo.settings.lastView = v
        scope.launch { drawer.close() }
    }
    LaunchedEffect(items) {
        // Selected items that vanished (synced away, completed) leave the selection.
        val live = items.filter { it.live || view == Views.TRASH }.map { it.id }.toSet()
        if (selection.any { it !in live }) selection = selection.filter { it in live }.toSet()
    }

    BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
    BackHandler(!drawer.isOpen && selection.isNotEmpty()) { selection = emptySet() }
    BackHandler(!drawer.isOpen && selection.isEmpty() && query != null) { query = null }
    BackHandler(!drawer.isOpen && selection.isEmpty() && query == null && view !in listOf(Views.TODAY, Views.UPCOMING, Views.NOTES)) { go(Views.NOTES) }

    val labels = remember(items) { items.filter { it.live }.flatMap { it.labels }.groupingBy { it }.eachCount().toSortedMap() }
    val todayCount = remember(items) { Views.todayCount(items) }
    val sections = remember(items, view, label, query) {
        val q = query
        if (q != null) Views.search(items, q) else Views.sections(view, label, items)
    }
    val cb = ListCallbacks(
        selection = selection,
        open = { item -> if (selection.isNotEmpty()) selection = if (item.id in selection) selection - item.id else selection + item.id else onEdit(item.id, false) },
        select = { id -> selection = if (id in selection) selection - id else selection + id },
        toggle = { item -> if (item.done) Repo.uncomplete(item.id) else actions.complete(listOf(item.id)) },
        toggleCheck = { item, checkId -> Repo.update(item.id) { it.copy(checks = it.checks.map { c -> if (c.id == checkId) c.copy(done = !c.done) else c }) } },
        schedule = { item -> scheduleFor = listOf(item.id) },
    )
    val bottomPad: Dp = with(density) { bottomPx.toDp() } + 8.dp
    val imeVisible = WindowInsets.isImeVisible

    ModalNavigationDrawer(
        drawerState = drawer,
        // Opened from the menu button only: a stray right-swipe shouldn't fight swipe-to-complete.
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            Drawer(view, label, todayCount, labels, onGo = ::go, onSettings = { scope.launch { drawer.close() }; onSettings() })
        },
    ) {
        Surface(color = scheme.surface, modifier = Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().drawBehind {
                // A faint warm glow from the top corner keeps the dark theme from feeling flat.
                drawRect(
                    Brush.radialGradient(
                        listOf(scheme.primary.copy(alpha = if (dark) 0.11f else 0.10f), Color.Transparent),
                        center = Offset(size.width * 0.9f, -size.width * 0.15f),
                        radius = size.width * 1.1f,
                    ),
                )
            },
        ) {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.statusBarsPadding()) {
                    AnimatedContent(
                        targetState = when {
                            selection.isNotEmpty() -> 1
                            query != null -> 2
                            else -> 0
                        },
                        transitionSpec = { fadeIn(Motion.fast()) togetherWith fadeOut(Motion.fast()) },
                        label = "topbar",
                    ) { mode ->
                        when (mode) {
                            1 -> SelectionBar(
                                count = selection.size,
                                selected = items.filter { it.id in selection },
                                view = view,
                                onClose = { selection = emptySet() },
                                onPin = {
                                    val pin = !items.filter { it.id in selection }.all { it.pinned }
                                    Repo.update(selection) { it.copy(pinned = pin) }
                                    selection = emptySet()
                                },
                                onDate = { scheduleFor = selection.toList() },
                                onLabels = { labelsFor = selection.toList() },
                                onColor = { colorFor = selection.toList() },
                                onArchive = { actions.archive(selection, view != Views.ARCHIVE); selection = emptySet() },
                                onTrash = { actions.trash(selection); selection = emptySet() },
                                onRestore = { Repo.untrash(selection); selection = emptySet() },
                                onDeleteForever = { Repo.deleteForever(selection); selection = emptySet() },
                                onComplete = { actions.complete(selection); selection = emptySet() },
                                onSelectAll = { selection = sections.flatMap { s -> s.items.map { it.id } }.toSet() },
                            )
                            2 -> SearchBar(query ?: "", { query = it }, onClose = { query = null })
                            else -> TopRow(
                                collapsedTitle = if (collapsed) titleFor(view, label) else null,
                                syncing = syncing, signedIn = prefs.signedIn, error = prefs.syncError,
                                gridToggle = !Views.isTaskView(view), columns = prefs.notesColumns,
                                onMenu = { scope.launch { drawer.open() } },
                                onSearch = { query = "" },
                                onSync = { Repo.requestSync(0) },
                                onColumns = { Repo.settings.update { p -> p.copy(notesColumns = if (p.notesColumns == 1) 2 else 1) } },
                            )
                        }
                    }
                }
                PullToRefreshBox(
                    isRefreshing = syncing && prefs.signedIn,
                    onRefresh = { Repo.requestSync(0) },
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) {
                    val empty = sections.all { it.items.isEmpty() }
                    val renderPage: @Composable (ContentKey) -> Unit = { key ->
                        // Each page derives its own sections: while animating out, a page must not see the new view's data.
                        val sections = remember(items, key, query) {
                            if (key.search) Views.search(items, query ?: "") else Views.sections(key.view, key.label, items)
                        }
                        val onCollapse: (Boolean) -> Unit = { collapsed = it }
                        when {
                            key.search -> GridContent(
                                title = "Search", subtitle = if ((query ?: "").isBlank()) "Type to search everything" else "${sections.sumOf { it.items.size }} results",
                                sections = sections, columns = prefs.notesColumns, cb = cb, bottom = bottomPad, onCollapse = onCollapse,
                                empty = { EmptyState(Illustration.SEARCH, if ((query ?: "").isBlank()) "Search everything" else "Nothing found", "Titles, notes, list items and labels.") },
                            )
                            key.view == Views.TODAY -> TodayContent(items, sections, cb, bottomPad, onCollapse)
                            key.view == Views.UPCOMING -> UpcomingContent(
                                items, sections, cb, bottomPad, selectedDay,
                                onSelectDay = { d -> dayKey = d?.toString() },
                                onMove = { item, day ->
                                    val prev = Repo.update(item.id) { it.copy(due = io.github.carnager.jot.model.Due.of(day, it.dueTime), snoozedUntil = "", done = false) }
                                    actions.undoable("Moved to " + day.format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())), listOfNotNull(prev))
                                },
                            )
                            key.view == Views.DONE -> TaskListContent(
                                "Done", "Completed tasks, newest first", sections, cb, bottomPad, onCollapse, swipe = false,
                                empty = { EmptyState(Illustration.DONE, "Nothing done yet", "Completed tasks land here.") },
                            )
                            else -> GridContent(
                                title = titleFor(key.view, key.label),
                                subtitle = gridSubtitle(key.view, sections),
                                sections = sections, columns = prefs.notesColumns, cb = cb, bottom = bottomPad, onCollapse = onCollapse,
                                trailing = if (key.view == Views.TRASH && !key.empty) ({ TextButton(onClick = { Repo.emptyTrash() }) { Text("Empty") } }) else null,
                                empty = {
                                    when (key.view) {
                                        Views.ARCHIVE -> EmptyState(Illustration.ARCHIVE, "Archive is empty", "Archived notes stay out of the way here.")
                                        Views.TRASH -> EmptyState(Illustration.TRASH, "Trash is empty", "Deleted items stay here for 30 days.")
                                        Views.LABEL -> EmptyState(Illustration.NOTES, "Nothing tagged yet", "Add #${key.label} while typing to file things here.")
                                        else -> EmptyState(Illustration.NOTES, "A clean slate", "Type below and press Enter. Try “Shopping: milk, eggs” or “idea #work”.")
                                    }
                                },
                            )
                        }
                    }
                    // Today, Upcoming and Notes stay composed once visited, so switching tabs reuses pages that
                    // already exist (no grid re-measure, no image reloads). Other views share one more page.
                    //
                    // Fade-through, never a crossfade: the outgoing page stays fully opaque underneath while the
                    // incoming one fades in on top as one composited layer (opaque background included). A
                    // symmetric crossfade showed both dense pages half-transparent at once, which read as flicker.
                    val pageId = if (query == null && view in MAIN_VIEWS) view else OTHER_PAGE
                    val otherKey = ContentKey(view, label, query != null, empty)
                    var lastOther by remember { mutableStateOf(otherKey) }
                    if (pageId == OTHER_PAGE) lastOther = otherKey
                    var visited by remember { mutableStateOf(setOf<String>()) }
                    if (pageId !in visited) visited = visited + pageId
                    // Material fade-through: the outgoing page fades out to the shared surface (FADE_OUT ms), then
                    // the incoming one fades and scales in (FADE_IN ms). The two are never drawn in the same frame.
                    var under by remember { mutableStateOf<String?>(null) }
                    var shown by remember { mutableStateOf(pageId) }
                    var t by remember { androidx.compose.runtime.mutableFloatStateOf(FADE_TOTAL) }
                    var outFrom by remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
                    var inFrom by remember { androidx.compose.runtime.mutableFloatStateOf(0f) }
                    var run by remember { androidx.compose.runtime.mutableIntStateOf(0) }
                    fun outAlpha() = outFrom * (1f - androidx.compose.animation.core.FastOutLinearInEasing.transform((t / FADE_OUT).coerceIn(0f, 1f)))
                    fun inProgress() = androidx.compose.animation.core.LinearOutSlowInEasing.transform(((t - FADE_OUT) / FADE_IN).coerceIn(0f, 1f))
                    fun inAlpha() = inFrom + (1f - inFrom) * inProgress()
                    if (pageId != shown) {
                        // Switching (also mid-animation): whatever is visible right now fades out from where it is.
                        val outgoingVisible = under != null && t < FADE_OUT
                        val visiblePage = if (outgoingVisible) under!! else shown
                        val visibleAlpha = if (outgoingVisible) outAlpha() else if (t < FADE_TOTAL) inAlpha() else 1f
                        if (visiblePage == pageId) {
                            // Back to the page that is still (partly) visible: just fade it in from there.
                            under = null; inFrom = visibleAlpha; t = FADE_OUT
                        } else {
                            under = visiblePage; outFrom = visibleAlpha; inFrom = 0f; t = 0f
                        }
                        shown = pageId; run++
                    }
                    LaunchedEffect(run) {
                        if (run == 0) return@LaunchedEffect
                        val start = t
                        androidx.compose.animation.core.animate(start, FADE_TOTAL, animationSpec = androidx.compose.animation.core.tween((FADE_TOTAL - start).toInt(), easing = androidx.compose.animation.core.LinearEasing)) { v, _ -> t = v }
                        under = null
                    }
                    var pageTop by remember { mutableStateOf(0f) }
                    val glow = scheme.primary.copy(alpha = if (dark) 0.11f else 0.10f)
                    val pageBg = scheme.surface
                    Box(Modifier.fillMaxSize().onGloballyPositioned { pageTop = it.positionInRoot().y }) {
                        (MAIN_VIEWS + OTHER_PAGE).forEach { v ->
                            if (v in visited) key(v) {
                                val active = v == pageId
                                val outgoing = v == under && t < FADE_OUT
                                val incoming = active && t >= FADE_OUT
                                val settled = active && t >= FADE_TOTAL
                                val drawn = outgoing || incoming || settled || (active && under == null && t >= FADE_OUT)
                                Box(
                                    Modifier.fillMaxSize().zIndex(if (active) 1f else 0f)
                                        .graphicsLayer {
                                            // One offscreen layer per page, so a page fades as a whole.
                                            compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen
                                            when {
                                                outgoing -> { alpha = outAlpha(); scaleX = 1f; scaleY = 1f }
                                                incoming -> { alpha = inAlpha(); val sc = 0.96f + 0.04f * inProgress(); scaleX = sc; scaleY = sc }
                                                else -> { alpha = 1f; scaleX = 1f; scaleY = 1f }
                                            }
                                        }
                                        // Pages that are neither shown nor fading out aren't drawn at all.
                                        .drawWithContent { if (drawn) drawContent() }
                                        // Opaque, with this page's slice of the screen-wide warm glow (no seam): a page
                                        // fading out reveals exactly the same surface it sits on.
                                        .drawBehind {
                                            drawRect(pageBg)
                                            drawRect(
                                                Brush.radialGradient(
                                                    listOf(glow, Color.Transparent),
                                                    center = Offset(size.width * 0.9f, -size.width * 0.15f - pageTop),
                                                    radius = size.width * 1.1f,
                                                ),
                                            )
                                        }
                                        .then(if (active) Modifier else Modifier.clearAndSetSemantics { }),
                                ) {
                                    // Only the active page joins the card↔editor transition: the same item on two
                                    // pages would be two matches for one shared key, which never settles.
                                    val shared = LocalSharedScope.current
                                    androidx.compose.runtime.CompositionLocalProvider(LocalSharedScope provides if (active) shared else null) {
                                        if (v == OTHER_PAGE) {
                                            // Label → label, Done, Archive, Trash, search: the same fade-through, never both at once.
                                            AnimatedContent(
                                                targetState = lastOther,
                                                transitionSpec = {
                                                    (fadeIn(androidx.compose.animation.core.tween(FADE_IN.toInt(), delayMillis = FADE_OUT.toInt(), easing = androidx.compose.animation.core.LinearOutSlowInEasing)) +
                                                        androidx.compose.animation.scaleIn(androidx.compose.animation.core.tween(FADE_IN.toInt(), delayMillis = FADE_OUT.toInt(), easing = androidx.compose.animation.core.LinearOutSlowInEasing), initialScale = 0.96f)) togetherWith
                                                        fadeOut(androidx.compose.animation.core.tween(FADE_OUT.toInt(), easing = androidx.compose.animation.core.FastOutLinearInEasing))
                                                },
                                                label = "content",
                                            ) { k ->
                                                Box(Modifier.fillMaxSize().drawBehind {
                                                    drawRect(pageBg)
                                                    drawRect(Brush.radialGradient(listOf(glow, Color.Transparent), center = Offset(size.width * 0.9f, -size.width * 0.15f - pageTop), radius = size.width * 1.1f))
                                                }) { renderPage(k) }
                                            }
                                        } else renderPage(ContentKey(v, null, false, false))
                                    }
                                    if (!active) Box(Modifier.matchParentSize().pointerInput(Unit) {
                                        awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
                                    })
                                }
                            }
                        }
                    }
                }
            }

            // Bottom: a soft fade, the floating capture pill and the navigation bar.
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().imePadding().onSizeChanged { bottomPx = it.height },
            ) {
                AnimatedVisibility(
                    selection.isEmpty() && view != Views.TRASH,
                    enter = fadeIn(Motion.fast()) + slideInVertically(Motion.medium()) { it / 2 },
                    exit = fadeOut(Motion.fast()) + slideOutVertically(Motion.medium()) { it / 2 },
                ) {
                    val ctx = when {
                        query != null -> CaptureContext(hint = "Add a note…")
                        view == Views.TODAY -> CaptureContext(dueToday = true, hint = "Add a task for today…")
                        view == Views.UPCOMING && selectedDay != null ->
                            CaptureContext(day = selectedDay, hint = "Add to " + selectedDay.format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())) + "…")
                        view == Views.UPCOMING -> CaptureContext(hint = "Add a task… “fri 9am”")
                        view == Views.LABEL -> CaptureContext(label = label, hint = "Add to #$label…")
                        else -> CaptureContext(hint = "Take a note or task…")
                    }
                    Box(
                        Modifier.fillMaxWidth()
                            .background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to scheme.surface.copy(alpha = 0.92f), 1f to scheme.surface))
                            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 10.dp),
                    ) {
                        CaptureField(
                            ctx,
                            focusRequester = captureFocus,
                            onSubmit = { item ->
                                Repo.save(item)
                                if (!visibleIn(item, view, label, selectedDay)) {
                                    val where = if (item.due.isNotEmpty()) Fmt.dueShort(item.due) else "Notes"
                                    actions.message("Added · $where", "Open") { onEdit(item.id, false) }
                                }
                            },
                            onExpand = { item -> Repo.save(item); onEdit(item.id, true) },
                        )
                    }
                }
                AnimatedVisibility(!imeVisible, enter = expandVertically(Motion.fast()) + fadeIn(Motion.fast()), exit = shrinkVertically(Motion.fast()) + fadeOut(Motion.fast())) {
                    BottomNav(view, todayCount, onSelect = { go(it) })
                }
            }
            SnackbarHost(actions.snackbar, Modifier.align(Alignment.BottomCenter).padding(bottom = bottomPad))
        }
        }
    }

    scheduleFor?.let { ids ->
        val targets = items.filter { it.id in ids }
        RescheduleSheet(
            targets,
            onDismiss = { scheduleFor = null },
            onDue = { due -> actions.setDue(ids, due); selection = emptySet() },
            onRepeat = if (ids.size == 1) { r -> Repo.update(ids) { it.copy(repeat = r, due = it.due.ifEmpty { LocalDate.now().toString() }) } } else null,
            onRemind = if (ids.size == 1) { on -> Repo.update(ids) { it.copy(remind = on) } } else null,
            onAlerts = if (ids.size == 1) { a -> Repo.update(ids) { it.copy(alerts = a) } } else null,
        )
    }
    labelsFor?.let { ids ->
        val targets = items.filter { it.id in ids }
        val common = targets.map { it.labels.toSet() }.reduceOrNull { a, b -> a intersect b } ?: emptySet()
        LabelDialog(labels.keys.toList(), common, onDismiss = { labelsFor = null }) { chosen ->
            val removed = common - chosen
            Repo.update(ids) { (it.labels.toSet() - removed + chosen).sorted().let { l -> it.copy(labels = l) } }
            labelsFor = null; selection = emptySet()
        }
    }
    colorFor?.let { ids ->
        ColorSheet(items.firstOrNull { it.id in ids }?.color ?: "", onDismiss = { colorFor = null }) { c ->
            Repo.update(ids) { it.copy(color = c) }; selection = emptySet()
        }
    }
}

private fun gridSubtitle(view: String, sections: List<Views.Section>): String {
    val n = sections.sumOf { it.items.size }
    val pinned = sections.firstOrNull { it.title == "Pinned" }?.items?.size ?: 0
    return when (view) {
        Views.TRASH -> "Deleted after 30 days"
        Views.ARCHIVE -> if (n == 1) "1 archived note" else "$n archived notes"
        else -> (if (n == 1) "1 note" else "$n notes") + if (pinned > 0) " · $pinned pinned" else ""
    }
}

private fun visibleIn(item: Item, view: String, label: String?, day: LocalDate?): Boolean {
    val today = LocalDate.now()
    if (view == Views.UPCOMING && day != null) return item.dueDate == day
    return when (view) {
        Views.TODAY -> item.dueDate?.let { it <= today } == true
        Views.UPCOMING -> item.dueDate?.let { it > today } == true
        Views.LABEL -> label in item.labels
        Views.NOTES -> true
        else -> false
    }
}

// ---- chrome --------------------------------------------------------------------------------------

@Composable
private fun TopRow(
    collapsedTitle: String?,
    syncing: Boolean, signedIn: Boolean, error: String, gridToggle: Boolean, columns: Int,
    onMenu: () -> Unit, onSearch: () -> Unit, onSync: () -> Unit, onColumns: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onMenu) { Icon(Icons.Outlined.Menu, "Menu") }
        CollapsedTitle(collapsedTitle, Modifier.weight(1f))
        IconButton(onClick = onSearch) { Icon(Icons.Outlined.Search, "Search") }
        if (gridToggle) IconButton(onClick = onColumns) {
            Icon(if (columns == 1) Icons.Outlined.GridView else Icons.Outlined.ViewAgenda, if (columns == 1) "Grid view" else "List view")
        }
        if (signedIn) IconButton(onClick = onSync) {
            when {
                syncing -> CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                error.isNotEmpty() -> Icon(Icons.Outlined.CloudOff, "Sync problem: $error", tint = MaterialTheme.colorScheme.error)
                else -> Icon(Icons.Outlined.CloudDone, "Synced", tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun CollapsedTitle(text: String?, modifier: Modifier) {
    Box(modifier) {
        AnimatedVisibility(text != null, enter = fadeIn(Motion.fast()) + slideInVertically(Motion.fast()) { it / 2 }, exit = fadeOut(Motion.fast())) {
            Text(text ?: "", style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 4.dp))
        }
    }
}

@Composable
private fun SearchBar(query: String, onChange: (String) -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Surface(
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp).height(52.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Close search") }
            BasicTextField(
                value = query, onValueChange = onChange, singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                modifier = Modifier.weight(1f).focusRequester(focus),
                decorationBox = { inner ->
                    if (query.isEmpty()) Text("Search notes, tasks, labels", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
                    inner()
                },
            )
            if (query.isNotEmpty()) IconButton(onClick = { onChange("") }) { Icon(Icons.Rounded.Close, "Clear") }
        }
    }
}

@Composable
private fun BottomNav(view: String, todayCount: Int, onSelect: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    NavigationBar(
        containerColor = scheme.surfaceContainer,
        tonalElevation = 0.dp,
        windowInsets = WindowInsets.navigationBars,
        modifier = Modifier.height(72.dp + with(LocalDensity.current) { WindowInsets.navigationBars.getBottom(this).toDp() }),
    ) {
        DESTS.forEach { d ->
            NavigationBarItem(
                selected = view == d.view,
                onClick = { onSelect(d.view) },
                icon = {
                    BadgedBox(badge = { if (d.view == Views.TODAY && todayCount > 0) Badge(containerColor = scheme.primary, contentColor = scheme.onPrimary) { Text("$todayCount") } }) {
                        Icon(d.icon, null)
                    }
                },
                label = { Text(d.title, style = MaterialTheme.typography.labelMedium) },
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = scheme.primaryContainer,
                    selectedIconColor = scheme.onPrimaryContainer,
                    selectedTextColor = scheme.onSurface,
                    unselectedIconColor = scheme.onSurfaceVariant,
                    unselectedTextColor = scheme.onSurfaceVariant,
                ),
            )
        }
    }
}

@Composable
private fun SelectionBar(
    count: Int, selected: List<Item>, view: String,
    onClose: () -> Unit, onPin: () -> Unit, onDate: () -> Unit, onLabels: () -> Unit, onColor: () -> Unit,
    onArchive: () -> Unit, onTrash: () -> Unit, onRestore: () -> Unit, onDeleteForever: () -> Unit,
    onComplete: () -> Unit, onSelectAll: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Surface(
        shape = RoundedCornerShape(28.dp), color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(52.dp).padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.Close, "Clear selection") }
            AnimatedContent(count, label = "count") { n ->
                Text("$n selected", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp))
            }
            Spacer(Modifier.weight(1f))
            if (view == Views.TRASH) {
                IconButton(onClick = onRestore) { Icon(Icons.Rounded.RestoreFromTrash, "Restore") }
                IconButton(onClick = onDeleteForever) { Icon(Icons.Rounded.DeleteForever, "Delete forever") }
                return@Row
            }
            IconButton(onClick = onPin) { Icon(Icons.Rounded.PushPin, if (selected.all { it.pinned }) "Unpin" else "Pin") }
            IconButton(onClick = onDate) { Icon(Icons.Rounded.Event, "Schedule") }
            IconButton(onClick = onLabels) { Icon(Icons.AutoMirrored.Rounded.Label, "Labels") }
            IconButton(onClick = onArchive) { Icon(if (view == Views.ARCHIVE) Icons.Rounded.Unarchive else Icons.Rounded.Archive, "Archive") }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                DropdownMenu(menu, { menu = false }) {
                    DropdownMenuItem(text = { Text("Complete") }, leadingIcon = { Icon(Icons.Rounded.TaskAlt, null) }, onClick = { menu = false; onComplete() })
                    DropdownMenuItem(text = { Text("Colour") }, leadingIcon = { Icon(Icons.Rounded.Palette, null) }, onClick = { menu = false; onColor() })
                    DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; onTrash() })
                    DropdownMenuItem(text = { Text("Select all") }, leadingIcon = { Icon(Icons.Rounded.Check, null) }, onClick = { menu = false; onSelectAll() })
                }
            }
        }
    }
}

@Composable
private fun Drawer(
    view: String, label: String?, todayCount: Int, labels: Map<String, Int>,
    onGo: (String, String?) -> Unit, onSettings: () -> Unit,
) {
    ModalDrawerSheet(drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 12.dp)) {
            item {
                Row(Modifier.padding(horizontal = 28.dp, vertical = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                    BrandMark(36.dp)
                    Spacer(Modifier.width(12.dp))
                    Text("Jot", style = MaterialTheme.typography.headlineMedium)
                }
            }
            item { DrawerEntry(Icons.Rounded.Today, "Today", view == Views.TODAY, todayCount.takeIf { it > 0 }?.toString()) { onGo(Views.TODAY, null) } }
            item { DrawerEntry(Icons.Rounded.CalendarMonth, "Upcoming", view == Views.UPCOMING, null) { onGo(Views.UPCOMING, null) } }
            item { DrawerEntry(Icons.Rounded.Lightbulb, "Notes", view == Views.NOTES, null) { onGo(Views.NOTES, null) } }
            if (labels.isNotEmpty()) {
                item {
                    HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant)
                    Text("Labels", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp))
                }
                items(labels.entries.toList(), key = { it.key }) { (l, n) ->
                    DrawerEntry(Icons.AutoMirrored.Rounded.Label, l, view == Views.LABEL && label == l, n.toString()) { onGo(Views.LABEL, l) }
                }
            }
            item { HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant) }
            item { DrawerEntry(Icons.Rounded.CheckCircle, "Done", view == Views.DONE, null) { onGo(Views.DONE, null) } }
            item { DrawerEntry(Icons.Rounded.Archive, "Archive", view == Views.ARCHIVE, null) { onGo(Views.ARCHIVE, null) } }
            item { DrawerEntry(Icons.Rounded.Delete, "Trash", view == Views.TRASH, null) { onGo(Views.TRASH, null) } }
            item { HorizontalDivider(Modifier.padding(horizontal = 28.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.outlineVariant) }
            item { DrawerEntry(Icons.Rounded.Settings, "Settings", false, null, onSettings) }
        }
    }
}

@Composable
private fun DrawerEntry(icon: ImageVector, text: String, selected: Boolean, badge: String?, onClick: () -> Unit) {
    NavigationDrawerItem(
        icon = { Icon(icon, null) },
        label = { Text(text, style = MaterialTheme.typography.labelLarge) },
        selected = selected,
        onClick = onClick,
        badge = badge?.let { { Text(it, style = MaterialTheme.typography.labelMedium) } },
        colors = NavigationDrawerItemDefaults.colors(selectedContainerColor = MaterialTheme.colorScheme.primaryContainer),
        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding),
    )
}

// ---- content -------------------------------------------------------------------------------------

private fun sectionIcon(key: String): ImageVector? = when (key) {
    "overdue" -> Icons.Rounded.ErrorOutline
    "allday" -> Icons.Rounded.Today
    "morning" -> Icons.Rounded.WbTwilight
    "afternoon" -> Icons.Rounded.WbSunny
    "evening" -> Icons.Rounded.Bedtime
    else -> null
}

@Composable
private fun TodayContent(items: List<Item>, sections: List<Views.Section>, cb: ListCallbacks, bottom: Dp, onCollapse: (Boolean) -> Unit) {
    val state = rememberLazyListState()
    LaunchedEffect(state) {
        snapshotFlow { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 120 }.collect(onCollapse)
    }
    val (done, total) = remember(items) { Views.todayProgress(items) }
    val today = LocalDate.now()
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = bottom)) {
        item("header") {
            ScreenHeader(
                "Today",
                Fmt.longDate(today),
                trailing = if (total > 0) ({ ProgressRing(done, total) }) else null,
            )
        }
        if (sections.isEmpty()) item("empty") {
            if (total > 0) EmptyState(Illustration.ALL_CLEAR, "All clear", "Everything for today is done. Enjoy the quiet.")
            else EmptyState(Illustration.ALL_CLEAR, "Nothing planned", "Type below: “call Anna 5pm”, “gym every mon 7am”.")
        }
        taskSections(sections, cb, swipe = true, showDate = { s -> s.alert })
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
private fun androidx.compose.foundation.lazy.LazyListScope.taskSections(
    sections: List<Views.Section>, cb: ListCallbacks, swipe: Boolean, showDate: (Views.Section) -> Boolean, sticky: Boolean = false,
    drag: DayDragState? = null, onMove: ((Item, LocalDate) -> Unit)? = null,
) {
    sections.forEach { s ->
        if (s.title != null) {
            val header: @Composable () -> Unit = {
                SectionHeader(s.title, s.items.size, s.alert, sectionIcon(s.key), Modifier.background(if (sticky) MaterialTheme.colorScheme.surface else Color.Transparent))
            }
            if (sticky) stickyHeader(key = "h:" + s.key) { header() } else item(key = "h:" + s.key) { Box(Modifier.animateItem()) { header() } }
        }
        items(s.items, key = { it.id }) { item ->
            Box(Modifier.animateItem().padding(horizontal = 16.dp, vertical = 4.dp)) {
                SwipeRow(item, swipe && cb.selection.isEmpty(), cb) {
                    if (drag != null && onMove != null) TaskRow(
                        item, selected = item.id in cb.selection,
                        showDate = showDate(s),
                        onToggle = { cb.toggle(item) },
                        onClick = { if (drag.clickAllowed()) cb.open(item) },
                        onLongClick = null,
                        modifier = Modifier.dragToDay(item, drag, onSelect = { cb.select(item.id) }, onDrop = onMove),
                    ) else TaskRow(
                        item, selected = item.id in cb.selection,
                        showDate = showDate(s),
                        onToggle = { cb.toggle(item) },
                        onClick = { cb.open(item) },
                        onLongClick = { cb.select(item.id) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SwipeRow(item: Item, enabled: Boolean, cb: ListCallbacks, content: @Composable () -> Unit) {
    val state = rememberSwipeToDismissBoxState()
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    SwipeToDismissBox(
        state = state,
        modifier = Modifier.clip(RowShape),
        enableDismissFromStartToEnd = enabled,
        enableDismissFromEndToStart = enabled,
        gesturesEnabled = enabled,
        onDismiss = { value ->
            when (value) {
                // The row already slid away; complete at once, and only snap back if it stays listed.
                SwipeToDismissBoxValue.StartToEnd -> {
                    cb.toggle(item)
                    scope.launch { Motion.wait(ctx, 400); state.reset() }
                }
                SwipeToDismissBoxValue.EndToStart -> {
                    cb.schedule(item)
                    scope.launch { state.reset() }
                }
                else -> scope.launch { state.reset() }
            }
        },
        backgroundContent = {
            val dir = state.dismissDirection
            val scheme = MaterialTheme.colorScheme
            val bg by animateColorAsState(
                when (dir) {
                    SwipeToDismissBoxValue.StartToEnd -> scheme.primary
                    SwipeToDismissBoxValue.EndToStart -> scheme.tertiary
                    else -> Color.Transparent
                }, Motion.fast(), label = "swipeBg",
            )
            val fg = if (dir == SwipeToDismissBoxValue.EndToStart) scheme.onTertiary else scheme.onPrimary
            Row(
                Modifier.fillMaxSize().clip(RowShape).background(bg).padding(horizontal = 24.dp),
                horizontalArrangement = if (dir == SwipeToDismissBoxValue.EndToStart) Arrangement.End else Arrangement.Start,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (dir == SwipeToDismissBoxValue.StartToEnd) {
                    Icon(Icons.Rounded.Check, null, tint = fg); Spacer(Modifier.width(8.dp))
                    Text(if (item.repeat != null) "Done · next" else "Done", color = fg, style = MaterialTheme.typography.labelLarge)
                } else if (dir == SwipeToDismissBoxValue.EndToStart) {
                    Text("Schedule", color = fg, style = MaterialTheme.typography.labelLarge); Spacer(Modifier.width(8.dp))
                    Icon(Icons.Rounded.Event, null, tint = fg)
                }
            }
        },
    ) { content() }
}

@Composable
private fun TaskListContent(
    title: String, subtitle: String, sections: List<Views.Section>, cb: ListCallbacks, bottom: Dp,
    onCollapse: (Boolean) -> Unit, swipe: Boolean, empty: @Composable () -> Unit,
) {
    val state = rememberLazyListState()
    LaunchedEffect(state) { snapshotFlow { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 120 }.collect(onCollapse) }
    LazyColumn(Modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(bottom = bottom)) {
        item("header") { ScreenHeader(title, subtitle) }
        if (sections.all { it.items.isEmpty() }) item("empty") { empty() }
        taskSections(sections, cb, swipe, showDate = { true })
    }
}

/**
 * Upcoming: a day strip on top. Tapping a day filters the list to it (tap again or "Show all"
 * to clear); dragging a task onto a day reschedules it.
 */
@Composable
private fun UpcomingContent(
    items: List<Item>, sections: List<Views.Section>, cb: ListCallbacks, bottom: Dp,
    selectedDay: LocalDate?, onSelectDay: (LocalDate?) -> Unit, onMove: (Item, LocalDate) -> Unit,
) {
    val today = LocalDate.now()
    val counts = remember(items) { Views.upcomingCounts(items) }
    val total = sections.sumOf { it.items.size }
    val drag = remember { DayDragState() }
    var rootOffset by remember { mutableStateOf(Offset.Zero) }
    var rootWidth by remember { mutableIntStateOf(0) }
    val lastDue = remember(items) { items.filter { it.live && !it.done && !it.archived }.mapNotNull { it.dueDate }.maxOrNull() }
    val days = remember(today, lastDue) {
        val end = maxOf(today.plusDays(20), minOf(lastDue ?: today, today.plusDays(365)))
        generateSequence(today) { it.plusDays(1) }.takeWhile { it <= end }.toList()
    }
    Box(Modifier.fillMaxSize().onGloballyPositioned { rootOffset = it.localToRoot(Offset.Zero); rootWidth = it.size.width }) {
        Column(Modifier.fillMaxSize()) {
            ScreenHeader(
                "Upcoming",
                when {
                    drag.active -> "Drop on a day to move it"
                    total == 0 -> "Nothing scheduled"
                    total == 1 -> "1 task ahead"
                    else -> "$total tasks ahead"
                },
            )
            WeekStrip(days, today, counts, selectedDay, drag) { d -> onSelectDay(if (d == selectedDay) null else d) }
            AnimatedContent(
                targetState = selectedDay,
                transitionSpec = { (fadeIn(Motion.medium()) + slideInVertically(Motion.medium()) { it / 16 }) togetherWith fadeOut(Motion.fast()) },
                label = "dayFilter",
            ) { day ->
                if (day == null) {
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom)) {
                        item("top") { Spacer(Modifier.height(4.dp)) }
                        if (sections.isEmpty()) item("empty") {
                            EmptyState(Illustration.CALENDAR, "Nothing scheduled", "Add a date while typing: “tomorrow”, “fri 9am”, “24.12.”")
                        }
                        taskSections(sections, cb, swipe = true, showDate = { false }, sticky = true, drag = drag, onMove = onMove)
                    }
                } else {
                    val dayItems = remember(items, day) {
                        items.filter { it.live && !it.archived && !it.done && it.dueDate == day }
                            .sortedWith(compareBy<Item>({ it.dueTime != null }, { it.dueTime }, { -it.sort }))
                    }
                    val name = day.format(DateTimeFormatter.ofPattern("EEEE, d MMM", Locale.getDefault()))
                    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = bottom)) {
                        item("dayHeader") {
                            Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 12.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    name + " · " + (if (dayItems.size == 1) "1 task" else "${dayItems.size} tasks"),
                                    style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f),
                                )
                                TextButton(onClick = { onSelectDay(null) }) { Text("Show all") }
                            }
                        }
                        if (dayItems.isEmpty()) item("dayEmpty") {
                            val weekday = day.format(DateTimeFormatter.ofPattern("EEEE", Locale.getDefault()))
                            EmptyState(Illustration.CALENDAR, "Nothing planned", "Add something for $weekday below, or drag a task onto its day.")
                        }
                        taskSections(listOf(Views.Section(null, dayItems, key = day.toString())), cb, swipe = true, showDate = { false }, drag = drag, onMove = onMove)
                    }
                }
            }
        }
        // A compact chip rides above the finger, so the day pills stay visible while aiming.
        val dragged = drag.item
        if (dragged != null && drag.active) {
            val density = LocalDensity.current
            var chipW by remember { mutableIntStateOf(0) }
            val lift = with(density) { 72.dp.toPx() }
            val margin = with(density) { 12.dp.toPx() }
            val raw = drag.pointer - rootOffset - Offset(chipW / 2f, lift)
            val pos = Offset(raw.x.coerceIn(margin, maxOf(margin, rootWidth - chipW - margin)), raw.y)
            val hover = drag.hover
            Surface(
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.inverseSurface,
                contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                shadowElevation = 12.dp,
                modifier = Modifier
                    .offset { androidx.compose.ui.unit.IntOffset(pos.x.toInt(), pos.y.toInt()) }
                    .onSizeChanged { chipW = it.width }
                    .widthIn(max = 280.dp),
            ) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(checkTint(dragged)))
                    Spacer(Modifier.width(8.dp))
                    Text(dragged.displayTitle().ifBlank { "Untitled" }, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    AnimatedVisibility(hover != null) {
                        Text(
                            " → " + (hover?.format(DateTimeFormatter.ofPattern("EEE d", Locale.getDefault())) ?: ""),
                            style = MaterialTheme.typography.labelLarge, color = io.github.carnager.jot.ui.theme.MarigoldBright,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun WeekStrip(
    days: List<LocalDate>, today: LocalDate, counts: Map<LocalDate, Int>, selected: LocalDate?, drag: DayDragState, onPick: (LocalDate) -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val dark = LocalDark.current
    val brand = if (dark) io.github.carnager.jot.ui.theme.MarigoldBright else io.github.carnager.jot.ui.theme.Marigold
    val dayName = remember { DateTimeFormatter.ofPattern("EEE", Locale.getDefault()) }
    val month = remember { DateTimeFormatter.ofPattern("MMM", Locale.getDefault()) }
    val pulse = rememberInfiniteTransition(label = "pulse")
    val pulseScale by pulse.animateFloat(1.04f, 1.12f, infiniteRepeatable(tween(420), RepeatMode.Reverse), label = "pulseScale")
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
    ) {
        items(days, key = { it.toString() }) { d ->
            val isToday = d == today
            val isSelected = d == selected
            val isTarget = drag.hover == d
            val bg by animateColorAsState(
                when {
                    isTarget -> scheme.tertiaryContainer
                    isSelected -> brand
                    dark -> scheme.surfaceContainerLow
                    else -> scheme.surfaceContainerLowest
                }, Motion.fast(), label = "dayBg",
            )
            val fg = when {
                isTarget -> scheme.onTertiaryContainer
                isSelected -> Color(0xFF2B1F00)
                isToday -> scheme.primary
                else -> scheme.onSurface
            }
            val border = when {
                isSelected || isTarget -> null
                isToday -> androidx.compose.foundation.BorderStroke(2.dp, brand)
                else -> androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = if (dark) 0.5f else 0.8f))
            }
            val selScale by androidx.compose.animation.core.animateFloatAsState(if (isSelected) 1.06f else 1f, androidx.compose.animation.core.spring(dampingRatio = 0.6f), label = "selScale")
            val n = counts[d] ?: 0
            DisposableEffect(d) { onDispose { drag.pills.remove(d) } }
            Column(
                Modifier
                    .onGloballyPositioned { drag.pills[d] = it.boundsInRoot() }
                    .graphicsLayer { val sc = if (isTarget) pulseScale else selScale; scaleX = sc; scaleY = sc }
                    .widthIn(min = 54.dp).clip(RoundedCornerShape(20.dp)).background(bg)
                    .then(if (border != null) Modifier.border(border, RoundedCornerShape(20.dp)) else Modifier)
                    .clickable(onClickLabel = if (isSelected) "Show all days" else "Show only this day") { onPick(d) }
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    if (d.dayOfMonth == 1 && !isToday) d.format(month) else if (isToday) "Today" else d.format(dayName),
                    style = MaterialTheme.typography.labelSmall, color = fg.copy(alpha = 0.85f),
                )
                Text("${d.dayOfMonth}", style = MaterialTheme.typography.titleLarge, color = fg)
                Row(Modifier.height(8.dp).padding(top = 3.dp), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    repeat(minOf(n, 3)) {
                        Box(Modifier.size(5.dp).clip(CircleShape).background(if (isSelected) Color(0xFF2B1F00) else brand))
                    }
                }
            }
        }
    }
}

@Composable
private fun GridContent(
    title: String, subtitle: String, sections: List<Views.Section>, columns: Int, cb: ListCallbacks, bottom: Dp,
    onCollapse: (Boolean) -> Unit, trailing: (@Composable () -> Unit)? = null, empty: @Composable () -> Unit,
) {
    val state = rememberLazyStaggeredGridState()
    LaunchedEffect(state) { snapshotFlow { state.firstVisibleItemIndex > 0 || state.firstVisibleItemScrollOffset > 120 }.collect(onCollapse) }
    LazyVerticalStaggeredGrid(
        columns = StaggeredGridCells.Fixed(columns),
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = bottom),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalItemSpacing = 10.dp,
    ) {
        item(key = "header", span = StaggeredGridItemSpan.FullLine) {
            ScreenHeader(title, subtitle, trailing = trailing, inset = 12.dp)
        }
        if (sections.all { it.items.isEmpty() }) item(key = "empty", span = StaggeredGridItemSpan.FullLine) { empty() }
        sections.forEach { s ->
            if (s.title != null && s.items.isNotEmpty()) item(key = "h:" + s.key, span = StaggeredGridItemSpan.FullLine) {
                Box(Modifier.animateItem()) {
                    SectionHeader(
                        s.title, s.items.size, icon = if (s.title == "Pinned") Icons.Rounded.PushPin else null,
                        inset = 12.dp,
                    )
                }
            }
            items(s.items.size, key = { s.items[it].id }, contentType = { if (s.items[it].images.isNotEmpty()) "image-card" else "card" }) { i ->
                val item = s.items[i]
                Box(Modifier.animateItem()) {
                    NoteCard(
                        item, selected = item.id in cb.selection,
                        onClick = { cb.open(item) },
                        onLongClick = { cb.select(item.id) },
                        onToggleCheck = { cb.toggleCheck(item, it) },
                        onToggleDone = { cb.toggle(item) },
                    )
                }
            }
        }
    }
}
