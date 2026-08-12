// Сэмпл — чистый Android-модуль и ОТДЕЛЬНЫЙ билд (см. settings.gradle.kts рядом).
// Открывать его в IDE как самостоятельный проект: File → Open → каталог sample-android.
//
// В plugins НЕТ ни одного Kotlin/Compose-плагина, и это принципиально: kotlin.plugin.compose
// тянет зависимостью весь KGP, тот попадает в класспут app-модуля, и AGP 9 при инициализации
// встроенного Kotlin находит внешний KGP и падает на выпиленном BaseVariant (та же механика,
// что в прогонах CI №4–№5). Kotlin и Compose-компилятор здесь — встроенные в AGP 9:
// компиляцию Compose включает buildFeatures.compose.
plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "com.ssh.scanner.sample"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.ssh.scanner.sample"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    // Версию подменяет композитный билд — важны только координаты.
    implementation("com.ssh.scanner:scanner-ui:0.1.0-SNAPSHOT")
    // Явные координаты вместо DSL плагина Compose Multiplatform: DSL приезжает вместе
    // с плагином, а плагин — вместе с KGP (см. комментарий к plugins выше).
    implementation(libs.jetbrains.compose.runtime)
    implementation(libs.jetbrains.compose.foundation)
    implementation(libs.jetbrains.compose.ui)
    implementation(libs.jetbrains.compose.material3)
    implementation(libs.androidx.activity.compose)
}
