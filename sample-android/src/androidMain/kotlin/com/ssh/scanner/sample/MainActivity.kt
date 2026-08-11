package com.ssh.scanner.sample

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.ssh.scanner.ScanContent
import com.ssh.scanner.ScanTarget
import com.ssh.scanner.ScannerConfig
import com.ssh.scanner.ui.ScannerView

/**
 * Демонстрация библиотеки: сканер на весь экран и чипы выбора целей.
 *
 * Чипы здесь не для красоты — они показывают главное свойство API: набор целей меняется на лету,
 * и одна и та же камера начинает отдавать телефоны вместо ссылок.
 *
 * ⚠️ Не собиралось: писалось в окружении без Android SDK.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { Surface { ScannerScreen() } } }
    }
}

@Composable
private fun ScannerScreen() {
    var granted by remember { mutableStateOf(false) }
    var targets by remember { mutableStateOf(setOf(ScanTarget.QrCode)) }
    var lastResult by remember { mutableStateOf<String?>(null) }

    val context = androidx.compose.ui.platform.LocalContext.current
    val launcher = androidx.activity.compose.rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted = it }

    androidx.compose.runtime.LaunchedEffect(Unit) {
        granted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) launcher.launch(Manifest.permission.CAMERA)
    }

    if (!granted) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("Нужен доступ к камере")
        }
        return
    }

    Box(Modifier.fillMaxSize()) {
        ScannerView(
            config = ScannerConfig(targets = targets, defaultCountryCode = "992"),
            modifier = Modifier.fillMaxSize(),
        ) { result ->
            lastResult = when (val content = result.content) {
                is ScanContent.Phone -> "Телефон: ${content.e164}"
                is ScanContent.Url -> "Ссылка: ${content.address}"
                is ScanContent.Wifi -> "Wi-Fi: ${content.ssid}"
                is ScanContent.Contact -> "Контакт: ${content.name ?: "без имени"}"
                is ScanContent.Text -> content.raw
            }
        }

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .systemBarsPadding()
                .padding(16.dp)
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            lastResult?.let { Text(text = it, style = MaterialTheme.typography.bodyLarge) }

            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(ScanTarget.entries) { target ->
                    FilterChip(
                        selected = target in targets,
                        onClick = {
                            val updated = if (target in targets) targets - target else targets + target
                            // Пустой набор целей запрещён конфигурацией — оставляем последнюю цель.
                            if (updated.isNotEmpty()) targets = updated
                        },
                        label = { Text(target.name) },
                    )
                }
            }
        }
    }
}
