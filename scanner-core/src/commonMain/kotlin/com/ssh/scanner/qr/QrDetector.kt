package com.ssh.scanner.qr

import com.ssh.scanner.geometry.PerspectiveTransform
import com.ssh.scanner.geometry.ScanPoint
import com.ssh.scanner.geometry.ScanQuad
import com.ssh.scanner.image.BitMatrix
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/** Найденный на кадре символ: что записано и где именно он лежит. */
internal data class DetectedQr(
    val text: String,
    val quad: ScanQuad,
    val version: QrVersion,
    val errorCorrectionLevel: ErrorCorrectionLevel,
)

/**
 * Находит QR-символы на бинаризованном кадре и декодирует их.
 *
 * Порядок ровно такой: искатели → ориентация → размер сетки → выравниватель → гомография →
 * сэмплирование → декодирование. Каждый шаг может отказать, и тогда кадр просто пропускается:
 * сканер живёт в потоке кадров, где дешевле не прочитать этот кадр, чем ошибиться.
 */
internal object QrDetector {

    /** Больше кандидатов перебирать смысла нет: тройки растут кубически, а лишние — это шум. */
    private const val MAX_CANDIDATES = 8

    /** Максимальная терпимая разница в размере модуля между искателями одного символа. */
    private const val MODULE_SIZE_TOLERANCE = 0.5f

    fun detect(matrix: BitMatrix): List<DetectedQr> {
        val candidates = FinderPatternFinder.find(matrix).take(MAX_CANDIDATES)
        if (candidates.size < 3) return emptyList()

        val results = mutableListOf<DetectedQr>()
        val used = mutableSetOf<FinderPattern>()

        for (triple in triples(candidates)) {
            if (triple.any { it in used }) continue
            val detected = decodeTriple(matrix, triple) ?: continue
            results += detected
            used += triple
        }
        return results
    }

    private fun triples(candidates: List<FinderPattern>): List<List<FinderPattern>> {
        val result = mutableListOf<List<FinderPattern>>()
        for (i in candidates.indices) {
            for (j in i + 1 until candidates.size) {
                for (k in j + 1 until candidates.size) {
                    val triple = listOf(candidates[i], candidates[j], candidates[k])
                    if (haveComparableModuleSize(triple)) result += triple
                }
            }
        }
        return result
    }

    private fun haveComparableModuleSize(triple: List<FinderPattern>): Boolean {
        val average = triple.sumOf { it.moduleSize.toDouble() }.toFloat() / triple.size
        return triple.all { abs(it.moduleSize - average) <= average * MODULE_SIZE_TOLERANCE }
    }

    private fun decodeTriple(matrix: BitMatrix, triple: List<FinderPattern>): DetectedQr? {
        val corners = orientPatterns(triple) ?: return null
        val moduleSize = measureModuleSize(matrix, corners)
        if (moduleSize.isNaN() || moduleSize < 1f) return null
        val dimension = estimateDimension(corners, moduleSize) ?: return null
        val version = QrVersion.ofSize(dimension) ?: return null

        // Профиль выравнивателя — тёмное-светлое-тёмное по модулю на полосу — в поле данных
        // встречается сплошь и рядом, поэтому «ближайший к ожидаемому месту» кандидат нередко
        // оказывается случайным сочетанием модулей. Единственная надёжная проверка кандидата —
        // построить по нему сетку и попробовать прочитать символ, поэтому перебираем несколько,
        // а последней гипотезой идёт экстраполяция без выравнивателя вовсе.
        val anchors: List<ScanPoint?> = if (version.number > 1) {
            findAlignmentCandidates(matrix, corners, dimension, moduleSize)
                .take(MAX_ALIGNMENT_CANDIDATES) + listOf(null)
        } else {
            listOf(null)
        }

        for (anchor in anchors) {
            val transform = buildTransform(corners, anchor, dimension)
            val sampled = sampleGrid(matrix, transform, dimension) ?: continue
            val symbol = QrMatrixDecoder.decode(sampled) ?: continue

            return DetectedQr(
                text = symbol.text,
                quad = symbolQuad(transform, dimension),
                version = symbol.version,
                errorCorrectionLevel = symbol.errorCorrectionLevel,
            )
        }
        return null
    }

