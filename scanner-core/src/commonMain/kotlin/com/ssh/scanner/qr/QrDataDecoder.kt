package com.ssh.scanner.qr

/** Побитовое чтение кодовых слов, старший бит первым — в этом порядке QR пишет данные. */
internal class BitSource(private val codewords: IntArray) {

    private var bitPosition = 0

    val availableBits: Int get() = codewords.size * 8 - bitPosition

    fun read(bitCount: Int): Int {
        require(bitCount in 1..32) { "За раз читается 1…32 бита, запрошено $bitCount" }
        require(bitCount <= availableBits) { "Запрошено $bitCount бит, осталось $availableBits" }

        var result = 0
        var remaining = bitCount
        while (remaining > 0) {
            val byteIndex = bitPosition / 8
            val bitInByte = bitPosition % 8
            val takeNow = minOf(remaining, 8 - bitInByte)
            val shift = 8 - bitInByte - takeNow
            val mask = (1 shl takeNow) - 1
            result = (result shl takeNow) or ((codewords[byteIndex] ushr shift) and mask)
            bitPosition += takeNow
            remaining -= takeNow
        }
        return result
    }
}

/**
 * Режим кодирования сегмента.
 *
 * Разрядность счётчика символов растёт с версией символа: чем больше символ, тем длиннее сегмент,
 * который в нём помещается, и тем шире должно быть поле длины.
 */
internal enum class QrMode(val indicator: Int, private val countBitsByRange: IntArray) {
    /** Три цифры в десять бит — самый плотный режим, им кодируют номера. */
    Numeric(indicator = 0x1, countBitsByRange = intArrayOf(10, 12, 14)),

    /** Два символа из 45-символьного алфавита в одиннадцать бит; сюда попадают `WIFI:`, `HTTP://`. */
    Alphanumeric(indicator = 0x2, countBitsByRange = intArrayOf(9, 11, 13)),

    /** Восемь бит на байт; всё, что не влезло в предыдущие два. */
    Byte(indicator = 0x4, countBitsByRange = intArrayOf(8, 16, 16)),
    ;

    fun countBits(version: QrVersion): Int = countBitsByRange[rangeIndex(version)]

    companion object {
        fun byIndicator(indicator: Int): QrMode? = entries.firstOrNull { it.indicator == indicator }

        private fun rangeIndex(version: QrVersion): Int = when {
            version.number <= 9 -> 0
            version.number <= 26 -> 1
            else -> 2
        }
    }
}

/**
 * Разбирает поток данных QR в текст.
 *
 * Символ может состоять из нескольких сегментов разных режимов: телефон часто кодируют цифровым
 * режимом, ссылку — байтовым, а `WIFI:S:…` — буквенно-цифровым. Смена режима посреди строки — норма.
 */
internal object QrDataDecoder {

    internal const val ALPHANUMERIC_TABLE = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ \$%*+-./:"

    private const val MODE_TERMINATOR = 0x0
    private const val MODE_ECI = 0x7
    private const val ECI_UTF8 = 26

    /**
     * @return расшифрованный текст или `null`, если поток повреждён либо использует режим,
     *   которого в M1 нет (кандзи, структурное объединение, FNC1). Вернуть обрывок строки было бы
     *   хуже отказа: потребитель принял бы его за полный результат.
     */
    fun decode(data: IntArray, version: QrVersion): String? {
        val source = BitSource(data)
        val result = StringBuilder()
        var forcedUtf8 = false

        while (source.availableBits >= 4) {
            val indicator = source.read(4)
            if (indicator == MODE_TERMINATOR) return result.toString()

            if (indicator == MODE_ECI) {
                val designator = readEciDesignator(source) ?: return null
                // Прочие ECI не роняют разбор — они просто не меняют интерпретацию байтов.
                if (designator == ECI_UTF8) forcedUtf8 = true
                continue
            }

            val mode = QrMode.byIndicator(indicator) ?: return null
            val countBits = mode.countBits(version)
            if (source.availableBits < countBits) return null
            val count = source.read(countBits)

            val decoded = when (mode) {
                QrMode.Numeric -> decodeNumeric(source, count, result)
                QrMode.Alphanumeric -> decodeAlphanumeric(source, count, result)
                QrMode.Byte -> decodeBytes(source, count, forcedUtf8, result)
            }
            if (!decoded) return null
        }
        return result.toString()
    }

    private fun decodeNumeric(source: BitSource, count: Int, result: StringBuilder): Boolean {
        var remaining = count
        while (remaining >= 3) {
            if (source.availableBits < 10) return false
            val triple = source.read(10)
            if (triple >= 1000) return false
            result.append(triple / 100).append((triple / 10) % 10).append(triple % 10)
            remaining -= 3
        }
        when (remaining) {
            2 -> {
                if (source.availableBits < 7) return false
                val pair = source.read(7)
                if (pair >= 100) return false
                result.append(pair / 10).append(pair % 10)
            }

            1 -> {
                if (source.availableBits < 4) return false
                val single = source.read(4)
                if (single >= 10) return false
                result.append(single)
            }
        }
        return true
    }

    private fun decodeAlphanumeric(source: BitSource, count: Int, result: StringBuilder): Boolean {
        var remaining = count
        while (remaining >= 2) {
            if (source.availableBits < 11) return false
            val pair = source.read(11)
            val first = pair / ALPHANUMERIC_TABLE.length
            val second = pair % ALPHANUMERIC_TABLE.length
            if (first >= ALPHANUMERIC_TABLE.length) return false
            result.append(ALPHANUMERIC_TABLE[first]).append(ALPHANUMERIC_TABLE[second])
            remaining -= 2
        }
        if (remaining == 1) {
            if (source.availableBits < 6) return false
            val single = source.read(6)
            if (single >= ALPHANUMERIC_TABLE.length) return false
            result.append(ALPHANUMERIC_TABLE[single])
        }
        return true
    }

    private fun decodeBytes(
        source: BitSource,
        count: Int,
        forcedUtf8: Boolean,
        result: StringBuilder,
    ): Boolean {
        if (source.availableBits < count * 8) return false
        val bytes = ByteArray(count) { source.read(8).toByte() }
        result.append(decodeText(bytes, forcedUtf8))
        return true
    }

    /**
     * Стандарт объявляет кодировкой байтового режима ISO-8859-1, но генераторы повсеместно пишут
     * UTF-8 без всякого ECI — иначе кириллица в QR не жила бы вовсе. Поэтому сначала пробуем UTF-8
     * и откатываемся на Latin-1, только если байты в UTF-8 не складываются.
     */
    private fun decodeText(bytes: ByteArray, forcedUtf8: Boolean): String {
        if (forcedUtf8) return bytes.decodeToString()
        return try {
            bytes.decodeToString(0, bytes.size, true)
        } catch (_: CharacterCodingException) {
            buildString(bytes.size) {
                bytes.forEach { append((it.toInt() and 0xFF).toChar()) }
            }
        }
    }

    /** Номер ECI занимает 1, 2 или 3 байта — длина закодирована старшими битами первого байта. */
    private fun readEciDesignator(source: BitSource): Int? {
        if (source.availableBits < 8) return null
        val first = source.read(8)
        return when {
            first and 0x80 == 0 -> first
            first and 0xC0 == 0x80 -> {
                if (source.availableBits < 8) return null
                ((first and 0x3F) shl 8) or source.read(8)
            }

            first and 0xE0 == 0xC0 -> {
                if (source.availableBits < 16) return null
                ((first and 0x1F) shl 16) or source.read(16)
            }

            else -> null
        }
    }
}
