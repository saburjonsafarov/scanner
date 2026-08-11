package com.ssh.scanner.image

/** Превращает кадр в чёрно-белую матрицу. Отдельный интерфейс — чтобы подменять в тестах. */
public fun interface Binarizer {
    public fun binarize(image: GrayImage): BitMatrix
}

/**
 * Локальный адаптивный порог по блокам [blockSize]×[blockSize].
 *
 * Почему не глобальный порог: реальный кадр почти никогда не освещён равномерно — тень от телефона,
 * блик от лампы, градиент от окна. Один порог на весь кадр превращает затенённый угол в сплошное
 * чёрное пятно, и finder-паттерн там уже не найти. Локальный порог смотрит только на свою окрестность.
 *
 * Однородные блоки (внутри большого белого поля или большого чёрного модуля) отдельная проблема:
 * своего контраста у них нет, и порог по их собственным min/max превратил бы шум в мусор. Для них
 * порог берётся у уже посчитанных соседей — сверху и слева.
 */
public class AdaptiveBinarizer(
    private val blockSize: Int = DEFAULT_BLOCK_SIZE,
    private val minDynamicRange: Int = DEFAULT_MIN_DYNAMIC_RANGE,
) : Binarizer {

    override fun binarize(image: GrayImage): BitMatrix {
        if (image.width < blockSize * MIN_BLOCKS || image.height < blockSize * MIN_BLOCKS) {
            return binarizeGlobally(image)
        }
        val blocksX = (image.width + blockSize - 1) / blockSize
        val blocksY = (image.height + blockSize - 1) / blockSize
        val thresholds = computeBlockThresholds(image, blocksX, blocksY)
        return applyThresholds(image, thresholds, blocksX, blocksY)
    }

    private fun computeBlockThresholds(image: GrayImage, blocksX: Int, blocksY: Int): IntArray {
        val thresholds = IntArray(blocksX * blocksY)
        for (blockY in 0 until blocksY) {
            val top = blockY * blockSize
            val bottom = minOf(top + blockSize, image.height)
            for (blockX in 0 until blocksX) {
                val left = blockX * blockSize
                val right = minOf(left + blockSize, image.width)

                var min = 255
                var max = 0
                for (y in top until bottom) {
                    val rowStart = image.rowOffset(y)
                    for (x in left until right) {
                        val luma = image.at(rowStart + x)
                        if (luma < min) min = luma
                        if (luma > max) max = luma
                    }
                }

                thresholds[blockY * blocksX + blockX] = if (max - min > minDynamicRange) {
                    (min + max) / 2
                } else {
                    // Контраста нет. По умолчанию считаем блок светлым фоном: порог max/2 оставит
                    // его целиком белым. Но если он темнее, чем окрестность, — это внутренность
                    // чёрного пятна, и правильный порог тот же, что у соседей.
                    val fallback = max / 2
                    if (blockX > 0 && blockY > 0) {
                        val neighbours = (
                            thresholds[(blockY - 1) * blocksX + blockX] +
                                2 * thresholds[blockY * blocksX + blockX - 1] +
                                thresholds[(blockY - 1) * blocksX + blockX - 1]
                            ) / 4
                        if (min < neighbours) neighbours else fallback
                    } else {
                        fallback
                    }
                }
            }
        }
        return thresholds
    }

    private fun applyThresholds(
        image: GrayImage,
        thresholds: IntArray,
        blocksX: Int,
        blocksY: Int,
    ): BitMatrix {
        val matrix = BitMatrix(image.width, image.height)
        for (blockY in 0 until blocksY) {
            val top = blockY * blockSize
            val bottom = minOf(top + blockSize, image.height)
            for (blockX in 0 until blocksX) {
                val left = blockX * blockSize
                val right = minOf(left + blockSize, image.width)

                // Порог усредняется по окну соседей: иначе на границе блоков появляются ступеньки,
                // и ровная линия штрихкода рвётся ровно по сетке блоков. Окно намеренно узкое —
                // см. SMOOTH_RADIUS.
                val threshold = averageAround(thresholds, blocksX, blocksY, blockX, blockY)
                for (y in top until bottom) {
                    val rowStart = image.rowOffset(y)
                    for (x in left until right) {
                        // Тёмный пиксель = чёрный модуль = true.
                        matrix[x, y] = image.at(rowStart + x) <= threshold
                    }
                }
            }
        }
        return matrix
    }

    private fun averageAround(
        thresholds: IntArray,
        blocksX: Int,
        blocksY: Int,
        blockX: Int,
        blockY: Int,
    ): Int {
        val left = (blockX - SMOOTH_RADIUS).coerceAtLeast(0)
        val right = (blockX + SMOOTH_RADIUS).coerceAtMost(blocksX - 1)
        val top = (blockY - SMOOTH_RADIUS).coerceAtLeast(0)
        val bottom = (blockY + SMOOTH_RADIUS).coerceAtMost(blocksY - 1)
        var sum = 0
        var count = 0
        for (y in top..bottom) {
            for (x in left..right) {
                sum += thresholds[y * blocksX + x]
                count++
            }
        }
        return sum / count
    }

    /**
     * Фолбэк для кадров меньше нескольких блоков: метод Оцу — порог, максимизирующий
     * межклассовую дисперсию. На таком размере локальность уже не нужна, а Оцу не требует настроек.
     */
    private fun binarizeGlobally(image: GrayImage): BitMatrix {
        val histogram = IntArray(HISTOGRAM_SIZE)
        for (y in 0 until image.height) {
            val rowStart = image.rowOffset(y)
            for (x in 0 until image.width) {
                histogram[image.at(rowStart + x)]++
            }
        }
        val threshold = otsuThreshold(histogram, total = image.width * image.height)
        val matrix = BitMatrix(image.width, image.height)
        for (y in 0 until image.height) {
            val rowStart = image.rowOffset(y)
            for (x in 0 until image.width) {
                matrix[x, y] = image.at(rowStart + x) <= threshold
            }
        }
        return matrix
    }

    private fun otsuThreshold(histogram: IntArray, total: Int): Int {
        var sumAll = 0L
        for (level in histogram.indices) sumAll += level.toLong() * histogram[level]

        var sumBackground = 0L
        var countBackground = 0
        var bestVariance = -1.0
        var bestThreshold = HISTOGRAM_SIZE / 2

        for (level in histogram.indices) {
            countBackground += histogram[level]
            if (countBackground == 0) continue
            val countForeground = total - countBackground
            if (countForeground == 0) break

            sumBackground += level.toLong() * histogram[level]
            val meanBackground = sumBackground.toDouble() / countBackground
            val meanForeground = (sumAll - sumBackground).toDouble() / countForeground
            val delta = meanBackground - meanForeground
            val variance = countBackground.toDouble() * countForeground * delta * delta
            if (variance > bestVariance) {
                bestVariance = variance
                bestThreshold = level
            }
        }
        return bestThreshold
    }

    private companion object {
        const val DEFAULT_BLOCK_SIZE = 8

        /**
         * Ниже этой разницы яркостей блок считается однородным. 24 из 255 — эмпирический порог:
         * шум матрицы телефона обычно укладывается в единицы уровней, реальный переход
         * «белая бумага → чёрная краска» даёт десятки.
         */
        const val DEFAULT_MIN_DYNAMIC_RANGE = 24

        /** Минимум блоков по стороне, ниже которого локальный порог теряет смысл. */
        const val MIN_BLOCKS = 5

        /**
         * Радиус окна сглаживания порогов в блоках (окно 3×3 = 24×24 пикселя).
         *
         * Радиус 2 (окно 5×5 = 40×40) выглядит безобиднее, но у него есть острый край: объект,
         * который меньше окна, усредняется вместе с окружающим фоном и теряет свой порог. На тесте
         * с градиентом освещения метка 14 px в светлой зоне так и пропадала — порог съезжал со 160
         * до 115 при яркости метки 130. Окно 3×3 всё ещё убирает ступеньки на границах блоков,
         * но перестаёт растворять мелкие объекты в фоне.
         */
        const val SMOOTH_RADIUS = 1

        const val HISTOGRAM_SIZE = 256
    }
}
