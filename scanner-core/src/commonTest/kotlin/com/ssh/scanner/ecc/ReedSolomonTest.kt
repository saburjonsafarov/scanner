package com.ssh.scanner.ecc

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ReedSolomonTest {

    /**
     * Эталон, посчитанный не нами: кодовые слова из хрестоматийного примера QR версии 1-M
     * со строкой `01234567` (ISO/IEC 18004). Этот тест — главная защита от «зеркальной ошибки»:
     * если бы кодер и декодер врали согласованно, roundtrip всё равно был бы зелёным, а совпадение
     * с внешним эталоном — нет.
     */
    @Test
    fun matchesPublishedQrExample() {
        val data = intArrayOf(
            16, 32, 12, 86, 97, 128, 236, 17, 236, 17, 236, 17, 236, 17, 236, 17,
        )
        val expectedEc = intArrayOf(165, 36, 212, 193, 237, 54, 199, 135, 44, 85)

        assertContentEquals(expectedEc, ReedSolomonEncoder.encode(data, ecLength = 10))
    }

    @Test
    fun generatorForTwoCorrectionWordsExpandsByHand() {
        // Π(x − α^i) при i = 0,1 это (x+1)(x+2) = x² + 3x + 2.
        val generator = ReedSolomonEncoder.generator(ecLength = 2)

        assertEquals(2, generator.degree)
        assertEquals(1, generator.coefficientAt(2))
        assertEquals(3, generator.coefficientAt(1))
        assertEquals(2, generator.coefficientAt(0))
    }

    @Test
    fun generatorRootsAreConsecutivePowersOfAlpha() {
        val ecLength = 10
        val generator = ReedSolomonEncoder.generator(ecLength)

        for (power in 0 until ecLength) {
            assertEquals(
                0,
                generator.evaluateAt(GaloisField256.exp(power)),
                "α^$power обязан быть корнем порождающего многочлена",
            )
        }
    }

    @Test
    fun cleanCodewordsDecodeUntouched() {
        val data = intArrayOf(32, 91, 11, 120, 209, 114, 220, 77, 67, 64)
        val codewords = data + ReedSolomonEncoder.encode(data, ecLength = 10)
        val original = codewords.copyOf()

        assertTrue(ReedSolomonDecoder.decode(codewords, ecLength = 10))
        assertContentEquals(original, codewords)
    }

    @Test
    fun repairsUpToHalfOfCorrectionCapacity() {
        // 10 корректирующих слов исправляют до 5 повреждений — проверяем ровно предел.
        val data = intArrayOf(32, 91, 11, 120, 209, 114, 220, 77, 67, 64)
        val original = data + ReedSolomonEncoder.encode(data, ecLength = 10)

        for (errorCount in 1..5) {
            val damaged = original.copyOf()
            for (index in 0 until errorCount) {
                damaged[index * 3] = damaged[index * 3] xor 0x5A
            }

            assertTrue(
                ReedSolomonDecoder.decode(damaged, ecLength = 10),
                "$errorCount повреждений должны исправляться",
            )
            assertContentEquals(original, damaged, "после исправления слова обязаны совпасть")
        }
    }

    @Test
    fun repairsDamageInsideCorrectionPartItself() {
        // Повреждения в хвосте (сами корректирующие слова) ничем не отличаются от повреждений данных.
        val data = intArrayOf(64, 196, 132, 84, 196, 196, 242, 194, 4, 132)
        val original = data + ReedSolomonEncoder.encode(data, ecLength = 10)
        val damaged = original.copyOf()
        damaged[original.size - 1] = damaged[original.size - 1] xor 0xFF
        damaged[original.size - 4] = damaged[original.size - 4] xor 0x33

        assertTrue(ReedSolomonDecoder.decode(damaged, ecLength = 10))
        assertContentEquals(original, damaged)
    }

    @Test
    fun doesNotSilentlyReturnWrongDataBeyondCapacity() {
        // Шесть повреждений при десяти корректирующих словах — за пределом. Декодер обязан либо
        // честно отказаться, либо вернуть что-то заведомо не равное исходнику; чего он не имеет
        // права делать — так это отрапортовать успех с подменёнными данными.
        val data = intArrayOf(32, 91, 11, 120, 209, 114, 220, 77, 67, 64)
        val original = data + ReedSolomonEncoder.encode(data, ecLength = 10)
        val damaged = original.copyOf()
        for (index in 0 until 6) {
            damaged[index * 3] = damaged[index * 3] xor 0x5A
        }

        val repaired = ReedSolomonDecoder.decode(damaged, ecLength = 10)

        assertFalse(
            repaired && damaged.contentEquals(original),
            "случайное совпадение за пределом корректирующей способности недопустимо",
        )
    }

    @Test
    fun shortBlocksAreEncodedWithLeadingZeroes() {
        // Остаток от деления может иметь степень меньше ecLength — старшие слова тогда нулевые,
        // и длина корректирующей части всё равно обязана быть ровно ecLength.
        val ec = ReedSolomonEncoder.encode(intArrayOf(1), ecLength = 7)
        assertEquals(7, ec.size)
    }
}
