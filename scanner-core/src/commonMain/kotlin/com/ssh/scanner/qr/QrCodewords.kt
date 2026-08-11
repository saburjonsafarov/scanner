package com.ssh.scanner.qr

import com.ssh.scanner.ecc.ReedSolomonDecoder

/**
 * Раскладка кодовых слов символа по блокам коррекции и обратно.
 *
 * Слова блоков в символе идут не подряд, а вперемешку — слово каждого блока по очереди. Это и есть
 * то, ради чего вся конструкция: физически соседние модули принадлежат **разным** блокам, поэтому
 * одно большое пятно грязи бьёт по каждому блоку понемногу, вместо того чтобы убить один целиком.
 */
internal object QrCodewords {

    /** Длины частей данных каждого блока в порядке следования. */
    private fun blockDataLengths(blocks: EcBlocks): IntArray {
        val lengths = IntArray(blocks.totalBlocks)
        var index = 0
        for (group in blocks.groups) {
            repeat(group.count) { lengths[index++] = group.dataCodewords }
        }
        return lengths
    }

    /**
     * Разбирает поток символа на блоки и исправляет каждый Рид-Соломоном.
     *
     * @return слова данных всех блоков подряд или `null`, если хотя бы один блок повреждён
     *   сильнее, чем позволяет уровень коррекции.
     */
    fun restoreData(raw: IntArray, version: QrVersion, level: ErrorCorrectionLevel): IntArray? {
        val ecBlocks = version.ecBlocks(level)
        require(raw.size >= ecBlocks.totalCodewords) {
            "Ожидалось ${ecBlocks.totalCodewords} кодовых слов, получено ${raw.size}"
        }

        val dataLengths = blockDataLengths(ecBlocks)
        val ecLength = ecBlocks.ecCodewordsPerBlock
        val blocks = Array(dataLengths.size) { IntArray(dataLengths[it] + ecLength) }

        var source = 0
        val longestBlock = dataLengths.max()
        for (position in 0 until longestBlock) {
            for (blockIndex in blocks.indices) {
                if (position < dataLengths[blockIndex]) {
                    blocks[blockIndex][position] = raw[source++]
                }
            }
        }
        for (position in 0 until ecLength) {
            for (blockIndex in blocks.indices) {
                blocks[blockIndex][dataLengths[blockIndex] + position] = raw[source++]
            }
        }

        val result = IntArray(ecBlocks.totalDataCodewords)
        var target = 0
        for (blockIndex in blocks.indices) {
            if (!ReedSolomonDecoder.decode(blocks[blockIndex], ecLength)) return null
            blocks[blockIndex].copyInto(
                destination = result,
                destinationOffset = target,
                startIndex = 0,
                endIndex = dataLengths[blockIndex],
            )
            target += dataLengths[blockIndex]
        }
        return result
    }

    /**
     * Обратная операция: раскладывает слова данных по блокам, дописывает корректирующие
     * и переплетает их в поток символа. Нужна кодеру, который живёт в тестах.
     */
    fun interleave(
        data: IntArray,
        version: QrVersion,
        level: ErrorCorrectionLevel,
        encodeEc: (IntArray, Int) -> IntArray,
    ): IntArray {
        val ecBlocks = version.ecBlocks(level)
        require(data.size == ecBlocks.totalDataCodewords) {
            "Ожидалось ${ecBlocks.totalDataCodewords} слов данных, получено ${data.size}"
        }

        val dataLengths = blockDataLengths(ecBlocks)
        val ecLength = ecBlocks.ecCodewordsPerBlock

        var source = 0
        val dataBlocks = Array(dataLengths.size) { blockIndex ->
            IntArray(dataLengths[blockIndex]) { data[source++] }
        }
        val ecBlocksData = Array(dataBlocks.size) { encodeEc(dataBlocks[it], ecLength) }

        val result = IntArray(ecBlocks.totalCodewords)
        var target = 0
        val longestBlock = dataLengths.max()
        for (position in 0 until longestBlock) {
            for (blockIndex in dataBlocks.indices) {
                if (position < dataLengths[blockIndex]) {
                    result[target++] = dataBlocks[blockIndex][position]
                }
            }
        }
        for (position in 0 until ecLength) {
            for (blockIndex in ecBlocksData.indices) {
                result[target++] = ecBlocksData[blockIndex][position]
            }
        }
        return result
    }
}
