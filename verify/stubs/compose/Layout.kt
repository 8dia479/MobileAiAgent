@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.foundation.layout

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

// ------------------------------------------------------------------ padding

fun Modifier.padding(all: Dp): Modifier = this
fun Modifier.padding(horizontal: Dp = Dp.Unspecified, vertical: Dp = Dp.Unspecified): Modifier = this
fun Modifier.padding(start: Dp = Dp.Unspecified, top: Dp = Dp.Unspecified, end: Dp = Dp.Unspecified, bottom: Dp = Dp.Unspecified): Modifier = this
fun Modifier.padding(paddingValues: PaddingValues): Modifier = this

// -------------------------------------------------------------------- size

fun Modifier.fillMaxWidth(fraction: Float = 1f): Modifier = this
fun Modifier.fillMaxHeight(fraction: Float = 1f): Modifier = this
fun Modifier.fillMaxSize(fraction: Float = 1f): Modifier = this
fun Modifier.width(width: Dp): Modifier = this
fun Modifier.height(height: Dp): Modifier = this
fun Modifier.size(size: Dp): Modifier = this
fun Modifier.size(width: Dp, height: Dp): Modifier = this
fun Modifier.widthIn(min: Dp = Dp.Unspecified, max: Dp = Dp.Unspecified): Modifier = this
fun Modifier.heightIn(min: Dp = Dp.Unspecified, max: Dp = Dp.Unspecified): Modifier = this
fun Modifier.wrapContentWidth(align: Alignment.Horizontal = Alignment.Horizontal.Start): Modifier = this
fun Modifier.wrapContentHeight(align: Alignment.Vertical = Alignment.Vertical.Top): Modifier = this

// ------------------------------------------------------------------ weight

interface RowScope {
    fun Modifier.weight(weight: Float, fill: Boolean = true): Modifier = this
    fun Modifier.align(alignment: Alignment.Vertical): Modifier = this
}

interface ColumnScope {
    fun Modifier.weight(weight: Float, fill: Boolean = true): Modifier = this
    fun Modifier.align(alignment: Alignment.Horizontal): Modifier = this
}

interface BoxScope {
    fun Modifier.align(alignment: Alignment): Modifier = this
}

// --------------------------------------------------------------- containers

@Composable
fun Row(
    modifier: Modifier = Modifier,
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Vertical.Top,
    content: @Composable RowScope.() -> Unit,
) = Unit

@Composable
fun Column(
    modifier: Modifier = Modifier,
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Horizontal.Start,
    content: @Composable ColumnScope.() -> Unit,
) = Unit

@Composable
fun Box(
    modifier: Modifier = Modifier,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit,
) = Unit

@Composable
fun Spacer(modifier: Modifier): Unit = Unit

@Composable
fun Spacer(modifier: Modifier, width: Dp): Unit = Unit

// --------------------------------------------------------------- offset/ime

fun Modifier.offset(x: Dp = Dp.Zero, y: Dp = Dp.Zero): Modifier = this
fun Modifier.imePadding(): Modifier = this
fun Modifier.navigationBarsPadding(): Modifier = this
fun Modifier.statusBarsPadding(): Modifier = this
fun Modifier.safeDrawingPadding(): Modifier = this
fun Modifier.defaultMinSize(minWidth: Dp = Dp.Unspecified, minHeight: Dp = Dp.Unspecified): Modifier = this

// ----------------------------------------------------------------- padding

interface PaddingValues {
    fun calculateLeftPadding(layoutDirection: androidx.compose.ui.unit.LayoutDirection): Dp
    fun calculateTopPadding(): Dp
    fun calculateRightPadding(layoutDirection: androidx.compose.ui.unit.LayoutDirection): Dp
    fun calculateBottomPadding(): Dp
}

fun PaddingValues(all: Dp): PaddingValues = object : PaddingValues {
    override fun calculateLeftPadding(layoutDirection: androidx.compose.ui.unit.LayoutDirection) = all
    override fun calculateTopPadding() = all
    override fun calculateRightPadding(layoutDirection: androidx.compose.ui.unit.LayoutDirection) = all
    override fun calculateBottomPadding() = all
}

fun PaddingValues(horizontal: Dp = Dp.Zero, vertical: Dp = Dp.Zero): PaddingValues =
    PaddingValues(if (horizontal > vertical) horizontal else vertical)

fun PaddingValues(start: Dp = Dp.Zero, top: Dp = Dp.Zero, end: Dp = Dp.Zero, bottom: Dp = Dp.Zero): PaddingValues =
    PaddingValues((if (start > end) start else end))

// --------------------------------------------------------------- scrollable

fun Modifier.verticalScroll(state: androidx.compose.foundation.ScrollState, enabled: Boolean = true): Modifier = this
fun Modifier.horizontalScroll(state: androidx.compose.foundation.ScrollState, enabled: Boolean = true): Modifier = this
fun Modifier.verticalScroll(state: androidx.compose.foundation.ScrollState, enabled: Boolean = true, flingBehavior: Any? = null, reverseScrolling: Boolean = false): Modifier = this
