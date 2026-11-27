@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.ui.text.font
class FontWeight(val weight: Int) {
    companion object {
        val Thin: FontWeight = FontWeight(100)
        val Light: FontWeight = FontWeight(300)
        val Normal: FontWeight = FontWeight(400)
        val Medium: FontWeight = FontWeight(500)
        val SemiBold: FontWeight = FontWeight(600)
        val Bold: FontWeight = FontWeight(700)
        val Black: FontWeight = FontWeight(900)
    }
}

class FontFamily {
    companion object {
        val Default: FontFamily = FontFamily()
        val SansSerif: FontFamily = FontFamily()
        val Serif: FontFamily = FontFamily()
        val Monospace: FontFamily = FontFamily()
        val Cursive: FontFamily = FontFamily()
    }
}
