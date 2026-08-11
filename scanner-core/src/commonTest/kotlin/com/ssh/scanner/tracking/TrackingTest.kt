package com.ssh.scanner.tracking

import com.ssh.scanner.geometry.ScanPoint
import com.ssh.scanner.geometry.ScanQuad
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private fun quadAt(left: Float, top: Float, side: Float) = ScanQuad(
    topLeft = ScanPoint(left, top),
    topRight = ScanPoint(left + side, top),
    bottomRight = ScanPoint(left + side, top + side),
    bottomLeft = ScanPoint(left, top + side),
)

private fun assertClose(expected: Float, actual: Float, tolerance: Float = 0.01f, message: String = "") {
    assertTrue(abs(expected - actual) <= tolerance, "$message ожидалось $expected, получено $actual")
}

class ViewportMapperTest {

    @Test
    fun mapsFrameCornersOntoViewWhenAspectMatches() {
        val mapper = ViewportMapper(
            frameWidth = 100,
            frameHeight = 200,
            viewWidth = 200f,
            viewHeight = 400f,
        )

        val topLeft = mapper.map(ScanPoint(0f, 0f))
        val bottomRight = mapper.map(ScanPoint(100f, 200f))

        assertClose(0f, topLeft.x)
        assertClose(0f, topLeft.y)
        assertClose(200f, bottomRight.x)
        assertClose(400f, bottomRight.y)
    }

    @Test
    fun rotationSwapsAxes() {
        // Кадр 100×200 при повороте 90° становится 200×100.
        val mapper = ViewportMapper(
            frameWidth = 100,
            frameHeight = 200,
            viewWidth = 200f,
            viewHeight = 100f,
            rotationDegrees = 90,
        )

        // Верхний левый угол кадра уезжает в верхний правый угол вью.
        val mapped = mapper.map(ScanPoint(0f, 0f))

        assertClose(200f, mapped.x)
        assertClose(0f, mapped.y)
    }

    @Test
    fun everyRotationKeepsPointInsideView() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val rotatedIsSideways = rotation % 180 != 0
            val mapper = ViewportMapper(
                frameWidth = 640,
                frameHeight = 480,
                viewWidth = if (rotatedIsSideways) 480f else 640f,
                viewHeight = if (rotatedIsSideways) 640f else 480f,
                rotationDegrees = rotation,
            )
            for (point in listOf(
                ScanPoint(0f, 0f),
                ScanPoint(640f, 0f),
                ScanPoint(640f, 480f),
                ScanPoint(0f, 480f),
                ScanPoint(320f, 240f),
            )) {
                val mapped = mapper.map(point)
                assertTrue(
                    mapped.x >= -0.01f && mapped.y >= -0.01f,
                    "поворот $rotation: точка $point ушла в отрицательные координаты — $mapped",
                )
            }
        }
    }

    @Test
    fun mirroringFlipsHorizontally() {
        val mapper = ViewportMapper(
            frameWidth = 100,
            frameHeight = 100,
            viewWidth = 100f,
            viewHeight = 100f,
            mirrored = true,
        )

        assertClose(100f, mapper.map(ScanPoint(0f, 0f)).x, message = "левый край уходит вправо")
        assertClose(0f, mapper.map(ScanPoint(100f, 0f)).x)
    }

    @Test
    fun mirroringRestoresCornerOrder() {
        // Зеркало меняет обход углов на противоположный: без восстановления порядка «верхний левый»
        // на фронтальной камере оказался бы справа, и анимация тянулась бы не из того угла.
        val mapper = ViewportMapper(
            frameWidth = 100,
            frameHeight = 100,
            viewWidth = 100f,
            viewHeight = 100f,
            mirrored = true,
        )

        val mapped = mapper.map(quadAt(left = 10f, top = 10f, side = 40f))

        assertTrue(mapped.topLeft.x < mapped.topRight.x, "верхний левый обязан остаться слева")
        assertTrue(mapped.topLeft.y < mapped.bottomLeft.y, "верхний левый обязан остаться сверху")
    }

    @Test
    fun fillCenterCropsWhileFitCenterLetterboxes() {
        val fill = ViewportMapper(
            frameWidth = 100, frameHeight = 100,
            viewWidth = 200f, viewHeight = 100f,
            scale = PreviewScale.FillCenter,
        )
        val fit = ViewportMapper(
            frameWidth = 100, frameHeight = 100,
            viewWidth = 200f, viewHeight = 100f,
            scale = PreviewScale.FitCenter,
        )

        // Заполнение растягивает по большей стороне — часть кадра уходит за верх и низ.
        assertTrue(fill.map(ScanPoint(0f, 0f)).y < 0f)
        // Вписывание оставляет поля по бокам, ничего не обрезая.
        assertTrue(fit.map(ScanPoint(0f, 0f)).x > 0f)
        assertClose(0f, fit.map(ScanPoint(0f, 0f)).y)
    }

    @Test
    fun rejectsUnsupportedRotation() {
        assertFailsWith<IllegalArgumentException> {
            ViewportMapper(100, 100, 100f, 100f, rotationDegrees = 45)
        }
    }
}

class QuadSmootherTest {

