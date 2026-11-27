package com.mobileaiagent.tools.builtin

import com.mobileaiagent.core.json.JsonValue
import com.mobileaiagent.tools.spec.*

/**
 * Data / document productivity tools that work fully offline and are honest
 * about format support:
 *
 *  - CSV/TSV: real parsing, querying, transformation (RFC-4180-ish with quotes)
 *  - JSON: real parse/query/transform
 *  - Markdown/plain text: real creation/editing
 *  - DOCX/XLSX/PPTX/PDF: text *extraction* for reading into context. Writing
 *    OOXML/PDF binary formats is not implemented yet and is reported as such.
 */
class CsvTool : AgentTool {
    override val id = "csv_tool"
    override val name = "CSV/TSV data tool"
    override val description =
        "Operate on CSV/TSV files: 'summary' (columns, row count, sample), 'query' (filter rows by column expression), " +
            "'create' (write from JSON rows), 'append' (add JSON rows). Handles quoted fields."
    override val version = "1.0.0"
    override val category = ToolCategory.PRODUCTIVITY
    override val inputSchema = schema {
        requiredString("action", "summary | query | create | append", listOf("summary", "query", "create", "append"))
        optionalString("path", "CSV file path. Required except for parsing raw data.")
        optionalString("delimiter", "Field delimiter, default ','. Use '\\t' for TSV.")
        optionalString("query_column", "For query: column name to test.")
        optionalString("query_operator", "For query: equals | contains | gt | lt | notempty", listOf("equals", "contains", "gt", "lt", "notempty"))
        optionalString("query_value", "For query: comparison value.")
        optionalArray("rows", "For create/append: array of objects (one per row) or arrays.")
        optionalInt("limit", "Max rows to output/return (default 100).")
        optionalBool("include_header", "Write a header row for create (default true).")
    }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions = listOf(ToolPermission.FILES_READ, ToolPermission.FILES_WRITE)
    override val mutagenic = true
    override val cancellationSupported = true
    override val maxResultChars = 40_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        val args = ToolArgs(input)
        val ws = ctx.workspace ?: return FileListTool.NoWorkspace
        val delimiter = args.optionalString("delimiter")?.let { if (it == "\\t") '\t' else it.first() } ?: ','
        val limit = args.int("limit", required = false, default = 100).coerceIn(1, 5000)
        return when (val action = args.string("action")) {
            "summary" -> {
                val path = args.string("path")
                val rows = Csv.parse(ws.readText(path, 32L * 1024 * 1024), delimiter)
                if (rows.isEmpty()) return ToolResult.Failure("CSV is empty", errorKind = "empty")
                val header = rows.first()
                val sb = StringBuilder()
                sb.appendLine("File: $path")
                sb.appendLine("Rows (excluding header): ${rows.size - 1}")
                sb.appendLine("Columns (${header.size}): ${header.joinToString(" | ")}")
                sb.appendLine()
                sb.appendLine("First ${minOf(10, rows.size - 1)} rows:")
                rows.drop(1).take(10).forEach { r -> sb.appendLine(r.joinToString(" | ")) }
                val stats = header.indices.take(20).mapNotNull { i ->
                    val values = rows.drop(1).mapNotNull { it.getOrNull(i)?.trim()?.takeIf { v -> v.isNotEmpty() } }
                    if (values.size < 2) null else {
                        val numbers = values.mapNotNull { it.toDoubleOrNull() }
                        if (numbers.size == values.size) {
                            "${header.getOrElse(i) { "col$i" }}: numeric, min ${numbers.min()}, max ${numbers.max()}, mean ${String.format("%.2f", numbers.average())}"
                        } else {
                            "${header.getOrElse(i) { "col$i" }}: ${values.toSet().size} distinct value(s)"
                        }
                    }
                }
                if (stats.isNotEmpty()) {
                    sb.appendLine()
                    sb.appendLine("Column stats:")
                    stats.forEach { sb.appendLine("- $it") }
                }
                ToolResult.Success(sb.toString())
            }
            "query" -> {
                val path = args.string("path")
                val rows = Csv.parse(ws.readText(path, 32L * 1024 * 1024), delimiter)
                if (rows.isEmpty()) return ToolResult.Failure("CSV is empty", errorKind = "empty")
                val header = rows.first()
                val column = args.string("query_column")
                val columnIndex = header.indexOfFirst { it.equals(column, ignoreCase = true) }
                if (columnIndex < 0) return ToolResult.Failure("No column '$column'. Available: ${header.joinToString()}", errorKind = "not_found")
                val op = args.optionalString("query_operator") ?: "contains"
                val value = args.optionalString("query_value") ?: ""
                val matches = rows.drop(1).filter { row ->
                    val cell = row.getOrNull(columnIndex)?.trim() ?: ""
                    when (op) {
                        "equals" -> cell.equals(value, ignoreCase = true)
                        "contains" -> cell.contains(value, ignoreCase = true)
                        "gt" -> (cell.toDoubleOrNull() ?: Double.NEGATIVE_INFINITY) > (value.toDoubleOrNull() ?: 0.0)
                        "lt" -> (cell.toDoubleOrNull() ?: Double.POSITIVE_INFINITY) < (value.toDoubleOrNull() ?: 0.0)
                        "notempty" -> cell.isNotEmpty()
                        else -> false
                    }
                }
                val sb = StringBuilder("$action '$column' $op '$value': ${matches.size} of ${rows.size - 1} rows\n")
                sb.appendLine(header.joinToString(" | "))
                matches.take(limit).forEach { r -> sb.appendLine(r.joinToString(" | ")) }
                if (matches.size > limit) sb.appendLine("…(${matches.size - limit} more)")
                ToolResult.Success(sb.toString())
            }
            "create", "append" -> {
                val path = args.string("path")
                val rowsJson = args.array("rows")
                val lines = rowsJson.map { row ->
                    when (row) {
                        is JsonValue.Obj -> row.entries.values.map { Csv.escape(it.asStringOrNull() ?: "", delimiter) }.joinToString(delimiter.toString())
                        is JsonValue.Arr -> row.items.map { Csv.escape(it.asStringOrNull() ?: "", delimiter) }.joinToString(delimiter.toString())
                        else -> Csv.escape(row.asStringOrNull() ?: "", delimiter)
                    }
                }
                val headerLine = if (action == "create" && args.boolean("include_header", true)) {
                    val first = rowsJson.firstOrNull()
                    if (first is JsonValue.Obj) first.entries.keys.joinToString(delimiter.toString()) { Csv.escape(it, delimiter) } + "\n" else ""
                } else ""
                val payload = headerLine + lines.joinToString("\n") + (if (lines.isNotEmpty()) "\n" else "")
                if (action == "create") ws.writeText(path, payload, overwrite = true) else ws.appendText(path, payload)
                ToolResult.Success(
                    "${if (action == "create") "Wrote" else "Appended"} ${rowsJson.size} row(s) to $path",
                    artifacts = listOf(ArtifactRef(path, path.substringAfterLast('/'), "text/csv", ws.sizeBytes(path).toLong())),
                )
            }
            else -> ToolResult.Failure("Unknown action '$action'", errorKind = "invalid_arguments")
        }
    }
}

