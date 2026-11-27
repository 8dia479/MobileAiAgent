@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.ui

import androidx.compose.ui.unit.Dp

/**
 * Mirrors the real shape closely enough that `Alignment.CenterVertically` resolves
 * to the *Vertical* nested object member (how Compose actually declares it),
 * while `Alignment.Center` / `.TopStart` resolve to plain Alignment values.
 */
open class Alignment {
    open class Horizontal {
        companion object {
            val Start: Horizontal = object : Horizontal() {}
            val End: Horizontal = object : Horizontal() {}
            val CenterHorizontally: Horizontal = object : Horizontal() {}
        }
    }

    open class Vertical {
        companion object {
            val Top: Vertical = object : Vertical() {}
            val Bottom: Vertical = object : Vertical() {}
            val CenterVertically: Vertical = object : Vertical() {}
        }
    }

    companion object {
        val TopStart: Alignment = Alignment()
        val TopCenter: Alignment = Alignment()
        val TopEnd: Alignment = Alignment()
        val CenterStart: Alignment = Alignment()
        val Center: Alignment = Alignment()
        val CenterEnd: Alignment = Alignment()
        val BottomStart: Alignment = Alignment()
        val BottomCenter: Alignment = Alignment()
        val BottomEnd: Alignment = Alignment()

        // Box/Scaffold use these names as Alignment values.
        val Top: Alignment = TopCenter
        val Bottom: Alignment = BottomCenter
        val Start: Alignment = CenterStart
        val End: Alignment = CenterEnd

        // Row/Column take Alignment.Vertical / Alignment.Horizontal, and Kotlin
        // resolves these companion-level names the same way it resolves the real
        // nested-object members used in Android code.
        val CenterVertically: Vertical = Vertical.CenterVertically
        val CenterHorizontally: Horizontal = Horizontal.CenterHorizontally
    }
}

