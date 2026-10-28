package io.github.carnager.jot.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Alarm
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.github.carnager.jot.data.Repo
import io.github.carnager.jot.model.Item
import io.github.carnager.jot.ui.theme.LocalDark
import io.github.carnager.jot.ui.theme.NoteColors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlin.math.cos
import kotlin.math.sin

// ---- checkbox ------------------------------------------------------------------------------

/**
 * The round task checkbox: the ring is tinted with the item's colour; checking it pops the
 * fill with a spring, draws the tick and gives a haptic tick.
 */
@Composable
fun AnimatedCheck(
    checked: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color = MaterialTheme.colorScheme.primary,
    size: Dp = 24.dp,
    touch: Dp = 48.dp,
) {
    val view = LocalView.current
    val fill by animateFloatAsState(
        if (checked) 1f else 0f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "fill",
    )
    val draw by animateFloatAsState(if (checked) 1f else 0f, tween(if (checked) 260 else 120, delayMillis = if (checked) 80 else 0), label = "tick")
    val onTint = MaterialTheme.colorScheme.surface
    Box(
        modifier
            .size(touch)
            .clip(CircleShape)
            .clickable(role = Role.Checkbox, onClickLabel = if (checked) "Mark not done" else "Complete") {
                view.performHapticFeedback(if (checked) HapticFeedbackConstants.CLOCK_TICK else HapticFeedbackConstants.CONFIRM)
                onToggle()
            },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(size)) {
            val r = this.size.minDimension / 2
            val stroke = 2.dp.toPx()
            drawCircle(tint.copy(alpha = 0.10f), radius = r - stroke / 2)
            drawCircle(tint, radius = r - stroke / 2, style = Stroke(stroke))
            if (fill > 0f) drawCircle(tint, radius = (r) * fill.coerceAtMost(1.08f))
            if (draw > 0f) {
                val p = Path().apply {
                    moveTo(r * 0.52f, r * 1.02f)
                    lineTo(r * 0.86f, r * 1.36f)
                    lineTo(r * 1.50f, r * 0.68f)
                }
                val measure = PathMeasure().apply { setPath(p, false) }
                val part = Path()
                measure.getSegment(0f, measure.length * draw, part, true)
                drawPath(part, onTint, style = Stroke(2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
    }
}

// ---- meta ------------------------------------------------------------------------------------

@Composable
fun MetaChip(icon: ImageVector?, text: String, color: Color = MaterialTheme.colorScheme.onSurfaceVariant, emphasis: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(15.dp), tint = color)
            if (text.isNotEmpty()) Spacer(Modifier.width(4.dp))
        }
        if (text.isNotEmpty()) Text(
            text, style = MaterialTheme.typography.labelMedium, color = color,
            fontWeight = if (emphasis) FontWeight.ExtraBold else FontWeight.Bold, maxLines = 1,
        )
    }
}

/** Labels read as tinted `#label` text. */
@Composable
fun LabelTag(name: String, color: Color = MaterialTheme.colorScheme.primary) {
    Text(
        "#$name", style = MaterialTheme.typography.labelMedium, color = color, maxLines = 1,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = 8.dp, vertical = 1.dp),
    )
}

/** Due/time, snooze, repeat, checklist progress and labels in one compact line. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ItemMeta(item: Item, showDate: Boolean = true, showTime: Boolean = true, content: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val overdue = Fmt.isOverdue(item)
    val dueText = when {
        item.due.isEmpty() -> ""
        showDate -> Fmt.dueShort(item.due)
        showTime -> item.dueTime?.let { Fmt.time(it) } ?: ""
        else -> ""
    }
    val hasAny = dueText.isNotEmpty() || item.repeat != null || item.labels.isNotEmpty() ||
        (item.isList && item.checks.isNotEmpty()) || (item.snoozedUntil.isNotEmpty() && !item.done)
    if (!hasAny) return
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        if (dueText.isNotEmpty()) MetaChip(
            Icons.Rounded.Schedule, dueText,
            if (overdue) MaterialTheme.colorScheme.error else content, emphasis = overdue,
        )
        if (item.snoozedUntil.isNotEmpty() && !item.done) MetaChip(Icons.Rounded.Alarm, Fmt.dueShort(item.snoozedUntil), content)
        if (item.repeat != null) MetaChip(Icons.Rounded.Repeat, "", content)
        if (item.isList && item.checks.isNotEmpty()) {
            MetaChip(Icons.Rounded.Checklist, "${item.checks.count { it.done }}/${item.checks.size}", content)
        }
        item.labels.forEach { LabelTag(it) }
    }
}

fun Item.displayTitle(): String = title.ifBlank {
    (if (isList) checks.firstOrNull()?.text else body.lineSequence().firstOrNull()).orEmpty()
}

/** The colour accent strip on an item's leading edge. */
fun Modifier.accentStrip(color: Color?, inset: Dp = 10.dp, x: Dp = 0.dp): Modifier =
    if (color == null) this else drawBehind {
        val w = 4.dp.toPx()
        val top = inset.toPx()
        drawRoundRect(
            color = color,
            topLeft = Offset(x.toPx(), top),
            size = Size(w, (size.height - 2 * top).coerceAtLeast(w)),
            cornerRadius = CornerRadius(w / 2, w / 2),
        )
    }

