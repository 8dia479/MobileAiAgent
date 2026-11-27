@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.ui

import androidx.compose.ui.unit.LayoutDirection

/**
 * Mirrors the real androidx.compose.ui.Alignment (verified against
 * androidx-main compose/ui/ui/src/commonMain/kotlin/androidx/compose/ui/Alignment.kt):
 *
 *   fun interface Horizontal
 *   fun interface Vertical
 *   companion object {
 *       val Top: Vertical          = BiasAlignment.Vertical(-1f)
 *       val CenterVertically: Vertical = …
 *       val Bottom: Vertical       = BiasAlignment.Vertical(1f)
 *       val Start: Horizontal      = BiasAlignment.Horizontal(-1f)
 *       val CenterHorizontally: Horizontal = …
 *       val End: Horizontal        = BiasAlignment.Horizontal(1f)
 *       // 2-D values: TopStart … BottomEnd, Center
 *   }
 *
 * So `Alignment.Vertical.Top` does NOT exist — that mistake is what the first
 * real Gradle build reported. Keep this file faithful; a permissive stub here
 * silently hides compile errors in every alignment call in the app.
 */
open class Alignment {
    fun interface Horizontal {
        fun align(size: Int, space: Int, layoutDirection: LayoutDirection): Int
    }

    fun interface Vertical {
        fun align(size: Int, space: Int): Int
    }

    companion object {
        // Vertical alignments
        val Top: Vertical = Vertical { _, _ -> 0 }
        val CenterVertically: Vertical = Vertical { _, _ -> 0 }
        val Bottom: Vertical = Vertical { _, _ -> 0 }

        // Horizontal alignments
        val Start: Horizontal = Horizontal { _, _, _ -> 0 }
        val CenterHorizontally: Horizontal = Horizontal { _, _, _ -> 0 }
        val End: Horizontal = Horizontal { _, _, _ -> 0 }

        // 2-D alignments
        val TopStart: Alignment = Alignment()
        val TopCenter: Alignment = Alignment()
        val TopEnd: Alignment = Alignment()
        val CenterStart: Alignment = Alignment()
        val Center: Alignment = Alignment()
        val CenterEnd: Alignment = Alignment()
        val BottomStart: Alignment = Alignment()
        val BottomCenter: Alignment = Alignment()
        val BottomEnd: Alignment = Alignment()
    }
}
