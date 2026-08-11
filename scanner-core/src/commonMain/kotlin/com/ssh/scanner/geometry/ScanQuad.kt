package com.ssh.scanner.geometry

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/** Точка в координатах кадра (пиксели исходного изображения, до любых поворотов превью). */
public data class ScanPoint(val x: Float, val y: Float) {
    public fun distanceTo(other: ScanPoint): Float = hypot(x - other.x, y - other.y)
}

/**
 * Четырёхугольник, которым описан найденный объект.
 *
 * Именно он превращается в анимацию: рамка не «прямоугольник вокруг кода», а реальный контур
 * с перспективой, поэтому наклонённый к камере QR обводится наклонённой рамкой.
 *
 * Порядок углов гарантирован: по часовой стрелке начиная с того, который в системе координат кадра
 * оказался верхним левым. Без этой гарантии рамка «переворачивалась» бы между кадрами, когда
 * детектор возвращает те же углы в другом порядке.
 */
public data class ScanQuad(
    val topLeft: ScanPoint,
    val topRight: ScanPoint,
    val bottomRight: ScanPoint,
    val bottomLeft: ScanPoint,
) {
    public val corners: List<ScanPoint> get() = listOf(topLeft, topRight, bottomRight, bottomLeft)

    public val center: ScanPoint
        get() = ScanPoint(
            x = corners.sumOf { it.x.toDouble() }.toFloat() / CORNER_COUNT,
            y = corners.sumOf { it.y.toDouble() }.toFloat() / CORNER_COUNT,
        )

    /** Площадь по формуле шнурования — мера «насколько объект близко». */
    public val area: Float
        get() {
            var sum = 0.0
            for (index in corners.indices) {
                val current = corners[index]
                val next = corners[(index + 1) % CORNER_COUNT]
                sum += current.x.toDouble() * next.y - next.x.toDouble() * current.y
            }
            return (abs(sum) / 2.0).toFloat()
        }

    public companion object {
        internal const val CORNER_COUNT = 4

        /**
         * Приводит четыре точки в канонический порядок: сортировка по углу вокруг центра тяжести,
         * старт с ближайшей к началу координат.
         *
         * Это не косметика. Детектор находит углы в порядке, зависящем от того, как повёрнут код;
         * без нормализации `topLeft` при повороте телефона перескакивал бы на соседний угол,
         * и рамка в анимации делала бы рывок на 90°, хотя объект не двигался.
         */
        public fun ofUnordered(points: List<ScanPoint>): ScanQuad {
            require(points.size == CORNER_COUNT) { "Нужно ровно 4 точки, получено ${points.size}" }

            val centerX = points.sumOf { it.x.toDouble() } / CORNER_COUNT
            val centerY = points.sumOf { it.y.toDouble() } / CORNER_COUNT
            val clockwise = points.sortedBy { atan2(it.y - centerY, it.x - centerX) }

            val startIndex = clockwise.indices.minBy { index ->
                val point = clockwise[index]
                (point.x - centerX) + (point.y - centerY)
            }
            val ordered = List(CORNER_COUNT) { clockwise[(startIndex + it) % CORNER_COUNT] }

            return ScanQuad(
                topLeft = ordered[0],
                topRight = ordered[1],
                bottomRight = ordered[2],
                bottomLeft = ordered[3],
            )
        }
    }
}
