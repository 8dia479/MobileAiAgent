@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.compose.ui.platform
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.ui.unit.Density

object LocalContext {
    val current: android.content.Context
        @Composable get() = throw UnsupportedOperationException("stub")
}

object LocalConfiguration {
    val current: android.content.res.Configuration
        @Composable get() = throw UnsupportedOperationException("stub")
}

object LocalDensity {
    val current: Density
        @Composable get() = throw UnsupportedOperationException("stub")
}

object LocalLifecycleOwner {
    val current: androidx.lifecycle.LifecycleOwner
        @Composable get() = throw UnsupportedOperationException("stub")
}
