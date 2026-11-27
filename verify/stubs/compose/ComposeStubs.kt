@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.runtime

import kotlin.reflect.KProperty

/**
 * Compose type-check stubs.
 *
 * These exist because Compose artifacts cannot be resolved offline (Google Maven
 * is blocked). They are NOT a reimplementation: nothing here draws anything. The
 * point is to let kotlinc type-check every UI call site — argument names, types,
 * arity, nullability — so the Compose code is verified as *compiling*, which is
 * the single biggest risk in a project that cannot be assembled here.
 *
 * Anything the stubs accept but real Compose would reject is a hole in this net,
 * so the stubs stay close to the real signatures: no `vararg Any`, no catch-all
 * overloads that would swallow genuine errors.
 */

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER, AnnotationTarget.TYPE, AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
@Retention(AnnotationRetention.BINARY)
annotation class Composable

@Target(AnnotationTarget.FUNCTION, AnnotationTarget.PROPERTY_GETTER)
@Retention(AnnotationRetention.BINARY)
annotation class ReadOnlyComposable

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
annotation class NonRestartableComposable

interface State<out T> {
    val value: T
}

interface MutableState<T> : State<T> {
    override var value: T
    operator fun component1(): T
    operator fun component2(): (T) -> Unit
}

interface SnapshotMutationPolicy<T>

object NeverEqualPolicy : SnapshotMutationPolicy<Any?>

fun <T> mutableStateOf(value: T): MutableState<T> {
    var current = value
    return object : MutableState<T> {
        override var value: T
            get() = current
            set(newValue) { current = newValue }
        override fun component1(): T = current
        override fun component2(): (T) -> Unit = { current = it }
    }
}

@Composable
fun <T> remember(calculation: () -> T): T = calculation()

@Composable
fun <T> remember(key1: Any?, calculation: () -> T): T = calculation()

@Composable
fun <T> remember(key1: Any?, key2: Any?, calculation: () -> T): T = calculation()

@Composable
fun <T> remember(key1: Any?, key2: Any?, key3: Any?, calculation: () -> T): T = calculation()

@Composable
fun <T> rememberUpdatedState(newValue: T): State<T> = object : State<T> { override val value: T = newValue }

@Composable
fun <T> key(vararg keys: Any?, block: @Composable () -> T): T = block()

interface CompositionLocal<T>

interface ProvidableCompositionLocal<T> : CompositionLocal<T>

inline fun <T> compositionLocalOf(crossinline defaultFactory: () -> T): ProvidableCompositionLocal<T> =
    object : ProvidableCompositionLocal<T> {}

inline fun <T> staticCompositionLocalOf(crossinline defaultFactory: () -> T): ProvidableCompositionLocal<T> =
    object : ProvidableCompositionLocal<T> {}

val <T> CompositionLocal<T>.current: T
    @Composable @ReadOnlyComposable get() = throw UnsupportedOperationException("compose stub: no real composition")

@Composable
fun <T> CompositionLocalProvider(local: ProvidableCompositionLocal<T>, value: T, content: @Composable () -> Unit) = content()

@Composable
fun <T1, T2> CompositionLocalProvider(
    local1: ProvidableCompositionLocal<T1>, value1: T1,
    local2: ProvidableCompositionLocal<T2>, value2: T2,
    content: @Composable () -> Unit,
) = content()

@Composable
fun SideEffect(effect: () -> Unit) = Unit

interface DisposableEffectResult

interface DisposableEffectScope {
    fun onDispose(onDisposeEffect: () -> Unit): DisposableEffectResult
}

@Composable
fun DisposableEffect(key1: Any?, effect: DisposableEffectScope.() -> DisposableEffectResult) = Unit

@Composable
fun DisposableEffect(vararg keys: Any?, effect: DisposableEffectScope.() -> DisposableEffectResult) = Unit

@Composable
fun LaunchedEffect(key1: Any?, block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = Unit

@Composable
fun LaunchedEffect(key1: Any?, key2: Any?, block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = Unit

@Composable
fun LaunchedEffect(key1: Any?, key2: Any?, key3: Any?, block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = Unit

@Composable
fun LaunchedEffect(vararg keys: Any?, block: suspend kotlinx.coroutines.CoroutineScope.() -> Unit) = Unit



@Composable
fun rememberCoroutineScope(): kotlinx.coroutines.CoroutineScope = throw UnsupportedOperationException("compose stub")

@Composable
fun <T> withFrameNanos(onFrame: (Long) -> T): T = throw UnsupportedOperationException("compose stub")

/** `collectAsState` lives in runtime in the real library. */
/** `var x by remember { mutableStateOf(...) }` / `by collectAsState()` support. */
operator fun <T> State<T>.getValue(thisObj: Any?, property: KProperty<*>): T = value

operator fun <T> MutableState<T>.setValue(thisObj: Any?, property: KProperty<*>, newValue: T) {
    value = newValue
}

@Composable
fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsState(): State<T> =
    throw UnsupportedOperationException("compose stub")

@Composable
fun <T> kotlinx.coroutines.flow.StateFlow<T>.collectAsState(initial: T): State<T> =
    throw UnsupportedOperationException("compose stub")
