@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.foundation.lazy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp

class LazyListState {
    val firstVisibleItemIndex: Int get() = 0
    val firstVisibleItemScrollOffset: Int get() = 0
    suspend fun scrollToItem(index: Int, scrollOffset: Int = 0) = Unit
    suspend fun animateScrollToItem(index: Int, scrollOffset: Int = 0) = Unit
}

@Composable
fun rememberLazyListState(): LazyListState = LazyListState()


interface LazyItemScope

interface LazyListScope {
    fun item(key: Any? = null, contentType: Any? = null, content: @Composable LazyItemScope.() -> Unit)
    fun items(count: Int, key: ((Int) -> Any)? = null, contentType: (Int) -> Any? = { null }, itemContent: @Composable LazyItemScope.(Int) -> Unit)

    /**
     * Compose declares the list overload as an extension, but declaring it here as
     * a member of the same arity group keeps overload resolution behaving like the
     * real library: a List argument must select the T-typed overload, never Int.
     */
    fun <T> items(
        items: List<T>,
        key: ((T) -> Any)? = null,
        contentType: (T) -> Any? = { null },
        itemContent: @Composable LazyItemScope.(T) -> Unit,
    ) = Unit
}

fun <T> LazyListScope.items(
    items: List<T>,
    key: ((T) -> Any)? = null,
    contentType: (T) -> Any? = { null },
    itemContent: @Composable LazyItemScope.(T) -> Unit,
) = Unit

/** Indexed variant used by some screens. */
fun <T> LazyListScope.itemsIndexed(
    items: List<T>,
    key: ((index: Int, item: T) -> Any)? = null,
    itemContent: @Composable LazyItemScope.(index: Int, item: T) -> Unit,
) = Unit

@Composable
fun LazyColumn(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(androidx.compose.ui.unit.Dp.Zero),
    verticalArrangement: Arrangement.Vertical = Arrangement.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Start,
    content: LazyListScope.() -> Unit,
) = Unit

@Composable
fun LazyRow(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    contentPadding: PaddingValues = PaddingValues(androidx.compose.ui.unit.Dp.Zero),
    horizontalArrangement: Arrangement.Horizontal = Arrangement.Start,
    verticalAlignment: Alignment.Vertical = Alignment.Top,
    content: LazyListScope.() -> Unit,
) = Unit

