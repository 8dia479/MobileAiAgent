@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.compose.ui.input.pointer

import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset

interface PointerInputScope

class PointerInputChange {
    fun consume() {}
    val position: Offset get() = Offset(0f, 0f)
}

/**
 * Exactly the real signature: one key, no vararg. `pointerInput(a, b) { }` does
 * not compile on the real library and must not compile here either.
 */
fun Modifier.pointerInput(key1: Any?, block: suspend PointerInputScope.() -> Unit): Modifier = this