    @Test
    fun firstQuadPassesThroughUnchanged() {
        val quad = quadAt(0f, 0f, 10f)

        assertEquals(quad, QuadSmoother().smooth(quad))
    }

    @Test
    fun convergesTowardsTargetWithoutOvershooting() {
        val smoother = QuadSmoother(factor = 0.5f)
        smoother.smooth(quadAt(0f, 0f, 10f))

        val target = quadAt(100f, 0f, 10f)
        val first = smoother.smooth(target)
        val second = smoother.smooth(target)

        assertClose(50f, first.topLeft.x, message = "половина пути за кадр")
        assertClose(75f, second.topLeft.x)
        assertTrue(second.topLeft.x < 100f, "сглаживание не должно проскакивать цель")
    }

    @Test
    fun dampensJitterOfStationaryTarget() {
        val smoother = QuadSmoother(factor = 0.3f)
        val base = quadAt(50f, 50f, 20f)
        smoother.smooth(base)

        // Дрожание ±1 пиксель на неподвижном коде.
        val jitter = listOf(1f, -1f, 1f, -1f, 1f, -1f)
        var last = base
        jitter.forEachIndexed { index, offset ->
            last = smoother.smooth(quadAt(50f + offset, 50f + offset, 20f))
            assertTrue(
                abs(last.topLeft.x - 50f) < 1f,
                "шаг $index: сглаженная рамка гуляет не меньше сырой",
            )
        }
    }

    @Test
    fun keepsCornerCorrespondenceWhenDetectorRelabelsThem() {
        // Тот же физический контур, но углы приехали со сдвигом на один — так бывает, когда код
        // поворачивается около 45° и «верхним левым» становится соседний угол.
        val smoother = QuadSmoother(factor = 1f)
        val original = quadAt(0f, 0f, 10f)
        smoother.smooth(original)

        val relabelled = ScanQuad(
            topLeft = original.topRight,
            topRight = original.bottomRight,
            bottomRight = original.bottomLeft,
            bottomLeft = original.topLeft,
        )
        val smoothed = smoother.smooth(relabelled)

        assertEquals(original.topLeft, smoothed.topLeft, "углы должны быть переставлены обратно")
        assertEquals(original.bottomRight, smoothed.bottomRight)
    }

    @Test
    fun rejectsFactorOutsideRange() {
        assertFailsWith<IllegalArgumentException> { QuadSmoother(factor = 0f) }
        assertFailsWith<IllegalArgumentException> { QuadSmoother(factor = 1.5f) }
    }
}

class LockTrackerTest {

    private val detection = LockTracker.Detection(quadAt(10f, 10f, 30f), payload = "PAYLOAD")

    @Test
    fun startsInSearchingState() {
        assertIs<LockState.Searching>(LockTracker().onFrame(nowMillis = 0, detection = null))
    }

    @Test
    fun locksOnDetection() {
        val state = LockTracker().onFrame(nowMillis = 0, detection = detection)

        assertIs<LockState.Locked>(state)
        assertEquals("PAYLOAD", state.payload)
    }

    @Test
    fun holdsLockThroughShortLoss() {
        val tracker = LockTracker(holdMillis = 400)
        tracker.onFrame(nowMillis = 0, detection = detection)

        // Детектор потерял код на несколько кадров — рамка обязана остаться на месте.
        assertIs<LockState.Losing>(tracker.onFrame(nowMillis = 100, detection = null))
        assertIs<LockState.Losing>(tracker.onFrame(nowMillis = 399, detection = null))
    }

    @Test
    fun releasesLockAfterHoldExpires() {
        val tracker = LockTracker(holdMillis = 400)
        tracker.onFrame(nowMillis = 0, detection = detection)

        assertIs<LockState.Searching>(tracker.onFrame(nowMillis = 401, detection = null))
    }

    @Test
    fun recoveredDetectionExtendsTheHold() {
        val tracker = LockTracker(holdMillis = 400)
        tracker.onFrame(nowMillis = 0, detection = detection)
        tracker.onFrame(nowMillis = 300, detection = null)
        tracker.onFrame(nowMillis = 350, detection = detection)

        assertIs<LockState.Losing>(tracker.onFrame(nowMillis = 700, detection = null))
    }

    @Test
    fun switchingPayloadJumpsInsteadOfSliding() {
        // Другой код в кадре — это другой объект. Тянуть рамку от старого к новому нельзя:
        // она проехала бы через пустоту между ними.
        val tracker = LockTracker()
        tracker.onFrame(nowMillis = 0, detection = detection)

        val other = LockTracker.Detection(quadAt(200f, 200f, 30f), payload = "OTHER")
        val state = tracker.onFrame(nowMillis = 16, detection = other)

        assertIs<LockState.Locked>(state)
        assertClose(200f, state.quad.topLeft.x, message = "рамка обязана оказаться сразу на новом коде")
    }

    @Test
    fun resetReturnsToSearching() {
        val tracker = LockTracker()
        tracker.onFrame(nowMillis = 0, detection = detection)
        tracker.reset()

        assertIs<LockState.Searching>(tracker.onFrame(nowMillis = 10, detection = null))
    }
}
