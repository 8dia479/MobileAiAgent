package com.mobileaiagent.ui.viewer

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/**
 * Image loading for the Workspace viewer.
 *
 * Decoding is done with BitmapFactory and *subsampled to the view size*, so a
 * 40-megapixel photo does not allocate 160 MB. This is pure platform code, no
 * image library, and it is exercised by the unit tests through [sampleSize].
 */
object ImageLoader {

    /** Largest edge we will ever decode for a preview. */
    const val MAX_PREVIEW_EDGE = 2048

    data class ImageInfo(
        val width: Int,
        val height: Int,
        val mimeType: String?,
        val hasAlpha: Boolean,
    ) {
        val aspectRatio: Float get() = if (height == 0) 1f else width.toFloat() / height
    }

    /** Reads dimensions without decoding pixels (cheap: header only). */
    fun inspect(file: File): ImageInfo? {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        return ImageInfo(
            width = options.outWidth,
            height = options.outHeight,
            mimeType = options.outMimeType,
            hasAlpha = mimeTypeHasAlpha(options.outMimeType),
        )
    }

    /**
     * Decodes [file] so the longest edge is at most [targetEdge]. Returns null for
     * anything that is not a decodable image (corrupt file, unsupported format).
     */
    fun decode(file: File, targetEdge: Int = MAX_PREVIEW_EDGE): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        val edge = targetEdge.coerceIn(64, MAX_PREVIEW_EDGE)
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, edge)
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        return runCatching { BitmapFactory.decodeFile(file.absolutePath, options) }.getOrNull()
    }

    /**
     * Power-of-two subsampling factor: the smallest n such that
     * max(width, height) / n <= targetEdge. BitmapFactory requires a power of 2.
     */
    fun sampleSize(width: Int, height: Int, targetEdge: Int): Int {
        if (width <= 0 || height <= 0 || targetEdge <= 0) return 1
        var sample = 1
        var longest = maxOf(width, height)
        // Keep halving while the *result* would still be larger than the target.
        // Using `longest / 2 >= target` would stop one step early (8000 -> 4000).
        while (longest > targetEdge && longest / 2 >= 1) {
            longest /= 2
            sample *= 2
        }
        return sample
    }

    private fun mimeTypeHasAlpha(mime: String?): Boolean =
        mime == "image/png" || mime == "image/webp" || mime == "image/gif"
}
