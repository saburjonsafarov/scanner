package com.ssh.scanner.qr

/**
 * Уровень коррекции ошибок. [formatBits] — то, чем уровень записан в информации о формате;
 * порядок битов задан стандартом и намеренно не совпадает с порядком «по возрастанию стойкости».
 */
public enum class ErrorCorrectionLevel(internal val formatBits: Int) {
    /** ~7 % повреждений. */
    L(0b01),

    /** ~15 % повреждений. */
    M(0b00),

    /** ~25 % повреждений. */
    Q(0b11),

    /** ~30 % повреждений. */
    H(0b10),
    ;

    internal companion object {
        fun fromFormatBits(bits: Int): ErrorCorrectionLevel =
            entries.first { it.formatBits == bits }
    }
}

/** Группа блоков одного размера: [count] блоков по [dataCodewords] слов данных в каждом. */
internal data class EcBlockGroup(val count: Int, val dataCodewords: Int)

/**
 * Раскладка символа на блоки для конкретного уровня коррекции.
 *
 * Блоки нужны, потому что Рид-Соломон исправляет ограниченное число слов **на блок**: разбив данные
 * на несколько блоков, символ переживает крупное локальное пятно, которое убило бы один длинный блок.
 */
internal class EcBlocks(
    val ecCodewordsPerBlock: Int,
    val groups: List<EcBlockGroup>,
) {
    val totalBlocks: Int get() = groups.sumOf { it.count }
    val totalDataCodewords: Int get() = groups.sumOf { it.count * it.dataCodewords }
    val totalCodewords: Int get() = totalDataCodewords + totalBlocks * ecCodewordsPerBlock
}

/**
 * Версия QR-символа: его размер и раскладка блоков коррекции.
 *
 * ⚠️ TODO(Saburjon): поддержаны версии 1…10 (до 57×57 модулей, ~270 байт полезной нагрузки —
 * это покрывает URL, `tel:`, Wi-Fi и платёжные QR). Версии 11…40 требуют ещё 120 строк таблицы
 * из стандарта; вписывать их по памяти без возможности сверки — прямой путь к тихой ошибке,
 * поэтому они добавятся отдельно, со сверкой по печатному ISO/IEC 18004.
 */
