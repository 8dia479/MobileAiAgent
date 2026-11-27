@file:Suppress("unused", "UNUSED_PARAMETER")

package androidx.compose.ui.text.input
class KeyboardType {
    companion object {
        val Text: KeyboardType = KeyboardType()
        val Number: KeyboardType = KeyboardType()
        val Decimal: KeyboardType = KeyboardType()
        val Email: KeyboardType = KeyboardType()
        val Password: KeyboardType = KeyboardType()
        val Uri: KeyboardType = KeyboardType()
        val Phone: KeyboardType = KeyboardType()
        val NumberPassword: KeyboardType = KeyboardType()
    }
}

interface VisualTransformation

class PasswordVisualTransformation(mask: Char = '\u2022') : VisualTransformation

class ImeAction {
    companion object {
        val Done: ImeAction = ImeAction()
        val Send: ImeAction = ImeAction()
        val Search: ImeAction = ImeAction()
        val Go: ImeAction = ImeAction()
        val Next: ImeAction = ImeAction()
        val Default: ImeAction = ImeAction()
        val None: ImeAction = ImeAction()
    }
}

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
