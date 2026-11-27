package com.mobileaiagent.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.mobileaiagent.MobileAIAgentApp
import com.mobileaiagent.ui.nav.Destination
import com.mobileaiagent.ui.screens.*

/**
 * Root composable: adaptive navigation shell.
 *
 * - Phones: single-pane NavHost with a bottom bar.
 * - Medium/large (tablets, foldables, freeform): NavigationRail on the left.
 *
 * The NavHost is the only place UI navigation happens; none of it touches task
 * execution, so rotating, split-screening or backgrounding the app is safe.
 */
@Composable
fun AppRoot(
    initialTaskId: String? = null,
    onTaskOpened: () -> Unit = {},
) {
    val context = LocalContext.current
    val app = context.applicationContext as MobileAIAgentApp
    val factory = remember { AppViewModelFactory.factory(app) }
    val navController = rememberNavController()
    val backStack by navController.currentBackStackEntryAsState()
    val current = Destination.fromRoute(backStack?.destination?.route)
    val windowSizeClass = currentWindowSizeClass()

    val chatViewModel: ChatViewModel = viewModel(factory = factory, key = "chat")
    val taskListViewModel: TaskListViewModel = viewModel(factory = factory, key = "tasks")

    LaunchedEffect(initialTaskId) {
        if (initialTaskId != null) {
            chatViewModel.openTask(initialTaskId)
            navController.navigate(Destination.Chat.route) { launchSingleTop = true }
            onTaskOpened()
        }
    }

    Scaffold(
        bottomBar = {
            if (windowSizeClass == WindowSize.MEDIUM_OR_LARGER) return@Scaffold
            NavigationBar {
                Destination.bottomBar.forEach { destination ->
                    val selected = backStack?.destination?.hierarchy?.any { it.route == destination.route } == true
                    NavigationBarItem(
                        selected = selected,
                        onClick = {
                            navController.navigate(destination.route) {
                                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                launchSingleTop = true
                                restoreState = true
                            }
                        },
                        icon = {
                            Icon(
                                if (selected) destination.selectedIcon else destination.icon,
                                contentDescription = destination.label,
                            )
                        },
                        label = { Text(destination.label) },
                    )
                }
            }
        },
    ) { padding ->
        Row(Modifier.fillMaxSize().padding(padding)) {
            if (windowSizeClass == WindowSize.MEDIUM_OR_LARGER) {
                NavigationRail {
                    Spacer(Modifier.weight(1f))
                    Destination.entries.forEach { destination ->
                        val selected = backStack?.destination?.hierarchy?.any { it.route == destination.route } == true
                        NavigationRailItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(if (selected) destination.selectedIcon else destination.icon, destination.label) },
                            label = { Text(destination.label) },
                        )
                    }
                    Spacer(Modifier.weight(1f))
                }
            }
            NavHost(
                navController = navController,
                startDestination = Destination.Home.route,
                modifier = Modifier.weight(1f).fillMaxHeight(),
            ) {
                composable(Destination.Home.route) {
                    HomeScreen(
                        chatViewModel = chatViewModel,
                        taskListViewModel = taskListViewModel,
                        onNavigate = { destination -> navController.navigate(destination.route) { launchSingleTop = true } },
                        onOpenTask = { taskId ->
                            chatViewModel.openTask(taskId)
                            navController.navigate(Destination.Chat.route) { launchSingleTop = true }
                        },
                    )
                }
                composable(Destination.Chat.route) {
                    ChatScreen(
                        viewModel = chatViewModel,
                        onOpenPlan = { navController.navigate(Destination.Tasks.route) { launchSingleTop = true } },
                        onOpenSettings = { navController.navigate(Destination.Settings.route) { launchSingleTop = true } },
                        onOpenModels = { navController.navigate(Destination.Models.route) { launchSingleTop = true } },
                    )
                }
                composable(Destination.Workspace.route) {
                    WorkspaceScreen(
                        viewModel = viewModel(factory = factory, key = "workspace"),
                        onAskAgent = { path ->
                            chatViewModel.send("Inspect the project file '$path' and tell me what it contains and how it could be used.")
                            navController.navigate(Destination.Chat.route) { launchSingleTop = true }
                        },
                    )
                }
                composable(Destination.Tasks.route) {
                    TasksScreen(
                        viewModel = taskListViewModel,
                        onOpenTask = { taskId ->
                            chatViewModel.openTask(taskId)
                            navController.navigate(Destination.Chat.route) { launchSingleTop = true }
                        },
                        chatViewModel = chatViewModel,
                    )
                }
                composable(Destination.Tools.route) {
                    ToolsScreen(viewModel = viewModel(factory = factory, key = "tools"))
                }
                composable(Destination.Plugins.route) {
                    PluginsScreen(viewModel = viewModel(factory = factory, key = "plugins"))
                }
                composable(Destination.Models.route) {
                    ModelsScreen(viewModel = viewModel(factory = factory, key = "models"))
                }
                composable(Destination.Settings.route) {
                    SettingsScreen(
                        viewModel = viewModel(factory = factory, key = "settings"),
                        onOpenModels = { navController.navigate(Destination.Models.route) { launchSingleTop = true } },
                        onOpenTools = { navController.navigate(Destination.Tools.route) { launchSingleTop = true } },
                        onOpenPlugins = { navController.navigate(Destination.Plugins.route) { launchSingleTop = true } },
                    )
                }
                composable(Destination.Tuning.route) {
                    AdvancedScreen(viewModel = viewModel(factory = factory, key = "settings"))
                }
            }
        }
    }
}

enum class WindowSize { COMPACT, MEDIUM_OR_LARGER }

@Composable
private fun currentWindowSizeClass(): WindowSize {
    val width = androidx.compose.ui.platform.LocalConfiguration.current.screenWidthDp
    return if (width >= 720) WindowSize.MEDIUM_OR_LARGER else WindowSize.COMPACT
}
