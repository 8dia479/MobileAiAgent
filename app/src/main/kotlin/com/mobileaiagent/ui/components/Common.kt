package com.mobileaiagent.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.mobileaiagent.domain.model.*
import com.mobileaiagent.ui.theme.MonoStyle

@Composable
fun StatusChip(status: TaskStatus, modifier: Modifier = Modifier) {
    val (icon, label) = when (status) {
        TaskStatus.QUEUED -> Icons.Filled.Schedule to "Queued"
        TaskStatus.RUNNING -> Icons.Filled.Sync to "Running"
        TaskStatus.PAUSED -> Icons.Filled.PauseCircle to "Paused"
        TaskStatus.WAITING_FOR_USER -> Icons.Filled.Schedule to "Needs input"
        TaskStatus.WAITING_FOR_NETWORK -> Icons.Filled.WifiOff to "Waiting for network"
        TaskStatus.COMPLETED -> Icons.Filled.CheckCircle to "Completed"
        TaskStatus.FAILED -> Icons.Filled.Error to "Failed"
        TaskStatus.CANCELLED -> Icons.Filled.Cancel to "Cancelled"
        TaskStatus.INTERRUPTED -> Icons.Filled.PlayCircle to "Interrupted"
    }
    val color = when (status) {
        TaskStatus.RUNNING -> Color(0xFF2E7D32)
        TaskStatus.COMPLETED -> Color(0xFF2E7D32)
        TaskStatus.FAILED -> MaterialTheme.colorScheme.error
        TaskStatus.CANCELLED -> MaterialTheme.colorScheme.outline
        TaskStatus.WAITING_FOR_NETWORK -> Color(0xFFB26A00)
        TaskStatus.WAITING_FOR_USER -> Color(0xFF7B4E9B)
        TaskStatus.PAUSED -> Color(0xFF555F6E)
        else -> MaterialTheme.colorScheme.primary
    }
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = color.copy(alpha = 0.12f),
        border = BorderStroke(1.dp, color.copy(alpha = 0.4f)),
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(14.dp))
            Text(label, color = color, style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
fun SectionCard(
    title: String,
    subtitle: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
        }
    }
}

@Composable
fun KeyValue(label: String, value: String, mono: Boolean = false) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(130.dp))
        Text(
            value,
            style = if (mono) MonoStyle else MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
fun MonoBlock(text: String, modifier: Modifier = Modifier, maxLines: Int = 30) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text,
            style = MonoStyle.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier.padding(10.dp).heightIn(max = 320.dp),
            maxLines = maxLines,
            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
        )
    }
}

@Composable
fun EmptyState(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, message: String) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(40.dp), tint = MaterialTheme.colorScheme.primary)
        Text(title, style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
fun ModeSelector(selected: ExecutionMode, onSelect: (ExecutionMode) -> Unit, enabled: Boolean = true) {
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        ExecutionMode.entries.forEach { mode ->
            FilterChip(
                selected = selected == mode,
                onClick = { if (enabled) onSelect(mode) },
                enabled = enabled,
                label = { Text(mode.label) },
            )
        }
    }
}

@Composable
fun ReasoningSelector(
    selected: ReasoningEffort,
    supported: Set<ReasoningEffort> = ReasoningEffort.entries.toSet(),
    onSelect: (ReasoningEffort) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.horizontalScroll(androidx.compose.foundation.rememberScrollState()),
        ) {
            ReasoningEffort.all.forEach { effort ->
                val isSupported = effort in supported
                FilterChip(
                    selected = selected == effort,
                    onClick = { if (isSupported) onSelect(effort) },
                    enabled = isSupported,
                    label = { Text(effort.label) },
                )
            }
        }
        if (supported.size < ReasoningEffort.entries.size) {
            Text(
                "Unsupported for this model: " +
                    ReasoningEffort.all.filter { it !in supported }.joinToString { it.label },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun AgentActivityBanner(isActive: Boolean, title: String, onClick: () -> Unit) {
    if (!isActive) return
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
            Column(Modifier.weight(1f)) {
                Text("Agent running in background", style = MaterialTheme.typography.labelLarge)
                Text(title, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            Icon(Icons.Filled.PlayCircle, contentDescription = "Open")
        }
    }
}

@Composable
fun ToggleRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
fun StepperRow(label: String, value: Int, min: Int, max: Int, step: Int = 1, onChange: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = { onChange((value - step).coerceAtLeast(min)) }) { Text("−") }
        Text("$value", style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = { onChange((value + step).coerceAtMost(max)) }) { Text("+") }
    }
}
