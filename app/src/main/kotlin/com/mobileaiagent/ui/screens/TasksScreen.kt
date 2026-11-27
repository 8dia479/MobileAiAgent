package com.mobileaiagent.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Task
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mobileaiagent.domain.model.*
import com.mobileaiagent.ui.ChatViewModel
import com.mobileaiagent.ui.TaskListViewModel
import com.mobileaiagent.ui.components.*
import com.mobileaiagent.ui.theme.MonoStyle
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * Task manager: running/queued/complete tasks, per-task execution log, tool
 * journal and the active plan checklist. This screen is the user's window into
 * background execution and recovery history.
 */
@Composable
fun TasksScreen(
    viewModel: TaskListViewModel,
    onOpenTask: (String) -> Unit,
    chatViewModel: ChatViewModel,
) {
    val state by viewModel.state.collectAsState()
    val chatState by chatViewModel.state.collectAsState()
    var selectedTab by rememberSaveable { mutableStateOf(0) }
    val tabs = listOf("Tasks", "Plan", "Logs")

    Column(Modifier.fillMaxSize()) {
        TabRow(selectedTabIndex = selectedTab) {
            tabs.forEachIndexed { index, title ->
                Tab(selected = selectedTab == index, onClick = { selectedTab = index }, text = { Text(title) })
            }
        }
        when (selectedTab) {
            0 -> TasksList(state, viewModel, onOpenTask)
            1 -> PlanTab(chatState.plan)
            2 -> LogsTab(chatState.events)
        }
    }
}

@Composable
private fun TasksList(state: com.mobileaiagent.ui.TaskListUiState, viewModel: TaskListViewModel, onOpenTask: (String) -> Unit) {
    if (state.tasks.isEmpty()) {
        EmptyState(Icons.Default.Task, "No tasks", "Tasks you start in Chat appear here with full history.")
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text(
                "${state.activeCount} running • ${state.queued.size} queued • ${state.tasks.size} total",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(state.tasks, key = { it.id }) { task ->
            TaskCard(task, viewModel, onOpenTask)
        }
    }
}

@Composable
private fun TaskCard(task: TaskRecord, viewModel: TaskListViewModel, onOpenTask: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(task.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${task.mode.label} • ${task.mainReasoningEffort.label} • ${java.time.Instant.ofEpochMilli(task.createdAt).toString().take(16).replace('T', ' ')}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                StatusChip(task.status)
                IconButton(onClick = { expanded = !expanded }) {
                    Icon(Icons.Default.ArrowDropDown, contentDescription = "Details")
                }
            }
            task.lastError?.let { error ->
                Text(error.take(300), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    KeyValue("Task id", task.id, mono = true)
                    KeyValue("Project", task.projectName ?: task.projectId ?: "—")
                    KeyValue("Model", task.mainModel)
                    KeyValue("Provider", task.mainProvider)
                    task.secondaryModel?.let { KeyValue("Delegation model", it) }
                    KeyValue("Attempts", task.attemptCount.toString())
                    KeyValue("Tokens", "${task.tokensIn} in / ${task.tokensOut} out")
                    KeyValue("Auto-resume", if (task.autoResumeOnBoot) "Enabled" else "Disabled")
                    KeyValue("Created", java.time.Instant.ofEpochMilli(task.createdAt).toString())
                    if (task.lastError != null) KeyValue("Last error", task.lastError)
                    Divider(Modifier.padding(vertical = 4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when (task.status) {
                            TaskStatus.RUNNING -> {
                                TextButton(onClick = { viewModel.pause(task.id) }) { Text("Pause") }
                                TextButton(onClick = { viewModel.cancel(task.id) }) { Text("Cancel") }
                            }
                            TaskStatus.QUEUED -> TextButton(onClick = { viewModel.cancel(task.id) }) { Text("Cancel") }
                            TaskStatus.PAUSED, TaskStatus.INTERRUPTED, TaskStatus.WAITING_FOR_NETWORK,
                            TaskStatus.WAITING_FOR_USER, TaskStatus.FAILED ->
                                TextButton(onClick = { viewModel.resume(task.id) }) { Text("Resume") }
                            else -> Unit
                        }
                        TextButton(onClick = { onOpenTask(task.id) }) { Text("Open") }
                        TextButton(onClick = { viewModel.delete(task.id) }) { Text("Delete") }
                    }
                    RequestRow(task)
                }
            }
        }
    }
}

@Composable
private fun RequestRow(task: TaskRecord) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            task.request.take(600),
            style = MonoStyle,
            modifier = Modifier.padding(8.dp),
            maxLines = 8,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PlanTab(plan: Plan?) {
    if (plan == null) {
        EmptyState(
            Icons.Default.Task, "No active plan",
            "Ask the agent to plan a project (PLAN or PLAN + BUILD mode). Plans are JSON files under Plans/active.plan.json.",
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(plan.title, style = MaterialTheme.typography.titleMedium)
                    Text(plan.goal.ifBlank { "No goal statement" }, style = MaterialTheme.typography.bodySmall)
                    LinearProgressIndicator(
                        progress = { plan.progressFraction },
                        modifier = Modifier.fillMaxWidth().height(8.dp),
                    )
                    Text(
                        "Revision ${plan.revision} • ${plan.completedCount}/${plan.totalCount} completed • ${plan.status.id}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "plan_id ${plan.planId}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        items(plan.tasks, key = { it.id }) { task ->
            PlanTaskCard(task)
        }
    }
}

@Composable
private fun PlanTaskCard(task: PlanTask) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(task.id, style = MonoStyle, fontWeight = FontWeight.Bold)
                Text(task.title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                TaskStatusBadge(task.status)
            }
            if (task.description.isNotBlank()) {
                Text(
                    task.description,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = if (expanded) 20 else 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.clickable { expanded = !expanded },
                )
            }
            task.verification?.let {
                Text("Verify: $it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            task.result?.let {
                Text("Result: ${it.take(300)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
            if (task.subtasks.isNotEmpty()) {
                task.subtasks.forEach { sub ->
                    Text(
                        "   ${if (sub.status == PlanTaskStatus.COMPLETED) "☑" else "☐"} ${sub.title}",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
private fun TaskStatusBadge(status: PlanTaskStatus) {
    val color = when (status) {
        PlanTaskStatus.COMPLETED -> MaterialTheme.colorScheme.primary
        PlanTaskStatus.IN_PROGRESS -> MaterialTheme.colorScheme.tertiary
        PlanTaskStatus.FAILED, PlanTaskStatus.BLOCKED -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(status.id.replace('_', ' '), style = MaterialTheme.typography.labelSmall, color = color)
}

@Composable
private fun LogsTab(events: List<ExecutionEvent>) {
    if (events.isEmpty()) {
        EmptyState(
            androidx.compose.material.icons.Icons.Default.Task, "No activity yet",
            "Open a task in Chat; its execution log appears here and in the Chat menu.",
        )
        return
    }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        items(events.asReversed(), key = { it.id }) { event ->
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(event.type.name.lowercase(), style = MonoStyle, color = MaterialTheme.colorScheme.primary)
                    Text(event.title, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                }
                event.body?.take(300)?.let {
                    Text(it, style = MonoStyle, maxLines = 3, overflow = TextOverflow.Ellipsis,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Divider(Modifier.padding(vertical = 2.dp))
            }
        }
    }
}
