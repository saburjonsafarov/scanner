package com.ssh.scanner.ecc

/**
 * Поле Галуа GF(256) — арифметика, на которой стоит коррекция ошибок в QR.
 *
 * Байт трактуется как многочлен над GF(2), умножение идёт по модулю неприводимого многочлена
 * `x⁸ + x⁴ + x³ + x² + 1`. Такой примитив зафиксирован стандартом QR; менять его нельзя, иначе
 * восстановление испорченных кодовых слов даст мусор, а не ошибку.
 *
 * Умножение сведено к сложению логарифмов: таблица [exponents] удвоена (511 элементов), чтобы
 * `log a + log b` не приходилось брать по модулю в горячем цикле.
 */
internal object GaloisField256 {

    private const val PRIMITIVE = 0x011D
    private const val SIZE = 256
    private const val ORDER = SIZE - 1

    private val exponents = IntArray(ORDER * 2)
    private val logarithms = IntArray(SIZE)

    init {
        var value = 1
        for (power in 0 until ORDER) {
            exponents[power] = value
            logarithms[value] = power
            value = value shl 1
            if (value >= SIZE) value = value xor PRIMITIVE
        }
        for (power in ORDER until ORDER * 2) {
            exponents[power] = exponents[power - ORDER]
        }
    }

    /** Сложение и вычитание в поле характеристики 2 — одна и та же операция. */
    fun add(a: Int, b: Int): Int = a xor b

    fun multiply(a: Int, b: Int): Int =
        if (a == 0 || b == 0) 0 else exponents[logarithms[a] + logarithms[b]]

    fun divide(a: Int, b: Int): Int {
        require(b != 0) { "Деление на ноль в GF(256)" }
        if (a == 0) return 0
        return exponents[(logarithms[a] - logarithms[b] + ORDER) % ORDER]
    }

    fun inverse(a: Int): Int {
        require(a != 0) { "У нуля нет обратного элемента в GF(256)" }
        return exponents[ORDER - logarithms[a]]
    }

    /** α в степени [power]; степень может быть любой, приводится по модулю порядка группы. */
    fun exp(power: Int): Int = exponents[((power % ORDER) + ORDER) % ORDER]

    fun log(a: Int): Int {
        require(a != 0) { "Логарифм нуля в GF(256) не определён" }
        return logarithms[a]
    }
}
