@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.compose.foundation.gestures

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

// NOTE: pointerInput lives in androidx.compose.ui.input.pointer (see Pointer.kt),
// not here. It used to be duplicated in this file with a vararg overload that the
// real API does not have, which let a non-compiling call pass the type-check.

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