/**
 * Completing plays its own exit: the box fills, the title is struck through, then the row
 * collapses; only then is the item actually completed (and leaves the list).
 */
@Composable
private fun rememberCompletion(item: Item, onToggle: () -> Unit): Pair<Boolean, () -> Unit> {
    val ctx = LocalContext.current
    val latest by rememberUpdatedState(onToggle)
    var completing by remember(item.id, item.due, item.done) { mutableStateOf(false) }
    val toggle: () -> Unit = {
        if (item.done || completing) latest()
        else {
            completing = true
            Repo.scope.launch(Dispatchers.Main) {
                Motion.wait(ctx, 480)
                latest()
            }
        }
    }
    return completing to toggle
}

@Composable
fun checkTint(item: Item): Color {
    val accent = NoteColors.accent(item.color, LocalDark.current)
    return when {
        Fmt.isOverdue(item) -> MaterialTheme.colorScheme.error
        accent != null -> accent
        else -> MaterialTheme.colorScheme.primary
    }
}

// ---- rows & cards ------------------------------------------------------------------------------

val RowShape = RoundedCornerShape(18.dp)
val CardShape = RoundedCornerShape(20.dp)

/** A task as a soft rounded container: check, title, a quiet meta line and the time on the right. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TaskRow(
    item: Item,
    selected: Boolean,
    showDate: Boolean,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
) {
    val dark = LocalDark.current
    val accent = NoteColors.accent(item.color, dark)
    val (completing, toggle) = rememberCompletion(item, onToggle)
    val checked = item.done || completing
    val visible = remember(item.id, item.due, item.done) { MutableTransitionState(true) }
    val ctx = LocalContext.current
    LaunchedEffect(completing) {
        if (completing) {
            Motion.wait(ctx, 280)
            visible.targetState = false
        }
    }
    val scheme = MaterialTheme.colorScheme
    val bg by animateColorAsState(
        when {
            selected -> scheme.secondaryContainer
            dark -> scheme.surfaceContainerLow
            else -> scheme.surfaceContainerLowest
        },
        Motion.fast(), label = "rowBg",
    )
    val overdue = Fmt.isOverdue(item)
    AnimatedVisibility(visible, enter = EnterTransition.None, exit = shrinkVertically(Motion.medium()) + fadeOut(Motion.medium())) {
        Row(
            modifier
                .fillMaxWidth()
                .sharedItem(item.id)
                .clip(RowShape)
                .background(bg)
                .then(
                    when {
                        selected -> Modifier.border(2.dp, scheme.primary, RowShape)
                        !dark -> Modifier.border(1.dp, scheme.outlineVariant.copy(alpha = 0.6f), RowShape)
                        else -> Modifier
                    },
                )
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .accentStrip(accent, inset = 14.dp, x = 6.dp)
                .padding(start = 8.dp, end = 16.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            AnimatedCheck(checked, toggle, tint = checkTint(item))
            Column(Modifier.weight(1f).padding(start = 4.dp, top = 8.dp, bottom = 8.dp)) {
                val titleColor by animateColorAsState(
                    if (checked) scheme.onSurfaceVariant.copy(alpha = 0.7f) else scheme.onSurface, Motion.fast(), label = "title",
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        item.displayTitle().ifBlank { "Untitled" },
                        style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (checked) TextDecoration.LineThrough else null,
                        color = titleColor,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (item.pinned) {
                        Spacer(Modifier.width(6.dp))
                        Icon(Icons.Rounded.PushPin, "Pinned", Modifier.size(14.dp), tint = scheme.primary)
                    }
                }
                val preview = if (item.title.isNotBlank() && !item.isList) item.body.lineSequence().firstOrNull { it.isNotBlank() } else null
                if (preview != null) Text(
                    preview, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp),
                )
                Box(Modifier.padding(top = 4.dp)) { ItemMeta(item, showDate = showDate, showTime = false) }
            }
            // The time (or overdue date) sits on the right, where the eye scans a schedule.
            val trailing = when {
                showDate && item.due.isNotEmpty() -> null
                item.dueTime != null -> Fmt.time(item.dueTime!!)
                else -> null
            }
            if (trailing != null) Text(
                trailing,
                style = MaterialTheme.typography.labelLarge,
                color = if (overdue) scheme.error else scheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** A raised card for the notes grid: bold title, preview, mini checklist, labels, colour accent. */
