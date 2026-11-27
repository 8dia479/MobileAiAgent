@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.lifecycle

import androidx.compose.runtime.Composable
import kotlinx.coroutines.CoroutineScope

abstract class ViewModel {
    protected open fun onCleared() {}
}

val ViewModel.viewModelScope: CoroutineScope
    get() = throw UnsupportedOperationException("lifecycle stub")

open class ViewModelProvider {
    /** Real API is an interface; a class would reject `object : Factory {}` shapes. */
    interface Factory {
        fun <T : ViewModel> create(modelClass: Class<T>): T
    }
}

class ViewModelStore

interface ViewModelStoreOwner

abstract class LifecycleOwner

class Lifecycle

@Composable
fun <T : ViewModel> viewModel(viewModelStoreOwner: ViewModelStoreOwner? = null): T =
    throw UnsupportedOperationException("stub")
