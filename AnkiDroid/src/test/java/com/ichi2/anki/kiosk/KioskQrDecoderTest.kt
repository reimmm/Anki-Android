// SPDX-License-Identifier: GPL-3.0-or-later

package com.ichi2.anki.kiosk

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.jupiter.api.Test
import java.nio.ByteBuffer
import kotlin.test.assertEquals

class KioskQrDecoderTest {
    @Test
    fun `decodes arbitrary UTF-8 QR code across normal rotated inverted and padded stride frames`() {
        val payload = "ankidroid-arbitrary-qr://鍵テスト-12345"
        val size = 220
        val matrix =
            QRCodeWriter().encode(
                payload,
                BarcodeFormat.QR_CODE,
                size,
                size,
                mapOf(EncodeHintType.CHARACTER_SET to "UTF-8"),
            )

        val luminance = ByteArray(size * size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                luminance[y * size + x] = if (matrix.get(x, y)) 16.toByte() else 240.toByte()
            }
        }

        // 1. Standard orientation
        assertEquals(payload, KioskQrDecoder.decodeLuminance(luminance, size, size))

        // 2. Inverted (white-on-black dark mode QR)
        val inverted = ByteArray(luminance.size) { i -> (255 - (luminance[i].toInt() and 0xFF)).toByte() }
        assertEquals(payload, KioskQrDecoder.decodeLuminance(inverted, size, size))

        // 3. Padded rowStride > width extraction
        val rowStride = size + 32
        val padded = ByteBuffer.allocate(rowStride * size)
        for (y in 0 until size) {
            padded.position(y * rowStride)
            padded.put(luminance, y * size, size)
        }
        val extracted = KioskQrDecoder.extractContiguousYPlane(padded, size, size, rowStride, pixelStride = 1)
        assertEquals(payload, KioskQrDecoder.decodeLuminance(extracted, size, size))
    }
}
