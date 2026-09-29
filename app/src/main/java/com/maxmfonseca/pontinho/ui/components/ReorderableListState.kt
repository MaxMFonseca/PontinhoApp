package com.maxmfonseca.pontinho.ui.components

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.isActive
import kotlin.math.abs

/** Shared handle-only reorder session. The caller supplies stable IDs and list-local pointer geometry. */
@Composable
fun <Key : Any> rememberReorderableListState(
    listState: LazyListState,
    order: List<Key>,
    enabled: Boolean,
    itemKey: (Key) -> Any,
    onReorder: (List<Key>) -> Unit,
): ReorderableListState<Key> {
    val currentOrder = rememberUpdatedState(order)
    val currentEnabled = rememberUpdatedState(enabled)
    val currentItemKey = rememberUpdatedState(itemKey)
    val currentOnReorder = rememberUpdatedState(onReorder)
    val state = remember(listState) {
        ReorderableListState(listState, currentOrder, currentEnabled, currentItemKey, currentOnReorder)
    }
    LaunchedEffect(enabled, order.toSet()) { state.cancelIfInvalid() }
    val edgeSize = with(LocalDensity.current) { 64.dp.toPx() }
    val maxScrollPerFrame = with(LocalDensity.current) { 18.dp.toPx() }
    val scrollSpeed by remember(state, edgeSize, maxScrollPerFrame) {
        derivedStateOf { state.edgeScrollSpeed(edgeSize, maxScrollPerFrame) }
    }
    LaunchedEffect(state.draggedKey, scrollSpeed) {
        if (state.draggedKey == null || scrollSpeed == 0f) return@LaunchedEffect
        while (isActive) {
            withFrameNanos { }
            // Process the preceding frame's layout even when the next scroll consumes zero.
            state.updatePreview()
            if (listState.scrollBy(scrollSpeed) == 0f) break
        }
    }
    return state
}

@Stable
class ReorderableListState<Key : Any> internal constructor(
    private val listState: LazyListState,
    private val committedOrder: State<List<Key>>,
    private val enabled: State<Boolean>,
    private val itemKey: State<(Key) -> Any>,
    private val onReorder: State<(List<Key>) -> Unit>,
) {
    var previewOrder by mutableStateOf<List<Key>?>(null)
        private set
    var draggedKey by mutableStateOf<Key?>(null)
        private set
    private var draggedTop by mutableFloatStateOf(0f)
    private var draggedHeight by mutableFloatStateOf(0f)
    private var pointerY by mutableFloatStateOf(0f)
    private var firstDragDelta = false

    fun startDrag(key: Key, pointerYInList: Float) {
        if (!enabled.value || key !in committedOrder.value) return
        val item = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == itemKey.value(key) } ?: return
        draggedKey = key
        previewOrder = committedOrder.value.toList()
        draggedTop = item.offset.toFloat()
        draggedHeight = item.size.toFloat()
        pointerY = pointerYInList
        firstDragDelta = true
    }

    fun dragBy(amount: Float) {
        cancelIfInvalid()
        if (draggedKey == null) return
        draggedTop += amount
        // Gesture start already includes the slop-triggering pointer position.
        if (firstDragDelta) firstDragDelta = false else pointerY += amount
        updatePreview()
    }

    fun endDrag() {
        cancelIfInvalid()
        val order = previewOrder
        cancelDrag()
        if (order != null) onReorder.value(order)
    }

    fun cancelDrag() {
        draggedKey = null
        previewOrder = null
    }

    fun translationY(key: Key): Float {
        if (draggedKey != key) return 0f
        val rowOffset = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == itemKey.value(key) }?.offset ?: 0
        return draggedTop - rowOffset
    }

    internal fun cancelIfInvalid() {
        if (!enabled.value || previewOrder?.toSet()?.let { it != committedOrder.value.toSet() } == true) cancelDrag()
    }

    internal fun updatePreview() {
        cancelIfInvalid()
        val key = draggedKey ?: return
        val order = previewOrder ?: return
        val from = order.indexOf(key)
        val center = draggedTop + draggedHeight / 2f
        val crossed = listState.layoutInfo.visibleItemsInfo.filter { item ->
            val index = order.indexOfFirst { itemKey.value(it) == item.key }
            index >= 0 && index != from &&
                if (index > from) center > item.offset + item.size / 2f else center < item.offset + item.size / 2f
        }
        val target = crossed.minByOrNull { abs(center - (it.offset + it.size / 2f)) } ?: return
        val to = order.indexOfFirst { itemKey.value(it) == target.key }
        // Anchor the viewport, not the first visible key, while the preview changes indices.
        listState.requestScrollToItem(listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
        previewOrder = order.toMutableList().apply { add(to, removeAt(from)) }
    }

    internal fun edgeScrollSpeed(edgeSize: Float, maxScrollPerFrame: Float): Float {
        if (draggedKey == null) return 0f
        val layout = listState.layoutInfo
        val top = layout.viewportStartOffset + edgeSize
        val bottom = layout.viewportEndOffset - edgeSize
        return when {
            pointerY < top -> -((top - pointerY) / edgeSize).coerceIn(0f, 1f) * maxScrollPerFrame
            pointerY > bottom -> ((pointerY - bottom) / edgeSize).coerceIn(0f, 1f) * maxScrollPerFrame
            else -> 0f
        }
    }
}
