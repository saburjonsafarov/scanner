// Сэмпл — чистый Android-модуль и ОТДЕЛЬНЫЙ билд (см. settings.gradle.kts рядом).
// Открывать его в IDE как самостоятельный проект: File → Open → каталог sample-android.
//
// Плагинов ровно два, и это официальный шаблон app-модуля на AGP 9: Kotlin — встроенный
// в AGP, Compose-компилятор подключается плагином kotlin.plugin.compose (AGP сам требует
// его при buildFeatures.compose — прогон CI №10). Плагин тянет KGP в класспут модуля,
// но здесь это безопасно: KGP и AGP живут в ОДНОМ лоадере standalone-билда. Опасна была
// другая конфигурация — KGP в родительском лоадере корня, где классов AGP нет (раны №4–№5);
// поэтому сэмпл и вынесен в отдельный билд.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
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
