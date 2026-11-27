package com.mobileaiagent.tools.builtin

import java.io.ByteArrayOutputStream

/**
 * Minimal, dependency-free PNG encoder (RGBA8, filter type 0, single IDAT with
 * stored-block deflate) and image header sniffing.
 *
 * Pure Kotlin so it runs identically on-device and in JVM tests. Colours are
 * ARGB ints; output files open in every standard image viewer.
 */
object Png {

    fun write(width: Int, height: Int, argb: IntArray): ByteArray {
        require(width > 0 && height > 0) { "invalid dimensions" }
        require(argb.size >= width * height) { "pixel buffer too small" }
        // Raw scanlines: filter byte 0 + RGBA per pixel.
        val raw = ByteArray(height * (1 + width * 4))
        var index = 0
        for (y in 0 until height) {
            raw[index++] = 0
            val rowStart = y * width
            for (x in 0 until width) {
                val color = argb[rowStart + x]
                raw[index++] = ((color shr 16) and 0xFF).toByte()
                raw[index++] = ((color shr 8) and 0xFF).toByte()
                raw[index++] = (color and 0xFF).toByte()
                raw[index++] = ((color shr 24) and 0xFF).toByte()
            }
        }
        val out = ByteArrayOutputStream(raw.size + 1024)
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        // IHDR
        val ihdr = ByteArrayOutputStream()
        writeIntBE(ihdr, width)
        writeIntBE(ihdr, height)
        ihdr.write(8)    // bit depth
        ihdr.write(6)    // colour type RGBA
        ihdr.write(0)    // compression
        ihdr.write(0)    // filter
        ihdr.write(0)    // interlace
        writeChunk(out, "IHDR", ihdr.toByteArray())
        writeChunk(out, "IDAT", zlibStore(raw))
        writeChunk(out, "IEND", ByteArray(0))
        return out.toByteArray()
    }

    /** zlib wrapper + stored (uncompressed) deflate blocks. Valid, if not compact. */
    private fun zlibStore(data: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(data.size + 64 + data.size / 65535 * 5)
        out.write(0x78); out.write(0x01) // CMF/FLG: deflate, 32K window, no dict
        var offset = 0
        while (offset < data.size) {
            val blockSize = minOf(65535, data.size - offset)
            val final = offset + blockSize >= data.size
            out.write(if (final) 1 else 0)
            out.write(blockSize and 0xFF)
            out.write((blockSize shr 8) and 0xFF)
            out.write(blockSize.inv() and 0xFF)
            out.write((blockSize.inv() shr 8) and 0xFF)
            out.write(data, offset, blockSize)
            offset += blockSize
        }
        if (data.isEmpty()) {
            out.write(1); out.write(0); out.write(0); out.write(0xFF); out.write(0xFF)
        }
        var a = 1
        var b = 0
        for (byte in data) {
            a = (a + (byte.toInt() and 0xFF)) % 65521
            b = (b + a) % 65521
        }
        writeIntBE(out, (b shl 16) or a)
        return out.toByteArray()
    }

    private fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
        writeIntBE(out, data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        out.write(typeBytes)
        out.write(data)
        val crc = java.util.zip.CRC32()
        crc.update(typeBytes)
        crc.update(data)
        writeIntBE(out, crc.value.toInt())
    }

    private fun writeIntBE(out: ByteArrayOutputStream, value: Int) {
        out.write((value ushr 24) and 0xFF)
        out.write((value ushr 16) and 0xFF)
        out.write((value ushr 8) and 0xFF)
        out.write(value and 0xFF)
    }
}

object ImageMeta {
    data class Info(val format: String, val width: Int, val height: Int)

    fun detect(bytes: ByteArray): Info? {
        if (bytes.size < 12) return null
        return when {
            bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes.size >= 24 -> Info(
                "PNG",
                readIntBE(bytes, 16),
                readIntBE(bytes, 20),
            )
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> detectJpeg(bytes)
            bytes.size > 10 && bytes.copyOfRange(0, 3).contentEquals("GIF".toByteArray()) -> Info(
                "GIF",
                (bytes[6].toInt() and 0xFF) or ((bytes[7].toInt() and 0xFF) shl 8),
                (bytes[8].toInt() and 0xFF) or ((bytes[9].toInt() and 0xFF) shl 8),
            )
            bytes.size > 26 && bytes.copyOfRange(0, 2).contentEquals("BM".toByteArray()) -> Info(
                "BMP",
                readIntLE(bytes, 18),
                readIntLE(bytes, 22),
            )
            bytes.size > 30 && bytes.copyOfRange(0, 4).contentEquals("RIFF".toByteArray()) &&
                bytes.copyOfRange(8, 12).contentEquals("WEBP".toByteArray()) -> Info("WebP", 0, 0)
            else -> null
        }
    }

    private fun detectJpeg(bytes: ByteArray): Info? {
        var i = 2
        while (i < bytes.size - 9) {
            if (bytes[i] != 0xFF.toByte()) { i++; continue }
            val marker = bytes[i + 1].toInt() and 0xFF
            if (marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC) {
                val height = ((bytes[i + 5].toInt() and 0xFF) shl 8) or (bytes[i + 6].toInt() and 0xFF)
                val width = ((bytes[i + 7].toInt() and 0xFF) shl 8) or (bytes[i + 8].toInt() and 0xFF)
                return Info("JPEG", width, height)
            }
            val length = ((bytes[i + 2].toInt() and 0xFF) shl 8) or (bytes[i + 3].toInt() and 0xFF)
            if (length <= 0) return null
            i += 2 + length
        }
        return Info("JPEG", 0, 0)
    }