    private class OrientedCorners(
        val topLeft: FinderPattern,
        val topRight: FinderPattern,
        val bottomLeft: FinderPattern,
    )

    /**
     * Меряет размер модуля вдоль линий между искателями, а не по профилю строки сканирования.
     *
     * Разница принципиальная. Профиль строки пересекает повёрнутый искатель наискось, поэтому его
     * длина растёт с поворотом: при 45° замер давал 7.3 пикселя вместо 6, размер сетки уезжал
     * на два модуля, и символ отбраковывался как «невозможный размер». Линия же между центрами
     * искателей идёт **вдоль края символа** при любом повороте — на ней профиль
     * тёмное-светлое-тёмное всегда равен 7 модулям искателя.
     */
    private fun measureModuleSize(matrix: BitMatrix, corners: OrientedCorners): Float {
        val alongTop = moduleSizeBetween(matrix, corners.topLeft, corners.topRight)
        val alongLeft = moduleSizeBetween(matrix, corners.topLeft, corners.bottomLeft)
        return when {
            alongTop.isNaN() -> alongLeft
            alongLeft.isNaN() -> alongTop
            else -> (alongTop + alongLeft) / 2f
        }
    }

    private fun moduleSizeBetween(matrix: BitMatrix, a: FinderPattern, b: FinderPattern): Float {
        val fromA = runBothWays(matrix, a, b)
        val fromB = runBothWays(matrix, b, a)
        return when {
            fromA.isNaN() && fromB.isNaN() -> Float.NaN
            fromA.isNaN() -> fromB / (PROFILE_MODULES * 1f)
            fromB.isNaN() -> fromA / (PROFILE_MODULES * 1f)
            else -> (fromA + fromB) / (PROFILE_MODULES * 2f)
        }
    }

    /** Профиль искателя целиком: половина от центра к соседу плюс половина в обратную сторону. */
    private fun runBothWays(matrix: BitMatrix, from: FinderPattern, towards: FinderPattern): Float {
        val forward = blackWhiteBlackRun(matrix, from.x, from.y, towards.x, towards.y)
        val backward = blackWhiteBlackRun(
            matrix,
            from.x,
            from.y,
            from.x - (towards.x - from.x),
            from.y - (towards.y - from.y),
        )
        if (forward.isNaN() || backward.isNaN()) return Float.NaN
        // Центральный пиксель посчитан обоими лучами — вычитаем его.
        return forward + backward - 1f
    }

    /**
     * Длина отрезка от центра искателя до конца профиля тёмное-светлое-тёмное в заданную сторону.
     * Идём по Брезенхэму, чтобы шаг был одинаковым при любом наклоне линии.
     */
    private fun blackWhiteBlackRun(
        matrix: BitMatrix,
        fromXf: Float,
        fromYf: Float,
        toXf: Float,
        toYf: Float,
    ): Float {
        var fromX = fromXf.roundToInt()
        var fromY = fromYf.roundToInt()
        var toX = toXf.roundToInt()
        var toY = toYf.roundToInt()

        val steep = abs(toY - fromY) > abs(toX - fromX)
        if (steep) {
            var swap = fromX; fromX = fromY; fromY = swap
            swap = toX; toX = toY; toY = swap
        }

        val dx = abs(toX - fromX)
        val dy = abs(toY - fromY)
        var error = -dx / 2
        val stepX = if (fromX < toX) 1 else -1
        val stepY = if (fromY < toY) 1 else -1

        // 0 — идём по тёмному ядру, 1 — по светлому кольцу, 2 — по внешнему тёмному кольцу.
        var state = 0
        var x = fromX
        var y = fromY
        val limitX = toX + stepX

        while (x != limitX) {
            val realX = if (steep) y else x
            val realY = if (steep) x else y

            if ((state == 1) == matrix.getOrFalse(realX, realY)) {
                if (state == 2) return hypot((x - fromX).toFloat(), (y - fromY).toFloat())
                state++
            }

            error += dy
            if (error > 0) {
                if (y == toY) break
                y += stepY
                error -= dx
            }
            x += stepX
        }

        // Профиль не успел закрыться внутри кадра — засчитываем, если дошли до внешнего кольца.
        if (state == 2) {
            return hypot((limitX - fromX).toFloat(), (toY - fromY).toFloat())
        }
        return Float.NaN
    }

