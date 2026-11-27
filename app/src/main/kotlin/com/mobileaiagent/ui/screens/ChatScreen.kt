package com.mobileaiagent.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mobileaiagent.domain.model.*
import com.mobileaiagent.ui.ChatUiState
import com.mobileaiagent.ui.ChatViewModel
import com.mobileaiagent.ui.components.*
import com.mobileaiagent.ui.theme.MonoStyle
import kotlinx.coroutines.launch
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * Chat is a view over a persisted task. Every message shown here came from the
 * database, not from transient composition state — reopening the app after a
 * process kill renders the same conversation and lets the user resume.
 */
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    onOpenPlan: () -> Unit,
    onOpenSettings: () -> Unit,
    onOpenModels: () -> Unit,
) {
    val state by viewModel.state.collectAsState()
    var input by rememberSaveable { mutableStateOf("") }
    var showMenu by remember { mutableStateOf(false) }
    var showEvents by remember { mutableStateOf(false) }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(state.messages.size, state.events.size) {
        if (state.messages.isNotEmpty()) {
            listState.animateScrollToItem((state.messages.size - 1).coerceAtLeast(0))
        }
    }

    Column(Modifier.fillMaxSize()) {
        ChatTopBar(
            state = state,
            onOpenSettings = onOpenSettings,
            onNewChat = { viewModel.newChat() },
            onRefreshModels = { viewModel.refreshModels() },
            showMenu = showMenu,
            onMenuChange = { showMenu = it },
            showEvents = showEvents,
            onToggleEvents = { showEvents = !showEvents },
        )
        Divider()
        state.plan?.let { plan ->
            PlanChecklistCard(plan = plan, onOpenPlan = onOpenPlan)
        }
        if (state.task != null) {
            TaskControlsBar(
                task = state.task!!,
                isLive = state.isLive,
                onPause = viewModel::pause,
                onResume = viewModel::resume,
                onCancel = viewModel::cancel,
            )
        }
        state.error?.let { error ->
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(error, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::clearError) { Text("Dismiss") }
                }
            }
        }
        Box(Modifier.weight(1f)) {
            if (showEvents) {
                EventTimeline(state)
            } else if (state.messages.isEmpty() && state.task == null) {
                ChatEmptyState(onSuggestion = { suggestion ->
                    input = suggestion
                })
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        MessageBubble(message)
                    }
                    if (state.isLive) {
                        item { TypingIndicator() }
                    }
                }
            }
        }
        ChatComposer(
            value = input,
            onValueChange = { input = it },
            enabled = !state.busySending,
            mode = state.selectedMode,
            onModeChange = viewModel::setMode,
            mainModel = state.mainModel,
            onModelClick = onOpenModels,
            reasoning = state.reasoningEffort,
            onReasoningChange = viewModel::setReasoning,
            projects = state.projects,
            selectedProjectId = state.selectedProjectId,
            onProjectChange = { viewModel.selectProject(it) },
            onSend = {
                if (input.isNotBlank()) {
                    viewModel.send(input)
                    input = ""
                    scope.launch { listState.scrollToItem(0) }
                }
            },
            isRunning = state.task?.status == TaskStatus.RUNNING,
            onCancel = viewModel::cancel,
        )
    }
}

