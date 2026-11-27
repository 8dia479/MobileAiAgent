@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.activity.result.contract

abstract class ActivityResultContract<I, O>

object ActivityResultContracts {
    class GetContent : ActivityResultContract<String, android.net.Uri?>()
    class OpenDocument : ActivityResultContract<Array<String>, android.net.Uri?>()
    class CreateDocument : ActivityResultContract<String, android.net.Uri?>()
    class RequestPermission : ActivityResultContract<String, Boolean>()
}
