package com.ssh.scanner.qr

import com.ssh.scanner.ecc.ReedSolomonEncoder
import com.ssh.scanner.image.BitMatrix

/**
 * Кодер QR — **только для тестов**. В рантайме сканера кодирование не нужно, но без него нечем
 * проверять декодер: эталонных снимков в репозитории нет, а рисовать матрицы руками нереально.
 *
 * Геометрию кодер не изобретает — берёт тот же [QrSymbolLayout], что и декодер. Это осознанный
 * компромисс: raundtrip не докажет, что раскладка соответствует стандарту (обе стороны могут
 * ошибаться одинаково), зато докажет всё остальное. Соответствие стандарту проверяется отдельно:
 * кодовыми словами из опубликованного примера в тестах Рида — Соломона и сверкой таблицы блоков
 * с геометрической ёмкостью символа.
 */
internal object QrTestEncoder {

    private const val PAD_BYTE_FIRST = 0xEC
    private const val PAD_BYTE_SECOND = 0x11
    private const val TERMINATOR_BITS = 4

    fun encode(
        text: String,
        level: ErrorCorrectionLevel = ErrorCorrectionLevel.M,
        maskPattern: Int = 0,
        minVersion: Int = 1,
    ): BitMatrix {
        val mode = chooseMode(text)
        val payload = if (mode == QrMode.Byte) text.encodeToByteArray() else ByteArray(0)
        val version = chooseVersion(text, payload, mode, level, minVersion)

        val bits = buildBitStream(text, payload, mode, version)
        val data = toDataCodewords(bits, version.ecBlocks(level).totalDataCodewords)
        val codewords = QrCodewords.interleave(data, version, level) { block, ecLength ->
            ReedSolomonEncoder.encode(block, ecLength)
        }

        val layout = QrSymbolLayout(version)
        val matrix = BitMatrix(version.size, version.size)
        layout.drawFunctionPatterns(matrix)
        layout.drawVersionInformation(matrix)

        var bitIndex = 0
        val totalBits = codewords.size * 8
        for ((x, y) in layout.dataModulePositions()) {
            if (bitIndex >= totalBits) break
            val word = codewords[bitIndex / 8]
            matrix[x, y] = (word ushr (7 - bitIndex % 8)) and 1 == 1
            bitIndex++
        }

        layout.applyMask(matrix, maskPattern)
        layout.drawFormatInformation(matrix, level, maskPattern)
        return matrix
    }

    private fun chooseMode(text: String): QrMode = when {
        text.isNotEmpty() && text.all { it in '0'..'9' } -> QrMode.Numeric
        text.isNotEmpty() && text.all { it in QrDataDecoder.ALPHANUMERIC_TABLE } -> QrMode.Alphanumeric
        else -> QrMode.Byte
    }

    private fun chooseVersion(
        text: String,
        payload: ByteArray,
        mode: QrMode,
        level: ErrorCorrectionLevel,
        minVersion: Int,
    ): QrVersion {
        for (number in minVersion..QrVersion.MAX_SUPPORTED) {
            val version = QrVersion.of(number)
            val required = 4 + mode.countBits(version) + payloadBits(text, payload, mode)
            if (required <= version.ecBlocks(level).totalDataCodewords * 8) return version
        }
        error("Строка длиной ${text.length} не помещается в поддерживаемые версии QR")
    }

    private fun payloadBits(text: String, payload: ByteArray, mode: QrMode): Int = when (mode) {
        QrMode.Numeric -> {
            val triples = text.length / 3
            triples * 10 + when (text.length % 3) {
                2 -> 7
                1 -> 4
                else -> 0
            }
        }

        QrMode.Alphanumeric -> (text.length / 2) * 11 + (text.length % 2) * 6
        QrMode.Byte -> payload.size * 8
    }

    private fun buildBitStream(
        text: String,
        payload: ByteArray,
        mode: QrMode,
        version: QrVersion,
    ): MutableList<Boolean> {
        val bits = ArrayList<Boolean>()
        bits.append(mode.indicator, TERMINATOR_BITS)
        val characterCount = if (mode == QrMode.Byte) payload.size else text.length
        bits.append(characterCount, mode.countBits(version))

        when (mode) {
            QrMode.Numeric -> {
                var index = 0
                while (index + 3 <= text.length) {
                    bits.append(text.substring(index, index + 3).toInt(), 10)
                    index += 3
                }
                when (text.length - index) {
                    2 -> bits.append(text.substring(index, index + 2).toInt(), 7)
                    1 -> bits.append(text.substring(index, index + 1).toInt(), 4)
                }
            }

            QrMode.Alphanumeric -> {
                var index = 0
                while (index + 2 <= text.length) {
                    val first = QrDataDecoder.ALPHANUMERIC_TABLE.indexOf(text[index])
                    val second = QrDataDecoder.ALPHANUMERIC_TABLE.indexOf(text[index + 1])
                    bits.append(first * QrDataDecoder.ALPHANUMERIC_TABLE.length + second, 11)
                    index += 2
                }
                if (index < text.length) {
                    bits.append(QrDataDecoder.ALPHANUMERIC_TABLE.indexOf(text[index]), 6)
                }
            }

            QrMode.Byte -> payload.forEach { bits.append(it.toInt() and 0xFF, 8) }
        }
        return bits
    }

    private fun toDataCodewords(bits: MutableList<Boolean>, capacityInCodewords: Int): IntArray {
        val capacityInBits = capacityInCodewords * 8

        repeat(minOf(TERMINATOR_BITS, capacityInBits - bits.size)) { bits.add(false) }
        while (bits.size % 8 != 0) bits.add(false)

        val result = IntArray(capacityInCodewords)
        for (index in 0 until bits.size / 8) {
            var word = 0
            for (bit in 0 until 8) {
                word = (word shl 1) or if (bits[index * 8 + bit]) 1 else 0
            }
            result[index] = word
        }
        // Хвост добивается чередующимися байтами-заполнителями — так велит стандарт, и декодер
        // обязан их проигнорировать, наткнувшись на терминатор.
        var position = bits.size / 8
        var useFirst = true
        while (position < capacityInCodewords) {
            result[position++] = if (useFirst) PAD_BYTE_FIRST else PAD_BYTE_SECOND
            useFirst = !useFirst
        }
        return result
    }

    private fun MutableList<Boolean>.append(value: Int, bitCount: Int) {
        for (shift in bitCount - 1 downTo 0) {
            add((value ushr shift) and 1 == 1)
        }
    }
}
