package com.mobileaiagent.ui.nav

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Task
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Chat
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Task
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.ui.graphics.vector.ImageVector

enum class Destination(
    val route: String,
    val label: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
    val primary: Boolean,
) {
    Home("home", "Home", Icons.Outlined.Home, Icons.Filled.Home, true),
    Chat("chat", "Chat", Icons.Outlined.Chat, Icons.Filled.Chat, true),
    Workspace("workspace", "Workspace", Icons.Outlined.Folder, Icons.Filled.Folder, true),
    Tasks("tasks", "Tasks", Icons.Outlined.Task, Icons.Filled.Task, true),
    Tools("tools", "Tools", Icons.Outlined.Build, Icons.Filled.Build, false),
    Plugins("plugins", "Plugins", Icons.Outlined.Extension, Icons.Filled.Extension, false),
    Models("models", "Models", Icons.Outlined.Psychology, Icons.Filled.Psychology, false),
    Settings("settings", "Settings", Icons.Outlined.Settings, Icons.Filled.Settings, true),
    Tuning("tuning", "Advanced", Icons.Outlined.Tune, Icons.Filled.Tune, false),
    ;

    companion object {
        val bottomBar: List<Destination> = listOf(Home, Chat, Workspace, Tasks, Settings)
        fun fromRoute(route: String?): Destination = entries.firstOrNull { it.route == route } ?: Home
    }
}
