package io.github.carnager.jot.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material.icons.rounded.Checklist
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Event
import androidx.compose.material.icons.rounded.PushPin
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.outlined.AddPhotoAlternate
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Send
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.InputChip
import androidx.compose.material3.InputChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import io.github.carnager.jot.model.Item
import io.github.carnager.jot.model.QuickAdd
import io.github.carnager.jot.model.Repeats
import java.time.LocalDate
import java.time.LocalDateTime

/** What a capture adds by default in the current view. */
data class CaptureContext(
    val dueToday: Boolean = false,
    val label: String? = null,
    val hint: String = "Add a note or task…",
    /** Upcoming with a day selected: new items land on that day. */
    val day: LocalDate? = null,
)

/** One chip per recognised meaning; tapping it turns the source words back into text. */
private data class ParseChip(val icon: ImageVector, val text: String, val sources: List<String>)

private fun chips(r: QuickAdd.Result): List<ParseChip> {
    val out = mutableListOf<ParseChip>()
    val dateTokens = r.tokens.filter { it.kind == QuickAdd.Kind.DATE || it.kind == QuickAdd.Kind.TIME }
    val repeatTokens = r.tokens.filter { it.kind == QuickAdd.Kind.REPEAT }
    if (r.due.isNotEmpty() && (dateTokens.isNotEmpty() || repeatTokens.isNotEmpty())) {
        out += ParseChip(Icons.Rounded.Event, Fmt.dueShort(r.due), dateTokens.map { it.text })
    }
    if (r.repeat != null) out += ParseChip(Icons.Rounded.Repeat, Repeats.describe(r.repeat), repeatTokens.map { it.text })
    r.tokens.filter { it.kind == QuickAdd.Kind.LABEL }.forEach { out += ParseChip(Icons.AutoMirrored.Rounded.Label, it.text.removePrefix("#"), listOf(it.text)) }
    r.tokens.filter { it.kind == QuickAdd.Kind.PIN }.forEach { out += ParseChip(Icons.Rounded.PushPin, "Pinned", listOf(it.text)) }
    if (r.kind == "list") out += ParseChip(Icons.Rounded.Checklist, "List · ${r.checks.size}", emptyList())
    return out.filter { it.sources.isNotEmpty() || it.icon == Icons.Rounded.Checklist }
}

/** Highlights recognised tokens in the field itself. */
private class TokenHighlight(val tokens: List<QuickAdd.Token>, val style: SpanStyle) : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val out = buildAnnotatedString {
            append(text)
            tokens.forEach { if (it.end <= text.length) addStyle(style, it.start, it.end) }
        }
        return TransformedText(out, OffsetMapping.Identity)
    }
}

fun buildCaptured(r: QuickAdd.Result, ctx: CaptureContext): Item {
    var item = r.toItem()
    if (item.due.isEmpty() && ctx.dueToday) item = item.copy(due = LocalDate.now().toString())
    if (ctx.day != null && r.repeat == null && r.tokens.none { it.kind == QuickAdd.Kind.DATE }) {
        // No date typed: the selected day, all-day, or at the typed time.
        val time = if (r.tokens.any { it.kind == QuickAdd.Kind.TIME }) io.github.carnager.jot.model.Due.time(item.due) else null
        item = item.copy(due = io.github.carnager.jot.model.Due.of(ctx.day, time))
    }
    if (ctx.label != null && ctx.label !in item.labels) item = item.copy(labels = (item.labels + ctx.label).sorted())
    return item.copy(labels = item.labels.distinct().sorted())
}

/**
 * The capture pill: live natural-language parse, Enter saves and keeps focus for the next one.
 * Quick buttons: checklist (opens the editor) and date (sets the due without typing it).
 * [onSubmit] gets the finished item; [onExpand] opens it in the full editor instead.
 */
