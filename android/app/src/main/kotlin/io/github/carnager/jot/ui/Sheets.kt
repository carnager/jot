package io.github.carnager.jot.ui

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.EventBusy
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.NotificationsOff
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.outlined.Weekend
import androidx.compose.material.icons.outlined.WbTwilight
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import io.github.carnager.jot.model.Due
import io.github.carnager.jot.model.Item
import io.github.carnager.jot.model.QuickAdd
import io.github.carnager.jot.model.Repeat
import io.github.carnager.jot.model.Repeats
import io.github.carnager.jot.model.WEEKDAY_CODES
import io.github.carnager.jot.ui.theme.LocalDark
import io.github.carnager.jot.ui.theme.NoteColors
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters

private data class Quick(val icon: ImageVector, val label: String, val sub: String, val due: String)

/**
 * The reschedule sheet: one-tap presets, a natural-language field, a picker, repeat and reminder.
 * [items] are the targets (one, or a multi-selection).
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RescheduleSheet(
    items: List<Item>,
    onDismiss: () -> Unit,
    onDue: (String) -> Unit,
    onRepeat: ((Repeat?) -> Unit)? = null,
    onRemind: ((Boolean) -> Unit)? = null,
    onAlerts: ((List<Int>) -> Unit)? = null,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val single = items.singleOrNull()
    val keepTime = single?.dueTime
    val now = LocalDateTime.now()
    val today = now.toLocalDate()
    var text by remember { mutableStateOf("") }
    val parsed = remember(text) { if (text.isBlank()) null else QuickAdd.parse(text, now) }
    var pickDate by remember { mutableStateOf(false) }
    var pickTimeFor by remember { mutableStateOf<LocalDate?>(null) }
    var repeatDialog by remember { mutableStateOf(false) }

    fun on(d: LocalDate) = Due.of(d, keepTime)
    val presets = buildList {
        val later = now.plusHours(3).withMinute(0).withSecond(0).withNano(0)
        if (later.toLocalDate() == today) add(Quick(Icons.Outlined.Schedule, "Later today", Fmt.time(later.toLocalTime()), Due.of(later)))
        if (now.hour < 18) add(Quick(Icons.Outlined.DarkMode, "Tonight", Fmt.time(LocalTime.of(19, 0)), Due.of(today, LocalTime.of(19, 0))))
        val tomorrow = today.plusDays(1)
        add(Quick(Icons.Outlined.LightMode, "Tomorrow", tomorrow.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")), on(tomorrow)))
        val mon = today.with(TemporalAdjusters.next(DayOfWeek.MONDAY))
        add(Quick(Icons.Outlined.WbTwilight, "Next Mon", mon.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")), on(mon)))
        val sat = today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)).let { if (it == today) it.plusWeeks(1) else it }
        add(Quick(Icons.Outlined.Weekend, "Weekend", sat.format(java.time.format.DateTimeFormatter.ofPattern("EEE d MMM")), on(sat)))
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp)) {
            Text(
                if (items.size > 1) "Schedule ${items.size} items" else "Schedule",
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold,
            )
            if (single != null && single.due.isNotEmpty()) Text(
                "Now: " + Fmt.dueShort(single.due) + (single.repeat?.let { " · " + Repeats.describe(it) } ?: ""),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(16.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), maxItemsInEachRow = 3) {
                presets.forEach { p -> PresetTile(p.icon, p.label, p.sub, Modifier.weight(1f)) { onDue(p.due); onDismiss() } }
                PresetTile(Icons.Outlined.CalendarMonth, "Pick…", "Date & time", Modifier.weight(1f)) { pickDate = true }
                if (items.any { it.due.isNotEmpty() }) {
                    PresetTile(Icons.Outlined.EventBusy, "No date", "", Modifier.weight(1f)) { onDue(""); onDismiss() }
                }
            }
            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Type it: fri 9am, in 3 days, 24.12. …") },
                singleLine = true,
                shape = RoundedCornerShape(16.dp),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = {
                    val p = parsed
                    if (p != null && p.due.isNotEmpty()) {
                        onDue(p.due)
                        if (p.repeat != null) onRepeat?.invoke(p.repeat)
                        onDismiss()
                    }
                }),
                supportingText = {
                    val p = parsed
                    when {
                        p == null -> {}
                        p.due.isEmpty() -> Text("Not a date yet")
                        else -> Text("→ " + Fmt.dueShort(p.due) + (p.repeat?.let { " · " + Repeats.describe(it) } ?: "") + "   ⏎ to set")
                    }
                },
            )
            if (onRepeat != null || onRemind != null) {
                Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (onRepeat != null) FilterChip(
                        selected = single?.repeat != null,
                        onClick = { repeatDialog = true },
                        label = { Text(single?.repeat?.let { Repeats.describe(it) } ?: "Repeat") },
                        leadingIcon = { Icon(Icons.Outlined.Repeat, null, Modifier.size(18.dp)) },
                    )
                    if (onRemind != null && single != null) {
                        FilterChip(
                            selected = single.remind,
                            onClick = { onRemind(!single.remind) },
                            label = { Text(if (single.remind) "Reminder on" else "No reminder") },
                            leadingIcon = { Icon(if (single.remind) Icons.Outlined.Notifications else Icons.Outlined.NotificationsOff, null, Modifier.size(18.dp)) },
                        )
                    }
                }
            }
            if (onAlerts != null && single != null && single.due.isNotEmpty() && single.remind) {
                AlertsSection(single, onAlerts)
            }
        }
    }

    if (pickDate) {
        val initial = (single?.dueDate ?: today).atStartOfDay().toInstant(ZoneOffset.UTC).toEpochMilli()
        val state = rememberDatePickerState(initialSelectedDateMillis = initial)
        DatePickerDialog(
            onDismissRequest = { pickDate = false },
            confirmButton = {
                TextButton(onClick = {
                    val ms = state.selectedDateMillis
                    pickDate = false
                    if (ms != null) pickTimeFor = Instant.ofEpochMilli(ms).atZone(ZoneOffset.UTC).toLocalDate()
                }) { Text("Next") }
            },
            dismissButton = { TextButton(onClick = { pickDate = false }) { Text("Cancel") } },
        ) { DatePicker(state) }
    }
    pickTimeFor?.let { d ->
        TimeDialog(
            initial = keepTime ?: LocalTime.of(9, 0),
            onDismiss = { pickTimeFor = null },
            onAllDay = { pickTimeFor = null; onDue(Due.of(d, null)); onDismiss() },
            onTime = { t -> pickTimeFor = null; onDue(Due.of(d, t)); onDismiss() },
        )
    }
    if (repeatDialog && onRepeat != null) {
        RepeatDialog(single?.repeat, single?.due ?: "", onDismiss = { repeatDialog = false }) { r ->
            repeatDialog = false
            onRepeat(r)
        }
    }
}

@Composable
private fun PresetTile(icon: ImageVector, label: String, sub: String, modifier: Modifier, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = modifier.heightIn(min = 76.dp),
    ) {
        Column(Modifier.padding(12.dp)) {
            Icon(icon, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1)
            if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeDialog(initial: LocalTime, onDismiss: () -> Unit, onAllDay: () -> Unit, onTime: (LocalTime) -> Unit) {
    val ctx = LocalContext.current
    val state = rememberTimePickerState(initial.hour, initial.minute, DateFormat.is24HourFormat(ctx))
    AlertDialog(
        onDismissRequest = onDismiss,
        confirmButton = { TextButton(onClick = { onTime(LocalTime.of(state.hour, state.minute)) }) { Text("Set time") } },
        dismissButton = { TextButton(onClick = onAllDay) { Text("All day") } },
        text = { TimePicker(state) },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RepeatDialog(initial: Repeat?, due: String, onDismiss: () -> Unit, onDone: (Repeat?) -> Unit) {
    val dueDay = Due.date(due)?.dayOfWeek
    var freq by remember { mutableStateOf(initial?.freq ?: "") }
    var interval by remember { mutableStateOf((initial?.interval ?: 1).toString()) }
    var days by remember { mutableStateOf(initial?.byDay?.toSet() ?: emptySet()) }
    var fromCompletion by remember { mutableStateOf(initial?.fromCompletion ?: false) }
    val freqs = listOf("" to "Never", "day" to "Daily", "week" to "Weekly", "month" to "Monthly", "year" to "Yearly")
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Repeat") },
        confirmButton = {
            TextButton(onClick = {
                if (freq.isEmpty()) onDone(null)
                else onDone(
                    Repeat(
                        freq = freq,
                        interval = interval.toIntOrNull()?.coerceIn(1, 999) ?: 1,
                        byDay = if (freq == "week") WEEKDAY_CODES.filter { it in days } else emptyList(),
                        monthDay = if (freq == "month") initial?.monthDay ?: 0 else 0,
                        fromCompletion = fromCompletion,
                        until = initial?.until ?: "",
                    )
                )
            }) { Text("Done") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    freqs.forEach { (k, label) ->
                        FilterChip(selected = freq == k, onClick = {
                            freq = k
                            if (k == "week" && days.isEmpty() && dueDay != null) days = setOf(Repeats.code(dueDay))
                        }, label = { Text(label) })
                    }
                }
                if (freq.isNotEmpty()) {
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Every ", style = MaterialTheme.typography.bodyLarge)
                        OutlinedTextField(
                            value = interval, onValueChange = { v -> interval = v.filter(Char::isDigit).take(3) },
                            modifier = Modifier.width(72.dp), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number),
                        )
                        Text(
                            " " + when (freq) { "day" -> "day(s)"; "week" -> "week(s)"; "month" -> "month(s)"; else -> "year(s)" },
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                    if (freq == "week") {
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                            WEEKDAY_CODES.forEach { code ->
                                val on = code in days
                                Box(
                                    Modifier.size(34.dp).clip(CircleShape)
                                        .background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh)
                                        .clickable { days = if (on) days - code else days + code },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        Repeats.dayOfWeek(code).getDisplayName(java.time.format.TextStyle.NARROW, java.util.Locale.getDefault()),
                                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                        style = MaterialTheme.typography.labelLarge,
                                    )
                                }
                            }
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Count from completion", style = MaterialTheme.typography.bodyLarge)
                            Text("Next date counts from when you finish", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(fromCompletion, { fromCompletion = it })
                    }
                }
            }
        },
    )
}

/** Labels for one or more items: checked = on all of them. New labels can be typed in. */
@Composable
fun LabelDialog(all: List<String>, initial: Set<String>, onDismiss: () -> Unit, onDone: (Set<String>) -> Unit) {
    var selected by remember { mutableStateOf(initial) }
    var extra by remember { mutableStateOf(listOf<String>()) }
    var text by remember { mutableStateOf("") }
    val labels = (all + extra).distinct().sorted()
    fun addTyped() {
        val l = text.trim().removePrefix("#").lowercase().replace(Regex("\\s+"), "-")
        if (l.isNotEmpty()) { extra = extra + l; selected = selected + l }
        text = ""
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Labels") },
        confirmButton = { TextButton(onClick = { if (text.isNotBlank()) addTyped(); onDone(selected) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
        text = {
            Column {
                OutlinedTextField(
                    value = text, onValueChange = { text = it }, singleLine = true,
                    placeholder = { Text("New label") },
                    leadingIcon = { Icon(Icons.Outlined.Add, null) },
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(onDone = { addTyped() }),
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(labels, key = { it }) { l ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp))
                                .clickable { selected = if (l in selected) selected - l else selected + l }
                                .padding(vertical = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(l in selected, { selected = if (l in selected) selected - l else selected + l })
                            Text(l, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
        },
    )
}

/** A row of colour dots. */
@Composable
fun ColorRow(selected: String, onPick: (String) -> Unit, modifier: Modifier = Modifier) {
    val dark = LocalDark.current
    androidx.compose.foundation.lazy.LazyRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(NoteColors.names) { name ->
            val c = NoteColors.accent(name, dark)
            Box(
                Modifier.size(40.dp).clip(CircleShape)
                    .background(c ?: MaterialTheme.colorScheme.surfaceContainerHigh)
                    .border(
                        if (name == selected) 2.5.dp else 1.dp,
                        if (name == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                        CircleShape,
                    )
                    .clickable { onPick(name) },
                contentAlignment = Alignment.Center,
            ) {
                when {
                    name == selected -> Icon(Icons.Rounded.Check, null, tint = if (c != null) MaterialTheme.colorScheme.surface else MaterialTheme.colorScheme.onSurface)
                    name.isEmpty() -> Icon(Icons.Outlined.Block, "Default", Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorSheet(selected: String, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 20.dp)) {
            Text("Colour", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(12.dp))
            ColorRow(selected, { onPick(it); onDismiss() })
        }
    }
}

@Composable
fun ThemeSegments(options: List<String>, selected: Int, onSelect: (Int) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, label ->
            SegmentedButton(
                selected = i == selected, onClick = { onSelect(i) },
                shape = SegmentedButtonDefaults.itemShape(i, options.size),
            ) { Text(label) }
        }
    }
}

@Composable
fun Divider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

/** Alerts (SPEC §5): several at once, e.g. "On time · 30 min before". Presets differ for all-day items. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AlertsSection(item: Item, onAlerts: (List<Int>) -> Unit) {
    val allDay = item.dueTime == null
    val presets = if (allDay) io.github.carnager.jot.model.Alerts.PRESETS_ALLDAY else io.github.carnager.jot.model.Alerts.PRESETS_TIMED
    val current = io.github.carnager.jot.model.Alerts.norm(item.alerts)
    Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Notifications, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.width(8.dp))
            Text("Alerts", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.width(8.dp))
            Text(
                io.github.carnager.jot.model.Alerts.describe(current, allDay),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            presets.forEach { m ->
                val on = m in current
                FilterChip(
                    selected = on,
                    onClick = {
                        val next = if (on) current - m else current + m
                        onAlerts(io.github.carnager.jot.model.Alerts.norm(next).let { if (it == listOf(0)) emptyList() else it })
                    },
                    label = { Text(io.github.carnager.jot.model.Alerts.label(m, allDay, short = true)) },
                    leadingIcon = if (on) ({ Icon(Icons.Rounded.Check, null, Modifier.size(16.dp)) }) else null,
                )
            }
        }
    }
}
