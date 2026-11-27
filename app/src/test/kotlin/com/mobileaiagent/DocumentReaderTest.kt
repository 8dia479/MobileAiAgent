package com.mobileaiagent

import com.mobileaiagent.ui.viewer.DocumentReader
import org.junit.Assert
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * These tests construct real PDF/DOCX/XLSX byte streams and parse them back, so
 * the Workspace viewers are verified against actual file formats rather than
 * mocks. If a format assumption here is wrong, the test fails.
 */
class DocumentReaderTest {

    // ------------------------------------------------------------------- CSV

    @Test fun csvHandlesQuotedCommasAndNewlines() {
        val csv = "name,note,value\n\"Smith, J\",\"line1\nline2\",42\nplain,text,7"
        val table = DocumentReader.parseCsv(csv)
        Assert.assertEquals(listOf("name", "note", "value"), table.headers)
        Assert.assertEquals(2, table.rows.size)
        Assert.assertEquals("Smith, J", table.rows[0][0])
        Assert.assertEquals("line1\nline2", table.rows[0][1])
        Assert.assertEquals("42", table.rows[0][2])
    }

    @Test fun csvHandlesEscapedQuotesAndSemicolons() {
        val csv = "a;b\n\"say \"\"hi\"\"\";2"
        val table = DocumentReader.parseCsv(csv)
        Assert.assertEquals(listOf("a", "b"), table.headers)
        Assert.assertEquals("say \"hi\"", table.rows[0][0])
    }

    @Test fun csvSniffsTabsAndFillsBlankHeaders() {
        val table = DocumentReader.parseCsv("x\t\ty\n1\t2\t3")
        Assert.assertEquals(3, table.headers.size)
        Assert.assertEquals("Column 2", table.headers[1])
        Assert.assertEquals(listOf("1", "2", "3"), table.rows[0])
    }

    @Test fun csvStripsBomAndHandlesEmptyInput() {
        Assert.assertEquals(emptyList<String>(), DocumentReader.parseCsv("").headers)
        val table = DocumentReader.parseCsv("\uFEFFh1,h2\nv1,v2")
        Assert.assertEquals("h1", table.headers[0])
    }

    // ------------------------------------------------------------------ DOCX