object Csv {
    fun parse(text: String, delimiter: Char = ','): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val current = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> { field.append('"'); i++ }
                c == '"' -> inQuotes = !inQuotes
                !inQuotes && c == delimiter -> { current.add(field.toString()); field.setLength(0) }
                !inQuotes && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    current.add(field.toString()); field.setLength(0)
                    if (current.any { it.isNotEmpty() }) rows.add(current.toList())
                    current.clear()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || current.isNotEmpty()) {
            current.add(field.toString())
            rows.add(current.toList())
        }
        return rows
    }

    fun escape(value: String, delimiter: Char): String =
        if (value.contains(delimiter) || value.contains('"') || value.contains('\n')) "\"${value.replace("\"", "\"\"")}\"" else value
}

class JsonTool : AgentTool {
    override val id = "json_tool"
    override val name = "JSON tool"
    override val description =
        "Validate, pretty-print, minify, query (simple dot/bracket path) or transform JSON files. " +
            "Use 'path' to read a file or 'data' to pass JSON inline."
    override val version = "1.0.0"
    override val category = ToolCategory.PRODUCTIVITY
    override val inputSchema = schema {
        requiredString("action", "validate | pretty | minify | query | write", listOf("validate", "pretty", "minify", "query", "write"))
        optionalString("path", "JSON file to read (and output file for pretty/minify when out_path set).")
        optionalString("data", "Inline JSON string instead of a file.")
        optionalString("query", "Dot/bracket path, e.g. 'items.0.name' or 'a[\"b\"]'.")
        optionalString("out_path", "Write the result to this file instead of returning it.")
    }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions = listOf(ToolPermission.FILES_READ, ToolPermission.FILES_WRITE)
    override val mutagenic = true
    override val cancellationSupported = false
    override val maxResultChars = 40_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        val args = ToolArgs(input)
        val ws = ctx.workspace ?: return FileListTool.NoWorkspace
        val raw = args.optionalString("data") ?: args.optionalString("path")?.let { ws.readText(it, 32L * 1024 * 1024) }
            ?: return ToolResult.Failure("Provide 'path' or 'data'", errorKind = "invalid_arguments")
        val json = try {
            com.mobileaiagent.core.json.JsonParser.parse(raw)
        } catch (e: Exception) {
            return ToolResult.Failure("Invalid JSON: ${e.message}", errorKind = "invalid_json")
        }
        val action = args.string("action")
        val result: String? = when (action) {
            "validate" -> "Valid JSON (${json.typeName()}). Root keys/items: ${
                json.asObjectOrNull()?.keys?.joinToString().orEmpty().ifEmpty { json.asArrayOrNull()?.size?.toString().orEmpty() }
            }"
            "pretty" -> com.mobileaiagent.core.json.JsonWriter.write(json, pretty = true)
            "minify" -> json.toJson()
            "query" -> {
                val query = args.string("query")
                val selected = queryPath(json, query)
                if (selected == null) return ToolResult.Failure("Path '$query' not found", errorKind = "not_found")
                com.mobileaiagent.core.json.JsonWriter.write(selected, pretty = true)
            }
            "write" -> {
                val out = args.string("out_path")
                ws.writeText(out, com.mobileaiagent.core.json.JsonWriter.write(json, pretty = true), overwrite = true)
                return ToolResult.Success("Wrote $out", artifacts = listOf(ArtifactRef(out, out.substringAfterLast('/'), "application/json")))
            }
            else -> return ToolResult.Failure("Unknown action '$action'", errorKind = "invalid_arguments")
        }
        val outPath = args.optionalString("out_path")
        if (outPath != null && action != "validate") {
            ws.writeText(outPath, result ?: "", overwrite = true)
            return ToolResult.Success("Wrote result to $outPath (${(result ?: "").length} chars)")
        }
        return ToolResult.Success(result ?: "")
    }

    private fun queryPath(root: JsonValue, path: String): JsonValue? {
        if (path.isBlank()) return root
        var current: JsonValue = root
        val tokens = Regex("""([^.\[\]]+)|\["([^"]+)"\]|\[(\d+)]""").findAll(path)
        for (t in tokens) {
            val name = t.groupValues[1]
            val quoted = t.groupValues[2]
            val index = t.groupValues[3]
            current = when {
                quoted.isNotEmpty() -> current[quoted]
                index.isNotEmpty() -> current[index.toInt()]
                else -> current[name]
            }
            if (current is JsonValue.Null) return null
        }
        return current
    }
}

