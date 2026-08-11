package com.ssh.scanner.qr

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class QrMatrixDecoderTest {

    @Test
    fun decodesNumericPayload() {
        val symbol = QrMatrixDecoder.decode(QrTestEncoder.encode("01234567"))

        assertNotNull(symbol)
        assertEquals("01234567", symbol.text)
        assertEquals(1, symbol.version.number)
        assertEquals(ErrorCorrectionLevel.M, symbol.errorCorrectionLevel)
    }

    @Test
    fun decodesAlphanumericPayload() {
        val text = "HTTPS://SSHORG.COM"
        val symbol = QrMatrixDecoder.decode(QrTestEncoder.encode(text))

        assertEquals(text, symbol?.text)
    }

    @Test
    fun decodesByteModeWithCyrillic() {
        // Кириллица идёт в байтовом режиме как UTF-8 без ECI — ровно так пишут реальные генераторы,
        // и именно на этом проверяется эвристика «сначала UTF-8, потом Latin-1».
        val text = "Салом, Хуҷанд!"
        val symbol = QrMatrixDecoder.decode(QrTestEncoder.encode(text))

        assertEquals(text, symbol?.text)
    }

    @Test
    fun decodesEveryMaskPattern() {
        for (mask in 0..7) {
            val symbol = QrMatrixDecoder.decode(QrTestEncoder.encode("MASK TEST", maskPattern = mask))
            assertEquals("MASK TEST", symbol?.text, "маска $mask")
            assertEquals(mask, symbol?.maskPattern, "маска $mask должна быть прочитана обратно")
        }
    }

    @Test
    fun decodesEveryErrorCorrectionLevel() {
        for (level in ErrorCorrectionLevel.entries) {
            val symbol = QrMatrixDecoder.decode(QrTestEncoder.encode("LEVEL TEST", level = level))
            assertEquals("LEVEL TEST", symbol?.text, "уровень $level")
            assertEquals(level, symbol?.errorCorrectionLevel)
        }
    }

    @Test
    fun decodesEverySupportedVersion() {
        // Пробегаем все версии: каждая имеет своё число блоков, свои выравниватели, а с седьмой —
        // ещё и информацию о версии. Ошибка в любой строке таблицы всплывёт здесь.
        for (number in 1..QrVersion.MAX_SUPPORTED) {
            val text = "V$number-" + "X".repeat(number * 3)
            val matrix = QrTestEncoder.encode(text, minVersion = number)
            val symbol = QrMatrixDecoder.decode(matrix)

            assertEquals(text, symbol?.text, "версия $number")
            assertEquals(number, symbol?.version?.number, "версия $number должна определиться по размеру")
        }
    }

    @Test
    fun decodesMultiBlockSymbol() {
        // Версия 5 уровня Q — две группы блоков разной длины: проверяем переплетение слов.
        val text = "BLOCK INTERLEAVING TEST 1234567890 ABCDEFGHIJ"
        val symbol = QrMatrixDecoder.decode(
            QrTestEncoder.encode(text, level = ErrorCorrectionLevel.Q, minVersion = 5),
        )

        assertEquals(text, symbol?.text)
    }

    @Test
    fun recoversFromDamagedModules() {
        // Портим прямоугольник модулей — имитация блика или пальца на углу кода.
        val text = "DAMAGE RECOVERY"
        val matrix = QrTestEncoder.encode(text, level = ErrorCorrectionLevel.H, minVersion = 4)
        for (y in 12 until 18) {
            for (x in 12 until 18) {
                matrix[x, y] = !matrix[x, y]
            }
        }

        assertEquals(text, QrMatrixDecoder.decode(matrix)?.text)
    }

    @Test
    fun rejectsNonSquareMatrix() {
        assertNull(QrMatrixDecoder.decode(com.ssh.scanner.image.BitMatrix(21, 25)))
    }

    @Test
    fun rejectsMatrixOfWrongSize() {
        assertNull(QrMatrixDecoder.decode(com.ssh.scanner.image.BitMatrix(22, 22)))
    }

    @Test
    fun rejectsSymbolDamagedBeyondRepair() {
        val matrix = QrTestEncoder.encode("BEYOND REPAIR", level = ErrorCorrectionLevel.L)
        // Уровень L прощает ~7 %; выворачиваем половину символа наизнанку.
        for (y in 0 until matrix.height) {
            for (x in 0 until matrix.width / 2) {
                matrix[x, y] = !matrix[x, y]
            }
        }

        assertNull(QrMatrixDecoder.decode(matrix))
    }
}