@Composable
private fun ChatTopBar(
    state: ChatUiState,
    onOpenSettings: () -> Unit,
    onNewChat: () -> Unit,
    onRefreshModels: () -> Unit,
    showMenu: Boolean,
    onMenuChange: (Boolean) -> Unit,
    showEvents: Boolean,
    onToggleEvents: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(state.task?.title ?: "New task", style = MaterialTheme.typography.titleMedium, maxLines = 1)
            Text(
                text = state.task?.let { "Model: ${it.mainModel.ifBlank { state.mainModel }} • ${it.mode.label} • ${it.mainReasoningEffort.label}" }
                    ?: "Explain, build, design, research — anything",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        state.task?.let { StatusChip(it.status) }
        Box {
            IconButton(onClick = { onMenuChange(true) }) {
                Icon(Icons.Default.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = showMenu, onDismissRequest = { onMenuChange(false) }) {
                DropdownMenuItem(text = { Text("New chat") }, onClick = { onMenuChange(false); onNewChat() })
                DropdownMenuItem(text = { Text("Refresh model list") }, onClick = { onMenuChange(false); onRefreshModels() })
                DropdownMenuItem(
                    text = { Text(if (showEvents) "Show conversation" else "Show execution log") },
                    onClick = { onMenuChange(false); onToggleEvents() },
                )
                DropdownMenuItem(text = { Text("Settings") }, onClick = { onMenuChange(false); onOpenSettings() })
            }
        }
    }
}

@Composable
private fun PlanChecklistCard(plan: Plan, onOpenPlan: () -> Unit) {
    var expanded by rememberSaveable { mutableStateOf(true) }
    Card(
        onClick = { expanded = !expanded },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Plan", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
                Text("${plan.completedCount}/${plan.totalCount}", style = MaterialTheme.typography.labelMedium)
                LinearProgressIndicator(
                    progress = { plan.progressFraction },
                    modifier = Modifier.width(80.dp).height(6.dp),
                )
            }
            AnimatedVisibility(visible = expanded) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    plan.tasks.take(8).forEach { task -> ChecklistRow(task) }
                    if (plan.tasks.size > 8) {
                        Text("+${plan.tasks.size - 8} more in Tasks", style = MaterialTheme.typography.bodySmall)
                    }
                    TextButton(onClick = onOpenPlan) { Text("Open task manager") }
                }
            }
        }
    }
}

