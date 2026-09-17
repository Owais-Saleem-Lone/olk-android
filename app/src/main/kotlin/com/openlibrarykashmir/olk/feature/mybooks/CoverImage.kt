package com.openlibrarykashmir.olk.feature.mybooks

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Turns a picked or captured photo into a cover the same way the web app's
 * `compressImage` does: longest side at most 1200px, WebP, under 500KB. A phone
 * camera photo is often 3–8MB; uploading that over a slow connection would take
 * minutes, and every reader browsing would download it too.
 */
object CoverImage {
    const val MAX_DIMENSION = 1200
    const val MAX_BYTES = 500 * 1024
    private const val START_QUALITY = 82
    private const val MIN_QUALITY = 30
    private const val QUALITY_STEP = 10

    /** Throws [IllegalArgumentException] when the file is not a readable image. */
    suspend fun compress(context: Context, uri: Uri): ByteArray = withContext(Dispatchers.IO) {
        val bitmap = decodeScaled(context, uri)
            ?: throw IllegalArgumentException("Not a readable image")
        try {
            var quality = START_QUALITY
            var bytes = encode(bitmap, quality)
            while (bytes.size > MAX_BYTES && quality > MIN_QUALITY) {
                quality -= QUALITY_STEP
                bytes = encode(bitmap, quality)
            }
            bytes
        } finally {
            bitmap.recycle()
        }
    }

    /** A cache file the camera app can write into, and the content URI to hand it. */
    fun newCaptureTarget(context: Context): Uri {
        val dir = File(context.cacheDir, "covers").apply { mkdirs() }
        val file = File.createTempFile("capture-", ".jpg", dir)
        return FileProvider.getUriForFile(context, "${context.packageName}.covers", file)
    }

    /** Captures are only needed until they are compressed and uploaded. */
    fun clearCaptures(context: Context) {
        File(context.cacheDir, "covers").listFiles()?.forEach { it.delete() }
    }

    private fun decodeScaled(context: Context, uri: Uri): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // ImageDecoder applies the EXIF rotation, so camera photos come out upright.
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                val (w, h) = info.size.width to info.size.height
                val scale = MAX_DIMENSION.toFloat() / max(w, h)
                if (scale < 1f) decoder.setTargetSize((w * scale).roundToInt(), (h * scale).roundToInt())
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            decodeWithBitmapFactory(context, uri)
        }
    }.getOrNull()

    private fun decodeWithBitmapFactory(context: Context, uri: Uri): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

        var sample = 1
        while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_DIMENSION) sample *= 2
        val decoded = context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return null

        val scale = MAX_DIMENSION.toFloat() / max(decoded.width, decoded.height)
        if (scale >= 1f) return decoded
        return Bitmap.createScaledBitmap(
            decoded,
            (decoded.width * scale).roundToInt(),
            (decoded.height * scale).roundToInt(),
            true,
        ).also { if (it !== decoded) decoded.recycle() }
    }

    private fun encode(bitmap: Bitmap, quality: Int): ByteArray {
        val format = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            @Suppress("DEPRECATION")
            Bitmap.CompressFormat.WEBP
        }
        return ByteArrayOutputStream().use { out ->
            bitmap.compress(format, quality, out)
            out.toByteArray()
        }
    }
}
