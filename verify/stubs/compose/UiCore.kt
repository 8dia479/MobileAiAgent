@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.ui

import androidx.compose.runtime.Composable

/** Marker for a modifier chain; real Compose is an immutable fold, we only type-check. */
interface Modifier {
    companion object : Modifier

    fun then(other: Modifier): Modifier = this
}

fun Modifier.composed(block: @Composable Modifier.() -> Modifier): Modifier = this

/** Modifier extension receiver used when chaining in a `Modifier.` context. */
inline fun Modifier.thenIf(condition: Boolean, crossinline block: Modifier.() -> Modifier): Modifier =
    if (condition) block() else this
