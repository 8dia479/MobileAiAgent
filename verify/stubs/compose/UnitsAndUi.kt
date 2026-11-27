@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.ui.unit

/** Density-independent pixel. Real class is inline; a value class is close enough for type checking. */
@JvmInline
value class Dp(val value: Float) : Comparable<Dp> {
    operator fun plus(other: Dp): Dp = Dp(value + other.value)
    operator fun minus(other: Dp): Dp = Dp(value - other.value)
    operator fun times(other: Float): Dp = Dp(value * other)
    operator fun div(other: Float): Dp = Dp(value / other)
    override fun compareTo(other: Dp): Int = value.compareTo(other.value)

    companion object {
        val Unspecified: Dp = Dp(Float.NaN)
        val Zero: Dp = Dp(0f)
        val Infinity: Dp = Dp(Float.POSITIVE_INFINITY)
    }
}

@JvmInline
value class TextUnit(val value: Float) {
    operator fun times(other: Float): TextUnit = TextUnit(value * other)

    companion object {
        val Unspecified: TextUnit = TextUnit(Float.NaN)
    }
}

val Int.dp: Dp get() = Dp(this.toFloat())
val Float.dp: Dp get() = Dp(this)
val Double.dp: Dp get() = Dp(this.toFloat())
val Int.sp: TextUnit get() = TextUnit(this.toFloat())
val Float.sp: TextUnit get() = TextUnit(this)
val Double.sp: TextUnit get() = TextUnit(this.toFloat())

enum class LayoutDirection { Ltr, Rtl }

class IntSize(val width: Int, val height: Int)

/**
 * Mirrors androidx.compose.ui.unit.Density: toPx/roundToPx/toSp are *member
 * extensions*, which is why `with(density) { 16.dp.toPx() }` compiles.
 */
interface Density {
    val density: Float
    fun Dp.toPx(): Float = value * density
    fun Dp.roundToPx(): Int = (value * density).toInt()
    fun Dp.toSp(): TextUnit = TextUnit(value)
}

fun FontScaleConverter(): Unit = Unit