@OptIn(ExperimentalFoundationApi::class, ExperimentalLayoutApi::class)
@Composable
fun NoteCard(
    item: Item,
    selected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleCheck: (String) -> Unit,
    onToggleDone: () -> Unit,
) {
    val dark = LocalDark.current
    val scheme = MaterialTheme.colorScheme
    val accent = NoteColors.accent(item.color, dark)
    val (completing, toggle) = rememberCompletion(item, onToggleDone)
    val checked = item.done || completing
    val content = scheme.onSurface
    val borderColor by animateColorAsState(
        if (selected) scheme.primary else scheme.outlineVariant.copy(alpha = if (dark) 0.55f else 0.8f),
        Motion.fast(), label = "cardBorder",
    )
    val alpha by animateFloatAsState(if (completing) 0.45f else 1f, Motion.medium(), label = "cardAlpha")
    Surface(
        shape = CardShape,
        color = if (selected) scheme.secondaryContainer else if (dark) scheme.surfaceContainer else scheme.surfaceContainerLowest,
        border = BorderStroke(if (selected) 2.dp else 1.dp, borderColor),
        shadowElevation = if (dark) 0.dp else 1.dp,
        modifier = Modifier.fillMaxWidth().sharedItem(item.id).graphicsLayer { this.alpha = alpha }
            .clip(CardShape)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Column(
            Modifier.accentStrip(accent, inset = 16.dp, x = 7.dp)
                .animateContentSize(Motion.medium())
                .padding(start = if (accent != null) 20.dp else 16.dp, end = 16.dp, top = 14.dp, bottom = 14.dp),
        ) {
            if (item.title.isNotBlank() || item.isTask) {
                Row(verticalAlignment = Alignment.Top) {
                    if (item.isTask) {
                        AnimatedCheck(checked, toggle, Modifier.padding(end = 4.dp), tint = checkTint(item), size = 18.dp, touch = 26.dp)
                    }
                    Text(
                        item.title.ifBlank { if (item.isTask) item.displayTitle() else "" },
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.ExtraBold),
                        color = content, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        textDecoration = if (checked) TextDecoration.LineThrough else null,
                        modifier = Modifier.weight(1f).padding(top = if (item.isTask) 2.dp else 0.dp),
                    )
                    if (item.pinned) Icon(Icons.Rounded.PushPin, "Pinned", Modifier.padding(start = 4.dp, top = 3.dp).size(15.dp), tint = scheme.primary)
                }
            }
            if (item.isList) {
                val open = item.checks.filter { !it.done && it.text.isNotBlank() }
                val shown = open.take(6)
                Column(Modifier.padding(top = if (item.title.isNotBlank()) 8.dp else 0.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    shown.forEach { c ->
                        key(c.id) {
                            Row(
                                Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onToggleCheck(c.id) }
                                    .padding(start = (c.indent * 14).dp, top = 2.dp, bottom = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Box(
                                    Modifier.size(15.dp).clip(RoundedCornerShape(4.dp))
                                        .border(1.6.dp, scheme.onSurfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(4.dp)),
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(c.text, style = MaterialTheme.typography.bodyMedium, color = content.copy(alpha = 0.9f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                    val more = open.size - shown.size
                    val doneCount = item.checks.count { it.done }
                    if (more > 0 || doneCount > 0) Text(
                        listOfNotNull(if (more > 0) "+$more more" else null, if (doneCount > 0) "$doneCount done" else null).joinToString(" · "),
                        style = MaterialTheme.typography.labelMedium, color = scheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            } else if (item.body.isNotBlank()) {
                Text(
                    item.body, style = MaterialTheme.typography.bodyMedium, color = scheme.onSurfaceVariant,
                    maxLines = 8, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = if (item.title.isNotBlank()) 6.dp else 0.dp),
                )
            }
            if (item.due.isNotEmpty() || item.labels.isNotEmpty() || item.repeat != null) {
                Spacer(Modifier.height(12.dp))
                ItemMeta(item.copy(checks = emptyList()))
            }
        }
    }
}

// ---- headers ---------------------------------------------------------------------------------

/** Section header: small caps-ish title, a count pill, optional alert colour and icon. */
@Composable
fun SectionHeader(text: String, count: Int? = null, alert: Boolean = false, icon: ImageVector? = null, modifier: Modifier = Modifier, inset: Dp = 24.dp) {
    val color = if (alert) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        modifier.fillMaxWidth().padding(start = inset, end = 20.dp, top = 20.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, Modifier.size(16.dp), tint = color)
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.ExtraBold)
        if (count != null) {
            Spacer(Modifier.width(8.dp))
            Text(
                "$count", style = MaterialTheme.typography.labelSmall, color = color,
                modifier = Modifier.clip(RoundedCornerShape(50)).background(color.copy(alpha = 0.12f)).padding(horizontal = 7.dp, vertical = 1.dp),
            )
        }
    }
}

/** The big screen title with a subtitle and an optional trailing element (progress ring). */
@Composable
fun ScreenHeader(title: String, subtitle: String?, modifier: Modifier = Modifier, trailing: @Composable (() -> Unit)? = null, inset: Dp = 24.dp) {
    Row(modifier.fillMaxWidth().padding(start = inset, end = inset - 4.dp, top = 8.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.displaySmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) Text(
                subtitle, style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp),
            )
        }
        trailing?.invoke()
    }
}

/** "2 of 5 done" as an animated ring. */
@Composable
fun ProgressRing(done: Int, total: Int, modifier: Modifier = Modifier) {
    val target = if (total == 0) 0f else done.toFloat() / total
    val p by animateFloatAsState(target, tween(600, easing = FastOutSlowInEasing), label = "progress")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val color = if (LocalDark.current) io.github.carnager.jot.ui.theme.MarigoldBright else io.github.carnager.jot.ui.theme.Marigold
    Box(modifier.size(64.dp).semantics { contentDescription = "$done of $total done" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(58.dp)) {
            val w = 6.dp.toPx()
            drawArc(track, 0f, 360f, false, style = Stroke(w), topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w))
            if (p > 0f) drawArc(color, -90f, 360f * p, false, style = Stroke(w, cap = StrokeCap.Round), topLeft = Offset(w / 2, w / 2), size = Size(size.width - w, size.height - w))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("$done", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
            Text("of $total", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// ---- brand & empty states ----------------------------------------------------------------------

/** The yellow check square. */
@Composable
fun BrandMark(size: Dp = 32.dp) {
    Canvas(Modifier.size(size)) {
        val s = this.size.minDimension
        drawRoundRect(
            Brush.linearGradient(listOf(Color(0xFFFFCF4A), Color(0xFFF2A516)), Offset.Zero, Offset(s, s)),
            cornerRadius = CornerRadius(s * 0.28f, s * 0.28f),
        )
        val p = Path().apply {
            moveTo(s * 0.27f, s * 0.52f); lineTo(s * 0.43f, s * 0.68f); lineTo(s * 0.74f, s * 0.34f)
        }
        drawPath(p, Color(0xFF2B1F00), style = Stroke(s * 0.1f, cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

enum class Illustration { ALL_CLEAR, CALENDAR, NOTES, DONE, ARCHIVE, TRASH, SEARCH }

/** A soft, gently floating illustration drawn in code. ALL_CLEAR draws its tick and bursts once. */
@Composable
fun EmptyIllustration(kind: Illustration, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val float = rememberInfiniteTransition(label = "float")
    val bob by float.animateFloat(-4f, 4f, infiniteRepeatable(tween(2400, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "bob")
    val intro = remember { Animatable(0f) }
    LaunchedEffect(kind) { intro.snapTo(0f); intro.animateTo(1f, tween(900, easing = FastOutSlowInEasing)) }
    val primary = if (LocalDark.current) io.github.carnager.jot.ui.theme.MarigoldBright else io.github.carnager.jot.ui.theme.Marigold
    val onBrand = Color(0xFF2B1F00)
    val container = scheme.primaryContainer
    val soft = scheme.surfaceContainerHighest
    val ink = scheme.onSurfaceVariant
    val burstColors = listOf(primary, Color(0xFFFF8A65), Color(0xFF6BCB77), Color(0xFF79A8FF), Color(0xFFB392F0))
    Canvas(modifier.size(180.dp)) {
        val c = Offset(size.width / 2, size.height / 2 + bob.dp.toPx())
        val t = intro.value
        // Soft halo.
        drawCircle(Brush.radialGradient(listOf(container.copy(alpha = 0.55f), Color.Transparent), c, size.minDimension / 2), radius = size.minDimension / 2, center = c)
        val u = size.minDimension / 180f
        when (kind) {
            Illustration.ALL_CLEAR, Illustration.DONE -> {
                val r = 46 * u * (0.6f + 0.4f * minOf(1f, t * 1.6f))
                drawCircle(primary, r, c)
                val p = Path().apply {
                    moveTo(c.x - 20 * u, c.y + 1 * u); lineTo(c.x - 5 * u, c.y + 16 * u); lineTo(c.x + 22 * u, c.y - 14 * u)
                }
                val m = PathMeasure().apply { setPath(p, false) }
                val seg = Path(); m.getSegment(0f, m.length * ((t - 0.3f) / 0.5f).coerceIn(0f, 1f), seg, true)
                drawPath(seg, onBrand, style = Stroke(9 * u, cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (kind == Illustration.ALL_CLEAR && t < 1f) {
                    // Confetti burst.
                    val b = ((t - 0.35f) / 0.65f).coerceIn(0f, 1f)
                    if (b > 0f) for (i in 0 until 14) {
                        val a = Math.toRadians(i * 360.0 / 14 + 8)
                        val d = (54 + 30 * b) * u
                        drawCircle(
                            burstColors[i % burstColors.size].copy(alpha = 1f - b),
                            radius = (4.5f - 2f * b) * u,
                            center = Offset(c.x + (cos(a) * d).toFloat(), c.y + (sin(a) * d).toFloat()),
                        )
                    }
                }
            }
            Illustration.CALENDAR -> {
                val w = 96 * u; val h = 86 * u
                val tl = Offset(c.x - w / 2, c.y - h / 2)
                drawRoundRect(soft, tl, Size(w, h), CornerRadius(18 * u))
                drawRoundRect(primary, tl, Size(w, 24 * u), CornerRadius(18 * u))
                drawRect(primary, Offset(tl.x, tl.y + 12 * u), Size(w, 12 * u))
                for (row in 0..2) for (col in 0..3) {
                    val on = row == 1 && col == 2
                    drawRoundRect(
                        if (on) primary else ink.copy(alpha = 0.25f),
                        Offset(tl.x + 12 * u + col * 20 * u, tl.y + 34 * u + row * 16 * u), Size(12 * u, 9 * u), CornerRadius(3 * u),
                    )
                }
            }
            Illustration.NOTES, Illustration.SEARCH, Illustration.ARCHIVE, Illustration.TRASH -> {
                val w = 78 * u; val h = 96 * u
                rotateCard(c, -10f, w, h, soft.copy(alpha = 0.7f), u)
                rotateCard(c, 6f, w, h, soft, u)
                val tl = Offset(c.x - w / 2, c.y - h / 2)
                for (i in 0..3) drawRoundRect(
                    if (i == 0) primary else ink.copy(alpha = 0.28f),
                    Offset(tl.x + 14 * u, tl.y + 18 * u + i * 16 * u), Size(if (i == 0) 40 * u else (50 - i * 8) * u, 7 * u), CornerRadius(4 * u),
                )
                when (kind) {
                    Illustration.SEARCH -> {
                        val lc = Offset(c.x + 30 * u, c.y + 28 * u)
                        drawCircle(primary, 18 * u, lc, style = Stroke(7 * u))
                        drawLine(primary, Offset(lc.x + 13 * u, lc.y + 13 * u), Offset(lc.x + 28 * u, lc.y + 28 * u), 8 * u, StrokeCap.Round)
                    }
                    Illustration.ARCHIVE -> drawRoundRect(primary, Offset(c.x - 46 * u, c.y + 28 * u), Size(92 * u, 26 * u), CornerRadius(8 * u))
                    Illustration.TRASH -> drawCircle(primary, 16 * u, Offset(c.x + 34 * u, c.y + 34 * u))
                    else -> drawCircle(primary, 12 * u, Offset(c.x + 34 * u, c.y - 44 * u))
                }
            }
        }
    }
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.rotateCard(c: Offset, deg: Float, w: Float, h: Float, color: Color, u: Float) {
    rotate(deg, c) {
        drawRoundRect(color, Offset(c.x - w / 2, c.y - h / 2), Size(w, h), CornerRadius(16 * u))
    }
}

@Composable
fun EmptyState(kind: Illustration, title: String, hint: String, modifier: Modifier = Modifier) {
    Column(
        modifier.fillMaxWidth().padding(top = 48.dp, start = 40.dp, end = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        EmptyIllustration(kind)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

/** Kept for callers that still want the small round box (e.g. the editor title). */
@Composable
fun RoundCheck(checked: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier, tint: Color = MaterialTheme.colorScheme.primary) =
    AnimatedCheck(checked, onToggle, modifier, tint, size = 26.dp)
