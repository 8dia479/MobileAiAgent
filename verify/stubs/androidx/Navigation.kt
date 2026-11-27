@file:Suppress("unused", "UNUSED_PARAMETER")
package androidx.navigation

open class NavDestination {
    val id: Int get() = 0
    val route: String? get() = null
    val hierarchy: Sequence<NavDestination> get() = emptySequence()
    companion object {
        val Companion.hierarchy: Any get() = Unit
    }
}

/** `destination.hierarchy` iterable used with `.any { }`. */
val NavDestination.Hierarchy: List<NavDestination> get() = emptyList()

class NavGraph : NavDestination() {
    companion object {
        fun NavGraph.findStartDestination(): NavDestination = this
    }
}

fun NavGraph.findStartDestination(): NavDestination = this

class NavOptionsBuilder {
    var launchSingleTop: Boolean = false
    var restoreState: Boolean = false
    var popUpToRoute: String? = null
    fun popUpTo(route: String, builder: NavOptionsBuilder.() -> Unit = {}) {}
    fun popUpTo(id: Int, builder: NavOptionsBuilder.() -> Unit = {}) {}
    var saveState: Boolean = false
    var inclusive: Boolean = false
}

class PopUpToBuilder {
    var saveState: Boolean = false
    var inclusive: Boolean = false
}

class NavController {
    val graph: NavGraph get() = NavGraph()
    fun navigate(route: String) {}
    fun navigate(route: String, builder: NavOptionsBuilder.() -> Unit) {}
    fun popBackStack(): Boolean = false
}

class NavGraphBuilder