@Composable
fun ChecklistRow(task: PlanTask) {
    val (mark, color) = when (task.status) {
        PlanTaskStatus.COMPLETED -> "✓" to MaterialTheme.colorScheme.primary
        PlanTaskStatus.IN_PROGRESS -> "◐" to MaterialTheme.colorScheme.tertiary
        PlanTaskStatus.FAILED -> "✗" to MaterialTheme.colorScheme.error
        PlanTaskStatus.BLOCKED -> "⊘" to MaterialTheme.colorScheme.error
        PlanTaskStatus.CANCELLED -> "–" to MaterialTheme.colorScheme.outline
        PlanTaskStatus.PENDING -> "○" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(mark, color = color, style = MonoStyle.copy(fontWeight = FontWeight.Bold))
        Text(
            task.title,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = if (task.status == PlanTaskStatus.COMPLETED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

@Composable
private fun TaskControlsBar(
    task: TaskRecord,
    isLive: Boolean,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = buildString {
                    append("Task ${task.id.takeLast(6)}")
                    if (task.attemptCount > 1) append(" • attempt ${task.attemptCount}")
                    if (task.tokensIn + task.tokensOut > 0) append(" • ${task.tokensIn + task.tokensOut} tokens")
                },
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.weight(1f),
            )
            when (task.status) {
                TaskStatus.RUNNING -> {
                    TextButton(onClick = onPause) { Text("Pause") }
                    TextButton(onClick = onCancel) { Text("Stop") }
                }
                TaskStatus.QUEUED -> TextButton(onClick = onCancel) { Text("Cancel") }
                TaskStatus.PAUSED, TaskStatus.INTERRUPTED, TaskStatus.WAITING_FOR_NETWORK, TaskStatus.WAITING_FOR_USER ->
                    TextButton(onClick = onResume) { Text("Resume") }
                TaskStatus.FAILED -> TextButton(onClick = onResume) { Text("Retry") }
                else -> Unit
            }
            if (isLive) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun EventTimeline(state: ChatUiState) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        items(state.events, key = { it.id }) { event ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Top) {
                Text(
                    java.time.Instant.ofEpochMilli(event.timestamp)
                        .atZone(java.time.ZoneId.systemDefault())
                        .toLocalTime().toString().take(8),
                    style = MonoStyle,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(Modifier.weight(1f)) {
                    Text(event.title, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Medium)
                    event.body?.take(400)?.let {
                        Text(it, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 6)
                    }
                }
            }
            Divider()
        }
    }
}

@Composable
private fun MessageBubble(message: ChatMessage) {
    val isUser = message.role == ChatMessage.Role.USER
    val isTool = message.role == ChatMessage.Role.TOOL
    val isSystem = message.role == ChatMessage.Role.SYSTEM
    val alignment: Alignment.Horizontal = if (isUser) Alignment.End else Alignment.Start
    val container = when {
        isUser -> MaterialTheme.colorScheme.primaryContainer
        isTool -> MaterialTheme.colorScheme.surfaceVariant
        isSystem -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surface
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = alignment) {
        if (isTool && message.toolName != null) {
            Text(
                "⚙ ${message.toolName}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 2.dp, start = 4.dp),
            )
        }
        if (isSystem) {
            Text(
                "↺ ${message.content}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
        Surface(
            shape = RoundedCornerShape(
                topStart = 14.dp, topEnd = 14.dp,
                bottomStart = if (isUser) 14.dp else 4.dp,
                bottomEnd = if (isUser) 4.dp else 14.dp,
            ),
            color = container,
            tonalElevation = if (isUser) 0.dp else 1.dp,
            modifier = Modifier.widthIn(max = 620.dp),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                message.reasoning?.takeIf { it.isNotBlank() }?.let { reasoning ->
                    var expanded by remember { mutableStateOf(false) }
                    Text(
                        (if (expanded) reasoning else "Thinking: " + reasoning.take(140) + "…"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.clickable { expanded = !expanded },
                    )
                }
                if (isTool) {
                    Text(message.content.take(1200), style = MonoStyle, maxLines = 24, overflow = TextOverflow.Ellipsis)
                } else {
                    MarkdownText(message.content)
                }
                if (message.isPartial) {
                    Text("(partial — task stopped mid-response)", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
        }
    }
}

/**
 * Small Markdown renderer: headings, bullet lists, code fences and inline code.
 * Deliberately not a full CommonMark implementation (no HTML, no tables yet).
 */
@Composable
fun MarkdownText(text: String) {
    val blocks = remember(text) { parseMarkdownBlocks(text) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        blocks.forEach { block ->
            when (block) {
                is MdBlock.Heading -> Text(block.text, style = MaterialTheme.typography.titleMedium)
                is MdBlock.Paragraph -> Text(block.text, style = MaterialTheme.typography.bodyMedium)
                is MdBlock.Bullet -> Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("•", style = MaterialTheme.typography.bodyMedium)
                    Text(block.text, style = MaterialTheme.typography.bodyMedium)
                }
                is MdBlock.Code -> MonoBlock(block.text, maxLines = 40)
            }
        }
    }
}

sealed class MdBlock {
    data class Heading(val text: String) : MdBlock()
    data class Paragraph(val text: String) : MdBlock()
    data class Bullet(val text: String) : MdBlock()
    data class Code(val text: String) : MdBlock()
}

fun parseMarkdownBlocks(text: String): List<MdBlock> {
    val blocks = mutableListOf<MdBlock>()
    val lines = text.split("\n")
    var i = 0
    val paragraph = StringBuilder()
    fun flushParagraph() {
        if (paragraph.isNotBlank()) {
            blocks.add(MdBlock.Paragraph(paragraph.toString().trim()))
            paragraph.setLength(0)
        }
    }
    while (i < lines.size) {
        val line = lines[i]
        when {
            line.startsWith("```") -> {
                flushParagraph()
                val lang = line.removePrefix("```").trim()
                val code = StringBuilder()
                i++
                while (i < lines.size && !lines[i].startsWith("```")) {
                    code.appendLine(lines[i]); i++
                }
                blocks.add(MdBlock.Code(if (lang.isNotEmpty()) "// $lang\n${code.toString().trimEnd()}" else code.toString().trimEnd()))
            }
            line.startsWith("### ") -> { flushParagraph(); blocks.add(MdBlock.Heading(line.removePrefix("### "))) }
            line.startsWith("## ") -> { flushParagraph(); blocks.add(MdBlock.Heading(line.removePrefix("## "))) }
            line.startsWith("# ") -> { flushParagraph(); blocks.add(MdBlock.Heading(line.removePrefix("# "))) }
            line.trimStart().startsWith("- ") || line.trimStart().startsWith("* ") -> {
                flushParagraph()
                blocks.add(MdBlock.Bullet(line.trimStart().removePrefix("- ").removePrefix("* ")))
            }
            line.isBlank() -> flushParagraph()
            else -> paragraph.appendLine(line)
        }
        i++
    }
    flushParagraph()
    return blocks
}

@Composable
private fun TypingIndicator() {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
        Text("Agent working…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ChatEmptyState(onSuggestion: (String) -> Unit) {
    val suggestions = listOf(
        "Plan a 3D printable phone stand and generate the STL",
        "Create a CSV of 2026 public holidays and summarise it",
        "Write a Kotlin file that parses CSV and add tests",
        "Research the best open-source 3D libraries and summarise trade-offs",
    )
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(24.dp))
        Text("What should we build?", style = MaterialTheme.typography.titleLarge)
        Text(
            "Tasks keep running in the background when you leave the app.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        suggestions.forEach { suggestion ->
            OutlinedButton(onClick = { onSuggestion(suggestion) }, modifier = Modifier.fillMaxWidth()) {
                Text(suggestion, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun ChatComposer(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    mode: ExecutionMode,
    onModeChange: (ExecutionMode) -> Unit,
    mainModel: String,
    onModelClick: () -> Unit,
    reasoning: ReasoningEffort,
    onReasoningChange: (ReasoningEffort) -> Unit,
    projects: List<ProjectInfo>,
    selectedProjectId: String?,
    onProjectChange: (String) -> Unit,
    onSend: () -> Unit,
    isRunning: Boolean,
    onCancel: () -> Unit,
) {
    var showOptions by remember { mutableStateOf(false) }
    Surface(tonalElevation = 3.dp) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                AssistChip(onClick = onModelClick, label = {
                    Text(mainModel.substringAfterLast('/').take(24), maxLines = 1)
                })
                AssistChip(
                    onClick = { showOptions = !showOptions },
                    label = { Text("${mode.label} • ${reasoning.label}") },
                )
                if (projects.size > 1) {
                    AssistChip(
                        onClick = { onProjectChange(projects.first { it.id != selectedProjectId }.id) },
                        label = { Text(projects.firstOrNull { it.id == selectedProjectId }?.name ?: "Project") },
                    )
                }
            }
            AnimatedVisibility(visible = showOptions) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    Text("Execution mode", style = MaterialTheme.typography.labelMedium)
                    ModeSelector(mode, onModeChange, enabled)
                    Text("Reasoning effort", style = MaterialTheme.typography.labelMedium)
                    ReasoningSelector(reasoning, ReasoningEffort.entries.toSet(), onReasoningChange)
                }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Describe the task…") },
                    maxLines = 6,
                    shape = RoundedCornerShape(18.dp),
                    keyboardActions = KeyboardActions(onSend = { onSend() }),
                )
                if (isRunning) {
                    FilledIconButton(onClick = onCancel) {
                        Icon(Icons.Default.Stop, contentDescription = "Stop task")
                    }
                } else {
                    FilledIconButton(onClick = onSend, enabled = enabled && value.isNotBlank()) {
                        Icon(Icons.Default.ArrowUpward, contentDescription = "Send")
                    }
                }
            }
        }
    }
}
