package com.mobileaiagent.ui.viewer

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import java.util.zip.ZipInputStream

/**
 * Real document readers for the Workspace viewer.
 *
 * Everything here parses actual file formats — no "open it with the agent"
 * placeholders. Implemented with `java.util.zip` only (present on every Android
 * version), so the whole pipeline runs in the JVM test harness.
 *
 * Supported:
 *  - PDF  : text extraction from content streams (FlateDecode), Tj/TJ operators
 *  - DOCX : ZIP -> word/document.xml -> paragraphs + table cells
 *  - XLSX : ZIP -> sharedStrings.xml + worksheet XML parts -> cell grid
 *  - CSV  : RFC 4180 quoted fields, delimiter sniffing
 *
 * Deliberately not supported, and reported honestly to the user instead of
 * pretending: encrypted PDFs, scanned/image-only PDFs (no OCR), .doc/.xls
 * legacy binaries, charts and embedded media.
 */
object DocumentReader {

    // ----------------------------------------------------------------- CSV

    data class Table(
        val headers: List<String>,
        val rows: List<List<String>>,
        val truncated: Boolean = false,
    ) {
        val columnCount: Int get() = headers.size.coerceAtLeast(rows.maxOfOrNull { it.size } ?: 0)
    }

    /** RFC 4180 parser with quote escaping; sniffs `,` `;` `\t` `|` from the header line. */
    fun parseCsv(text: String, maxRows: Int = 5_000): Table {
        val clean = text.removePrefix("\uFEFF")
        if (clean.isBlank()) return Table(emptyList(), emptyList())
        val delimiter = sniffDelimiter(clean.lineSequence().firstOrNull() ?: "")
        val records = ArrayList<List<String>>()
        val field = StringBuilder()
        val row = ArrayList<String>()
        var inQuotes = false
        var i = 0
        var truncated = false
        while (i < clean.length) {
            val c = clean[i]
            when {
                inQuotes -> when {
                    c == '"' && i + 1 < clean.length && clean[i + 1] == '"' -> { field.append('"'); i++ }
                    c == '"' -> inQuotes = false
                    else -> field.append(c)
                }
                c == '"' -> inQuotes = true
                c == delimiter -> { row.add(field.toString()); field.setLength(0) }
                c == '\r' -> { /* handled by \n */ }
                c == '\n' -> {
                    row.add(field.toString()); field.setLength(0)
                    if (records.size < maxRows) records.add(row.toList()) else truncated = true
                    row.clear()
                }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) {
            row.add(field.toString())
            if (records.size < maxRows) records.add(row.toList()) else truncated = true
        }
        if (records.isEmpty()) return Table(emptyList(), emptyList())
        val headers = records.first().mapIndexed { index, value ->
            value.ifBlank { "Column ${index + 1}" }
        }
        return Table(headers, records.drop(1), truncated)
    }

    private fun sniffDelimiter(header: String): Char {
        // Quote-aware: a comma inside a quoted field must not win the sniff.
        var inQuotes = false
        val counts = mutableMapOf(',' to 0, ';' to 0, '\t' to 0, '|' to 0)
        header.forEach { c ->
            if (c == '"') inQuotes = !inQuotes
            else if (!inQuotes && c in counts) counts[c] = counts.getValue(c) + 1
        }
        return counts.maxByOrNull { it.value }?.takeIf { it.value > 0 }?.key ?: ','
    }

    // ----------------------------------------------------------------- PDF

    data class PdfText(
        val text: String,
        val pageCount: Int,
        val encrypted: Boolean = false,
        val hasText: Boolean = text.isNotBlank(),
        val notes: List<String> = emptyList(),
    )

    /**
     * Extracts text by walking content streams. Handles FlateDecode and
     * uncompressed streams, including object streams from linearised PDFs by
     * scanning for every `stream ... endstream` pair.
     */
    fun extractPdfText(bytes: ByteArray): PdfText {
        if (bytes.size < 8 || !bytes.copyOfRange(0, 5).toString(Charsets.ISO_8859_1).startsWith("%PDF")) {
            return PdfText("", 0, notes = listOf("Not a PDF file (missing %PDF header)."))
        }
        val raw = String(bytes, Charsets.ISO_8859_1)
        // An /Encrypt entry only counts when it labels a dictionary; a literal
        // "/Encrypt" inside a compressed stream would otherwise false-positive.
        // Real PDFs reference the encryption dictionary either indirectly
        // (/Encrypt 12 0 R) or inline (/Encrypt << /Filter /Standard ... >>).
        if (Regex("""/Encrypt\s*(\d+\s+\d+\s+R|<<)""").containsMatchIn(raw)) {
            return PdfText("", 0, encrypted = true, notes = listOf("PDF is encrypted — text extraction is not supported."))
        }
        val pageCount = Regex("""/Type\s*/Page(?![s])""").findAll(raw).count()

        val builder = StringBuilder()
        var searchFrom = 0
        var streams = 0
        while (true) {
            val streamIdx = raw.indexOf("stream", searchFrom)
            if (streamIdx < 0) break
            var dataStart = streamIdx + 6
            if (dataStart < raw.length && raw[dataStart] == '\r') dataStart++
            if (dataStart < raw.length && raw[dataStart] == '\n') dataStart++
            val endIdx = raw.indexOf("endstream", dataStart)
            if (endIdx < 0) break
            val dictStart = maxOf(0, raw.lastIndexOf("<<", streamIdx))
            val dict = raw.substring(dictStart, streamIdx)
            var payload = bytes.copyOfRange(dataStart, minOf(endIdx, bytes.size))
            if (dict.contains("/FlateDecode")) {
                val inflated = inflate(payload)
                if (inflated == null) {
                    searchFrom = endIdx + 9
                    continue
                }
                payload = inflated
            }
            streams++
            val content = String(payload, Charsets.ISO_8859_1)
            if (content.contains("BT") && (content.contains("Tj") || content.contains("TJ"))) {
                builder.append(extractTextOperators(content)).append('\n')
            }
            searchFrom = endIdx + 9
        }

        val text = cleanPdfText(builder.toString())
        val notes = buildList {
            if (streams == 0) add("No readable content streams found.")
            if (text.isBlank()) add("This PDF has no extractable text — it is most likely a scan; OCR is not implemented.")
        }
        return PdfText(text, if (pageCount > 0) pageCount else 1, notes = notes)
    }

    private fun extractTextOperators(content: String): String {
        val out = StringBuilder()
        var i = 0
        while (i < content.length) {
            // Tj: (string) Tj
            if (content.startsWith("Tj", i) || content.startsWith("TJ", i)) {
                i += 2
                continue
            }
            if (content[i] == '(') {
                val (token, next) = readPdfString(content, i)
                i = next
                // Look ahead: only count strings feeding a text operator.
                val tail = content.substring(i, minOf(content.length, i + 24))
                if (tail.contains("Tj") || tail.contains("TJ")) out.append(token)
                continue
            }
            if (content.startsWith("Td", i) || content.startsWith("TD", i)) { out.append(' '); i += 2; continue }
            if (content.startsWith("T*", i) || content.startsWith("ET", i)) { out.append('\n'); i += 2; continue }
            i++
        }
        return out.toString()
    }

    /** Reads a PDF literal string with nesting and backslash escapes. */
    private fun readPdfString(content: String, start: Int): Pair<String, Int> {
        val sb = StringBuilder()
        var depth = 0
        var i = start
        while (i < content.length) {
            val c = content[i]
            when {
                c == '\\' && i + 1 < content.length -> {
                    val next = content[i + 1]
                    when (next) {
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        '(', ')', '\\' -> sb.append(next)
                        in '0'..'7' -> {
                            var oct = StringBuilder()
                            var j = i + 1
                            while (j < content.length && j < i + 4 && content[j] in '0'..'7') { oct.append(content[j]); j++ }
                            oct.toString().toIntOrNull(8)?.let { sb.append(it.toChar()) }
                            i = j
                            continue
                        }
                        else -> sb.append(next)
                    }
                    i += 2
                }
                c == '(' -> { depth++; if (depth > 1) sb.append(c); i++ }
                c == ')' -> { depth--; if (depth == 0) return sb.toString() to i + 1; sb.append(c); i++ }
                else -> { sb.append(c); i++ }
            }
        }
        return sb.toString() to i
    }

    private fun cleanPdfText(raw: String): String =
        raw.replace("\r\n", "\n")
            .lines()
            .joinToString("\n") { it.trim().replace(Regex("[ \\t]{2,}"), " ") }
            .replace(Regex("\n{3,}"), "\n\n")
            .trim()

    // ---------------------------------------------------------------- DOCX

    data class DocxDocument(
        val paragraphs: List<String>,
        val tableRows: List<List<String>>,
        val notes: List<String> = emptyList(),
    ) {
        val text: String get() = paragraphs.joinToString("\n\n")
    }

    fun readDocx(bytes: ByteArray): DocxDocument {
        val entries = readZip(bytes)
        val main = entries["word/document.xml"]
            ?: return DocxDocument(emptyList(), emptyList(), listOf("Not a DOCX file (word/document.xml missing). Legacy .doc is not supported."))
        val xml = main.toString(Charsets.UTF_8)
        val runRegex = Regex("<w:t(?:\\s[^>]*)?>(.*?)</w:t>", RegexOption.DOT_MATCHES_ALL)

        // Tables are extracted first and then removed from the body text, because
        // table cells contain their own <w:p> elements. Scanning the raw XML would
        // report every cell paragraph as a top-level paragraph.
        val tableRows = ArrayList<List<String>>()
        val tableSpans = Regex("<w:tbl[ >].*?</w:tbl>", RegexOption.DOT_MATCHES_ALL).findAll(xml).toList()
        tableSpans.forEach { table ->
            Regex("<w:tr[ >].*?</w:tr>", RegexOption.DOT_MATCHES_ALL).findAll(table.value).forEach { rowMatch ->
                val cells = Regex("<w:tc[ >].*?</w:tc>", RegexOption.DOT_MATCHES_ALL).findAll(rowMatch.value).map { cell ->
                    runRegex.findAll(cell.value).map { unescapeXml(it.groupValues[1]) }.joinToString(" ")
                }.toList()
                if (cells.isNotEmpty()) tableRows.add(cells)
            }
        }
        var bodyXml = xml
        tableSpans.asReversed().forEach { span ->
            bodyXml = bodyXml.removeRange(span.range)
        }

        val paragraphs = ArrayList<String>()
        val paraRegex = Regex("<w:p[ >].*?</w:p>|<w:p/>", RegexOption.DOT_MATCHES_ALL)
        paraRegex.findAll(bodyXml).forEach { match ->
            val runs = runRegex.findAll(match.value).map { unescapeXml(it.groupValues[1]) }.joinToString("")
            if (runs.isNotBlank()) paragraphs.add(runs)
        }
        val notes = if (paragraphs.isEmpty()) listOf("No text runs found in document.xml.") else emptyList()
        return DocxDocument(paragraphs, tableRows, notes)
    }

    // ---------------------------------------------------------------- XLSX

    data class Sheet(val name: String, val rows: List<List<String>>)

    data class Workbook(val sheets: List<Sheet>, val notes: List<String> = emptyList())

    fun readXlsx(bytes: ByteArray, maxRows: Int = 2_000): Workbook {
        val entries = readZip(bytes)
        val sharedStringsXml = entries["xl/sharedStrings.xml"]?.toString(Charsets.UTF_8)
        val shared = ArrayList<String>()
        if (sharedStringsXml != null) {
            Regex("<si>(.*?)</si>", RegexOption.DOT_MATCHES_ALL).findAll(sharedStringsXml).forEach { si ->
                shared.add(
                    Regex("<t(?:\\s[^>]*)?>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                        .findAll(si.groupValues[1])
                        .map { unescapeXml(it.groupValues[1]) }
                        .joinToString("")
                )
            }
        }
        val workbookXml = entries["xl/workbook.xml"]?.toString(Charsets.UTF_8)
        val sheetNames = workbookXml?.let {
            Regex("<sheet\\s[^>]*name=\"([^\"]*)\"").findAll(it).map { m -> unescapeXml(m.groupValues[1]) }.toList()
        } ?: emptyList()

        val sheetEntries = entries.keys
            .filter { it.startsWith("xl/worksheets/sheet") && it.endsWith(".xml") }
            .sortedBy { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
        if (sheetEntries.isEmpty()) {
            return Workbook(emptyList(), listOf("Not an XLSX workbook (no worksheets found). Legacy .xls is not supported."))
        }
        val sheets = sheetEntries.mapIndexed { index, path ->
            val xml = entries[path]!!.toString(Charsets.UTF_8)
            val rows = ArrayList<List<String>>()
            val rowMatches = Regex("<row[ >].*?</row>|<row[^>]*/>", RegexOption.DOT_MATCHES_ALL).findAll(xml).toList()
            for (rowMatch in rowMatches) {
                if (rows.size >= maxRows) break
                val cells = ArrayList<String>()
                Regex("<c\\s([^>]*)>(.*?)</c>|<c\\s([^>]*)/>", RegexOption.DOT_MATCHES_ALL).findAll(rowMatch.value).forEach { c ->
                    val attrs = (c.groupValues[1] + c.groupValues[3])
                    val body = c.groupValues[2]
                    val type = Regex("t=\"([^\"]+)\"").find(attrs)?.groupValues?.get(1)
                    val columnIndex = columnIndexOf(Regex("r=\"([A-Z]+)").find(attrs)?.groupValues?.get(1))
                    val rawValue = Regex("<v>(.*?)</v>", RegexOption.DOT_MATCHES_ALL).find(body)?.groupValues?.get(1)
                    val inlineStr = Regex("<t(?:\\s[^>]*)?>(.*?)</t>", RegexOption.DOT_MATCHES_ALL)
                        .findAll(body).map { unescapeXml(it.groupValues[1]) }.joinToString("")
                    val value = when (type) {
                        "s" -> rawValue?.toIntOrNull()?.let { shared.getOrNull(it) } ?: ""
                        "inlineStr" -> inlineStr
                        "str" -> unescapeXml(rawValue ?: "")
                        "b" -> if (rawValue == "1") "TRUE" else "FALSE"
                        else -> rawValue ?: inlineStr
                    }
                    // Preserve gaps so columns line up with the header row.
                    while (cells.size < columnIndex) cells.add("")
                    if (cells.size == columnIndex) cells.add(value) else cells[columnIndex] = value
                }
                if (cells.any { it.isNotBlank() }) rows.add(cells)
            }
            Sheet(sheetNames.getOrNull(index) ?: "Sheet ${index + 1}", rows)
        }
        return Workbook(sheets)
    }

    /** "A" -> 0, "AB" -> 27 */
    private fun columnIndexOf(letters: String?): Int {
        if (letters.isNullOrEmpty()) return 0
        var index = 0
        letters.forEach { c -> index = index * 26 + (c - 'A' + 1) }
        return index - 1
    }

    // ------------------------------------------------------------- utilities

    private fun readZip(bytes: ByteArray): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        try {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
                var entry = zip.nextEntry
                var guard = 0
                while (entry != null && guard++ < 5_000) {
                    if (!entry.isDirectory) {
                        val buffer = ByteArrayOutputStream()
                        val chunk = ByteArray(16 * 1024)
                        var read: Int
                        var total = 0
                        while (true) {
                            read = zip.read(chunk)
                            if (read <= 0) break
                            total += read
                            if (total > 40_000_000) break // refuse zip bombs
                            buffer.write(chunk, 0, read)
                        }
                        out[entry.name] = buffer.toByteArray()
                    }
                    zip.closeEntry()
                    entry = zip.nextEntry
                }
            }
        } catch (_: Exception) {
            return emptyMap()
        }
        return out
    }

    fun inflate(payload: ByteArray): ByteArray? = try {
        val inflater = Inflater()
        inflater.setInput(payload)
        val out = ByteArrayOutputStream(payload.size * 3 + 64)
        val buffer = ByteArray(16 * 1024)
        while (!inflater.finished()) {
            val n = inflater.inflate(buffer)
            if (n == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break
            } else {
                out.write(buffer, 0, n)
            }
        }
        inflater.end()
        out.toByteArray().takeIf { it.isNotEmpty() }
    } catch (_: Exception) {
        null
    }

    fun unescapeXml(value: String): String =
        value.replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&apos;", "'")
            .replace("&amp;", "&")
}
