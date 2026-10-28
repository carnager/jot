package io.github.carnager.jot.ui

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import io.github.carnager.jot.model.Item
import java.time.LocalDate

/**
 * Long-press a task and drag it onto a day pill to reschedule. A long-press without moving
 * still selects (multi-select); the drag only starts once the finger moves.
 */
class DayDragState {
    var item by mutableStateOf<Item?>(null)
        private set
    /** Pointer in root coordinates. */
    var pointer by mutableStateOf(Offset.Zero)
    var grab = Offset.Zero
    var rowSize by mutableStateOf(IntSize.Zero)
    var active by mutableStateOf(false)
    /** Day pills' bounds in root coordinates, registered by the strip. */
    val pills = mutableStateMapOf<LocalDate, Rect>()
    private var clickBlockedUntil = 0L

    val hover: LocalDate? by derivedStateOf {
        if (!active) null else pills.entries.firstOrNull { it.value.inflate(10f).contains(pointer) }?.key
    }

    fun start(item: Item, pointer: Offset, grab: Offset, size: IntSize) {
        this.item = item; this.pointer = pointer; this.grab = grab; rowSize = size; active = false
    }

    fun end() {
        item = null; active = false
        clickBlockedUntil = SystemClock.uptimeMillis() + 400
    }

    /** The tap that ends a long-press must not also open the item. */
    fun clickAllowed() = SystemClock.uptimeMillis() > clickBlockedUntil
}

@Composable
fun Modifier.dragToDay(item: Item, drag: DayDragState, onSelect: () -> Unit, onDrop: (Item, LocalDate) -> Unit): Modifier {
    var coords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val view = LocalView.current
    val slop = with(LocalDensity.current) { 12.dp.toPx() }
    val latestItem by rememberUpdatedState(item)
    val select by rememberUpdatedState(onSelect)
    val drop by rememberUpdatedState(onDrop)
    val dragging = drag.item?.id == item.id && drag.active
    return this
        .onGloballyPositioned { coords = it }
        .graphicsLayer { alpha = if (dragging) 0.35f else 1f }
        .pointerInput(item.id) {
            var moved = 0f
            detectDragGesturesAfterLongPress(
                onDragStart = { pos ->
                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                    val c = coords ?: return@detectDragGesturesAfterLongPress
                    moved = 0f
                    drag.start(latestItem, c.localToRoot(pos), pos, c.size)
                },
                onDrag = { change, amount ->
                    change.consume()
                    moved += amount.getDistance()
                    drag.pointer += amount
                    if (!drag.active && moved > slop) drag.active = true
                },
                onDragEnd = {
                    val day = drag.hover
                    val dragged = drag.item
                    when {
                        !drag.active -> select()
                        day != null && dragged != null -> {
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            drop(dragged, day)
                        }
                    }
                    drag.end()
                },
                onDragCancel = { drag.end() },
            )
        }
}
