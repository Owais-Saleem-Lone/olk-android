package com.openlibrarykashmir.olk

import com.google.zxing.BarcodeFormat
import com.google.zxing.oned.EAN13Writer
import com.google.zxing.oned.EAN8Writer
import com.google.zxing.qrcode.QRCodeWriter
import com.openlibrarykashmir.olk.feature.mybooks.bookBarcodeReader
import com.openlibrarykashmir.olk.feature.mybooks.decodeLuminance
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The scanner's decoding step, exercised against generated barcodes. Camera
 * frames arrive as greyscale (the Y plane), which is what these build.
 */
class BarcodeDecodingTest {

    private fun luminanceOf(matrix: com.google.zxing.common.BitMatrix, quietZone: Int = 20): Triple<ByteArray, Int, Int> {
        val width = matrix.width + quietZone * 2
        val height = matrix.height + quietZone * 2
        val bytes = ByteArray(width * height) { WHITE }
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width) {
                if (matrix.get(x, y)) bytes[(y + quietZone) * width + (x + quietZone)] = BLACK
            }
        }
        return Triple(bytes, width, height)
    }

    @Test
    fun `reads the EAN-13 barcode printed on a book`() {
        val isbn = "9780141439518"
        val (bytes, width, height) = luminanceOf(EAN13Writer().encode(isbn, BarcodeFormat.EAN_13, 380, 160))

        assertEquals(isbn, decodeLuminance(bookBarcodeReader(), bytes, width, width, height))
    }

    @Test
    fun `reads a short EAN-8 too`() {
        val code = "96385074"
        val (bytes, width, height) = luminanceOf(EAN8Writer().encode(code, BarcodeFormat.EAN_8, 300, 160))

        assertEquals(code, decodeLuminance(bookBarcodeReader(), bytes, width, width, height))
    }

    @Test
    fun `ignores a QR code, which is never an ISBN`() {
        val (bytes, width, height) = luminanceOf(QRCodeWriter().encode("https://example.com", BarcodeFormat.QR_CODE, 200, 200))

        assertNull(decodeLuminance(bookBarcodeReader(), bytes, width, width, height))
    }

    @Test
    fun `a frame with no barcode decodes to nothing`() {
        val bytes = ByteArray(200 * 200) { WHITE }

        assertNull(decodeLuminance(bookBarcodeReader(), bytes, 200, 200, 200))
    }

    private companion object {
        const val WHITE = 255.toByte()
        const val BLACK = 0.toByte()
    }
}
