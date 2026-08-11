package com.ssh.scanner.qr

import com.ssh.scanner.image.BitMatrix

/**
 * Геометрия QR-символа: где функциональные паттерны, в каком порядке идут модули данных,
 * как накладывается маска.
 *
 * Класс общий для кодера (он живёт в тестах) и декодера намеренно. Если бы каждый считал раскладку
 * сам, они могли бы «договориться» об одинаково неверной геометрии, и roundtrip-тест был бы зелёным
 * при нечитаемых в реальности символах.
 */
internal class QrSymbolLayout(val version: QrVersion) {

    val size: Int = version.size

    /** `true` — модуль занят функциональным паттерном и не участвует в потоке данных. */
    private val reserved: BitMatrix = BitMatrix(size, size).also { reserveFunctionModules(it) }

    fun isFunctionModule(x: Int, y: Int): Boolean = reserved[x, y]

    /** Рисует всё, что не является данными: искатели, разделители, синхродорожки, выравниватели. */
    fun drawFunctionPatterns(matrix: BitMatrix) {
        drawFinderPattern(matrix, 0, 0)
        drawFinderPattern(matrix, size - FINDER_SIZE, 0)
        drawFinderPattern(matrix, 0, size - FINDER_SIZE)

        // Синхродорожки: чередование, по которому декодер восстанавливает шаг сетки.
        for (position in TIMING_START until size - TIMING_START) {
            val black = position % 2 == 0
            matrix[position, TIMING_LINE] = black
            matrix[TIMING_LINE, position] = black
        }

        for (centerY in version.alignmentCenters) {
            for (centerX in version.alignmentCenters) {
                if (isAlignmentUnderFinder(centerX, centerY)) continue
                drawAlignmentPattern(matrix, centerX, centerY)
            }
        }

        // «Тёмный модуль» — единственный модуль с постоянным значением вне паттернов.
        matrix[DARK_MODULE_X, size - DARK_MODULE_OFFSET] = true
    }

    fun drawFormatInformation(matrix: BitMatrix, level: ErrorCorrectionLevel, maskPattern: Int) {
        val bits = BchCodes.encodeFormat(level, maskPattern)
        formatPositions().forEachIndexed { bitIndex, positions ->
            val bit = (bits ushr bitIndex) and 1 == 1
            positions.forEach { (x, y) -> matrix[x, y] = bit }
        }
    }

    fun drawVersionInformation(matrix: BitMatrix) {
        if (!version.hasVersionInformation) return
        val bits = BchCodes.encodeVersion(version.number)
        versionPositions().forEachIndexed { bitIndex, positions ->
            val bit = (bits ushr bitIndex) and 1 == 1
            positions.forEach { (x, y) -> matrix[x, y] = bit }
        }
    }

    /**
     * Позиции 15 бит формата, младший бит первым. Каждый бит записан дважды — вокруг верхнего
     * левого искателя и разорванной полосой у двух других, чтобы потеря угла не убила формат.
     */
    fun formatPositions(): List<List<Pair<Int, Int>>> = List(FORMAT_BITS) { bitIndex ->
        val first = when {
            bitIndex < 6 -> 8 to bitIndex
            bitIndex == 6 -> 8 to 7
            bitIndex == 7 -> 8 to 8
            bitIndex == 8 -> 7 to 8
            else -> (14 - bitIndex) to 8
        }
        val second = if (bitIndex < 8) {
            (size - 1 - bitIndex) to 8
        } else {
            8 to (size - FORMAT_BITS + bitIndex)
        }
        listOf(first, second)
    }

    /** Позиции 18 бит версии, младший бит первым: два блока 6×3 у правого верхнего и левого нижнего. */
    fun versionPositions(): List<List<Pair<Int, Int>>> = List(VERSION_BITS) { bitIndex ->
        val along = size - 11 + bitIndex % 3
        val across = bitIndex / 3
        listOf(along to across, across to along)
    }

    /**
     * Модули данных в порядке записи: парами столбцов справа налево, змейкой снизу вверх и обратно.
     * Столбец синхродорожки пропускается целиком — иначе вся раскладка сдвинется на модуль.
     */
    fun dataModulePositions(): List<Pair<Int, Int>> {
        val positions = ArrayList<Pair<Int, Int>>(size * size)
        var right = size - 1
        while (right >= 1) {
            if (right == TIMING_LINE) right = TIMING_LINE - 1
            for (step in 0 until size) {
                for (columnInPair in 0..1) {
                    val x = right - columnInPair
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - step else step
                    if (!reserved[x, y]) positions += x to y
                }
            }
            right -= 2
        }
        return positions
    }

