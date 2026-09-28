package com.maxmfonseca.pontinho.ui.components

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/** The pointer coroutine survives reordering; only this visible target owns dragging. */
@Composable
fun ImmediateDragHandle(
    enabled: Boolean,
    contentDescription: String,
    onDragStart: (Offset) -> Unit,
    onDragDelta: (Float) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val start by rememberUpdatedState(onDragStart)
    val delta by rememberUpdatedState(onDragDelta)
    val end by rememberUpdatedState(onDragEnd)
    val cancel by rememberUpdatedState(onDragCancel)
    Box(
        modifier.size(48.dp).semantics {
            this.contentDescription = contentDescription
            if (!enabled) disabled()
        }.pointerInput(enabled) {
            var dragging = false
            try {
                if (enabled) detectVerticalDragGestures(
                    onDragStart = { dragging = true; start(it) },
                    onVerticalDrag = { change, amount -> change.consume(); delta(amount) },
                    onDragEnd = { dragging = false; end() },
                    onDragCancel = { dragging = false; cancel() },
                )
            } finally {
                // Disabling/removing the handle cancels the pointer coroutine itself.
                if (dragging) cancel()
            }
        },
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Menu, contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (enabled) 1f else 0.38f))
    }
}
