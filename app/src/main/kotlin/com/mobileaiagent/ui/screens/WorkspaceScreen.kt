package com.mobileaiagent.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mobileaiagent.core.util.humanBytes
import com.mobileaiagent.ui.FileNode
import com.mobileaiagent.ui.PreviewKind
import com.mobileaiagent.ui.WorkspaceViewModel
import com.mobileaiagent.ui.components.MonoBlock
import com.mobileaiagent.ui.theme.MonoStyle

/**
 * Workspace: a real project browser with previews.
 *
 * Text/JSON/plan files are shown inline; meshes get geometry stats; images are
 * opened externally via the system viewer (no in-app raster viewer yet); PDFs and
 * office binaries are identified and handed to the agent for extraction.
 */
@Composable
fun WorkspaceScreen(viewModel: WorkspaceViewModel, onAskAgent: (String) -> Unit) {
    val state by viewModel.state.collectAsState()
    var showProjectMenu by remember { mutableStateOf(false) }
    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) {
            val name = uri.lastPathSegment?.substringAfterLast('/') ?: "imported_file"
            viewModel.importUri(uri, name)
        }
    }
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (state.currentPath.isNotEmpty()) {
                IconButton(onClick = viewModel::navigateUp) { Icon(Icons.Default.ArrowBack, "Up") }
            }
            Column(Modifier.weight(1f)) {
                Text(
                    state.breadcrumbs.joinToString(" / ").ifBlank { "Workspace" },
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${state.nodes.size} items",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                TextButton(onClick = { showProjectMenu = true }) {
                    Text(state.projects.firstOrNull { it.id == state.currentProjectId }?.name ?: "Project")
                }
                DropdownMenu(expanded = showProjectMenu, onDismissRequest = { showProjectMenu = false }) {
                    state.projects.forEach { project ->
                        DropdownMenuItem(
                            text = { Text(project.name) },
                            onClick = { showProjectMenu = false; viewModel.selectProject(project.id) },
                        )
                    }
                }
            }
            IconButton(onClick = { importLauncher.launch("*/*") }) { Icon(Icons.Default.Add, "Import file") }
            IconButton(onClick = viewModel::refresh) { Icon(Icons.Default.Refresh, "Refresh") }
        }
        Divider()
        state.importMessage?.let {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, modifier = Modifier.fillMaxWidth()) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(it, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    TextButton(onClick = viewModel::clearMessages) { Text("OK") }
                }
            }
        }
        state.selectedFile?.let { file ->
            SelectedFilePanel(
                file = file,
                state = state,
                onClose = { viewModel.openFolder(state.currentPath) },
                onAskAgent = { onAskAgent(file.path) },
                onOpenExternal = {
                    scope.launch {
                        val target = withContext(Dispatchers.IO) { viewModel.absoluteFile(file) } ?: return@launch
                        val uri = runCatching {
                            androidx.core.content.FileProvider.getUriForFile(
                                context, "${context.packageName}.fileprovider", target,
                            )
                        }.getOrNull() ?: return@launch
                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                            setDataAndType(uri, context.contentResolver.getType(uri) ?: "*/*")
                            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        }
                        runCatching { context.startActivity(intent) }
                    }
                },
                onDelete = { viewModel.deleteFile(file) },
            )
            Divider()
        }
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(vertical = 4.dp)) {
            items(state.nodes, key = { it.path }) { node ->
                FileRow(node) {
                    if (node.isDirectory) viewModel.openFolder(node.path) else viewModel.openFile(node)
                }
            }
        }
    }
}

@Composable
private fun FileRow(node: FileNode, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(node.name, style = MaterialTheme.typography.bodyMedium, maxLines = 1) },
        leadingContent = {
            Icon(
                if (node.isDirectory) Icons.Default.Folder else Icons.Default.InsertDriveFile,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
            )
        },
        supportingContent = if (!node.isDirectory) {
            { Text(humanBytes(node.sizeBytes), style = MaterialTheme.typography.labelSmall) }
        } else null,
        modifier = Modifier.fillMaxWidth().clickableRow(onClick),
    )
}

private fun Modifier.clickableRow(onClick: () -> Unit): Modifier =
    this.then(Modifier.clickable(onClick = onClick))

@Composable
private fun MissingProject() {
    Text("Select a project to preview this file.", style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun SelectedFilePanel(
    file: FileNode,
    state: com.mobileaiagent.ui.WorkspaceUiState,
    onClose: () -> Unit,
    onAskAgent: () -> Unit,
    onOpenExternal: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(Modifier.fillMaxWidth().padding(8.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Description, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(file.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(
                        "${state.previewKind.name.lowercase()} • ${humanBytes(file.sizeBytes)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Delete") }
            }
            // Every branch below renders a real viewer or states a real limitation.
            val root = state.projects.firstOrNull { it.id == state.currentProjectId }?.rootPath
            val absolute = root?.let { java.io.File(it, file.path) }
            when (state.previewKind) {
                PreviewKind.TEXT, PreviewKind.PLAN -> state.previewText?.let {
                    MonoBlock(it, maxLines = 40)
                }
                PreviewKind.BINARY_INFO, PreviewKind.UNSUPPORTED, PreviewKind.CAD_UNSUPPORTED -> state.previewText?.let {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (state.previewKind == PreviewKind.CAD_UNSUPPORTED) MaterialTheme.colorScheme.surfaceVariant
                        else MaterialTheme.colorScheme.surfaceVariant,
                    ) {
                        Text(it, style = MonoStyle, modifier = Modifier.padding(10.dp))
                    }
                }
                PreviewKind.IMAGE -> if (absolute != null) ImageViewerPanel(absolute) else MissingProject()
                PreviewKind.MESH -> if (absolute != null) MeshViewerPanel(absolute) else MissingProject()
                PreviewKind.PDF -> if (absolute != null) PdfViewerPanel(absolute) else MissingProject()
                PreviewKind.DOCX -> if (absolute != null) DocxViewerPanel(absolute) else MissingProject()
                PreviewKind.SHEET -> if (absolute != null) {
                    SheetViewerPanel(absolute, csv = file.name.endsWith(".csv") || file.name.endsWith(".tsv"))
                } else MissingProject()
                PreviewKind.NONE -> Text("Select a file", style = MaterialTheme.typography.bodySmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onAskAgent) { Text("Ask agent about this file") }
                OutlinedButton(onClick = onOpenExternal) { Text("Open") }
                TextButton(onClick = onClose) { Text("Close") }
            }
        }
    }
}
