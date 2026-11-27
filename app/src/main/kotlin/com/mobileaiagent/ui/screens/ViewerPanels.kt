package com.mobileaiagent.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.mobileaiagent.tools.builtin.Geometry
import com.mobileaiagent.tools.builtin.Png
import com.mobileaiagent.ui.viewer.DocumentReader
import com.mobileaiagent.ui.viewer.ImageLoader
import com.mobileaiagent.ui.viewer.MeshRenderer
import com.mobileaiagent.ui.components.MonoBlock
import com.mobileaiagent.ui.theme.MonoStyle
import java.io.File

/**
 * Real file viewers for the Workspace.
 *
 * What is genuinely implemented here:
 *  - 3D: orbit/zoom/pan of STL/OBJ/GLB meshes using the software rasterizer
 *    (MeshRenderer). The PNG bytes are decoded back into a Bitmap for display.
 *  - Images: subsampled BitmapFactory decode, no OOM on large photos.
 *  - PDF: text extraction with page count; scanned/encrypted files are called out.
 *  - DOCX: paragraphs + tables rendered natively (no Word viewer).
 *  - XLSX/CSV: scrollable cell grid.
 *
 * Deliberately not implemented (reported to the user instead of faked): STEP and
 * IGES geometry (they need a B-rep kernel), animated GIF/video playback, PDF page
 * *rasterisation*, and charts in spreadsheets.
 */

// ---------------------------------------------------------------------- 3D

private data class MeshPreview(
    val mesh: Geometry.Mesh? = null,
    val error: String? = null,
    val loading: Boolean = true,
)

private fun decodeMesh(file: File): MeshPreview = try {
    val bytes = file.readBytes()
    val ext = file.extension.lowercase()
    val mesh = when (ext) {
        "stl" -> com.mobileaiagent.tools.builtin.MeshReader.readStl(bytes)
        "obj" -> com.mobileaiagent.tools.builtin.MeshReader.readObj(bytes)
        "glb" -> com.mobileaiagent.tools.builtin.MeshReader.readGlb(bytes)
        else -> null
    }
    if (mesh == null || mesh.triangleCount == 0) {
        MeshPreview(error = "Could not read geometry from ${file.name}.", loading = false)
    } else {
        MeshPreview(mesh = mesh, loading = false)
    }
} catch (e: Exception) {
    MeshPreview(error = e.message ?: "Failed to read mesh", loading = false)
} catch (oom: OutOfMemoryError) {
    MeshPreview(error = "File is too large to preview on this device (${file.length()} bytes).", loading = false)
}

