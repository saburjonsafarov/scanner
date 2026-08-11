package com.ssh.scanner.image

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AdaptiveBinarizerTest {

    /**
     * Главный тест смысла всего класса: кадр с сильным градиентом освещения, на котором
     * тёмное пятно в светлой части **светлее**, чем фон в тёмной части. Любой глобальный порог
     * здесь обязан ошибиться хотя бы на одном из двух пятен.
     */
    @Test
    fun separatesMarksUnderLightingGradient() {
        val width = 96
        val height = 96
        val pixels = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val background = 210 - (x * 150 / width) // 210 слева → 60 справа
                pixels[y * width + x] = background.toByte()
            }
        }
        val markContrast = 55
        fun drawMark(left: Int, top: Int, size: Int) {
            for (y in top until top + size) {
                for (x in left until left + size) {
                    val background = 210 - (x * 150 / width)
                    pixels[y * width + x] = (background - markContrast).toByte()
                }
            }
        }
        drawMark(left = 12, top = 12, size = 14) // в светлой части: ~150 — светлее фона справа
        drawMark(left = 70, top = 70, size = 14) // в тёмной части: ~10

        val matrix = AdaptiveBinarizer().binarize(GrayImage(pixels, width, height))

        assertTrue(matrix[18, 18], "пятно в светлой части должно стать чёрным")
        assertTrue(matrix[76, 76], "пятно в тёмной части должно стать чёрным")
        assertFalse(matrix[50, 20], "фон между пятнами должен остаться белым")
        assertFalse(matrix[90, 20], "тёмный фон справа всё равно фон, а не метка")
    }

    @Test
    fun handlesFrameSizeNotDivisibleByBlock() {
        // 70×66 не делится на 8: последний блок в каждом ряду неполный.
        val width = 70
        val height = 66
        val pixels = ByteArray(width * height) { 200.toByte() }
        for (y in 40 until 60) {
            for (x in 50 until 68) {
                pixels[y * width + x] = 30
            }
        }

        val matrix = AdaptiveBinarizer().binarize(GrayImage(pixels, width, height))

        assertTrue(matrix[60, 50], "метка у правого края")
        assertTrue(matrix[67, 59], "самый последний столбец метки — в неполном блоке")
        assertFalse(matrix[10, 10])
    }

    @Test
    fun fallsBackToGlobalThresholdOnTinyImages() {
        // Меньше пяти блоков по стороне — работает Оцу, и он обязан разделить два уровня.
        val width = 20
        val height = 20
        val pixels = ByteArray(width * height) { 220.toByte() }
        for (y in 5 until 15) {
            for (x in 5 until 15) {
                pixels[y * width + x] = 40
            }
        }

        val matrix = AdaptiveBinarizer().binarize(GrayImage(pixels, width, height))

        assertTrue(matrix[10, 10])
        assertFalse(matrix[2, 2])
    }

    @Test
    fun keepsUniformWhiteFrameWhite() {
        // Однородный светлый кадр без единой метки: порог не должен «найти» структуру в шуме.
        val width = 64
        val height = 64
        val pixels = ByteArray(width * height) { index -> (200 + (index % 5)).toByte() }

        val matrix = AdaptiveBinarizer().binarize(GrayImage(pixels, width, height))

        var black = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (matrix[x, y]) black++
            }
        }
        assertTrue(black == 0, "на чистом фоне не должно быть ни одного чёрного пикселя, найдено $black")
    }
}