/** Extracts text from DOCX/XLSX/PPTX/PDF-ish containers for reading into context. */
class DocumentReadTool : AgentTool {
    override val id = "document_read"
    override val name = "Extract document text"
    override val description =
        "Extract readable text from .docx, .xlsx, .pptx (OOXML) and .pdf files in the workspace. " +
            "This is extraction only — it cannot create or edit those binary formats."
    override val version = "1.0.0"
    override val category = ToolCategory.PRODUCTIVITY
    override val inputSchema = schema {
        requiredString("path", "Document path in the workspace.")
        optionalInt("max_chars", "Max characters to return (default 20000).")
    }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions = listOf(ToolPermission.FILES_READ)
    override val mutagenic = false
    override val cancellationSupported = true
    override val maxResultChars = 60_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        val args = ToolArgs(input)
        val ws = ctx.workspace ?: return FileListTool.NoWorkspace
        val path = args.string("path")
        val maxChars = args.int("max_chars", required = false, default = 20_000).coerceIn(500, 60_000)
        val ext = path.substringAfterLast('.', "").lowercase()
        return try {
            // DOCX/XLSX/PDF go through DocumentReader, the same implementation the
            // in-app viewer uses. One vetted parser instead of two that could drift.
            val text = when (ext) {
                "docx" -> {
                    val doc = com.mobileaiagent.ui.viewer.DocumentReader.readDocx(ws.readBytes(path, 128L * 1024 * 1024))
                    (doc.text + doc.tableRows.joinToString("\n") { it.joinToString(" | ") })
                        .ifBlank { doc.notes.joinToString("\n") }
                }
                "xlsx" -> {
                    val book = com.mobileaiagent.ui.viewer.DocumentReader.readXlsx(ws.readBytes(path, 128L * 1024 * 1024))
                    book.sheets.joinToString("\n\n") { sheet ->
                        "## ${sheet.name}\n" + sheet.rows.take(500).joinToString("\n") { it.joinToString(" | ") }
                    }.ifBlank { book.notes.joinToString("\n") }
                }
                "pptx" -> extractPptx(java.io.ByteArrayInputStream(ws.readBytes(path, 128L * 1024 * 1024)))
                "pdf" -> {
                    val pdf = com.mobileaiagent.ui.viewer.DocumentReader.extractPdfText(ws.readBytes(path, 128L * 1024 * 1024))
                    if (pdf.encrypted) "The PDF is encrypted and cannot be read without a password."
                    else pdf.text.ifBlank { pdf.notes.joinToString("\n") }
                }
                "txt", "md", "markdown", "csv", "tsv", "json", "xml", "html", "htm", "log" ->
                    ws.readText(path, 16L * 1024 * 1024)
                else -> return ToolResult.Failure(
                    "Unsupported document type '.$ext'. Supported: docx, xlsx, pptx, pdf, txt, md, csv, json.",
                    errorKind = "unsupported",
                )
            }
            if (text.isBlank()) {
                ToolResult.Success("No extractable text found in $path (may be image-only or protected).")
            } else {
                ToolResult.Success(
                    "Extracted from $path (${text.length} chars):\n---\n" + text.take(maxChars),
                )
            }
        } catch (e: Exception) {
            ToolResult.Failure("Extraction failed: ${e.message}", errorKind = "io")
        }
    }

    private fun xmlToText(xml: String): String {
        val withBreaks = xml
            .replace(Regex("(?i)</w:p>"), "\n")
            .replace(Regex("(?i)<w:tab[^>]*/>"), "\t")
            .replace(Regex("(?i)<w:br[^>]*/>"), "\n")
            .replace(Regex("(?is)<[^>]+>"), "")
        return HtmlText.decodeEntities(withBreaks).replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun extractPptx(bytes: java.io.ByteArrayInputStream): String {
        val slides = sortedMapOf<Int, String>()
        java.util.zip.ZipInputStream(bytes).use { zin ->
            var entry = zin.nextEntry
            while (entry != null) {
                val name = entry.name
                if (name.startsWith("ppt/slides/slide") && name.endsWith(".xml")) {
                    val index = name.removePrefix("ppt/slides/slide").removeSuffix(".xml").toIntOrNull() ?: 0
                    val xml = zin.readBytes().toString(Charsets.UTF_8)
                    val text = Regex("(?is)<a:t[^>]*>(.*?)</a:t>").findAll(xml)
                        .joinToString(" ") { HtmlText.decodeEntities(it.groupValues[1]) }
                    slides[index] = text
                }
                entry = zin.nextEntry
            }
        }
        return slides.entries.joinToString("\n\n") { (i, text) -> "--- Slide $i ---\n$text" }
    }

    /**
     * Minimal PDF text extractor: handles uncompressed content streams and
     * FlateDecode streams, extracting text from Tj/TJ operators. Encrypted or
     * exotic PDFs (CID fonts, scanned images) return partial/empty text and the
     * caller reports that honestly.
     */
    private fun extractPdfOperators(content: String, out: StringBuilder) {
        val tokenRegex = Regex("""\((?:\\.|[^\\()])*\)|\bTJ\b|\bTj\b|\bTd\b|\bTD\b|\bT\*\b|\bET\b""")
        var pending: String? = null
        for (m in tokenRegex.findAll(content)) {
            val token = m.value
            when {
                token.startsWith("(") -> pending = unescapePdfString(token.substring(1, token.length - 1))
                token == "Tj" || token == "TJ" -> {
                    pending?.let { out.append(it) }
                    pending = null
                }
                token == "Td" || token == "TD" || token == "T*" || token == "ET" -> {
                    pending = null
                    if (out.isNotEmpty() && out.last() != '\n') out.append('\n')
                }
            }
        }
    }

    private fun unescapePdfString(s: String): String = s
        .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
        .replace("\\(", "(").replace("\\)", ")").replace("\\\\", "\\")

    private fun inflate(chunk: ByteArray): ByteArray {
        val inflater = java.util.zip.Inflater()
        try {
            inflater.setInput(chunk)
            val out = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0) break
                out.write(buffer, 0, n)
                if (out.size() > 20 * 1024 * 1024) break
            }
            return out.toByteArray()
        } finally {
            inflater.end()
        }
    }

    private fun indexOf(haystack: ByteArray, needle: ByteArray, from: Int): Int {
        outer@ for (i in from..haystack.size - needle.size) {
            for (j in needle.indices) if (haystack[i + j] != needle[j]) continue@outer
            return i
        }
        return -1
    }
}

