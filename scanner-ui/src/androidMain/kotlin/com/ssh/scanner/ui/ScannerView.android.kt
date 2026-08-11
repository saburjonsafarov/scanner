package com.ssh.scanner.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ssh.scanner.ScanResult
import com.ssh.scanner.Scanner
import com.ssh.scanner.ScannerConfig
import com.ssh.scanner.camera.ScannerImageAnalyzer
import com.ssh.scanner.tracking.LockState
import com.ssh.scanner.tracking.LockTracker
import com.ssh.scanner.tracking.PreviewScale
import com.ssh.scanner.tracking.ViewportMapper
import java.util.concurrent.Executors

/** Какой камерой сканировать. */
public enum class CameraLens { Back, Front }

/**
 * Готовый экран сканера: превью камеры и рамка поверх него.
 *
 * ⚠️ Файл **не собирался**: писался в окружении без Android SDK. Требуется проверка на устройстве.
 *
 * Разрешение анализа намеренно занижено до 1280×720. Разбирать 4K-кадр целиком незачем: QR-модуль
 * при съёмке с руки занимает несколько пикселей и в 720p, а объём работы растёт с площадью кадра.
 */
@Composable
public fun ScannerView(
    config: ScannerConfig,
    modifier: Modifier = Modifier,
    lens: CameraLens = CameraLens.Back,
    style: ScannerOverlayStyle = ScannerOverlayStyle(),
    onResult: (ScanResult) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scanner = remember(config) { Scanner(config) }
    val tracker = remember(config) { LockTracker() }

    var lockState by remember { mutableStateOf<LockState>(LockState.Searching) }
    var mapper by remember { mutableStateOf<ViewportMapper?>(null) }
    var viewSize by remember { mutableStateOf(0 to 0) }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) { onDispose { analysisExecutor.shutdown() } }

    DisposableEffect(lens, config) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        val mirrored = lens == CameraLens.Front

        providerFuture.addListener({
            val provider = providerFuture.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }

            val analysis = ImageAnalysis.Builder()
                // Копить кадры бессмысленно: разбирать надо самый свежий, остальные уже неактуальны.
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                android.util.Size(ANALYSIS_WIDTH, ANALYSIS_HEIGHT),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            ),
                        )
                        .build(),
                )
                .build()

            analysis.setAnalyzer(
                analysisExecutor,
                ScannerImageAnalyzer(scanner = scanner, mirrored = mirrored) { results, frame ->
                    val (width, height) = viewSize
                    if (width > 0 && height > 0) {
                        mapper = ViewportMapper(
                            frameWidth = frame.image.width,
                            frameHeight = frame.image.height,
                            viewWidth = width.toFloat(),
                            viewHeight = height.toFloat(),
                            rotationDegrees = frame.rotationDegrees,
                            mirrored = frame.mirrored,
                            scale = PreviewScale.FillCenter,
                        )
                    }
                    val best = results.firstOrNull()
                    val state = tracker.onFrame(
                        nowMillis = System.currentTimeMillis(),
                        detection = best?.let {
                            LockTracker.Detection(quad = it.quad, payload = it.content.raw)
                        },
                    )
                    lockState = state
                    if (best != null && state is LockState.Locked) onResult(best)
                },
            )

            provider.unbindAll()
            provider.bindToLifecycle(
                lifecycleOwner,
                if (mirrored) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                analysis,
            )
        }, ContextCompat.getMainExecutor(context))

        onDispose { runCatching { providerFuture.get().unbindAll() } }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { previewView },
            modifier = Modifier.matchParentSize(),
            update = { view -> viewSize = view.width to view.height },
        )
        ScanningLine(visible = lockState is LockState.Searching)
        ScannerOverlay(state = lockState, mapper = mapper, style = style)
    }
}

private const val ANALYSIS_WIDTH = 1280
private const val ANALYSIS_HEIGHT = 720
