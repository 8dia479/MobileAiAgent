@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.foundation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle

@Composable
fun Modifier.clickable(
    enabled: Boolean = true,
    onClickLabel: String? = null,
    role: Any? = null,
    onClick: () -> Unit,
): Modifier = this

fun Modifier.background(color: Color): Modifier = this
fun Modifier.background(color: Color, shape: RoundedCornerShape): Modifier = this
fun Modifier.background(brush: Any? = null, shape: RoundedCornerShape = RoundedCornerShape(0), alpha: Float = 1f): Modifier = this
fun Modifier.border(width: androidx.compose.ui.unit.Dp, color: Color, shape: RoundedCornerShape = RoundedCornerShape(0)): Modifier = this
fun Modifier.clip(shape: RoundedCornerShape): Modifier = this
fun Modifier.alpha(alpha: Float): Modifier = this

interface ScrollState {
    var value: Int
    val maxValue: Int
}

val ScrollState.value: Int get() = 0

class ScrollStateImpl : ScrollState {
    override var value: Int = 0
    override val maxValue: Int = 0
}

@Composable
fun rememberScrollState(initial: Int = 0): ScrollState = ScrollStateImpl()

class BorderStroke(val width: androidx.compose.ui.unit.Dp, val brush: Any? = null)






@androidx.compose.runtime.Composable
fun isSystemInDarkTheme(): Boolean = false

fun Modifier.horizontalScroll(state: ScrollState, enabled: Boolean = true): Modifier = this