    /**
     * Раскладывает три искателя по местам.
     *
     * Верхний левый — тот, что напротив самой длинной стороны: три искателя образуют прямоугольный
     * треугольник, и напротив гипотенузы лежит прямой угол. Кто из двух оставшихся правый, а кто
     * нижний, решает знак векторного произведения — он же говорит, не смотрим ли мы на код в зеркале.
     */
    private fun orientPatterns(triple: List<FinderPattern>): OrientedCorners? {
        val (a, b, c) = triple
        val distanceAB = distance(a, b)
        val distanceBC = distance(b, c)
        val distanceAC = distance(a, c)

        val (corner, first, second) = when (maxOf(distanceAB, distanceBC, distanceAC)) {
            distanceBC -> Triple(a, b, c)
            distanceAC -> Triple(b, a, c)
            else -> Triple(c, a, b)
        }

        val cross = (first.x - corner.x) * (second.y - corner.y) -
            (first.y - corner.y) * (second.x - corner.x)
        if (cross == 0f) return null

        return if (cross > 0) {
            OrientedCorners(topLeft = corner, topRight = first, bottomLeft = second)
        } else {
            OrientedCorners(topLeft = corner, topRight = second, bottomLeft = first)
        }
    }

    /**
     * Оценивает сторону символа в модулях по расстояниям между центрами искателей.
     *
     * Между центрами верхнего левого и верхнего правого ровно `dimension − 7` модулей, поэтому
     * размер восстанавливается делением на размер модуля. Результат обязан быть сравним с 1 по
     * модулю 4 — все допустимые размеры символа такие; отклонение на единицу списываем на
     * погрешность и правим, отклонение на два означает, что тройка искателей ложная.
     */
    private fun estimateDimension(corners: OrientedCorners, moduleSize: Float): Int? {
        if (moduleSize <= 0f) return null

        val horizontal = distance(corners.topLeft, corners.topRight) / moduleSize
        val vertical = distance(corners.topLeft, corners.bottomLeft) / moduleSize
        var dimension = ((horizontal + vertical) / 2f).roundToInt() + 7

        return when (dimension % 4) {
            1 -> dimension
            0 -> dimension + 1
            2 -> dimension - 1
            else -> null
        }
    }

    /**
     * Ищет выравниватель рядом с ожидаемым местом — маленький квадрат 5×5 у нижнего правого угла.
     *
     * Без него дальний угол приходится экстраполировать как параллелограмм, а при наклоне камеры
     * он «уезжает»: чем сильнее перспектива, тем больше сетка расходится с реальными модулями.
     */
    private fun findAlignmentCandidates(
        matrix: BitMatrix,
        corners: OrientedCorners,
        dimension: Int,
        moduleSize: Float,
    ): List<ScanPoint> {
        val estimate = extrapolatedAlignmentCenter(corners, dimension)
        val radius = (moduleSize * ALIGNMENT_SEARCH_MODULES).roundToInt().coerceAtLeast(2)

        val left = (estimate.x.roundToInt() - radius).coerceAtLeast(0)
        val right = (estimate.x.roundToInt() + radius).coerceAtMost(matrix.width - 1)
        val top = (estimate.y.roundToInt() - radius).coerceAtLeast(0)
        val bottom = (estimate.y.roundToInt() + radius).coerceAtMost(matrix.height - 1)
        if (left >= right || top >= bottom) return emptyList()

        val found = mutableListOf<ScanPoint>()
        val scanWidth = right - left + 1

        for (y in top..bottom) {
            for (candidateX in centersOfTripleProfile(scanWidth, moduleSize) { matrix[left + it, y] }) {
                val centerX = left + candidateX
                if (!hasVerticalTripleProfile(matrix, centerX.roundToInt(), y, moduleSize)) continue
                found += ScanPoint(centerX, y.toFloat())
            }
        }

        // Один и тот же выравниватель находится на нескольких соседних строках — усредняем их
        // в один центр, иначе перебор кандидатов уйдёт на повторы одного и того же места.
        return clusterCenters(found, moduleSize).sortedBy { it.distanceTo(estimate) }
    }

