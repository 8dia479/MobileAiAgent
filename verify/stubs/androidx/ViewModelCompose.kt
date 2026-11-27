@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.lifecycle.viewmodel.compose

import androidx.compose.runtime.Composable
import androidx.lifecycle.ViewModel

@Composable
inline fun <reified VM : ViewModel> viewModel(
    viewModelStoreOwner: androidx.lifecycle.ViewModelStoreOwner? = null,
    key: String? = null,
    factory: Any? = null,
    extras: Any? = null,
): VM = throw UnsupportedOperationException("stub")
