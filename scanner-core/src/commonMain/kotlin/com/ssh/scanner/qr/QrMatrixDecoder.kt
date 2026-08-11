package com.ssh.scanner.qr

import com.ssh.scanner.image.BitMatrix

/** Что удалось вытащить из символа. */
internal data class QrDecodedSymbol(
    val text: String,
    val version: QrVersion,
    val errorCorrectionLevel: ErrorCorrectionLevel,
    val maskPattern: Int,
)

/**
 * Превращает готовую матрицу модулей в текст.
 *
 * Вход — уже выровненная сетка (её строит сэмплер по найденным углам), поэтому здесь нет ни
 * геометрии, ни изображения: только служебные поля, снятие маски, блоки и поток данных.
 */
internal object QrMatrixDecoder {

    fun decode(matrix: BitMatrix): QrDecodedSymbol? {
        if (matrix.width != matrix.height) return null
        val version = QrVersion.ofSize(matrix.width) ?: return null
        val layout = QrSymbolLayout(version)

        if (version.hasVersionInformation && !versionInformationAgrees(matrix, layout, version)) {
            return null
        }

        val format = readFormat(matrix, layout) ?: return null

        // Маска обратима, но исходную матрицу портить нельзя: вызывающий код может пробовать
        // несколько гипотез ориентации по одной и той же сетке.
        val unmasked = matrix.copyOf()
        layout.applyMask(unmasked, format.maskPattern)

        val raw = readCodewords(unmasked, layout, version, format.errorCorrectionLevel)
        val data = QrCodewords.restoreData(raw, version, format.errorCorrectionLevel) ?: return null
        val text = QrDataDecoder.decode(data, version) ?: return null

        return QrDecodedSymbol(
            text = text,
            version = version,
            errorCorrectionLevel = format.errorCorrectionLevel,
            maskPattern = format.maskPattern,
        )
    }

    /**
     * Формат записан дважды. Первая копия читается целиком у верхнего левого искателя, вторая
     * разорвана между двумя другими углами — если один угол смазан, символ всё ещё читается.
     */
    private fun readFormat(matrix: BitMatrix, layout: QrSymbolLayout): FormatInfo? {
        val positions = layout.formatPositions()
        var firstCopy = 0
        var secondCopy = 0
        positions.forEachIndexed { bitIndex, copies ->
            if (matrix[copies[0].first, copies[0].second]) firstCopy = firstCopy or (1 shl bitIndex)
            if (matrix[copies[1].first, copies[1].second]) secondCopy = secondCopy or (1 shl bitIndex)
        }
        return BchCodes.decodeFormat(firstCopy) ?: BchCodes.decodeFormat(secondCopy)
    }

    private fun versionInformationAgrees(
        matrix: BitMatrix,
        layout: QrSymbolLayout,
        version: QrVersion,
    ): Boolean {
        val positions = layout.versionPositions()
        var firstCopy = 0
        var secondCopy = 0
        positions.forEachIndexed { bitIndex, copies ->
            if (matrix[copies[0].first, copies[0].second]) firstCopy = firstCopy or (1 shl bitIndex)
            if (matrix[copies[1].first, copies[1].second]) secondCopy = secondCopy or (1 shl bitIndex)
        }
        val declared = BchCodes.decodeVersion(firstCopy) ?: BchCodes.decodeVersion(secondCopy)
        // Размер сетки уже дал версию; расхождение означает, что сетка построена неверно.
        return declared == null || declared == version.number
    }

    private fun readCodewords(
        matrix: BitMatrix,
        layout: QrSymbolLayout,
        version: QrVersion,
        level: ErrorCorrectionLevel,
    ): IntArray {
        val expected = version.ecBlocks(level).totalCodewords
        val result = IntArray(expected)
        var codeword = 0
        var bitsFilled = 0

        for ((x, y) in layout.dataModulePositions()) {
            if (codeword >= expected) break
            result[codeword] = (result[codeword] shl 1) or if (matrix[x, y]) 1 else 0
            bitsFilled++
            if (bitsFilled == 8) {
                codeword++
                bitsFilled = 0
            }
        }
        return result
    }
}

/** Копия матрицы: снятие маски не должно портить исходную сетку. */
internal fun BitMatrix.copyOf(): BitMatrix = BitMatrix(width, height).also { copy ->
    for (y in 0 until height) {
        for (x in 0 until width) {
            copy[x, y] = this[x, y]
        }
    }
}