    private fun clusterCenters(points: List<ScanPoint>, moduleSize: Float): List<ScanPoint> {
        val clusters = mutableListOf<MutableList<ScanPoint>>()
        for (point in points) {
            val existing = clusters.firstOrNull { cluster ->
                cluster.any { it.distanceTo(point) <= moduleSize * 1.5f }
            }
            if (existing != null) existing += point else clusters += mutableListOf(point)
        }
        return clusters.map { cluster ->
            ScanPoint(
                x = cluster.sumOf { it.x.toDouble() }.toFloat() / cluster.size,
                y = cluster.sumOf { it.y.toDouble() }.toFloat() / cluster.size,
            )
        }
    }

    /**
     * Ищет профиль **тёмное-светлое-тёмное** с полосами по одному модулю — разрез выравнивателя
     * через центр (рамка 5×5, светлое кольцо, тёмное ядро в один модуль).
     *
     * Проверять только «одиночный тёмный модуль» недостаточно: в окне поиска таких модулей данных
     * десятки, и ближайший к оценке почти наверняка окажется не выравнивателем. Именно на этом
     * рассыпались версии 7 и 10 при перспективе — сетка строилась по случайному модулю.
     */
    private fun centersOfTripleProfile(
        length: Int,
        moduleSize: Float,
        isDark: (Int) -> Boolean,
    ): List<Float> {
        val runs = collectRuns(length, isDark)
        val result = mutableListOf<Float>()
        val tolerance = moduleSize * ALIGNMENT_TOLERANCE

        for (index in 1 until runs.size - 1) {
            val core = runs[index]
            if (!core.dark) continue
            val before = runs[index - 1]
            val after = runs[index + 1]
            if (before.dark || after.dark) continue

            // Ищем именно «светлое кольцо — тёмное ядро — светлое кольцо».
            //
            // Напрашивающийся вариант «внешняя рамка — кольцо — ядро» ненадёжен: рамка снаружи
            // граничит с полем данных и сливается с любым примыкающим тёмным модулем, из-за чего
            // её ширина перестаёт быть модулем. На версии 10 с наклоном настоящий выравниватель
            // так и не находился вовсе. Кольцо же с обеих сторон ядра — часть самого паттерна,
            // и его ширина от окружения не зависит.
            if (abs(before.length - moduleSize) > tolerance) continue
            if (abs(core.length - moduleSize) > tolerance) continue
            if (abs(after.length - moduleSize) > tolerance) continue

            result += core.start + core.length / 2f
        }
        return result
    }

    private class Run(val dark: Boolean, val start: Int, val length: Int)

    private fun collectRuns(length: Int, isDark: (Int) -> Boolean): List<Run> {
        val runs = mutableListOf<Run>()
        var index = 0
        while (index < length) {
            val dark = isDark(index)
            var runLength = 0
            while (index + runLength < length && isDark(index + runLength) == dark) runLength++
            runs += Run(dark, index, runLength)
            index += runLength
        }
        return runs
    }

    /** Тот же профиль по вертикали через найденный центр — выравниватель обязан быть квадратным. */
    private fun hasVerticalTripleProfile(
        matrix: BitMatrix,
        x: Int,
        y: Int,
        moduleSize: Float,
    ): Boolean {
        if (!matrix.contains(x, y) || !matrix[x, y]) return false

        val radius = (moduleSize * ALIGNMENT_SEARCH_MODULES).roundToInt().coerceAtLeast(3)
        val top = (y - radius).coerceAtLeast(0)
        val bottom = (y + radius).coerceAtMost(matrix.height - 1)
        if (top >= bottom) return false

        val centers = centersOfTripleProfile(bottom - top + 1, moduleSize) { matrix[x, top + it] }
        return centers.any { abs(top + it - y) <= moduleSize }
    }

