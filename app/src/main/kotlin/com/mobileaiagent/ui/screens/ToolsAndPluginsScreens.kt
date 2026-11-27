package com.mobileaiagent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mobileaiagent.tools.spec.AgentTool
import com.mobileaiagent.tools.spec.ToolCategory
import com.mobileaiagent.ui.PluginViewModel
import com.mobileaiagent.ui.ToolManagerViewModel
import com.mobileaiagent.ui.components.EmptyState
import com.mobileaiagent.ui.components.ToggleRow
import com.mobileaiagent.ui.theme.MonoStyle

@Composable
fun ToolsScreen(viewModel: ToolManagerViewModel) {
    val tools by viewModel.tools.collectAsState()
    val settings by viewModel.settings.collectAsState()
    var expandedId by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Build, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(8.dp))
            Column {
                Text("Tools", style = MaterialTheme.typography.titleLarge)
                Text(
                    "${tools.size} registered • ${tools.count { it.id !in settings.toolOverridesDisabled }} enabled",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (tools.isEmpty()) {
            EmptyState(Icons.Default.Build, "No tools", "Built-in tools register when the app initialises.")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ToolCategory.entries.forEach { category ->
                val categoryTools = tools.filter { it.category == category }
                if (categoryTools.isEmpty()) return@forEach
                item {
                    Text(category.label, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp))
                }
                items(categoryTools, key = { it.id }) { tool ->
                    ToolCard(
                        tool = tool,
                        enabled = tool.id !in settings.toolOverridesDisabled,
                        expanded = expandedId == tool.id,
                        onToggle = { enabled -> viewModel.toggle(tool.id, enabled) },
                        onExpand = { expandedId = if (expandedId == tool.id) null else tool.id },
                    )
                }
            }
        }
    }
}

@Composable
private fun ToolCard(tool: AgentTool, enabled: Boolean, expanded: Boolean, onToggle: (Boolean) -> Unit, onExpand: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(tool.name, style = MaterialTheme.typography.bodyMedium)
                    Text(tool.id + "  v" + tool.version, style = MonoStyle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (tool.mutagenic) {
                    AssistChip(onClick = onExpand, label = { Text("writes", style = MaterialTheme.typography.labelSmall) })
                }
                Switch(checked = enabled, onCheckedChange = onToggle)
            }
            Text(
                tool.description,
                style = MaterialTheme.typography.bodySmall,
                maxLines = if (expanded) 20 else 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (expanded) {
                Text(
                    "Environment: ${tool.executionEnvironment.name.lowercase()} • cancel: ${tool.cancellationSupported}",
                    style = MaterialTheme.typography.labelSmall,
                )
                if (tool.permissions.isNotEmpty()) {
                    Text("Permissions: " + tool.permissions.joinToString { it.label }, style = MaterialTheme.typography.labelSmall)
                }
                Text("Input schema:", style = MaterialTheme.typography.labelSmall)
                com.mobileaiagent.ui.components.MonoBlock(
                    com.mobileaiagent.core.json.JsonWriter.write(tool.inputSchema, pretty = true),
                    maxLines = 30,
                )
            } else {
                TextButton(onClick = onExpand) { Text("Details") }
            }
        }
    }
}

@Composable
fun PluginsScreen(viewModel: PluginViewModel) {
    val plugins by viewModel.plugins.collectAsState()
    val message by viewModel.message.collectAsState()
    var manifest by remember { mutableStateOf(defaultManifest()) }
    var showInstall by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Extension, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Plugins", style = MaterialTheme.typography.titleLarge)
            }
        }
        item {
            Text(
                "External plugins are declarative manifests (no native code loading — Android dynamic code is a " +
                    "security risk). Remote plugins call an endpoint; script plugins run in the sandboxed runtime. " +
                    "Permissions are granted per plugin and enforced before every call.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        message?.let {
            item {
                Surface(color = MaterialTheme.colorScheme.surfaceVariant) {
                    Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                        TextButton(onClick = viewModel::clearMessage) { Text("OK") }
                    }
                }
            }
        }
        item {
            Button(onClick = { showInstall = !showInstall }) {
                Text(if (showInstall) "Hide installer" else "Install from manifest")
            }
        }
        if (showInstall) {
            item {
                OutlinedTextField(
                    value = manifest,
                    onValueChange = { manifest = it },
                    label = { Text("plugin.json manifest") },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 180.dp),
                    textStyle = MonoStyle,
                )
            }
            item {
                Button(onClick = { viewModel.install(manifest) }) { Text("Install") }
            }
        }
        if (plugins.isEmpty()) {
            item {
                EmptyState(Icons.Default.Extension, "No plugins installed", "Built-in tools already cover files, terminal, research, CAD/mesh and productivity.")
            }
        } else {
            items(plugins, key = { it.id }) { plugin ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${plugin.name} v${plugin.version}", style = MaterialTheme.typography.titleSmall)
                        Text("${plugin.id} • ${plugin.kind}", style = MonoStyle)
                        if (plugin.permissions.isNotEmpty()) {
                            Text("Permissions: " + plugin.permissions.joinToString { it.label }, style = MaterialTheme.typography.labelSmall)
                        }
                        Text("sha256 ${plugin.sha256?.take(16)}…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        ToggleRow("Enabled", plugin.enabled) { enabled -> viewModel.setEnabled(plugin.id, enabled) }
                        TextButton(onClick = { viewModel.uninstall(plugin.id) }) { Text("Uninstall") }
                    }
                }
            }
        }
    }
}

