@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.compose.animation

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

@Composable
fun AnimatedVisibility(
    visible: Boolean,
    modifier: Modifier = Modifier,
    enter: Any? = null,
    exit: Any? = null,
    label: String = "AnimatedVisibility",
    content: @Composable () -> Unit,
) = Unit

interface AnimatedVisibilityScope

@Composable
fun Crossfade(targetState: Any?, modifier: Modifier = Modifier, content: @Composable (Any?) -> Unit) = Unit

@Composable
fun animateContentSize(): Modifier = Modifier
