@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.ui.graphics

import androidx.compose.ui.unit.Dp

@JvmInline
value class Color(val value: ULong) {
    val alpha: Float get() = ((value shr 24) and 0xFFu).toFloat() / 255f
    val red: Float get() = ((value shr 16) and 0xFFu).toFloat() / 255f
    val green: Float get() = ((value shr 8) and 0xFFu).toFloat() / 255f
    val blue: Float get() = (value and 0xFFu).toFloat() / 255f

    fun copy(
        alpha: Float = this.alpha,
        red: Float = this.red,
        green: Float = this.green,
        blue: Float = this.blue,
    ): Color = Color(
        ((alpha * 255f).toInt().coerceIn(0, 255) shl 24 or
            ((red * 255f).toInt().coerceIn(0, 255) shl 16) or
            ((green * 255f).toInt().coerceIn(0, 255) shl 8) or
            (blue * 255f).toInt().coerceIn(0, 255)).toULong()
    )

    companion object {
        val Black: Color = Color(0xFF000000u)
        val White: Color = Color(0xFFFFFFFFu)
        val Transparent: Color = Color(0x00000000u)
        val Unspecified: Color = Color(0u)
        val Red: Color = Color(0xFFFF0000u)
        val Green: Color = Color(0xFF00FF00u)
        val Blue: Color = Color(0xFF0000FFu)
        val Gray: Color = Color(0xFF888888u)
    }
}

fun Color(red: Int, green: Int, blue: Int, alpha: Int = 0xFF): Color = Color(0u)
fun Color(red: Float, green: Float, blue: Float, alpha: Float = 1f): Color = Color(0u)
fun Color(color: Long): Color = Color(0u)
fun Color(color: Int): Color = Color(0u)


object ColorFilter

interface Brush

interface Shader