class TextTool : AgentTool {
    override val id = "text_tool"
    override val name = "Text transform"
    override val description =
        "Transform text or a file: 'replace' (literal/regex), 'line_edit' (replace/insert/delete specific lines), " +
            "'extract' (grep lines / between markers), 'stats', 'wrap', 'template' (fill placeholders)."
    override val version = "1.0.0"
    override val category = ToolCategory.PRODUCTIVITY
    override val inputSchema = schema {
        requiredString(
            "action",
            "replace | line_edit | extract | stats | template",
            listOf("replace", "line_edit", "extract", "stats", "template"),
        )
        optionalString("path", "Input file (otherwise 'text' is used).")
        optionalString("text", "Input text.")
        optionalString("out_path", "Write the result to this file (optional).")
        optionalString("find", "For replace/extract: literal or regex.")
        optionalString("replace_with", "Replacement text (supports $1 groups for regex).")
        optionalBool("regex", "Treat 'find' as a regex.")
        optionalInt("line", "For line_edit: 1-based line number.")
        optionalString("line_action", "For line_edit: replace | insert_after | delete", listOf("replace", "insert_after", "delete"))
        optionalString("line_text", "For line_edit replace/insert: new line content.")
        optionalString("variables", "For template: JSON object of placeholder → value.")
    }
    override val executionEnvironment = ExecutionEnvironment.LOCAL_ANDROID
    override val permissions = listOf(ToolPermission.FILES_READ, ToolPermission.FILES_WRITE)
    override val mutagenic = true
    override val cancellationSupported = true
    override val maxResultChars = 40_000

