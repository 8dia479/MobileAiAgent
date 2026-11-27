package com.mobileaiagent.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Build
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.mobileaiagent.domain.model.ReasoningEffort
import com.mobileaiagent.domain.repo.AgentSettings
import com.mobileaiagent.ui.SettingsViewModel
import com.mobileaiagent.ui.components.ReasoningSelector
import com.mobileaiagent.ui.components.SectionCard
import com.mobileaiagent.ui.components.StepperRow
import com.mobileaiagent.ui.components.ToggleRow
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    onOpenModels: () -> Unit,
    onOpenTools: () -> Unit,
    onOpenPlugins: () -> Unit,
) {
    val settings by viewModel.settings.collectAsState()
    val scope = rememberCoroutineScope()

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }
        item {
            SectionCard("AI providers", "Keys are encrypted with the Android Keystore and never leave the device.") {
                ProviderKeyRow("nvidia_nim", "NVIDIA NIM (default)", viewModel)
                ProviderKeyRow("openai", "OpenAI", viewModel)
                ProviderKeyRow("anthropic", "Anthropic", viewModel)
                ProviderKeyRow("gemini", "Google Gemini", viewModel)
                ProviderKeyRow("openai_compatible", "OpenAI-compatible endpoint", viewModel)
                ProviderKeyRow("custom", "Custom provider", viewModel)
                TextButton(onClick = onOpenModels) { Text("Discover & manage models") }
            }
        }
        item {
            SectionCard("Models & reasoning") {
                Text("Main model: ${settings.mainModel}", style = MaterialTheme.typography.bodySmall)
                Text("Delegation model: ${settings.secondaryModel ?: "not set"}", style = MaterialTheme.typography.bodySmall)
                ReasoningSelector(
                    selected = settings.mainReasoningEffort,
                    onSelect = { effort -> viewModel.update { it.copy(mainReasoningEffort = effort) } },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onOpenModels) { Text("Choose models") }
                }
            }
        }
        item {
            SectionCard("Background execution", "Tasks keep running in a foreground service with a visible notification.") {
                ToggleRow("Run tasks in background", settings.backgroundExecutionEnabled) { value ->
                    viewModel.update { it.copy(backgroundExecutionEnabled = value) }
                }
                ToggleRow("Auto-resume interrupted tasks", settings.autoResumeOnBoot) { value ->
                    viewModel.update { it.copy(autoResumeOnBoot = value) }
                }
                ToggleRow("Background work on Wi-Fi only", settings.backgroundWifiOnly) { value ->
                    viewModel.update { it.copy(backgroundWifiOnly = value) }
                }
                ToggleRow("Ask before uploading files", settings.privacyRequireApprovalForUpload) { value ->
                    viewModel.update { it.copy(privacyRequireApprovalForUpload = value) }
                }
                Text(
                    "Android may still stop background work (battery optimisers, low memory). " +
                        "Progress is always persisted; reopen the app to resume.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SectionCard("Agent limits") {
                StepperRow("Max tool iterations", settings.maxToolIterations, 1, 200) { value ->
                    viewModel.update { it.copy(maxToolIterations = value) }
                }
                StepperRow("Max output tokens", settings.maxTokensPerRequest, 256, 32_768, step = 1024) { value ->
                    viewModel.update { it.copy(maxTokensPerRequest = value) }
                }
            }
        }
        item {
            SectionCard("Tools & extensions") {
                ToggleRow("Shell/terminal access", settings.allowShell) { value ->
                    viewModel.update { it.copy(allowShell = value) }
                }
                ToggleRow("Internet access for tools", settings.internetAccess) { value ->
                    viewModel.update { it.copy(internetAccess = value) }
                }
                TextButton(onClick = onOpenTools) { Text("Manage tools & permissions") }
                TextButton(onClick = onOpenPlugins) { Text("Plugins") }
            }
        }
        item {
            SectionCard("Remote runtime (optional)", "Locally executable work never requires a server.") {
                var url by remember(settings.remoteServerUrl) { mutableStateOf(settings.remoteServerUrl ?: "") }
                var token by remember(settings.remoteServerToken) { mutableStateOf(settings.remoteServerToken ?: "") }
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("Server URL (https://…)") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text("Access token") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = {
                    viewModel.update { it.copy(remoteServerUrl = url.ifBlank { null }, remoteServerToken = token.ifBlank { null }) }
                }) { Text("Save remote settings") }
                Text(
                    "Capability discovery, task submission and fallback are implemented on the client side; " +
                        "a compatible server must be running. Local execution remains the default path.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        item {
            SectionCard("Research") {
                var endpoint by remember(settings.searchEndpoint) { mutableStateOf(settings.searchEndpoint ?: "") }
                Text(
                    "Web search uses DuckDuckGo by default (no key). Provide a custom endpoint with {query} " +
                        "placeholder to use your own search API.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = endpoint, onValueChange = { endpoint = it },
                    label = { Text("Search endpoint URL") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(onClick = {
                    viewModel.update { it.copy(searchEndpoint = endpoint.ifBlank { null }) }
                }) { Text("Save") }
            }
        }
        item {
            SectionCard("About") {
                Text(
                    "MobileAIAgent runs the agent loop on-device. Features not implemented in this build " +
                        "(STEP/IGES CAD kernel, in-app 3D viewport, native plugin code loading) are reported " +
                        "to the agent as unsupported rather than faked.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ProviderKeyRow(providerId: String, label: String, viewModel: SettingsViewModel) {
    var key by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(Unit) { saved = viewModel.hasApiKey(providerId) }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(150.dp))
            Text(
                if (saved) "key saved" else "no key",
                style = MaterialTheme.typography.labelSmall,
                color = if (saved) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = {
                scope.launch {
                    viewModel.setApiKey(providerId, key)
                    saved = viewModel.hasApiKey(providerId)
                    key = ""
                    status = "Saved"
                }
            }, enabled = key.isNotBlank()) { Text("Save") }
            TextButton(onClick = {
                scope.launch { status = viewModel.testProvider(providerId) }
            }) { Text("Test") }
        }
        status?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Divider(Modifier.padding(vertical = 4.dp))
}

