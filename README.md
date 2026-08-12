# scanner

Сканер кодов для Kotlin Multiplatform и Compose Multiplatform, написанный **без сторонних библиотек
распознавания**: ни ML Kit, ни ZXing. Декодер — свой, от поиска искателей до Рида — Соломона.

Работает в Android, в общем коде Compose Multiplatform и в нативном iOS-проекте.

## Что умеет сейчас

- **QR-коды** версий 1…10, все четыре уровня коррекции, все восемь масок; режимы numeric,
  alphanumeric и byte (UTF-8 с откатом на ISO-8859-1), указатели ECI.
- **Разбор содержимого**: ссылки, `tel:`, `WIFI:`, MECARD, vCard; телефоны нормализуются в E.164.
- **Цели сканирования флагами**: набор `ScanTarget` определяет и то, какие декодеры строятся,
  и то, какие результаты публикуются.
- **Контур найденного объекта** — четыре угла в координатах кадра, готовые для анимации рамки.
- **Трекинг для интерфейса**: перевод координат кадра во вью, сглаживание контура, удержание
  захвата при кратковременной потере.

## Устройство

| Модуль | Зависит от платформы | Назначение |
|---|---|---|
| `scanner-core` | нет | декодер, геометрия, разбор содержимого, математика анимации |
| `scanner-camera` | Android, iOS | получение кадров, `GrayFrame` |
| `scanner-ui` | Compose Multiplatform | превью и анимированная рамка |
| `sample-android` | Android | демонстрация |

Ядро не зависит ни от Compose, ни от Android SDK — поэтому нативный iOS-проект может взять
только его, а Android-приложение потребляет обычный JVM-артефакт.

## Быстрый старт

```kotlin
val scanner = Scanner(
    ScannerConfig(
        targets = setOf(ScanTarget.QrCode, ScanTarget.PhoneNumber),
        defaultCountryCode = "992",
    ),
)

// image — кадр в градациях серого; на Android это Y-плоскость YUV_420_888 без конвертации.
scanner.scan(image).forEach { result ->
    when (val content = result.content) {
        is ScanContent.Phone -> println(content.e164)
        is ScanContent.Url -> println(content.address)
        else -> println(content.raw)
    }
    println(result.quad) // контур объекта — под рамку в интерфейсе
}
```

Готовый экран на Android:

```kotlin
ScannerView(config = ScannerConfig(targets = setOf(ScanTarget.QrCode))) { result ->
    // сюда приходит результат в момент захвата
}
```

## Два пути для нативного iOS

1. **Только декодер.** `./gradlew :scanner-core:assembleScannerCoreXCFramework` собирает
   `ScannerCore.xcframework`; интерфейс пишется на SwiftUI поверх него.
2. **Готовый экран.** `:scanner-ui` отдаёт `UIViewController` через `ComposeUIViewController`,
   он оборачивается в `UIViewControllerRepresentable`.

## Сборка

```bash
./gradlew build                              # библиотеки; нужен Android SDK, для iOS — macOS
./gradlew -Pscanner.targets=core :scanner-core:jvmTest   # только ядро, где угодно
./gradlew -p sample-android assembleDebug    # демо-приложение (отдельный композитный билд)
```

Сэмпл — самостоятельный проект: в Android Studio открывается каталог `sample-android`,
а не корень (на AGP 9 app-модуль со встроенным Kotlin не живёт в одном билде
с KMP-библиотеками — подробности в `sample-android/settings.gradle.kts`).

Гейт `-Pscanner.targets=core` оставляет в сборке одно ядро на JVM: оно не зависит ни от Android
SDK, ни от репозитория Google, поэтому тесты гоняются в любом окружении, включая CI без Android.

## Состояние

Ядро покрыто тестами и проверено на JVM: помимо обычных проверок, декодер гоняется на синтетически
испорченных кадрах — поворот, перспектива, неровный свет, шум, расфокус.

Кодер Рида — Соломона сверен с опубликованным примером из ISO/IEC 18004, а таблица блоков коррекции
проверяется против ёмкости символа, посчитанной независимо из его геометрии.

**Модули `scanner-camera`, `scanner-ui` и `sample-android` ещё ни разу не собирались** — они
писались в окружении без Android SDK. До первого запуска на устройстве считать их непроверенными.

Не поддержаны пока: версии QR 11…40, режим кандзи, структурное объединение, одномерные штрихкоды,
номер карты и MRZ.

## Лицензия

Apache 2.0.
