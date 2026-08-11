package com.ssh.scanner.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.VectorConverter
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.ssh.scanner.geometry.ScanPoint
import com.ssh.scanner.geometry.ScanQuad
import com.ssh.scanner.tracking.LockState
import com.ssh.scanner.tracking.ViewportMapper
import kotlinx.coroutines.launch

/**
 * Оформление рамки.
 *
 * Все размеры объявлены в [Dp] и переводятся в пиксели на месте отрисовки. Голое число внутри
 * `DrawScope` — это пиксели, поэтому «толщина 3» дала бы на экране x3 вдвое более тонкую линию,
 * чем на x1.5: задумка тем незаметнее, чем лучше экран у пользователя.
 */
public data class ScannerOverlayStyle(
    val searchingColor: Color = Color.White,
    val lockedColor: Color = Color(0xFF4CD964),
    val scrimColor: Color = Color.Black.copy(alpha = 0.55f),
    val strokeWidth: Dp = 3.dp,
    /** Доля стороны, которую занимает уголок дежурной рамки. */
    val idleCornerFraction: Float = 0.22f,
    /** Доля стороны при захвате: уголки почти смыкаются в сплошную рамку. */
    val lockedCornerFraction: Float = 0.5f,
    /** Сторона дежурной рамки как доля меньшей стороны вью. */
    val reticleFraction: Float = 0.62f,
)

/**
 * Рамка поверх превью: ищет — стоит в центре, нашла — обтягивает объект.
 *
 * Эффект «врапа» держится на трёх вещах, и все три важны в равной мере:
 *
 * 1. **Углы анимируются по отдельности.** Рамка не прямоугольник, который переезжает и меняет
 *    размер, а четыре независимые точки. Поэтому она умеет принимать форму кода, снятого под углом,
 *    и переход выглядит как натягивание, а не как подстановка другой фигуры.
 * 2. **Пружина с демпфированием около критического** — см. [cornerSpring].
 * 3. **Удержание при потере цели** ([LockState.Losing]): детектор регулярно проваливает кадр-другой,
 *    и без удержания рамка мигала бы на каждой такой потере.
 *
 * @param mapper перевод координат кадра во вью. Без него контур считается уже во вью-координатах —
 *   это удобно для превью и тестов, но с живой камерой [ViewportMapper] обязателен.
 */
@Composable
public fun ScannerOverlay(
    state: LockState,
    modifier: Modifier = Modifier,
    mapper: ViewportMapper? = null,
    style: ScannerOverlayStyle = ScannerOverlayStyle(),
) {
    var canvasSize by remember { mutableStateOf(Size.Zero) }
    var placed by remember { mutableStateOf(false) }
    val corners = remember {
        List(CORNER_COUNT) { Animatable(Offset.Zero, Offset.VectorConverter) }
    }
    val cornerFraction = remember { Animatable(style.idleCornerFraction) }
    val lockProgress = remember { Animatable(0f) }

    LaunchedEffect(state, mapper, canvasSize) {
        if (canvasSize == Size.Zero) return@LaunchedEffect

        val targets = targetCorners(state, mapper, canvasSize, style)
        if (!placed) {
            // Первая укладка — без анимации: иначе рамка приедет из левого верхнего угла экрана.
            targets.forEachIndexed { index, target -> corners[index].snapTo(target) }
            placed = true
        } else {
            targets.forEachIndexed { index, target ->
                launch { corners[index].animateTo(target, cornerSpring()) }
            }
        }

        val locked = state !is LockState.Searching
        launch {
            cornerFraction.animateTo(
                targetValue = if (locked) style.lockedCornerFraction else style.idleCornerFraction,
                animationSpec = spring(stiffness = Spring.StiffnessLow),
            )
        }
        launch { lockProgress.animateTo(if (locked) 1f else 0f, tween(durationMillis = 180)) }
    }

    Canvas(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = Size(it.width.toFloat(), it.height.toFloat()) },
    ) {
        if (!placed) return@Canvas
        drawFrame(
            corners = corners.map { it.value },
            cornerFraction = cornerFraction.value,
            lockProgress = lockProgress.value,
            style = style,
        )
    }
}

/**
 * Пружина рамки.
 *
 * Демпфирование 0.78 — чуть ниже критического: рамка приходит к цели без заметного перелёта, но
 * сохраняет живость. Заметно ниже — начинается предельный цикл: детектор подталкивает рамку каждым
 * кадром, затухание не успевает съесть остаточную скорость, и она дрожит вокруг цели.
 */
