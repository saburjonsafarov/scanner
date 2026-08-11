package com.ssh.scanner.qr

import com.ssh.scanner.geometry.PerspectiveTransform
import com.ssh.scanner.geometry.ScanPoint
import com.ssh.scanner.image.BitMatrix
import com.ssh.scanner.image.GrayImage
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin
import kotlin.random.Random

/**
 * Рисует матрицу модулей в кадр — с поворотом, перспективой, неровным светом, шумом и расфокусом.
 *
 * Без этого стенда декодер проверялся бы только на идеальных матрицах, то есть ровно там, где он
 * заведомо работает. Настоящие отказы живут в другом месте: искатель не нашёлся на смазанном кадре,
 * сетка уехала на соседний модуль у дальнего края, порог съел тонкую полосу в тени.
 */
internal object QrTestRenderer {

    private const val DARK_LUMA = 30
    private const val LIGHT_LUMA = 225

    fun render(
        matrix: BitMatrix,
        moduleSize: Int = 6,
        quietZoneModules: Int = 4,
        rotationDegrees: Float = 0f,
        perspective: Float = 0f,
        blurRadius: Int = 0,
        noiseAmplitude: Int = 0,
        lightingGradient: Int = 0,
        seed: Int = 20260811,
    ): GrayImage {
        val symbolPixels = matrix.width * moduleSize
        val margin = quietZoneModules * moduleSize
        // Запас по краям, чтобы повёрнутый символ не вылезал за кадр.
        val canvas = symbolPixels + margin * 2 + symbolPixels / 2
        val pixels = ByteArray(canvas * canvas)

        val transform = PerspectiveTransform.quadToQuad(
            source = listOf(
                ScanPoint(0f, 0f),
                ScanPoint(matrix.width.toFloat(), 0f),
                ScanPoint(matrix.width.toFloat(), matrix.height.toFloat()),
                ScanPoint(0f, matrix.height.toFloat()),
            ),
            target = symbolCorners(canvas, symbolPixels, rotationDegrees, perspective),
        ).inverse()

        val random = Random(seed)
        for (y in 0 until canvas) {
            for (x in 0 until canvas) {
                val grid = transform.transform(x + 0.5, y + 0.5)
                val column = floor(grid.x).toInt()
                val row = floor(grid.y).toInt()

                val dark = matrix.contains(column, row) && matrix[column, row]
                var luma = if (dark) DARK_LUMA else LIGHT_LUMA
                // Неровный свет: яркость плавно падает по диагонали кадра.
                luma -= lightingGradient * (x + y) / (2 * canvas)
                if (noiseAmplitude > 0) {
                    luma += random.nextInt(-noiseAmplitude, noiseAmplitude + 1)
                }
                pixels[y * canvas + x] = luma.coerceIn(0, 255).toByte()
            }
        }

        val result = if (blurRadius > 0) blur(pixels, canvas, blurRadius) else pixels
        return GrayImage(result, canvas, canvas)
    }

    /** Углы символа на кадре: сначала перспектива (верхний край сужается), затем поворот. */
    private fun symbolCorners(
        canvas: Int,
        symbolPixels: Int,
        rotationDegrees: Float,
        perspective: Float,
    ): List<ScanPoint> {
        val left = (canvas - symbolPixels) / 2f
        val top = (canvas - symbolPixels) / 2f
        val right = left + symbolPixels
        val bottom = top + symbolPixels
        val inset = symbolPixels * perspective

        val corners = listOf(
            ScanPoint(left + inset, top),
            ScanPoint(right - inset, top),
            ScanPoint(right, bottom),
            ScanPoint(left, bottom),
        )
        if (rotationDegrees == 0f) return corners

        val angle = rotationDegrees * kotlin.math.PI.toFloat() / 180f
        val centerX = canvas / 2f
        val centerY = canvas / 2f
        return corners.map { point ->
            val dx = point.x - centerX
            val dy = point.y - centerY
            ScanPoint(
                x = centerX + dx * cos(angle) - dy * sin(angle),
                y = centerY + dx * sin(angle) + dy * cos(angle),
            )
        }
    }

    /** Расфокус скользящим средним — грубо, но именно так размывается кадр с автофокусом в поиске. */
    private fun blur(pixels: ByteArray, size: Int, radius: Int): ByteArray {
        val result = ByteArray(pixels.size)
        for (y in 0 until size) {
            for (x in 0 until size) {
                var sum = 0
                var count = 0
                for (dy in -radius..radius) {
                    val sampleY = y + dy
                    if (sampleY !in 0 until size) continue
                    for (dx in -radius..radius) {
                        val sampleX = x + dx
                        if (sampleX !in 0 until size) continue
                        sum += pixels[sampleY * size + sampleX].toInt() and 0xFF
                        count++
                    }
                }
                result[y * size + x] = (sum / count).toByte()
            }
        }
        return result
    }
}
