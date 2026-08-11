package com.ssh.scanner.qr

import com.ssh.scanner.image.BitMatrix
import kotlin.math.abs

/** Найденный искатель: центр в пикселях и оценка размера одного модуля. */
internal data class FinderPattern(
    val x: Float,
    val y: Float,
    val moduleSize: Float,
    val confirmations: Int = 1,
) {
    fun mergedWith(other: FinderPattern): FinderPattern {
        val weight = confirmations + 1
        return FinderPattern(
            x = (confirmations * x + other.x) / weight,
            y = (confirmations * y + other.y) / weight,
            moduleSize = (confirmations * moduleSize + other.moduleSize) / weight,
            confirmations = weight,
        )
    }
}

/**
 * Ищет искатели — три квадрата по углам символа с профилем **1:1:3:1:1**.
 *
 * Это соотношение и есть весь фокус QR: под каким углом ни сканируй квадрат, проходящий через его
 * центр разрез даёт одну и ту же пропорцию тёмных и светлых полос. Поэтому искать код можно
 * простым построчным проходом, без всякого перебора поворотов.
 */
internal object FinderPatternFinder {

    /** Профиль состоит из пяти чередующихся полос: чёрная-белая-чёрная-белая-чёрная. */
    private const val RUN_COUNT = 5

    /** Суммарная ширина профиля в модулях: 1+1+3+1+1. */
    private const val PROFILE_MODULES = 7

    /**
     * Допуск на ширину полосы, доля модуля. Полмодуля — это компромисс: жёстче отсекает мусор,
     * но теряет коды на грани разрешения, где ширина полосы гуляет из-за округления пикселей.
     */
    private const val TOLERANCE = 0.5f

    /** Шаг между сканируемыми строками. Искатель высотой 7 модулей не проскочит через три строки. */
    private const val ROW_SKIP = 3

    fun find(matrix: BitMatrix): List<FinderPattern> {
        val candidates = mutableListOf<FinderPattern>()
        val runs = IntArray(RUN_COUNT)

        var y = ROW_SKIP
        while (y < matrix.height) {
            runs.fill(0)
            var state = 0

            for (x in 0 until matrix.width) {
                if (matrix[x, y]) {
                    // Тёмный пиксель: если шли по светлой полосе, начинается новая тёмная.
                    if (state % 2 == 1) state++
                    runs[state]++
                } else when {
                    // Уже идём по светлой полосе — просто продолжаем её.
                    state % 2 == 1 -> runs[state]++

                    // Закончилась пятая полоса: профиль собран целиком.
                    state == RUN_COUNT - 1 -> {
                        if (isFinderProfile(runs)) confirm(matrix, runs, x, y, candidates)
                        // Последние три полосы могут оказаться началом следующего искателя,
                        // поэтому сдвигаем окно, а не обнуляем его.
                        shiftProfile(runs)
                        state = RUN_COUNT - 2
                    }

                    // Тёмная полоса закончилась — переходим к следующей светлой.
                    runs[state] > 0 -> {
                        state++
                        runs[state]++
                    }

                    // Иначе это светлый фон до первой тёмной полосы: считать нечего.
                    else -> Unit
                }
            }

            if (state == RUN_COUNT - 1 && isFinderProfile(runs)) {
                confirm(matrix, runs, matrix.width, y, candidates)
            }
            y += ROW_SKIP
        }

        return candidates.sortedByDescending { it.confirmations }
    }

    private fun shiftProfile(runs: IntArray) {
        runs[0] = runs[2]
        runs[1] = runs[3]
        runs[2] = runs[4]
        // Текущий светлый пиксель уже принадлежит четвёртой полосе нового окна.
        runs[3] = 1
        runs[4] = 0
    }

    /** Проверяет пропорцию 1:1:3:1:1 с допуском, пропорциональным найденному размеру модуля. */
    private fun isFinderProfile(runs: IntArray): Boolean {
        var total = 0
        for (run in runs) {
            if (run == 0) return false
            total += run
        }
        if (total < PROFILE_MODULES) return false

        val moduleSize = total.toFloat() / PROFILE_MODULES
        val maxDeviation = moduleSize * TOLERANCE
        return abs(moduleSize - runs[0]) < maxDeviation &&
            abs(moduleSize - runs[1]) < maxDeviation &&
            abs(moduleSize * 3 - runs[2]) < maxDeviation * 3 &&
            abs(moduleSize - runs[3]) < maxDeviation &&
            abs(moduleSize - runs[4]) < maxDeviation
    }

    private fun centerOfProfile(runs: IntArray, end: Int): Float =
        end - runs[4] - runs[3] - runs[2] / 2.0f