private fun cornerSpring() = spring<Offset>(
    dampingRatio = 0.78f,
    stiffness = Spring.StiffnessMediumLow,
)

private const val CORNER_COUNT = 4

private fun targetCorners(
    state: LockState,
    mapper: ViewportMapper?,
    canvasSize: Size,
    style: ScannerOverlayStyle,
): List<Offset> {
    val quad = when (state) {
        is LockState.Locked -> state.quad
        is LockState.Losing -> state.quad
        LockState.Searching -> null
    }
    val resolved = quad?.let { mapper?.map(it) ?: it } ?: reticle(canvasSize, style.reticleFraction)
    return resolved.corners.map { Offset(it.x, it.y) }
}

/** Дежурная рамка: квадрат по центру вью. */
private fun reticle(size: Size, fraction: Float): ScanQuad {
    val side = minOf(size.width, size.height) * fraction
    val left = (size.width - side) / 2f
    val top = (size.height - side) / 2f
    return ScanQuad(
        topLeft = ScanPoint(left, top),
        topRight = ScanPoint(left + side, top),
        bottomRight = ScanPoint(left + side, top + side),
        bottomLeft = ScanPoint(left, top + side),
    )
}

private fun DrawScope.drawFrame(
    corners: List<Offset>,
    cornerFraction: Float,
    lockProgress: Float,
    style: ScannerOverlayStyle,
) {
    val path = Path().apply {
        moveTo(corners[0].x, corners[0].y)
        for (index in 1 until CORNER_COUNT) lineTo(corners[index].x, corners[index].y)
        close()
    }

    // Затемняем всё, кроме контура: подсказка «смотри сюда» без единого слова.
    clipPath(path, clipOp = ClipOp.Difference) {
        drawRect(color = style.scrimColor, size = size)
    }

    val color = lerpColor(style.searchingColor, style.lockedColor, lockProgress)
    val stroke = Stroke(width = style.strokeWidth.toPx())

    // Уголки рисуются вдоль настоящих рёбер контура, а не вдоль сторон описанного прямоугольника:
    // иначе на коде, снятом под углом, скобки висели бы рядом с ним, а не на нём.
    for (index in 0 until CORNER_COUNT) {
        val current = corners[index]
        val next = corners[(index + 1) % CORNER_COUNT]
        val previous = corners[(index + CORNER_COUNT - 1) % CORNER_COUNT]

        drawPath(
            path = Path().apply {
                moveTo(
                    lerp(current.x, previous.x, cornerFraction),
                    lerp(current.y, previous.y, cornerFraction),
                )
                lineTo(current.x, current.y)
                lineTo(
                    lerp(current.x, next.x, cornerFraction),
                    lerp(current.y, next.y, cornerFraction),
                )
            },
            color = color,
            style = stroke,
        )
    }
}

private fun lerp(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction

private fun lerpColor(from: Color, to: Color, fraction: Float): Color = Color(
    red = lerp(from.red, to.red, fraction),
    green = lerp(from.green, to.green, fraction),
    blue = lerp(from.blue, to.blue, fraction),
    alpha = lerp(from.alpha, to.alpha, fraction),
)

/**
 * Полоса, пробегающая по дежурной рамке, пока цели нет.
 *
 * Смысл не в украшении: неподвижная рамка выглядит как зависшее приложение, и пользователь начинает
 * трясти телефоном. Движение сообщает «я работаю, ищу».
 */
@Composable
public fun ScanningLine(
    visible: Boolean,
    modifier: Modifier = Modifier,
    color: Color = Color.White.copy(alpha = 0.7f),
    thickness: Dp = 2.dp,
    reticleFraction: Float = 0.62f,
) {
    val transition = rememberInfiniteTransition(label = "scanning-line")
    val progress by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(durationMillis = 2200)),
        label = "scanning-line-progress",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        if (!visible) return@Canvas
        val side = minOf(size.width, size.height) * reticleFraction
        val left = (size.width - side) / 2f
        val top = (size.height - side) / 2f
        // Маятник вместо пилы: скачок с конца в начало читался бы как рывок.
        val position = top + side * triangleWave(progress)

        drawLine(
            color = color,
            start = Offset(left, position),
            end = Offset(left + side, position),
            strokeWidth = thickness.toPx(),
        )
    }
}

private fun triangleWave(progress: Float): Float =
    if (progress <= 0.5f) progress * 2f else (1f - progress) * 2f