    override suspend fun execute(input: JsonValue, ctx: ToolContext): ToolResult {
        val args = ToolArgs(input)
        val ws = ctx.workspace ?: return FileListTool.NoWorkspace
        val source = args.optionalString("text") ?: args.optionalString("path")?.let { ws.readText(it, 32L * 1024 * 1024) }
            ?: return ToolResult.Failure("Provide 'path' or 'text'", errorKind = "invalid_arguments")
        val result = try {
            when (val action = args.string("action")) {
                "replace" -> {
                    val find = args.string("find")
                    val replacement = args.optionalString("replace_with") ?: ""
                    if (args.boolean("regex", false)) source.replace(Regex(find), replacement)
                    else source.replace(find, replacement)
                }
                "line_edit" -> {
                    val lineNo = args.int("line")
                    val lines = source.split("\n").toMutableList()
                    if (lineNo < 1 || lineNo > lines.size + 1) return ToolResult.Failure("Line $lineNo out of range (1..${lines.size})", errorKind = "invalid_arguments")
                    when (args.optionalString("line_action") ?: "replace") {
                        "replace" -> lines[lineNo - 1] = args.optionalString("line_text") ?: ""
                        "insert_after" -> lines.add(lineNo, args.optionalString("line_text") ?: "")
                        "delete" -> lines.removeAt(lineNo - 1)
                    }
                    lines.joinToString("\n")
                }
                "extract" -> {
                    val find = args.string("find")
                    val regex = Regex(if (args.boolean("regex", false)) find else Regex.escape(find))
                    source.lineSequence().withIndex().filter { regex.containsMatchIn(it.value) }
                        .joinToString("\n") { (i, line) -> "${i + 1}: $line" }
                }
                "stats" -> {
                    val words = source.split(Regex("\\s+")).filter { it.isNotBlank() }
                    "chars=${source.length} words=${words.size} lines=${source.count { it == '\n' } + 1} " +
                        "unique_words=${words.map { it.lowercase() }.toSet().size}"
                }
                "template" -> {
                    var out = source
                    val varsJson = args.optionalString("variables")?.let { com.mobileaiagent.core.json.JsonParser.parseOrNull(it) }
                    varsJson?.asObjectOrNull()?.forEach { (k, v) ->
                        out = out.replace("{{$k}}", v.asStringOrNull() ?: v.toJson())
                    }
                    out
                }
                else -> return ToolResult.Failure("Unknown action '$action'", errorKind = "invalid_arguments")
            }
        } catch (e: Exception) {
            return ToolResult.Failure("Transform failed: ${e.message}", errorKind = "invalid_arguments")
        }
        val outPath = args.optionalString("out_path") ?: args.optionalString("path")?.takeIf { args.has("out_path") }
        val target = if (args.has("out_path")) outPath!! else {
            // Overwrite the source file when editing in place.
            args.optionalString("path")
        }
        return if (target != null) {
            ws.writeText(target, result, overwrite = true)
            ToolResult.Success(
                "Wrote ${result.length} chars to $target",
                artifacts = listOf(ArtifactRef(target, target.substringAfterLast('/'), FileWriteTool.mimeFor(target), result.length.toLong())),
            )
        } else {
            ToolResult.Success(result)
        }
    }
}
