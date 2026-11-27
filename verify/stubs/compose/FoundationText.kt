@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.compose.foundation.text

import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

class KeyboardOptions(
    val capitalization: Any? = null,
    val autoCorrect: Boolean = true,
    val keyboardType: KeyboardType = KeyboardType.Text,
    val imeAction: ImeAction = ImeAction.Default,
)

interface KeyboardActionScope

class KeyboardActions(
    val onDone: (KeyboardActionScope.() -> Unit)? = null,
    val onGo: (KeyboardActionScope.() -> Unit)? = null,
    val onNext: (KeyboardActionScope.() -> Unit)? = null,
    val onSearch: (KeyboardActionScope.() -> Unit)? = null,
    val onSend: (KeyboardActionScope.() -> Unit)? = null,
    val onPrevious: (KeyboardActionScope.() -> Unit)? = null,
)