@Composable
fun MeshViewerPanel(file: File, modifier: Modifier = Modifier) {
    var preview by remember(file.path) { mutableStateOf(MeshPreview()) }
    LaunchedEffect(file.path) {
        preview = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { decodeMesh(file) }
    }
    when {
        preview.loading -> Row(
            modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            Text("Reading geometry…", style = MaterialTheme.typography.bodySmall)
        }

        preview.error != null -> MonoSizedText(preview.error ?: "Unknown error")

        else -> {
            val mesh = preview.mesh!!
            var camera by remember(file.path) { mutableStateOf(MeshRenderer.Camera()) }
            var bitmap by remember(file.path) { mutableStateOf<Bitmap?>(null) }
            var stats by remember(file.path) { mutableStateOf<MeshRenderer.RenderStats?>(null) }
            var autoRotate by remember(file.path) { mutableStateOf(false) }
            var showWireframe by remember(file.path) { mutableStateOf(true) }
            val density = androidx.compose.ui.platform.LocalDensity.current
            // Density.toPx is a member of Density in Compose; this mirrors it exactly.
            val widthPx = with(density) { 320.dp.toPx().toInt() }
            val heightPx = with(density) { 260.dp.toPx().toInt() }

            LaunchedEffect(mesh, camera, showWireframe, widthPx, heightPx) {
                if (mesh.triangleCount == 0) return@LaunchedEffect
                bitmap = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val rendered = MeshRenderer.render(
                        mesh, widthPx, heightPx, camera,
                        MeshRenderer.RenderOptions(wireframe = showWireframe),
                    )
                    stats = rendered.stats
                    // The renderer produces PNG-ready ARGB; decode via BitmapFactory
                    // so we exercise the same encoder the export path uses.
                    val png = Png.write(rendered.width, rendered.height, rendered.argb)
                    android.graphics.BitmapFactory.decodeByteArray(png, 0, png.size)
                }
            }

            if (autoRotate) {
                LaunchedEffect(mesh, autoRotate) {
                    while (true) {
                        kotlinx.coroutines.delay(60)
                        camera = camera.orbit(4f, 0f)
                    }
                }
            }

            Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
                    val bmp = bitmap
                    if (bmp != null) {
                        Image(
                            bitmap = bmp.asImageBitmap(),
                            contentDescription = "3D preview of ${file.name}",
                            contentScale = ContentScale.Fit,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(260.dp)
                                .pointerInput(Unit) {
                                    detectDragGestures { change, drag ->
                                        change.consume()
                                        camera = camera.orbit(drag.x * 0.4f, -drag.y * 0.4f)
                                    }
                                }
                                .pointerInput(Unit) {
                                    detectTransformGestures { _, _, zoom, _ ->
                                        camera = camera.zoomBy(zoom)
                                    }
                                },
                        )
                    } else {
                        Box(Modifier.fillMaxWidth().height(260.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                        }
                    }
                }
                Text(
                    "Drag to orbit • pinch to zoom",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { camera = MeshRenderer.Camera() }) { Text("Reset") }
                    TextButton(onClick = { showWireframe = !showWireframe }) {
                        Text(if (showWireframe) "Hide edges" else "Show edges")
                    }
                    TextButton(onClick = { autoRotate = !autoRotate }) {
                        Text(if (autoRotate) "Stop spin" else "Spin")
                    }
                }
                stats?.let { s ->
                    Text(
                        "${mesh.triangleCount} triangles • ${mesh.vertexCount} vertices • " +
                            "rendered ${s.trianglesDrawn} in ${s.ms} ms",
                        style = MonoStyle,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                MeshInfoTable(mesh)
                Text(
                    if (mesh.triangleCount > 60_000) {
                        "Large mesh: dragging is decimated by full re-renders, expect ~${stats?.ms ?: 0} ms per frame."
                    } else {
                        "Rendered on CPU (software rasterizer) — no GPU context is created, so the preview " +
                            "behaves identically on every device."
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun MeshInfoTable(mesh: Geometry.Mesh) {
    val bounds = mesh.bounds()
    val volume = runCatching { mesh.volume() }.getOrNull()
    val area = runCatching { mesh.surfaceArea() }.getOrNull()
    Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp), color = MaterialTheme.colorScheme.surfaceVariant) {
        Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val size = bounds.size
            Text(
                "Size: %.2f × %.2f × %.2f".format(size.x, size.y, size.z),
                style = MonoStyle,
            )
            if (volume != null && volume > 0f) {
                Text("Volume: %.2f (closed mesh)".format(volume), style = MonoStyle)
            } else {
                Text("Volume: n/a (mesh is not watertight)", style = MonoStyle)
            }
            if (area != null) Text("Surface: %.2f".format(area), style = MonoStyle)
        }
    }
}

// ------------------------------------------------------------------ images

@Composable
fun ImageViewerPanel(file: File, modifier: Modifier = Modifier) {
    var info by remember(file.path) { mutableStateOf<ImageLoader.ImageInfo?>(null) }
    var bitmap by remember(file.path) { mutableStateOf<Bitmap?>(null) }
    var error by remember(file.path) { mutableStateOf<String?>(null) }
    var zoom by remember(file.path) { mutableStateOf(1f) }

    LaunchedEffect(file.path) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val details = ImageLoader.inspect(file)
            if (details == null) {
                error = "Not a decodable image (or the format is unsupported on this Android version)."
            } else {
                info = details
                bitmap = ImageLoader.decode(file)
                if (bitmap == null) error = "Decoding failed — the file may be corrupt or use an unsupported profile."
            }
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        error?.let { MonoSizedText(it) }
        val bmp = bitmap
        if (bmp != null) {
            Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp)) {
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = file.name,
                    contentScale = ContentScale.Fit,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 340.dp)
                        .pointerInput(Unit) {
                            detectTransformGestures { _, _, pinch, _ ->
                                zoom = (zoom * pinch).coerceIn(1f, 6f)
                            }
                        },
                )
            }
        } else if (error == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Decoding…", style = MaterialTheme.typography.bodySmall)
            }
        }
        info?.let {
            Text(
                "${it.width} × ${it.height} • ${it.mimeType ?: "unknown"} • %.1f MP".format(it.width * it.height / 1_000_000f),
                style = MonoStyle,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (bitmap != null) {
            Text(
                "Decoded at ${bitmap!!.width} × ${bitmap!!.height} (subsampled to protect memory).",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// --------------------------------------------------------------- documents

@Composable
fun PdfViewerPanel(file: File, modifier: Modifier = Modifier) {
    var doc by remember(file.path) { mutableStateOf<DocumentReader.PdfText?>(null) }
    var error by remember(file.path) { mutableStateOf<String?>(null) }
    LaunchedEffect(file.path) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                doc = DocumentReader.extractPdfText(file.readBytes())
            } catch (e: Exception) {
                error = e.message
            } catch (oom: OutOfMemoryError) {
                error = "PDF is too large to read on this device."
            }
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        error?.let { MonoSizedText(it) }
        val parsed = doc
        if (parsed == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Extracting text…", style = MaterialTheme.typography.bodySmall)
            }
            return@Column
        }
        Text(
            "${parsed.pageCount} page(s) • ${parsed.text.length} characters extracted",
            style = MaterialTheme.typography.labelMedium,
        )
        if (parsed.notes.isNotEmpty()) {
            Surface(color = MaterialTheme.colorScheme.errorContainer) {
                Column(Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    parsed.notes.forEach { Text(it, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
        if (parsed.hasText) {
            MonoBlock(parsed.text.take(20_000), maxLines = 40)
            Text(
                "Text only — the app does not rasterise PDF pages (that needs a native PDF renderer). " +
                    "Use Open to view the real layout in your PDF app.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun DocxViewerPanel(file: File, modifier: Modifier = Modifier) {
    var doc by remember(file.path) { mutableStateOf<DocumentReader.DocxDocument?>(null) }
    var error by remember(file.path) { mutableStateOf<String?>(null) }
    LaunchedEffect(file.path) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                doc = DocumentReader.readDocx(file.readBytes())
            } catch (e: Exception) {
                error = e.message
            }
        }
    }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        error?.let { MonoSizedText(it) }
        val parsed = doc ?: return@Column
        parsed.notes.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error) }
        LazyColumn(
            Modifier.heightIn(max = 320.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            items(parsed.paragraphs.take(400)) { paragraph ->
                Text(paragraph, style = MaterialTheme.typography.bodySmall)
            }
            if (parsed.tableRows.isNotEmpty()) {
                item { Text("Tables", style = MaterialTheme.typography.labelMedium) }
                items(parsed.tableRows.take(60)) { row ->
                    Text(row.joinToString("  |  "), style = MonoStyle, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Text(
            "${parsed.paragraphs.size} paragraphs • ${parsed.tableRows.size} table rows",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun SheetViewerPanel(
    file: File,
    csv: Boolean,
    modifier: Modifier = Modifier,
) {
    var table by remember(file.path) { mutableStateOf<DocumentReader.Table?>(null) }
    var sheetNames by remember(file.path) { mutableStateOf<List<String>>(emptyList()) }
    var selectedSheet by remember(file.path) { mutableStateOf(0) }
    var error by remember(file.path) { mutableStateOf<String?>(null) }

    LaunchedEffect(file.path, selectedSheet) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                if (csv) {
                    table = DocumentReader.parseCsv(file.readText().take(4_000_000))
                } else {
                    val book = DocumentReader.readXlsx(file.readBytes())
                    if (book.sheets.isEmpty()) {
                        error = book.notes.firstOrNull() ?: "No worksheets found."
                        return@withContext
                    }
                    sheetNames = book.sheets.map { it.name }
                    val sheet = book.sheets.getOrNull(selectedSheet.coerceIn(0, book.sheets.lastIndex)) ?: book.sheets.first()
                    val rows = sheet.rows
                    val headers = rows.firstOrNull().orEmpty()
                    table = DocumentReader.Table(
                        headers = headers.mapIndexed { i, h -> h.ifBlank { "Column ${i + 1}" } },
                        rows = rows.drop(1),
                    )
                }
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        error?.let { MonoSizedText(it) }
        if (sheetNames.size > 1) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                sheetNames.forEachIndexed { index, name ->
                    FilterChip(
                        selected = index == selectedSheet,
                        onClick = { selectedSheet = index },
                        label = { Text(name, style = MaterialTheme.typography.labelSmall) },
                    )
                }
            }
        }
        val grid = table
        if (grid == null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                Text("Reading sheet…", style = MaterialTheme.typography.bodySmall)
            }
            return@Column
        }
        if (grid.headers.isEmpty()) {
            Text("The sheet is empty.", style = MaterialTheme.typography.bodySmall)
            return@Column
        }
        Text(
            "${grid.rows.size} rows × ${grid.columnCount} columns",
            style = MaterialTheme.typography.labelMedium,
        )
        LazyColumn(Modifier.heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            item {
                Surface(color = MaterialTheme.colorScheme.primaryContainer) {
                    Text(
                        grid.headers.joinToString("  │  "),
                        style = MonoStyle,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                    )
                }
            }
            items(grid.rows.take(500)) { row ->
                Text(
                    row.joinToString("  │  "),
                    style = MonoStyle,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
        }
        if (grid.rows.size > 500) {
            Text(
                "Showing the first 500 rows. Ask the agent in Chat to query or summarise the whole sheet.",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun MonoSizedText(text: String) {
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(text, style = MonoStyle, modifier = Modifier.padding(10.dp))
    }
}