    private fun readIntBE(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xFF) shl 24) or ((bytes[at + 1].toInt() and 0xFF) shl 16) or
            ((bytes[at + 2].toInt() and 0xFF) shl 8) or (bytes[at + 3].toInt() and 0xFF)

    private fun readIntLE(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or ((bytes[at + 3].toInt() and 0xFF) shl 24)
}

object Rgb {
    fun parse(hex: String): Int {
        val clean = hex.trim().removePrefix("#")
        return when (clean.length) {
            6 -> (0xFF000000.toInt()) or clean.toInt(16)
            8 -> clean.toLong(16).toInt()
            3 -> {
                val r = clean[0].digitToInt(16) * 17
                val g = clean[1].digitToInt(16) * 17
                val b = clean[2].digitToInt(16) * 17
                (0xFF000000.toInt()) or (r shl 16) or (g shl 8) or b
            }
            else -> 0xFF4C8DFF.toInt()
        }
    }

    fun lerp(a: Int, b: Int, t: Double): Int {
        val clamped = t.coerceIn(0.0, 1.0)
        fun ch(shift: Int): Int {
            val av = (a shr shift) and 0xFF
            val bv = (b shr shift) and 0xFF
            return (av + (bv - av) * clamped).toInt().coerceIn(0, 255)
        }
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/**
 * Android-only image scaling (uses BitmapFactory/Bitmap). Declared in a separate
 * object so the pure-Kotlin tool classes stay testable on the JVM: on a JVM the
 * call fails loudly with a clear message instead of pretending to resize.
 */
object RealImage {
    fun rescale(
        ws: ToolWorkspaceRef,
        inPath: String,
        outPath: String,
        maxWidth: Int,
        maxHeight: Int,
        quality: Int,
    ): com.mobileaiagent.tools.spec.ToolResult {
        return try {
            val bitmapClass = Class.forName("android.graphics.BitmapFactory")
            val decodeMethod = bitmapClass.getMethod("decodeFile", String::class.java)
            val bitmap = decodeMethod.invoke(null, ws.absolutePathFor(inPath))
                ?: return com.mobileaiagent.tools.spec.ToolResult.Failure("Could not decode $inPath", errorKind = "unsupported")
            val bmpClass = Class.forName("android.graphics.Bitmap")
            val width = bmpClass.getMethod("getWidth").invoke(bitmap) as Int
            val height = bmpClass.getMethod("getHeight").invoke(bitmap) as Int
            val targetWidth = when {
                maxWidth > 0 -> maxWidth
                maxHeight > 0 -> (width.toLong() * maxHeight / height).toInt().coerceAtLeast(1)
                else -> width
            }
            val targetHeight = when {
                maxHeight > 0 -> maxHeight
                maxWidth > 0 -> (height.toLong() * maxWidth / width).toInt().coerceAtLeast(1)
                else -> height
            }
            val scaledClass = Class.forName("android.graphics.Bitmap")
            val createScaled = bmpClass.getMethod("createScaledBitmap", bmpClass, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType)
            val scaled = createScaled.invoke(null, bitmap, targetWidth, targetHeight, true)
            val outStream = java.io.FileOutputStream(ws.absolutePathFor(outPath))
            outStream.use { stream ->
                if (outPath.endsWith(".jpg", true) || outPath.endsWith(".jpeg", true)) {
                    val compress = bmpClass.getMethod("compress", Class.forName("android.graphics.Bitmap\$CompressFormat"), Int::class.javaPrimitiveType, java.io.OutputStream::class.java)
                    val formatClass = Class.forName("android.graphics.Bitmap\$CompressFormat")
                    val jpeg = formatClass.getField("JPEG").get(null)
                    compress.invoke(scaled, jpeg, quality.coerceIn(1, 100), stream)
                } else {
                    val compress = bmpClass.getMethod("compress", Class.forName("android.graphics.Bitmap\$CompressFormat"), Int::class.javaPrimitiveType, java.io.OutputStream::class.java)
                    val formatClass = Class.forName("android.graphics.Bitmap\$CompressFormat")
                    val png = formatClass.getField("PNG").get(null)
                    compress.invoke(scaled, png, 100, stream)
                }
            }
            com.mobileaiagent.tools.spec.ToolResult.Success("Resized $inPath ($width×$height) → $outPath ($targetWidth×$targetHeight)")
        } catch (e: ClassNotFoundException) {
            com.mobileaiagent.tools.spec.ToolResult.Failure(
                "Image decoding requires Android; this build path is unavailable here.",
                errorKind = "unsupported",
            )
        } catch (e: Exception) {
            com.mobileaiagent.tools.spec.ToolResult.Failure("Resize failed: ${e.message}", errorKind = "io")
        }
    }
}

/** Narrow view of the workspace that RealImage needs. */
interface ToolWorkspaceRef {
    fun absolutePathFor(relativePath: String): String
}
