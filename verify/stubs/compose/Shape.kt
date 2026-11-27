@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.compose.foundation.shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
class RoundedCornerShape(val size: Dp) {
    companion object {
        fun all(size: Dp): RoundedCornerShape = RoundedCornerShape(size)
    }
}
fun RoundedCornerShape(size: Int): RoundedCornerShape = RoundedCornerShape(size.dp)
fun RoundedCornerShape(topStart: Dp = 0.dp, topEnd: Dp = 0.dp, bottomEnd: Dp = 0.dp, bottomStart: Dp = 0.dp): RoundedCornerShape = RoundedCornerShape(0.dp)
