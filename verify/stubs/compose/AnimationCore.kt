@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.compose.animation.core
interface AnimationSpec<T>
interface FiniteAnimationSpec<T> : AnimationSpec<T>
object tween
object spring
object snap
