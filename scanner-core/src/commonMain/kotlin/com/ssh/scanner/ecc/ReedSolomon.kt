package com.ssh.scanner.ecc

/**
 * Кодер Рида — Соломона над GF(256).
 *
 * Нужен ровно для одного: генерировать эталонные символы в тестах. В рантайме сканера кодирование
 * не используется — но без него нечем проверить декодер на данных, где ответ известен заранее.
 */
internal object ReedSolomonEncoder {

    private val generatorCache = mutableMapOf<Int, GaloisPolynomial>()

    /** Порождающий многочлен `Π(x − α^i)`, i = 0 … [ecLength]−1. База степени 0 — как в QR. */
    fun generator(ecLength: Int): GaloisPolynomial = generatorCache.getOrPut(ecLength) {
        var result = GaloisPolynomial.ONE
        for (power in 0 until ecLength) {
            result *= GaloisPolynomial.of(intArrayOf(1, GaloisField256.exp(power)))
        }
        result
    }

    /** Возвращает ровно [ecLength] корректирующих слов для блока [data]. */
    fun encode(data: IntArray, ecLength: Int): IntArray {
        require(ecLength > 0) { "Длина корректирующей части должна быть положительной" }
        require(data.isNotEmpty()) { "Пустой блок данных" }

        val shifted = GaloisPolynomial.of(data).timesMonomial(ecLength, 1)
        val remainder = shifted.divide(generator(ecLength)).remainder

        // Остаток может оказаться короче ecLength — недостающие старшие слова нулевые.
        val result = IntArray(ecLength)
        val coefficients = remainder.coefficients
        val offset = ecLength - coefficients.size
        for (index in coefficients.indices) {
            result[offset + index] = coefficients[index]
        }
        return result
    }
}

/**
 * Декодер Рида — Соломона: восстанавливает кодовые слова, испорченные бликом, тенью или пальцем
 * на углу кода. Это то, за счёт чего QR читается, будучи частично закрытым.
 *
 * Ключевое уравнение решается расширенным алгоритмом Евклида; позиции ошибок ищутся перебором
 * Ченя, величины — формулой Форни.
 */
internal object ReedSolomonDecoder {

    /**
     * Правит [received] на месте (данные и корректирующая часть одним массивом, старшее слово первым).
     *
     * @return `true`, если ошибок не было или все они исправлены; `false`, если повреждений больше,
     *   чем позволяет уровень коррекции — тогда содержимое [received] считать невалидным.
     */
    fun decode(received: IntArray, ecLength: Int): Boolean {
        require(ecLength > 0) { "Длина корректирующей части должна быть положительной" }
        if (received.size > FIELD_ORDER) return false

        val poly = GaloisPolynomial.of(received)
        val syndromes = IntArray(ecLength) { poly.evaluateAt(GaloisField256.exp(it)) }
        if (syndromes.all { it == 0 }) return true

        val syndromePoly = GaloisPolynomial.of(syndromes.reversedArray())
        val keyEquation = solveKeyEquation(
            GaloisPolynomial.monomial(ecLength, 1),
            syndromePoly,
            ecLength / 2,
        ) ?: return false

        val errorLocations = findErrorLocations(keyEquation.errorLocator) ?: return false
        val errorMagnitudes = findErrorMagnitudes(keyEquation.errorEvaluator, errorLocations)

        for (index in errorLocations.indices) {
            val position = received.size - 1 - GaloisField256.log(errorLocations[index])
            if (position !in received.indices) return false
            received[position] = GaloisField256.add(received[position], errorMagnitudes[index])
        }
        return true
    }

    private class KeyEquation(
        val errorLocator: GaloisPolynomial,
        val errorEvaluator: GaloisPolynomial,
    )

    /**
     * Расширенный алгоритм Евклида для `Λ(x)·S(x) ≡ Ω(x) mod x^ec`.
     * Останавливается, когда степень остатка опускается ниже половины корректирующей части —
     * дальше решение уже не единственно.
     */
    private fun solveKeyEquation(
        a: GaloisPolynomial,
        b: GaloisPolynomial,
        stopDegree: Int,
    ): KeyEquation? {
        var remainderPrevious = a
        var remainder = b
        var locatorPrevious = GaloisPolynomial.ZERO
        var locator = GaloisPolynomial.ONE

        while (remainder.degree >= stopDegree) {
            val remainderBeforeLast = remainderPrevious
            val locatorBeforeLast = locatorPrevious
            remainderPrevious = remainder
            locatorPrevious = locator

            if (remainderPrevious.isZero) return null

            val division = remainderBeforeLast.divide(remainderPrevious)
            remainder = division.remainder
            locator = division.quotient * locatorPrevious + locatorBeforeLast

            if (remainder.degree >= remainderPrevious.degree) return null
        }

        val constantTerm = locator.coefficientAt(0)
        if (constantTerm == 0) return null
        val scale = GaloisField256.inverse(constantTerm)
        return KeyEquation(
            errorLocator = locator.timesScalar(scale),
            errorEvaluator = remainder.timesScalar(scale),
        )
    }

    /** Перебор Ченя: корни локатора дают позиции повреждённых слов. */
    private fun findErrorLocations(errorLocator: GaloisPolynomial): IntArray? {
        val errorCount = errorLocator.degree
        if (errorCount == 0) return null

        val result = IntArray(errorCount)
        var found = 0
        var candidate = 1
        while (candidate < FIELD_SIZE && found < errorCount) {
            if (errorLocator.evaluateAt(candidate) == 0) {
                result[found] = GaloisField256.inverse(candidate)
                found++
            }
            candidate++
        }
        // Корней меньше степени — значит повреждений больше, чем код в состоянии исправить.
        return if (found == errorCount) result else null
    }

    /** Формула Форни: величина ошибки в каждой найденной позиции. */
    private fun findErrorMagnitudes(
        errorEvaluator: GaloisPolynomial,
        errorLocations: IntArray,
    ): IntArray {
        val result = IntArray(errorLocations.size)
        for (i in errorLocations.indices) {
            val inverseLocation = GaloisField256.inverse(errorLocations[i])
            var denominator = 1
            for (j in errorLocations.indices) {
                if (i == j) continue
                val term = GaloisField256.multiply(errorLocations[j], inverseLocation)
                denominator = GaloisField256.multiply(denominator, GaloisField256.add(1, term))
            }
            result[i] = GaloisField256.multiply(
                errorEvaluator.evaluateAt(inverseLocation),
                GaloisField256.inverse(denominator),
            )
        }
        return result
    }

    private const val FIELD_SIZE = 256
    private const val FIELD_ORDER = FIELD_SIZE - 1
}
