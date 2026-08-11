package com.ssh.scanner.qr

/**
 * Служебные поля символа — информация о формате и о версии — защищены кодами БЧХ.
 *
 * Их значения не хранятся таблицей, а считаются: таблица из 32 и 34 констант, переписанная
 * по памяти, ничем не проверяется, а порождающие многочлены проверяются одним известным значением
 * (нулевой формат обязан дать `0x5412`).
 */
internal object BchCodes {

    /** Порождающий многочлен информации о формате: x¹⁰+x⁸+x⁵+x⁴+x²+x+1. */
    private const val FORMAT_GENERATOR = 0x537

    /**
     * Маска информации о формате. Без неё формат «уровень M, маска 0» был бы нулевым — сплошное
     * белое поле, неотличимое от пустого места, поэтому стандарт всегда подмешивает эту константу.
     */
    private const val FORMAT_MASK = 0x5412

    private const val FORMAT_DATA_BITS = 5
    private const val FORMAT_CHECK_BITS = 10

    /** Порождающий многочлен информации о версии: x¹²+x¹¹+x¹⁰+x⁹+x⁸+x⁵+x²+1. */
    private const val VERSION_GENERATOR = 0x1F25

    private const val VERSION_DATA_BITS = 6
    private const val VERSION_CHECK_BITS = 12

    /** Сколько ошибочных битов служебного поля мы готовы простить. */
    private const val MAX_CORRECTABLE_DISTANCE = 3

    fun encodeFormat(level: ErrorCorrectionLevel, maskPattern: Int): Int {
        require(maskPattern in 0..7) { "Маска QR должна быть 0…7, получено $maskPattern" }
        val data = (level.formatBits shl 3) or maskPattern
        val payload = data shl FORMAT_CHECK_BITS
        return (payload or remainder(payload, FORMAT_GENERATOR)) xor FORMAT_MASK
    }

    /**
     * Разбирает 15 бит формата, выбирая ближайший по Хэммингу из 32 допустимых значений.
     * Символ несёт формат дважды — вызывающий код пробует обе копии.
     */
    fun decodeFormat(bits: Int): FormatInfo? {
        var best: FormatInfo? = null
        var bestDistance = Int.MAX_VALUE

        for (level in ErrorCorrectionLevel.entries) {
            for (maskPattern in 0..7) {
                val candidate = encodeFormat(level, maskPattern)
                if (candidate == bits) return FormatInfo(level, maskPattern)
                val distance = (candidate xor bits).countOneBits()
                if (distance < bestDistance) {
                    bestDistance = distance
                    best = FormatInfo(level, maskPattern)
                }
            }
        }
        return if (bestDistance <= MAX_CORRECTABLE_DISTANCE) best else null
    }

    fun encodeVersion(version: Int): Int {
        require(version >= QrVersion.MIN_VERSION_WITH_VERSION_INFO) {
            "Информация о версии есть только с версии ${QrVersion.MIN_VERSION_WITH_VERSION_INFO}"
        }
        val payload = version shl VERSION_CHECK_BITS
        return payload or remainder(payload, VERSION_GENERATOR)
    }

    fun decodeVersion(bits: Int): Int? {
        var best: Int? = null
        var bestDistance = Int.MAX_VALUE

        for (version in QrVersion.MIN_VERSION_WITH_VERSION_INFO..QrVersion.MAX_SUPPORTED) {
            val candidate = encodeVersion(version)
            if (candidate == bits) return version
            val distance = (candidate xor bits).countOneBits()
            if (distance < bestDistance) {
                bestDistance = distance
                best = version
            }
        }
        return if (bestDistance <= MAX_CORRECTABLE_DISTANCE) best else null
    }

    /** Остаток от деления многочленов над GF(2) — обычный xor со сдвигом. */
    private fun remainder(value: Int, generator: Int): Int {
        var result = value
        val generatorDegree = bitLength(generator) - 1
        while (bitLength(result) - 1 >= generatorDegree) {
            result = result xor (generator shl (bitLength(result) - 1 - generatorDegree))
        }
        return result
    }

    private fun bitLength(value: Int): Int = Int.SIZE_BITS - value.countLeadingZeroBits()

    init {
        // Дешёвая страховка порождающих многочленов: длины полей должны сойтись с их степенями.
        check(bitLength(FORMAT_GENERATOR) - 1 == FORMAT_CHECK_BITS)
        check(bitLength(VERSION_GENERATOR) - 1 == VERSION_CHECK_BITS)
        check(FORMAT_DATA_BITS + FORMAT_CHECK_BITS == 15)
        check(VERSION_DATA_BITS + VERSION_CHECK_BITS == 18)
    }
}

internal data class FormatInfo(
    val errorCorrectionLevel: ErrorCorrectionLevel,
    val maskPattern: Int,
)
