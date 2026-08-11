package com.ssh.scanner.qr

import com.ssh.scanner.image.BitMatrix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class QrVersionTest {

    /**
     * Самая ценная проверка таблицы блоков: ёмкость версии считается **из геометрии символа**
     * (сколько модулей остаётся под данные после всех функциональных паттернов) и сверяется с суммой
     * по таблице. Опечатка в любом из 200 чисел таблицы почти наверняка разойдётся с геометрией.
     */
    @Test
    fun blockTableMatchesGeometricCapacity() {
        for (version in QrVersion.supported) {
            val dataModules = QrSymbolLayout(version).dataModulePositions().size
            val capacityInCodewords = dataModules / 8

            for (level in ErrorCorrectionLevel.entries) {
                assertEquals(
                    capacityInCodewords,
                    version.ecBlocks(level).totalCodewords,
                    "версия ${version.number}, уровень $level: таблица разошлась с геометрией",
                )
            }
        }
    }

    @Test
    fun sizeGrowsByFourModulesPerVersion() {
        assertEquals(21, QrVersion.of(1).size)
        assertEquals(25, QrVersion.of(2).size)
        assertEquals(57, QrVersion.of(10).size)
    }

    @Test
    fun resolvesVersionBySize() {
        assertEquals(1, QrVersion.ofSize(21)?.number)
        assertEquals(10, QrVersion.ofSize(57)?.number)
        assertNull(QrVersion.ofSize(22), "22 не кратно шагу сетки")
        assertNull(QrVersion.ofSize(17), "меньше минимального символа")
    }

    @Test
    fun alignmentCentersStartAtSixAndEndSevenFromEdge() {
        assertEquals(0, QrVersion.of(1).alignmentCenters.size, "у первой версии выравнивателей нет")

        for (version in QrVersion.supported.drop(1)) {
            val centers = version.alignmentCenters
            assertEquals(6, centers.first(), "версия ${version.number}")
            assertEquals(version.size - 7, centers.last(), "версия ${version.number}")
        }
    }

    @Test
    fun versionInformationAppearsFromSeventhVersion() {
        assertTrue(!QrVersion.of(6).hasVersionInformation)
        assertTrue(QrVersion.of(7).hasVersionInformation)
    }
}

class BchCodesTest {

    @Test
    fun zeroFormatEncodesToTheStandardMask() {
        // Уровень M и маска 0 дают нулевые данные и нулевой БЧХ-остаток, поэтому результат —
        // ровно константа маски из стандарта. Единственное значение, которым проверяется
        // порождающий многочлен формата.
        assertEquals(0x5412, BchCodes.encodeFormat(ErrorCorrectionLevel.M, maskPattern = 0))
    }

    @Test
    fun everyFormatValueIsFarFromEveryOther() {
        // Код формата обязан иметь минимальное расстояние 7 — иначе исправление трёх ошибок,
        // на которое рассчитывает декодер, приводило бы к подмене уровня или маски.
        val values = ErrorCorrectionLevel.entries.flatMap { level ->
            (0..7).map { BchCodes.encodeFormat(level, it) }
        }
        for (i in values.indices) {
            for (j in i + 1 until values.size) {
                val distance = (values[i] xor values[j]).countOneBits()
                assertTrue(distance >= 7, "расстояние $distance между форматами $i и $j слишком мало")
            }
        }
    }

    @Test
    fun formatRoundTripsThroughDecoder() {
        for (level in ErrorCorrectionLevel.entries) {
            for (mask in 0..7) {
                val decoded = BchCodes.decodeFormat(BchCodes.encodeFormat(level, mask))
                assertEquals(FormatInfo(level, mask), decoded)
            }
        }
    }

    @Test
    fun formatSurvivesThreeFlippedBits() {
        val encoded = BchCodes.encodeFormat(ErrorCorrectionLevel.Q, maskPattern = 5)
        val damaged = encoded xor 0b000000000010110 // три единицы — три испорченных бита

        assertEquals(FormatInfo(ErrorCorrectionLevel.Q, 5), BchCodes.decodeFormat(damaged))
    }

    @Test
    fun versionInformationRoundTrips() {
        for (version in QrVersion.MIN_VERSION_WITH_VERSION_INFO..QrVersion.MAX_SUPPORTED) {
            assertEquals(version, BchCodes.decodeVersion(BchCodes.encodeVersion(version)))
        }
    }

    @Test
    fun versionInformationIsEighteenBits() {
        val encoded = BchCodes.encodeVersion(7)
        assertTrue(encoded < (1 shl 18), "информация о версии не должна вылезать за 18 бит")
        assertEquals(7, encoded ushr 12, "старшие шесть бит — номер версии")
    }
}

