@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.compose.runtime.saveable

import androidx.compose.runtime.Composable

@Composable
fun <T> rememberSaveable(vararg inputs: Any?, saver: Any? = null, key: String? = null, init: () -> T): T = init()

@Composable
fun <T> rememberSaveable(vararg inputs: Any?, init: () -> T): T = init()