@Composable
fun CaptureField(
    ctx: CaptureContext,
    onSubmit: (Item) -> Unit,
    onExpand: (Item) -> Unit,
    modifier: Modifier = Modifier,
    initialText: String = "",
    autoFocus: Boolean = false,
    elevated: Boolean = true,
    /** Images that come with the capture (shared from another app). */
    attachments: List<io.github.carnager.jot.model.ImageRef> = emptyList(),
    importing: Boolean = false,
    focusRequester: FocusRequester = remember { FocusRequester() },
) {
    var value by remember { mutableStateOf(TextFieldValue(initialText, TextRange(initialText.length))) }
    var disabled by remember { mutableStateOf(setOf<String>()) }
    var pickedDue by remember { mutableStateOf<String?>(null) }
    var dateSheet by remember { mutableStateOf(false) }
    val parsed = remember(value.text, disabled) { QuickAdd.parse(value.text.trimEnd('\n'), LocalDateTime.now(), disabled) }
    val scheme = MaterialTheme.colorScheme
    val highlight = SpanStyle(color = scheme.primary, background = scheme.primaryContainer.copy(alpha = 0.6f), fontWeight = FontWeight.Bold)

    fun build(): Item {
        var item = buildCaptured(parsed, ctx)
        pickedDue?.let { item = item.copy(due = it) }
        if (attachments.isNotEmpty()) item = item.copy(images = attachments)
        return item
    }
    fun reset() { value = TextFieldValue(""); disabled = emptySet(); pickedDue = null }
    fun submit() {
        if (value.text.isBlank() && attachments.isEmpty()) return
        if (importing) return
        onSubmit(build())
        reset()
    }

    LaunchedEffect(autoFocus) { if (autoFocus) runCatching { focusRequester.requestFocus() } }
    var imageMenu by remember { mutableStateOf(false) }
    // A photo straight from the capture bar becomes a new note, opened so it can get a title.
    val picker = rememberImagePicker { refs ->
        val item = build().let { it.copy(images = it.images + refs) }
        onExpand(item)
        reset()
    }

    Column(modifier) {
        AnimatedVisibility(attachments.isNotEmpty() || importing, enter = expandVertically(Motion.fast()) + fadeIn(Motion.fast()), exit = shrinkVertically(Motion.fast()) + fadeOut(Motion.fast())) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 4.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
            ) {
                items(attachments, key = { it.id }) { ref ->
                    AttachmentImage(ref, 240, Modifier.animateItem().size(72.dp).clip(RoundedCornerShape(14.dp)))
                }
                if (importing) item("importing") {
                    androidx.compose.foundation.layout.Box(
                        Modifier.size(72.dp).clip(RoundedCornerShape(14.dp)).background(scheme.surfaceContainerHighest),
                        contentAlignment = Alignment.Center,
                    ) { androidx.compose.material3.CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp) }
                }
            }
        }
        val chipList = chips(parsed).let { list ->
            val p = pickedDue
            if (p != null) listOf(ParseChip(Icons.Rounded.Event, Fmt.dueShort(p), listOf(PICKED))) + list.filter { it.icon != Icons.Rounded.Event } else list
        }
        AnimatedVisibility(chipList.isNotEmpty(), enter = expandVertically(Motion.fast()) + fadeIn(Motion.fast()), exit = shrinkVertically(Motion.fast()) + fadeOut(Motion.fast())) {
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 4.dp),
                modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
            ) {
                items(chipList, key = { it.text + it.sources }) { c ->
                    Surface(
                        onClick = {
                            if (PICKED in c.sources) pickedDue = null
                            else if (c.sources.isNotEmpty()) disabled = disabled + c.sources.map { it.lowercase() }
                        },
                        shape = RoundedCornerShape(50),
                        color = scheme.primaryContainer,
                        contentColor = scheme.onPrimaryContainer,
                        shadowElevation = 2.dp,
                        modifier = Modifier.animateItem(),
                    ) {
                        Row(Modifier.padding(start = 10.dp, end = 10.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(c.icon, null, Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text(c.text, style = MaterialTheme.typography.labelLarge, maxLines = 1)
                            if (c.sources.isNotEmpty()) {
                                Spacer(Modifier.width(4.dp))
                                Icon(Icons.Rounded.Close, "Keep as text", Modifier.size(14.dp))
                            }
                        }
                    }
                }
            }
        }
        Surface(
            shape = RoundedCornerShape(28.dp),
            color = if (elevated) scheme.surfaceContainerHigh else scheme.surfaceContainerHighest,
            shadowElevation = if (elevated) 10.dp else 0.dp,
            border = BorderStroke(1.dp, scheme.outlineVariant.copy(alpha = 0.5f)),
        ) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 4.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = {
                    val item = build().let { if (it.isList) it else it.copy(kind = "list", body = "") }
                    onExpand(item)
                    reset()
                }) { Icon(Icons.Rounded.Checklist, "New list", tint = scheme.onSurfaceVariant) }
                BasicTextField(
                    value = value,
                    onValueChange = { nv ->
                        // Enter on a hardware or soft keyboard saves; pasted multi-line text is kept.
                        val typedNewline = nv.text.length == value.text.length + 1 && nv.text.endsWith("\n") &&
                            nv.selection.end == nv.text.length && !value.text.endsWith("\n")
                        if (typedNewline && !nv.text.dropLast(1).contains('\n')) {
                            value = nv.copy(text = nv.text.dropLast(1)); submit()
                        } else value = nv
                    },
                    modifier = Modifier.weight(1f).padding(vertical = 16.dp).focusRequester(focusRequester),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = scheme.onSurface),
                    cursorBrush = SolidColor(scheme.primary),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit() }),
                    maxLines = 6,
                    visualTransformation = TokenHighlight(parsed.tokens, highlight),
                    decorationBox = { inner ->
                        if (value.text.isEmpty()) Text(ctx.hint, style = MaterialTheme.typography.bodyLarge, color = scheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        inner()
                    },
                )
                androidx.compose.foundation.layout.Box {
                    IconButton(onClick = { imageMenu = true }) {
                        if (picker.busy()) androidx.compose.material3.CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Outlined.AddPhotoAlternate, "Add a photo", tint = scheme.onSurfaceVariant)
                    }
                    androidx.compose.material3.DropdownMenu(imageMenu, { imageMenu = false }) {
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Take photo") }, leadingIcon = { Icon(Icons.Outlined.PhotoCamera, null) }, onClick = { imageMenu = false; picker.camera() })
                        androidx.compose.material3.DropdownMenuItem(text = { Text("Choose from gallery") }, leadingIcon = { Icon(Icons.Outlined.PhotoLibrary, null) }, onClick = { imageMenu = false; picker.gallery() })
                    }
                }
                IconButton(onClick = { dateSheet = true }) {
                    Icon(Icons.Rounded.Event, "Set date", tint = if (pickedDue != null) scheme.primary else scheme.onSurfaceVariant)
                }
                AnimatedVisibility(
                    value.text.isNotBlank() || attachments.isNotEmpty(),
                    enter = scaleIn(spring(dampingRatio = Spring.DampingRatioMediumBouncy)) + fadeIn(Motion.fast()),
                    exit = scaleOut(Motion.fast()) + fadeOut(Motion.fast()),
                ) {
                    FilledIconButton(onClick = { submit() }) { Icon(Icons.AutoMirrored.Rounded.Send, "Add") }
                }
            }
        }
    }
    if (dateSheet) RescheduleSheet(emptyList(), onDismiss = { dateSheet = false }, onDue = { d -> pickedDue = d.ifEmpty { null } })
}

private const val PICKED = "\u0000picked"