    /**
     * Горизонтальная находка ещё ничего не значит: такой же профиль даёт, например, полоса текста.
     * Настоящий искатель обязан дать ту же пропорцию по вертикали через свой центр и по диагонали.
     */
    private fun confirm(
        matrix: BitMatrix,
        runs: IntArray,
        rowEnd: Int,
        y: Int,
        candidates: MutableList<FinderPattern>,
    ) {
        val total = runs.sum()
        val centerX = centerOfProfile(runs, rowEnd)
        val centerY = verticalCenter(matrix, centerX.toInt(), y, total) ?: return
        val refinedX = horizontalCenter(matrix, centerX.toInt(), centerY.toInt(), total) ?: return
        if (!hasDiagonalProfile(matrix, refinedX.toInt(), centerY.toInt())) return

        val found = FinderPattern(refinedX, centerY, total.toFloat() / PROFILE_MODULES)
        val existing = candidates.indexOfFirst { it.isSamePatternAs(found) }
        if (existing >= 0) {
            candidates[existing] = candidates[existing].mergedWith(found)
        } else {
            candidates += found
        }
    }

    private fun FinderPattern.isSamePatternAs(other: FinderPattern): Boolean {
        val distance = abs(x - other.x) + abs(y - other.y)
        val sizeDifference = abs(moduleSize - other.moduleSize)
        return distance <= moduleSize * 2 && sizeDifference <= moduleSize * 0.7f
    }

    private fun verticalCenter(matrix: BitMatrix, x: Int, startY: Int, expectedTotal: Int): Float? =
        centerAlongAxis(
            readModule = { offset -> matrix.getOrFalse(x, startY + offset) },
            bound = matrix.height,
            start = startY,
            expectedTotal = expectedTotal,
        )?.let { startY + it }

    private fun horizontalCenter(matrix: BitMatrix, startX: Int, y: Int, expectedTotal: Int): Float? =
        centerAlongAxis(
            readModule = { offset -> matrix.getOrFalse(startX + offset, y) },
            bound = matrix.width,
            start = startX,
            expectedTotal = expectedTotal,
        )?.let { startX + it }

    /**
     * Разрез через предполагаемый центр в одну сторону и в другую: считает те же пять полос
     * и возвращает смещение настоящего центра относительно точки старта.
     */
    private fun centerAlongAxis(
        readModule: (Int) -> Boolean,
        bound: Int,
        start: Int,
        expectedTotal: Int,
    ): Float? {
        if (!readModule(0)) return null

        var centerRun = 1
        var offset = 1
        while (start + offset < bound && readModule(offset)) {
            centerRun++
            offset++
        }
        val centerEnd = offset
        offset = -1
        while (start + offset >= 0 && readModule(offset)) {
            centerRun++
            offset--
        }
        val centerStart = offset

        val (beforeWhite, beforeBlack) = runsOutward(readModule, bound, start, centerStart, step = -1)
            ?: return null
        val (afterWhite, afterBlack) = runsOutward(readModule, bound, start, centerEnd, step = 1)
            ?: return null

        val runs = intArrayOf(beforeBlack, beforeWhite, centerRun, afterWhite, afterBlack)
        if (!isFinderProfile(runs)) return null

        // Пропорции сошлись, но масштаб должен совпасть с горизонтальным — иначе это другой объект.
        val total = runs.sum()
        if (abs(total - expectedTotal) * 5 >= expectedTotal * 2) return null

        return (centerStart + centerEnd) / 2.0f
    }

    /** Считает белую и следующую за ней чёрную полосу, уходя от центра в направлении [step]. */
    private fun runsOutward(
        readModule: (Int) -> Boolean,
        bound: Int,
        start: Int,
        from: Int,
        step: Int,
    ): Pair<Int, Int>? {
        var offset = from
        var white = 0
        while (start + offset in 0 until bound && !readModule(offset)) {
            white++
            offset += step
        }
        if (white == 0) return null

        var black = 0
        while (start + offset in 0 until bound && readModule(offset)) {
            black++
            offset += step
        }
        if (black == 0) return null

        return white to black
    }

    /**
     * Диагональная проверка отсеивает то, что похоже на искатель по обеим осям, но не является
     * квадратом — например, перекрестье из двух полос.
     */
    private fun hasDiagonalProfile(matrix: BitMatrix, centerX: Int, centerY: Int): Boolean {
        if (!matrix.contains(centerX, centerY)) return false

        var centerRun = 1
        var offset = 1
        while (matrix.getOrFalse(centerX + offset, centerY + offset)) {
            centerRun++
            offset++
        }
        val forwardEnd = offset
        offset = -1
        while (matrix.getOrFalse(centerX + offset, centerY + offset)) {
            centerRun++
            offset--
        }
        val backwardEnd = offset

        val before = diagonalRuns(matrix, centerX, centerY, backwardEnd, step = -1) ?: return false
        val after = diagonalRuns(matrix, centerX, centerY, forwardEnd, step = 1) ?: return false

        return isFinderProfile(
            intArrayOf(before.second, before.first, centerRun, after.first, after.second),
        )
    }

    private fun diagonalRuns(
        matrix: BitMatrix,
        centerX: Int,
        centerY: Int,
        from: Int,
        step: Int,
    ): Pair<Int, Int>? {
        var offset = from
        var white = 0
        while (matrix.contains(centerX + offset, centerY + offset) &&
            !matrix[centerX + offset, centerY + offset]
        ) {
            white++
            offset += step
        }
        if (white == 0) return null

        var black = 0
        while (matrix.contains(centerX + offset, centerY + offset) &&
            matrix[centerX + offset, centerY + offset]
        ) {
            black++
            offset += step
        }
        if (black == 0) return null

        return white to black
    }
}
