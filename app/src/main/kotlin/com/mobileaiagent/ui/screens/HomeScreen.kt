package com.mobileaiagent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Chat
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Task
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.mobileaiagent.domain.model.TaskRecord
import com.mobileaiagent.domain.model.TaskStatus
import com.mobileaiagent.ui.ChatViewModel
import com.mobileaiagent.ui.TaskListViewModel
import com.mobileaiagent.ui.components.AgentActivityBanner
import com.mobileaiagent.ui.components.EmptyState
import com.mobileaiagent.ui.components.StatusChip
import com.mobileaiagent.ui.nav.Destination

@Composable
fun HomeScreen(
    chatViewModel: ChatViewModel,
    taskListViewModel: TaskListViewModel,
    onNavigate: (Destination) -> Unit,
    onOpenTask: (String) -> Unit,
) {
    val tasks by taskListViewModel.state.collectAsState()
    val chatState by chatViewModel.state.collectAsState()
    val activeTask = tasks.tasks.firstOrNull { it.status == TaskStatus.RUNNING }
        ?: tasks.tasks.firstOrNull { it.status == TaskStatus.QUEUED }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("MobileAIAgent", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Your on-device AI workspace. Tasks continue while you use other apps.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            AgentActivityBanner(
                isActive = tasks.agentActive && activeTask != null,
                title = activeTask?.let { "${it.title} • ${it.status.label}" } ?: "",
                onClick = { activeTask?.let { onOpenTask(it.id) } },
            )
        }
        item {
            Card(onClick = { onNavigate(Destination.Chat) }, Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Default.AutoAwesome, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text("Start something", style = MaterialTheme.typography.titleMedium)
                    }
                    Text(
                        "Code, CAD, 3D models, images, documents, research, automation — " +
                            "pick an execution mode in Chat (BUILD, PLAN, PLAN + BUILD).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "Current model: ${chatState.mainModel.substringAfterLast('/')} • ${chatState.settings.defaultProviderId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                QuickTile("Chat", Icons.Default.Chat, Modifier.weight(1f)) { onNavigate(Destination.Chat) }
                QuickTile("Workspace", Icons.Default.Folder, Modifier.weight(1f)) { onNavigate(Destination.Workspace) }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                QuickTile("Tasks", Icons.Default.Task, Modifier.weight(1f)) { onNavigate(Destination.Tasks) }
                QuickTile("Settings", Icons.Default.Settings, Modifier.weight(1f)) { onNavigate(Destination.Settings) }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Recent tasks", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { onNavigate(Destination.Tasks) }) { Text("All") }
            }
        }
        if (tasks.tasks.isEmpty()) {
            item {
                EmptyState(
                    Icons.Default.Task,
                    "No tasks yet",
                    "Start a conversation in Chat. Tasks are saved and survive app restarts.",
                )
            }
        } else {
            items(tasks.tasks.take(5), key = { it.id }) { task ->
                RecentTaskRow(task) { onOpenTask(task.id) }
            }
        }
    }
}

@Composable
private fun QuickTile(label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = modifier) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(label, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun RecentTaskRow(task: TaskRecord, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(task.title, maxLines = 1, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${task.mode.label} • ${java.time.Instant.ofEpochMilli(task.updatedAt).toString().take(16).replace('T', ' ')}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusChip(task.status)
        }
    }
}
