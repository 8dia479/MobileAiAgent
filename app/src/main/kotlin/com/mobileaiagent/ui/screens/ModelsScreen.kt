package com.mobileaiagent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mobileaiagent.providers.CapabilitySource
import com.mobileaiagent.providers.ModelDescriptor
import com.mobileaiagent.ui.ModelManagerViewModel
import androidx.compose.runtime.saveable.rememberSaveable

/**
 * Model Manager: dynamic discovery per provider (never a hardcoded list),
 * cached fallback, manual model IDs, and tri-state capability metadata
 * (verified / unknown) that the user can override.
 */
@Composable
fun ModelsScreen(viewModel: ModelManagerViewModel) {
    val state by viewModel.state.collectAsState()
    var manualId by rememberSaveable { mutableStateOf("") }
    var showManual by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Models", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
            IconButton(onClick = viewModel::refresh, enabled = !state.refreshing) {
                if (state.refreshing) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else Icon(Icons.Default.Refresh, "Refresh")
            }
            IconButton(onClick = { showManual = !showManual }) { Icon(Icons.Default.Add, "Add model ID") }
        }
        ScrollableTabRow(
            selectedTabIndex = state.providers.indexOfFirst { it.id == state.selectedProviderId }.coerceAtLeast(0),
            edgePadding = 8.dp,
        ) {
            state.providers.forEach { provider ->
                Tab(
                    selected = provider.id == state.selectedProviderId,
                    onClick = { viewModel.selectProvider(provider.id) },
                    text = {
                        Text(
                            provider.displayName + if (provider.id in state.configuredProviders) " ✓" else "",
                            maxLines = 1,
                        )
                    },
                )
            }
        }
        if (showManual) {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = manualId,
                    onValueChange = { manualId = it },
                    label = { Text("Model ID (exact, e.g. meta/llama-3.3-70b-instruct)") },
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                )
                Spacer(Modifier.width(8.dp))
                Button(onClick = { viewModel.addManualModel(manualId); manualId = "" }) { Text("Add") }
            }
        }
        state.message?.let {
            Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
                Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(10.dp))
            }
        }
        val provider = state.providers.firstOrNull { it.id == state.selectedProviderId }
        if (provider != null && provider.id !in state.configuredProviders) {
            Surface(color = MaterialTheme.colorScheme.errorContainer, modifier = Modifier.fillMaxWidth()) {
                Text(
                    "No API key stored for ${provider.displayName}. Add one in Settings → Providers to discover models.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(10.dp),
                )
            }
        }
        if (state.models.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "No cached models. Press refresh to discover from the provider, or add a model ID manually.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(24.dp),
                )
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    Text(
                        "${state.models.size} models" +
                            (state.lastRefresh?.let { " • cached ${java.time.Instant.ofEpochMilli(it).toString().take(16)}" } ?: ""),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                items(state.models, key = { it.id }) { model ->
                    ModelRow(model, viewModel)
                }
            }
        }
    }
}

@Composable
private fun ModelRow(model: ModelDescriptor, viewModel: ModelManagerViewModel) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(model.id, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        model.displayName.takeIf { it != model.id } ?: "",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                CapabilityBadge("vision", model.capabilities.vision)
                CapabilityBadge("tools", model.capabilities.toolCalling)
                CapabilityBadge("reason", model.capabilities.reasoning)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(model.capabilitiesSource.name.lowercase(), style = MaterialTheme.typography.labelSmall)
                model.contextWindow?.let { Text("ctx ${it / 1000}k", style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = { viewModel.selectAsMain(model.id) }) { Text("Use as main", style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = { viewModel.selectAsSecondary(model.id) }) { Text("Use as delegation", style = MaterialTheme.typography.labelSmall) }
                TextButton(onClick = { expanded = !expanded }) { Text("Capabilities", style = MaterialTheme.typography.labelSmall) }
            }
            if (expanded) {
                Text(
                    "Capabilities are tri-state: ✓ verified supported, ✗ verified unsupported, ? unknown. " +
                        "Unknown means the provider did not advertise it — the agent will not assume it.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                CapabilityToggle("Vision / image input", model.capabilities.vision) { v -> viewModel.setCapability(model.id, "vision", v) }
                CapabilityToggle("Tool calling", model.capabilities.toolCalling) { v -> viewModel.setCapability(model.id, "tools", v) }
                CapabilityToggle("Reasoning control", model.capabilities.reasoning) { v -> viewModel.setCapability(model.id, "reasoning", v) }
            }
        }
    }
}

@Composable
private fun CapabilityBadge(label: String, value: Boolean?) {
    val (text, color) = when (value) {
        true -> "✓$label" to MaterialTheme.colorScheme.primary
        false -> "✗$label" to MaterialTheme.colorScheme.outline
        null -> "?$label" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = color)
}

@Composable
private fun CapabilityToggle(label: String, value: Boolean?, onChange: (Boolean?) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.bodySmall, modifier = Modifier.width(150.dp))
        FilterChip(selected = value == true, onClick = { onChange(true) }, label = { Text("Yes") })
        FilterChip(selected = value == false, onClick = { onChange(false) }, label = { Text("No") })
        FilterChip(selected = value == null, onClick = { onChange(null) }, label = { Text("Unknown") })
    }
}
