@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.ui.text.style
class TextOverflow {
    companion object {
        val Clip: TextOverflow = TextOverflow()
        val Ellipsis: TextOverflow = TextOverflow()
        val Visible: TextOverflow = TextOverflow()
        val StartEllipsis: TextOverflow = TextOverflow()
    }
}

class TextAlign {
    companion object {
        val Start: TextAlign = TextAlign()
        val End: TextAlign = TextAlign()
        val Center: TextAlign = TextAlign()
        val Justify: TextAlign = TextAlign()
    }
}