private fun defaultManifest(): String = """
{
  "id": "example.echo",
  "name": "Echo tools",
  "version": "1.0.0",
  "kind": "remote",
  "endpoint": "https://example.com/agent-tools",
  "permissions": ["NETWORK"],
  "tools": [
    {
      "id": "echo",
      "name": "Echo",
      "description": "Returns the input text",
      "permissions": ["NETWORK"],
      "input_schema": {"type": "object", "properties": {"text": {"type": "string"}}}
    }
  ]
}
""".trimIndent()

@Composable
fun AdvancedScreen(viewModel: com.mobileaiagent.ui.SettingsViewModel) {
    val settings by viewModel.settings.collectAsState()
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Advanced", style = MaterialTheme.typography.titleLarge) }
        item {
            com.mobileaiagent.ui.components.SectionCard(
                "Pipes and chaining",
                "Allow 'cmd1 && cmd2' and 'cmd1 | cmd2'. Each segment is still checked against the whitelist, " +
                    "and chained commands always ask for approval. Off by default because chaining is easy to misuse.",
            ) {
                com.mobileaiagent.ui.components.ToggleRow(
                    "Allow pipes and chaining",
                    settings.allowShellChaining,
                ) { value -> viewModel.update { it.copy(allowShellChaining = value) } }
                Text(
                    if (settings.allowShellChaining) {
                        "Enabled. Command substitution (\$(...)) and backticks remain blocked regardless."
                    } else {
                        "Disabled. Single commands only; redirects within one command are allowed."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            com.mobileaiagent.ui.components.SectionCard("Shell whitelist", "Only these binaries may be executed by the terminal tool.") {
                var text by remember(settings.shellWhitelist) { mutableStateOf(settings.shellWhitelist.joinToString(", ")) }
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Comma-separated binary names") },
                )
                Button(onClick = {
                    viewModel.update { it.copy(shellWhitelist = text.split(',').map { b -> b.trim() }.filter { b -> b.isNotEmpty() }) }
                }) { Text("Save whitelist") }
            }
        }
        item {
            com.mobileaiagent.ui.components.SectionCard("Custom base URLs", "Override provider endpoints (e.g. self-hosted NIM gateway).") {
                listOf("nvidia_nim", "openai", "anthropic", "gemini", "openai_compatible", "custom").forEach { providerId ->
                    var url by remember(settings.customBaseUrls[providerId]) { mutableStateOf(settings.customBaseUrls[providerId] ?: "") }
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        OutlinedTextField(
                            value = url, onValueChange = { url = it },
                            label = { Text(providerId) }, singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = {
                            viewModel.update { s ->
                                val map = s.customBaseUrls.toMutableMap()
                                if (url.isBlank()) map.remove(providerId) else map[providerId] = url
                                s.copy(customBaseUrls = map)
                            }
                        }) { Text("Set") }
                    }
                }
            }
        }
        item {
            com.mobileaiagent.ui.components.SectionCard("Reasoning effort: secondary model") {
                val current = settings.secondaryReasoningEffort
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.mobileaiagent.domain.model.ReasoningEffort.all.forEach { effort ->
                        FilterChip(
                            selected = current == effort,
                            onClick = { viewModel.update { it.copy(secondaryReasoningEffort = effort) } },
                            label = { Text(effort.label) },
                        )
                    }
                }
                Text(
                    "The delegation model has an independent effort level (spec §3.2). Unsupported levels are shown " +
                        "disabled in Chat; none are silently converted.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
