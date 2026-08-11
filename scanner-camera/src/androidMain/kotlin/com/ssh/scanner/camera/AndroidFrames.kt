package com.ssh.scanner.camera

import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import com.ssh.scanner.ScanResult
import com.ssh.scanner.Scanner
import com.ssh.scanner.image.GrayImage

/**
 * Превращает кадр CameraX в [GrayFrame] без конвертации цвета.
 *
 * `YUV_420_888` держит яркость в нулевой плоскости отдельно от цветности, поэтому «перевод в
 * градации серого» здесь — это просто чтение первой плоскости. Никакого прохода по кадру с
 * арифметикой RGB не происходит, и это главное, что делает разбор в реальном времени возможным.
 */
public fun ImageProxy.toGrayFrame(mirrored: Boolean = false): GrayFrame {
    val plane = planes[0]
    val buffer = plane.buffer
    // Буфер придётся скопировать: он живёт до close(), а кадр может пережить анализатор.
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)

    return GrayFrame(
        image = GrayImage(
            pixels = bytes,
            width = width,
            height = height,
            rowStride = plane.rowStride,
        ),
        rotationDegrees = imageInfo.rotationDegrees,
        mirrored = mirrored,
    )
}

/**
 * Анализатор кадров для `ImageAnalysis`.
 *
 * Кадр разбирается **синхронно**, прямо в колбэке: пока он не закрыт, CameraX не отдаёт следующий.
 * В связке со `STRATEGY_KEEP_ONLY_LATEST` это и есть регулировка нагрузки — лишние кадры
 * отбрасываются сами, без очередей и без собственного ограничителя частоты.
 *
 * ⚠️ Модуль не собирался: писался в окружении без Android SDK.
 */
public class ScannerImageAnalyzer(
    private val scanner: Scanner,
    private val mirrored: Boolean = false,
    private val onFrame: (List<ScanResult>, GrayFrame) -> Unit,
) : ImageAnalysis.Analyzer {

    override fun analyze(image: ImageProxy) {
        try {
            val frame = image.toGrayFrame(mirrored)
            onFrame(scanner.scan(frame.image), frame)
        } finally {
            // Незакрытый кадр останавливает всю камеру, поэтому close обязателен при любом исходе.
            image.close()
        }
    }
}
