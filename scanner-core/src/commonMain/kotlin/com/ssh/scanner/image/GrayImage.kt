package com.ssh.scanner.image

/**
 * Кадр в градациях серого — единственный вход всех детекторов.
 *
 * Формат выбран не случайно: и Android (`YUV_420_888`), и iOS (`420YpCbCr8BiPlanar`) отдают кадр
 * плоскостями, где первая плоскость — Y, то есть **уже готовая яркость**. Конвертация в RGB не нужна
 * никогда: она стоила бы полного прохода по кадру на каждый кадр, а детекторам цвет не нужен.
 *
 * @param pixels яркость, по байту на пиксель, без знака (0 — чёрный, 255 — белый)
 * @param rowStride расстояние между началами соседних строк в байтах; у камеры оно почти всегда
 *   больше [width] из-за выравнивания буфера, поэтому по строкам нельзя ходить как по плотному массиву
 */
public class GrayImage(
    private val pixels: ByteArray,
    public val width: Int,
    public val height: Int,
    public val rowStride: Int = width,
    private val offset: Int = 0,
) {
    init {
        require(width > 0 && height > 0) {
            "Размеры кадра должны быть положительными, получено ${width}x$height"
        }
        require(rowStride >= width) {
            "rowStride ($rowStride) не может быть меньше ширины ($width)"
        }
        require(offset >= 0) { "Смещение не может быть отрицательным: $offset" }
        require(pixels.size >= offset + (height - 1).toLong() * rowStride + width) {
            "Буфер мал: ${pixels.size} байт при ${width}x$height, rowStride=$rowStride, offset=$offset"
        }
    }

    /** Яркость пикселя как 0..255. */
    public operator fun get(x: Int, y: Int): Int = pixels[offset + y * rowStride + x].toInt() and 0xFF

    /** Смещение начала строки [y] в исходном буфере — для горячих циклов. */
    public fun rowOffset(y: Int): Int = offset + y * rowStride

    /** Прямой доступ к буферу — для горячих циклов, где индекс уже посчитан через [rowOffset]. */
    public fun at(offset: Int): Int = pixels[offset].toInt() and 0xFF

    /**
     * Вырезает область без копирования пикселей: подложка та же, меняются только границы.
     * Нужно для ROI-сканирования — когда рамка уже нашла объект и следующий кадр
     * достаточно разбирать вокруг него.
     */
    public fun crop(x: Int, y: Int, cropWidth: Int, cropHeight: Int): GrayImage {
        require(x >= 0 && y >= 0 && x + cropWidth <= width && y + cropHeight <= height) {
            "Область (${x},$y ${cropWidth}x$cropHeight) выходит за границы кадра ${width}x$height"
        }
        return GrayImage(
            pixels = pixels,
            width = cropWidth,
            height = cropHeight,
            rowStride = rowStride,
            offset = offset + y * rowStride + x,
        )
    }
}
