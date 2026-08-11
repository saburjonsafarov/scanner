package com.ssh.scanner.geometry

/**
 * Проективное преобразование плоскости (гомография) — то, чем «выпрямляется» снятый под углом код.
 *
 * Аффинного преобразования здесь не хватает: когда телефон наклонён, дальняя сторона кода короче
 * ближней, а параллельные линии сходятся. Такое умеет только проективное преобразование, и ровно
 * поэтому сетка модулей сэмплируется через него, а не через простое масштабирование.
 *
 * Считается в [Double]: ошибка в третьем знаке при 57 модулях по стороне уводит сэмпл на соседний
 * модуль ближе к дальнему краю.
 */
internal class PerspectiveTransform private constructor(
    private val a11: Double,
    private val a21: Double,
    private val a31: Double,
    private val a12: Double,
    private val a22: Double,
    private val a32: Double,
    private val a13: Double,
    private val a23: Double,
    private val a33: Double,
) {

    fun transform(x: Double, y: Double): ScanPoint {
        val denominator = a13 * x + a23 * y + a33
        return ScanPoint(
            x = ((a11 * x + a21 * y + a31) / denominator).toFloat(),
            y = ((a12 * x + a22 * y + a32) / denominator).toFloat(),
        )
    }

    companion object {

        /**
         * Преобразование единичного квадрата в произвольный четырёхугольник.
         * Точки задают образы углов (0,0), (1,0), (1,1), (0,1) — именно в этом порядке.
         */
        fun squareTo(
            x0: Double, y0: Double,
            x1: Double, y1: Double,
            x2: Double, y2: Double,
            x3: Double, y3: Double,
        ): PerspectiveTransform {
            val dx3 = x0 - x1 + x2 - x3
            val dy3 = y0 - y1 + y2 - y3

            // Обе разности нулевые — четырёхугольник оказался параллелограммом, перспективы нет.
            if (dx3 == 0.0 && dy3 == 0.0) {
                return PerspectiveTransform(
                    a11 = x1 - x0, a21 = x2 - x1, a31 = x0,
                    a12 = y1 - y0, a22 = y2 - y1, a32 = y0,
                    a13 = 0.0, a23 = 0.0, a33 = 1.0,
                )
            }

            val dx1 = x1 - x2
            val dx2 = x3 - x2
            val dy1 = y1 - y2
            val dy2 = y3 - y2
            val denominator = dx1 * dy2 - dx2 * dy1
            val a13 = (dx3 * dy2 - dx2 * dy3) / denominator
            val a23 = (dx1 * dy3 - dx3 * dy1) / denominator

            return PerspectiveTransform(
                a11 = x1 - x0 + a13 * x1, a21 = x3 - x0 + a23 * x3, a31 = x0,
                a12 = y1 - y0 + a13 * y1, a22 = y3 - y0 + a23 * y3, a32 = y0,
                a13 = a13, a23 = a23, a33 = 1.0,
            )
        }

        /**
         * Преобразование одного четырёхугольника в другой — через единичный квадрат как посредник.
         *
         * Здесь это «координаты сетки модулей → координаты изображения»: [source] задаётся в модулях
         * (центры искателей и выравнивателя), [target] — их найденные образы на кадре.
         */
        fun quadToQuad(source: List<ScanPoint>, target: List<ScanPoint>): PerspectiveTransform {
            require(source.size == 4 && target.size == 4) {
                "Гомография строится ровно по четырём парам точек"
            }
            val sourceToSquare = squareTo(
                source[0].x.toDouble(), source[0].y.toDouble(),
                source[1].x.toDouble(), source[1].y.toDouble(),
                source[2].x.toDouble(), source[2].y.toDouble(),
                source[3].x.toDouble(), source[3].y.toDouble(),
            ).inverse()
            val squareToTarget = squareTo(
                target[0].x.toDouble(), target[0].y.toDouble(),
                target[1].x.toDouble(), target[1].y.toDouble(),
                target[2].x.toDouble(), target[2].y.toDouble(),
                target[3].x.toDouble(), target[3].y.toDouble(),
            )
            return squareToTarget * sourceToSquare
        }
    }

    /** Обратное преобразование через присоединённую матрицу: общий множитель в проективных
     *  координатах роли не играет, поэтому делить на определитель не нужно. */
    fun inverse(): PerspectiveTransform = PerspectiveTransform(
        a11 = a22 * a33 - a23 * a32,
        a21 = a23 * a31 - a21 * a33,
        a31 = a21 * a32 - a22 * a31,
        a12 = a13 * a32 - a12 * a33,
        a22 = a11 * a33 - a13 * a31,
        a32 = a12 * a31 - a11 * a32,
        a13 = a12 * a23 - a13 * a22,
        a23 = a13 * a21 - a11 * a23,
        a33 = a11 * a22 - a12 * a21,
    )

    operator fun times(other: PerspectiveTransform): PerspectiveTransform = PerspectiveTransform(
        a11 = a11 * other.a11 + a21 * other.a12 + a31 * other.a13,
        a21 = a11 * other.a21 + a21 * other.a22 + a31 * other.a23,
        a31 = a11 * other.a31 + a21 * other.a32 + a31 * other.a33,
        a12 = a12 * other.a11 + a22 * other.a12 + a32 * other.a13,
        a22 = a12 * other.a21 + a22 * other.a22 + a32 * other.a23,
        a32 = a12 * other.a31 + a22 * other.a32 + a32 * other.a33,
        a13 = a13 * other.a11 + a23 * other.a12 + a33 * other.a13,
        a23 = a13 * other.a21 + a23 * other.a22 + a33 * other.a23,
        a33 = a13 * other.a31 + a23 * other.a32 + a33 * other.a33,
    )
}
