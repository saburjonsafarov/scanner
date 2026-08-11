package com.ssh.scanner

import com.ssh.scanner.geometry.ScanQuad
import com.ssh.scanner.image.AdaptiveBinarizer
import com.ssh.scanner.image.Binarizer
import com.ssh.scanner.image.GrayImage
import com.ssh.scanner.qr.QrDetector

/** Найденный объект: что прочитано, где лежит и в каком формате было записано. */
public data class ScanResult(
    val content: ScanContent,
    val quad: ScanQuad,
    val format: ScanFormat,
)

/**
 * Точка входа библиотеки: кадр на входе, найденные объекты на выходе.
 *
 * Класс ничего не знает ни о камере, ни о UI — только `GrayImage`. Благодаря этому он одинаково
 * работает под Android, iOS и в тестах, а нативный iOS-проект может взять его без Compose вовсе.
 */
public class Scanner(
    private val config: ScannerConfig = ScannerConfig(),
    private val binarizer: Binarizer = AdaptiveBinarizer(),
) {
    private val decoders = DetectorPlanner.plan(config.targets)

    /**
     * Разбирает один кадр.
     *
     * Возвращает пустой список, если ничего не найдено, — это норма для потока кадров, а не ошибка.
     * Исключений не бросает: кадр из камеры бывает каким угодно, и падать на нём нельзя.
     */
    public fun scan(image: GrayImage): List<ScanResult> {
        if (DecoderKind.Qr !in decoders) return emptyList()

        val matrix = binarizer.binarize(image)
        val found = QrDetector.detect(matrix).map { detected ->
            ScanResult(
                content = ContentParser.parse(detected.text, config.defaultCountryCode),
                quad = detected.quad,
                format = ScanFormat.QrCode,
            )
        }

        val matching = found.filter { matches(it.content) }
        return when (config.multiScan) {
            MultiScan.All -> matching
            MultiScan.Single -> matching
                .minByOrNull { result ->
                    val center = result.quad.center
                    val frameCenterX = image.width / 2f
                    val frameCenterY = image.height / 2f
                    val dx = center.x - frameCenterX
                    val dy = center.y - frameCenterY
                    dx * dx + dy * dy
                }
                ?.let { listOf(it) }
                .orEmpty()
        }
    }

    /**
     * Отсев по целям.
     *
     * `QrCode` в целях означает «отдавай любой QR», поэтому проверяется первым: с ним набор
     * остальных целей уже ничего не сужает.
     */
    private fun matches(content: ScanContent): Boolean {
        if (ScanTarget.QrCode in config.targets) return true
        return config.targets.any { target ->
            when (target) {
                ScanTarget.Url -> content is ScanContent.Url
                ScanTarget.PhoneNumber -> content is ScanContent.Phone ||
                    (content is ScanContent.Contact && content.phones.isNotEmpty())

                ScanTarget.Wifi -> content is ScanContent.Wifi
                ScanTarget.Contact -> content is ScanContent.Contact
                ScanTarget.QrCode -> true
            }
        }
    }
}
