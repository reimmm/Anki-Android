// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.kiosk

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.ReaderException
import com.google.zxing.common.GlobalHistogramBinarizer
import com.google.zxing.common.HybridBinarizer
import java.nio.ByteBuffer

/**
 * Offline QR code decoder backed by ZXing Core, handling padded camera Y-plane strides,
 * 90-degree portrait rotation, and inverted (light-on-dark) QR codes.
 */
object KioskQrDecoder {
    private val hints =
        mapOf(
            DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
            DecodeHintType.CHARACTER_SET to "UTF-8",
            DecodeHintType.TRY_HARDER to true,
        )

    fun extractContiguousYPlane(
        buffer: ByteBuffer,
        width: Int,
        height: Int,
        rowStride: Int,
        pixelStride: Int,
    ): ByteArray {
        val output = ByteArray(width * height)
        val duplicate = buffer.duplicate()
        duplicate.rewind()
        if (pixelStride == 1 && rowStride == width) {
            duplicate.get(output, 0, output.size.coerceAtMost(duplicate.remaining()))
            return output
        }
        val rowBuffer = ByteArray(rowStride)
        for (row in 0 until height) {
            val rowStart = row * rowStride
            if (rowStart >= duplicate.limit()) break
            duplicate.position(rowStart)
            val bytesToRead = rowStride.coerceAtMost(duplicate.remaining())
            duplicate.get(rowBuffer, 0, bytesToRead)
            if (pixelStride == 1) {
                System.arraycopy(rowBuffer, 0, output, row * width, width.coerceAtMost(bytesToRead))
            } else {
                var outIndex = row * width
                for (col in 0 until width) {
                    val srcIndex = col * pixelStride
                    if (srcIndex < bytesToRead) {
                        output[outIndex] = rowBuffer[srcIndex]
                    }
                    outIndex++
                }
            }
        }
        return output
    }

    fun decodeLuminance(
        luminance: ByteArray,
        width: Int,
        height: Int,
    ): String? {
        if (width <= 0 || height <= 0 || luminance.size < width * height) return null
        val reader = MultiFormatReader().apply { setHints(hints) }
        decodeSingleOrientation(reader, luminance, width, height)?.let { return it }

        // Rotate 90 degrees clockwise for portrait camera sensors if needed
        val rotated = rotate90Clockwise(luminance, width, height)
        return decodeSingleOrientation(reader, rotated, height, width)
    }

    private fun decodeSingleOrientation(
        reader: MultiFormatReader,
        data: ByteArray,
        width: Int,
        height: Int,
    ): String? {
        val source = PlanarYUVLuminanceSource(data, width, height, 0, 0, width, height, false)
        tryDecode(reader, BinaryBitmap(HybridBinarizer(source)))?.let { return it }
        tryDecode(reader, BinaryBitmap(GlobalHistogramBinarizer(source)))?.let { return it }
        val inverted = source.invert()
        return tryDecode(reader, BinaryBitmap(HybridBinarizer(inverted)))
    }

    private fun tryDecode(
        reader: MultiFormatReader,
        bitmap: BinaryBitmap,
    ): String? =
        try {
            reader.decodeWithState(bitmap).text?.takeIf { it.isNotEmpty() }
        } catch (_: ReaderException) {
            null
        } finally {
            reader.reset()
        }

    internal fun rotate90Clockwise(
        data: ByteArray,
        width: Int,
        height: Int,
    ): ByteArray {
        val rotated = ByteArray(width * height)
        for (y in 0 until height) {
            val rowOffset = y * width
            val targetX = height - 1 - y
            for (x in 0 until width) {
                rotated[x * height + targetX] = data[rowOffset + x]
            }
        }
        return rotated
    }
}
