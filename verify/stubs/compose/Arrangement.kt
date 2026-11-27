@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.foundation.layout

import androidx.compose.ui.unit.Dp

open class Arrangement {
    interface Horizontal
    interface Vertical

    companion object {
        val Start: Horizontal = object : Horizontal {}
        val End: Horizontal = object : Horizontal {}
        val Center: Horizontal = object : Horizontal {}
        val SpaceBetween: Horizontal = object : Horizontal {}
        val SpaceAround: Horizontal = object : Horizontal {}
        val SpaceEvenly: Horizontal = object : Horizontal {}

        val Top: Vertical = object : Vertical {}
        val Bottom: Vertical = object : Vertical {}
        val CenterVertically: Vertical = object : Vertical {}

        fun spacedBy(space: Dp): SpacedAligned = SpacedAligned(space)
    }

    /** Usable for both Row and Column, which is how the UI calls it. */
    class SpacedAligned(val space: Dp) : Horizontal, Vertical
}
