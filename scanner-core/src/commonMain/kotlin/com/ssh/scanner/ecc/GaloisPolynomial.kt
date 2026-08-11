package com.ssh.scanner.ecc

/**
 * Многочлен над GF(256).
 *
 * Коэффициенты хранятся **старшим вперёд**: `[1, 0, 3]` это `x² + 3`. Такой порядок совпадает
 * с порядком кодовых слов в блоке QR, поэтому массив кодовых слов ложится в многочлен как есть,
 * без разворота.
 */
internal class GaloisPolynomial private constructor(val coefficients: IntArray) {

    val degree: Int get() = coefficients.size - 1

    val isZero: Boolean get() = coefficients[0] == 0

    /** Коэффициент при `x^[power]`. */
    fun coefficientAt(power: Int): Int = coefficients[coefficients.size - 1 - power]

    fun evaluateAt(x: Int): Int {
        if (x == 0) return coefficientAt(0)
        var result = coefficients[0]
        for (index in 1 until coefficients.size) {
            result = GaloisField256.add(GaloisField256.multiply(x, result), coefficients[index])
        }
        return result
    }

    /** Сложение оно же вычитание: характеристика поля 2. */
    operator fun plus(other: GaloisPolynomial): GaloisPolynomial {
        if (isZero) return other
        if (other.isZero) return this

        val (shorter, longer) = if (coefficients.size < other.coefficients.size) {
            coefficients to other.coefficients
        } else {
            other.coefficients to coefficients
        }
        val result = longer.copyOf()
        val offset = longer.size - shorter.size
        for (index in shorter.indices) {
            result[offset + index] = GaloisField256.add(longer[offset + index], shorter[index])
        }
        return of(result)
    }

    operator fun times(other: GaloisPolynomial): GaloisPolynomial {
        if (isZero || other.isZero) return ZERO
        val result = IntArray(coefficients.size + other.coefficients.size - 1)
        for (i in coefficients.indices) {
            val left = coefficients[i]
            if (left == 0) continue
            for (j in other.coefficients.indices) {
                result[i + j] = GaloisField256.add(
                    result[i + j],
                    GaloisField256.multiply(left, other.coefficients[j]),
                )
            }
        }
        return of(result)
    }

    fun timesScalar(scalar: Int): GaloisPolynomial {
        if (scalar == 0) return ZERO
        if (scalar == 1) return this
        return of(IntArray(coefficients.size) { GaloisField256.multiply(coefficients[it], scalar) })
    }

    /** Умножение на `coefficient · x^power` — сдвиг с масштабированием, без общего умножения. */
    fun timesMonomial(power: Int, coefficient: Int): GaloisPolynomial {
        require(power >= 0) { "Степень одночлена не может быть отрицательной: $power" }
        if (coefficient == 0) return ZERO
        val result = IntArray(coefficients.size + power)
        for (index in coefficients.indices) {
            result[index] = GaloisField256.multiply(coefficients[index], coefficient)
        }
        return of(result)
    }

    /** Деление с остатком. Остаток — это и есть корректирующие кодовые слова при кодировании. */
    fun divide(other: GaloisPolynomial): Division {
        require(!other.isZero) { "Деление многочлена на ноль" }

        var quotient = ZERO
        var remainder = this
        val leadingInverse = GaloisField256.inverse(other.coefficients[0])

        while (remainder.degree >= other.degree && !remainder.isZero) {
            val degreeDifference = remainder.degree - other.degree
            val scale = GaloisField256.multiply(remainder.coefficients[0], leadingInverse)
            quotient += monomial(degreeDifference, scale)
            remainder += other.timesMonomial(degreeDifference, scale)
        }
        return Division(quotient = quotient, remainder = remainder)
    }

    data class Division(val quotient: GaloisPolynomial, val remainder: GaloisPolynomial)

    companion object {
        val ZERO: GaloisPolynomial = GaloisPolynomial(intArrayOf(0))
        val ONE: GaloisPolynomial = GaloisPolynomial(intArrayOf(1))

        /** Отбрасывает ведущие нули: степень многочлена должна быть настоящей. */
        fun of(coefficients: IntArray): GaloisPolynomial {
            require(coefficients.isNotEmpty()) { "Пустой массив коэффициентов" }
            var start = 0
            while (start < coefficients.size - 1 && coefficients[start] == 0) start++
            return GaloisPolynomial(
                if (start == 0) coefficients else coefficients.copyOfRange(start, coefficients.size),
            )
        }

        fun monomial(power: Int, coefficient: Int): GaloisPolynomial {
            require(power >= 0) { "Степень одночлена не может быть отрицательной: $power" }
            if (coefficient == 0) return ZERO
            return GaloisPolynomial(IntArray(power + 1).also { it[0] = coefficient })
        }
    }
}
