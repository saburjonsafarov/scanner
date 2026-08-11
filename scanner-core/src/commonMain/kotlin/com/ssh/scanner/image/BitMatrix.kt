package com.ssh.scanner.image

/**
 * Двумерная битовая матрица: результат бинаризации и носитель модулей QR-символа.
 *
 * Конвенция всей библиотеки: **`true` — чёрный модуль**, `false` — белый. Она совпадает с тем,
 * как устроен QR (данные кодируются тёмными модулями), поэтому нигде не приходится инвертировать.
 *
 * Хранение — по одному биту в [LongArray]: матрица версии 40 это 177×177 = 31 329 модулей, и держать
 * их массивом Boolean значило бы 31 КБ на кадр вместо 4 КБ.
 */
public class BitMatrix(
    public val width: Int,
    public val height: Int,
) {
    private val wordsPerRow = (width + WORD_BITS - 1) / WORD_BITS
    private val bits = LongArray(wordsPerRow * height)

    init {
        require(width > 0 && height > 0) {
            "Размеры матрицы должны быть положительными, получено ${width}x$height"
        }
    }

    public operator fun get(x: Int, y: Int): Boolean {
        val index = y * wordsPerRow + (x shr WORD_SHIFT)
        return (bits[index] ushr (x and WORD_MASK)) and 1L != 0L
    }

    public operator fun set(x: Int, y: Int, value: Boolean) {
        val index = y * wordsPerRow + (x shr WORD_SHIFT)
        val mask = 1L shl (x and WORD_MASK)
        bits[index] = if (value) bits[index] or mask else bits[index] and mask.inv()
    }

    /** Точка внутри матрицы? Пригождается на краях кадра, где сэмплер может выйти за границу. */
    public fun contains(x: Int, y: Int): Boolean = x in 0 until width && y in 0 until height

    /** Значение с безопасной границей: за пределами матрицы считаем белым (фон). */
    public fun getOrFalse(x: Int, y: Int): Boolean = if (contains(x, y)) get(x, y) else false

    public fun setRegion(left: Int, top: Int, regionWidth: Int, regionHeight: Int, value: Boolean) {
        for (y in top until top + regionHeight) {
            for (x in left until left + regionWidth) {
                set(x, y, value)
            }
        }
    }

    /** Отладочный вид: `#` — чёрный, `.` — белый. Он же формат текстовых фикстур в тестах. */
    override fun toString(): String = buildString(capacity = (width + 1) * height) {
        for (y in 0 until height) {
            for (x in 0 until width) {
                append(if (get(x, y)) '#' else '.')
            }
            append('\n')
        }
    }

    public companion object {
        private const val WORD_BITS = 64
        private const val WORD_SHIFT = 6
        private const val WORD_MASK = 63

        /** Разбор текстовой фикстуры вида `##..#` / `.` — обратная операция к [toString]. */
        public fun parse(text: String): BitMatrix {
            val rows = text.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }
            require(rows.isNotEmpty()) { "Пустая фикстура матрицы" }
            val width = rows.first().length
            require(rows.all { it.length == width }) { "Строки фикстуры разной длины" }
            return BitMatrix(width, rows.size).apply {
                rows.forEachIndexed { y, row ->
                    row.forEachIndexed { x, symbol -> set(x, y, symbol == '#') }
                }
            }
        }
    }
}
