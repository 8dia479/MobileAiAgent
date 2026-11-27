@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.activity

import androidx.compose.runtime.Composable

open class ComponentActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {}
    override fun onNewIntent(intent: android.content.Intent) {}
    @Composable fun setContent(content: @Composable () -> Unit) {}
    fun <I, O> registerForActivityResult(
        contract: androidx.activity.result.contract.ActivityResultContract<I, O>,
        callback: (O) -> Unit,
    ): androidx.activity.compose.ManagedActivityResultLauncher<I, O> =
        androidx.activity.compose.ManagedActivityResultLauncher()
}

fun androidx.activity.ComponentActivity.enableEdgeToEdge() {}
