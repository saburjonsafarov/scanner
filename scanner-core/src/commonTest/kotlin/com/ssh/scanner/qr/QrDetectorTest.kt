package com.ssh.scanner.qr

import com.ssh.scanner.image.AdaptiveBinarizer
import com.ssh.scanner.image.GrayImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class QrDetectorTest {

    private fun detect(image: GrayImage): List<DetectedQr> =
        QrDetector.detect(AdaptiveBinarizer().binarize(image))

    @Test
    fun findsAndDecodesCleanSymbol() {
        val text = "HTTPS://SSHORG.COM"
        val image = QrTestRenderer.render(QrTestEncoder.encode(text))

        val found = detect(image)

        assertEquals(1, found.size)
        assertEquals(text, found.first().text)
    }

    @Test
    fun decodesRotatedSymbol() {
        val text = "ROTATION TEST"
        for (angle in listOf(10f, 25f, 45f, 75f, 120f, 200f, 330f)) {
            val image = QrTestRenderer.render(
                QrTestEncoder.encode(text),
                rotationDegrees = angle,
            )

            assertEquals(text, detect(image).firstOrNull()?.text, "поворот $angle°")
        }
    }

    @Test
    fun decodesSymbolUnderPerspective() {
        // Верхний край сужается — так выглядит код, снятый под наклоном к плоскости.
        val text = "PERSPECTIVE"
        for (perspective in listOf(0.05f, 0.10f, 0.15f)) {
            val image = QrTestRenderer.render(
                QrTestEncoder.encode(text, minVersion = 3),
                perspective = perspective,
            )

            assertEquals(text, detect(image).firstOrNull()?.text, "перспектива $perspective")
        }
    }

    @Test
    fun decodesUnderUnevenLightingAndNoise() {
        val text = "NOISY LIGHT"
        val image = QrTestRenderer.render(
            QrTestEncoder.encode(text),
            lightingGradient = 120,
            noiseAmplitude = 18,
        )

        assertEquals(text, detect(image).firstOrNull()?.text)
    }

    @Test
    fun decodesSlightlyBlurredSymbol() {
        val text = "BLUR TEST"
        val image = QrTestRenderer.render(
            QrTestEncoder.encode(text),
            moduleSize = 8,
            blurRadius = 2,
        )

        assertEquals(text, detect(image).firstOrNull()?.text)
    }

    @Test
    fun decodesLargerVersionsWithAlignmentPattern() {
        // С версии 2 появляется выравниватель, и гомография строится уже по нему, а не по
        // экстраполяции — этот тест как раз про дальний угол сетки.
        for (version in listOf(2, 4, 7, 10)) {
            val text = "ALIGNMENT V$version " + "0".repeat(version * 4)
            val image = QrTestRenderer.render(
                QrTestEncoder.encode(text, minVersion = version),
                rotationDegrees = 12f,
                perspective = 0.06f,
            )

            assertEquals(text, detect(image).firstOrNull()?.text, "версия $version")
        }
    }

    @Test
    fun reportsQuadCoveringTheSymbol() {
        val moduleSize = 6
        val matrix = QrTestEncoder.encode("QUAD")
        val image = QrTestRenderer.render(matrix, moduleSize = moduleSize)

        val quad = detect(image).first().quad

        // Символ отрисован по центру кадра со стороной width * moduleSize — рамка обязана лечь
        // на него, а не на весь кадр и не на его часть.
        val expectedSide = (matrix.width * moduleSize).toFloat()
        val topSide = quad.topLeft.distanceTo(quad.topRight)
        val leftSide = quad.topLeft.distanceTo(quad.bottomLeft)

        assertTrue(
            kotlin.math.abs(topSide - expectedSide) < moduleSize * 2,
            "верхняя сторона рамки $topSide, ожидалось около $expectedSide",
        )
        assertTrue(
            kotlin.math.abs(leftSide - expectedSide) < moduleSize * 2,
            "левая сторона рамки $leftSide, ожидалось около $expectedSide",
        )
        assertTrue(
            quad.center.x > image.width * 0.35f && quad.center.x < image.width * 0.65f,
            "центр рамки должен быть примерно в центре кадра, получено ${quad.center}",
        )
    }

    @Test
    fun cornersAreOrderedClockwiseFromTopLeft() {
        val image = QrTestRenderer.render(QrTestEncoder.encode("ORDER"), rotationDegrees = 8f)

        val quad = detect(image).first().quad

        assertTrue(quad.topLeft.x < quad.topRight.x, "верхний левый левее верхнего правого")
        assertTrue(quad.topLeft.y < quad.bottomLeft.y, "верхний левый выше нижнего левого")
        assertTrue(quad.bottomRight.x > quad.bottomLeft.x)
    }

    @Test
    fun returnsNothingOnEmptyFrame() {
        val pixels = ByteArray(200 * 200) { 210.toByte() }

        assertTrue(detect(GrayImage(pixels, 200, 200)).isEmpty())
    }

    @Test
    fun returnsNothingOnNoiseOnly() {
        val random = kotlin.random.Random(7)
        val pixels = ByteArray(200 * 200) { random.nextInt(0, 256).toByte() }

        assertTrue(detect(GrayImage(pixels, 200, 200)).isEmpty())
    }
}
