package io.github.carnager.jot.ui

import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.rounded.Block
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.Label
import androidx.compose.material.icons.automirrored.outlined.Notes
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.outlined.Archive
import androidx.compose.material.icons.rounded.CheckBox
import androidx.compose.material.icons.rounded.CheckBoxOutlineBlank
import androidx.compose.material.icons.outlined.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DragIndicator
import androidx.compose.material.icons.outlined.Event
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.outlined.Unarchive
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.PushPin
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import io.github.carnager.jot.data.Repo
import io.github.carnager.jot.model.Check
import io.github.carnager.jot.model.Ids
import io.github.carnager.jot.model.Item
import io.github.carnager.jot.model.Repeats
import io.github.carnager.jot.ui.theme.LocalDark
import io.github.carnager.jot.ui.theme.NoteColors

private class CheckState(val id: String, text: String, done: Boolean, indent: Int) {
    var text by mutableStateOf(TextFieldValue(text, TextRange(text.length)))
    var done by mutableStateOf(done)
    var indent by mutableStateOf(indent)
    /** The IME may re-send the text with the newline we already split on; ignore that echo. */
    var lastSplit: String? = null
    fun toCheck() = Check(id, text.text, done, indent)
}

/** Full-screen editor. Every change is saved immediately; there is no save button. */
@Composable
fun Editor(id: String, actions: AppActions, focusTitle: Boolean, onBack: () -> Unit) {
    val all by Repo.items.collectAsState()
    val item = all.firstOrNull { it.id == id } ?: run {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val dark = LocalDark.current
    val accent = NoteColors.accent(item.color, dark)
    val ctx = LocalContext.current

    var titleSplit by remember(id) { mutableStateOf<String?>(null) }
    var title by remember(id) { mutableStateOf(TextFieldValue(item.title, TextRange(item.title.length))) }
    var body by remember(id) { mutableStateOf(TextFieldValue(item.body, TextRange(item.body.length))) }
    val checks = remember(id) { mutableStateListOf<CheckState>().apply { addAll(item.checks.map { CheckState(it.id, it.text, it.done, it.indent) }) } }
    val focus = remember(id) { mutableStateMapOf<String, FocusRequester>() }
    var focusNext by remember { mutableStateOf<String?>(null) }
    val titleFocus = remember { FocusRequester() }
    val bodyFocus = remember { FocusRequester() }
    var showChecked by remember { mutableStateOf(false) }
    var sheet by remember { mutableStateOf<String?>(null) }
    var menu by remember { mutableStateOf(false) }
    var palette by remember { mutableStateOf(false) }

    fun save(f: (Item) -> Item) { Repo.update(id, f) }
    fun saveChecks() = save { it.copy(checks = checks.map(CheckState::toCheck)) }
    fun close() {
        val cur = Repo.get(id)
        if (cur != null && cur.isEmpty() && !cur.deleted) Repo.deleteForever(listOf(id))
        else if (cur != null && cur.checks.any { it.text.isBlank() }) Repo.update(id) { it.copy(checks = it.checks.filter { c -> c.text.isNotBlank() }) }
        onBack()
    }

    BackHandler { close() }
    LaunchedEffect(id) {
        if (focusTitle) runCatching {
            if (item.isList && item.title.isNotEmpty()) {
                if (checks.isEmpty()) {
                    val c = CheckState(Ids.short(), "", false, 0); checks.add(c); focusNext = c.id
                }
            } else titleFocus.requestFocus()
        }
    }
    LaunchedEffect(focusNext) {
        val f = focusNext ?: return@LaunchedEffect
        androidx.compose.runtime.withFrameNanos { }
        runCatching { focus[f]?.requestFocus() }
        focusNext = null
    }

    Surface(color = MaterialTheme.colorScheme.surface, modifier = Modifier.fillMaxSize().sharedItem(id)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            // Top bar
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { close() }) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back") }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { palette = !palette }, modifier = Modifier.semantics { contentDescription = "Colour" }) {
                    val ring = MaterialTheme.colorScheme.outline
                    Box(
                        Modifier.size(20.dp).clip(CircleShape)
                            .background(accent ?: Color.Transparent)
                            .border(if (accent == null) 1.5.dp else 0.dp, if (accent == null) ring else Color.Transparent, CircleShape),
                        contentAlignment = Alignment.Center,
                    ) { if (accent == null) Icon(Icons.Outlined.Palette, null, Modifier.size(12.dp), tint = ring) }
                }
                IconButton(onClick = { save { it.copy(pinned = !it.pinned) } }) {
                    Icon(
                        if (item.pinned) Icons.Filled.PushPin else Icons.Outlined.PushPin, if (item.pinned) "Unpin" else "Pin",
                        tint = if (item.pinned) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = {
                    actions.archive(listOf(id), !item.archived); onBack()
                }) { Icon(if (item.archived) Icons.Outlined.Unarchive else Icons.Outlined.Archive, if (item.archived) "Unarchive" else "Archive") }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, "More") }
                    DropdownMenu(menu, { menu = false }) {
                        DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = {
                            menu = false; actions.trash(listOf(id)); onBack()
                        })
                        DropdownMenuItem(text = { Text("Make a copy") }, leadingIcon = { Icon(Icons.Rounded.ContentCopy, null) }, onClick = {
                            menu = false
                            val copy = Item.new().copy(
                                kind = item.kind, title = item.title, body = item.body, color = item.color, labels = item.labels,
                                checks = item.checks.map { it.copy(id = Ids.short()) },
                            )
                            Repo.save(copy); actions.open(copy.id)
                        })
                        DropdownMenuItem(text = { Text("Share") }, leadingIcon = { Icon(Icons.Rounded.Share, null) }, onClick = {
                            menu = false
                            val text = buildString {
                                if (item.title.isNotBlank()) append(item.title).append("\n\n")
                                if (item.isList) item.checks.forEach { append(if (it.done) "☑ " else "☐ ").append(it.text).append('\n') }
                                else append(item.body)
                            }.trim()
                            ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text), null))
                        })
                    }
                }
            }

            // Content
            Column(
                Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.isTask) {
                        RoundCheck(
                            item.done,
                            onToggle = { if (item.done) Repo.uncomplete(id) else actions.complete(listOf(id)) },
                            modifier = Modifier.padding(end = 2.dp),
                            tint = checkTint(item),
                        )
                    }
                    BasicTextField(
                        value = title,
                        onValueChange = { v ->
                            if (v.text.contains('\n') && v.text == titleSplit) {
                                // IME echo of an Enter we already handled.
                            } else if (v.text.contains('\n')) {
                                titleSplit = v.text
                                // Enter in the title moves on to the body or first check.
                                title = v.copy(text = v.text.replace("\n", ""))
                                if (item.isList) {
                                    val c = CheckState(Ids.short(), "", false, 0); checks.add(0, c); saveChecks(); focusNext = c.id
                                } else runCatching { bodyFocus.requestFocus() }
                            } else title = v
                            save { it.copy(title = title.text) }
                        },
                        modifier = Modifier.weight(1f).padding(vertical = 8.dp).focusRequester(titleFocus),
                        textStyle = MaterialTheme.typography.headlineMedium.copy(
                            color = MaterialTheme.colorScheme.onSurface,
                            textDecoration = if (item.done) TextDecoration.LineThrough else null,
                        ),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        decorationBox = { inner ->
                            if (title.text.isEmpty()) Text("Title", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f))
                            inner()
                        },
                    )
                }
                MetaChips(item, onDate = { sheet = "date" }, onRepeat = { sheet = "repeat" }, onLabels = { sheet = "labels" })
                Spacer(Modifier.height(16.dp))

                if (!item.isList) {
                    BasicTextField(
                        value = body,
                        onValueChange = { body = it; save { i -> i.copy(body = it.text) } },
                        modifier = Modifier.fillMaxWidth().padding(bottom = 120.dp).focusRequester(bodyFocus),
                        textStyle = MaterialTheme.typography.bodyLarge.copy(color = MaterialTheme.colorScheme.onSurface),
                        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                        decorationBox = { inner ->
                            if (body.text.isEmpty()) Text("Note", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                            inner()
                        },
                    )
                } else {
                    ChecklistEditor(checks, focus, showChecked, { showChecked = !showChecked }, onChanged = { saveChecks() }, onFocus = { focusNext = it })
                }
            }

            // Floating toolbar; the palette unfolds inside it.
            Surface(
                shape = RoundedCornerShape(28.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
                shadowElevation = 8.dp,
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            ) {
                Column(Modifier.animateContentSize(Motion.medium())) {
                    AnimatedVisibility(palette, enter = expandVertically(Motion.medium()) + fadeIn(Motion.fast()), exit = shrinkVertically(Motion.fast()) + fadeOut(Motion.fast())) {
                        PaletteRow(item.color) { c -> save { it.copy(color = c) } }
                    }
                    Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { palette = !palette }) {
                            Icon(Icons.Outlined.Palette, "Colour", tint = if (palette) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        IconButton(onClick = { sheet = "labels" }) { Icon(Icons.AutoMirrored.Outlined.Label, "Labels", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        IconButton(onClick = { sheet = "date" }) { Icon(Icons.Outlined.Event, "Date", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        IconButton(onClick = { sheet = "repeat" }) { Icon(Icons.Outlined.Repeat, "Repeat", tint = MaterialTheme.colorScheme.onSurfaceVariant) }
                        IconButton(onClick = {
                            if (item.isList) {
                                val text = checks.joinToString("\n") { it.text.text }
                                body = TextFieldValue(text)
                                save { it.copy(kind = "note", body = text, checks = emptyList()) }
                                checks.clear()
                            } else {
                                val lines = body.text.lines().filter { it.isNotBlank() }
                                checks.clear(); checks.addAll(lines.map { CheckState(Ids.short(), it.trim().removePrefix("- ").removePrefix("* "), false, 0) })
                                if (checks.isEmpty()) checks.add(CheckState(Ids.short(), "", false, 0))
                                save { it.copy(kind = "list", body = "", checks = checks.map(CheckState::toCheck)) }
                                focusNext = checks.last().id
                            }
                        }) {
                            Icon(
                                if (item.isList) Icons.AutoMirrored.Outlined.Notes else Icons.Outlined.Checklist,
                                if (item.isList) "Convert to note" else "Convert to list", tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.weight(1f))
                        Text(Fmt.edited(item.updatedAt), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(end = 14.dp))
                    }
                }
            }
        }
    }

    when (sheet) {
        "date" -> RescheduleSheet(
            listOf(item), onDismiss = { sheet = null },
            onDue = { d -> Repo.setDue(listOf(id), d) },
            onRepeat = { r -> save { it.copy(repeat = r, due = if (r != null && it.due.isEmpty()) java.time.LocalDate.now().toString() else it.due) } },
            onRemind = { on -> save { it.copy(remind = on) } },
        )
        "repeat" -> RepeatDialog(item.repeat, item.due, onDismiss = { sheet = null }) { r ->
            sheet = null
            save {
                val due = if (r != null && it.due.isEmpty()) Repeats.firstFrom(java.time.LocalDateTime.now(), r, null) else it.due
                it.copy(repeat = r, due = due)
            }
        }
        "labels" -> LabelDialog(Repo.labels(), item.labels.toSet(), onDismiss = { sheet = null }) { set ->
            sheet = null; save { it.copy(labels = set.sorted()) }
        }
    }
}

/** Date, repeat and labels as one clean row; empty slots show as quiet "add" chips. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun MetaChips(item: Item, onDate: () -> Unit, onRepeat: () -> Unit, onLabels: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 4.dp).animateContentSize(Motion.medium()),
    ) {
        if (item.due.isNotEmpty()) MetaPill(Icons.Outlined.Event, Fmt.dueShort(item.due), filled = true, alert = Fmt.isOverdue(item), onClick = onDate)
        else MetaPill(Icons.Outlined.Event, "Add date", filled = false, onClick = onDate)
        if (item.repeat != null) MetaPill(Icons.Outlined.Repeat, Repeats.describe(item.repeat), filled = true, onClick = onRepeat)
        item.labels.forEach { l -> MetaPill(null, "#$l", filled = true, tint = scheme.primary, onClick = onLabels) }
        if (item.labels.isEmpty()) MetaPill(Icons.Rounded.Add, "Label", filled = false, onClick = onLabels)
    }
}

@Composable
private fun MetaPill(icon: ImageVector?, text: String, filled: Boolean, alert: Boolean = false, tint: Color? = null, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val fg = when {
        alert -> scheme.error
        tint != null -> tint
        filled -> scheme.onSurface
        else -> scheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(50),
        color = when {
            tint != null -> tint.copy(alpha = 0.12f)
            filled -> scheme.surfaceContainerHigh
            else -> Color.Transparent
        },
        border = if (filled) null else androidx.compose.foundation.BorderStroke(1.dp, scheme.outlineVariant),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, Modifier.size(16.dp), tint = fg)
                Spacer(Modifier.width(6.dp))
            }
            Text(text, style = MaterialTheme.typography.labelLarge, color = fg)
        }
    }
}

/** Colour dots that pop in one after another. */
@Composable
private fun PaletteRow(selected: String, onPick: (String) -> Unit) {
    val dark = LocalDark.current
    val scheme = MaterialTheme.colorScheme
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 4.dp),
    ) {
        itemsIndexed(NoteColors.names) { i, name ->
            val c = NoteColors.accent(name, dark)
            val appear = remember { androidx.compose.animation.core.Animatable(0f) }
            LaunchedEffect(Unit) {
                kotlinx.coroutines.delay(i * 22L)
                appear.animateTo(1f, androidx.compose.animation.core.spring(dampingRatio = 0.55f, stiffness = 500f))
            }
            val sel = name == selected
            val ring by androidx.compose.animation.core.animateDpAsState(if (sel) 3.dp else 0.dp, label = "ring")
            Box(
                Modifier.size(36.dp).graphicsLayer { scaleX = appear.value; scaleY = appear.value }
                    .clip(CircleShape)
                    .border(ring, scheme.onSurface, CircleShape)
                    .padding(if (sel) 5.dp else 0.dp)
                    .clip(CircleShape)
                    .background(c ?: scheme.surfaceContainerHighest)
                    .clickable { onPick(name) }
                    .semantics { contentDescription = if (name.isEmpty()) "No colour" else name },
                contentAlignment = Alignment.Center,
            ) {
                if (name.isEmpty()) Icon(Icons.Rounded.Block, null, Modifier.size(16.dp), tint = scheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ChecklistEditor(
    checks: MutableList<CheckState>,
    focus: MutableMap<String, FocusRequester>,
    showChecked: Boolean,
    toggleChecked: () -> Unit,
    onChanged: () -> Unit,
    onFocus: (String) -> Unit,
) {
    val open = checks.filter { !it.done }
    val closed = checks.filter { it.done }
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val heights = remember { mutableStateMapOf<String, Int>() }

    fun insertAfter(c: CheckState, text: String) {
        val n = CheckState(Ids.short(), text, false, c.indent)
        checks.add(checks.indexOf(c) + 1, n)
        onChanged(); onFocus(n.id)
    }

    open.forEach { c ->
        val isDragging = dragging == c.id
        CheckRow(
            c, focus.getOrPut(c.id) { FocusRequester() },
            modifier = Modifier
                .zIndex(if (isDragging) 1f else 0f)
                .graphicsLayer { translationY = if (isDragging) dragOffset else 0f; shadowElevation = if (isDragging) 8f else 0f }
                .onSizeChanged { heights[c.id] = it.height },
            dragHandle = Modifier.pointerInput(c.id) {
                detectDragGestures(
                    onDragStart = { dragging = c.id; dragOffset = 0f },
                    onDragEnd = { dragging = null; dragOffset = 0f; onChanged() },
                    onDragCancel = { dragging = null; dragOffset = 0f; onChanged() },
                ) { change, amount ->
                    change.consume()
                    dragOffset += amount.y
                    val list = checks.filter { !it.done }
                    val i = list.indexOfFirst { it.id == c.id }
                    val next = list.getOrNull(i + 1)
                    val prev = list.getOrNull(i - 1)
                    if (next != null && dragOffset > (heights[next.id] ?: 100) / 2f) {
                        val a = checks.indexOf(c); val b = checks.indexOf(next)
                        checks[a] = next; checks[b] = c
                        dragOffset -= heights[next.id] ?: 100
                    } else if (prev != null && dragOffset < -(heights[prev.id] ?: 100) / 2f) {
                        val a = checks.indexOf(c); val b = checks.indexOf(prev)
                        checks[a] = prev; checks[b] = c
                        dragOffset += heights[prev.id] ?: 100
                    }
                }
            },
            onToggle = { c.done = true; onChanged() },
            onText = { v ->
                val nl = v.text.indexOf('\n')
                if (nl >= 0 && v.text == c.lastSplit) {
                    // IME echo of an Enter we already handled.
                } else if (nl >= 0) {
                    c.lastSplit = v.text
                    c.text = TextFieldValue(v.text.substring(0, nl), TextRange(nl))
                    insertAfter(c, v.text.substring(nl + 1).replace("\n", " "))
                } else {
                    c.text = v; onChanged()
                }
            },
            onBackspaceEmpty = {
                val idx = open.indexOf(c)
                checks.remove(c); onChanged()
                open.getOrNull(idx - 1)?.let { onFocus(it.id) }
            },
            onRemove = { checks.remove(c); onChanged() },
            onIndent = { c.indent = if (c.indent == 0) 1 else 0; onChanged() },
        )
    }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable {
            val c = CheckState(Ids.short(), "", false, 0)
            val lastOpen = open.lastOrNull()
            if (lastOpen != null) checks.add(checks.indexOf(lastOpen) + 1, c) else checks.add(0, c)
            onChanged(); onFocus(c.id)
        }.padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Spacer(Modifier.width(24.dp))
        Icon(Icons.Rounded.Add, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(12.dp))
        Text("List item", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
    }
    if (closed.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable { toggleChecked() }.padding(vertical = 10.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(if (showChecked) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text("${closed.size} checked item${if (closed.size == 1) "" else "s"}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.weight(1f))
            if (showChecked) Text(
                "Uncheck all", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
                modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable { closed.forEach { it.done = false }; onChanged() }.padding(6.dp),
            )
        }
        if (showChecked) closed.forEach { c ->
            CheckRow(
                c, focus.getOrPut(c.id) { FocusRequester() }, Modifier, null,
                onToggle = { c.done = false; onChanged() },
                onText = { v -> c.text = TextFieldValue(v.text.replace("\n", " "), v.selection); onChanged() },
                onBackspaceEmpty = { checks.remove(c); onChanged() },
                onRemove = { checks.remove(c); onChanged() },
                onIndent = {},
            )
        }
    }
    Spacer(Modifier.height(120.dp))
}

@Composable
private fun CheckRow(
    c: CheckState,
    requester: FocusRequester,
    modifier: Modifier,
    dragHandle: Modifier?,
    onToggle: () -> Unit,
    onText: (TextFieldValue) -> Unit,
    onBackspaceEmpty: () -> Unit,
    onRemove: () -> Unit,
    onIndent: () -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier.fillMaxWidth().padding(start = (c.indent * 28).dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (dragHandle != null) {
            Icon(
                Icons.Rounded.DragIndicator, "Reorder",
                dragHandle.size(24.dp).clickable(onClickLabel = "Indent") { onIndent() },
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
            )
        } else Spacer(Modifier.width(24.dp))
        IconButton(onClick = onToggle, modifier = Modifier.size(40.dp)) {
            Icon(
                if (c.done) Icons.Rounded.CheckBox else Icons.Rounded.CheckBoxOutlineBlank, null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (c.done) 0.5f else 0.8f),
            )
        }
        BasicTextField(
            value = c.text,
            onValueChange = onText,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp).focusRequester(requester)
                .onFocusChanged { focused = it.isFocused }
                .onPreviewKeyEvent { e ->
                    if (e.type == KeyEventType.KeyDown && e.key == Key.Backspace && c.text.text.isEmpty()) { onBackspaceEmpty(); true } else false
                },
            textStyle = MaterialTheme.typography.bodyLarge.copy(
                color = if (c.done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                textDecoration = if (c.done) TextDecoration.LineThrough else null,
            ),
            cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
        )
        if (focused) IconButton(onClick = onRemove, modifier = Modifier.size(40.dp)) {
            Icon(Icons.Rounded.Close, "Remove", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
