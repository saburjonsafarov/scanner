package com.ssh.scanner.tracking

import com.ssh.scanner.geometry.ScanPoint
import com.ssh.scanner.geometry.ScanQuad

/**
 * Сглаживает контур между кадрами.
 *
 * Детектор находит углы с точностью до пикселя, и эта точность гуляет от кадра к кадру: рамка,
 * привязанная к сырым углам, мелко трясётся даже на неподвижном коде. Экспоненциальное сглаживание
 * гасит дрожание, оставляя реакцию на настоящее движение.
 */
public class QuadSmoother(
    private val factor: Float = DEFAULT_FACTOR,
) {
    private var current: ScanQuad? = null

    init {
        require(factor > 0f && factor <= 1f) { "Коэффициент сглаживания лежит в (0, 1], дано $factor" }
    }

    public fun smooth(target: ScanQuad): ScanQuad {
        val previous = current
        val aligned = if (previous == null) target else alignCorners(previous, target)
        val result = if (previous == null) {
            aligned
        } else {
            ScanQuad(
                topLeft = blend(previous.topLeft, aligned.topLeft),
                topRight = blend(previous.topRight, aligned.topRight),
                bottomRight = blend(previous.bottomRight, aligned.bottomRight),
                bottomLeft = blend(previous.bottomLeft, aligned.bottomLeft),
            )
        }
        current = result
        return result
    }

    public fun reset() {
        current = null
    }

    private fun blend(from: ScanPoint, to: ScanPoint): ScanPoint = ScanPoint(
        x = from.x + (to.x - from.x) * factor,
        y = from.y + (to.y - from.y) * factor,
    )

    /**
     * Сопоставляет углы нового контура с предыдущим циклическим сдвигом.
     *
     * Детектор нумерует углы от верхнего левого **угла символа**, а не кадра. Стоит коду повернуться
     * около 45°, и тот же физический угол приезжает под другим индексом: рамка бы прыгнула на 90°,
     * хотя объект едва шевельнулся. Перебор четырёх сдвигов выбирает тот, при котором углы ближе
     * всего к прежним, — обход по часовой сохраняется, зеркального сопоставления не возникает.
     */
    private fun alignCorners(previous: ScanQuad, target: ScanQuad): ScanQuad {
        val corners = target.corners
        var bestShift = 0
        var bestDistance = Float.MAX_VALUE

        for (shift in 0 until CORNERS) {
            var distance = 0f
            for (index in 0 until CORNERS) {
                distance += previous.corners[index].distanceTo(corners[(index + shift) % CORNERS])
            }
            if (distance < bestDistance) {
                bestDistance = distance
                bestShift = shift
            }
        }
        if (bestShift == 0) return target

        val shifted = List(CORNERS) { corners[(it + bestShift) % CORNERS] }
        return ScanQuad(shifted[0], shifted[1], shifted[2], shifted[3])
    }

    private companion object {
        const val CORNERS = 4

        /**
         * Доля пути к новому положению за кадр. 0.35 при 30 кадрах в секунду — это примерно
         * 100 мс на «догнать» цель: дрожание уже незаметно, а задержка ещё не читается как тормоза.
         */
        const val DEFAULT_FACTOR = 0.35f
    }
}

/** Состояние захвата — то, по чему интерфейс решает, как рисовать рамку. */
public sealed interface LockState {

    /** Цели нет: рисуется дежурная рамка в центре. */
    public data object Searching : LockState

    /** Цель в кадре: рамка обтягивает контур. */
    public data class Locked(val quad: ScanQuad, val payload: String) : LockState

    /**
     * Цель пропала, но захват удерживается.
     *
     * Именно это состояние делает рамку спокойной: детектор регулярно теряет код на один-два кадра
     * (моргнул автофокус, дрогнула рука), и без удержания рамка мигала бы на каждой такой потере.
     */
    public data class Losing(val quad: ScanQuad, val payload: String) : LockState
}

/**
 * Ведёт захват цели во времени: подтверждение находки, сглаживание, гистерезис потери.
 *
 * Класс намеренно не знает ни про Compose, ни про корутины — это чистая функция от
 * «текущее время и что нашлось в кадре». Поэтому вся логика анимации проверяется обычными тестами,
 * без единого кадра на экране.
 */
public class LockTracker(
    private val holdMillis: Long = DEFAULT_HOLD_MILLIS,
    private val smoother: QuadSmoother = QuadSmoother(),
) {
    private var payload: String? = null
    private var quad: ScanQuad? = null
    private var lastSeenMillis: Long = 0

    init {
        require(holdMillis >= 0) { "Время удержания не может быть отрицательным" }
    }

    /**
     * @param detection найденный в этом кадре объект или `null`, если кадр пустой
     * @return состояние, в котором интерфейс должен отрисовать рамку
     */
    public fun onFrame(nowMillis: Long, detection: Detection?): LockState {
        if (detection != null) {
            // Сменилось содержимое — это другой объект, и тянуть к нему рамку от старого нельзя.
            if (detection.payload != payload) smoother.reset()
            payload = detection.payload
            quad = smoother.smooth(detection.quad)
            lastSeenMillis = nowMillis
            return LockState.Locked(quad = quad!!, payload = detection.payload)
        }

        val heldQuad = quad
        val heldPayload = payload
        if (heldQuad == null || heldPayload == null) return LockState.Searching

        return if (nowMillis - lastSeenMillis <= holdMillis) {
            LockState.Losing(quad = heldQuad, payload = heldPayload)
        } else {
            reset()
            LockState.Searching
        }
    }

    public fun reset() {
        payload = null
        quad = null
        smoother.reset()
    }

    /** Что нашлось в кадре. */
    public data class Detection(val quad: ScanQuad, val payload: String)

    private companion object {
        /**
         * Сколько держать захват без подтверждения. 400 мс — примерно дюжина кадров: пережидает
         * перефокусировку и дрожание руки, но не создаёт ощущения, что рамка «прилипла» к месту,
         * откуда код уже убрали.
         */
        const val DEFAULT_HOLD_MILLIS = 400L
    }
}
