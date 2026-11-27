@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.activity.compose

import androidx.compose.runtime.Composable

@Composable
fun setContent(content: @Composable () -> Unit) = Unit

@Composable
fun <I, O> rememberLauncherForActivityResult(
    contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
    onResult: (O) -> Unit,
): ManagedActivityResultLauncher<I, O> = ManagedActivityResultLauncher()

class ManagedActivityResultLauncher<I, O> {
    fun launch(input: I) {}
}