    /** Центр выравнивателя лежит на три модуля ближе к центру символа, чем угол сетки. */
    private fun extrapolatedAlignmentCenter(corners: OrientedCorners, dimension: Int): ScanPoint {
        val bottomRightX = corners.topRight.x - corners.topLeft.x + corners.bottomLeft.x
        val bottomRightY = corners.topRight.y - corners.topLeft.y + corners.bottomLeft.y
        val correction = 1f - 3f / (dimension - 7f)
        return ScanPoint(
            x = corners.topLeft.x + correction * (bottomRightX - corners.topLeft.x),
            y = corners.topLeft.y + correction * (bottomRightY - corners.topLeft.y),
        )
    }

    private fun buildTransform(
        corners: OrientedCorners,
        alignment: ScanPoint?,
        dimension: Int,
    ): PerspectiveTransform {
        val edge = dimension - 3.5f
        val bottomRightGrid = if (alignment != null) {
            ScanPoint(dimension - 6.5f, dimension - 6.5f)
        } else {
            ScanPoint(edge, edge)
        }
        val bottomRightImage = alignment ?: ScanPoint(
            x = corners.topRight.x - corners.topLeft.x + corners.bottomLeft.x,
            y = corners.topRight.y - corners.topLeft.y + corners.bottomLeft.y,
        )

        return PerspectiveTransform.quadToQuad(
            source = listOf(
                ScanPoint(3.5f, 3.5f),
                ScanPoint(edge, 3.5f),
                bottomRightGrid,
                ScanPoint(3.5f, edge),
            ),
            target = listOf(
                ScanPoint(corners.topLeft.x, corners.topLeft.y),
                ScanPoint(corners.topRight.x, corners.topRight.y),
                bottomRightImage,
                ScanPoint(corners.bottomLeft.x, corners.bottomLeft.y),
            ),
        )
    }

    /** Читает сетку модулей: центр каждого модуля — точка (col+0.5, row+0.5) в координатах сетки. */
    private fun sampleGrid(
        matrix: BitMatrix,
        transform: PerspectiveTransform,
        dimension: Int,
    ): BitMatrix? {
        val sampled = BitMatrix(dimension, dimension)
        for (row in 0 until dimension) {
            for (column in 0 until dimension) {
                val point = transform.transform(column + 0.5, row + 0.5)
                val x = point.x.roundToInt()
                val y = point.y.roundToInt()
                // Сетка вылезла за кадр — символ обрезан краем, читать его нельзя.
                if (!matrix.contains(x, y)) return null
                sampled[column, row] = matrix[x, y]
            }
        }
        return sampled
    }

    /** Внешний контур символа в координатах кадра — то, во что врапится рамка в интерфейсе. */
    private fun symbolQuad(transform: PerspectiveTransform, dimension: Int): ScanQuad {
        val size = dimension.toDouble()
        return ScanQuad(
            topLeft = transform.transform(0.0, 0.0),
            topRight = transform.transform(size, 0.0),
            bottomRight = transform.transform(size, size),
            bottomLeft = transform.transform(0.0, size),
        )
    }

    private fun distance(first: FinderPattern, second: FinderPattern): Float =
        hypot(first.x - second.x, first.y - second.y)

    /**
     * Радиус окна поиска выравнивателя в модулях.
     *
     * Пять модулей не хватает: ожидаемое место считается экстраполяцией параллелограмма, а при
     * перспективе дальний угол уезжает от параллелограмма тем сильнее, чем крупнее символ. Замер на
     * версии 7 с наклоном 6 %: ошибка оценки 5.3 модуля — ровно за границей прежнего окна.
     */
    private const val ALIGNMENT_SEARCH_MODULES = 9f

    /**
     * Сколько кандидатов в выравниватели пробовать, прежде чем отступить к экстраполяции.
     * Проверка кандидата — это построение сетки и попытка декодирования, то есть заметная работа;
     * четырёх хватает, чтобы настоящий выравниватель попал в перебор даже в плотном поле данных.
     */
    private const val MAX_ALIGNMENT_CANDIDATES = 12
    private const val ALIGNMENT_TOLERANCE = 0.6f

    /** Ширина профиля искателя в модулях: 1+1+3+1+1. */
    private const val PROFILE_MODULES = 7
}
