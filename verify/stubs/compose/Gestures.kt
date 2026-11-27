@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.compose.foundation.gestures

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

fun Modifier.pointerInput(key1: Any?, block: suspend androidx.compose.ui.input.pointer.PointerInputScope.() -> Unit): Modifier = this
fun Modifier.pointerInput(vararg keys: Any?, block: suspend androidx.compose.ui.input.pointer.PointerInputScope.() -> Unit): Modifier = this

suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectDragGestures(
    onDragStart: (Offset) -> Unit = {},
    onDragEnd: () -> Unit = {},
    onDragCancel: () -> Unit = {},
    onDrag: (change: androidx.compose.ui.input.pointer.PointerInputChange, dragAmount: Offset) -> Unit,
) = Unit

suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTransformGestures(
    panZoomLock: Boolean = false,
    onGesture: (centroid: Offset, pan: Offset, zoom: Float, rotation: Float) -> Unit,
) = Unit

suspend fun androidx.compose.ui.input.pointer.PointerInputScope.detectTapGestures(
    onTap: ((Offset) -> Unit)? = null,
    onDoubleTap: ((Offset) -> Unit)? = null,
    onLongPress: ((Offset) -> Unit)? = null,
) = Unit