internal class QrVersion(
    val number: Int,
    private val ecBlocksByLevel: Map<ErrorCorrectionLevel, EcBlocks>,
) {
    /** Сторона символа в модулях: версия 1 — 21×21, каждая следующая на 4 модуля больше. */
    val size: Int = MIN_SIZE + (number - 1) * SIZE_STEP

    /** Символ несёт информацию о версии (два блока 6×3), начиная с седьмой версии. */
    val hasVersionInformation: Boolean = number >= MIN_VERSION_WITH_VERSION_INFO

    fun ecBlocks(level: ErrorCorrectionLevel): EcBlocks = requireNotNull(ecBlocksByLevel[level]) {
        "Для версии $number нет раскладки уровня $level"
    }

    /**
     * Координаты центров выравнивающих паттернов по обеим осям.
     *
     * Считаются, а не берутся таблицей: первый центр всегда 6, последний — на семь модулей левее
     * правого края, а третий (с версии 7) ровно посередине между ними. Для версий 1…10 это
     * даёт в точности стандартные значения, и одной таблицей чисел из памяти становится меньше.
     */
    val alignmentCenters: IntArray = when {
        number == 1 -> IntArray(0)
        number < MIN_VERSION_WITH_VERSION_INFO -> intArrayOf(FIRST_ALIGNMENT_CENTER, size - 7)
        else -> intArrayOf(
            FIRST_ALIGNMENT_CENTER,
            (FIRST_ALIGNMENT_CENTER + size - 7) / 2,
            size - 7,
        )
    }

    internal companion object {
        const val MIN_SIZE = 21
        const val SIZE_STEP = 4
        const val FIRST_ALIGNMENT_CENTER = 6
        const val MIN_VERSION_WITH_VERSION_INFO = 7
        const val MAX_SUPPORTED = 10

        /**
         * Таблица из ISO/IEC 18004. Каждая строка: уровень → число корректирующих слов на блок
         * и группы блоков. Целостность строк проверяется тестом, который считает вместимость
         * версии по геометрии символа и сверяет с суммой по таблице.
         */
        private val VERSIONS: Map<Int, QrVersion> = buildMap {
            fun version(number: Int, vararg levels: Pair<ErrorCorrectionLevel, EcBlocks>) {
                put(number, QrVersion(number, levels.toMap()))
            }

            fun blocks(ec: Int, vararg groups: Pair<Int, Int>) =
                EcBlocks(ec, groups.map { EcBlockGroup(count = it.first, dataCodewords = it.second) })

            version(
                1,
                ErrorCorrectionLevel.L to blocks(7, 1 to 19),
                ErrorCorrectionLevel.M to blocks(10, 1 to 16),
                ErrorCorrectionLevel.Q to blocks(13, 1 to 13),
                ErrorCorrectionLevel.H to blocks(17, 1 to 9),
            )
            version(
                2,
                ErrorCorrectionLevel.L to blocks(10, 1 to 34),
                ErrorCorrectionLevel.M to blocks(16, 1 to 28),
                ErrorCorrectionLevel.Q to blocks(22, 1 to 22),
                ErrorCorrectionLevel.H to blocks(28, 1 to 16),
            )
            version(
                3,
                ErrorCorrectionLevel.L to blocks(15, 1 to 55),
                ErrorCorrectionLevel.M to blocks(26, 1 to 44),
                ErrorCorrectionLevel.Q to blocks(18, 2 to 17),
                ErrorCorrectionLevel.H to blocks(22, 2 to 13),
            )
            version(
                4,
                ErrorCorrectionLevel.L to blocks(20, 1 to 80),
                ErrorCorrectionLevel.M to blocks(18, 2 to 32),
                ErrorCorrectionLevel.Q to blocks(26, 2 to 24),
                ErrorCorrectionLevel.H to blocks(16, 4 to 9),
            )
            version(
                5,
                ErrorCorrectionLevel.L to blocks(26, 1 to 108),
                ErrorCorrectionLevel.M to blocks(24, 2 to 43),
                ErrorCorrectionLevel.Q to blocks(18, 2 to 15, 2 to 16),
                ErrorCorrectionLevel.H to blocks(22, 2 to 11, 2 to 12),
            )
            version(
                6,
                ErrorCorrectionLevel.L to blocks(18, 2 to 68),
                ErrorCorrectionLevel.M to blocks(16, 4 to 27),
                ErrorCorrectionLevel.Q to blocks(24, 4 to 19),
                ErrorCorrectionLevel.H to blocks(28, 4 to 15),
            )
            version(
                7,
                ErrorCorrectionLevel.L to blocks(20, 2 to 78),
                ErrorCorrectionLevel.M to blocks(18, 4 to 31),
                ErrorCorrectionLevel.Q to blocks(18, 2 to 14, 4 to 15),
                ErrorCorrectionLevel.H to blocks(26, 4 to 13, 1 to 14),
            )
            version(
                8,
                ErrorCorrectionLevel.L to blocks(24, 2 to 97),
                ErrorCorrectionLevel.M to blocks(22, 2 to 38, 2 to 39),
                ErrorCorrectionLevel.Q to blocks(22, 4 to 18, 2 to 19),
                ErrorCorrectionLevel.H to blocks(26, 4 to 14, 2 to 15),
            )
            version(
                9,
                ErrorCorrectionLevel.L to blocks(30, 2 to 116),
                ErrorCorrectionLevel.M to blocks(22, 3 to 36, 2 to 37),
                ErrorCorrectionLevel.Q to blocks(20, 4 to 16, 4 to 17),
                ErrorCorrectionLevel.H to blocks(24, 4 to 12, 4 to 13),
            )
            version(
                10,
                ErrorCorrectionLevel.L to blocks(18, 2 to 68, 2 to 69),
                ErrorCorrectionLevel.M to blocks(26, 4 to 43, 1 to 44),
                ErrorCorrectionLevel.Q to blocks(24, 6 to 19, 2 to 20),
                ErrorCorrectionLevel.H to blocks(28, 6 to 15, 2 to 16),
            )
        }

        val supported: List<QrVersion> get() = VERSIONS.values.sortedBy { it.number }

        fun of(number: Int): QrVersion = requireNotNull(VERSIONS[number]) {
            "Версия QR $number не поддерживается (доступны 1…$MAX_SUPPORTED)"
        }

        /** Версия по стороне символа в модулях; `null`, если сторона не соответствует ни одной. */
        fun ofSize(size: Int): QrVersion? {
            if (size < MIN_SIZE || (size - MIN_SIZE) % SIZE_STEP != 0) return null
            return VERSIONS[(size - MIN_SIZE) / SIZE_STEP + 1]
        }
    }
}