    private fun zipOf(entries: Map<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    @Test fun docxExtractsParagraphsAndTables() {
        val documentXml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
              <w:body>
                <w:p><w:r><w:t>Project brief</w:t></w:r></w:p>
                <w:p><w:r><w:t xml:space="preserve">A bracket </w:t></w:r><w:r><w:t>for the wall.</w:t></w:r></w:p>
                <w:tbl>
                  <w:tr><w:tc><w:p><w:r><w:t>Part</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>Qty</w:t></w:r></w:p></w:tc></w:tr>
                  <w:tr><w:tc><w:p><w:r><w:t>Bolt</w:t></w:r></w:p></w:tc><w:tc><w:p><w:r><w:t>4</w:t></w:r></w:p></w:tc></w:tr>
                </w:tbl>
                <w:p><w:r><w:t>Escaped &amp; entity &lt;ok&gt;</w:t></w:r></w:p>
              </w:body>
            </w:document>
        """.trimIndent()
        val docx = zipOf(mapOf("word/document.xml" to documentXml))
        val doc = DocumentReader.readDocx(docx)
        Assert.assertEquals(3, doc.paragraphs.size)
        Assert.assertEquals("Project brief", doc.paragraphs[0])
        Assert.assertEquals("A bracket for the wall.", doc.paragraphs[1])
        Assert.assertEquals("Escaped & entity <ok>", doc.paragraphs[2])
        Assert.assertEquals(2, doc.tableRows.size)
        Assert.assertEquals(listOf("Part", "Qty"), doc.tableRows[0])
        Assert.assertEquals(listOf("Bolt", "4"), doc.tableRows[1])
    }

    @Test fun docxRejectsNonDocxWithClearMessage() {
        val result = DocumentReader.readDocx("not a zip".toByteArray())
        Assert.assertTrue(result.paragraphs.isEmpty())
        Assert.assertTrue(result.notes.first().contains("not supported", ignoreCase = true))
    }

    // ------------------------------------------------------------------ XLSX

    @Test fun xlsxReadsSharedStringsNumbersAndGaps() {
        val shared = """
            <?xml version="1.0" encoding="UTF-8"?>
            <sst xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <si><t>Name</t></si><si><t>Qty</t></si><si><t>Price</t></si><si><t>Widget</t></si>
            </sst>
        """.trimIndent()
        val workbook = """
            <?xml version="1.0" encoding="UTF-8"?>
            <workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheets><sheet name="Inventory" sheetId="1" r:id="rId1"/></sheets>
            </workbook>
        """.trimIndent()
        val sheet = """
            <?xml version="1.0" encoding="UTF-8"?>
            <worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
              <sheetData>
                <row r="1">
                  <c r="A1" t="s"><v>0</v></c><c r="B1" t="s"><v>1</v></c><c r="C1" t="s"><v>2</v></c>
                </row>
                <row r="2">
                  <c r="A2" t="s"><v>3</v></c><c r="B2"><v>12</v></c><c r="C2"><v>3.5</v></c>
                </row>
                <row r="3">
                  <c r="A3" t="inlineStr"><is><t>Skip column B</t></is></c><c r="C3"><v>9</v></c>
                </row>
              </sheetData>
            </worksheet>
        """.trimIndent()
        val xlsx = zipOf(
            mapOf(
                "xl/sharedStrings.xml" to shared,
                "xl/workbook.xml" to workbook,
                "xl/worksheets/sheet1.xml" to sheet,
            )
        )
        val parsed = DocumentReader.readXlsx(xlsx)
        Assert.assertEquals(1, parsed.sheets.size)
        val first = parsed.sheets[0]
        Assert.assertEquals("Inventory", first.name)
        Assert.assertEquals(listOf("Name", "Qty", "Price"), first.rows[0])
        Assert.assertEquals(listOf("Widget", "12", "3.5"), first.rows[1])
        // A gap at column B must be preserved as an empty cell, not shifted.
        Assert.assertEquals(listOf("Skip column B", "", "9"), first.rows[2])
    }

    @Test fun xlsxColumnLettersMapCorrectly() {
        val sheet = """
            <worksheet><sheetData><row><c r="AA1"><v>27</v></c></row></sheetData></worksheet>
        """.trimIndent()
        val xlsx = zipOf(mapOf("xl/worksheets/sheet1.xml" to sheet))
        val rows = DocumentReader.readXlsx(xlsx).sheets[0].rows[0]
        // "AA" is the 27th column, i.e. index 26, so the row must have 27 cells
        // with A..Z padded empty.
        Assert.assertEquals(27, rows.size)
        Assert.assertEquals("27", rows[26])
        Assert.assertTrue(rows.take(26).all { it.isEmpty() })
    }

    @Test fun xlsxRejectsGarbageWithoutThrowing() {
        val parsed = DocumentReader.readXlsx("garbage".toByteArray())
        Assert.assertTrue(parsed.sheets.isEmpty())
        Assert.assertTrue(parsed.notes.isNotEmpty())
    }

    // ------------------------------------------------------------------- PDF

    private fun deflate(data: ByteArray): ByteArray {
        val deflater = Deflater()
        deflater.setInput(data)
        deflater.finish()
        val out = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
        deflater.end()
        return out.toByteArray()
    }

    private fun buildPdf(contentStreams: List<ByteArray>, compress: Boolean, encrypt: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.7\n".toByteArray())
        out.write("1 0 obj\n<< /Type /Catalog /Pages 2 0 R >>\nendobj\n".toByteArray())
        // Encrypted PDFs carry an /Encrypt reference in the trailer (and usually an
        // inline dictionary on the security handler object). Write both, like a real one.
        if (encrypt) {
            out.write("3 0 obj\n<< /Filter /Standard /V 2 /R 3 /O (x) /U (y) /P -3904 >>\nendobj\n".toByteArray())
        }
        contentStreams.forEachIndexed { index, content ->
            val payload = if (compress) deflate(content) else content
            val header = if (compress) "<< /Length ${payload.size} /Filter /FlateDecode >>" else "<< /Length ${payload.size} >>"
            out.write("${10 + index} 0 obj\n$header\nstream\n".toByteArray())
            out.write(payload)
            out.write("\nendstream\nendobj\n".toByteArray())
        }
        val trailer = if (encrypt) "<< /Root 1 0 R /Encrypt 3 0 R >>" else "<< /Root 1 0 R >>"
        out.write("trailer\n$trailer\n%%EOF\n".toByteArray())
        return out.toByteArray()
    }

    @Test fun pdfExtractsUncompressedText() {
        val content = "BT /F1 12 Tf 72 720 Td (Hello PDF) Tj 0 -14 Td (Second line) Tj ET".toByteArray()
        val pdf = buildPdf(listOf(content), compress = false)
        val result = DocumentReader.extractPdfText(pdf)
        Assert.assertFalse(result.encrypted)
        Assert.assertTrue("got: ${result.text}", result.text.contains("Hello PDF"))
        Assert.assertTrue(result.text.contains("Second line"))
        Assert.assertTrue(result.hasText)
    }

    @Test fun pdfExtractsFlateCompressedText() {
        val content = "BT (Compressed body text) Tj ET".toByteArray()
        val pdf = buildPdf(listOf(content), compress = true)
        val result = DocumentReader.extractPdfText(pdf)
        Assert.assertTrue("got: ${result.text}", result.text.contains("Compressed body text"))
    }

    @Test fun pdfHandlesEscapesAndTJArrays() {
        val content = "BT [(Part one ) -200 (part two)] TJ (paren \\(inside\\) slash \\\\ done) Tj ET".toByteArray()
        val pdf = buildPdf(listOf(content), compress = false)
        val text = DocumentReader.extractPdfText(pdf).text
        Assert.assertTrue("got: $text", text.contains("paren (inside) slash"))
        Assert.assertTrue(text.contains("Part one"))
    }

    @Test fun pdfReportsEncryptionInsteadOfReturningEmptyText() {
        val pdf = buildPdf(listOf("BT (secret) Tj ET".toByteArray()), compress = false, encrypt = true)
        val result = DocumentReader.extractPdfText(pdf)
        Assert.assertTrue(result.encrypted)
        Assert.assertTrue(result.notes.first().contains("encrypted", ignoreCase = true))
    }

    @Test fun scannedPdfIsReportedHonestly() {
        // A page with no text operators at all is what a scan looks like.
        val pdf = buildPdf(listOf("q 100 0 0 100 0 0 cm /Im0 Do Q".toByteArray()), compress = false)
        val result = DocumentReader.extractPdfText(pdf)
        Assert.assertFalse(result.hasText)
        Assert.assertTrue(result.notes.any { it.contains("scan", ignoreCase = true) })
    }

    @Test fun nonPdfIsRejectedByMagicBytes() {
        val result = DocumentReader.extractPdfText("plain text".toByteArray())
        Assert.assertTrue(result.notes.first().contains("Not a PDF"))
    }

    @Test fun pdfCountsPages() {
        val pdf = buildPdf(listOf("BT (x) Tj ET".toByteArray()), compress = false) +
            "4 0 obj << /Type /Page /Parent 2 0 R >> endobj\n".toByteArray()
        Assert.assertTrue(DocumentReader.extractPdfText(pdf).pageCount >= 1)
    }
}

/**
 * ImageLoader.sampleSize is the memory guard for photo previews: a wrong value
 * means an OOM when a user taps a 50 MP photo. The maths is pure, so it is
 * verified here; the BitmapFactory call itself needs a device.
 */
class ImageSamplingTest {
    @Test fun smallImagesAreNotSubsampled() {
        Assert.assertEquals(1, com.mobileaiagent.ui.viewer.ImageLoader.sampleSize(800, 600, 2048))
        Assert.assertEquals(1, com.mobileaiagent.ui.viewer.ImageLoader.sampleSize(2048, 2048, 2048))
    }

    @Test fun largeImagesAreHalvedUntilTheyFit() {
        // 8000 -> 4000 -> 2000 (<= 2048): sample 4
        Assert.assertEquals(4, com.mobileaiagent.ui.viewer.ImageLoader.sampleSize(8000, 6000, 2048))
        // 12000 -> 6000 -> 3000 -> 1500: sample 8
        Assert.assertEquals(8, com.mobileaiagent.ui.viewer.ImageLoader.sampleSize(12000, 9000, 2048))
        // 4096 -> 2048 fits exactly: sample 2
        Assert.assertEquals(2, com.mobileaiagent.ui.viewer.ImageLoader.sampleSize(4096, 4096, 2048))
        // The contract that matters: the decoded longest edge never exceeds the target.
        listOf(3000 to 1024, 8000 to 2048, 20000 to 1024, 5000 to 4096).forEach { (edge, target) ->
            val sample = com.mobileaiagent.ui.viewer.ImageLoader.sampleSize(edge, edge / 2, target)
            Assert.assertTrue("edge $edge sample $sample target $target", edge / sample <= target)
        }
    }

    @Test fun sampleSizeIsAlwaysAPowerOfTwo() {
        var edge = 100
        while (edge < 100_000) {
            val sample = com.mobileaiagent.ui.viewer.ImageLoader.sampleSize(edge, edge / 2, 1024)
            Assert.assertTrue("$sample is not a power of two", sample > 0 && (sample and (sample - 1)) == 0)
            Assert.assertTrue("edge $edge over target", edge / sample <= 2048)
            edge *= 3
        }
    }

    @Test fun degenerateInputDoesNotDivideByZero() {
        Assert.assertEquals(1, com.mobileaiagent.ui.viewer.ImageLoader.sampleSize(0, 0, 1024))
        Assert.assertEquals(1, com.mobileaiagent.ui.viewer.ImageLoader.sampleSize(-5, 10, 1024))
        Assert.assertEquals(1, com.mobileaiagent.ui.viewer.ImageLoader.sampleSize(100, 100, 0))
    }
}
