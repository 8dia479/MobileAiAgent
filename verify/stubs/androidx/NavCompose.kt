@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.navigation.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder

class NavBackStackEntry {
    val destination: androidx.navigation.NavDestination get() = androidx.navigation.NavDestination()
    val arguments: android.os.Bundle? get() = null
}

@Composable
fun rememberNavController(): NavController = NavController()

@Composable
fun NavController.currentBackStackEntryAsState(): State<NavBackStackEntry?> =
    object : State<NavBackStackEntry?> { override val value: NavBackStackEntry? = null }

@Composable
fun NavHost(
    navController: NavController,
    startDestination: String,
    modifier: androidx.compose.ui.Modifier = androidx.compose.ui.Modifier,
    route: String? = null,
    builder: NavGraphBuilder.() -> Unit,
) = Unit

fun NavGraphBuilder.composable(
    route: String,
    arguments: List<Any> = emptyList(),
    deepLinks: List<Any> = emptyList(),
    content: @Composable (NavBackStackEntry) -> Unit,
) = Unit
