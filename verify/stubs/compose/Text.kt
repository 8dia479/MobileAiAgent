@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.ui.text

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit

data class TextStyle(
    val color: Color = Color.Unspecified,
    val fontSize: TextUnit = TextUnit.Unspecified,
    val fontWeight: FontWeight? = null,
    val fontStyle: FontStyle? = null,
    val fontFamily: FontFamily? = null,
    val letterSpacing: TextUnit = TextUnit.Unspecified,
    val textDecoration: TextDecoration? = null,
    val textAlign: TextAlign? = null,
    val lineHeight: TextUnit = TextUnit.Unspecified,
) {
}

class FontStyle

class TextDecoration {
    companion object {
        val Underline: TextDecoration = TextDecoration()
        val LineThrough: TextDecoration = TextDecoration()
        val None: TextDecoration = TextDecoration()
    }
}

class TextAlign {
    companion object {
        val Left: TextAlign = TextAlign()
        val Right: TextAlign = TextAlign()
        val Center: TextAlign = TextAlign()
        val Start: TextAlign = TextAlign()
        val End: TextAlign = TextAlign()
        val Justify: TextAlign = TextAlign()
    }
}

// ---------------------------------------------------------------- font types
