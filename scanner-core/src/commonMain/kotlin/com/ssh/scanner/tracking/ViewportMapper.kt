package com.ssh.scanner.tracking

import com.ssh.scanner.geometry.ScanPoint
import com.ssh.scanner.geometry.ScanQuad

/** Как превью вписано во вью. */
public enum class PreviewScale {
    /** Заполнить вью целиком, обрезав лишнее по одной оси. Так работает превью по умолчанию. */
    FillCenter,

    /** Вписать целиком, оставив поля. */
    FitCenter,
}

/**
 * Переводит координаты кадра в координаты вью — **единственное место**, где это делается.
 *
 * Это самый частый источник багов в сканерах: детектор работает в системе координат буфера камеры,
 * а рамка рисуется в системе координат вью, и между ними три несовпадения сразу — кадр повёрнут
 * относительно экрана, фронтальная камера отзеркалена, а превью обрезано по краям. Ошибка в любом
 * из трёх даёт рамку, которая уверенно обводит пустое место рядом с кодом.
 *
 * Поэтому и отрисовка, и попадание пальцем обязаны спрашивать координаты здесь, а не считать сами.
 */
public class ViewportMapper(
    private val frameWidth: Int,
    private val frameHeight: Int,
    private val viewWidth: Float,
    private val viewHeight: Float,
    private val rotationDegrees: Int = 0,
    private val mirrored: Boolean = false,
    private val scale: PreviewScale = PreviewScale.FillCenter,
) {
    init {
        require(frameWidth > 0 && frameHeight > 0) { "Размеры кадра должны быть положительными" }
        require(viewWidth > 0f && viewHeight > 0f) { "Размеры вью должны быть положительными" }
        require(rotationDegrees in ALLOWED_ROTATIONS) {
            "Поворот кадра бывает только кратным 90°, получено $rotationDegrees"
        }
    }

    /** Размеры кадра после поворота: при 90° и 270° стороны меняются местами. */
    private val rotatedWidth: Float =
        if (rotationDegrees % 180 == 0) frameWidth.toFloat() else frameHeight.toFloat()
    private val rotatedHeight: Float =
        if (rotationDegrees % 180 == 0) frameHeight.toFloat() else frameWidth.toFloat()

    private val factor: Float = when (scale) {
        PreviewScale.FillCenter -> maxOf(viewWidth / rotatedWidth, viewHeight / rotatedHeight)
        PreviewScale.FitCenter -> minOf(viewWidth / rotatedWidth, viewHeight / rotatedHeight)
    }

    private val offsetX: Float = (viewWidth - rotatedWidth * factor) / 2f
    private val offsetY: Float = (viewHeight - rotatedHeight * factor) / 2f

    public fun map(point: ScanPoint): ScanPoint {
        val rotated = rotate(point)
        val mirroredPoint = if (mirrored) ScanPoint(rotatedWidth - rotated.x, rotated.y) else rotated
        return ScanPoint(
            x = mirroredPoint.x * factor + offsetX,
            y = mirroredPoint.y * factor + offsetY,
        )
    }

    /**
     * Переносит контур во вью.
     *
     * Зеркалирование меняет обход углов на противоположный, поэтому порядок восстанавливается
     * заново — иначе `topLeft` на фронтальной камере оказался бы справа, и анимация рамки
     * стартовала бы не из того угла.
     */
    public fun map(quad: ScanQuad): ScanQuad {
        val mapped = quad.corners.map(::map)
        return if (mirrored) ScanQuad.ofUnordered(mapped) else ScanQuad(
            topLeft = mapped[0],
            topRight = mapped[1],
            bottomRight = mapped[2],
            bottomLeft = mapped[3],
        )
    }

    private fun rotate(point: ScanPoint): ScanPoint = when (rotationDegrees) {
        90 -> ScanPoint(x = frameHeight - point.y, y = point.x)
        180 -> ScanPoint(x = frameWidth - point.x, y = frameHeight - point.y)
        270 -> ScanPoint(x = point.y, y = frameWidth - point.x)
        else -> point
    }

    private companion object {
        val ALLOWED_ROTATIONS = setOf(0, 90, 180, 270)
    }
}
