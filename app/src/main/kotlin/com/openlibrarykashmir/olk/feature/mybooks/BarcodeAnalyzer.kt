package com.openlibrarykashmir.olk.feature.mybooks

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * Reads book barcodes from the camera feed with ZXing's plain decoder — no ML Kit,
 * so nothing here needs Google Play services.
 *
 * A book's barcode is an EAN-13 (or the older EAN-8) whose digits are its ISBN, so
 * only those two formats are looked for; that keeps each frame cheap and avoids
 * reading stray QR codes.
 */
class BarcodeAnalyzer(private val onIsbn: (String) -> Unit) : ImageAnalysis.Analyzer {

    private val reader = bookBarcodeReader()

    @Volatile
    private var done = false

    override fun analyze(image: ImageProxy) {
        if (done) {
            image.close()
            return
        }
        try {
            decode(image)?.let { isbn ->
                done = true
                onIsbn(isbn)
            }
        } finally {
            image.close()
        }
    }

    private fun decode(image: ImageProxy): String? {
        // The Y plane of YUV_420_888 is the greyscale image, which is all the
        // decoder needs; no colour conversion or bitmap allocation per frame.
        val plane = image.planes.firstOrNull() ?: return null
        val buffer = plane.buffer
        val bytes = ByteArray(buffer.remaining()).also { buffer.get(it) }
        return decodeLuminance(reader, bytes, plane.rowStride, image.width, image.height)
    }
}

/**
 * Decodes one greyscale frame. Separate from the camera plumbing so it can be
 * tested against a generated barcode without a device.
 */
internal fun decodeLuminance(
    reader: MultiFormatReader,
    luminance: ByteArray,
    rowStride: Int,
    width: Int,
    height: Int,
): String? {
    val source = PlanarYUVLuminanceSource(
        luminance,
        rowStride,
        height,
        0,
        0,
        minOf(rowStride, width),
        height,
        false,
    )
    return runCatching { reader.decodeWithState(BinaryBitmap(HybridBinarizer(source))) }
        .getOrNull()
        ?.text
        ?.takeIf { it.all(Char::isDigit) && (it.length == EAN_8 || it.length == EAN_13) }
        .also { reader.reset() }
}

/** The formats a book barcode uses; nothing else is worth decoding here. */
internal fun bookBarcodeReader(): MultiFormatReader = MultiFormatReader().apply {
    setHints(
        mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.EAN_13, BarcodeFormat.EAN_8),
            DecodeHintType.TRY_HARDER to true,
        ),
    )
}

private const val EAN_8 = 8
private const val EAN_13 = 13
