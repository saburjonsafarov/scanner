package com.ssh.scanner.ecc

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GaloisFieldTest {

    @Test
    fun powersOfAlphaFollowThePrimitivePolynomial() {
        // До восьмой степени умножение на α — обычный сдвиг влево.
        assertEquals(1, GaloisField256.exp(0))
        assertEquals(2, GaloisField256.exp(1))
        assertEquals(128, GaloisField256.exp(7))
        // На восьмой степени вступает приведение по x⁸+x⁴+x³+x²+1: 256 xor 0x11D = 0x1D = 29.
        assertEquals(29, GaloisField256.exp(8))
        assertEquals(58, GaloisField256.exp(9))
        // Порядок мультипликативной группы — 255, дальше степени зацикливаются.
        assertEquals(1, GaloisField256.exp(255))
        assertEquals(2, GaloisField256.exp(256))
    }

    @Test
    fun everyNonZeroElementHasInverse() {
        for (value in 1 until 256) {
            assertEquals(1, GaloisField256.multiply(value, GaloisField256.inverse(value)),
                "элемент $value умноженный на обратный должен давать 1")
        }
    }

    @Test
    fun logarithmAndExponentAreMutuallyInverse() {
        for (value in 1 until 256) {
            assertEquals(value, GaloisField256.exp(GaloisField256.log(value)))
        }
    }

    @Test
    fun additionIsItsOwnInverse() {
        // Характеристика 2: сложение и вычитание совпадают, поэтому a+a = 0.
        for (value in 0 until 256) {
            assertEquals(0, GaloisField256.add(value, value))
        }
    }

    @Test
    fun multiplicationIsAssociativeAndDistributive() {
        val a = 0x53
        val b = 0xCA
        val c = 0x1F
        assertEquals(
            GaloisField256.multiply(GaloisField256.multiply(a, b), c),
            GaloisField256.multiply(a, GaloisField256.multiply(b, c)),
        )
        assertEquals(
            GaloisField256.multiply(a, GaloisField256.add(b, c)),
            GaloisField256.add(GaloisField256.multiply(a, b), GaloisField256.multiply(a, c)),
        )
    }

    @Test
    fun divisionUndoesMultiplication() {
        for (a in 1 until 256 step 7) {
            for (b in 1 until 256 step 11) {
                assertEquals(a, GaloisField256.divide(GaloisField256.multiply(a, b), b))
            }
        }
    }

    @Test
    fun rejectsZeroInverseAndZeroDivision() {
        assertFailsWith<IllegalArgumentException> { GaloisField256.inverse(0) }
        assertFailsWith<IllegalArgumentException> { GaloisField256.divide(5, 0) }
        assertFailsWith<IllegalArgumentException> { GaloisField256.log(0) }
    }

    @Test
    fun multiplicationByZeroIsZero() {
        assertTrue((0 until 256).all { GaloisField256.multiply(it, 0) == 0 })
    }
}

class GaloisPolynomialTest {

    @Test
    fun evaluatesByHorner() {
        // x² + 3x + 2 при x = 1 даёт 1 xor 3 xor 2 = 0.
        val poly = GaloisPolynomial.of(intArrayOf(1, 3, 2))
        assertEquals(0, poly.evaluateAt(1))
        // При x = 0 остаётся свободный член.
        assertEquals(2, poly.evaluateAt(0))
    }

    @Test
    fun stripsLeadingZeroes() {
        val poly = GaloisPolynomial.of(intArrayOf(0, 0, 5, 3))
        assertEquals(1, poly.degree)
        assertEquals(5, poly.coefficientAt(1))
        assertEquals(3, poly.coefficientAt(0))
    }

    @Test
    fun additionCancelsEqualTerms() {
        val poly = GaloisPolynomial.of(intArrayOf(1, 3, 2))
        assertTrue((poly + poly).isZero, "многочлен, сложенный сам с собой, обнуляется")
    }

    @Test
    fun multiplicationMatchesManualExpansion() {
        // (x + 1)(x + α) = x² + (1 xor 2)x + 2 = x² + 3x + 2
        val left = GaloisPolynomial.of(intArrayOf(1, 1))
        val right = GaloisPolynomial.of(intArrayOf(1, 2))
        val product = left * right

        assertEquals(2, product.degree)
        assertEquals(1, product.coefficientAt(2))
        assertEquals(3, product.coefficientAt(1))
        assertEquals(2, product.coefficientAt(0))
    }

    @Test
    fun divisionSatisfiesDividendIdentity() {
        val dividend = GaloisPolynomial.of(intArrayOf(7, 3, 9, 1, 4))
        val divisor = GaloisPolynomial.of(intArrayOf(1, 2, 5))

        val division = dividend.divide(divisor)

        assertTrue(division.remainder.degree < divisor.degree)
        // делимое = частное·делитель + остаток (в GF(2ⁿ) минус это плюс)
        assertTrue((division.quotient * divisor + division.remainder + dividend).isZero)
    }

    @Test
    fun monomialShiftsCoefficients() {
        val poly = GaloisPolynomial.of(intArrayOf(1, 1))
        val shifted = poly.timesMonomial(power = 3, coefficient = 2)

        assertEquals(4, shifted.degree)
        assertEquals(2, shifted.coefficientAt(4))
        assertEquals(2, shifted.coefficientAt(3))
        assertEquals(0, shifted.coefficientAt(2))
    }
}
