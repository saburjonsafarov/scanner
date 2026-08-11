package com.ssh.scanner.image

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GrayImageTest {

    @Test
    fun readsPixelsThroughRowStride() {
        // Буфер с «лишними» байтами в конце каждой строки — так кадр отдаёт камера.
        val width = 3
        val height = 2
        val rowStride = 5
        val pixels = ByteArray(rowStride * height)
        pixels[0] = 10
        pixels[2] = 20
        pixels[rowStride + 1] = 30.toByte()

        val image = GrayImage(pixels, width, height, rowStride)

        assertEquals(10, image[0, 0])
        assertEquals(20, image[2, 0])
        assertEquals(30, image[1, 1], "вторая строка начинается через rowStride, а не через width")
    }

    @Test
    fun readsHighLumaAsUnsigned() {
        // 200 не влезает в знаковый Byte и приезжает как -56: без `and 0xFF` светлый пиксель
        // притворился бы самым тёмным, и бинаризация вывернулась бы наизнанку.
        val pixels = byteArrayOf(200.toByte())
        val image = GrayImage(pixels, width = 1, height = 1)

        assertEquals(200, image[0, 0])
    }

    @Test
    fun cropSharesBufferAndShiftsOrigin() {
        val width = 4
        val height = 4
        val pixels = ByteArray(width * height) { it.toByte() }
        val image = GrayImage(pixels, width, height)

        val cropped = image.crop(x = 1, y = 1, cropWidth = 2, cropHeight = 2)

        assertEquals(2, cropped.width)
        assertEquals(5, cropped[0, 0], "пиксель (1,1) исходника")
        assertEquals(10, cropped[1, 1], "пиксель (2,2) исходника")
    }

    @Test
    fun cropOfCropStaysCorrect() {
        val pixels = ByteArray(8 * 8) { it.toByte() }
        val image = GrayImage(pixels, width = 8, height = 8)

        val once = image.crop(x = 2, y = 2, cropWidth = 4, cropHeight = 4)
        val twice = once.crop(x = 1, y = 1, cropWidth = 2, cropHeight = 2)

        assertEquals(image[3, 3], twice[0, 0], "смещения должны складываться")
    }

    @Test
    fun rejectsCropOutsideBounds() {
        val image = GrayImage(ByteArray(16), width = 4, height = 4)
        assertFailsWith<IllegalArgumentException> {
            image.crop(x = 3, y = 0, cropWidth = 2, cropHeight = 1)
        }
    }

    @Test
    fun rejectsBufferSmallerThanDeclaredFrame() {
        assertFailsWith<IllegalArgumentException> {
            GrayImage(ByteArray(10), width = 4, height = 4)
        }
    }
}