    /** Накладывает маску на модули данных. Операция обратна сама себе — ею же снимают. */
    fun applyMask(matrix: BitMatrix, maskPattern: Int) {
        require(maskPattern in 0..7) { "Маска QR должна быть 0…7, получено $maskPattern" }
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (reserved[x, y]) continue
                if (maskCondition(maskPattern, x, y)) matrix[x, y] = !matrix[x, y]
            }
        }
    }

    private fun maskCondition(pattern: Int, x: Int, y: Int): Boolean = when (pattern) {
        0 -> (x + y) % 2 == 0
        1 -> y % 2 == 0
        2 -> x % 3 == 0
        3 -> (x + y) % 3 == 0
        4 -> (x / 3 + y / 2) % 2 == 0
        5 -> x * y % 2 + x * y % 3 == 0
        6 -> (x * y % 2 + x * y % 3) % 2 == 0
        else -> ((x + y) % 2 + x * y % 3) % 2 == 0
    }

    private fun reserveFunctionModules(target: BitMatrix) {
        // Искатели вместе с разделителями — квадрат 9×9 в каждом из трёх углов.
        target.setRegion(0, 0, FINDER_SIZE + 1, FINDER_SIZE + 1, true)
        target.setRegion(size - FINDER_SIZE - 1, 0, FINDER_SIZE + 1, FINDER_SIZE + 1, true)
        target.setRegion(0, size - FINDER_SIZE - 1, FINDER_SIZE + 1, FINDER_SIZE + 1, true)

        for (position in 0 until size) {
            target[position, TIMING_LINE] = true
            target[TIMING_LINE, position] = true
        }

        for (centerY in version.alignmentCenters) {
            for (centerX in version.alignmentCenters) {
                if (isAlignmentUnderFinder(centerX, centerY)) continue
                target.setRegion(
                    centerX - ALIGNMENT_RADIUS,
                    centerY - ALIGNMENT_RADIUS,
                    ALIGNMENT_SIZE,
                    ALIGNMENT_SIZE,
                    true,
                )
            }
        }

        formatPositions().flatten().forEach { (x, y) -> target[x, y] = true }
        target[DARK_MODULE_X, size - DARK_MODULE_OFFSET] = true
        if (version.hasVersionInformation) {
            versionPositions().flatten().forEach { (x, y) -> target[x, y] = true }
        }
    }

    /** Выравниватель не рисуется там, где его место занял искатель. */
    private fun isAlignmentUnderFinder(centerX: Int, centerY: Int): Boolean {
        val nearLeft = centerX == QrVersion.FIRST_ALIGNMENT_CENTER
        val nearTop = centerY == QrVersion.FIRST_ALIGNMENT_CENTER
        val nearRight = centerX == size - 7
        val nearBottom = centerY == size - 7
        return (nearLeft && nearTop) || (nearRight && nearTop) || (nearLeft && nearBottom)
    }

    private fun drawFinderPattern(matrix: BitMatrix, left: Int, top: Int) {
        for (y in 0 until FINDER_SIZE) {
            for (x in 0 until FINDER_SIZE) {
                // Кольца 7×7: чёрное — внешняя рамка и ядро 3×3, между ними белое кольцо.
                val ring = maxOf(
                    kotlin.math.abs(x - FINDER_RADIUS),
                    kotlin.math.abs(y - FINDER_RADIUS),
                )
                matrix[left + x, top + y] = ring != 2
            }
        }
    }

    private fun drawAlignmentPattern(matrix: BitMatrix, centerX: Int, centerY: Int) {
        for (dy in -ALIGNMENT_RADIUS..ALIGNMENT_RADIUS) {
            for (dx in -ALIGNMENT_RADIUS..ALIGNMENT_RADIUS) {
                val ring = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                matrix[centerX + dx, centerY + dy] = ring != 1
            }
        }
    }

    internal companion object {
        const val FINDER_SIZE = 7
        const val FINDER_RADIUS = 3
        const val ALIGNMENT_SIZE = 5
        const val ALIGNMENT_RADIUS = 2
        const val TIMING_LINE = 6
        const val TIMING_START = 8
        const val FORMAT_BITS = 15
        const val VERSION_BITS = 18
        const val DARK_MODULE_X = 8
        const val DARK_MODULE_OFFSET = 8
    }
}