class QrSymbolLayoutTest {

    @Test
    fun finderPatternsHaveTheOneToThreeRatio() {
        val version = QrVersion.of(1)
        val layout = QrSymbolLayout(version)
        val matrix = BitMatrix(version.size, version.size)
        layout.drawFunctionPatterns(matrix)

        // Разрез через центр верхнего левого искателя: чёрное-белое-чёрное(3)-белое-чёрное.
        val row = (0 until 7).map { matrix[it, 3] }
        assertEquals(listOf(true, false, true, true, true, false, true), row)
    }

    @Test
    fun timingLineAlternates() {
        val version = QrVersion.of(2)
        val layout = QrSymbolLayout(version)
        val matrix = BitMatrix(version.size, version.size)
        layout.drawFunctionPatterns(matrix)

        for (position in 8 until version.size - 8) {
            assertEquals(position % 2 == 0, matrix[position, 6], "горизонтальная дорожка, x=$position")
            assertEquals(position % 2 == 0, matrix[6, position], "вертикальная дорожка, y=$position")
        }
    }

    @Test
    fun darkModuleIsAlwaysBlack() {
        val version = QrVersion.of(3)
        val matrix = BitMatrix(version.size, version.size)
        QrSymbolLayout(version).drawFunctionPatterns(matrix)

        assertTrue(matrix[8, version.size - 8])
    }

    @Test
    fun dataModulesNeverOverlapFunctionModules() {
        for (version in QrVersion.supported) {
            val layout = QrSymbolLayout(version)
            val positions = layout.dataModulePositions()

            assertEquals(positions.size, positions.toSet().size, "позиции данных не должны повторяться")
            assertTrue(
                positions.none { (x, y) -> layout.isFunctionModule(x, y) },
                "версия ${version.number}: поток данных залез на функциональный модуль",
            )
        }
    }

    @Test
    fun maskIsItsOwnInverse() {
        val version = QrVersion.of(4)
        val layout = QrSymbolLayout(version)
        val matrix = BitMatrix(version.size, version.size)
        layout.drawFunctionPatterns(matrix)
        layout.dataModulePositions().forEachIndexed { index, (x, y) -> matrix[x, y] = index % 3 == 0 }
        val before = matrix.toString()

        for (mask in 0..7) {
            layout.applyMask(matrix, mask)
            layout.applyMask(matrix, mask)
        }

        assertEquals(before, matrix.toString(), "двойное наложение маски обязано вернуть исходное")
    }

    @Test
    fun maskTouchesOnlyDataModules() {
        val version = QrVersion.of(7)
        val layout = QrSymbolLayout(version)
        val matrix = BitMatrix(version.size, version.size)
        layout.drawFunctionPatterns(matrix)
        val functionBefore = buildFunctionSnapshot(layout, matrix)

        layout.applyMask(matrix, maskPattern = 3)

        assertEquals(functionBefore, buildFunctionSnapshot(layout, matrix))
    }

    @Test
    fun formatBitsAreWrittenTwiceAndReadBack() {
        val version = QrVersion.of(5)
        val layout = QrSymbolLayout(version)
        val matrix = BitMatrix(version.size, version.size)
        layout.drawFunctionPatterns(matrix)
        layout.drawFormatInformation(matrix, ErrorCorrectionLevel.H, maskPattern = 6)

        val positions = layout.formatPositions()
        var firstCopy = 0
        var secondCopy = 0
        positions.forEachIndexed { bitIndex, copies ->
            if (matrix[copies[0].first, copies[0].second]) firstCopy = firstCopy or (1 shl bitIndex)
            if (matrix[copies[1].first, copies[1].second]) secondCopy = secondCopy or (1 shl bitIndex)
        }

        assertEquals(BchCodes.encodeFormat(ErrorCorrectionLevel.H, 6), firstCopy)
        assertEquals(firstCopy, secondCopy, "обе копии формата обязаны совпадать")
        assertNotNull(BchCodes.decodeFormat(firstCopy))
    }

    @Test
    fun formatPositionsDoNotCollide() {
        for (version in QrVersion.supported) {
            val flat = QrSymbolLayout(version).formatPositions().flatten()
            assertEquals(30, flat.size)
            assertEquals(30, flat.toSet().size, "версия ${version.number}: позиции формата пересеклись")
        }
    }

    private fun buildFunctionSnapshot(layout: QrSymbolLayout, matrix: BitMatrix): String =
        buildString {
            for (y in 0 until layout.size) {
                for (x in 0 until layout.size) {
                    if (layout.isFunctionModule(x, y)) append(if (matrix[x, y]) '#' else '.')
                }
            }
        }
}
